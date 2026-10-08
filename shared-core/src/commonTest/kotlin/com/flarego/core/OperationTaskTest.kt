package com.flarego.core

import com.flarego.core.model.*
import com.flarego.core.ports.OperationStore
import kotlin.test.*

/**
 * Core contract tests for the recoverable task metadata: state fallbacks, the
 * fixed summary mapping, identity validation, the poll-path gate, the flat stored
 * row codec and the absence of payload/credential columns.
 */
class OperationTaskTest {

    private val account = AccountScope("connection-1", "account-1")
    private val zone = ZoneScope(account, "zone-1")
    private val user = UserScope("connection-1", "user-1")
    private val organization = OrganizationScope("connection-1", "organization-1")

    private fun fingerprint(seed: Char = 'a'): String = seed.toString().repeat(64)

    private fun task(
        id: String = "task-1",
        connectionId: String = "connection-1",
        scope: CloudScope = account,
        actionKey: String = "dns-records-for-a-zone-create-dns-record",
        resourceRef: ResourceRef? = ResourceRef(account, "dns_record", "record-1"),
        requestFingerprint: String = fingerprint(),
        desiredHash: String? = fingerprint('b'),
        state: TaskState = TaskState.QUEUED,
        safeDetail: String = "已登记，等待执行",
        providerRequestId: String? = "provider-request-1",
        pollPath: String? = null,
        createdAtMs: Long = 1_000,
        updatedAtMs: Long = 2_000,
    ) = OperationTask(
        id,
        connectionId,
        scope,
        actionKey,
        resourceRef,
        requestFingerprint,
        desiredHash,
        state,
        safeDetail,
        providerRequestId,
        pollPath,
        createdAtMs,
        updatedAtMs,
    )

    private fun assertInvalid(label: String, block: () -> Any) {
        val failure = assertFailsWith<CloudFailure>(label) { block() }
        assertEquals(CloudFailureCode.INVALID_INPUT, failure.code, label)
        assertFalse(failure.uncertain, label)
        assertNull(failure.cause, label)
    }

    // ---------------------------------------------------------------- states

    @Test
    fun everyTaskStateNameParsesAndUnknownValuesFallBack() {
        assertEquals(9, TaskState.entries.size)
        for (state in TaskState.entries) assertEquals(state, TaskState.parse(state.name), state.name)
        assertEquals(TaskState.UNKNOWN, TaskState.parse("FUTURE_STATE"))
        assertEquals(TaskState.UNKNOWN, TaskState.parse(null))
        assertEquals(TaskState.UNKNOWN, TaskState.parse(""))
    }

    @Test
    fun legacyOperationStatusKeepsThreeNamesAndFallsBackForFutureValues() {
        assertEquals(3, OperationStatus.entries.size)
        assertEquals(OperationStatus.SUCCESS, OperationTaskPersistence.operationStatusOf("SUCCESS"))
        assertEquals(OperationStatus.FAILED, OperationTaskPersistence.operationStatusOf("FAILED"))
        assertEquals(OperationStatus.UNKNOWN, OperationTaskPersistence.operationStatusOf("UNKNOWN"))
        assertEquals(OperationStatus.UNKNOWN, OperationTaskPersistence.operationStatusOf("FUTURE_STATUS"))
        assertEquals(OperationStatus.UNKNOWN, OperationTaskPersistence.operationStatusOf(null))
    }

    @Test
    fun unknownStoredTaskStateIsReadAsUnknown() {
        val row = OperationTaskPersistence.toRow(task())
        val future = row.copy(state = "FUTURE_STATE")
        val decoded = OperationTaskPersistence.fromRow(future)
        assertEquals(TaskState.UNKNOWN, decoded.state)
    }

    // ---------------------------------------------------------------- summary mapping

