package com.flarego.core.model

import com.flarego.core.ports.KvContent
import kotlinx.serialization.json.JsonElement

const val KV_MAX_KEY_BYTES = 512
const val KV_MAX_METADATA_BYTES = 1024
const val KV_MAX_VALUE_BYTES = 25L * 1024 * 1024
const val KV_MAX_BULK_BYTES = 100_000_000L
const val KV_EDITOR_BYTES = 65536

data class KvNamespaceRef(val account: AccountScope, val id: String)
data class KvNamespace(val ref: KvNamespaceRef, val title: String, val supportsUrlEncoding: Boolean?)
data class KvKeyTarget(val namespace: KvNamespaceRef, val key: String)
/** Metadata content is intentionally absent from public listing state. */
data class KvKeyEntry(val key: String, val expiration: Long?, val hasMetadata: Boolean)
data class KvKeyPageRequest(val limit: Int = 100, val prefix: String = "", val cursor: String? = null, val seen: Set<String> = emptySet())
enum class KvNamespaceOrder { id, title }
enum class KvSortDirection { asc, desc }
data class KvNamespacePageRequest(val page: Int = 1, val perPage: Int = 50, val order: KvNamespaceOrder = KvNamespaceOrder.id, val direction: KvSortDirection = KvSortDirection.asc)

sealed interface KvExpiry {
    data object Never : KvExpiry
    data class Absolute(val epochSeconds: Long) : KvExpiry
    data class Ttl(val seconds: Long) : KvExpiry
}

/** Owner-only sensitive document. It is never a UI state, task field or fingerprint. */
class KvMetadata internal constructor(internal val json: JsonElement) {
    /** Only a private content owner should consume this; never assign it to public UI state. */
    fun withJsonText(consume: (String) -> Unit) = consume(json.toString())
    override fun toString() = "KvMetadata(redacted)"
}

class KvValueInput(val content: KvContent, val expiry: KvExpiry, val metadata: KvMetadata?) {
    override fun toString() = "KvValueInput(redacted)"
}

enum class KvBulkReadType { text, json }
data class KvBulkReadInput(val keys: List<String>, val type: KvBulkReadType, val withMetadata: Boolean)
/** Constructed only after complete streaming validation of the immutable content. */
class KvBulkWriteInput internal constructor(val content: KvContent, keys: List<String>) {
    val keys: List<String> = keys.toList()
    override fun toString() = "KvBulkWriteInput(redacted)"
}
enum class KvAcceptance { ACCEPTED, PARTIAL, UNKNOWN }
data class KvWriteReceipt(val acceptance: KvAcceptance, val successfulCount: Int? = null, val failedKeys: List<String> = emptyList())
/** No automatic read-back can prove global propagation. */
data class KvDownload(val content: KvContent, val status: Int, val contentType: String?) {
    override fun toString() = "KvDownload(status=$status, content=redacted)"
}
