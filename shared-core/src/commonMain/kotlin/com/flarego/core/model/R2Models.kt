package com.flarego.core.model

import com.flarego.core.ports.ByteSource
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

/**
 * Listing filter for one bucket. A single-character [delimiter] groups keys into
 * common prefixes instead of listing every key below them.
 */
data class R2ObjectFilter(
    val prefix: String? = null,
    val delimiter: String? = null,
    val startAfter: String? = null,
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
 * The pinned schema states that this endpoint is **not** atomic: when any key fails,
 * `success` is false and `errors` carries one entry per failed key, while the other keys
 * are still deleted. A partial result is therefore never reported as complete, and its
 * true set is only known after a read-back — never by replaying the request.
 *
 * [confirmedDeleted] is what the provider echoed back as deleted; [failedCount] is the
 * number of keys it reported as failed.
 */
data class R2BulkDeleteOutcome(
    val requestedCount: Int,
    val confirmedDeleted: Int?,
    val failedCount: Int?,
    val uncertain: Boolean,
) {
    init {
        require(requestedCount in 1..R2_MAX_BULK_DELETE) { "批量删除数量超出允许范围" }
        require(confirmedDeleted == null || confirmedDeleted in 0..requestedCount) { "确认删除数不得超过请求数" }
        require(failedCount == null || failedCount in 0..requestedCount) { "失败数不得超过请求数" }
        require(confirmedDeleted == null || failedCount == null || confirmedDeleted + failedCount <= requestedCount) {
            "确认删除数与失败数之和不得超过请求数"
        }
    }
}

/** Official `r2-delete-objects` bound: 1 to 1000 keys in one request. */
const val R2_MAX_BULK_DELETE: Int = 1000

/** A single REST upload is never a multipart upload; larger objects belong to the S3 P5 batch. */
const val R2_MAX_SINGLE_OBJECT_BYTES: Long = 5L * 1024 * 1024 * 1024

/**
 * One bucket write. The pinned schema has no body for the `PUT`/`PATCH` variants: the
 * optional default storage class travels in the `cf-r2-storage-class` header, and only
 * the `POST` variant carries a JSON body.
 */
sealed interface R2BucketWrite {
    val scope: AccountScope

    data class Create(override val scope: AccountScope, val input: R2BucketCreateInput) : R2BucketWrite

    data class CreateByName(override val scope: AccountScope, val name: String, val storageClass: String?) : R2BucketWrite

    /** The only mutable bucket property: the default storage class for newly uploaded objects. */
    data class PatchStorageClass(override val scope: AccountScope, val name: String, val storageClass: String) : R2BucketWrite

    /**
     * [typedName] must equal [name]: deleting a bucket destroys every object and its
     * configuration, so the caller has to carry the name the user actually typed.
     */
    data class Delete(override val scope: AccountScope, val name: String, val typedName: String) : R2BucketWrite
}

/** Display only: never a source of executable payload or ownership evidence. */
data class R2ChangePreview(
    val scope: AccountScope,
    val bucket: String,
    val impact: String,
    val concurrencyWarning: String,
)

/** Snapshot of a write, so a later mutation of the caller's draft cannot change what is sent. */
internal fun freezeR2BucketWrite(write: R2BucketWrite): R2BucketWrite = when (write) {
    is R2BucketWrite.Create -> write.copy(input = write.input.copy())
    is R2BucketWrite.CreateByName -> write.copy()
    is R2BucketWrite.PatchStorageClass -> write.copy()
    is R2BucketWrite.Delete -> write.copy()
}

internal fun r2BucketWriteKey(write: R2BucketWrite): String = when (write) {
    is R2BucketWrite.Create -> "r2-create-bucket"
    is R2BucketWrite.CreateByName -> "r2-create-bucket-by-name"
    is R2BucketWrite.PatchStorageClass -> "r2-patch-bucket"
    is R2BucketWrite.Delete -> "r2-delete-bucket"
}

/**
 * One object write.
 *
 * [Upload] holds a **source factory** rather than a stream: the factory is invoked only at
 * the single send, so a prepared-but-unconfirmed change never opens a file. The stream is
 * one-shot — the transport takes ownership of whatever the factory returns and closes it
 * on every outcome, so an upload can never be replayed.
 */
sealed interface R2ObjectWrite {
    val scope: AccountScope
    val bucket: String

    data class Upload(
        override val scope: AccountScope,
        override val bucket: String,
        val key: String,
        val source: suspend () -> ByteSource,
        val length: Long,
        val contentType: String?,
    ) : R2ObjectWrite

    data class Delete(override val scope: AccountScope, override val bucket: String, val key: String) : R2ObjectWrite

    data class BulkDelete(override val scope: AccountScope, override val bucket: String, val keys: List<String>) : R2ObjectWrite
}

internal fun r2ObjectWriteKey(write: R2ObjectWrite): String = when (write) {
    is R2ObjectWrite.Upload -> "r2-put-object"
    is R2ObjectWrite.Delete -> "r2-delete-object"
    is R2ObjectWrite.BulkDelete -> "r2-delete-objects"
}
