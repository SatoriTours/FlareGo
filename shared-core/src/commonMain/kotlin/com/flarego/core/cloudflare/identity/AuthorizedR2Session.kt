package com.flarego.core.cloudflare.identity

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.r2.CloudflareR2Client
import com.flarego.core.model.*
import com.flarego.core.ports.*

/**
 * All production R2 writes pass here, including convenience methods. Read delegation grants
 * no permission. Each mutation uses a fresh inspection and a proof-bound short client whose
 * final synchronous guard runs after product callbacks and immediately before the gateway send.
 * The stable issuer survives reconstruction of this adapter and its transport.
 */
class AuthorizedR2Session(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
    private val transfers: TransferPort,
    private val issuer: R2TokenAuthorityIssuer,
) : R2Session by CloudflareR2Client(connection, gateway, transfers) {
    private val authority = CloudflareR2WriteAuthorityClient(issuer, gateway)
    private suspend fun <T> authorized(target: R2WriteTarget, operation: String, block: suspend (CloudflareR2Client) -> T): T {
        val observed = authority.observe(target, operation, forceRefresh = true)
        if (observed.decision != WriteAuthorityDecision.TOKEN_POLICY_GRANTED)
            throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "无法证明当前 Token 拥有该账号的 R2 REST 写权限；未发送写入")
        val proof = issuer.proofFor(observed)
        issuer.requireCurrent(proof, target, operation)
        return block(CloudflareR2Client(connection, gateway, transfers) { issuer.requireCurrent(proof, target, operation) })
    }
    override suspend fun createBucket(account: AccountScope, input: R2BucketCreateInput): R2BucketDocument =
        authorized(R2WriteTarget(account, input.name), "r2-create-bucket") { it.createBucket(account, input.copy()) }
    override suspend fun createBucketByName(account: AccountScope, name: String, storageClass: String?): R2BucketDocument =
        authorized(R2WriteTarget(account, name), "r2-create-bucket-by-name") { it.createBucketByName(account, name, storageClass) }
    override suspend fun patchBucketStorageClass(account: AccountScope, name: String, storageClass: String): R2BucketDocument =
        authorized(R2WriteTarget(account, name), "r2-patch-bucket") { it.patchBucketStorageClass(account, name, storageClass) }
    override suspend fun deleteBucket(account: AccountScope, name: String) =
        authorized(R2WriteTarget(account, name), "r2-delete-bucket") { it.deleteBucket(account, name) }
    override suspend fun write(write: R2BucketWrite, beforeSend: (R2BucketDocument?) -> Unit): ExecutionReceipt {
        val frozen = freezeR2BucketWrite(write)
        val name = when (frozen) {
            is R2BucketWrite.Create -> frozen.input.name
            is R2BucketWrite.CreateByName -> frozen.name
            is R2BucketWrite.PatchStorageClass -> frozen.name
            is R2BucketWrite.Delete -> frozen.name
        }
        return authorized(R2WriteTarget(frozen.scope, name), r2BucketWriteKey(frozen)) { it.write(frozen, beforeSend) }
    }
    override suspend fun uploadObject(target: R2ObjectTarget, source: ByteSource, length: Long, contentType: String?, contentFingerprint: String): R2ObjectEntry {
        var handedToClient = false
        try {
            return authorized(R2WriteTarget(target.scope, target.bucket), "r2-put-object") {
                handedToClient = true
                it.uploadObject(target, source, length, contentType, contentFingerprint)
            }
        } finally {
            // The direct caller already supplied an open source, unlike writeObject's factory.
            if (!handedToClient) runCatching { source.close() }
        }
    }
    override suspend fun deleteObject(target: R2ObjectTarget) =
        authorized(R2WriteTarget(target.scope, target.bucket), "r2-delete-object") { it.deleteObject(target) }
    override suspend fun deleteObjects(account: AccountScope, bucket: String, keys: List<String>): R2BulkDeleteOutcome {
        val frozen = keys.toList()
        return authorized(R2WriteTarget(account, bucket), "r2-delete-objects") { it.deleteObjects(account, bucket, frozen) }
    }
    override suspend fun writeObject(write: R2ObjectWrite, beforeSend: (R2ObjectEntry?) -> Unit): ExecutionReceipt {
        val frozen = when (write) {
            is R2ObjectWrite.Upload -> write.copy()
            is R2ObjectWrite.Delete -> write.copy()
            is R2ObjectWrite.BulkDelete -> write.copy(keys = write.keys.toList())
        }
        return authorized(R2WriteTarget(frozen.scope, frozen.bucket), r2ObjectWriteKey(frozen)) { it.writeObject(frozen, beforeSend) }
    }
}
