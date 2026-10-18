package com.flarego.core.cloudflare.r2

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.r2.R2_STORAGE_CLASSES
import com.flarego.core.application.r2.encodeR2ObjectKeyPath
import com.flarego.core.application.r2.validateR2BucketName
import com.flarego.core.application.r2.validateR2CreateInput
import com.flarego.core.application.r2.validateR2Delimiter
import com.flarego.core.application.r2.validateR2ObjectKey
import com.flarego.core.application.r2.validateR2ObjectKeyPrefix
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.encodePath
import com.flarego.core.cloudflare.protocol.ActionPolicies
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.protocol.Pagination
import com.flarego.core.cloudflare.protocol.PaginationStrategy
import com.flarego.core.cloudflare.protocol.ResponseDecoders
import com.flarego.core.cloudflare.protocol.invalidResponse
import com.flarego.core.cloudflare.protocol.readEnvelope
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.CloudPage
import com.flarego.core.model.Connection
import com.flarego.core.model.PageRequest
import com.flarego.core.model.PageToken
import com.flarego.core.model.R2BucketCreateInput
import com.flarego.core.model.R2BucketDocument
import com.flarego.core.model.R2BucketWrite
import com.flarego.core.model.R2BulkDeleteOutcome
import com.flarego.core.model.R2_MAX_BULK_DELETE
import com.flarego.core.model.R2_MAX_SINGLE_OBJECT_BYTES
import com.flarego.core.model.R2ObjectEntry
import com.flarego.core.model.R2ObjectFilter
import com.flarego.core.model.R2ObjectHead
import com.flarego.core.model.R2ObjectTarget
import com.flarego.core.model.R2ObjectWrite
import com.flarego.core.model.ScopeGuard
import com.flarego.core.model.freezeR2BucketWrite
import com.flarego.core.model.invalidInputFailure
import com.flarego.core.model.r2BucketWriteKey
import com.flarego.core.model.r2ObjectWriteKey
import com.flarego.core.model.scopeMismatchFailure
import com.flarego.core.ports.ByteSink
import com.flarego.core.ports.ByteSource
import com.flarego.core.ports.R2ManagementSession
import com.flarego.core.ports.R2ObjectSession
import com.flarego.core.ports.R2Session
import com.flarego.core.ports.TransferPort
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * R2 bucket control plane over the P1 gateway and transport: fixed host, bounded
 * response handling and the handwritten policy registry.
 *
 * The pinned schema fixes two shapes that are easy to get wrong:
 * - the bucket list lives under `result.buckets`, not in a top-level array;
 * - the `PUT`/`PATCH` write variants have **no** body and take the default storage
 *   class from the required `cf-r2-storage-class` header.
 */
