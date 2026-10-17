package com.flarego.ui.r2

data class R2BucketRow(val name: String, val location: String?, val storageClass: String?, val createdAt: String?)
data class R2ObjectRow(val key: String, val size: Long?, val etag: String?, val lastModified: String?)

sealed interface R2BucketIntent {
    data class NameChanged(val value: String) : R2BucketIntent
    data class LocationHintChanged(val value: String?) : R2BucketIntent
    data class StorageClassChanged(val value: String) : R2BucketIntent
    object SubmitCreate : R2BucketIntent
    data class RequestDelete(val name: String) : R2BucketIntent
    data class TypedDeleteNameChanged(val value: String) : R2BucketIntent
    object ConfirmDelete : R2BucketIntent
    object CancelDelete : R2BucketIntent
    data class OpenBucket(val name: String) : R2BucketIntent
    object LoadMore : R2BucketIntent
    object Refresh : R2BucketIntent
}

data class R2BucketListState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val rows: List<R2BucketRow> = emptyList(),
    val nextCursor: String? = null,
    val draftName: String = "",
    val draftLocationHint: String? = null,
    val draftStorageClass: String = "Standard",
    val locationHints: List<String> = listOf("apac", "eeur", "enam", "weur", "wnam", "oc"),
    val storageClasses: List<String> = listOf("Standard", "InfrequentAccess"),
    val pendingDeleteName: String? = null,
    val typedDeleteName: String = "",
    val validationMessage: String? = null,
)

sealed interface R2ObjectIntent {
    data class PrefixChanged(val value: String) : R2ObjectIntent
    data class RequestDownload(val key: String) : R2ObjectIntent
    data class RequestUpload(val key: String) : R2ObjectIntent
    data class UploadKeyChanged(val value: String) : R2ObjectIntent
    object ConfirmUpload : R2ObjectIntent
    object CancelUpload : R2ObjectIntent
    data class RequestDelete(val key: String) : R2ObjectIntent
    data class TypedDeleteKeyChanged(val value: String) : R2ObjectIntent
    object ConfirmDelete : R2ObjectIntent
    object CancelDelete : R2ObjectIntent
    object SelectAllVisible : R2ObjectIntent
    object ClearSelection : R2ObjectIntent
    data class ToggleSelection(val key: String) : R2ObjectIntent
    object RequestBulkDelete : R2ObjectIntent
    object ConfirmBulkDelete : R2ObjectIntent
    object CancelBulkDelete : R2ObjectIntent
    object LoadMore : R2ObjectIntent
    object Refresh : R2ObjectIntent
    object BackToBuckets : R2ObjectIntent
}

data class R2ObjectListState(
    val bucket: String,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val rows: List<R2ObjectRow> = emptyList(),
    val nextCursor: String? = null,
    val prefix: String = "",
    val selected: Set<String> = emptySet(),
    val uploadKey: String = "",
    val pendingDeleteKey: String? = null,
    val typedDeleteKey: String = "",
    val pendingBulkDelete: Boolean = false,
    val validationMessage: String? = null,
    val notice: String? = null,
)

/** 仅展示用；不可执行负载，也不代表写权限。 */
data class R2PreviewUi(
    val title: String,
    val bucket: String,
    val impact: String,
    val concurrencyWarning: String,
    val requiresTypedText: String? = null,
)
