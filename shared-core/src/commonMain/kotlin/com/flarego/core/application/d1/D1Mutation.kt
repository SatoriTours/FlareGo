package com.flarego.core.application.d1

import com.flarego.core.cloudflare.encodePath
import com.flarego.core.model.*
import com.flarego.core.ports.D1Content
import kotlin.io.encoding.Base64

/** Immutable memory-only commands. No SQL, params, file hash or URL enters a persisted plan. */
sealed class D1Mutation(val account: AccountScope, val databaseId: String?, val action: String) {
    class Create(account: AccountScope, val input: D1CreateInput) : D1Mutation(account, null, "d1-create-database")
    class PutReplication(val database: D1DatabaseRef, val replication: D1Replication) : D1Mutation(database.account, database.id, "d1-update-database")
    class PatchReplication(val database: D1DatabaseRef, val replication: D1Replication?) : D1Mutation(database.account, database.id, "d1-update-partial-database")
    class Delete(val database: D1DatabaseRef) : D1Mutation(database.account, database.id, "d1-delete-database")
    class Undelete(val database: D1DatabaseRef, val newName: String? = null) : D1Mutation(database.account, database.id, "d1-undelete-database")
    class Sql(val database: D1DatabaseRef, val input: D1SqlInput, val raw: Boolean = false) : D1Mutation(database.account, database.id, if (raw) "d1-raw-database-query" else "d1-query-database")
    class Import(val database: D1DatabaseRef, val input: D1ImportFile) : D1Mutation(database.account, database.id, "d1-import-database")
    class Export(val database: D1DatabaseRef, val options: D1ExportOptions) : D1Mutation(database.account, database.id, "d1-export-database")
    class Restore(val database: D1DatabaseRef, val point: D1RestorePoint) : D1Mutation(database.account, database.id, "d1-time-travel-restore")
    final override fun toString() = "D1Mutation(action=$action, content=redacted)"
}
data class D1LiveSelection(val connection: Connection, val credentialRevision: Long, val epoch: Long, val command: D1Mutation?)
enum class D1ConfirmationStage { RISK, TYPED, FINAL }
class D1ConfirmationHandle internal constructor(val stage: D1ConfirmationStage)
/** Consumed only inside a protected private owner. toString never prints the scope contents. */
class D1ConfirmationView internal constructor(
    val handle: D1ConfirmationHandle, val account: AccountScope, val databaseId: String?, val databaseName: String,
    val action: String, val oldReplication: D1Replication?, val newReplication: D1Replication?, val omittedReplication: Boolean,
    val content: D1Content?, val tables: List<String>?, val allTables: Boolean?, val noData: Boolean?, val noSchema: Boolean?,
    val restoreTimestamp: String?, val restoreBookmark: String?, val newName: String?, val warning: String,
    val createJurisdiction: D1Jurisdiction? = null, val createLocation: D1Location? = null,
    val connectionLabel: String,
) {
    val connectionId: String get() = account.connectionId
    internal fun withHandle(next: D1ConfirmationHandle) = D1ConfirmationView(next, account, databaseId, databaseName, action,
        oldReplication, newReplication, omittedReplication, content, tables, allTables, noData, noSchema, restoreTimestamp, restoreBookmark, newName, warning, createJurisdiction, createLocation, connectionLabel)
    override fun toString() = "D1ConfirmationView(action=$action, stage=${handle.stage}, content=redacted)"
}
class D1Submission internal constructor(val task: OperationTask, val created: D1Database?, val restore: D1RestoreReceipt?) {
    override fun toString() = "D1Submission(task=$task)"
}

object D1TaskTargets {
    const val DATABASE = "d1_database"
    const val ACCOUNT = "d1_account"
    fun ref(account: AccountScope, database: String?) = if (database == null) ResourceRef(account, ACCOUNT, account.accountId)
        else ResourceRef(account, DATABASE, D1Validation.databaseId(database).lowercase())
    fun unsettled(task: OperationTask) = task.state in setOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.UNKNOWN, TaskState.PARTIAL, TaskState.RECONCILING) ||
        task.state == TaskState.ACCEPTED && task.pollPath != null
    private fun prefix(ref: D1DatabaseRef, kind: D1FileKind) = "/accounts/${encodePath(ref.account.accountId)}/d1/database/${D1Validation.databaseId(ref.id).lowercase()}/${if (kind == D1FileKind.IMPORT) "import" else "export"}?bookmark_b64="
    internal fun path(locator: D1FileLocator) = prefix(locator.database, locator.kind) + Base64.UrlSafe.encode(D1Validation.bookmark(locator.bookmark).encodeToByteArray())
    /** This metadata encoding is decoded into a typed POST poll; never sent as a GET/path replay. */
    internal fun locator(task: OperationTask): D1FileLocator {
        val account = task.scope as? AccountScope ?: throw invalidInputFailure()
        val resource = task.resourceRef?.takeIf { it.kind == DATABASE && it.scope == account } ?: throw invalidInputFailure()
        val kind = when (task.actionKey) { "d1-import-database" -> D1FileKind.IMPORT; "d1-export-database" -> D1FileKind.EXPORT; else -> throw invalidInputFailure() }
        val ref = D1DatabaseRef(account, D1Validation.databaseId(resource.externalId))
        val path = task.pollPath ?: throw invalidInputFailure()
        val start = prefix(ref, kind)
        if (!path.startsWith(start)) throw invalidInputFailure()
        val text = path.removePrefix(start)
        val bookmark = try { Base64.UrlSafe.decode(text).decodeToString(throwOnInvalidSequence = true) } catch (_: Exception) { throw invalidInputFailure() }
        val locator = D1FileLocator(ref, kind, D1Validation.bookmark(bookmark))
        if (path(locator) != path) throw invalidInputFailure()
        return locator
    }
}