class CloudflareR2Client(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
    private val transfers: TransferPort,
    // Trusted integration seam. AuthorizedR2Session installs a proof-bound final guard;
    // raw fixture clients intentionally retain their typed-transport-only semantics.
    private val beforeMutation: () -> Unit = {},
) : R2Session {

    override suspend fun listBuckets(account: AccountScope, page: PageRequest): CloudPage<R2BucketDocument> {
        ScopeGuard.requireOwned(account, connection)
        // The provider paginates buckets with cursors, so a numeric token is a caller bug.
        if (page.limit !in 1..MAX_PAGE_SIZE || page.token is PageToken.Number) throw invalidInputFailure()
        val paging = PaginationStrategy.Cursor((page.token as? PageToken.Cursor)?.value)
        val root = readEnvelope(
            gateway,
            "r2-list-buckets",
            "/accounts/${encodePath(account.accountId)}/r2/buckets?${Pagination.query(paging, page.limit)}",
        )
        val result = root["result"] as? JsonObject ?: throw invalidResponse(false)
        val rows = result["buckets"] as? JsonArray ?: throw invalidResponse(false)
        val items = rows.map { decodeBucket(it as? JsonObject ?: throw invalidResponse(false), account, false) }
        val metadata = Pagination.decode(root, paging)
        return CloudPage(items, metadata.next, metadata.total)
    }

    override suspend fun getBucket(account: AccountScope, name: String): R2BucketDocument {
        ScopeGuard.requireOwned(account, connection)
        val validated = validateR2BucketName(name)
        val root = readEnvelope(gateway, "r2-get-bucket", locator(account, validated))
        return decodeBucket(root["result"] as? JsonObject ?: throw invalidResponse(false), account, false)
    }

    override suspend fun createBucket(account: AccountScope, input: R2BucketCreateInput): R2BucketDocument =
        perform(R2BucketWrite.Create(account, input)) {}.details ?: throw unknownResult()

    override suspend fun createBucketByName(account: AccountScope, name: String, storageClass: String?): R2BucketDocument =
        perform(R2BucketWrite.CreateByName(account, name, storageClass)) {}.details ?: throw unknownResult()

    override suspend fun patchBucketStorageClass(account: AccountScope, name: String, storageClass: String): R2BucketDocument =
        perform(R2BucketWrite.PatchStorageClass(account, name, storageClass)) {}.details ?: throw unknownResult()

    override suspend fun deleteBucket(account: AccountScope, name: String) {
        // The typed transport port carries name == typedName; the destructive product
        // confirmation (the user's typed name) is enforced by R2Controller.
        val receipt = perform(R2BucketWrite.Delete(account, name, name)) {}.receipt
        if (receipt !is ExecutionReceipt.Completed) throw unknownResult()
    }

    override suspend fun listObjects(
        account: AccountScope,
        bucket: String,
        filter: R2ObjectFilter,
        page: PageRequest,
    ): CloudPage<R2ObjectEntry> {
        ScopeGuard.requireOwned(account, connection)
        val name = validateR2BucketName(bucket)
        if (page.limit !in 1..MAX_PAGE_SIZE || page.token is PageToken.Number) throw invalidInputFailure()
        val paging = PaginationStrategy.Cursor((page.token as? PageToken.Cursor)?.value)
        val query = buildString {
            append(Pagination.query(paging, page.limit))
            filter.prefix?.let { append("&prefix=").append(encodePath(validateR2ObjectKeyPrefix(it))) }
            filter.delimiter?.let { append("&delimiter=").append(encodePath(validateR2Delimiter(it))) }
            filter.startAfter?.let { append("&start_after=").append(encodePath(validateR2ObjectKeyPrefix(it))) }
        }
        val root = readEnvelope(gateway, "r2-list-objects", "${locator(account, name)}/objects?$query")
        val rows = root["result"] as? JsonArray ?: throw invalidResponse(false)
        val items = rows.map { decodeObject(it as? JsonObject ?: throw invalidResponse(false), false) }
        val metadata = Pagination.decode(root, paging)
        return CloudPage(items, metadata.next, metadata.total)
    }

    override suspend fun downloadObject(target: R2ObjectTarget, sink: ByteSink): R2ObjectHead {
        ScopeGuard.requireOwned(target.scope, connection)
        val bucket = validateR2BucketName(target.bucket)
        val key = validateR2ObjectKey(target.key)
        // Independent ownership proof before the single streaming send.
        getBucket(target.scope, bucket)
        val request = HttpRequest("GET", "${locator(target.scope, bucket)}/objects/${encodeR2ObjectKeyPath(key)}")
        // A streamed download bypasses the gateway, so the policy match is enforced here.
        requireRegistered("r2-get-object", request)
        val head =
            try {
                transfers.download(request, sink)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: CloudFailure) {
                throw failure
            } catch (_: Exception) {
                throw objectIoFailure()
            }
        // This path requests the complete object without Range. Accepted/no-content or
        // partial-content responses must never publish an empty/truncated user file.
        if (head.status != 200) {
            throw CloudFailure(
                when (head.status) {
                    403 -> CloudFailureCode.PERMISSION_REQUIRED
                    404 -> CloudFailureCode.NOT_FOUND
                    429 -> CloudFailureCode.RATE_LIMITED
                    else -> CloudFailureCode.INVALID_RESPONSE
                },
                "对象下载未被接受，请检查权限后重试",
            )
        }
        return R2ObjectHead(
            status = head.status,
            transferredBytes = head.transferredBytes,
            etag = headerValue(head.headers, "ETag"),
            contentType = headerValue(head.headers, "Content-Type"),
        )
    }

    override suspend fun uploadObject(
        target: R2ObjectTarget,
        source: ByteSource,
        length: Long,
        contentType: String?,
        contentFingerprint: String,
    ): R2ObjectEntry {
        val owned = R2OwnedSource(source)
        try {
            val result =
                performObject(
                    R2ObjectWrite.Upload(
                        target.scope,
                        target.bucket,
                        target.key,
                        { owned },
                        length,
                        contentType,
                        contentFingerprint,
                    ),
                ) {}
            return result.details ?: throw unknownResult()
        } finally {
            // Idempotent wrapper: pre-send rejection and transport cleanup share one owner.
            owned.close()
        }
    }

    override suspend fun deleteObject(target: R2ObjectTarget) {
        // The typed transport port carries key == typedKey; the destructive product
        // confirmation (the user's typed key) is enforced by R2Controller.
        val receipt = performObject(R2ObjectWrite.Delete(target.scope, target.bucket, target.key, target.key)) {}.receipt
        if (receipt !is ExecutionReceipt.Completed) throw unknownResult()
    }

    override suspend fun deleteObjects(account: AccountScope, bucket: String, keys: List<String>): R2BulkDeleteOutcome {
        val result = performObject(R2ObjectWrite.BulkDelete(account, bucket, keys)) {}
        return result.outcome ?: throw unknownResult()
    }

    override suspend fun writeObject(write: R2ObjectWrite, beforeSend: (R2ObjectEntry?) -> Unit): ExecutionReceipt =
        try {
            performObject(write, beforeSend).receipt
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CloudFailure) {
            if (failure.uncertain) ExecutionReceipt.Unknown() else throw failure
        }

    private data class ObjectResult(
        val details: R2ObjectEntry?,
        val receipt: ExecutionReceipt,
        val outcome: R2BulkDeleteOutcome? = null,
    )

    private suspend fun performObject(write: R2ObjectWrite, beforeSend: (R2ObjectEntry?) -> Unit): ObjectResult {
        ScopeGuard.requireOwned(write.scope, connection)
        val bucket = validateR2BucketName(write.bucket)
        // Independent ownership proof of the bucket before any object request.
        getBucket(write.scope, bucket)
        val key = when (write) {
            is R2ObjectWrite.Upload -> validateR2ObjectKey(write.key)
            is R2ObjectWrite.Delete -> validateR2ObjectKey(write.key)
            is R2ObjectWrite.BulkDelete -> null
        }
        val keys = when (write) {
            is R2ObjectWrite.BulkDelete -> {
                if (write.keys.size !in 1..R2_MAX_BULK_DELETE) throw invalidInputFailure()
                write.keys.map { validateR2ObjectKey(it) }
            }
            else -> emptyList()
        }
        if (write is R2ObjectWrite.Delete && (write.typedKey.isBlank() || write.typedKey != write.key)) {
            throw invalidInputFailure()
        }
        if (write is R2ObjectWrite.Upload && !write.contentFingerprint.matches(SHA256)) throw invalidInputFailure()
        if (write is R2ObjectWrite.Upload && (write.length < 0 || write.length > R2_MAX_SINGLE_OBJECT_BYTES)) {
            throw CloudFailure(
                CloudFailureCode.UNSUPPORTED_CAPABILITY,
                "单个对象超过 REST 直传上限，分片上传属于后续批次",
            )
        }
        // The shape (method and path) is validated and policy-matched *before* an upload
        // source is ever opened, so a rejected request cannot leak a stream.
        val shape =
            when (write) {
                is R2ObjectWrite.Upload -> HttpRequest("PUT", "${locator(write.scope, bucket)}/objects/${encodeR2ObjectKeyPath(key!!)}")
                is R2ObjectWrite.Delete -> HttpRequest("DELETE", "${locator(write.scope, bucket)}/objects/${encodeR2ObjectKeyPath(key!!)}")
                is R2ObjectWrite.BulkDelete -> HttpRequest("DELETE", "${locator(write.scope, bucket)}/objects")
            }
        val operation = r2ObjectWriteKey(write)
        requireRegistered(operation, shape)
        var owned: R2OwnedSource? = null
        try {
            val request =
                when (write) {
                    is R2ObjectWrite.Upload -> {
                        val type = write.contentType ?: DEFAULT_OBJECT_CONTENT_TYPE
                        HttpBody.Text("", type) // Validate media type before opening a file.
                        val source = R2OwnedSource(write.source()).also { owned = it }
                        shape.copy(payload = HttpBody.Stream(source, write.length, type))
                    }
                    is R2ObjectWrite.BulkDelete ->
                        shape.copy(payload = HttpBody.Text(JsonArray(keys.map(::JsonPrimitive)).toString(), "application/json; charset=utf-8"))
                    is R2ObjectWrite.Delete -> shape
                }
            beforeSend(null)
            beforeMutation()
            val response = send(operation, request)
            return when (write) {
                is R2ObjectWrite.Upload -> {
                    val root = envelope(response)
                    val row = root["result"] as? JsonObject ?: throw invalidResponse(true)
                    val entry = decodeUploadedObject(row)
                    if (entry.key != key) throw invalidResponse(true)
                    ObjectResult(entry, ExecutionReceipt.Completed())
                }
                is R2ObjectWrite.Delete -> {
                    val root = envelope(response)
                    val row = root["result"] as? JsonObject ?: throw invalidResponse(true)
                    // The provider echoes the key it deleted; a mismatch is not this request's result.
                    if (row.optionalText("key") != key) throw invalidResponse(true)
                    ObjectResult(null, ExecutionReceipt.Completed())
                }
                is R2ObjectWrite.BulkDelete -> bulkDeleteResult(response, keys)
            }
        } finally { owned?.close() }
    }

    /**
     * The bulk delete response is a real provider result even when `success` is false:
     * the keys the provider did not report as failed were still deleted, and the true set
     * needs a read-back.
     */
    private fun bulkDeleteResult(response: HttpResponse, keys: List<String>): ObjectResult {
        val requested = keys.size
        val root = ResponseDecoders.envelope(response, writing = true)
        val success = (root["success"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw invalidResponse(true)
        val errors = root["errors"] as? JsonArray ?: throw invalidResponse(true)
        val echoed = root["result"] as? JsonArray
        val confirmedKeys = echoed?.let { rows ->
            rows.map { row -> (row as? JsonObject) ?: throw invalidResponse(true) }
                .map { row -> row.optionalText("key") ?: throw invalidResponse(true) }
        }
        val confirmed = confirmedKeys?.size
        if (confirmedKeys != null && (confirmedKeys.toSet().size != confirmedKeys.size || !keys.containsAll(confirmedKeys))) throw invalidResponse(true)
        if (success) {
            // Every requested key must be echoed back, or this is not a complete success.
            if (confirmed != requested || confirmedKeys?.toSet() != keys.toSet() || errors.isNotEmpty()) throw invalidResponse(true)
            return ObjectResult(
                details = null,
                receipt = ExecutionReceipt.Completed(),
                outcome = R2BulkDeleteOutcome(requested, confirmed, 0, uncertain = false),
            )
        }
        val failed = errors.size
        val receipt = if (failed in 1 until requested) ExecutionReceipt.Partial("部分对象未被删除，请核对后再操作") else ExecutionReceipt.Unknown()
        return ObjectResult(
            details = null,
            receipt = receipt,
            outcome = R2BulkDeleteOutcome(requested, confirmed, failed, uncertain = true),
        )
    }

    override suspend fun write(write: R2BucketWrite, beforeSend: (R2BucketDocument?) -> Unit): ExecutionReceipt =
        try {
            perform(freezeR2BucketWrite(write), beforeSend).receipt
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CloudFailure) {
            // A failure after a possible send is never a reason to replay it.
            if (failure.uncertain) ExecutionReceipt.Unknown() else throw failure
        }

    private data class Result(val details: R2BucketDocument?, val receipt: ExecutionReceipt)

    private suspend fun perform(write: R2BucketWrite, beforeSend: (R2BucketDocument?) -> Unit): Result {
        ScopeGuard.requireOwned(write.scope, connection)
        val name =
            when (write) {
                is R2BucketWrite.Create -> validateR2CreateInput(write.input).name
                is R2BucketWrite.CreateByName -> validateR2BucketName(write.name)
                is R2BucketWrite.PatchStorageClass -> validateR2BucketName(write.name)
                is R2BucketWrite.Delete -> validateR2BucketName(write.name)
            }
        when (write) {
            is R2BucketWrite.CreateByName -> requireStorageClass(write.storageClass)
            is R2BucketWrite.PatchStorageClass -> requireStorageClass(write.storageClass)
            else -> Unit
        }
        if (write is R2BucketWrite.Delete && (write.typedName.isBlank() || write.typedName != write.name)) {
            throw invalidInputFailure()
        }
        // Ownership and state evidence is read before the guard; a create has no bucket yet.
        val observed =
            when (write) {
                is R2BucketWrite.Create, is R2BucketWrite.CreateByName -> null
                else -> getBucket(write.scope, name)
            }
        // An independent provider read must confirm the target, never the caller's claim.
        if (observed != null && observed.name != name) throw scopeMismatchFailure()
        val request =
            when (write) {
                is R2BucketWrite.Create ->
                    HttpRequest(
                        "POST",
                        "/accounts/${encodePath(write.scope.accountId)}/r2/buckets",
                        buildJsonObject {
                            put("name", name)
                            write.input.locationHint?.let { put("locationHint", it) }
                            write.input.storageClass?.let { put("storageClass", it) }
                        }.toString(),
                    )
                is R2BucketWrite.CreateByName ->
                    HttpRequest(
                        "PUT",
                        locator(write.scope, name),
                        headers = write.storageClass?.let { mapOf(STORAGE_CLASS_HEADER to it) } ?: emptyMap(),
                    )
                is R2BucketWrite.PatchStorageClass ->
                    HttpRequest("PATCH", locator(write.scope, name), headers = mapOf(STORAGE_CLASS_HEADER to write.storageClass))
                is R2BucketWrite.Delete -> HttpRequest("DELETE", locator(write.scope, name))
            }
        // Everything that can suspend is before this guard.
        beforeSend(observed)
        beforeMutation()
        val response = send(r2BucketWriteKey(write), request)
        val root = envelope(response)
        if (response.status == STATUS_ACCEPTED) return Result(null, ExecutionReceipt.Accepted())
        return when (write) {
            // The delete response carries no id to compare, so one envelope is a provider
            // receipt, not proof of absence; the controller re-reads to confirm.
            is R2BucketWrite.Delete -> Result(null, ExecutionReceipt.Completed())
            else -> {
                val details = decodeBucket(root["result"] as? JsonObject ?: throw invalidResponse(true), write.scope, true)
                if (details.name != name) throw invalidResponse(true)
                Result(details, ExecutionReceipt.Completed())
            }
        }
    }

    private suspend fun send(key: String, request: HttpRequest): HttpResponse =
        try {
            gateway.send(key, request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: CloudFailure) {
            throw failure
        } catch (_: Exception) {
            throw unknownResult()
        }

    private fun envelope(response: HttpResponse): JsonObject {
        val root = ResponseDecoders.envelope(response, writing = true)
        if ((root["success"] as? JsonPrimitive)?.booleanOrNull != true) throw invalidResponse(true)
        return root
    }

    /**
     * Read-side decode keeps the provider string verbatim, because the strict write
     * pattern must never turn a legitimate provider value into a failed read.
     */
    private fun decodeBucket(row: JsonObject, account: AccountScope, writing: Boolean): R2BucketDocument =
        R2BucketDocument(row.text("name", writing), account, row)

    private fun decodeObject(row: JsonObject, writing: Boolean): R2ObjectEntry {
        val size = row["size"]?.let { element ->
            (element as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw invalidResponse(writing)
        }
        return R2ObjectEntry(row.text("key", writing), size, row.optionalText("etag"), row.optionalText("last_modified"), row)
    }

    /**
     * The upload response uses `r2_r2_put_object_result`, whose `size` is a **string** and
     * whose timestamp field is `uploaded` rather than `last_modified`.
     */
    private fun decodeUploadedObject(row: JsonObject): R2ObjectEntry {
        val size = row["size"]?.let { element ->
            val primitive = element as? JsonPrimitive ?: throw invalidResponse(true)
            if (primitive.isString) primitive.content.toLongOrNull() ?: throw invalidResponse(true)
            else primitive.longOrNull ?: throw invalidResponse(true)
        }
        if (size != null && size < 0) throw invalidResponse(true)
        return R2ObjectEntry(row.text("key", true), size, row.optionalText("etag"), row.optionalText("uploaded"), row)
    }

    private fun requireRegistered(key: String, request: HttpRequest) {
        val entry = ActionPolicies.registry.registration(key) ?: throw invalidInputFailure()
        if (!entry.matches(request.method, request.path)) throw invalidInputFailure()
    }

    private fun requireStorageClass(value: String?) {
        if (value != null && value !in R2_STORAGE_CLASSES) throw invalidInputFailure()
    }

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
        const val MAX_PAGE_SIZE = 1000
        const val STORAGE_CLASS_HEADER = "cf-r2-storage-class"
        const val STATUS_ACCEPTED = 202
        const val DEFAULT_OBJECT_CONTENT_TYPE = "application/octet-stream"
    }
}

private fun locator(account: AccountScope, name: String) =
    "/accounts/${encodePath(account.accountId)}/r2/buckets/${encodePath(name)}"

private fun JsonObject.text(key: String, writing: Boolean): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        ?: throw invalidResponse(writing)

private fun JsonObject.optionalText(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun headerValue(headers: Map<String, List<String>>, name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.singleOrNull()

/** One ownership latch across caller, final-guard rejection and real transport teardown. */
@OptIn(ExperimentalAtomicApi::class)
private class R2OwnedSource(source: ByteSource) : ByteSource {
    private val current = AtomicReference<ByteSource?>(source)
    override fun read(buffer: ByteArray) = (current.load() ?: throw objectIoFailure()).read(buffer)
    override fun close() {
        try { current.exchange(null)?.close() } catch (_: Exception) { throw objectIoFailure() }
    }
}

private fun objectIoFailure() =
    CloudFailure(CloudFailureCode.UNAVAILABLE, "对象传输没有完成，请检查网络后重试；临时文件不会被当作结果")

private fun unknownResult() =
    CloudFailure(CloudFailureCode.UNKNOWN_RESULT, "操作结果尚未确认，请先核对云端状态，不要重复发送", uncertain = true)
