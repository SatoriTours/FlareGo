package com.flarego.core.cloudflare.identity

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.r2.CloudflareR2ConfigurationClient
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.R2BucketDocument
import com.flarego.core.model.R2ConfigurationDocument
import com.flarego.core.model.R2ConfigurationWrite
import com.flarego.core.model.R2CorsRule
import com.flarego.core.model.R2LifecycleRule
import com.flarego.core.model.R2LockRule
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.WriteAuthorityDecision
import com.flarego.core.model.freezeR2ConfigurationWrite
import com.flarego.core.model.r2ConfigurationWriteKey
import com.flarego.core.ports.R2ConfigurationSession

/**
 * All production batch B1 configuration writes pass here.
 *
 * Each write obtains a fresh, account-level `Workers R2 Storage Write` observation for
 * exactly this target (account, bucket, jurisdiction) and operation, mints an
 * issuer-owned proof and installs the proof-bound final guard in the wire client. The
 * guard runs after every product callback and immediately before the single send, so a
 * credential, clock, catalog or session change during the read phase ends in zero sends.
 *
 * Reads are delegated unchanged: reading a configuration grants no write permission.
 */
class AuthorizedR2ConfigurationSession(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
    private val issuer: R2TokenAuthorityIssuer,
) : R2ConfigurationSession {
    private val authority = CloudflareR2WriteAuthorityClient(issuer, gateway)

    private fun reader() = CloudflareR2ConfigurationClient(connection, gateway)

    override suspend fun getBucket(target: R2WriteTarget): R2BucketDocument = reader().getBucket(target)

    override suspend fun getCors(target: R2WriteTarget): R2ConfigurationDocument = reader().getCors(target)

    override suspend fun getLifecycle(target: R2WriteTarget): R2ConfigurationDocument = reader().getLifecycle(target)

    override suspend fun getLocalUploads(target: R2WriteTarget): R2ConfigurationDocument = reader().getLocalUploads(target)

    override suspend fun getLock(target: R2WriteTarget): R2ConfigurationDocument = reader().getLock(target)

    override suspend fun putCors(target: R2WriteTarget, rules: List<R2CorsRule>): ExecutionReceipt =
        writeConfiguration(R2ConfigurationWrite.PutCors(target, rules.toList())) {}

    override suspend fun deleteCors(target: R2WriteTarget): ExecutionReceipt =
        writeConfiguration(R2ConfigurationWrite.DeleteCors(target)) {}

    override suspend fun putLifecycle(target: R2WriteTarget, rules: List<R2LifecycleRule>): ExecutionReceipt =
        writeConfiguration(R2ConfigurationWrite.PutLifecycle(target, rules.toList())) {}

    override suspend fun putLocalUploads(target: R2WriteTarget, enabled: Boolean): ExecutionReceipt =
        writeConfiguration(R2ConfigurationWrite.PutLocalUploads(target, enabled)) {}

    override suspend fun putLock(target: R2WriteTarget, rules: List<R2LockRule>): ExecutionReceipt =
        writeConfiguration(R2ConfigurationWrite.PutLock(target, rules.toList())) {}

    override suspend fun deleteLock(target: R2WriteTarget): ExecutionReceipt =
        writeConfiguration(R2ConfigurationWrite.DeleteLock(target)) {}

    override suspend fun writeConfiguration(
        write: R2ConfigurationWrite,
        beforeSend: (R2ConfigurationDocument?) -> Unit,
    ): ExecutionReceipt {
        val frozen = freezeR2ConfigurationWrite(write)
        val target = frozen.target
        val operation = r2ConfigurationWriteKey(frozen)
        val observed = authority.observe(target, operation, forceRefresh = true)
        if (observed.decision != WriteAuthorityDecision.TOKEN_POLICY_GRANTED) {
            throw CloudFailure(
                CloudFailureCode.PERMISSION_REQUIRED,
                "无法证明当前 Token 拥有该账号级 R2 REST 写权限；未发送配置写入",
            )
        }
        val proof = issuer.proofFor(observed)
        issuer.requireCurrent(proof, target, operation)
        // The wire client calls this guard after every fallible/product step and immediately
        // before the single typed send; nothing suspends between the guard and the send.
        val client = CloudflareR2ConfigurationClient(connection, gateway) {
            issuer.requireCurrent(proof, target, operation)
        }
        return client.writeConfiguration(frozen, beforeSend)
    }
}
