package com.flarego.core.cloudflare.identity

import com.flarego.core.application.r2.R2TaskTargets
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.r2.CloudflareR2JobsClient
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.R2BucketJob
import com.flarego.core.model.R2JobCreateRequest
import com.flarego.core.model.R2JobListQuery
import com.flarego.core.model.R2JobPage
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.WriteAuthorityDecision
import com.flarego.core.ports.R2JobSession
import com.flarego.core.ports.R2JobCreateAuthorization
import com.flarego.core.ports.R2JobSubmission

/**
 * All production batch C bucket-job writes pass here.
 *
 * The single `POST` obtains a fresh account-level `Workers R2 Storage Write` observation for exactly
 * this account, bucket, jurisdiction and the reviewed `r2-create-bucket-job` operation, mints an
 * issuer-owned proof and installs the proof-bound final guard in the wire client. A bucket-scoped
 * grant, an S3 bucket-item permission or an unknown policy syntax never mints one, and a stale proof
 * (a changed credential/route) refuses the send through the same issuer that admitted it.
 *
 * Reads are delegated unchanged: listing or reading a job grants no write permission.
 */
class AuthorizedR2JobsSession(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
    private val issuer: R2TokenAuthorityIssuer,
) : R2JobSession {
    private val authority = CloudflareR2WriteAuthorityClient(issuer, gateway)

    private fun reader() = CloudflareR2JobsClient(connection, gateway)

    override suspend fun listJobs(target: R2WriteTarget, query: R2JobListQuery): R2JobPage =
        reader().listJobs(target, query)

    override suspend fun getJob(target: R2WriteTarget, jobId: String): R2BucketJob = reader().getJob(target, jobId)

    /** The port's plain create keeps the proof-bound path; the controller always adds its own guard. */
    override suspend fun createJob(target: R2WriteTarget, request: R2JobCreateRequest): R2JobSubmission =
        createJob(target, request) {}

    override suspend fun createJob(
        target: R2WriteTarget,
        request: R2JobCreateRequest,
        beforeSend: () -> Unit,
    ): R2JobSubmission = authorizeCreate(target, request).send(beforeSend)

    override suspend fun authorizeCreate(
        target: R2WriteTarget,
        request: R2JobCreateRequest,
    ): R2JobCreateAuthorization {
        val operation = R2TaskTargets.JOB_CREATE_ACTION
        val observed = authority.observe(target, operation, forceRefresh = true)
        if (observed.decision != WriteAuthorityDecision.TOKEN_POLICY_GRANTED) {
            throw CloudFailure(
                CloudFailureCode.PERMISSION_REQUIRED,
                "无法证明当前 Token 拥有该账号级 R2 REST 写权限；未发送后台作业创建",
            )
        }
        val proof = issuer.proofFor(observed)
        issuer.requireCurrent(proof, target, operation)
        return object : R2JobCreateAuthorization {
            override suspend fun send(beforeSend: () -> Unit): R2JobSubmission {
                val client =
                    CloudflareR2JobsClient(connection, gateway) {
                        // Last non-suspending checks: the proof this issuer minted is still current
                        // (it may have expired while the caller read the final preflight), then the
                        // caller's composed final guard, then the single typed POST.
                        issuer.requireCurrent(proof, target, operation)
                        beforeSend()
                    }
                return client.createJob(target, request)
            }
        }
    }
}
