package com.flarego.core.application.r2

import com.flarego.core.application.operations.ConfirmedPlan
import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.operations.OperationCoordinator
import com.flarego.core.model.OperationTask
import com.flarego.core.model.ResourceRef
import kotlinx.coroutines.CancellationException

/**
 * The single trusted dispatch-evidence protocol of the R2 write adapters.
 *
 * Both batch A and batch B go through it: only evidence that the typed send was really
 * entered can turn a local failure into a terminal "not dispatched"; every failure after
 * the guard completes stays possibly-sent, and an adapter that reports success without
 * ever running the mandatory guard is reported as unknown, never as success.
 */
internal suspend fun OperationCoordinator.executeR2Dispatch(
    confirmed: ConfirmedPlan,
    guard: () -> Unit,
    group: ResourceRef,
    write: suspend (beforeSend: () -> Unit) -> ExecutionReceipt,
): OperationTask = executeGrouped(confirmed, guard, group) { composed ->
    var mayHaveDispatched = false
    try {
        val receipt = write { composed(); mayHaveDispatched = true }
        // Missing the mandatory guard is an adapter contract violation, not success.
        if (mayHaveDispatched) receipt else ExecutionReceipt.Unknown()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (mayHaveDispatched) throw failure
        ExecutionReceipt.NotDispatched()
    }
}
