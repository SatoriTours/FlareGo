package com.flarego.core.application.r2

import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.OperationTask
import com.flarego.core.model.R2_JOB_MAX_ID_CHARS
import com.flarego.core.model.isTrustedR2JobId
import com.flarego.core.model.requireR2JobId
import com.flarego.core.model.requireR2NotificationQueueId
import com.flarego.core.model.R2ConfigurationKind
import com.flarego.core.model.R2JobType
import com.flarego.core.model.R2DomainKind
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.R2_JURISDICTIONS
import com.flarego.core.model.ResourceRef
import com.flarego.core.model.TaskState

/**
 * The one read-only interpretation of a persisted R2 configuration locator.
 *
 * Recovering a task may read this location, but it never restores an executable
 * command, an editor draft or a confirmation.
 */
data class R2ConfigurationLocation(
    val account: AccountScope,
    val bucket: String,
    val jurisdiction: String,
    /** Batch A/B1/domain family; null only for the shared group locator. */
    val kind: R2ConfigurationKind? = null,
    /** Batch B2 family, set instead of [kind] for a persisted domain write. */
    val domainKind: R2DomainKind? = null,
    /** The fixed canonical domain of a CUSTOM_DOMAIN locator; null for managed/collection locators. */
    val domain: String? = null,
    /** Batch B3 Sippy family; the locator carries no domain and no executable command. */
    val sippy: Boolean = false,
)

/**
 * Shared grouping and unsettled gating for every R2 write of this application.
 *
 * Batch A and batch B are different products but they address the same buckets, so
 * they must share one lock identity and one "previous write is not settled" gate.
 * A default-jurisdiction configuration target deliberately groups exactly like the
 * batch A bucket/object writes (`r2_bucket:<bucket>`); a non-default target must not
 * collide with a same-named default bucket in another jurisdiction.
 *
 * The locator vocabulary is fixed here and never loaded from a plan, a ledger or the UI:
 * - `r2_bucket` / `r2_object` are batch A locators and always mean the default jurisdiction;
 * - `r2_configuration` is a batch B locator whose external id is
 *   `<jurisdiction>/<bucket>` (the shared group) or `<jurisdiction>/<bucket>/<KIND>`
 *   (one persisted write reference).
 */
object R2TaskTargets {
    const val BUCKET_KIND: String = "r2_bucket"
    const val OBJECT_KIND: String = "r2_object"
    const val CONFIGURATION_KIND: String = "r2_configuration"

    /** Batch C bucket-job locator and its one family name. */
    const val JOB_KIND: String = "r2_job"
    const val JOB_FAMILY: String = "JOB"
    /** The two kind-specific job families: a job of one kind can never be verified as the other. */
    const val JOB_PREFIX_DELETE_FAMILY: String = "JOB_PREFIX_DELETE"
    const val JOB_MIGRATION_FAMILY: String = "JOB_MIGRATION"

    /** Batch C event-notification locator and the three action-specific families. */
    const val NOTIFICATION_KIND: String = "r2_notification"
    const val NOTIFICATION_APPEND_FAMILY: String = "NOTIFICATION_APPEND"
    const val NOTIFICATION_SELECTED_FAMILY: String = "NOTIFICATION_SELECTED"
    const val NOTIFICATION_ALL_FAMILY: String = "NOTIFICATION_ALL"

    /** The one Sippy family name of this app's persisted locator. */
    const val SIPPY_FAMILY: String = "SIPPY"

    const val DEFAULT_JURISDICTION: String = "default"

    val A_BUCKET_ACTIONS: Set<String> =
        setOf("r2-create-bucket", "r2-create-bucket-by-name", "r2-patch-bucket", "r2-delete-bucket")

    val A_OBJECT_ACTIONS: Set<String> = setOf("r2-put-object", "r2-delete-object", "r2-delete-objects")

    val A_ACTIONS: Set<String> = A_BUCKET_ACTIONS + A_OBJECT_ACTIONS

