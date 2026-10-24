package com.flarego.core.ports

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.r2.R2SippySecretLease
import com.flarego.core.application.r2.R2SippyWrite
import com.flarego.core.model.R2SippySnapshot
import com.flarego.core.model.R2WriteTarget

/**
 * Typed port for the reviewed batch B3 Sippy plane.
 *
 * Reads return only the safe allowlist projection (no raw provider JSON). Writes take a memory-only
 * [R2SippySecretLease]; a `PUT` enables migration with the supplied credentials, a `DELETE` disables
 * it. Every method takes the explicit [R2WriteTarget] so the bucket/jurisdiction are bound before a
 * request exists, and a write returns only an [ExecutionReceipt] — the same-lock read-back belongs
 * to the controller that owns the bucket.
 */
interface R2SippyPort {
    suspend fun getSippy(target: R2WriteTarget): R2SippySnapshot

    suspend fun putSippy(target: R2WriteTarget, lease: R2SippySecretLease): ExecutionReceipt

    suspend fun deleteSippy(target: R2WriteTarget): ExecutionReceipt
}

/**
 * Credential-bound typed adapter for Sippy writes.
 *
 * [beforeSend] receives the safe current snapshot (or null only for a confirmed absence with
 * pinned evidence) and runs after every fallible local step. The lease is admitted only by the
 * final guard, immediately before the single send; nothing suspends in between.
 */
interface R2SippySession : R2SippyPort {
    suspend fun writeSippy(write: R2SippyWrite, beforeSend: (R2SippySnapshot?) -> Unit): ExecutionReceipt
}
