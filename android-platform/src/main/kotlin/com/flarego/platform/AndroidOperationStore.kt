package com.flarego.platform

import com.flarego.core.model.Operation
import com.flarego.core.model.OperationTask
import com.flarego.core.model.OperationTaskPersistence
import com.flarego.core.model.StoredOperationTaskRow
import com.flarego.core.ports.OperationStore
import com.flarego.db.FlareGoDatabase

/**
 * The SQLDelight-backed task store.
 *
 * It never opens its own connection: it uses the [LocalDatabase] owner's database,
 * so [putWithSummary] can write the task and the legacy summary inside one real
 * transaction. The stored row holds fixed safe metadata only.
 *
 * Mapping uses the generated result properties (column names) directly, so the
 * codec stays the single source of truth for the field order and meaning.
 */
class AndroidOperationStore internal constructor(private val database: FlareGoDatabase) : OperationStore {
    private val tasks = database.tasksQueries
    private val operations = database.localQueries

    override fun get(id: String): OperationTask? =
        tasks
            .taskById(id)
            .executeAsOneOrNull()
            ?.let {
                OperationTaskPersistence.fromRow(
                    StoredOperationTaskRow(
                        id = it.id,
                        connectionId = it.connection_id,
                        scopeKind = it.scope_kind,
                        scopeConnectionId = it.scope_connection_id,
                        accountId = it.account_id,
                        zoneId = it.zone_id,
                        userId = it.user_id,
                        organizationId = it.organization_id,
                        actionKey = it.action_key,
                        resourceKind = it.resource_kind,
                        resourceExternalId = it.resource_external_id,
                        requestFingerprint = it.request_fingerprint,
                        desiredHash = it.desired_hash,
                        state = it.state,
                        safeDetail = it.safe_detail,
                        providerRequestId = it.provider_request_id,
                        pollPath = it.poll_path,
                        createdAtMs = it.created_at_ms,
                        updatedAtMs = it.updated_at_ms,
                    )
                )
            }

    override fun list(connectionId: String): List<OperationTask> =
        tasks
            .tasksForConnection(connectionId)
            .executeAsList()
            .map {
                OperationTaskPersistence.fromRow(
                    StoredOperationTaskRow(
                        id = it.id,
                        connectionId = it.connection_id,
                        scopeKind = it.scope_kind,
                        scopeConnectionId = it.scope_connection_id,
                        accountId = it.account_id,
                        zoneId = it.zone_id,
                        userId = it.user_id,
                        organizationId = it.organization_id,
                        actionKey = it.action_key,
                        resourceKind = it.resource_kind,
                        resourceExternalId = it.resource_external_id,
                        requestFingerprint = it.request_fingerprint,
                        desiredHash = it.desired_hash,
                        state = it.state,
                        safeDetail = it.safe_detail,
                        providerRequestId = it.provider_request_id,
                        pollPath = it.poll_path,
                        createdAtMs = it.created_at_ms,
                        updatedAtMs = it.updated_at_ms,
                    )
                )
            }

    override fun put(task: OperationTask) {
        val row = OperationTaskPersistence.toRow(task)
        tasks.upsertTask(
            row.id,
            row.connectionId,
            row.scopeKind,
            row.scopeConnectionId,
            row.accountId,
            row.zoneId,
            row.userId,
            row.organizationId,
            row.actionKey,
            row.resourceKind,
            row.resourceExternalId,
            row.requestFingerprint,
            row.desiredHash,
            row.state,
            row.safeDetail,
            row.providerRequestId,
            row.pollPath,
            row.createdAtMs,
            row.updatedAtMs,
        )
    }

    override fun removeConnection(connectionId: String) {
        tasks.removeTasksForConnection(connectionId)
    }

    /**
     * Writes the task and its legacy summary in one transaction: a failing summary
     * rolls the task back too, so a task is never stored without its summary.
     */
    override fun putWithSummary(task: OperationTask, summary: Operation) {
        OperationTaskPersistence.requireSummaryMatches(task, summary)
        database.transaction {
            put(task)
            operations.upsertOperation(
                summary.id,
                summary.connectionId,
                summary.title,
                summary.status.name,
                summary.detail,
            )
        }
    }
}