    /** Batch B1 write operations and the configuration family each one addresses. */
    val CONFIGURATION_ACTIONS: Map<String, R2ConfigurationKind> =
        mapOf(
            "r2-put-bucket-cors-policy" to R2ConfigurationKind.CORS,
            "r2-delete-bucket-cors-policy" to R2ConfigurationKind.CORS,
            "r2-put-bucket-lifecycle-configuration" to R2ConfigurationKind.LIFECYCLE,
            "r2-put-bucket-local-uploads-configuration" to R2ConfigurationKind.LOCAL_UPLOADS,
            "r2-put-bucket-lock-configuration" to R2ConfigurationKind.LOCK,
            "r2-delete-bucket-lock-configuration" to R2ConfigurationKind.LOCK,
        )

    val CONFIGURATION_WRITE_ACTIONS: Set<String> = CONFIGURATION_ACTIONS.keys

    /**
     * Batch B2 domain writes and the family each one persists.
     *
     * An `AddCustomDomain` task is located by the created domain, not by the collection it read
     * before the write, so a later reconcile verifies exactly that domain.
     */
    val DOMAIN_ACTIONS: Map<String, R2DomainKind> =
        mapOf(
            "r2-put-bucket-public-policy" to R2DomainKind.MANAGED_DOMAIN,
            "r2-add-custom-domain" to R2DomainKind.CUSTOM_DOMAIN,
            "r2-edit-custom-domain-settings" to R2DomainKind.CUSTOM_DOMAIN,
            "r2-delete-custom-domain" to R2DomainKind.CUSTOM_DOMAIN,
        )

    val DOMAIN_WRITE_ACTIONS: Set<String> = DOMAIN_ACTIONS.keys

    /**
     * Batch B3 Sippy writes.
     *
     * They share the R2 configuration locator with one fixed family name and no domain, and they
     * share this app's bucket lock and unsettled gate with A/B1/B2.
     */
    val SIPPY_ACTIONS: Set<String> =
        setOf("r2-put-bucket-sippy-config", "r2-delete-bucket-sippy-config")

    /**
     * Batch C write actions: the three reviewed operationIds of this batch.
     *
     * The notification delete operation covers two *intents* (a selected list and the explicit whole
     * queue clear); the intent is carried by the persisted locator family and by the plan's desired
     * state, never by a second registered operation.
     */
    const val JOB_CREATE_ACTION: String = "r2-create-bucket-job"
    const val NOTIFICATION_APPEND_ACTION: String = "r2-put-event-notification-config"
    const val NOTIFICATION_DELETE_ACTION: String = "r2-event-notification-delete-config"

    val JOB_ACTIONS: Set<String> = setOf(JOB_CREATE_ACTION)
    val NOTIFICATION_ACTIONS: Set<String> = setOf(NOTIFICATION_APPEND_ACTION, NOTIFICATION_DELETE_ACTION)
    val C_ACTIONS: Set<String> = JOB_ACTIONS + NOTIFICATION_ACTIONS

    /**
     * Every family one action's persisted locator may carry.
     *
     * Batch A/B1/B2/B3 have exactly one; a notification delete has the two mutually exclusive
     * deletion intents, and nothing else.
     */
    fun familiesOf(actionKey: String): Set<String> =
        when (actionKey) {
            in JOB_ACTIONS -> setOf(JOB_PREFIX_DELETE_FAMILY, JOB_MIGRATION_FAMILY)
            NOTIFICATION_APPEND_ACTION -> setOf(NOTIFICATION_APPEND_FAMILY)
            NOTIFICATION_DELETE_ACTION -> setOf(NOTIFICATION_SELECTED_FAMILY, NOTIFICATION_ALL_FAMILY)
            else -> familyOf(actionKey)?.let { setOf(it) } ?: emptySet()
        }

    /** The one family name of an action, used to compare a persisted locator with its task. */
    fun familyOf(actionKey: String): String? =
        CONFIGURATION_ACTIONS[actionKey]?.name ?: DOMAIN_ACTIONS[actionKey]?.name
            ?: if (actionKey in SIPPY_ACTIONS) SIPPY_FAMILY else null

    val ALL_WRITE_ACTIONS: Set<String> =
        A_ACTIONS + CONFIGURATION_WRITE_ACTIONS + DOMAIN_WRITE_ACTIONS + SIPPY_ACTIONS + C_ACTIONS

