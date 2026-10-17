package com.flarego.core.application.dns

import com.flarego.core.model.*
import com.flarego.core.ports.OperationStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

const val DNS_BATCH_KEY = "dns-records-for-a-zone-batch-dns-records"
const val DNS_IMPORT_KEY = "dns-records-for-a-zone-import-dns-records"
const val DNS_SCAN_APPLY_KEY = "dns-records-for-a-zone-apply-dns-scan-results"
const val DNS_SETTINGS_PATCH_KEY = "dns-settings-for-a-zone-update-dns-settings"
val DNS_MUTATION_KEYS: Set<String> = DNS_WRITE_KEYS + setOf(
    DNS_BATCH_KEY, DNS_IMPORT_KEY, DNS_SCAN_APPLY_KEY, DNS_SETTINGS_PATCH_KEY,
)
val DNS_MUTATION_UNSETTLED: Set<TaskState> = setOf(TaskState.QUEUED,TaskState.RUNNING,TaskState.ACCEPTED,TaskState.PARTIAL,TaskState.UNKNOWN,TaskState.RECONCILING)

/** Durable admission check, not a lock. Actual app sends must still hold the one runtime write lock. */
class DnsUncertaintyGate(private val store:OperationStore,private val dispatcher:CoroutineDispatcher) {
    /** excludingTaskId is only the currently issued execution, never a user-supplied bypass. */
    suspend fun requireClear(scope:ZoneScope,incomingActionKey:String,target:ResourceRef?=null,excludingTaskId:String?=null) {
        if (incomingActionKey !in DNS_MUTATION_KEYS || (target != null && target.scope != scope))
            throw CloudFailure(CloudFailureCode.INVALID_INPUT, "DNS 写入范围或操作无效")
        val blocked = withContext(dispatcher) { store.list(scope.connectionId) }.any { task ->
            if (task.id == excludingTaskId || task.scope != scope || task.state !in DNS_MUTATION_UNSETTLED ||
                task.actionKey !in DNS_MUTATION_KEYS) false
            else if (incomingActionKey !in DNS_WRITE_KEYS || task.actionKey !in DNS_WRITE_KEYS) true
            else {
                val prior = task.resourceRef
                prior == null || target == null || prior == target ||
                    prior.kind != "dns_record" || target.kind != "dns_record"
            }
        }
        if (blocked) throw CloudFailure(CloudFailureCode.UNKNOWN_RESULT,
            "该 Zone 的 DNS 写入结果尚未核对，请先只读核对已有任务", uncertain = true)
    }
}
