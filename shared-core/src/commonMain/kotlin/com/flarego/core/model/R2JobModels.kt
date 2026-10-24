package com.flarego.core.model

/**
 * The two reviewed bucket-job kinds of batch C.
 *
 * Nothing here is loaded from a schema at runtime. The wire value is the pinned discriminator; an
 * unknown provider value is never guessed into one of these entries.
 */
enum class R2JobType(val wireValue: String) {
    PREFIX_DELETE("prefixDelete"),
    STORAGE_CLASS_MIGRATION("storageClassMigration"),
    ;

    companion object {
        fun fromWire(value: String): R2JobType? = entries.firstOrNull { it.wireValue == value }
    }
}

/** Lifecycle status of a bucket background job. */
enum class R2JobStatus(val wireValue: String) {
    ENQUEUED("ENQUEUED"),
    RUNNING("RUNNING"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED"),
    CANCELLED("CANCELLED"),
    ;

    companion object {
        fun fromWire(value: String): R2JobStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

/** The two pinned storage classes of a storage-class migration. */
enum class R2StorageClass(val wireValue: String) {
    STANDARD("Standard"),
    INFREQUENT_ACCESS("InfrequentAccess"),
    ;

    companion object {
        fun fromWire(value: String): R2StorageClass? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * The prefix-delete details of one job.
 *
 * [prefix] is the provider's value verbatim; an empty prefix is the explicit whole-bucket mode and
 * is never trimmed or replaced. [isBucketClear] can be absent on older jobs and stays null then;
 * [deletedObjects] is a provider counter and is never filled in with a guessed 0.
 */
data class R2PrefixDeleteDetails(
    val prefix: String,
    val isBucketClear: Boolean?,
    val deletedObjects: Long?,
)

/**
 * The storage-class-migration details of one job.
 *
 * Every counter is optional because the pinned schema only requires the two classes and
 * `migratedObjects`; a missing count stays null instead of becoming 0. A terminal partial/failed
 * result therefore stays visible as its real numbers.
 */
data class R2StorageClassMigrationDetails(
    val sourceStorageClass: R2StorageClass?,
    val destinationStorageClass: R2StorageClass?,
    val migratedObjects: Long?,
    val migratedBytes: Long?,
    val migratedMetadataBytes: Long?,
    val skippedObjects: Long?,
    val skippedBytes: Long?,
    val failedObjects: Long?,
    val failedBytes: Long?,
)

/** The typed details discriminated by [R2JobType]. */
sealed interface R2JobDetails {
    data class PrefixDelete(val details: R2PrefixDeleteDetails) : R2JobDetails

    data class StorageClassMigration(val details: R2StorageClassMigrationDetails) : R2JobDetails
}

/**
 * A safe projection of one bucket job.
 *
 * The provider's raw result is never retained. Unknown/echoed members, an unknown `jobType` or an
 * unknown `status` keep the job display-only ([interpretable] = false) instead of being dropped and
 * rewritten. A job is read-only data; [interpretable] says whether this app understands it.
 */
data class R2BucketJob(
    val id: String?,
    val jobType: R2JobType?,
    val status: R2JobStatus?,
    val startTime: String?,
    val endTime: String?,
    val details: R2JobDetails?,
    val interpretable: Boolean,
    val readOnlyReason: String? = null,
)

/** One bounded page of bucket jobs; `nextContinuationToken` is provider text, never a total count. */
data class R2JobPage(
    val jobs: List<R2BucketJob>,
    val nextContinuationToken: String?,
    val interpretable: Boolean,
    val readOnlyReason: String? = null,
)

/**
 * The reviewed list filter.
 *
 * The pinned schema requires `jobType` whenever `status` is present. The page size bound is a
 * client safety budget (1..1000), not a provider total guarantee; a continuation token is used
 * verbatim and never trimmed.
 */
data class R2JobListQuery(
    val jobType: R2JobType? = null,
    val status: R2JobStatus? = null,
    val maxKeys: Int? = null,
    val continuationToken: String? = null,
) {
    init {
        if (status != null && jobType == null) throw invalidInputFailure()
        if (maxKeys != null && maxKeys !in 1..R2_JOB_MAX_PAGE_SIZE) throw invalidInputFailure()
        if (continuationToken != null) requireR2JobToken(continuationToken)
    }
}

/** The two explicitly-shaped job creation requests. There is no omitted-field default. */
sealed interface R2JobCreateRequest {
    /**
     * A prefix-delete request. A non-empty prefix must end in `/`; an empty prefix is the explicit
     * "empty the whole bucket" mode. The value is never trimmed, normalized or guessed.
     */
    data class PrefixDelete(val prefix: String) : R2JobCreateRequest {
        init {
            requireR2JobPrefix(prefix)
        }
    }

    /** A migration request that always sends both classes; the two must differ. */
    data class StorageClassMigration(
        val source: R2StorageClass,
        val destination: R2StorageClass,
    ) : R2JobCreateRequest {
        init {
            if (source == destination) throw invalidInputFailure()
        }
    }
}

/**
 * A read-only poll locator for a job whose creation receipt carried a trustworthy ID.
 *
 * It carries only the target and the job ID: never the request payload, confirmations or a command
 * that could be replayed. A missing/blank/untrusted ID produces no locator at all.
 */
data class R2JobLocator(val target: R2WriteTarget, val jobId: String)

/** Client-side bounds; these are not provider limits. */
const val R2_JOB_MAX_PAGE_SIZE: Int = 1000
const val R2_JOB_MAX_ID_CHARS: Int = 256
const val R2_JOB_MAX_PREFIX_CHARS: Int = 1024
const val R2_JOB_MAX_TOKEN_CHARS: Int = 2048

/**
 * A job prefix is kept verbatim: no trimming, no normalization. Non-empty values must end in `/`
 * (the pinned contract for a key prefix); an empty value is the explicit whole-bucket mode.
 */
internal fun requireR2JobPrefix(prefix: String) {
    if (prefix.length > R2_JOB_MAX_PREFIX_CHARS) throw invalidInputFailure()
    if (prefix.any { it.code < 0x20 || it.code == 0x7F }) throw invalidInputFailure()
    if (prefix.isNotEmpty() && !prefix.endsWith('/')) throw invalidInputFailure()
}

/** A continuation token is opaque provider text; it is used verbatim and never trimmed. */
internal fun requireR2JobToken(token: String) {
    if (token.isEmpty() || token.length > R2_JOB_MAX_TOKEN_CHARS) throw invalidInputFailure()
    if (token.any { it.code !in 0x21..0x7E }) throw invalidInputFailure()
}

/**
 * A job ID is addressed by appending one path segment. Only an opaque identifier that is safe as
 * exactly one segment is trustworthy: no `/` or `\` separator, no `?`/`#` query or fragment, no `%`
 * (an encoded separator is never decoded here), no control/space, and not the `.`/`..` traversal
 * segments. This is a client-side addressing rule, not a claim that the pinned schema requires a
 * UUID; a plain opaque provider ID stays valid.
 */
internal fun isTrustedR2JobId(jobId: String): Boolean {
    if (jobId.isEmpty() || jobId.length > R2_JOB_MAX_ID_CHARS) return false
    if (jobId.any { it.code !in 0x21..0x7E }) return false
    if (jobId.any { it == '/' || it == '\\' || it == '?' || it == '#' || it == '%' }) return false
    return jobId != "." && jobId != ".."
}

/** A job ID must be a trustworthy, addressable provider identity before it can be addressed or polled. */
internal fun requireR2JobId(jobId: String): String {
    if (!isTrustedR2JobId(jobId)) throw invalidInputFailure()
    return jobId
}

/**
 * One operator-frozen bucket-job creation command.
 *
 * The command is what the live editor holds in memory; it is never persisted, and a plan or a task
 * row only ever carries its fingerprint. A prefix delete (including the explicit whole-bucket clear)
 * cannot even be constructed without the operator's manual multipart statement: that statement is a
 * *precondition declaration* ("I handled active multipart uploads and paused writers outside this
 * app"), never evidence that the bucket is quiescent and never a substitute for the REST authority
 * proof, the preview or the deletion confirmations.
 */
sealed interface R2JobWrite {
    val target: R2WriteTarget
    val request: R2JobCreateRequest
    val kind: R2JobType

    /**
     * A prefix delete: `prefix` is the verbatim provider prefix (empty = the explicit whole-bucket
     * mode). [manualMpuHandled] must be a real operator statement; a missing one cannot be built.
     */
    data class PrefixDelete(
        override val target: R2WriteTarget,
        val prefix: String,
        val manualMpuHandled: Boolean,
    ) : R2JobWrite {
        init {
            if (!manualMpuHandled) throw invalidInputFailure()
        }

        override val request: R2JobCreateRequest = R2JobCreateRequest.PrefixDelete(prefix)

        override val kind: R2JobType = R2JobType.PREFIX_DELETE
    }

    /** A storage-class migration that always names both classes explicitly. */
    data class StorageClassMigration(
        override val target: R2WriteTarget,
        val source: R2StorageClass,
        val destination: R2StorageClass,
    ) : R2JobWrite {
        override val request: R2JobCreateRequest = R2JobCreateRequest.StorageClassMigration(source, destination)

        override val kind: R2JobType = R2JobType.STORAGE_CLASS_MIGRATION
    }
}
