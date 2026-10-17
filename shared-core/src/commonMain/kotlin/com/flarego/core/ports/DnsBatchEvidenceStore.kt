package com.flarego.core.ports

import com.flarego.core.model.*

/** All three records are committed in one transaction, inside the coordinator metadata latch. */
interface DnsBatchEvidenceStore : OperationStore {
    fun batchEvidence(taskId: String): DnsBatchTaskEvidence?
    fun putWithSummaryAndEvidence(task: OperationTask, summary: Operation, evidence: DnsBatchTaskEvidence)
}

/** Memory staging only; the existing coordinator's canonical store call persists this atomically. */
interface DnsBatchEvidenceBinding : DnsBatchEvidenceStore {
    fun stageBatchEvidence(task: OperationTask, evidence: DnsBatchTaskEvidence)
}
