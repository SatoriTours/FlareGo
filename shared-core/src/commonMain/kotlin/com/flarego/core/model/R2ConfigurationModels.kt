package com.flarego.core.model

import kotlinx.serialization.json.JsonObject

/**
 * One R2 bucket configuration family of batch B1.
 *
 * The kind is a local routing and validation fact: it selects the reviewed endpoint,
 * the supported jurisdiction header and the read-only analysis. Later batches add
 * their own kinds to this list; nothing here is loaded from a schema, a ledger or the UI.
 */
enum class R2ConfigurationKind {
    CORS,
    LIFECYCLE,
    LOCAL_UPLOADS,
    LOCK,
}

/**
 * The pinned `cf-r2-jurisdiction` enum.
 *
 * Declaring a value never claims that the current account or token is entitled to it;
 * ownership is proven by an explicit scoped read, and unsupported targets are refused
 * before any request is built.
 */
val R2_JURISDICTIONS: Set<String> = setOf("default", "eu", "us", "fedramp", "fedramp-high")

/**
 * Client-side editing limits of one configuration document.
 *
 * These are safety limits of this app, not provider limits. A document above them stays
 * readable and is never truncated, re-serialized from a guess or written back.
 */
const val R2_MAX_CONFIGURATION_RULES: Int = 100

/** Largest complete configuration document, measured in UTF-8 bytes of its JSON text. */
const val R2_MAX_CONFIGURATION_DOCUMENT_BYTES: Int = 256 * 1024

/** The `allowed` object of one CORS rule: origins and methods are required by the schema. */
data class R2CorsAllowed(
    val methods: List<String>,
    val origins: List<String>,
    val headers: List<String> = emptyList(),
)

/**
 * One writable CORS rule.
 *
 * [id] and [maxAgeSeconds] are optional in the pinned schema and are omitted when absent,
 * never replaced by an invented default. [exposeHeaders] and [allowed] are always sent,
 * so an explicitly empty list stays an explicitly empty list.
 */
data class R2CorsRule(
    val id: String? = null,
    val allowed: R2CorsAllowed,
    val exposeHeaders: List<String> = emptyList(),
    val maxAgeSeconds: Long? = null,
)

/**
 * One lifecycle transition condition.
 *
 * The pinned lifecycle age field is `maxAge`, measured in seconds; the lock family uses a
 * different field name, so the two never share a type.
 */
sealed interface R2LifecycleCondition {
    data class Age(val maxAge: Long) : R2LifecycleCondition

    /**
     * An explicit UTC instant inside this app's writing subset: RFC3339 text with the `Z` zone,
     * a year in `1970..9999` and whole seconds `00..59`. A value outside that subset (including a
     * `:60` leap-second instant, which RFC3339 does permit) is never written by this app.
     */
    data class Date(val utcDate: String) : R2LifecycleCondition
}

/** Scope shared by every transition of one lifecycle rule. An empty prefix means the whole bucket. */
data class R2LifecycleConditions(val prefix: String)

/** One storage-class transition; the pinned schema only admits `InfrequentAccess` here. */
data class R2LifecycleStorageTransition(val condition: R2LifecycleCondition, val storageClass: String)

/**
 * One writable lifecycle rule.
 *
 * [deleteObjectsTransition] may carry a damage-capable age or date condition, so the whole
 * lifecycle `PUT` is registered as high risk. [abortMultipartUploadsTransition] is age-only
 * in the pinned schema.
 */
data class R2LifecycleRule(
    val id: String,
    val enabled: Boolean,
    val conditions: R2LifecycleConditions,
    val deleteObjectsTransition: R2LifecycleCondition? = null,
    val abortMultipartUploadsTransition: R2LifecycleCondition.Age? = null,
    val storageClassTransitions: List<R2LifecycleStorageTransition> = emptyList(),
)

/**
 * One lock rule condition.
 *
 * The pinned lock age field is `maxAgeSeconds` (seconds) and is deliberately a different
 * model than [R2LifecycleCondition]. [Indefinite] means the retention protection may last for
 * the object's lifetime: this app's only way to change lock rules is `r2-put-bucket-lock-configuration`
 * / `r2-delete-bucket-lock-configuration`, and changing the rules cannot promise that protection
 * already applied to existing objects is released immediately, so no removal is claimed to be
 * certain — see `docs/superpowers/specs/2026-10-17-p3-r2-management.md` §9.4.
 */
sealed interface R2LockCondition {
    data class Age(val maxAgeSeconds: Long) : R2LockCondition

    /**
     * An explicit UTC instant inside this app's writing subset: RFC3339 text with the `Z` zone,
     * a year in `1970..9999` and whole seconds `00..59`. A value outside that subset (including a
     * `:60` leap-second instant, which RFC3339 does permit) is never written by this app.
     */
    data class Date(val utcDate: String) : R2LockCondition

    data object Indefinite : R2LockCondition
}

/** One writable lock rule. An empty [prefix] protects every object in the bucket. */
data class R2LockRule(
    val id: String,
    val enabled: Boolean,
    val prefix: String = "",
    val condition: R2LockCondition,
)

