package com.flarego.core.cloudflare.identity

import com.flarego.core.application.d1.D1SqlInput
import com.flarego.core.cloudflare.d1.CloudflareD1Client
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.*
import com.flarego.core.ports.*

/** Production adapter: no synthetic permit constructor and no UI/debug override. */
class AuthorizedD1Session(
    private val connection: Connection,
    private val gateway: CloudflareGateway,
    private val transfers: TransferPort,
    private val contents: D1ContentStore,
    private val issuer: D1TokenAuthorityIssuer,
    private val beforeSend: () -> Unit,
) : D1Session by CloudflareD1Client(connection, gateway, transfers, contents,
    { _, _, _ -> throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "缺少 D1 写入证明") }) {
    private val authority = CloudflareD1WriteAuthorityClient(issuer, gateway)
    private suspend fun <T> authorized(target: D1WriteTarget, operation: String, send: suspend (D1Session) -> T): T {
        val observed = authority.observe(target, operation, forceRefresh = true)
        if (observed.decision != WriteAuthorityDecision.TOKEN_POLICY_GRANTED) {
            if (observed.reason == WriteAuthorityReason.UNSUPPORTED_OPERATION)
                throw CloudFailure(CloudFailureCode.UNSUPPORTED_CAPABILITY, "该 D1 操作缺少官方精确权限证据，生产入口暂不可用")
            throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "无法证明当前 Token 拥有此账号的 D1 写权限；未发送写入")
        }
        val proof = issuer.proofFor(observed)
        issuer.requireCurrent(proof, target, operation)
        return send(CloudflareD1Client(connection, gateway, transfers, contents) { actual, account, database ->
            if (actual != operation || D1WriteTarget(account, database) != target) throw invalidInputFailure()
            beforeSend()
            issuer.requireCurrent(proof, target, operation)
        })
    }
    override suspend fun createDatabase(account: AccountScope, input: D1CreateInput) =
        authorized(D1WriteTarget(account, null), "d1-create-database") { it.createDatabase(account, input) }
    override suspend fun updateDatabase(database: D1DatabaseRef, replication: D1Replication) =
        authorized(database.target(), "d1-update-database") { it.updateDatabase(database, replication) }
    override suspend fun patchDatabase(database: D1DatabaseRef, replication: D1Replication?) =
        authorized(database.target(), "d1-update-partial-database") { it.patchDatabase(database, replication) }
    override suspend fun deleteDatabase(database: D1DatabaseRef) =
        authorized(database.target(), "d1-delete-database") { it.deleteDatabase(database) }
    override suspend fun query(database: D1DatabaseRef, input: D1SqlInput) =
        authorized(database.target(), "d1-query-database") { it.query(database, input) }
    override suspend fun raw(database: D1DatabaseRef, input: D1SqlInput) =
        authorized(database.target(), "d1-raw-database-query") { it.raw(database, input) }
    override suspend fun importDatabase(database: D1DatabaseRef, request: D1ImportRequest) =
        authorized(database.target(), "d1-import-database") { it.importDatabase(database, request) }
    override suspend fun exportDatabase(database: D1DatabaseRef, options: D1ExportOptions) =
        authorized(database.target(), "d1-export-database") { it.exportDatabase(database, options) }
    override suspend fun pollExport(database: D1DatabaseRef, bookmark: String) =
        authorized(database.target(), "d1-export-database") { it.pollExport(database, bookmark) }
    override suspend fun restore(database: D1DatabaseRef, point: D1RestorePoint) =
        authorized(database.target(), "d1-time-travel-restore") { it.restore(database, point) }
    override suspend fun undeleteDatabase(database: D1DatabaseRef, newName: String?) =
        authorized(database.target(), "d1-undelete-database") { it.undeleteDatabase(database, newName) }
    private fun D1DatabaseRef.target() = D1WriteTarget(account, id)
}