    val UNSETTLED_STATES: Set<TaskState> =
        setOf(
            TaskState.QUEUED,
            TaskState.RUNNING,
            TaskState.UNKNOWN,
            TaskState.PARTIAL,
            TaskState.RECONCILING,
            TaskState.ACCEPTED,
            TaskState.ACTION_REQUIRED,
        )

    /**
     * One stable resource group for a write target; batch A and default batch B share it.
     *
     * A non-default jurisdiction cannot reuse `r2_bucket:<bucket>`: that identity belongs to
     * the default-jurisdiction bucket, and a same-named bucket elsewhere must never be
     * serialized, blocked or unlocked together with it.
     */
    fun group(target: R2WriteTarget): ResourceRef =
        if (target.jurisdiction == DEFAULT_JURISDICTION) ResourceRef(target.account, BUCKET_KIND, target.bucket)
        else ResourceRef(target.account, CONFIGURATION_KIND, "${target.jurisdiction}/${target.bucket}")

    /** The persisted read-only locator of one configuration write. */
    fun reference(target: R2WriteTarget, kind: R2ConfigurationKind): ResourceRef =
        ResourceRef(target.account, CONFIGURATION_KIND, "${target.jurisdiction}/${target.bucket}/${kind.name}")

    /**
     * The persisted locator of one managed-domain write (no domain: the endpoint is the bucket's).
     */
    fun domainReference(target: R2WriteTarget): ResourceRef =
        ResourceRef(target.account, CONFIGURATION_KIND, "${target.jurisdiction}/${target.bucket}/${R2DomainKind.MANAGED_DOMAIN.name}")

    /**
     * The persisted locator of one single-domain write.
     *
     * The fixed canonical domain is part of the read-only identity, so two domains of one bucket can
     * never share a locator. This is a location, never an executable permission.
     */
    fun domainReference(target: R2WriteTarget, domain: String): ResourceRef =
        ResourceRef(
            target.account,
            CONFIGURATION_KIND,
            "${target.jurisdiction}/${target.bucket}/${R2DomainKind.CUSTOM_DOMAIN.name}/$domain",
        )

    /** The persisted read-only locator of one Sippy write. */
    fun sippyReference(target: R2WriteTarget): ResourceRef =
        ResourceRef(target.account, CONFIGURATION_KIND, "${target.jurisdiction}/${target.bucket}/$SIPPY_FAMILY")

    /**
     * The read-only locator of one bucket-job task.
     *
     * A job ID only exists after the single POST, so the persisted locator pins account (scope) +
     * jurisdiction + bucket + the job family; the trustworthy job ID itself is stored by the shared
     * executor in the task's already-persisted, shape-validated poll path. Together they are the
     * complete read-only identity — no payload, no confirmation, no command is stored, and a later
     * read must reproduce all of it (see [jobTask]) or it is refused.
     */
    fun jobReference(target: R2WriteTarget, kind: R2JobType): ResourceRef =
        ResourceRef(target.account, JOB_KIND, "${target.jurisdiction}/${target.bucket}/${jobFamilyOf(kind)}")

    /** The one family of a job kind; anything else is not a job locator of this app. */
    fun jobFamilyOf(kind: R2JobType): String =
        when (kind) {
            R2JobType.PREFIX_DELETE -> JOB_PREFIX_DELETE_FAMILY
            R2JobType.STORAGE_CLASS_MIGRATION -> JOB_MIGRATION_FAMILY
        }

    /**
     * The read-only locator of one event-notification write.
     *
     * The family carries the exact intent (append / selected deletion / whole-queue clear) and the
     * fourth segment the target queue. A queue ID is a single opaque path segment here: an ID
     * containing `/` cannot be addressed unambiguously, so such a write is refused instead of being
     * stored under a softened identity (`R2_NOTIFICATION_QUEUE_UNLOCATABLE`).
     */
    fun notificationReference(
        target: R2WriteTarget,
        queueId: String,
        intent: R2NotificationIntent,
    ): ResourceRef {
        val queue = requireR2NotificationQueueId(queueId)
        if (queue.contains('/') || queue == "." || queue == "..") throw unlocatableQueue()
        return ResourceRef(
            target.account,
            NOTIFICATION_KIND,
            "${target.jurisdiction}/${target.bucket}/${intent.family}/$queue",
        )
    }

