package com.flarego.core

import com.flarego.core.application.dns.*
import com.flarego.core.application.operations.*
import com.flarego.core.demo.DemoDnsManagement
import com.flarego.core.demo.DemoSession
import com.flarego.core.model.*
import com.flarego.core.ports.*
import java.security.MessageDigest
import kotlin.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class DnsTaskFamilyIntegrationTest {
    // Fixed operation IDs are independent expectations, including during the behavior RED.
    private val scanApply = "dns-records-for-a-zone-apply-dns-scan-results"
    private val settingsPatch = "dns-settings-for-a-zone-update-dns-settings"
    private val added = listOf(scanApply, settingsPatch)
    private val trigger = "dns-records-for-a-zone-trigger-dns-scan"
    private val owner = Connection("connection", "Cloudflare", "account")
    private val scope = ZoneScope(AccountScope(owner.id, owner.accountId), "zone-demo")
    private val target = ResourceRef(scope, "dns_record", "example-1")
    private val fields = buildJsonObject { put("comment", "explicit change") }
    private val input = DnsRecordInput("A", "flarego.dev", fields)
    private val batch = DnsBatchInput(patches = listOf(DnsBatchPatch("example-1", fields)))

    private class Store : DnsBatchEvidenceStore {
        val rows = linkedMapOf<String, OperationTask>()
        val proofs = linkedMapOf<String, DnsBatchTaskEvidence>()
        var mutations = 0
        override fun get(id: String) = rows[id]
        override fun list(connectionId: String) = rows.values.filter { it.connectionId == connectionId }
        override fun put(task: OperationTask) { mutations++; rows[task.id] = task }
        override fun putWithSummary(task: OperationTask, summary: Operation) { put(task) }
        override fun putWithSummaryAndEvidence(task: OperationTask, summary: Operation, evidence: DnsBatchTaskEvidence) {
            putWithSummary(task, summary); proofs[task.id] = evidence
        }
        override fun batchEvidence(taskId: String) = proofs[taskId]
        override fun removeConnection(connectionId: String) {
            val ids = list(connectionId).map { it.id }.toSet()
            rows.keys.removeAll(ids); proofs.keys.removeAll(ids)
        }
    }
    private fun task(key: String, zone: ZoneScope = scope, state: TaskState = TaskState.UNKNOWN,
                     ref: ResourceRef? = null) = OperationTask(
        "unsettled", zone.connectionId, zone, key, ref, "a".repeat(64), "b".repeat(64),
        state, "结果待核对", null, null, 1, 1)

    private inner class Application(test: TestScope) {
        val store = Store()
        var state = AppState(listOf(owner), owner, page = Page.DNS,
            domain = Domain(scope.zoneId, "flarego.dev", "active", owner.id))
        var factoryAcquisitions = 0
        var typedProviderReads = 0
        var writes = 0
        var legacySessions = 0
        var legacyWrites = 0
        private var next = 0
        private val example = DemoDnsManagement(owner.copy(demo = true))
        private val zones = object : ZoneManagementSession by example {
            override suspend fun get(scope: ZoneScope): ZoneDetails { typedProviderReads++; return example.get(scope) }
            override suspend fun list(account: AccountScope, page: PageRequest): CloudPage<ZoneDetails> {
                typedProviderReads++; return example.list(account, page)
            }
            override suspend fun write(action: ZoneAction, beforeSend: (ZoneDetails?) -> Unit): ExecutionReceipt {
                writes++; return example.write(action, beforeSend)
            }
        }
        private val dns = object : DnsBatchManagementSession by example {
            override suspend fun zone(scope: ZoneScope): ZoneDetails { typedProviderReads++; return example.zone(scope) }
            override suspend fun get(scope: ZoneScope, recordId: String): DnsRecordDocument {
                typedProviderReads++; return example.get(scope, recordId)
            }
            override suspend fun list(scope: ZoneScope, filter: DnsFilter, page: PageRequest): CloudPage<DnsRecordDocument> {
                typedProviderReads++; return example.list(scope, filter, page)
            }
            override suspend fun observeBatch(scope: ZoneScope, input: DnsBatchInput): DnsBatchSnapshot {
                typedProviderReads++; return example.observeBatch(scope, input)
            }
            override suspend fun write(action: DnsWriteAction, beforeSend: (DnsRecordDocument?) -> Unit): ExecutionReceipt {
                writes++; return example.write(action, beforeSend)
            }
            override suspend fun writeBatch(scope: ZoneScope, input: DnsBatchInput,
                                            beforeSend: (DnsBatchSnapshot) -> Unit): DnsBatchOutcome {
                writes++; return example.writeBatch(scope, input, beforeSend)
            }
        }
        val runtime = AppOperationRuntime(test.backgroundScope, store, StandardTestDispatcher(test.testScheduler),
            { state }, { 0L }, { 0L }, SessionFactory {
                legacySessions++
                object : CloudSession by DemoSession(owner) {
                    override suspend fun save(zoneId: String, recordId: String?, draft: DnsDraft) { legacyWrites++ }
                    override suspend fun delete(zoneId: String, recordId: String) { legacyWrites++ }
                }
            }, FingerprintPort { value ->
                MessageDigest.getInstance("SHA-256").digest(value)
                    .joinToString("") { "%02x".format(it) }
            }, OperationClock { 1000 }, OperationIds { "runtime-${++next}" }, {},
            ManagementSessionFactory { factoryAcquisitions++; ManagementServices(dns, zones) })
    }

    @Test fun applyAndSettingsUnsettledBlockEveryExistingMutationWithNullOrFutureReferences() = runTest {
        for (key in added) for (state in DNS_MUTATION_UNSETTLED)
            for (ref in listOf(null, ResourceRef(scope, "future_kind", "future"))) {
                val store = Store().also { it.put(task(key, state = state, ref = ref)) }
                val gate = DnsUncertaintyGate(store, StandardTestDispatcher(testScheduler))
                for (incoming in DNS_WRITE_KEYS + setOf(DNS_BATCH_KEY, DNS_IMPORT_KEY)) {
                    assertEquals(CloudFailureCode.UNKNOWN_RESULT,
                        assertFailsWith<CloudFailure>("$key/$state must block $incoming") {
                            gate.requireClear(scope, incoming, target)
                        }.code)
                }
            }
    }

    @Test fun everySingleBatchImportAndNewUnsettledMutationBlocksApplyAndSettings() = runTest {
        for (prior in DNS_WRITE_KEYS + setOf(DNS_BATCH_KEY, DNS_IMPORT_KEY) + added)
            for (state in DNS_MUTATION_UNSETTLED) for (ref in listOf(null, ResourceRef(scope, "future_kind", "future"))) {
                val store = Store().also { it.put(task(prior, state = state, ref = ref)) }
                val gate = DnsUncertaintyGate(store, StandardTestDispatcher(testScheduler))
                for (incoming in added) assertEquals(CloudFailureCode.UNKNOWN_RESULT,
                    assertFailsWith<CloudFailure>("$prior/$state must block $incoming") {
                        gate.requireClear(scope, incoming, ResourceRef(scope, "dns_scan", scope.zoneId))
                    }.code)
            }
    }

    @Test fun completeScopeTerminalAndReadOnlyOrAcceptedTriggerDoNotBlock() = runTest {
        val rows = added.flatMap { key ->
            listOf(task(key, ZoneScope(scope.account, "other-zone")),
                task(key, ZoneScope(AccountScope(owner.id, "other-account"), scope.zoneId)),
                task(key, ZoneScope(AccountScope("other-connection", owner.accountId), scope.zoneId))) +
                listOf(TaskState.FAILED, TaskState.SUCCEEDED, TaskState.ACTION_REQUIRED).map { task(key, state = it) }
        } + listOf("dns-records-for-a-zone-export-dns-records", "dns-records-for-a-zone-review-dns-scan",
            "dns-settings-for-a-zone-list-dns-settings").map { task(it) } + task(trigger, state = TaskState.ACCEPTED)
        for (row in rows) {
            val store = Store().also { it.put(row) }
            val gate = DnsUncertaintyGate(store, StandardTestDispatcher(testScheduler))
            for (incoming in DNS_WRITE_KEYS + setOf(DNS_BATCH_KEY, DNS_IMPORT_KEY) + added)
                gate.requireClear(scope, incoming, target)
        }
        assertFalse(trigger in DNS_MUTATION_KEYS)
        assertTrue(added.none { it in DNS_WRITE_KEYS })
    }

    @Test fun realRuntimePendingFamilyIncludesNewUnknown() = runTest {
        for (key in added) {
            val app = Application(this); app.runtime.recover(); app.store.put(task(key))
            assertEquals(key, app.runtime.pendingManagement(scope)?.actionKey)
        }
    }

    @Test fun realRuntimeLegacyCannotBypassNewUnknown() = runTest {
        for (key in added) for (mode in listOf("create", "patch", "delete")) {
            val app = Application(this); app.runtime.recover(); app.store.put(task(key))
            assertEquals(CloudFailureCode.UNKNOWN_RESULT, assertFailsWith<CloudFailure> {
                when (mode) {
                    "create" -> app.runtime.create(scope, DnsDraft("A", "new.flarego.dev", "192.0.2.8"))
                    "patch" -> app.runtime.update(scope, "example-1", DnsDraft("A", "flarego.dev", "192.0.2.8"))
                    else -> app.runtime.delete(scope, "example-1")
                }
            }.code)
            assertEquals(0, app.legacySessions); assertEquals(0, app.legacyWrites)
            assertEquals(0, app.factoryAcquisitions); assertEquals(0, app.writes)
        }
    }

    @Test fun newUnknownAfterActualSinglePreviewBlocksSecondConfirmationWithoutSendingOrPersistingIt() = runTest {
        for (key in added) for (ref in listOf(null, ResourceRef(scope, "future_kind", "future"))) {
            val app = Application(this)
            val prepared = app.runtime.prepareDns(DnsWriteAction(scope, "example-1", input, DnsWriteMode.PATCH)) { true }
            app.store.put(task(key, ref = ref)); val metadata = app.store.mutations
            assertEquals(CloudFailureCode.UNKNOWN_RESULT, assertFailsWith<CloudFailure> {
                app.runtime.confirmDns(prepared)
            }.code)
            assertEquals(0, app.writes); assertEquals(metadata, app.store.mutations)
            assertEquals(listOf("unsettled"), app.store.rows.keys.toList())
        }
    }

    @Test fun newUnknownAfterActualBatchPreviewBlocksSecondConfirmationWithoutSendingOrPersistingIt() = runTest {
        for (key in added) for (ref in listOf(null, ResourceRef(scope, "future_kind", "future"))) {
            val app = Application(this); val prepared = app.runtime.prepareBatch(scope, batch) { true }
            app.store.put(task(key, ref = ref)); val metadata = app.store.mutations
            assertEquals(CloudFailureCode.UNKNOWN_RESULT, assertFailsWith<CloudFailure> {
                app.runtime.confirmBatch(prepared)
            }.code)
            assertEquals(0, app.writes); assertEquals(metadata, app.store.mutations)
            assertEquals(listOf("unsettled"), app.store.rows.keys.toList()); assertTrue(app.store.proofs.isEmpty())
        }
    }

    @Test fun realRuntimeOtherZoneOrAcceptedTriggerStillAllowsExplicitSingleConfirmation() = runTest {
        for (row in added.map { task(it, ZoneScope(scope.account, "other-zone")) } + task(trigger, state = TaskState.ACCEPTED)) {
            val app = Application(this); app.runtime.recover(); app.store.put(row)
            val prepared = app.runtime.prepareDns(DnsWriteAction(scope, "example-1", input, DnsWriteMode.PATCH)) { true }
            assertEquals(TaskState.SUCCEEDED, app.runtime.confirmDns(prepared).state)
            assertEquals(1, app.writes)
        }
    }

    @Test fun missingAdaptersReconcileAcquiresFactoryOnceWithoutTypedReadsOrMetadataChanges() = runTest {
        for (key in added) for (publicJobs in listOf(false, true)) {
            val app = Application(this); app.runtime.recover(); val unknown = task(key)
            app.store.put(unknown); val metadata = app.store.mutations
            assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY, assertFailsWith<CloudFailure> {
                if (publicJobs) app.runtime.reconcile(unknown.id)
                else app.runtime.reconcileManagement(scope, unknown.id) { true }
            }.code)
            assertEquals(1, app.factoryAcquisitions); assertEquals(0, app.typedProviderReads)
            assertEquals(0, app.writes); assertEquals(0, app.legacySessions); assertEquals(0, app.legacyWrites)
            assertEquals(metadata, app.store.mutations); assertEquals(unknown, app.store.get(unknown.id))
            assertEquals(TaskState.UNKNOWN, app.store.get(unknown.id)?.state)
        }
    }
}
