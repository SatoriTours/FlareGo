package com.flarego.platform

import com.flarego.core.model.Operation
import com.flarego.core.model.OperationTask
import com.flarego.core.model.OperationTaskPersistence
import com.flarego.core.model.StoredOperationTaskRow
import com.flarego.core.ports.DnsBatchEvidenceStore
import com.flarego.core.model.*
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
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
class AndroidOperationStore internal constructor(private val database: FlareGoDatabase) : DnsBatchEvidenceStore {
    private val evidenceQueries = database.dnsBatchEvidenceQueries
    private val fingerprint = AndroidFingerprint()

    override fun batchEvidence(taskId: String): DnsBatchTaskEvidence? = try {
        val parent = get(taskId)
        val h = evidenceQueries.headerByTask(taskId).executeAsOneOrNull()
        if (parent == null || h == null) null else {
            val rows = evidenceQueries.itemsByTask(taskId).executeAsList()
            if (rows.mapIndexed { index, row -> row.ordinal == index.toLong() }.any { !it }) throw invalidEvidence()
            val proof = DnsBatchTaskEvidence(boundedInt(h.version),h.task_id,
                ZoneScope(AccountScope(h.connection_id,h.account_id),h.zone_id),
                listOf(h.delete_count,h.patch_count,h.put_count,h.post_count).map { boundedInt(it) },
                rows.map { r -> DnsBatchItemEvidence(DnsBatchKind.valueOf(r.kind),boundedInt(r.item_index),
                    r.record_id,r.create_type,r.create_name,r.desired_hash,r.before_hash,r.provider_id,
                    r.creation_precondition_version?.let { boundedInt(it) }) },
                h.manifest_hash,DnsBatchTransaction.valueOf(h.transaction_receipt))
            proof.requireValid(parent,fingerprint)
            proof
        }
    } catch (_: Exception) { null } // Incomplete/corrupt metadata can never authorize recovery.

    override fun putWithSummaryAndEvidence(task: OperationTask, summary: Operation, evidence: DnsBatchTaskEvidence) {
        val frozen = evidence.copy(counts=evidence.counts.toList(),items=evidence.items.toList())
        frozen.requireValid(task,fingerprint)
        OperationTaskPersistence.requireSummaryMatches(task,summary)
        database.transaction {
            val prior = batchEvidence(task.id)
            prior?.let { old ->
                if (old.copy(transaction=frozen.transaction,items=frozen.items) != frozen ||
                    (old.transaction != DnsBatchTransaction.UNKNOWN && old.transaction != frozen.transaction) ||
                    old.items.zip(frozen.items).any { (a,b) -> a.copy(providerId=b.providerId) != b || (a.providerId != null && a.providerId != b.providerId) }) throw invalidEvidence()
            }
            writeTask(task)
            writeSummary(task)
            writeEvidence(frozen)
        }
    }
    private fun boundedInt(value: Long): Int {
        if (value !in 0..3500) throw invalidEvidence()
        return value.toInt()
    }
    private fun invalidEvidence() = CloudFailure(CloudFailureCode.INVALID_INPUT,"批量核对证据无效")
    private fun writeEvidence(e: DnsBatchTaskEvidence) {
        evidenceQueries.upsertHeader(e.taskId,e.version.toLong(),e.scope.connectionId,e.scope.account.accountId,e.scope.zoneId,
            e.counts[0].toLong(),e.counts[1].toLong(),e.counts[2].toLong(),e.counts[3].toLong(),e.manifestHash,e.transaction.name)
        evidenceQueries.removeItems(e.taskId)
        e.items.forEachIndexed { ordinal,item -> evidenceQueries.insertItem(e.taskId,ordinal.toLong(),item.kind.name,item.index.toLong(),
            item.recordId,item.createType,item.createName,item.desiredHash,item.beforeHash,item.providerId,item.creationPreconditionVersion?.toLong()) }
    }
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
        database.transaction {
            val prior = batchEvidence(task.id)
            prior?.requireValid(task,fingerprint)
            writeTask(task)
            prior?.let { writeEvidence(it) }
        }
    }
    private fun writeTask(task: OperationTask) {
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
        database.transaction {
            tasks.removeTasksForConnection(connectionId)
            operations.removeOperations(connectionId)
        }
    }

    /**
     * Writes the task and its legacy summary in one transaction: a failing summary
     * rolls the task back too, so a task is never stored without its summary.
     */
    override fun putWithSummary(task: OperationTask, summary: Operation) {
        // The caller summary only proves identity and state agreement; the row that is
        // written is the canonical state-derived summary, never caller title/detail.
        OperationTaskPersistence.requireSummaryMatches(task, summary)
        database.transaction {
            put(task)
            writeSummary(task)
        }
    }
    private fun writeSummary(task: OperationTask) {
        val canonical = OperationTaskPersistence.summaryFor(task)
        operations.upsertOperation(canonical.id,canonical.connectionId,canonical.title,canonical.status.name,canonical.detail)
    }
}
