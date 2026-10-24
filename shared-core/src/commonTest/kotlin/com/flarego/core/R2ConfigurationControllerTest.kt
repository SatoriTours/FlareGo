package com.flarego.core

import com.flarego.core.application.operations.ChangePlan
import com.flarego.core.application.operations.ChangePlans
import com.flarego.core.application.operations.ConfirmedPlan
import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.operations.LiveOperationContext
import com.flarego.core.application.operations.OperationClock
import com.flarego.core.application.operations.OperationContextResolver
import com.flarego.core.application.operations.OperationCoordinator
import com.flarego.core.application.operations.OperationIds
import com.flarego.core.application.r2.R2ConfigurationController
import com.flarego.core.application.r2.r2ConfigurationWriteBody
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.CloudScope
import com.flarego.core.model.Connection
import com.flarego.core.model.Operation
import com.flarego.core.model.OperationTask
import com.flarego.core.model.R2ChangePreview
import com.flarego.core.model.R2ConfigurationDocument
import com.flarego.core.model.R2ConfigurationKind
import com.flarego.core.model.R2ConfigurationWrite
import com.flarego.core.model.R2CorsAllowed
import com.flarego.core.model.R2CorsRule
import com.flarego.core.model.R2LifecycleCondition
import com.flarego.core.model.R2LifecycleConditions
import com.flarego.core.model.R2LifecycleRule
import com.flarego.core.model.R2LockCondition
import com.flarego.core.model.R2LockRule
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.TaskState
import com.flarego.core.ports.FingerprintPort
import com.flarego.core.ports.OperationStore
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Batch B1 configuration write lifecycle over the real shared issuer/coordinator:
 * preview is read-only, one confirmation is one send, the complete observable target is
 * compared under the same lock, uncertainty is never upgraded and never replayed, and a
 * cancelled UI waiter cannot cancel the app-owned read-back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R2ConfigurationControllerTest {
    private val connection = Connection("local", "fixture", "account-one")
    private val account = AccountScope("local", "account-one")
    private val target = R2WriteTarget(account, "my-bucket")
    private val zero = "0".repeat(64)

    private class Store : OperationStore {
        val rows = mutableMapOf<String, OperationTask>()
        override fun get(id: String) = rows[id]
        override fun list(connectionId: String) = rows.values.filter { it.connectionId == connectionId }
        override fun put(task: OperationTask) { rows[task.id] = task }
        override fun putWithSummary(task: OperationTask, summary: Operation) = put(task)
        override fun removeConnection(connectionId: String) {
            rows.entries.removeAll { it.value.connectionId == connectionId }
        }
    }

    private val corsRule = R2CorsRule(id = "one", allowed = R2CorsAllowed(listOf("GET"), listOf("https://a.example")))

    private fun putCors(
        rules: List<R2CorsRule> = listOf(corsRule),
        on: R2WriteTarget = target,
    ) = R2ConfigurationWrite.PutCors(on, rules)

    private fun jsonRules(origin: String, id: String = "one") =
        """{"rules":[{"id":"$id","allowed":{"methods":["GET"],"origins":["$origin"],"headers":[]},"exposeHeaders":[]}]}"""

    private inner class Harness(scope: TestScope) {
        val session = FakeConfigurationSession(account).apply { emptyRules(target) }
        var live = LiveOperationContext(connection, account, 1, zero)
        var revision = 1L
        var now = 1_000L
        var routeEpoch = 1L
        var ids = 0
        var command: R2ConfigurationWrite? = null
        val store = Store()
        val fingerprint = FingerprintPort { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
        var fingerprintOf: (R2ConfigurationWrite) -> String = { zero }
        val resolver = object : OperationContextResolver {
            override fun current(): LiveOperationContext =
                live.copy(requestFingerprint = command?.let { fingerprintOf(it) } ?: zero)

            override fun liveConnections(): List<Connection> = listOf(connection)

            override fun isLive(scope: CloudScope, credentialRevision: Long?): Boolean =
                scope == account && revision >= 0 && (credentialRevision == null || credentialRevision == revision)
        }
        val plans = ChangePlans(resolver, OperationClock { now }, OperationIds { "cfg-${++ids}" }, retentionLimit = 64)
        val coordinator = OperationCoordinator(
            plans, store, resolver, OperationClock { now }, scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler),
        )
        val controller = R2ConfigurationController(
            resolver, plans, coordinator, OperationClock { now }, fingerprint, store,
            StandardTestDispatcher(scope.testScheduler), { routeEpoch },
        ) {
            session.open()
            session
        }

        init {
            fingerprintOf = { write -> controller.requestFingerprint(write) }
        }

        fun select(write: R2ConfigurationWrite) {
            command = write
            live = live.copy(requestFingerprint = controller.requestFingerprint(write))
        }

        suspend fun prepare(write: R2ConfigurationWrite): ChangePlan {
            select(write)
            return controller.prepare(write)
        }

        fun preview(plan: ChangePlan): R2ChangePreview = controller.preview(plan.id)

        fun confirm(write: R2ConfigurationWrite, plan: ChangePlan): ConfirmedPlan =
            plans.confirm(plan.id, account, live.credentialRevision, controller.requestFingerprint(write), now)
    }

    @Test
    fun previewReadsOnlyAndOneConfirmationIsExactlyOneSend() = runTest {
        val h = Harness(this)
        val write = putCors()
        val plan = h.prepare(write)
        val preview = h.preview(plan)
        assertTrue(preview.impact.contains("CORS"))
        assertEquals(0, h.session.sends.size)
        assertEquals(1, h.session.reads.size)
        val confirmed = h.confirm(write, plan)
        val first = h.controller.submit(write, confirmed)
        assertEquals(TaskState.SUCCEEDED, first.state)
        assertEquals(1, h.session.sends.size)
        assertEquals(1, h.session.readsAfterADispatch, "one independent read-back under the same lock")
        val second = h.controller.submit(write, confirmed)
        assertEquals(first.id, second.id)
        assertEquals(1, h.session.sends.size)
    }

    @Test
    fun thePreviewNamesTheFullReplacementAndTheJurisdiction() = runTest {
        val h = Harness(this)
        val eu = R2WriteTarget(account, "my-bucket", "eu")
        h.session.emptyRules(eu)
        val plan = h.prepare(putCors(on = eu))
        val preview = h.preview(plan)
        assertTrue(preview.impact.contains("整组") || preview.impact.contains("替换"))
        assertTrue(preview.concurrencyWarning.contains("eu"), "a non-default target is never presented as the default bucket")
    }

    @Test
    fun aSnapshotThatDriftedAfterThePreviewIsRefusedBeforeTheSingleSend() = runTest {
        val h = Harness(this)
        val write = putCors()
        val plan = h.prepare(write)
        h.session.put(target, R2ConfigurationKind.CORS, jsonRules("https://drifted.example"))
        val task = h.controller.submit(write, h.confirm(write, plan))
        assertEquals(TaskState.FAILED, task.state)
        assertEquals(0, h.session.sends.size)
        assertEquals(0, h.session.readsAfterADispatch)
    }

    @Test
    fun aContradictoryPostWriteSnapshotStaysUnknownAndIsNeverReplayed() = runTest {
        val h = Harness(this)
        h.session.onApply = { h.session.put(target, R2ConfigurationKind.CORS, jsonRules("https://other.example")) }
        val write = putCors()
        val task = h.controller.submit(write, h.confirm(write, h.prepare(write)))
        assertEquals(TaskState.UNKNOWN, task.state)
        assertEquals(1, h.session.sends.size)
        assertEquals(1, h.session.readsAfterADispatch)
        assertEquals(TaskState.UNKNOWN, h.controller.reconcile(task.id).state)
        assertEquals(1, h.session.sends.size, "reconciliation reads; it never replays the write")
    }

    @Test
    fun aTargetWithTheRightRuleCountButDifferentContentIsNotSuccess() = runTest {
        val h = Harness(this)
        h.session.onApply = { h.session.put(target, R2ConfigurationKind.CORS, jsonRules("https://other.example")) }
        val write = putCors()
        assertEquals(TaskState.UNKNOWN, h.controller.submit(write, h.confirm(write, h.prepare(write))).state)
    }

    @Test
    fun anAcceptedReceiptIsNeverUpgradedByAReadBack() = runTest {
        val h = Harness(this)
        h.session.receipt = ExecutionReceipt.Accepted()
        val write = putCors()
        val task = h.controller.submit(write, h.confirm(write, h.prepare(write)))
        assertEquals(TaskState.ACCEPTED, task.state)
        assertEquals(1, h.session.sends.size)
        assertEquals(0, h.session.readsAfterADispatch)
    }

    @Test
    fun aDocumentWithUnknownContentOrAnOversizedTargetIsNeverPreviewed() = runTest {
        val h = Harness(this)
        h.session.put(target, R2ConfigurationKind.CORS, """{"rules":[],"future":true}""")
        val failure = assertFailsWith<CloudFailure> { h.prepare(putCors()) }
        assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY, failure.code)
        assertEquals(0, h.session.sends.size)
        assertEquals(0, h.session.writes.size)
    }

    @Test
    fun deletingSucceedsOnlyWhenTheCurrentReadExposesNoRulesAndANotFoundIsNotAbsence() = runTest {
        val emptyAfterDelete = Harness(this)
        emptyAfterDelete.session.onApply = {
            emptyAfterDelete.session.put(target, R2ConfigurationKind.CORS, """{"rules":[]}""")
        }
        val delete = R2ConfigurationWrite.DeleteCors(target)
        assertEquals(TaskState.SUCCEEDED,
            emptyAfterDelete.controller.submit(delete, emptyAfterDelete.confirm(delete, emptyAfterDelete.prepare(delete))).state)

        val notFoundAfterDelete = Harness(this)
        notFoundAfterDelete.session.onApply = { notFoundAfterDelete.session.remove(target, R2ConfigurationKind.CORS) }
        val second = R2ConfigurationWrite.DeleteCors(target)
        val task = notFoundAfterDelete.controller.submit(
            second, notFoundAfterDelete.confirm(second, notFoundAfterDelete.prepare(second)),
        )
        assertEquals(TaskState.UNKNOWN, task.state, "an arbitrary 404 is not proof of absence")
        assertEquals(1, notFoundAfterDelete.session.sends.size)
    }

    @Test
    fun aTrustedPreGuardRefusalIsTerminalWhileAAnyPostGuardErrorStaysRunning() = runTest {
        val before = Harness(this)
        before.session.refuseBeforeSend = true
        val write = putCors()
        val refused = before.controller.submit(write, before.confirm(write, before.prepare(write)))
        assertEquals(TaskState.FAILED, refused.state)
        assertEquals(0, before.session.sends.size)
        val reads = before.session.reads.size
        assertEquals(TaskState.FAILED, before.controller.reconcile(refused.id).state)
        assertEquals(reads, before.session.reads.size, "a settled refusal is not turned into uncertainty")

        val after = Harness(this)
        after.session.refuseAfterGuard = true
        val other = putCors()
        val running = after.controller.submit(other, after.confirm(other, after.prepare(other)))
        assertEquals(TaskState.RUNNING, running.state, "a post-guard failure may have been sent")
    }

    @Test
    fun anAdapterThatNeverRunsTheMandatoryGuardCannotClaimSuccess() = runTest {
        val h = Harness(this)
        h.session.skipGuard = true
        val write = putCors()
        assertEquals(TaskState.UNKNOWN, h.controller.submit(write, h.confirm(write, h.prepare(write))).state)
        assertEquals(1, h.session.sends.size)
    }

    @Test
    fun localUploadsFalseIsSentExplicitlyAndReadBackAgain() = runTest {
        val h = Harness(this)
        h.session.put(target, R2ConfigurationKind.LOCAL_UPLOADS, """{"enabled":true}""")
        val write = R2ConfigurationWrite.PutLocalUploads(target, false)
        val task = h.controller.submit(write, h.confirm(write, h.prepare(write)))
        assertEquals(TaskState.SUCCEEDED, task.state)
        val sent = h.session.sends.single()
        assertEquals(false, r2ConfigurationWriteBody(sent)!!.getValue("enabled").jsonPrimitive.boolean)
        assertEquals(1, h.session.readsAfterADispatch)
    }

    @Test
    fun nonDefaultLocalUploadsIsRefusedBeforeAnySessionOrRequest() = runTest {
        val h = Harness(this)
        val failure = assertFailsWith<CloudFailure> {
            h.prepare(R2ConfigurationWrite.PutLocalUploads(R2WriteTarget(account, "my-bucket", "eu"), true))
        }
        assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY, failure.code)
        assertEquals(0, h.session.sessionOpens)
        assertTrue(h.session.reads.isEmpty())
        assertTrue(h.session.writes.isEmpty())
    }

    @Test
    fun cancellingTheUiWaiterDoesNotCancelTheAppOwnedReadBackOrResend() = runTest {
        val h = Harness(this)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.session.readBackGate = { gate.await() }
        val write = putCors()
        val confirmed = h.confirm(write, h.prepare(write))
        val waiter = async { h.controller.submit(write, confirmed) }
        runCurrent()
        assertEquals(TaskState.RUNNING, h.store.rows.values.single().state)
        assertEquals(1, h.session.sends.size)
        waiter.cancelAndJoin()
        gate.complete(Unit)
        runCurrent()
        assertEquals(TaskState.SUCCEEDED, h.store.rows.values.single().state)
        assertEquals(1, h.session.sends.size)
    }

    @Test
    fun reconcileOfAConfigurationTaskNeverReopensTheSessionForACommand() = runTest {
        val h = Harness(this)
        h.session.receipt = ExecutionReceipt.Unknown()
        val write = putCors()
        val task = h.controller.submit(write, h.confirm(write, h.prepare(write)))
        assertEquals(TaskState.UNKNOWN, task.state)
        assertNotNull(task.resourceRef)
        // The read-only recovery applies the real provider rule set.
        h.session.put(target, R2ConfigurationKind.CORS, jsonRules("https://a.example"))
        assertEquals(TaskState.SUCCEEDED, h.controller.reconcile(task.id).state)
        assertEquals(1, h.session.sends.size)
    }

    private val lifecycleRule = R2LifecycleRule(
        id = "expire",
        enabled = true,
        conditions = R2LifecycleConditions("logs/"),
        deleteObjectsTransition = R2LifecycleCondition.Age(86_400),
        storageClassTransitions = emptyList(),
    )
    private val lockRule = R2LockRule(id = "lock", enabled = true, prefix = "", condition = R2LockCondition.Age(3_600))

    @Test
    fun aProviderDocumentThatOmitsOptionalMembersIsStillTheSameSnapshot() = runTest {
        val h = Harness(this)
        // The pinned schema makes these members optional; an unchanged GET may omit them.
        h.session.put(
            target,
            R2ConfigurationKind.CORS,
            """{"rules":[{"id":"one","allowed":{"methods":["GET"],"origins":["https://a.example"]}}]}""",
        )
        h.session.put(
            target,
            R2ConfigurationKind.LIFECYCLE,
            """{"rules":[{"id":"expire","enabled":true,"conditions":{"prefix":"logs/"},"deleteObjectsTransition":{"condition":{"type":"Age","maxAge":86400}}}]}""",
        )
        h.session.put(
            target,
            R2ConfigurationKind.LOCK,
            """{"rules":[{"id":"lock","enabled":true,"condition":{"type":"Age","maxAgeSeconds":3600}}]}""",
        )
        for (write in listOf(
            putCors(),
            R2ConfigurationWrite.PutLifecycle(target, listOf(lifecycleRule)),
            R2ConfigurationWrite.PutLock(target, listOf(lockRule)),
        )) {
            val task = h.controller.submit(write, h.confirm(write, h.prepare(write)))
            assertEquals(TaskState.SUCCEEDED, task.state, write::class.simpleName.orEmpty())
        }
        assertEquals(3, h.session.sends.size)
    }

    @Test
    fun aDocumentOfAnotherTargetOrKindCanNeverPrepareOrConfirmAWrite() = runTest {
        for (case in listOf("target", "kind")) {
            val h = Harness(this)
            val write = putCors()
            h.session.documentOverride = { requested, kind ->
                R2ConfigurationDocument(
                    if (case == "target") requested.copy(jurisdiction = "eu") else requested,
                    if (case == "kind") R2ConfigurationKind.LIFECYCLE else kind,
                    """{"rules":[]}""".let { kotlinx.serialization.json.Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject },
                    true,
                    null,
                )
            }
            assertFailsWith<CloudFailure> { h.prepare(write) }
            assertEquals(0, h.session.sends.size)
        }
        // A read-back of another target/kind is not success either.
        for (case in listOf("target", "kind")) {
            val h = Harness(this)
            val write = putCors()
            h.session.documentOverride = { requested, kind ->
                if (h.session.sends.isEmpty()) null
                else R2ConfigurationDocument(
                    if (case == "target") requested.copy(jurisdiction = "eu") else requested,
                    if (case == "kind") R2ConfigurationKind.LIFECYCLE else kind,
                    kotlinx.serialization.json.Json.parseToJsonElement("""{"rules":[]}""") as kotlinx.serialization.json.JsonObject,
                    true,
                    null,
                )
            }
            val task = h.controller.submit(write, h.confirm(write, h.prepare(write)))
            assertEquals(TaskState.UNKNOWN, task.state, case)
            assertEquals(1, h.session.sends.size)
        }
    }

    @Test
    fun deletesReachTheControllerAsRealDeleteCommandsAndA404ReadBackStaysUnknown() = runTest {
        val cors = Harness(this)
        val deleteCors = R2ConfigurationWrite.DeleteCors(target)
        cors.session.onApply = { cors.session.remove(target, R2ConfigurationKind.CORS) }
        assertEquals(TaskState.UNKNOWN,
            cors.controller.submit(deleteCors, cors.confirm(deleteCors, cors.prepare(deleteCors))).state)
        assertEquals(listOf<R2ConfigurationWrite>(deleteCors), cors.session.sends)

        val lock = Harness(this)
        lock.session.onApply = { lock.session.put(target, R2ConfigurationKind.LOCK, """{"rules":[]}""") }
        val deleteLock = R2ConfigurationWrite.DeleteLock(target)
        assertEquals(TaskState.SUCCEEDED,
            lock.controller.submit(deleteLock, lock.confirm(deleteLock, lock.prepare(deleteLock))).state)
        assertEquals(listOf<R2ConfigurationWrite>(deleteLock), lock.session.sends)
    }
}
