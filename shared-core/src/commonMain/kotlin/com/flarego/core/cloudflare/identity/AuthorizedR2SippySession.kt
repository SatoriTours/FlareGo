package com.flarego.core.cloudflare.identity

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.r2.R2SippySecretLease
import com.flarego.core.application.r2.R2SippyWrite
import com.flarego.core.application.r2.r2SippyWriteKey
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.r2.CloudflareR2SippyClient
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.R2SippySnapshot
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.WriteAuthorityDecision
import com.flarego.core.ports.R2SippySession

/**
 * All production batch B3 Sippy writes pass here.
 *
 * Each write obtains a fresh account-level `Workers R2 Storage Write` observation for exactly this
 * account, bucket, jurisdiction and operation, mints an issuer-owned proof and installs the
 * proof-bound final guard in the wire client. A bucket-scoped grant, an S3 bucket-item permission or
 * an unknown policy syntax never mints one.
 *
 * Reads are delegated unchanged: reading the Sippy configuration grants no write permission.
 */
class AuthorizedR2SippySession(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
    private val issuer: R2TokenAuthorityIssuer,
) : R2SippySession {
    private val authority = CloudflareR2WriteAuthorityClient(issuer, gateway)

    private fun reader() = CloudflareR2SippyClient(connection, gateway)

    override suspend fun getSippy(target: R2WriteTarget): R2SippySnapshot = reader().getSippy(target)

    override suspend fun putSippy(target: R2WriteTarget, lease: R2SippySecretLease): ExecutionReceipt =
        writeSippy(R2SippyWrite.PutSippy(target, lease)) {}

    override suspend fun deleteSippy(target: R2WriteTarget): ExecutionReceipt =
        writeSippy(R2SippyWrite.DeleteSippy(target)) {}

    override suspend fun writeSippy(
        write: R2SippyWrite,
        beforeSend: (R2SippySnapshot?) -> Unit,
    ): ExecutionReceipt {
        val target = write.target
        val operation = r2SippyWriteKey(write)
        val observed = authority.observe(target, operation, forceRefresh = true)
        if (observed.decision != WriteAuthorityDecision.TOKEN_POLICY_GRANTED) {
            throw CloudFailure(
                CloudFailureCode.PERMISSION_REQUIRED,
                "无法证明当前 Token 拥有该账号级 R2 REST 写权限；未发送 Sippy 配置写入",
            )
        }
        val proof = issuer.proofFor(observed)
        issuer.requireCurrent(proof, target, operation)
        val client =
            CloudflareR2SippyClient(connection, gateway) {
                issuer.requireCurrent(proof, target, operation)
            }
        return client.writeSippy(write, beforeSend)
    }
}
