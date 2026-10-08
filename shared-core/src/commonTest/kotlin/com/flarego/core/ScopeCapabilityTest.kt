package com.flarego.core

import com.flarego.core.application.AppController
import com.flarego.core.application.safeMessage
import com.flarego.core.demo.DemoSession
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlin.test.*
import kotlinx.coroutines.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScopeCapabilityTest {

    private val owned = Connection("conn-owned", "Production", "acct-owned-111", true)
    private val other = Connection("conn-other", "Sandbox", "acct-other-222", true)

    private fun assertInvalidInput(label: String, block: () -> Unit) {
        val failure = assertFailsWith<CloudFailure>(label, block)
        assertEquals(CloudFailureCode.INVALID_INPUT, failure.code, label)
        assertTrue(failure.publicMessage.isNotBlank(), label)
        assertFalse(failure.publicMessage.contains("\t"), "$label: message must not echo the input")
        assertFalse(failure.uncertain, label)
    }

    private suspend fun TestScope.failedWriteOperation(failure: Exception): Operation {
        var writes = 0
        val session =
            object : CloudSession by DemoSession(owned) {
                override suspend fun save(zoneId: String, recordId: String?, draft: DnsDraft) {
                    writes++
                    throw failure
                }
            }
        val store = MemoryStore()
        val controller =
            AppController(backgroundScope, SessionFactory { session }, store, listOf(owned))
        controller.refresh()
        runCurrent()
        val outcome =
            controller.saveDns(
                Domain("zone-1", "example.com", "active", owned.id),
                null,
                DnsDraft("A", "@", "192.0.2.1"),
            )
        assertEquals(1, writes, "a failed write must never be replayed automatically")
        assertEquals(
            outcome.status,
            store.operations().single().status,
            "the persisted record must agree with the returned operation",
        )
        return outcome
    }

    @Test
    fun sameExternalIdInDifferentAccountsIsNotTheSameResource() {
        val a = AccountScope(owned.id, owned.accountId)
        val b = AccountScope(other.id, other.accountId)
        assertNotEquals(ResourceRef(a, "worker", "same"), ResourceRef(b, "worker", "same"))
        assertEquals(ResourceRef(a, "worker", "same"), ResourceRef(a, "worker", "same"))
        assertNotEquals(ResourceRef(a, "worker", "same"), ResourceRef(a, "r2", "same"))
        assertNotEquals(ResourceRef(a, "worker", "same"), ResourceRef(a, "worker", "other"))
    }

    @Test
    fun sameExternalIdInDifferentZoneOrOwnerScopeIsNotEqual() {
        val account = AccountScope(owned.id, owned.accountId)
        val zoneOne = ZoneScope(account, "zone-1")
        val zoneTwo = ZoneScope(account, "zone-2")
        assertNotEquals(ResourceRef(zoneOne, "dns", "rec-1"), ResourceRef(zoneTwo, "dns", "rec-1"))
        val user = UserScope(owned.id, "user-1")
        val organization = OrganizationScope(owned.id, "org-1")
        assertNotEquals(ResourceRef(user, "worker", "same"), ResourceRef(organization, "worker", "same"))
        assertNotEquals(ResourceRef(account, "worker", "same"), ResourceRef(user, "worker", "same"))
    }

    @Test
    fun identifiersAreKeptVerbatimWithoutNormalization() {
        val padded = AccountScope("conn-owned", " acct-owned-111 ")
        assertEquals(" acct-owned-111 ", padded.accountId)
        assertNotEquals(padded, AccountScope("conn-owned", "acct-owned-111"))
        assertNotEquals(
            ResourceRef(padded, "worker", " id-1 "),
            ResourceRef(padded, "worker", "id-1"),
        )
    }

    @Test
    fun blankIdentifiersAreRejectedWithStableInvalidInput() {
        val blank = " \t "
        val account = AccountScope("conn-owned", "acct-owned-111")
        assertInvalidInput("account id") { AccountScope("conn-owned", blank) }
        assertInvalidInput("connection id") { AccountScope(blank, "acct-owned-111") }
        assertInvalidInput("zone id") { ZoneScope(account, blank) }
        assertInvalidInput("user id") { UserScope(blank, "user-1") }
        assertInvalidInput("organization id") { OrganizationScope("conn-owned", blank) }
        assertInvalidInput("resource kind") { ResourceRef(account, blank, "worker") }
        assertInvalidInput("resource external id") { ResourceRef(account, "worker", blank) }
    }

    @Test
    fun registeredImplementationDoesNotImplyAvailability() {
        val account = AccountScope(owned.id, owned.accountId)
        val registered =
            CapabilityStatus(
                "workers.workers.list",
                account,
                implementation = ImplementationLevel.REGISTERED,
            )
        assertEquals(ImplementationLevel.REGISTERED, registered.implementation)
        assertEquals(CapabilityAvailability.UNKNOWN, registered.availability)

        val available =
            CapabilityStatus(
                "workers.workers.list",
                account,
                implementation = ImplementationLevel.NONE,
                availability = CapabilityAvailability.AVAILABLE,
            )
        assertEquals(ImplementationLevel.NONE, available.implementation)
        assertEquals(CapabilityAvailability.AVAILABLE, available.availability)
    }

    @Test
    fun capabilityDefaultsAreSafeAndNotAFabricatedObservation() {
        val account = AccountScope(owned.id, owned.accountId)
        val bare = CapabilityStatus("workers.workers.list", account)
        assertNull(bare.reason)
        assertNull(bare.observedAtMs)
        assertEquals(ImplementationLevel.NONE, bare.implementation)
        assertEquals(CapabilityAvailability.UNKNOWN, bare.availability)
    }

    @Test
    fun unknownImplementationAndAvailabilityNamesFallBackSafely() {
        assertEquals(ImplementationLevel.OPERABLE, ImplementationLevel.parse("OPERABLE"))
        assertEquals(ImplementationLevel.NONE, ImplementationLevel.parse("NOT_A_LEVEL"))
        assertEquals(ImplementationLevel.NONE, ImplementationLevel.parse(null))
        assertEquals(CapabilityAvailability.CONSOLE_ONLY, CapabilityAvailability.parse("CONSOLE_ONLY"))
        assertEquals(CapabilityAvailability.UNKNOWN, CapabilityAvailability.parse("SOMETHING_NEW"))
        assertEquals(CapabilityAvailability.UNKNOWN, CapabilityAvailability.parse(null))
    }

    @Test
    fun zoneFromAnotherConnectionIsRejectedWithScopeMismatch() {
        val foreignZone = ZoneScope(AccountScope(other.id, other.accountId), "zone-1")
        val failure = assertFailsWith<CloudFailure> { ScopeGuard.requireOwned(foreignZone, owned) }
        assertEquals(CloudFailureCode.SCOPE_MISMATCH, failure.code)
        assertFalse(failure.uncertain)
        assertFalse(failure.publicMessage.contains(other.id), "must not echo the connection id")
        assertFalse(failure.publicMessage.contains(other.accountId), "must not echo the account id")
    }

    @Test
    fun accountScopeWithDifferentAccountOnSameConnectionIsRejected() {
        val foreignAccount = AccountScope(owned.id, other.accountId)
        val failure = assertFailsWith<CloudFailure> { ScopeGuard.requireOwned(foreignAccount, owned) }
        assertEquals(CloudFailureCode.SCOPE_MISMATCH, failure.code)
        assertFalse(failure.publicMessage.contains(other.accountId), "must not echo the account id")
    }

    @Test
    fun zoneWhoseAccountDoesNotMatchTheConnectionAccountIsRejected() {
        val zone = ZoneScope(AccountScope(owned.id, other.accountId), "zone-1")
        val failure = assertFailsWith<CloudFailure> { ScopeGuard.requireOwned(zone, owned) }
        assertEquals(CloudFailureCode.SCOPE_MISMATCH, failure.code)
    }

    @Test
    fun allFourScopeKindsBindToTheirConnection() {
        val account = AccountScope(owned.id, owned.accountId)
        val cases =
            listOf(
                Triple("account matching", AccountScope(owned.id, owned.accountId), true),
                Triple("account foreign connection", AccountScope(other.id, owned.accountId), false),
                Triple("account foreign account id", AccountScope(owned.id, other.accountId), false),
                Triple("zone matching", ZoneScope(account, "zone-1"), true),
                Triple("zone foreign connection", ZoneScope(AccountScope(other.id, other.accountId), "zone-1"), false),
                Triple("user matching", UserScope(owned.id, "user-1"), true),
                Triple("user foreign connection", UserScope(other.id, "user-1"), false),
                Triple("organization matching", OrganizationScope(owned.id, "org-1"), true),
                Triple("organization foreign connection", OrganizationScope(other.id, "org-1"), false),
            )
        for ((label, scope, accepted) in cases) {
            if (accepted) {
                ScopeGuard.requireOwned(scope, owned)
            } else {
                val failure =
                    assertFailsWith<CloudFailure>(label) { ScopeGuard.requireOwned(scope, owned) }
                assertEquals(CloudFailureCode.SCOPE_MISMATCH, failure.code, label)
            }
        }
    }

    @Test
    fun zoneScopeDerivesItsConnectionFromItsAccount() {
        val account = AccountScope(owned.id, owned.accountId)
        val zone = ZoneScope(account, "zone-1")
        assertEquals(account.connectionId, zone.connectionId)
        assertEquals(account, zone.account)
        assertEquals(zone, ZoneScope(AccountScope(owned.id, owned.accountId), "zone-1"))
        assertNotEquals(zone, ZoneScope(AccountScope(owned.id, owned.accountId), "zone-2"))
    }

    @Test
    fun pageWithoutProviderMetadataKeepsNextAndTotalUnknown() {
        val page = CloudPage(items = listOf("a", "b", "c"), next = null, total = null)
        assertEquals(3, page.items.size)
        assertNull(page.next, "a page without a provider token has no next page token")
        assertNull(page.total, "total must never be inferred from the item count")
    }

    @Test
    fun pageRequestDefaultsAndTokensStayDistinct() {
        val request = PageRequest()
        assertEquals(50, request.limit)
        assertNull(request.token)
        assertEquals(
            PageRequest(limit = 20, token = PageToken.Number(2)),
            PageRequest(limit = 20, token = PageToken.Number(2)),
        )
        val numberToken: PageToken = PageToken.Number(2)
        val cursorToken: PageToken = PageToken.Cursor("2")
        assertNotEquals(numberToken, cursorToken)
        assertEquals(7, PageToken.Number(7).value)
        assertEquals("opaque-cursor-1", PageToken.Cursor("opaque-cursor-1").value)
    }

    @Test
    fun invalidPageValuesAreRejectedWithStableInvalidInput() {
        assertInvalidInput("limit must be positive") { PageRequest(limit = 0) }
        assertInvalidInput("limit must be positive") { PageRequest(limit = -5) }
        assertInvalidInput("number token must be positive") { PageToken.Number(0) }
        assertInvalidInput("cursor must not be blank") { PageToken.Cursor("  ") }
        assertInvalidInput("total must not be negative") { CloudPage(listOf("a"), null, -1L) }

        assertEquals(1, PageRequest(limit = 1).limit)
        assertEquals(1, PageToken.Number(1).value)
        assertEquals("c", PageToken.Cursor("c").value)
        assertEquals(0L, CloudPage(listOf("a"), null, 0L).total)
    }

    @Test
    fun typedFailureCarriesSafeMessageAndLegacyAdapter() {
        val failure = CloudFailure(CloudFailureCode.NOT_FOUND, "找不到该资源", requestId = "req-1")
        assertEquals("找不到该资源", failure.publicMessage)
        assertEquals("找不到该资源", failure.message)
        assertFalse(failure.uncertain)
        assertEquals("req-1", failure.requestId)

        val legacy = failure.toCloudException()
        assertEquals("找不到该资源", legacy.publicMessage)
        assertFalse(legacy.uncertain)

        val uncertainLegacy =
            CloudFailure(CloudFailureCode.UNKNOWN_RESULT, "结果未知，请核对", uncertain = true)
                .toCloudException()
        assertEquals("结果未知，请核对", uncertainLegacy.publicMessage)
        assertTrue(uncertainLegacy.uncertain)
    }

    @Test
    fun safeMessagePrefersTypedPublicMessageOverTheGenericFallback() {
        assertEquals(
            "找不到该资源",
            safeMessage(CloudFailure(CloudFailureCode.NOT_FOUND, "找不到该资源")),
        )
        assertEquals("旧错误消息", safeMessage(CloudException("旧错误消息", true)))
        assertEquals(
            "暂时无法连接，请检查网络后重试",
            safeMessage(RuntimeException("boom")),
        )
    }

    @Test
    fun typedUncertainFailureMarksTheWriteUnknown() = runTest {
        val outcome =
            failedWriteOperation(
                CloudFailure(CloudFailureCode.UNKNOWN_RESULT, "结果未知，请核对", uncertain = true)
            )
        assertEquals(OperationStatus.UNKNOWN, outcome.status)
        assertEquals("结果未知，请核对", outcome.detail)
    }

    @Test
    fun typedKnownFailureMarksTheWriteFailed() = runTest {
        val outcome =
            failedWriteOperation(CloudFailure(CloudFailureCode.NOT_FOUND, "找不到该记录"))
        assertEquals(OperationStatus.FAILED, outcome.status)
        assertEquals("找不到该记录", outcome.detail)
    }

    @Test
    fun legacyUncertainExceptionStillMarksTheWriteUnknown() = runTest {
        val outcome = failedWriteOperation(CloudException("网络中断，结果待核对", true))
        assertEquals(OperationStatus.UNKNOWN, outcome.status)
        assertEquals("网络中断，结果待核对", outcome.detail)
    }
}