    @Test
    fun summaryIsDerivedFromTheStateWithFixedPublicText() {
        val expected =
            mapOf(
                TaskState.SUCCEEDED to OperationStatus.SUCCESS,
                TaskState.FAILED to OperationStatus.FAILED,
                TaskState.QUEUED to OperationStatus.UNKNOWN,
                TaskState.RUNNING to OperationStatus.UNKNOWN,
                TaskState.ACCEPTED to OperationStatus.UNKNOWN,
                TaskState.PARTIAL to OperationStatus.UNKNOWN,
                TaskState.UNKNOWN to OperationStatus.UNKNOWN,
                TaskState.RECONCILING to OperationStatus.UNKNOWN,
                TaskState.ACTION_REQUIRED to OperationStatus.UNKNOWN,
            )
        assertEquals(9, expected.size)
        for ((state, status) in expected) {
            val summary = OperationTaskPersistence.summaryFor(task(state = state))
            assertEquals(task().id, summary.id)
            assertEquals(task().connectionId, summary.connectionId)
            assertEquals(status, summary.status, state.name)
            assertTrue(summary.title.isNotBlank(), state.name)
            assertTrue(summary.detail.isNotBlank(), state.name)
        }
        // The fixed text distinguishes a finished task from an accepted or partial one.
        val succeeded = OperationTaskPersistence.summaryFor(task(state = TaskState.SUCCEEDED)).detail
        val accepted = OperationTaskPersistence.summaryFor(task(state = TaskState.ACCEPTED)).detail
        val partial = OperationTaskPersistence.summaryFor(task(state = TaskState.PARTIAL)).detail
        assertNotEquals(succeeded, accepted)
        assertNotEquals(succeeded, partial)
        assertNotEquals(accepted, partial)
    }

    @Test
    fun callerSummaryTextNeverBecomesTheCanonicalSummary() {
        val marker = "synthetic-caller-detail-not-a-secret"
        val callerSummary = Operation("task-1", "connection-1", "caller title", OperationStatus.SUCCESS, marker)
        OperationTaskPersistence.requireSummaryMatches(task(state = TaskState.SUCCEEDED), callerSummary)
        val canonical = OperationTaskPersistence.summaryFor(task(state = TaskState.SUCCEEDED))
        assertFalse(canonical.detail.contains(marker))
        assertFalse(canonical.title.contains("caller title"))
    }

    @Test
    fun summaryMustMatchTheTaskIdentityAndState() {
        val task = task(state = TaskState.SUCCEEDED)
        assertInvalid("different id") {
            OperationTaskPersistence.requireSummaryMatches(task, Operation("other", task.connectionId, "t", OperationStatus.SUCCESS, "d"))
        }
        assertInvalid("different connection") {
            OperationTaskPersistence.requireSummaryMatches(task, Operation(task.id, "other", "t", OperationStatus.SUCCESS, "d"))
        }
        assertInvalid("wrong status") {
            OperationTaskPersistence.requireSummaryMatches(task, Operation(task.id, task.connectionId, "t", OperationStatus.UNKNOWN, "d"))
        }
        assertInvalid("partial claimed as success") {
            OperationTaskPersistence.requireSummaryMatches(task(state = TaskState.PARTIAL), Operation(task.id, task.connectionId, "t", OperationStatus.SUCCESS, "d"))
        }
        OperationTaskPersistence.requireSummaryMatches(task, OperationTaskPersistence.summaryFor(task))
    }

    // ---------------------------------------------------------------- identity validation

    @Test
    fun scopeReferenceAndConnectionMustAgree() {
        assertInvalid("scope connection mismatch") { task(connectionId = "connection-2") }
        assertInvalid("resource ref from another scope") {
            task(resourceRef = ResourceRef(ZoneScope(account, "zone-2"), "dns_record", "record-1"))
        }
        assertInvalid("resource ref from another account") {
            task(resourceRef = ResourceRef(AccountScope("connection-1", "account-2"), "dns_record", "record-1"))
        }
        assertInvalid("blank id") { task(id = " ") }
        assertInvalid("blank action key") { task(actionKey = "") }
        assertInvalid("blank safe detail") { task(safeDetail = " ") }
        assertNull(task(resourceRef = null).resourceRef)
    }

