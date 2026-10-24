package com.flarego.core.cloudflare.identity

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.r2.R2TaskTargets
import com.flarego.core.application.r2.r2NotificationWriteKey
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.r2.CloudflareR2NotificationClient
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.R2NotificationCollection
import com.flarego.core.model.R2NotificationDeletion
import com.flarego.core.model.R2NotificationQueue
import com.flarego.core.model.R2NotificationRuleSpec
import com.flarego.core.model.R2NotificationWrite
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.WriteAuthorityDecision
import com.flarego.core.ports.R2NotificationSession

/**
 * All production batch C event-notification writes pass here.
 *
 * Each write obtains a fresh account-level `Workers R2 Storage Write` observation for exactly this
 * account, bucket, jurisdiction and the reviewed operation (append or delete), mints an issuer-owned
 * proof and installs the proof-bound guard as the wire client's own pre-write seam — so the guard
 * runs against the client's fresh read of the very document that is about to be modified, immediately
 * before the single typed send.
 *
 * Reads are delegated unchanged: reading a configuration grants no write permission.
 */
class AuthorizedR2NotificationSession(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
    private val issuer: R2TokenAuthorityIssuer,
) : R2NotificationSession {
    private val authority = CloudflareR2WriteAuthorityClient(issuer, gateway)

    private fun reader() = CloudflareR2NotificationClient(connection, gateway)

    override suspend fun listConfigurations(target: R2WriteTarget): R2NotificationCollection =
        reader().listConfigurations(target)

    override suspend fun getConfiguration(target: R2WriteTarget, queueId: String): R2NotificationQueue =
        reader().getConfiguration(target, queueId)

    /** The port's plain append keeps the proof-bound path; the controller always adds its own guard. */
    override suspend fun appendRules(
        target: R2WriteTarget,
        queueId: String,
        rules: List<R2NotificationRuleSpec>,
    ): ExecutionReceipt = writeNotification(R2NotificationWrite.Append(target, queueId, rules)) {}

    /** The port's plain delete keeps the proof-bound path; the controller always adds its own guard. */
    override suspend fun deleteRules(
        target: R2WriteTarget,
        queueId: String,
        deletion: R2NotificationDeletion,
    ): ExecutionReceipt = writeNotification(R2NotificationWrite.Delete(target, queueId, deletion)) {}

    override suspend fun writeNotification(
        write: R2NotificationWrite,
        beforeSend: (R2NotificationQueue) -> Unit,
    ): ExecutionReceipt {
        val target = write.target
        val operation = r2NotificationWriteKey(write)
        val observed = authority.observe(target, operation, forceRefresh = true)
        if (observed.decision != WriteAuthorityDecision.TOKEN_POLICY_GRANTED) {
            throw CloudFailure(
                CloudFailureCode.PERMISSION_REQUIRED,
                "无法证明当前 Token 拥有该账号级 R2 REST 写权限；未发送事件通知配置写入",
            )
        }
        val proof = issuer.proofFor(observed)
        issuer.requireCurrent(proof, target, operation)
        val client =
            CloudflareR2NotificationClient(connection, gateway) { before ->
                // The client's own fresh pre-write document: the proof must still be current, then the
                // controller compares exactly this baseline and runs the composed final guard.
                issuer.requireCurrent(proof, target, operation)
                beforeSend(before)
            }
        return when (write) {
            is R2NotificationWrite.Append -> client.appendRules(target, write.queueId, write.rules)
            is R2NotificationWrite.Delete -> client.deleteRules(target, write.queueId, write.deletion)
        }
    }
}
