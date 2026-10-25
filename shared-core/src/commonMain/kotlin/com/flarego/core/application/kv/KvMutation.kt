package com.flarego.core.application.kv

import com.flarego.core.model.*

/** Memory-only command. Content never enters a plan, fingerprint or recoverable task. */
sealed class KvMutation(val account: AccountScope, val namespaceId: String?, val action: String) {
    class Create(account: AccountScope, val title: String) : KvMutation(account, null, op("create-a-namespace"))
    class Rename(val namespace: KvNamespaceRef, val title: String) : KvMutation(namespace.account, namespace.id, op("rename-a-namespace"))
    class DeleteNamespace(val namespace: KvNamespaceRef) : KvMutation(namespace.account, namespace.id, op("remove-a-namespace"))
    class Put(val target: KvKeyTarget, val input: KvValueInput) : KvMutation(target.namespace.account, target.namespace.id, op("write-key-value-pair-with-metadata"))
    class DeleteKey(val target: KvKeyTarget) : KvMutation(target.namespace.account, target.namespace.id, op("delete-key-value-pair"))
    class BulkWrite(val namespace: KvNamespaceRef, val input: KvBulkWriteInput) : KvMutation(namespace.account, namespace.id, op("write-multiple-key-value-pairs"))
    class BulkDelete(val namespace: KvNamespaceRef, keys: List<String>) : KvMutation(namespace.account, namespace.id, op("delete-multiple-key-value-pairs")) {
        val keys = keys.toList()
    }
    final override fun toString() = "KvMutation(action=$action, content=redacted)"
    companion object { private fun op(suffix: String) = "workers-kv-namespace-$suffix" }
}

data class KvLiveSelection(val connection: Connection, val credentialRevision: Long, val epoch: Long, val command: KvMutation?)
enum class KvConfirmationStage { RISK, TYPED, FINAL }
/** Instance identity is issued by the runtime; a copied preview grants no authority. */
class KvConfirmationHandle internal constructor(val stage: KvConfirmationStage)
data class KvConfirmationView(
    val handle: KvConfirmationHandle, val account: AccountScope, val namespaceId: String?,
    val namespaceTitle: String, val action: String, val keys: List<String>,
    val expiration: String, val metadata: String, val bytes: Long?, val warning: String,
)
data class KvSubmission(val task: OperationTask, val receipt: KvWriteReceipt?, val created: KvNamespace?)

object KvTaskTargets {
    const val NAMESPACE = "kv_namespace"
    const val ACCOUNT = "kv_account"
    fun ref(account: AccountScope, namespace: String?) = if (namespace == null) ResourceRef(account, ACCOUNT, account.accountId)
        else ResourceRef(account, NAMESPACE, KvValidation.namespace(namespace))
    fun unsettled(state: TaskState) = state in setOf(TaskState.QUEUED, TaskState.RUNNING, TaskState.UNKNOWN, TaskState.PARTIAL, TaskState.RECONCILING)
}
