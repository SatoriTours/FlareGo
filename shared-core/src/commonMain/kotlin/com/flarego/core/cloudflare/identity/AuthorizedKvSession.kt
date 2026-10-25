package com.flarego.core.cloudflare.identity

import com.flarego.core.cloudflare.kv.CloudflareKvClient
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.*
import com.flarego.core.ports.*

class AuthorizedKvSession(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
    private val transfers: TransferPort,
    private val contents: KvContentStore,
    private val nowSeconds: () -> Long,
    private val issuer: KvTokenAuthorityIssuer,
    private val beforeSend: () -> Unit,
) : KvSession by CloudflareKvClient(connection, gateway, transfers, contents, nowSeconds,
    { _, _, _ -> throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "缺少 KV 写入证明") }) {
    private val authority = CloudflareKvWriteAuthorityClient(issuer, gateway)
    private suspend fun <T> authorized(target: KvWriteTarget, suffix: String, send: suspend (KvSession) -> T): T {
        val operation = "workers-kv-namespace-$suffix"
        val observed = authority.observe(target, operation, forceRefresh = true)
        if (observed.decision != WriteAuthorityDecision.TOKEN_POLICY_GRANTED)
            throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "无法证明当前 Token 拥有该账号的 KV 写权限；未发送写入")
        val proof = issuer.proofFor(observed)
        issuer.requireCurrent(proof, target, operation)
        return send(CloudflareKvClient(connection, gateway, transfers, contents, nowSeconds) { actual, account, namespace ->
            if (actual != operation || KvWriteTarget(account, namespace) != target) throw invalidInputFailure()
            beforeSend()
            // The product guard may itself revoke a credential or window. Check the issuer last.
            issuer.requireCurrent(proof, target, operation)
        })
    }
    override suspend fun createNamespace(account: AccountScope, title: String): KvNamespace =
        authorized(KvWriteTarget(account, null), "create-a-namespace") { it.createNamespace(account, title) }
    override suspend fun renameNamespace(namespace: KvNamespaceRef, title: String): KvWriteReceipt =
        authorized(namespace.target(), "rename-a-namespace") { it.renameNamespace(namespace, title) }
    override suspend fun deleteNamespace(namespace: KvNamespaceRef): KvWriteReceipt =
        authorized(namespace.target(), "remove-a-namespace") { it.deleteNamespace(namespace) }
    override suspend fun putValue(target: KvKeyTarget, input: KvValueInput): KvWriteReceipt =
        authorized(target.namespace.target(), "write-key-value-pair-with-metadata") { it.putValue(target, input) }
    override suspend fun deleteValue(target: KvKeyTarget): KvWriteReceipt =
        authorized(target.namespace.target(), "delete-key-value-pair") { it.deleteValue(target) }
    override suspend fun bulkWrite(namespace: KvNamespaceRef, input: KvBulkWriteInput): KvWriteReceipt =
        authorized(namespace.target(), "write-multiple-key-value-pairs") { it.bulkWrite(namespace, input) }
    override suspend fun bulkDelete(namespace: KvNamespaceRef, keys: List<String>): KvWriteReceipt {
        val frozen = keys.toList()
        return authorized(namespace.target(), "delete-multiple-key-value-pairs") { it.bulkDelete(namespace, frozen) }
    }
    private fun KvNamespaceRef.target() = KvWriteTarget(account, id)
}
