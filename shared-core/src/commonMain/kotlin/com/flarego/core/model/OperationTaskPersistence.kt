package com.flarego.core.model

/** The flat, fixed stored shape of one task row. */
data class StoredOperationTaskRow(
    val id: String,
    val connectionId: String,
    val scopeKind: String,
    val scopeConnectionId: String,
    val accountId: String?,
    val zoneId: String?,
    val userId: String?,
    val organizationId: String?,
    val actionKey: String,
    val resourceKind: String?,
    val resourceExternalId: String?,
    val requestFingerprint: String,
    val desiredHash: String?,
    val state: String,
    val safeDetail: String,
    val providerRequestId: String?,
    val pollPath: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/** Fixed-field persistence codec and safe summary conversion. */
object OperationTaskPersistence {
    val STORED_COLUMNS: List<String> =
        listOf(
            "id",
            "connection_id",
            "scope_kind",
            "scope_connection_id",
            "account_id",
            "zone_id",
            "user_id",
            "organization_id",
            "action_key",
            "resource_kind",
            "resource_external_id",
            "request_fingerprint",
            "desired_hash",
            "state",
            "safe_detail",
            "provider_request_id",
            "poll_path",
            "created_at_ms",
            "updated_at_ms",
        )

    fun toRow(task: OperationTask): StoredOperationTaskRow =
        StoredOperationTaskRow(
            task.id,
            task.connectionId,
            scopeKindOf(task.scope),
            connectionIdOf(task.scope),
            accountIdOf(task.scope),
            zoneIdOf(task.scope),
            userIdOf(task.scope),
            organizationIdOf(task.scope),
            task.actionKey,
            task.resourceRef?.kind,
            task.resourceRef?.externalId,
            task.requestFingerprint,
            task.desiredHash,
            task.state.name,
            task.safeDetail,
            task.providerRequestId,
            task.pollPath,
            task.createdAtMs,
            task.updatedAtMs,
        )

    /**
     * Decodes a stored row strictly. A future *state* name is read as UNKNOWN, but a
     * structurally corrupt row (unknown scope kind, missing or extra scope fields,
     * inconsistent resource reference, invalid fingerprint, time or poll path) is a
     * stable error that never echoes the stored value.
     */
    fun fromRow(row: StoredOperationTaskRow): OperationTask {
        val scope = scopeOfStrict(row)
        val resourceRef = resourceRefOfStrict(row, scope)
        return OperationTask(
            row.id,
            row.connectionId,
            scope,
            row.actionKey,
            resourceRef,
            row.requestFingerprint,
            row.desiredHash,
            TaskState.parse(row.state),
            row.safeDetail,
            row.providerRequestId,
            row.pollPath,
            row.createdAtMs,
            row.updatedAtMs,
        )
    }

    fun summaryFor(task: OperationTask): Operation =
        Operation(task.id, task.connectionId, summaryTitleFor(task.state), summaryStatusFor(task.state), summaryDetailFor(task.state))

    /**
     * The legacy summary is derived from the task state with fixed public text, so
     * caller text, provider text and the task's own detail never reach the legacy
     * operation row.
     */
    fun requireSummaryMatches(task: OperationTask, summary: Operation) {
        if (summary.id != task.id) throw invalidInputFailure()
        if (summary.connectionId != task.connectionId) throw invalidInputFailure()
        if (summary.status != summaryStatusFor(task.state)) throw invalidInputFailure()
    }

    fun summaryStatusFor(state: TaskState): OperationStatus =
        when (state) {
            TaskState.SUCCEEDED -> OperationStatus.SUCCESS
            TaskState.FAILED -> OperationStatus.FAILED
            // Accepted, partial, queued, running, reconciling and unknown outcomes are
            // never reported as a completed operation.
            else -> OperationStatus.UNKNOWN
        }

    fun summaryTitleFor(state: TaskState): String = "Cloudflare 操作"

    fun summaryDetailFor(state: TaskState): String =
        when (state) {
            TaskState.SUCCEEDED -> "已确认完成"
            TaskState.FAILED -> "未完成，请检查输入或权限后重试"
            TaskState.ACCEPTED -> "已被接受，尚未完成；结果待核对，请勿重复提交"
            TaskState.PARTIAL -> "部分完成；请核对结果后再操作"
            else -> "结果待核对，请刷新后检查"
        }

    fun stateOf(raw: String?): TaskState = TaskState.parse(raw)

    /** Legacy statuses keep their three names; a future value falls back to UNKNOWN. */
    fun operationStatusOf(raw: String?): OperationStatus {
        val name = raw?.trim() ?: return OperationStatus.UNKNOWN
        return OperationStatus.entries.firstOrNull { it.name == name } ?: OperationStatus.UNKNOWN
    }
}

private fun connectionIdOf(scope: CloudScope): String =
    when (scope) {
        is AccountScope -> scope.connectionId
        is ZoneScope -> scope.connectionId
        is UserScope -> scope.connectionId
        is OrganizationScope -> scope.connectionId
    }

private fun scopeKindOf(scope: CloudScope): String =
    when (scope) {
        is AccountScope -> "ACCOUNT"
        is ZoneScope -> "ZONE"
        is UserScope -> "USER"
        is OrganizationScope -> "ORGANIZATION"
    }

private fun accountIdOf(scope: CloudScope): String? =
    when (scope) {
        is AccountScope -> scope.accountId
        is ZoneScope -> scope.account.accountId
        else -> null
    }

private fun zoneIdOf(scope: CloudScope): String? = (scope as? ZoneScope)?.zoneId

private fun userIdOf(scope: CloudScope): String? = (scope as? UserScope)?.userId

private fun organizationIdOf(scope: CloudScope): String? = (scope as? OrganizationScope)?.organizationId

private fun scopeOfStrict(row: StoredOperationTaskRow): CloudScope {
    if (row.scopeConnectionId.isBlank()) throw invalidInputFailure()
    return when (row.scopeKind) {
        "ACCOUNT" -> {
            val accountId = row.accountId ?: throw invalidInputFailure()
            requireNoExtras(row, zone = false, user = false, organization = false)
            AccountScope(row.scopeConnectionId, accountId)
        }
        "ZONE" -> {
            val accountId = row.accountId ?: throw invalidInputFailure()
            val zoneId = row.zoneId ?: throw invalidInputFailure()
            requireNoExtras(row, zone = true, user = false, organization = false)
            ZoneScope(AccountScope(row.scopeConnectionId, accountId), zoneId)
        }
        "USER" -> {
            val userId = row.userId ?: throw invalidInputFailure()
            requireNoExtras(row, zone = false, user = true, organization = false)
            UserScope(row.scopeConnectionId, userId)
        }
        "ORGANIZATION" -> {
            val organizationId = row.organizationId ?: throw invalidInputFailure()
            requireNoExtras(row, zone = false, user = false, organization = true)
            OrganizationScope(row.scopeConnectionId, organizationId)
        }
        else -> throw invalidInputFailure()
    }
}

private fun requireNoExtras(row: StoredOperationTaskRow, zone: Boolean, user: Boolean, organization: Boolean) {
    if (row.accountId == null && row.scopeKind != "USER" && row.scopeKind != "ORGANIZATION") throw invalidInputFailure()
    if (!zone && row.zoneId != null) throw invalidInputFailure()
    if (!user && row.userId != null) throw invalidInputFailure()
    if (!organization && row.organizationId != null) throw invalidInputFailure()
    if (row.scopeKind == "USER" && (row.accountId != null || row.zoneId != null || row.organizationId != null)) {
        throw invalidInputFailure()
    }
    if (row.scopeKind == "ORGANIZATION" && (row.accountId != null || row.zoneId != null || row.userId != null)) {
        throw invalidInputFailure()
    }
}

private fun resourceRefOfStrict(row: StoredOperationTaskRow, scope: CloudScope): ResourceRef? {
    val kind = row.resourceKind
    val externalId = row.resourceExternalId
    if (kind == null && externalId == null) return null
    if (kind == null || externalId == null) throw invalidInputFailure()
    return ResourceRef(scope, kind, externalId)
}
