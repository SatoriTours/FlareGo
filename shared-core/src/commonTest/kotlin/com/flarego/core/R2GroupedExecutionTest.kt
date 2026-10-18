package com.flarego.core

import com.flarego.core.application.operations.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class R2GroupedExecutionTest {
    @Test fun sameBucketSerializesDistinctLocatorsAndCancelledUiWaiterDoesNotReleaseAppLock() = runTest {
        val connection = Connection("local", "fixture", "account")
        val account = AccountScope("local", "account")
        var current = LiveOperationContext(connection, account, 1, "a".repeat(64))
        val resolver = object : OperationContextResolver {
            override fun current() = current
            override fun liveConnections() = listOf(connection)
            override fun isLive(scope: CloudScope, credentialRevision: Long?) = scope == account && (credentialRevision == null || credentialRevision == 1L)
        }
        val rows = mutableMapOf<String, OperationTask>()
        val store = object : OperationStore {
            override fun get(id: String) = rows[id]
            override fun list(connectionId: String) = rows.values.toList()
            override fun put(task: OperationTask) { rows[task.id] = task }
            override fun putWithSummary(task: OperationTask, summary: Operation) { put(task) }
            override fun removeConnection(connectionId: String) { rows.clear() }
        }
        var id = 0
        val clock = OperationClock { 1_000 }
        val plans = ChangePlans(resolver, clock, OperationIds { "id-${++id}" })
        val coordinator = OperationCoordinator(plans, store, resolver, clock, backgroundScope, StandardTestDispatcher(testScheduler))
        fun confirmed(key: String, kind: String, locator: String, hash: String): ConfirmedPlan {
            current = current.copy(requestFingerprint = hash)
            val p = plans.prepare(account, key, ResourceRef(account, kind, locator), null, null, hash, 1, 1_000)
            return plans.confirm(p.id, account, 1, hash, 1_000)
        }
        val first = confirmed("r2-delete-object", "r2_object", "my-bucket/a.txt", "a".repeat(64))
        val second = confirmed("r2-delete-bucket", "r2_bucket", "my-bucket", "b".repeat(64))
        current = current.copy(requestFingerprint = "a".repeat(64))
        val gate = CompletableDeferred<Unit>()
        val group = ResourceRef(account, "r2_bucket", "my-bucket")
        var firstSent = false; var secondSent = false
        val waiter = backgroundScope.async {
            coordinator.executeGrouped(first, {}, group) { guard -> guard(); firstSent = true; gate.await(); ExecutionReceipt.Completed() }
        }
        runCurrent(); assertTrue(firstSent)
        current = current.copy(requestFingerprint = "b".repeat(64))
        val secondWaiter = backgroundScope.async {
            coordinator.executeGrouped(second, {}, group) { guard -> guard(); secondSent = true; ExecutionReceipt.Completed() }
        }
        runCurrent(); assertFalse(secondSent)
        waiter.cancel(); runCurrent(); assertFalse(secondSent)
        gate.complete(Unit); runCurrent()
        assertTrue(secondSent)
        assertEquals(TaskState.SUCCEEDED, secondWaiter.await().state)
        assertEquals("my-bucket/a.txt", rows.getValue(first.plan.id).resourceRef!!.externalId)
    }
}
