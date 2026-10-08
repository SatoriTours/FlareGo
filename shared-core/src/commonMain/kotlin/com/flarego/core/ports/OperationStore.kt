package com.flarego.core.ports

import com.flarego.core.model.Operation
import com.flarego.core.model.OperationTask

/**
 * Recoverable task metadata, separate from the legacy [LocalStore] operation list.
 *
 * [putWithSummary] must persist the task and its legacy summary in one
 * transaction, so a summary failure never leaves a task without its summary.
 */
interface OperationStore {
    fun get(id: String): OperationTask?

    fun list(connectionId: String): List<OperationTask>

    fun put(task: OperationTask)

    fun removeConnection(connectionId: String)

    fun putWithSummary(task: OperationTask, summary: Operation)
}