    @Test
    fun fingerprintAndTimestampsAreValidated() {
        assertInvalid("short fingerprint") { task(requestFingerprint = "abc") }
        assertInvalid("non hex fingerprint") { task(requestFingerprint = "z".repeat(64)) }
        assertInvalid("short desired hash") { task(desiredHash = "abc") }
        assertInvalid("non hex desired hash") { task(desiredHash = "z".repeat(64)) }
        assertInvalid("negative created time") { task(createdAtMs = -1) }
        assertInvalid("updated before created") { task(createdAtMs = 2_000, updatedAtMs = 1_000) }
        assertNull(task(desiredHash = null).desiredHash)
        assertEquals(fingerprint('A'), task(requestFingerprint = fingerprint('A')).requestFingerprint)
    }

    @Test
    fun providerRequestIdIsABoundedSafeIdentifier() {
        assertInvalid("blank provider request id") { task(providerRequestId = " ") }
        assertInvalid("oversized provider request id") { task(providerRequestId = "a".repeat(129)) }
        assertInvalid("control character in provider request id") { task(providerRequestId = "a\r\nb") }
        assertNull(task(providerRequestId = null).providerRequestId)
    }

    // ---------------------------------------------------------------- poll path gate

    @Test
    fun pollPathRejectsUnsafeLocations() {
        val unsafe =
            listOf(
                "https://evil.example/accounts/account-1/x",
                "//evil.example/accounts/account-1/x",
                "/accounts/account-1/x#fragment",
                "/accounts/account-1/%2e%2e/other",
                "/accounts/account-1/x%2Fy",
                "/accounts/account-1/x%zz",
                "/accounts/account-1/\r\nx",
                "/accounts/account-1\\x",
                "/accounts/account-2/x",
                "/zones/zone-2/dns_records",
                "/organizations/organization-2/x",
                "accounts/account-1/x",
                "",
            )
        for (path in unsafe) {
            val failure = assertFailsWith<CloudFailure>(path) { task(pollPath = path) }
            assertEquals(CloudFailureCode.INVALID_INPUT, failure.code, path)
            assertTrue(failure.publicMessage.isNotBlank(), path)
            if (path.isNotEmpty()) assertFalse(failure.publicMessage.contains(path), path)
        }
    }

    @Test
    fun pollPathAcceptsSafeRelativeLocationsForItsOwnScope() {
        assertEquals("/accounts/account-1/x", task(pollPath = "/accounts/account-1/x").pollPath)
        assertEquals("/zones/zone-1/dns_records", task(scope = zone, resourceRef = null, pollPath = "/zones/zone-1/dns_records").pollPath)
        assertEquals("/user/tokens/verify", task(scope = user, resourceRef = null, pollPath = "/user/tokens/verify").pollPath)
        assertEquals(
            "/organizations/organization-1/x",
            task(scope = organization, resourceRef = null, pollPath = "/organizations/organization-1/x").pollPath,
        )
        assertNull(task(pollPath = null).pollPath)
    }

    // ---------------------------------------------------------------- stored row codec

    @Test
    fun storedRowRoundTripPreservesFixedFields() {
        val scopes = listOf(account, zone, user, organization)
        for (scope in scopes) {
            val original = task(scope = scope, resourceRef = ResourceRef(scope, "dns_record", "record-1"))
            assertEquals(original, OperationTaskPersistence.fromRow(OperationTaskPersistence.toRow(original)), scope.toString())
        }
        val minimal =
            task(
                scope = user,
                resourceRef = null,
                desiredHash = null,
                providerRequestId = null,
                pollPath = "/user/tokens/verify",
            )
        val decoded = OperationTaskPersistence.fromRow(OperationTaskPersistence.toRow(minimal))
        assertEquals(minimal, decoded)
        assertNull(decoded.resourceRef)
        assertNull(decoded.desiredHash)
        assertNull(decoded.providerRequestId)
    }

