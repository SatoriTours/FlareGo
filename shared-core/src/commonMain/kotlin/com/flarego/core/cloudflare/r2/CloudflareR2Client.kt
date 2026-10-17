package com.flarego.core.cloudflare.r2

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.r2.R2_STORAGE_CLASSES
import com.flarego.core.application.r2.encodeR2ObjectKeyPath
import com.flarego.core.application.r2.validateR2BucketName
import com.flarego.core.application.r2.validateR2CreateInput
import com.flarego.core.application.r2.validateR2Delimiter
import com.flarego.core.application.r2.validateR2ObjectKey
import com.flarego.core.application.r2.validateR2ObjectKeyPrefix
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
import com.flarego.core.model.R2ObjectEntry
import com.flarego.core.model.R2ObjectFilter
import com.flarego.core.model.R2ObjectHead
import com.flarego.core.model.R2ObjectTarget
import com.flarego.core.model.ScopeGuard
import com.flarego.core.model.freezeR2BucketWrite
import com.flarego.core.model.invalidInputFailure
import com.flarego.core.model.r2BucketWriteKey
import com.flarego.core.model.scopeMismatchFailure
import com.flarego.core.ports.ByteSink
import com.flarego.core.ports.R2ManagementSession
import com.flarego.core.ports.TransferPort
import kotlinx.coroutines.CancellationException
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
) : R2ManagementSession {

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
        val receipt = perform(R2BucketWrite.Delete(account, name)) {}.receipt
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
        if (head.status !in 200..299) {
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

    private fun requireRegistered(key: String, request: HttpRequest) {
        val entry = ActionPolicies.registry.registration(key) ?: throw invalidInputFailure()
        if (!entry.matches(request.method, request.path)) throw invalidInputFailure()
    }

    private fun requireStorageClass(value: String?) {
        if (value != null && value !in R2_STORAGE_CLASSES) throw invalidInputFailure()
    }

    private companion object {
        const val MAX_PAGE_SIZE = 1000
        const val STORAGE_CLASS_HEADER = "cf-r2-storage-class"
        const val STATUS_ACCEPTED = 202
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

private fun objectIoFailure() =
    CloudFailure(CloudFailureCode.UNAVAILABLE, "对象传输没有完成，请检查网络后重试；临时文件不会被当作结果")

private fun unknownResult() =
    CloudFailure(CloudFailureCode.UNKNOWN_RESULT, "操作结果尚未确认，请先核对云端状态，不要重复发送", uncertain = true)