/**
 * A provider configuration document for one target.
 *
 * [raw] keeps the provider's own result object verbatim, including fields this app does not
 * model yet. [editable] is false whenever the document cannot be round-tripped losslessly
 * (unknown nested fields, unknown enums, a missing expected member, or a size/rule count
 * above the local editing limit); then [readOnlyReason] explains why and no write may be
 * built from it.
 */
data class R2ConfigurationDocument(
    val target: R2WriteTarget,
    val kind: R2ConfigurationKind,
    val raw: JsonObject,
    val editable: Boolean,
    val readOnlyReason: String? = null,
)

/**
 * One B1 configuration write.
 *
 * A `PUT` replaces the complete rule set of its kind, so an empty [PutCors.rules],
 * [PutLifecycle.rules] or [PutLock.rules] list explicitly clears it. Every rule field is
 * typed: an arbitrary provider document is never passed through as a write body.
 */
sealed interface R2ConfigurationWrite {
    val target: R2WriteTarget

    data class PutCors(override val target: R2WriteTarget, val rules: List<R2CorsRule>) : R2ConfigurationWrite

    data class DeleteCors(override val target: R2WriteTarget) : R2ConfigurationWrite

    data class PutLifecycle(override val target: R2WriteTarget, val rules: List<R2LifecycleRule>) : R2ConfigurationWrite

    /** [enabled] is always sent, so an explicit `false` is never turned into an omission. */
    data class PutLocalUploads(override val target: R2WriteTarget, val enabled: Boolean) : R2ConfigurationWrite

    data class PutLock(override val target: R2WriteTarget, val rules: List<R2LockRule>) : R2ConfigurationWrite

    data class DeleteLock(override val target: R2WriteTarget) : R2ConfigurationWrite
}

/** The configuration family one write addresses. */
internal fun r2ConfigurationWriteKind(write: R2ConfigurationWrite): R2ConfigurationKind =
    when (write) {
        is R2ConfigurationWrite.PutCors, is R2ConfigurationWrite.DeleteCors -> R2ConfigurationKind.CORS
        is R2ConfigurationWrite.PutLifecycle -> R2ConfigurationKind.LIFECYCLE
        is R2ConfigurationWrite.PutLocalUploads -> R2ConfigurationKind.LOCAL_UPLOADS
        is R2ConfigurationWrite.PutLock, is R2ConfigurationWrite.DeleteLock -> R2ConfigurationKind.LOCK
    }

/** The pinned operationId one read addresses. */
internal fun r2ConfigurationReadKey(kind: R2ConfigurationKind): String =
    when (kind) {
        R2ConfigurationKind.CORS -> "r2-get-bucket-cors-policy"
        R2ConfigurationKind.LIFECYCLE -> "r2-get-bucket-lifecycle-configuration"
        R2ConfigurationKind.LOCAL_UPLOADS -> "r2-get-bucket-local-uploads-configuration"
        R2ConfigurationKind.LOCK -> "r2-get-bucket-lock-configuration"
    }

/**
 * Snapshot of one write, so a later mutation of the caller's draft cannot change what is sent.
 *
 * Every nested list is copied: a caller that keeps its own `MutableList` after preparing a
 * write cannot edit the payload that was already previewed and confirmed.
 */
internal fun freezeR2ConfigurationWrite(write: R2ConfigurationWrite): R2ConfigurationWrite = when (write) {
    is R2ConfigurationWrite.PutCors ->
        write.copy(
            target = write.target.copy(),
            rules =
                write.rules.map { rule ->
                    rule.copy(
                        allowed =
                            rule.allowed.copy(
                                methods = rule.allowed.methods.toList(),
                                origins = rule.allowed.origins.toList(),
                                headers = rule.allowed.headers.toList(),
                            ),
                        exposeHeaders = rule.exposeHeaders.toList(),
                    )
                },
        )
    is R2ConfigurationWrite.DeleteCors -> write.copy(target = write.target.copy())
    is R2ConfigurationWrite.PutLifecycle ->
        write.copy(
            target = write.target.copy(),
            rules = write.rules.map { rule ->
                rule.copy(
                    conditions = rule.conditions.copy(),
                    // The transition elements are immutable value types; only the list itself is aliased.
                    storageClassTransitions = rule.storageClassTransitions.toList(),
                )
            },
        )
    is R2ConfigurationWrite.PutLocalUploads -> write.copy(target = write.target.copy())
    is R2ConfigurationWrite.PutLock ->
        write.copy(target = write.target.copy(), rules = write.rules.toList())
    is R2ConfigurationWrite.DeleteLock -> write.copy(target = write.target.copy())
}

/** The pinned operationId one write addresses. */
internal fun r2ConfigurationWriteKey(write: R2ConfigurationWrite): String =
    when (write) {
        is R2ConfigurationWrite.PutCors -> "r2-put-bucket-cors-policy"
        is R2ConfigurationWrite.DeleteCors -> "r2-delete-bucket-cors-policy"
        is R2ConfigurationWrite.PutLifecycle -> "r2-put-bucket-lifecycle-configuration"
        is R2ConfigurationWrite.PutLocalUploads -> "r2-put-bucket-local-uploads-configuration"
        is R2ConfigurationWrite.PutLock -> "r2-put-bucket-lock-configuration"
        is R2ConfigurationWrite.DeleteLock -> "r2-delete-bucket-lock-configuration"
    }