    @Test
    fun storedRowRejectsCorruptShapesWithoutEchoingValues() {
        val row = OperationTaskPersistence.toRow(task())
        val corrupt =
            listOf(
                row.copy(scopeKind = "FUTURE_SCOPE"),
                row.copy(accountId = null),
                row.copy(zoneId = "zone-1"),
                row.copy(scopeConnectionId = "connection-2"),
                row.copy(requestFingerprint = "nope"),
                row.copy(createdAtMs = -1),
                row.copy(updatedAtMs = 0, createdAtMs = 10),
                row.copy(resourceKind = "dns_record", resourceExternalId = null),
                row.copy(providerRequestId = "a".repeat(200)),
                row.copy(pollPath = "/accounts/account-2/x"),
            )
        for (candidate in corrupt) {
            val failure = assertFailsWith<CloudFailure> { OperationTaskPersistence.fromRow(candidate) }
            assertEquals(CloudFailureCode.INVALID_INPUT, failure.code)
            assertNull(failure.cause)
        }
        assertFalse(failureMessageContainsSynthetic(corrupt.first()))
    }

    private fun failureMessageContainsSynthetic(row: StoredOperationTaskRow): Boolean {
        val failure = assertFailsWith<CloudFailure> { OperationTaskPersistence.fromRow(row) }
        return failure.publicMessage.contains(row.id) || failure.publicMessage.contains(row.scopeKind)
    }

    @Test
    fun storedColumnsAreOnlySafeMetadataAndNeverPayloadOrCredentials() {
        val forbidden = listOf("payload", "credential", "token", "secret", "body", "header", "json", "dto")
        assertEquals(19, OperationTaskPersistence.STORED_COLUMNS.size)
        for (column in OperationTaskPersistence.STORED_COLUMNS) {
            for (word in forbidden) {
                assertFalse(column.contains(word), "$column must not look like $word")
            }
        }
        assertEquals(
            listOf(
                "id",
                "connection_id",
                "scope_kind",
                "scope_connection_id",
                "account_id",
                "zone_id",
                "user_id",
                "organization_id",
                "action_key",
                "resource_kind",
                "resource_external_id",
                "request_fingerprint",
                "desired_hash",
                "state",
                "safe_detail",
                "provider_request_id",
                "poll_path",
                "created_at_ms",
                "updated_at_ms",
            ),
            OperationTaskPersistence.STORED_COLUMNS,
        )
    }

    // ---------------------------------------------------------------- store contract

    @Test
    fun inMemoryStoreHonoursThePortContract() {
        val store: OperationStore = InMemoryOperationStore()
        val first = task(id = "task-1")
        val second = task(id = "task-2", state = TaskState.SUCCEEDED, createdAtMs = 1_000, updatedAtMs = 3_000)
        store.put(first)
        store.putWithSummary(second, OperationTaskPersistence.summaryFor(second))
        assertEquals(first, store.get("task-1"))
        assertEquals(second, store.get("task-2"))
        assertEquals(listOf("task-2", "task-1"), store.list("connection-1").map { it.id })
        assertNull(store.get("missing"))
        store.removeConnection("connection-1")
        assertTrue(store.list("connection-1").isEmpty())
        assertNull(store.get("task-1"))
    }

    @Test
    fun inMemoryStoreRejectsASummaryThatDoesNotMatchTheTask() {
        val store = InMemoryOperationStore()
        val mismatched = Operation("task-1", "connection-1", "t", OperationStatus.SUCCESS, "d")
        assertInvalid("unmapped state") { store.putWithSummary(task(state = TaskState.QUEUED), mismatched) }
        assertTrue(store.list("connection-1").isEmpty(), "a rejected summary must not be stored")
    }
}

/** A minimal in-memory control for the port contract; it proves nothing about SQLite. */
private class InMemoryOperationStore : OperationStore {
    private val tasks = linkedMapOf<String, OperationTask>()

    override fun get(id: String): OperationTask? = tasks[id]

    override fun list(connectionId: String): List<OperationTask> =
        tasks.values.filter { it.connectionId == connectionId }.sortedByDescending { it.updatedAtMs }

    override fun put(task: OperationTask) {
        tasks[task.id] = task
    }

    override fun removeConnection(connectionId: String) {
        tasks.entries.removeAll { it.value.connectionId == connectionId }
    }

    override fun putWithSummary(task: OperationTask, summary: Operation) {
        OperationTaskPersistence.requireSummaryMatches(task, summary)
        tasks[task.id] = task
    }
}