    /** The parsed identity of a persisted job locator; null means "not a trustworthy job reference". */
    fun parseJob(reference: ResourceRef?): R2JobLocation? {
        val ref = reference ?: return null
        if (ref.kind != JOB_KIND) return null
        val account = ref.scope as? AccountScope ?: return null
        val segments = ref.externalId.split('/')
        if (segments.size != 3 && segments.size != 4) return null
        val kind =
            when (segments[2]) {
                JOB_PREFIX_DELETE_FAMILY -> R2JobType.PREFIX_DELETE
                JOB_MIGRATION_FAMILY -> R2JobType.STORAGE_CLASS_MIGRATION
                else -> return null
            }
        if (segments[0] !in R2_JURISDICTIONS) return null
        if (!isLegalBucket(segments[1])) return null
        val jobId = if (segments.size == 4) segments[3].takeIf(::isTrustedR2JobId) ?: return null else null
        return R2JobLocation(account, segments[1], segments[0], jobId, kind)
    }

    /**
     * The complete read-only identity of one persisted job task: its locator plus the trustworthy job
     * ID recovered from the task's own poll path.
     *
     * The poll path must be exactly `/accounts/<this account>/r2/buckets/<this bucket>/jobs/<id>`, the
     * ID must decode canonically from one path segment and be a trustworthy, addressable opaque ID.
     * Anything else yields null: a missing or foreign ID is never guessed from a list, a time or a
     * prefix.
     */
    fun jobTask(reference: ResourceRef?, pollPath: String?): R2JobTask? {
        val location = parseJob(reference) ?: return null
        if (location.jobId != null) return R2JobTask(location)
        val path = pollPath ?: return null
        val segments = path.substringBefore('?').split('/')
        if (segments.size != 8) return null
        if (segments[0].isNotEmpty() || segments[1] != "accounts" || segments[3] != "r2" ||
            segments[4] != "buckets" || segments[6] != "jobs"
        ) {
            return null
        }
        if (segments[2] != location.account.accountId) return null
        if (segments[5] != location.bucket) return null
        val jobId = decodeSinglePathSegment(segments[7]) ?: return null
        if (!isTrustedR2JobId(jobId)) return null
        return R2JobTask(location.copy(jobId = jobId))
    }

    /** The parsed identity of a persisted notification locator; null when it is not trustworthy. */
    fun parseNotification(reference: ResourceRef?): R2NotificationLocation? {
        val ref = reference ?: return null
        if (ref.kind != NOTIFICATION_KIND) return null
        val account = ref.scope as? AccountScope ?: return null
        val segments = ref.externalId.split('/')
        if (segments.size != 4) return null
        val intent = R2NotificationIntent.ofFamily(segments[2]) ?: return null
        if (segments[0] !in R2_JURISDICTIONS) return null
        if (!isLegalBucket(segments[1])) return null
        if (segments[3].isEmpty() || segments[3] == "." || segments[3] == "..") return null
        val queue = try {
            requireR2NotificationQueueId(segments[3])
        } catch (_: CloudFailure) {
            return null
        }
        return R2NotificationLocation(account, segments[1], segments[0], queue, intent)
    }

