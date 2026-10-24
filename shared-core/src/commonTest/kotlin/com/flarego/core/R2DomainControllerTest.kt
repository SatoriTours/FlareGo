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
import com.flarego.core.application.r2.R2DomainConfigurationController
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.CloudScope
import com.flarego.core.model.Connection
import com.flarego.core.model.Operation
import com.flarego.core.model.OperationTask
import com.flarego.core.model.R2ChangePreview
import com.flarego.core.model.R2CustomDomainCreateInput
import com.flarego.core.model.R2CustomDomainEditInput
import com.flarego.core.model.R2DomainConfigurationWrite
import com.flarego.core.model.R2CustomDomain
import com.flarego.core.model.R2CustomDomainStatus
import com.flarego.core.model.R2DomainDocument
import com.flarego.core.model.R2DomainKind
import com.flarego.core.model.R2DomainTarget
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.ResourceRef
import com.flarego.core.model.TaskState
import com.flarego.core.ports.FingerprintPort
import com.flarego.core.ports.OperationStore
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Batch B2 domain write lifecycle over the real shared issuer/coordinator.
 *
 * Each action has its own before/after contract: the managed endpoint compares the managed
 * document, a create proves absence in the complete collection and then the new single domain, an
 * edit compares the complete observable target of that domain, and a delete compares the complete
 * collection without the domain. Domain documents are never squeezed through the B1 `rules[]`
 * normalization.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R2DomainControllerTest {
    private val connection = Connection("local", "fixture", "account-one")
    private val account = AccountScope("local", "account-one")
    private val target = R2WriteTarget(account, "my-bucket")
    private val domain = "cdn.example.com"
    private val zoneId = "0123456789abcdef0123456789abcdef"
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

    private inner class Harness(scope: TestScope) {
        val session = FakeDomainSession(account).apply { open() }
        var live = LiveOperationContext(connection, account, 1, zero)
        var revision = 1L
        var now = 1_000L
        var routeEpoch = 1L
        var ids = 0
        var command: R2DomainConfigurationWrite? = null
        val store = Store()
        val fingerprint = FingerprintPort { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
        var fingerprintOf: (R2DomainConfigurationWrite) -> String = { zero }
        val resolver = object : OperationContextResolver {
            override fun current(): LiveOperationContext =
                live.copy(requestFingerprint = command?.let { fingerprintOf(it) } ?: zero)

            override fun liveConnections(): List<Connection> = listOf(connection)

            override fun isLive(scope: CloudScope, credentialRevision: Long?): Boolean =
                scope == account && revision >= 0 && (credentialRevision == null || credentialRevision == revision)
        }
        val plans = ChangePlans(resolver, OperationClock { now }, OperationIds { "dom-${++ids}" }, retentionLimit = 64)
        val coordinator = OperationCoordinator(
            plans, store, resolver, OperationClock { now }, scope.backgroundScope,
            StandardTestDispatcher(scope.testScheduler),
        )
        val controller = R2DomainConfigurationController(
            resolver, plans, coordinator, OperationClock { now }, fingerprint, store,
            StandardTestDispatcher(scope.testScheduler), { routeEpoch },
        ) {
            session.open()
            session
        }

        init {
            fingerprintOf = { write -> controller.requestFingerprint(write) }
        }

        fun select(write: R2DomainConfigurationWrite) {
            command = write
            live = live.copy(requestFingerprint = controller.requestFingerprint(write))
        }

        suspend fun prepare(write: R2DomainConfigurationWrite): ChangePlan {
            select(write)
            return controller.prepare(write)
        }

        fun preview(plan: ChangePlan): R2ChangePreview = controller.preview(plan.id)

        fun confirm(write: R2DomainConfigurationWrite, plan: ChangePlan): ConfirmedPlan =
            plans.confirm(plan.id, account, live.credentialRevision, controller.requestFingerprint(write), now)

        suspend fun run(write: R2DomainConfigurationWrite): OperationTask =
            controller.submit(write, confirm(write, prepare(write)))
    }

    private fun managed(enabled: Boolean) = R2DomainConfigurationWrite.PutManagedDomain(target, enabled)

    private fun add(enabled: Boolean = true) =
        R2DomainConfigurationWrite.AddCustomDomain(
            target,
            R2CustomDomainCreateInput(domain = domain, zoneId = zoneId, enabled = enabled),
        )

    private fun edit(
        enabled: Boolean? = null,
        minTls: String? = null,
    ) = R2DomainConfigurationWrite.EditCustomDomain(target, domain, R2CustomDomainEditInput(enabled, minTls))

    private fun delete() = R2DomainConfigurationWrite.DeleteCustomDomain(target, domain)

    @Test
    fun managedDomainFalseIsSentExplicitlyAndTheReadBackComparesTheManagedDocument() = runTest {
        val h = Harness(this)
        h.session.managed = buildJsonObject {
            put("domain", "pub-abc.r2.dev"); put("enabled", true); put("bucketId", "bucket-id")
        }
        val write = managed(false)
        val task = h.run(write)
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals(listOf<R2DomainConfigurationWrite>(write), h.session.sends)
        assertEquals(1, h.session.readsAfterADispatch)
        assertTrue(task.safeDetail.isNotBlank())
    }

    @Test
    fun addingADomainRequiresAbsenceInTheCompleteCollectionAndVerifiesTheNewDomain() = runTest {
        val h = Harness(this)
        h.session.seedDomain("other.example.com", enabled = false)
        val write = add(enabled = true)
        val task = h.run(write)
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals(listOf<R2DomainConfigurationWrite>(write), h.session.sends)
        assertEquals(listOf(domain, "other.example.com"), h.session.collection.map { (it["domain"] as kotlinx.serialization.json.JsonPrimitive).content }.sorted())
    }

    @Test
    fun addingAnAlreadyPresentDomainOrAChangedCollectionIsRefusedBeforeAnySend() = runTest {
        val present = Harness(this)
        present.session.seedDomain(domain, enabled = false)
        val conflict = assertFailsWith<CloudFailure> { present.prepare(add()) }
        assertEquals(CloudFailureCode.CONFLICT, conflict.code)
        assertEquals(0, present.session.writes.size)

        val drifted = Harness(this)
        drifted.session.seedDomain("other.example.com", enabled = false)
        val plan = drifted.prepare(add())
        drifted.session.seedDomain("concurrent.example.com", enabled = false)
        val task = drifted.controller.submit(add(), drifted.confirm(add(), plan))
        assertEquals(TaskState.FAILED, task.state)
        assertEquals(0, drifted.session.sends.size)
    }

    @Test
    fun editingKeepsEveryPreservedFieldAndComparesTheCompleteObservableTarget() = runTest {
        val h = Harness(this)
        h.session.seedDomain(domain, enabled = true, minTls = "1.2")
        val write = edit(enabled = false)
        val task = h.run(write)
        assertEquals(TaskState.SUCCEEDED, task.state)
        val row = h.session.collection.single()
        assertEquals("false", row.getValue("enabled").jsonPrimitive.content)
        assertEquals("1.2", row.getValue("minTLS").jsonPrimitive.content)
        assertEquals(
            listOf("ECDHE-ECDSA-AES128-GCM-SHA256"),
            (row.getValue("ciphers") as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content },
        )

        val contradicted = Harness(this)
        contradicted.session.seedDomain(domain, enabled = true, minTls = "1.2")
        contradicted.session.onApply = { contradicted.session.collection[0] = FakeDomainSession.json("""{"domain":"$domain","enabled":false,"minTLS":"1.0","ciphers":["ECDHE-ECDSA-AES128-GCM-SHA256"],"zoneId":"$zoneId","zoneName":"example.com","status":{"ownership":"pending","ssl":"initializing"}}""") }
        val write2 = edit(enabled = false)
        val unknown = contradicted.controller.submit(write2, contradicted.confirm(write2, contradicted.prepare(write2)))
        assertEquals(TaskState.UNKNOWN, unknown.state, "a changed preserved field is a contradiction, not success")
        assertEquals(1, contradicted.session.sends.size)
    }

    @Test
    fun deletingRequiresTheCompleteCollectionWithoutTheDomainAndA404IsNotAbsence() = runTest {
        val ok = Harness(this)
        ok.session.seedDomain(domain, enabled = true)
        ok.session.seedDomain("keep.example.com", enabled = true)
        val write = delete()
        assertEquals(TaskState.SUCCEEDED, ok.controller.submit(write, ok.confirm(write, ok.prepare(write))).state)
        assertEquals(listOf("keep.example.com"), ok.session.collection.map { (it["domain"] as kotlinx.serialization.json.JsonPrimitive).content })

        val notFound = Harness(this)
        notFound.session.seedDomain(domain, enabled = true)
        notFound.session.documentOverride = { requested, kind ->
            if (kind == R2DomainKind.CUSTOM_DOMAINS && notFound.session.sends.isNotEmpty()) {
                throw CloudFailure(CloudFailureCode.NOT_FOUND, "合成 404")
            }
            null
        }
        val second = delete()
        assertEquals(TaskState.UNKNOWN, notFound.controller.submit(second, notFound.confirm(second, notFound.prepare(second))).state)
        assertEquals(1, notFound.session.sends.size)
    }

    @Test
    fun deleteRequiresTheDomainToBePresentInTheCompleteCollection() = runTest {
        val h = Harness(this)
        h.session.seedDomain("other.example.com", enabled = true)
        val failure = assertFailsWith<CloudFailure> { h.prepare(delete()) }
        assertEquals(CloudFailureCode.CONFLICT, failure.code)
        assertEquals(0, h.session.writes.size)
    }

    @Test
    fun anAcceptedReceiptIsNotUpgradedAndIsNeverReplayed() = runTest {
        val h = Harness(this)
        h.session.receipt = ExecutionReceipt.Accepted()
        val write = managed(true)
        val task = h.run(write)
        assertEquals(TaskState.ACCEPTED, task.state)
        assertEquals(0, h.session.readsAfterADispatch)
        assertEquals(1, h.session.sends.size)
    }

    @Test
    fun aDriftedDocumentOrAFailedReadBackStaysUnsettledAndIsNeverReplayed() = runTest {
        val drifted = Harness(this)
        drifted.session.seedDomain(domain, enabled = true)
        val write = edit(enabled = false)
        val plan = drifted.prepare(write)
        drifted.session.collection[0] = FakeDomainSession.json(
            """{"domain":"$domain","enabled":true,"minTLS":"1.3","ciphers":["ECDHE-ECDSA-AES128-GCM-SHA256"],"zoneId":"$zoneId","zoneName":"example.com","status":{"ownership":"pending","ssl":"initializing"}}""",
        )
        assertEquals(TaskState.FAILED, drifted.controller.submit(write, drifted.confirm(write, plan)).state)
        assertEquals(0, drifted.session.sends.size)

        val failed = Harness(this)
        failed.session.seedDomain(domain, enabled = true)
        val second = edit(enabled = false)
        val failedPlan = failed.prepare(second)
        // The write is applied, then the same-lock read-back fails: possibly sent, so UNKNOWN.
        failed.session.onApply = { failed.session.failReads = true }
        assertEquals(TaskState.UNKNOWN, failed.controller.submit(second, failed.confirm(second, failedPlan)).state)
        assertEquals(1, failed.session.sends.size)
        assertEquals(TaskState.UNKNOWN, failed.controller.reconcile(failed.store.rows.values.single().id).state)
        assertEquals(1, failed.session.sends.size)
    }

    @Test
    fun aDocumentOfAnotherTargetOrKindCanNeverPrepareOrConfirmADomainWrite() = runTest {
        for (case in listOf("target", "kind")) {
            val h = Harness(this)
            h.session.seedDomain(domain, enabled = true)
            val write = edit(enabled = false)
            h.session.documentOverride = { requested, kind ->
                R2DomainDocument(
                    if (case == "target") account.let { R2WriteTarget(it, "other-bucket") } else target,
                    if (case == "kind") R2DomainKind.MANAGED_DOMAIN else kind,
                    FakeDomainSession.json("""{"domain":"$domain","enabled":true,"minTLS":"1.2","ciphers":[],"zoneId":"$zoneId","zoneName":"example.com","status":{"ownership":"pending","ssl":"initializing"}}"""),
                    true,
                )
            }
            assertFailsWith<CloudFailure> { h.prepare(write) }
            assertEquals(0, h.session.sends.size)
        }
    }

    @Test
    fun cancellingTheUiWaiterDoesNotCancelTheAppOwnedDomainReadBackOrResend() = runTest {
        val h = Harness(this)
        val gate = CompletableDeferred<Unit>()
        h.session.readBackGate = { gate.await() }
        val write = managed(true)
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
    fun anUnsettledWriteOfTheSameBucketBlocksADomainWrite() = runTest {
        val h = Harness(this)
        h.store.rows["pending"] = OperationTask(
            id = "pending",
            connectionId = connection.id,
            scope = account,
            actionKey = "r2-patch-bucket",
            resourceRef = ResourceRef(account, "r2_bucket", "my-bucket"),
            requestFingerprint = "a".repeat(64),
            desiredHash = "b".repeat(64),
            state = TaskState.UNKNOWN,
            safeDetail = "任务状态未确定",
            providerRequestId = null,
            pollPath = null,
            createdAtMs = 1,
            updatedAtMs = 1,
        )
        assertFailsWith<CloudFailure> { h.prepare(managed(true)) }
        assertEquals(0, h.session.writes.size)
    }

    @Test
    fun thePreviewNamesTheDomainAndSeparatesOwnershipAndSslFromEnabled() = runTest {
        val h = Harness(this)
        h.session.seedDomain(domain, enabled = true, ownership = "pending", ssl = "initializing")
        val preview = h.preview(h.prepare(edit(enabled = false)))
        assertTrue(preview.impact.contains(domain))
        assertTrue(preview.concurrencyWarning.isNotBlank())
        val add = h.preview(h.prepare(R2DomainConfigurationWrite.AddCustomDomain(target, R2CustomDomainCreateInput("new.example.com", zoneId, enabled = false))))
        assertTrue(add.impact.contains("new.example.com"))
    }

    private val otherZone = "fedcba9876543210fedcba9876543210"

    private fun customDomainJson(
        domainName: String = domain,
        enabled: Boolean = true,
        minTls: String = "1.2",
        zone: String = zoneId,
        withZone: Boolean = true,
        withCiphers: Boolean = true,
    ): JsonObject = FakeDomainSession.json(
        buildString {
            append("""{"domain":"$domainName","enabled":$enabled,"minTLS":"$minTls"""")
            if (withCiphers) append(""","ciphers":["ECDHE-ECDSA-AES128-GCM-SHA256"]""")
            if (withZone) append(""","zoneId":"$zone"""")
            append(""","zoneName":"example.com","status":{"ownership":"pending","ssl":"initializing"}}""")
        },
    )

    private fun otherDomainDocument(target: R2WriteTarget, domainName: String): R2DomainDocument {
        val raw = customDomainJson(domainName = domainName)
        return R2DomainDocument(
            target = target,
            kind = R2DomainKind.CUSTOM_DOMAIN,
            raw = raw,
            editable = true,
            customDomain = R2CustomDomain(
                domain = domainName,
                enabled = true,
                minTls = "1.2",
                ciphers = listOf("ECDHE-ECDSA-AES128-GCM-SHA256"),
                zoneId = zoneId,
                zoneName = "example.com",
                status = R2CustomDomainStatus("pending", "initializing"),
                raw = raw,
            ),
        )
    }

    @Test
    fun anAddedDomainWithAnotherOrMissingZoneIsNotSuccessAndIsNeverReplayed() = runTest {
        val wrong = Harness(this)
        wrong.session.onApply = {
            wrong.session.collection += customDomainJson(zone = otherZone)
        }
        val write = add(enabled = true)
        val task = wrong.controller.submit(write, wrong.confirm(write, wrong.prepare(write)))
        assertEquals(TaskState.UNKNOWN, task.state, "the returned zone differs from the requested zone")
        assertEquals(1, wrong.session.sends.size)
        assertEquals(TaskState.UNKNOWN, wrong.controller.reconcile(task.id).state)
        assertEquals(1, wrong.session.sends.size, "a wrong-zone read is never a reason to POST again")
        wrong.session.collection.clear()
        wrong.session.collection += customDomainJson(zone = zoneId)
        assertEquals(TaskState.SUCCEEDED, wrong.controller.reconcile(task.id).state)
        assertEquals(1, wrong.session.sends.size)

        val missing = Harness(this)
        missing.session.onApply = { missing.session.collection += customDomainJson(withZone = false) }
        val second = add(enabled = true)
        assertEquals(
            TaskState.UNKNOWN,
            missing.controller.submit(second, missing.confirm(second, missing.prepare(second))).state,
            "a missing zone is not proof of the requested association",
        )
        assertEquals(1, missing.session.sends.size)
    }

    @Test
    fun anEditPreviewCannotSurviveAZoneRebindingAndAFreshPreviewStillWorks() = runTest {
        val h = Harness(this)
        h.session.seedDomain(domain, enabled = true, minTls = "1.2", zoneId = zoneId)
        val write = edit(enabled = false)
        val plan = h.prepare(write)
        // Same domain and same visible config, but the provider rebound it to another zone.
        h.session.collection[0] = customDomainJson(zone = otherZone)
        val task = h.controller.submit(write, h.confirm(write, plan))
        assertEquals(TaskState.FAILED, task.state)
        assertEquals(0, h.session.sends.size)

        val fresh = edit(enabled = false)
        assertEquals(TaskState.SUCCEEDED, h.controller.submit(fresh, h.confirm(fresh, h.prepare(fresh))).state)
        assertEquals(1, h.session.sends.size)
        assertEquals(otherZone, (h.session.collection.single()["zoneId"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun anEditWithoutAnInterpretableZoneAssociationIsRefusedBeforeAnySend() = runTest {
        for (zone in listOf(null, "not-a-zone")) {
            val h = Harness(this)
            h.session.collection += customDomainJson(withZone = zone != null, zone = zone ?: zoneId)
            val failure = assertFailsWith<CloudFailure> { h.prepare(edit(enabled = false)) }
            assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY, failure.code, "zone=$zone")
            assertEquals(0, h.session.writes.size)
        }
    }

    @Test
    fun aPortReturningAnotherDomainCannotPrepareConfirmOrSettleAWrite() = runTest {
        // prepare: an editable snapshot of another domain is not evidence for this write.
        val prepareCase = Harness(this)
        prepareCase.session.seedDomain(domain, enabled = true, minTls = "1.2", zoneId = zoneId)
        prepareCase.session.documentOverride = { _, kind ->
            if (kind == R2DomainKind.CUSTOM_DOMAIN) otherDomainDocument(target, "other.example.com") else null
        }
        assertFailsWith<CloudFailure> { prepareCase.prepare(edit(enabled = false)) }
        assertEquals(0, prepareCase.session.sends.size)

        // the final callback: an echoed document of another domain is not this target.
        val sendCase = Harness(this)
        sendCase.session.seedDomain(domain, enabled = true, minTls = "1.2", zoneId = zoneId)
        val write = edit(enabled = false)
        val plan = sendCase.prepare(write)
        sendCase.session.documentOverride = { _, kind ->
            if (kind == R2DomainKind.CUSTOM_DOMAIN) otherDomainDocument(target, "other.example.com") else null
        }
        val failed = sendCase.controller.submit(write, sendCase.confirm(write, plan))
        assertEquals(TaskState.FAILED, failed.state)
        assertEquals(0, sendCase.session.sends.size)

        // the read-back: another domain cannot settle the task.
        val readCase = Harness(this)
        readCase.session.seedDomain(domain, enabled = true, minTls = "1.2", zoneId = zoneId)
        val second = edit(enabled = false)
        val secondPlan = readCase.prepare(second)
        readCase.session.documentOverride = { _, kind ->
            if (kind == R2DomainKind.CUSTOM_DOMAIN && readCase.session.sends.isNotEmpty()) {
                otherDomainDocument(target, "other.example.com")
            } else {
                null
            }
        }
        val unknown = readCase.controller.submit(second, readCase.confirm(second, secondPlan))
        assertEquals(TaskState.UNKNOWN, unknown.state)
        assertEquals(1, readCase.session.sends.size)
        assertEquals(TaskState.UNKNOWN, readCase.controller.reconcile(unknown.id).state)
        assertEquals(1, readCase.session.sends.size)
    }
}
