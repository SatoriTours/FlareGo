package com.flarego.core.ports

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.model.R2BucketJob
import com.flarego.core.model.R2JobCreateRequest
import com.flarego.core.model.R2JobListQuery
import com.flarego.core.model.R2JobLocator
import com.flarego.core.model.R2JobPage
import com.flarego.core.model.R2WriteTarget

/**
 * Typed port for the reviewed batch C bucket-job plane (two reads, one write).
 *
 * Reads return the safe allowlist projection, never raw provider JSON. The write takes one explicit
 * [R2JobCreateRequest] and returns a [R2JobSubmission]: the execution receipt plus an optional safe
 * job and an optional read-only [com.flarego.core.model.R2JobLocator]. A missing trustworthy job ID
 * never produces a locator, and a 200 response is never treated as the job having completed.
 */
interface R2JobsPort {
    suspend fun listJobs(target: R2WriteTarget, query: R2JobListQuery): R2JobPage

    suspend fun getJob(target: R2WriteTarget, jobId: String): R2BucketJob

    suspend fun createJob(target: R2WriteTarget, request: R2JobCreateRequest): R2JobSubmission
}

/**
 * The result of one job creation.
 *
 * [job] is null when the response did not carry a trustworthy job shape. [pollLocator] is present
 * only for a trustworthy same-kind ID; a 200 response is never promoted into "the job completed"
 * and the later controller must still perform its independent GET.
 */
data class R2JobSubmission(
    val receipt: ExecutionReceipt,
    val job: R2BucketJob?,
    val pollLocator: R2JobLocator?,
)

/**
 * The credential-bound jobs adapter used by the batch C controller.
 *
 * Reads are delegated unchanged (a job list or a job document grants no write permission). The single
 * create additionally takes [beforeSend]: everything fallible — the fresh account-level authority
 * observation, the proof, and every read of the controller's preflight — happens before it, and it is
 * invoked immediately before the one typed POST, without a suspension after it.
 */
interface R2JobSession : R2JobsPort {
    suspend fun createJob(
        target: R2WriteTarget,
        request: R2JobCreateRequest,
        beforeSend: () -> Unit,
    ): R2JobSubmission

    /**
     * Phase 1 of the two-phase create: obtain the fresh account-level authority observation for
     * exactly this account/bucket/jurisdiction/operation and mint the issuer-owned proof, before the
     * caller performs any further (suspendable) read.
     *
     * The returned authorization sends the single `POST` with the caller's last non-suspending checks;
     * it re-checks that the same proof is still current, so a credential/route change or an expired
     * proof during those reads refuses the send instead of trusting the earlier observation.
     */
    suspend fun authorizeCreate(target: R2WriteTarget, request: R2JobCreateRequest): R2JobCreateAuthorization
}

/** The admitted, proof-bound single create of one bucket job. */
interface R2JobCreateAuthorization {
    /** Sends the one typed `POST`; [beforeSend] runs non-suspending immediately before it. */
    suspend fun send(beforeSend: () -> Unit): R2JobSubmission
}
