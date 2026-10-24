package com.flarego.core.ports

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.model.R2BucketDocument
import com.flarego.core.model.R2ConfigurationDocument
import com.flarego.core.model.R2ConfigurationWrite
import com.flarego.core.model.R2CorsRule
import com.flarego.core.model.R2LifecycleRule
import com.flarego.core.model.R2LockRule
import com.flarego.core.model.R2WriteTarget

/**
 * Typed port for the reviewed R2 configuration plane (batch B1: CORS, lifecycle,
 * local uploads, bucket lock).
 *
 * Every method takes the explicit [R2WriteTarget] (account, bucket and jurisdiction);
 * the implementation proves local ownership with
 * [com.flarego.core.model.ScopeGuard] and an independent provider read *before* it
 * builds any request. Reads keep the provider's own result object so unknown fields —
 * and unknown enum values — survive and force the document read-only instead of being
 * overwritten by a guess.
 *
 * A `PUT` replaces the complete rule set of its kind: an empty list clears it. Writes
 * return only an [ExecutionReceipt]: a `PUT` response is not assumed to contain the
 * resulting configuration document, so the write-after read-back belongs to the
 * controller that owns the bucket lock.
 */
interface R2ConfigurationPort {
    /**
     * Independent ownership proof of one bucket under the target's jurisdiction.
     *
     * The bucket name in the response is compared with the requested one, so a
     * same-named bucket in another jurisdiction can never stand in as evidence.
     */
    suspend fun getBucket(target: R2WriteTarget): R2BucketDocument

    suspend fun getCors(target: R2WriteTarget): R2ConfigurationDocument

    suspend fun getLifecycle(target: R2WriteTarget): R2ConfigurationDocument

    /**
     * Local uploads support the default jurisdiction only: the pinned schema declares no
     * `cf-r2-jurisdiction` parameter for this endpoint. A non-default target is refused
     * before any request, and never falls back to reading the default one.
     */
    suspend fun getLocalUploads(target: R2WriteTarget): R2ConfigurationDocument

    suspend fun getLock(target: R2WriteTarget): R2ConfigurationDocument

    suspend fun putCors(target: R2WriteTarget, rules: List<R2CorsRule>): ExecutionReceipt

    suspend fun deleteCors(target: R2WriteTarget): ExecutionReceipt

    suspend fun putLifecycle(target: R2WriteTarget, rules: List<R2LifecycleRule>): ExecutionReceipt

    suspend fun putLocalUploads(target: R2WriteTarget, enabled: Boolean): ExecutionReceipt

    suspend fun putLock(target: R2WriteTarget, rules: List<R2LockRule>): ExecutionReceipt

    suspend fun deleteLock(target: R2WriteTarget): ExecutionReceipt
}

/**
 * Credential-bound typed adapter for configuration writes.
 *
 * [beforeSend] is the last non-suspending check. Everything fallible — target and rule
 * validation, the request-shape policy match, the independent bucket read and the current
 * configuration snapshot — precedes it, and the single typed send follows immediately.
 *
 * The snapshot argument is null only for a *confirmed* absence of the configuration; a
 * failed or unrecognised read is an error, never an absence. Batch B1 has no confirmed
 * absence contract, so this client never passes null.
 */
interface R2ConfigurationSession : R2ConfigurationPort {
    suspend fun writeConfiguration(
        write: R2ConfigurationWrite,
        beforeSend: (R2ConfigurationDocument?) -> Unit,
    ): ExecutionReceipt
}