    /**
     * Whether an unsettled task still blocks this target.
     *
     * Fail closed: a known R2 write action with a missing, foreign-scope, mismatched or
     * unparsable locator blocks every R2 target of that account. A task of another product
     * is never reclassified as an R2 write, and a settled task never blocks.
     */
    fun blocks(task: OperationTask, target: R2WriteTarget): Boolean {
        if (task.state !in UNSETTLED_STATES) return false
        if (task.actionKey !in ALL_WRITE_ACTIONS) return false
        if (task.scope != target.account) return false
        val reference = task.resourceRef ?: return true
        if (reference.scope != task.scope) return true
        // A legal target name is required before any locator can be compared with it.
        if (!isLegalBucket(target.bucket)) return true
        return when (reference.kind) {
            BUCKET_KIND -> {
                if (task.actionKey !in A_BUCKET_ACTIONS) return true
                // Structural trust comes first: a malformed locator blocks every R2 target.
                if (!isLegalBucket(reference.externalId)) return true
                if (target.jurisdiction != DEFAULT_JURISDICTION) return false
                reference.externalId == target.bucket
            }
            OBJECT_KIND -> {
                if (task.actionKey !in A_OBJECT_ACTIONS) return true
                // The locator is exactly `<valid bucket>/<key or empty>`; the empty remainder is
                // the reviewed bulk-delete form and is never dropped.
                val separator = reference.externalId.indexOf('/')
                if (separator <= 0) return true
                val bucketPart = reference.externalId.substring(0, separator)
                val keyPart = reference.externalId.substring(separator + 1)
                if (!isLegalBucket(bucketPart)) return true
                if (keyPart.isNotEmpty() && !isLegalObjectKey(keyPart)) return true
                if (target.jurisdiction != DEFAULT_JURISDICTION) return false
                bucketPart == target.bucket
            }
            JOB_KIND -> {
                if (task.actionKey !in JOB_ACTIONS) return true
                val location = parseJob(reference) ?: return true
                location.jurisdiction == target.jurisdiction && location.bucket == target.bucket
            }
            NOTIFICATION_KIND -> {
                if (task.actionKey !in NOTIFICATION_ACTIONS) return true
                val location = parseNotification(reference) ?: return true
                // The locator's action-specific family must belong to this action, never another one.
                if (location.intent.family !in familiesOf(task.actionKey)) return true
                location.jurisdiction == target.jurisdiction && location.bucket == target.bucket
            }
            CONFIGURATION_KIND -> {
                val parts = parts(reference.externalId) ?: return true
                val family = parts.family ?: return true // A group locator is not a persisted write reference.
                // The persisted family must belong to this action, and a domain locator must carry a
                // canonical domain: anything else blocks instead of being compared loosely.
                if (family !in familiesOf(task.actionKey)) return true
                if (parts.domain != null && !isLegalDomain(parts.domain)) return true
                parts.jurisdiction == target.jurisdiction && parts.bucket == target.bucket
            }
            else -> true
        }
    }

    /** Read-only locator parsing; null means "not a trustworthy configuration reference". */
    fun parse(reference: ResourceRef?): R2ConfigurationLocation? {
        val ref = reference ?: return null
        if (ref.kind != CONFIGURATION_KIND) return null
        val account = ref.scope as? AccountScope ?: return null
        val parts = parts(ref.externalId) ?: return null
        val family = parts.family ?: return null
        val kind = R2ConfigurationKind.entries.firstOrNull { it.name == family }
        val domainKind = R2DomainKind.entries.firstOrNull { it.name == family }
        // A single-domain locator must carry a canonical domain; a malformed one is not trusted.
        if (domainKind == R2DomainKind.CUSTOM_DOMAIN && !isLegalDomain(parts.domain ?: return null)) return null
        return R2ConfigurationLocation(account, parts.bucket, parts.jurisdiction, kind, domainKind, parts.domain,
            sippy = family == SIPPY_FAMILY)
    }

    /**
     * Decodes exactly one path segment of a persisted poll path.
     *
     * Only canonical-shaped `%XX` escapes of printable ASCII are accepted, so a decoded separator or a
     * hidden dot segment can never be re-read as a fresh structure.
     */
    private fun decodeSinglePathSegment(segment: String): String? {
        if (segment.isEmpty() || segment.length > R2_JOB_MAX_ID_CHARS * 3) return null
        val out = StringBuilder()
        var index = 0
        while (index < segment.length) {
            val character = segment[index]
            if (character == '%') {
                if (index + 2 >= segment.length) return null
                val high = segment[index + 1].digitToIntOrNull(16) ?: return null
                val low = segment[index + 2].digitToIntOrNull(16) ?: return null
                val code = high * 16 + low
                if (code < 0x21 || code > 0x7E) return null
                out.append(code.toChar())
                index += 3
            } else {
                if (character.code !in 0x21..0x7E || character == '%') return null
                out.append(character)
                index++
            }
        }
        return out.toString()
    }

    private fun isLegalDomain(value: String): Boolean =
        try {
            validateR2DomainName(value)
            true
        } catch (_: com.flarego.core.model.CloudFailure) {
            false
        }

    private class Parts(
        val jurisdiction: String,
        val bucket: String,
        val family: String?,
        val domain: String? = null,
    )

