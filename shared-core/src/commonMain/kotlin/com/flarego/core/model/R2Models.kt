package com.flarego.core.model

import kotlinx.serialization.json.JsonObject

/**
 * Provider document for one R2 bucket.
 *
 * [raw] keeps every provider field, including ones this app does not model yet, so
 * editing a known field never deletes unknown content.
 */
data class R2BucketDocument(val name: String, val scope: AccountScope, val raw: JsonObject)

/**
 * Creation input restricted to the official writable fields of `r2-create-bucket`
 * and `r2-create-bucket-by-name`. Read-only values such as `creation_date` never
 * appear here.
 */
data class R2BucketCreateInput(
    val name: String,
    val locationHint: String? = null,
    val storageClass: String? = null,
)

/** One object listing entry: provider metadata only, never object content. */
data class R2ObjectEntry(
    val key: String,
    val size: Long?,
    val etag: String?,
    val lastModified: String?,
    val raw: JsonObject,
)

/** The exact bucket and key a request targets; never a draft or a UI-supplied path. */
data class R2ObjectTarget(val scope: AccountScope, val bucket: String, val key: String)

/** Response head of one streamed download. The body only ever reaches a [ByteSink]. */
data class R2ObjectHead(
    val status: Int,
    val transferredBytes: Long,
    val etag: String?,
    val contentType: String?,
)

/**
 * Result of one bulk delete.
 *
 * The provider either applies the whole request or the outcome is unknown
 * ([uncertain]); a partially applied batch is never reported as complete.
 */
data class R2BulkDeleteOutcome(
    val requestedCount: Int,
    val confirmedDeleted: Int?,
    val uncertain: Boolean,
) {
    init {
        require(requestedCount in 1..R2_MAX_BULK_DELETE) { "批量删除数量超出允许范围" }
        require(confirmedDeleted == null || confirmedDeleted in 0..requestedCount) { "确认删除数不得超过请求数" }
    }
}

/** Official `r2-delete-objects` bound: 1 to 1000 keys in one request. */
const val R2_MAX_BULK_DELETE: Int = 1000
