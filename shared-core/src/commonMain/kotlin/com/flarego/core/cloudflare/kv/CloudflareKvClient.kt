package com.flarego.core.cloudflare.kv

import com.flarego.core.application.kv.KvValidation
import com.flarego.core.application.kv.KvBulkJson
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.protocol.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

class CloudflareKvClient(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
    private val transfers: TransferPort,
    private val contents: KvContentStore,
    private val nowSeconds: () -> Long,
    private val beforeMutation: (String, AccountScope, String?) -> Unit,
) : KvSession {
    override suspend fun listNamespaces(account: AccountScope, page: KvNamespacePageRequest): CloudPage<KvNamespace> {
        val base = collection(account)
        if (page.page < 1 || page.perPage !in 5..1000) throw invalidInputFailure()
        val strategy = PaginationStrategy.Number(page.page, page.perPage)
        val root = readEnvelope(gateway, id("list-namespaces"), "$base?${Pagination.query(strategy)}&order=${page.order.name}&direction=${page.direction.name}")
        val rows = root["result"] as? JsonArray ?: throw invalidResponse(false)
        val items = rows.map { namespace(it, account, false) }
        val paging = Pagination.decode(root, strategy)
        return CloudPage(items, paging.next, paging.total)
    }
    override suspend fun createNamespace(account: AccountScope, title: String): KvNamespace {
        val path = collection(account); KvValidation.title(title)
        val root = write("create-a-namespace", "POST", path, titleBody(title), account, null)
        return namespace(root["result"], account, true)
    }
    override suspend fun getNamespace(namespace: KvNamespaceRef): KvNamespace {
        val result = namespace(readEnvelope(gateway, id("get-a-namespace"), locator(namespace))["result"], namespace.account, false)
        if (result.ref != namespace) throw invalidResponse(false)
        return result
    }
    override suspend fun renameNamespace(namespace: KvNamespaceRef, title: String): KvWriteReceipt {
        val path = locator(namespace); KvValidation.title(title)
        write("rename-a-namespace", "PUT", path, titleBody(title), namespace.account, namespace.id)
        return KvWriteReceipt(KvAcceptance.ACCEPTED)
    }
    override suspend fun deleteNamespace(namespace: KvNamespaceRef): KvWriteReceipt {
        write("remove-a-namespace", "DELETE", locator(namespace), HttpBody.Empty, namespace.account, namespace.id)
        return KvWriteReceipt(KvAcceptance.ACCEPTED)
    }
    override suspend fun listKeys(namespace: KvNamespaceRef, page: KvKeyPageRequest): CloudPage<KvKeyEntry> {
        val path = locator(namespace)
        if (page.limit !in 10..1000 || page.seen.size > 10000 || page.prefix.encodeToByteArray().size > 512) throw invalidInputFailure()
        val query = "limit=${page.limit}&prefix=${encodePath(page.prefix)}" + (page.cursor?.let { "&cursor=${encodePath(it)}" } ?: "")
        val root = readEnvelope(gateway, id("list-a-namespace'-s-keys"), "$path/keys?$query")
        val rows = root["result"] as? JsonArray ?: throw invalidResponse(false)
        val keys = rows.map {
            val row = it as? JsonObject ?: throw invalidResponse(false)
            val key = string(row["name"], false)
            try { KvValidation.key(key) } catch (_: Exception) { throw invalidResponse(false) }
            val expiry = row["expiration"]?.let { value ->
                (value as? JsonPrimitive)?.takeIf { p -> !p.isString }?.longOrNull?.takeIf { n -> n >= 0 } ?: throw invalidResponse(false)
            }
            KvKeyEntry(key, expiry, row.containsKey("metadata"))
        }
        if (keys.map { it.key }.toSet().size != keys.size) throw invalidResponse(false)
        val metadata = Pagination.decode(root, PaginationStrategy.Cursor(page.cursor, page.seen))
        return CloudPage(keys, metadata.next, metadata.total)
    }
    override suspend fun readMetadata(target: KvKeyTarget): KvMetadata {
        val path = keyPath(target, "metadata")
        val root = readEnvelope(gateway, id("read-the-metadata-for-a-key"), path)
        val json = root["result"] ?: throw invalidResponse(false)
        return try { KvValidation.metadata(json.toString()) } catch (_: Exception) { throw invalidResponse(false) }
    }
    override suspend fun readValue(target: KvKeyTarget): KvDownload = download(
        "read-key-value-pair", HttpRequest("GET", keyPath(target, "values")), KV_MAX_VALUE_BYTES,
    )
    override suspend fun putValue(target: KvKeyTarget, input: KvValueInput): KvWriteReceipt {
        val path = keyPath(target, "values")
        KvValidation.expiry(input.expiry, nowSeconds())
        if (input.content.descriptor.length !in 0..KV_MAX_VALUE_BYTES) throw invalidInputFailure()
        val source = input.content.openVerified()
        var metadataBody: HttpBody.SensitiveJson? = null
        try {
            val value = HttpBody.Stream(source, input.content.descriptor.length, "application/octet-stream")
            val payload = if (input.metadata == null) value else {
                metadataBody = HttpBody.SensitiveJson(input.metadata.json.toString(), "application/json")
                HttpBody.Multipart(listOf(
                    MultipartPart("value", contentType = "application/octet-stream", body = value),
                    MultipartPart("metadata", contentType = "application/json", body = metadataBody),
                ))
            }
            // Authentication and any suspending preflight finished; recheck immediately before send.
            KvValidation.expiry(input.expiry, nowSeconds())
            val query = when (val expiry = input.expiry) {
                KvExpiry.Never -> ""
                is KvExpiry.Ttl -> "?expiration_ttl=${expiry.seconds}"
                is KvExpiry.Absolute -> "?expiration=${expiry.epochSeconds}"
            }
            write("write-key-value-pair-with-metadata", "PUT", path + query, payload, target.namespace.account, target.namespace.id)
            return KvWriteReceipt(KvAcceptance.ACCEPTED)
        } finally { source.close(); metadataBody?.discard() }
    }
    override suspend fun deleteValue(target: KvKeyTarget): KvWriteReceipt {
        write("delete-key-value-pair", "DELETE", keyPath(target, "values"), HttpBody.Empty, target.namespace.account, target.namespace.id)
        return KvWriteReceipt(KvAcceptance.ACCEPTED)
    }
    override suspend fun bulkRead(namespace: KvNamespaceRef, input: KvBulkReadInput): KvDownload {
        val path = locator(namespace); val keys = KvValidation.keys(input.keys, 100)
        val body = buildJsonObject {
            put("keys", JsonArray(keys.map(::JsonPrimitive))); put("type", input.type.name); put("withMetadata", input.withMetadata)
        }
        // Keys and options are safe repeatable read input. Full response always goes to encrypted storage.
        val frozen = input.copy(keys = keys)
        val downloaded = download("get-multiple-key-value-pairs", HttpRequest("POST", "$path/bulk/get", body.toString()), MAX_BULK_READ_RESPONSE)
        try {
            KvBulkJson.validateRead(downloaded.content, frozen)
            return downloaded
        } catch (cancel: CancellationException) { downloaded.content.close(); throw cancel
        } catch (_: Exception) { downloaded.content.close(); throw invalidResponse(false) }
    }
    override suspend fun bulkWrite(namespace: KvNamespaceRef, input: KvBulkWriteInput): KvWriteReceipt {
        val path = locator(namespace); val keys = KvValidation.keys(input.keys, 10000)
        if (input.content.descriptor.length !in 1 until KV_MAX_BULK_BYTES) throw invalidInputFailure()
        val verified = KvBulkJson.prepareWrite(input.content, nowSeconds())
        if (verified.keys != keys) throw invalidInputFailure()
        val source = input.content.openVerified()
        try {
            return bulkResult(write("write-multiple-key-value-pairs", "PUT", "$path/bulk", HttpBody.Stream(source, input.content.descriptor.length, "application/json"), namespace.account, namespace.id, allowPartial = true), keys)
        } finally { source.close() }
    }
    override suspend fun bulkDelete(namespace: KvNamespaceRef, keys: List<String>): KvWriteReceipt {
        val path = locator(namespace); val frozen = KvValidation.keys(keys, 10000)
        val body = HttpBody.SensitiveJson(JsonArray(frozen.map(::JsonPrimitive)).toString(), "application/json")
        try {
            return bulkResult(write("delete-multiple-key-value-pairs", "POST", "$path/bulk/delete", body, namespace.account, namespace.id, allowPartial = true), frozen)
        } finally { body.discard() }
    }

    private suspend fun keyPath(target: KvKeyTarget, kind: String): String {
        val path = locator(target.namespace); KvValidation.key(target.key)
        val encoded = encodePath(target.key)
        if (encoded != target.key && getNamespace(target.namespace).supportsUrlEncoding != true) {
            throw CloudFailure(CloudFailureCode.INVALID_INPUT, "此命名空间未确认支持键名 URL 编码，不能安全操作该键")
        }
        return "$path/$kind/$encoded"
    }
    private fun collection(account: AccountScope): String {
        ScopeGuard.requireOwned(account, connection)
        return "/accounts/${encodePath(account.accountId)}/storage/kv/namespaces"
    }
    private fun locator(namespace: KvNamespaceRef) = "${collection(namespace.account)}/${encodePath(KvValidation.namespace(namespace.id))}"
    private fun namespace(value: JsonElement?, account: AccountScope, writing: Boolean): KvNamespace {
        val row = value as? JsonObject ?: throw invalidResponse(writing)
        val id = string(row["id"], writing); val title = string(row["title"], writing)
        try { KvValidation.namespace(id); KvValidation.title(title) } catch (_: Exception) { throw invalidResponse(writing) }
        val encoding = row["supports_url_encoding"]?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull ?: throw invalidResponse(writing) }
        if (row["mode"] != null && row["mode"] != JsonNull) throw CloudFailure(CloudFailureCode.INVALID_INPUT, "此入口仅支持标准 KV 命名空间", uncertain = writing)
        return KvNamespace(KvNamespaceRef(account, id), title, encoding)
    }
    private fun string(value: JsonElement?, writing: Boolean) = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw invalidResponse(writing)
    private fun titleBody(title: String) = HttpBody.SensitiveJson(buildJsonObject { put("title", title) }.toString(), "application/json")
    private suspend fun write(suffix: String, method: String, path: String, body: HttpBody, account: AccountScope, namespaceId: String?, allowPartial: Boolean = false): JsonObject {
        try {
            beforeMutation(id(suffix), account, namespaceId)
            val response = gateway.send(id(suffix), HttpRequest(method, path, body))
            // Bulk errors may carry a valid partial receipt. No provider message is surfaced.
            val decoded = if (allowPartial && response.status in setOf(400, 422)) response.copy(status = 200) else response
            val root = ResponseDecoders.envelope(decoded, writing = true)
            if (!allowPartial && (root["success"] as? JsonPrimitive)?.booleanOrNull != true) throw invalidResponse(true)
            return root
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: CloudFailure) { throw failure
        } catch (_: Exception) { throw CloudFailure(CloudFailureCode.UNAVAILABLE, "操作结果未知，请核对后处理", uncertain = true)
        } finally { (body as? HttpBody.SensitiveJson)?.discard() }
    }
    private fun bulkResult(root: JsonObject, keys: List<String>): KvWriteReceipt {
        fun unknown() = KvWriteReceipt(KvAcceptance.UNKNOWN)
        val result = root["result"] as? JsonObject ?: return unknown()
        val count = (result["successful_key_count"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return unknown()
        val failed = (result["unsuccessful_keys"] as? JsonArray)?.map {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return unknown()
        } ?: return unknown()
        if (count !in 0..keys.size || failed.size != failed.toSet().size || failed.any { it !in keys } || count + failed.size != keys.size) return unknown()
        if (failed.isEmpty() && (root["success"] as? JsonPrimitive)?.booleanOrNull != true) return unknown()
        return KvWriteReceipt(if (failed.isEmpty()) KvAcceptance.ACCEPTED else KvAcceptance.PARTIAL, count, failed.toList())
    }
    private suspend fun download(suffix: String, request: HttpRequest, max: Long): KvDownload {
        val registration = ActionPolicies.registry.registration(id(suffix)) ?: throw invalidInputFailure()
        if (!registration.matches(request.method, request.path)) throw invalidInputFailure()
        val writer = contents.writer(max)
        try {
            val head = transfers.download(request, writer)
            if (head.status != 200) {
                // Map status without parsing or retaining any provider error body.
                ResponseDecoders.json(HttpResponse(head.status, "null"), writing = false)
                throw invalidResponse(false)
            }
            val content = writer.seal()
            if (content.descriptor.length != head.transferredBytes) { content.close(); throw invalidResponse(false) }
            return KvDownload(content, head.status, head.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.singleOrNull())
        } catch (cancelled: CancellationException) { writer.abort(); throw cancelled
        } catch (failure: CloudFailure) { writer.abort(); throw failure
        } catch (_: Exception) { writer.abort(); throw CloudFailure(CloudFailureCode.UNAVAILABLE, "读取内容失败，请重试") }
    }
    private fun id(suffix: String) = "workers-kv-namespace-$suffix"
    private companion object { const val MAX_BULK_READ_RESPONSE = 16L * 1024 * 1024 * 1024 }
}