    private fun isLegalBucket(name: String): Boolean =
        try {
            validateR2BucketName(name)
            true
        } catch (_: CloudFailure) {
            false
        }

    private fun isLegalObjectKey(key: String): Boolean =
        try {
            validateR2ObjectKey(key)
            true
        } catch (_: CloudFailure) {
            false
        }

    private fun unlocatableQueue(): CloudFailure =
        CloudFailure(
            CloudFailureCode.UNSUPPORTED_CAPABILITY,
            R2_NOTIFICATION_QUEUE_UNLOCATABLE,
        )

    /** `<jurisdiction>/<bucket>` (group) or `<jurisdiction>/<bucket>/<KIND>` (write reference). */
    private fun parts(externalId: String): Parts? {
        val segments = externalId.split('/')
        if (segments.size !in 2..4) return null
        val jurisdiction = segments[0]
        if (jurisdiction !in R2_JURISDICTIONS) return null
        val bucket = segments[1]
        if (!isLegalBucket(bucket)) return null
        if (segments.size == 2) return Parts(jurisdiction, bucket, null)
        val family = segments[2]
        if (family == SIPPY_FAMILY) {
            // The Sippy locator is exactly three segments and never carries a domain or a payload.
            return if (segments.size == 3) Parts(jurisdiction, bucket, SIPPY_FAMILY) else null
        }
        val configuration = R2ConfigurationKind.entries.firstOrNull { it.name == family }
        val domainKind = R2DomainKind.entries.firstOrNull { it.name == family }
        if (configuration == null && domainKind == null) return null
        if (configuration != null) {
            // Batch A/B1 locators keep exactly their three segments.
            return if (segments.size == 3) Parts(jurisdiction, bucket, configuration.name) else null
        }
        return when (domainKind) {
            R2DomainKind.MANAGED_DOMAIN, R2DomainKind.CUSTOM_DOMAINS ->
                if (segments.size == 3) Parts(jurisdiction, bucket, domainKind.name) else null
            // A single-domain locator always carries exactly the one canonical domain.
            R2DomainKind.CUSTOM_DOMAIN ->
                if (segments.size == 4 && segments[3].isNotEmpty()) Parts(jurisdiction, bucket, domainKind.name, segments[3]) else null
            null -> null
        }
    }
}

/** The parsed, read-only identity of one persisted bucket-job locator. */
data class R2JobLocation(
    val account: AccountScope,
    val bucket: String,
    val jurisdiction: String,
    /** Null only for the at-prepare locator, before the single POST existed. */
    val jobId: String? = null,
    /** The job kind pinned by the locator family. */
    val kind: R2JobType,
) {
    val target: R2WriteTarget get() = R2WriteTarget(account, bucket, jurisdiction)
}

/** The complete read-only identity of one persisted job task (locator + its own poll path). */
data class R2JobTask(val location: R2JobLocation) {
    val account: AccountScope get() = location.account

    val target: R2WriteTarget get() = location.target

    val jobId: String get() = checkNotNull(location.jobId) { "a job task always carries its ID" }

    val kind: R2JobType get() = location.kind
}

/** The exact deletion intent of one persisted notification locator. */
enum class R2NotificationIntent(val family: String) {
    APPEND(R2TaskTargets.NOTIFICATION_APPEND_FAMILY),
    SELECTED(R2TaskTargets.NOTIFICATION_SELECTED_FAMILY),
    ALL(R2TaskTargets.NOTIFICATION_ALL_FAMILY),
    ;

    companion object {
        fun ofFamily(family: String): R2NotificationIntent? = entries.firstOrNull { it.family == family }
    }
}

/** The parsed, read-only identity of one persisted event-notification locator. */
data class R2NotificationLocation(
    val account: AccountScope,
    val bucket: String,
    val jurisdiction: String,
    val queueId: String,
    val intent: R2NotificationIntent,
) {
    val target: R2WriteTarget get() = R2WriteTarget(account, bucket, jurisdiction)
}

/** Fixed message for a queue whose provider ID cannot be stored as one path segment. */
const val R2_NOTIFICATION_QUEUE_UNLOCATABLE: String =
    "该 Queue 的标识无法安全保存为只读定位，应用不会发送此写入；请先核对 Queue 配置"
