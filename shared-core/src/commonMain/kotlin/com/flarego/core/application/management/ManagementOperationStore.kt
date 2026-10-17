package com.flarego.core.application.management

import com.flarego.core.model.*
import com.flarego.core.ports.*
import com.flarego.core.application.dns.DNS_BATCH_KEY
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** One coordinator/store domain. Demo metadata is explicitly volatile and atomically grouped. */
@OptIn(ExperimentalAtomicApi::class)
internal class ManagementOperationStore(private val real:OperationStore,private val fingerprint:FingerprintPort,private val isDemo:(String)->Boolean):DnsBatchEvidenceBinding {
    private data class Examples(val tasks:Map<String,OperationTask> = emptyMap(),val summaries:Map<String,Operation> = emptyMap(),val evidence:Map<String,DnsBatchTaskEvidence> = emptyMap())
    private val examples=AtomicReference(Examples())
    private val staged=AtomicReference<Map<String,DnsBatchTaskEvidence>>(emptyMap())
    override fun get(id:String)=examples.load().tasks[id]?:real.get(id)
    override fun list(connectionId:String)=if(isDemo(connectionId)) examples.load().tasks.values.filter { it.connectionId==connectionId } else real.list(connectionId)
    override fun batchEvidence(taskId:String):DnsBatchTaskEvidence? {
        val demo=examples.load()
        val proof=if(taskId in demo.tasks) demo.evidence[taskId] else (real as? DnsBatchEvidenceStore)?.batchEvidence(taskId)
        val task=get(taskId)?:return null
        return proof?.takeIf { runCatching { it.requireValid(task,fingerprint) }.isSuccess }
    }
    override fun stageBatchEvidence(task:OperationTask,evidence:DnsBatchTaskEvidence) {
        if(!isDemo(task.connectionId) && real !is DnsBatchEvidenceStore) throw batchUnavailable()
        val frozen=evidence.copy(counts=evidence.counts.toList(),items=evidence.items.toList())
        frozen.requireValid(task,fingerprint)
        batchEvidence(task.id)?.let { progress(it,frozen) }
        while(true) { val old=staged.load();if(staged.compareAndSet(old,old+(task.id to frozen))) return }
    }
    override fun putWithSummaryAndEvidence(task:OperationTask,summary:Operation,evidence:DnsBatchTaskEvidence) {
        val frozen=evidence.copy(counts=evidence.counts.toList(),items=evidence.items.toList())
        frozen.requireValid(task,fingerprint);OperationTaskPersistence.requireSummaryMatches(task,summary)
        if(!isDemo(task.connectionId)) {
            val port=real as? DnsBatchEvidenceStore ?: throw batchUnavailable()
            port.putWithSummaryAndEvidence(task,summary,frozen);return
        }
        update { old ->
            old.evidence[task.id]?.let { progress(it,frozen) }
            old.copy(tasks=old.tasks+(task.id to task),summaries=old.summaries+(task.id to OperationTaskPersistence.summaryFor(task)),evidence=old.evidence+(task.id to frozen))
        }
    }
    override fun put(task:OperationTask) {
        if(!isDemo(task.connectionId)) { real.put(task);return }
        update { old -> old.evidence[task.id]?.requireValid(task,fingerprint);old.copy(tasks=old.tasks+(task.id to task)) }
    }
    override fun putWithSummary(task:OperationTask,summary:Operation) {
        OperationTaskPersistence.requireSummaryMatches(task,summary)
        val evidence=staged.load()[task.id]?:batchEvidence(task.id)
        if(evidence!=null) {
            putWithSummaryAndEvidence(task,summary,evidence)
            if(task.state !in setOf(TaskState.QUEUED,TaskState.RUNNING)) while(true) { val old=staged.load();if(staged.compareAndSet(old,old-task.id)) break }
            return
        }
        if(task.actionKey==DNS_BATCH_KEY && task.state==TaskState.QUEUED) throw batchUnavailable()
        if(!isDemo(task.connectionId)) real.putWithSummary(task,summary)
        else update { old -> old.copy(tasks=old.tasks+(task.id to task),summaries=old.summaries+(task.id to OperationTaskPersistence.summaryFor(task))) }
    }
    override fun removeConnection(connectionId:String) {
        update { old -> old.copy(tasks=old.tasks.filterValues { it.connectionId!=connectionId },summaries=old.summaries.filterValues { it.connectionId!=connectionId },evidence=old.evidence.filterValues { it.scope.connectionId!=connectionId }) }
        while(true) { val old=staged.load();if(staged.compareAndSet(old,old.filterValues { it.scope.connectionId!=connectionId })) break }
        if(!isDemo(connectionId)) real.removeConnection(connectionId)
    }
    private fun progress(old:DnsBatchTaskEvidence,next:DnsBatchTaskEvidence) {
        if(old.copy(items=next.items,transaction=next.transaction)!=next ||
            (old.transaction!=DnsBatchTransaction.UNKNOWN && old.transaction!=next.transaction) ||
            old.items.zip(next.items).any { (a,b) -> a.copy(providerId=b.providerId)!=b || (a.providerId!=null && a.providerId!=b.providerId) }) throw invalidInputFailure()
    }
    private fun update(block:(Examples)->Examples) { while(true) { val old=examples.load();if(examples.compareAndSet(old,block(old))) return } }
}
