package com.flarego.core

import com.flarego.core.application.connection.*
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlin.test.*

/**
 * API Token connection contract: discovery, verification, single/multi selection, manual
 * account id, cancel races, retry, pagination and persistence dispatch.
 */
class TokenConnectionTest {
    private val token = "synthetic-token-value-0123456789"
    private val first = "a".repeat(32)
    private val second = "b".repeat(32)

    private class FakeIdentity(
        var userVerify: () -> TokenVerification = { TokenVerification(TokenKind.USER_OWNED, true, null) },
        var page: (PageToken?) -> CloudPage<AccountSummary> = { _ ->
            CloudPage(listOf(AccountSummary("a".repeat(32), "Account")), null, 1)
        },
        var account: (String) -> AccountSummary = { AccountSummary(it, "Account") },
        var accountTokenVerify: (String) -> TokenVerification = { id ->
            TokenVerification(TokenKind.ACCOUNT_OWNED, true, null)
        },
    ) : IdentityPort {
        var userVerifies = 0; var discovers = 0; var accountVerifies = 0; var accountTokenVerifies = 0
        var lastKind: TokenKind? = null
        override suspend fun discover(request: PageRequest): CloudPage<AccountSummary> { discovers++; return page(request.token) }
        override suspend fun verifyAccount(accountId: String): AccountSummary { accountVerifies++; return account(accountId) }
        override suspend fun verifyToken(kind: TokenKind, accountId: String?): TokenVerification {
            lastKind = kind
            return when (kind) {
                TokenKind.USER_OWNED -> { userVerifies++; userVerify() }
                TokenKind.ACCOUNT_OWNED -> { accountTokenVerifies++; accountTokenVerify(accountId!!) }
                TokenKind.UNKNOWN -> TokenVerification(kind, null, null)
            }
        }
    }

    private class Saver {
        val saved = mutableListOf<Triple<String, String, String>>()
        /** What the persisting layer reports; defaults to a normal saved connection. */
        var outcome: CredentialSaveOutcome = CredentialSaveOutcome.SAVED
        var throwOnSave: Exception? = null
        suspend fun save(name: String, accountId: String, token: String): CredentialSaveOutcome {
            throwOnSave?.let { throw it }
            if (outcome == CredentialSaveOutcome.SAVED) saved += Triple(name, accountId, token)
            return outcome
        }
    }

    private fun controller(identity: FakeIdentity, saver: Saver, scope: kotlinx.coroutines.CoroutineScope) =
        TokenConnectionController(scope, { identity }, saver::save)

    @Test fun aSingleDiscoveredAccountIsVerifiedAndSavedWithoutAskingForAnAccountId() = runTest {
        val identity = FakeIdentity(); val saver = Saver()
        val c = controller(identity, saver, backgroundScope)
        c.submit(token); runCurrent()
        assertEquals(TokenPhase.CONNECTED, c.status.value.phase)
        assertEquals(listOf(Triple("Account", first, token)), saver.saved)
        assertTrue(identity.accountVerifies == 1)
        assertFalse(c.status.value.tokenHeld)
        assertFalse(c.status.value.toString().contains(token))
    }

    @Test fun severalAccountsWaitForAnExplicitChoiceAndNeverAutoSave() = runTest {
        val identity = FakeIdentity(
            page = { CloudPage(listOf(AccountSummary(first, "One"), AccountSummary(second, "Two")), null, 2) },
            account = { id -> AccountSummary(id, if (id == second) "Two" else "One") },
        )
        val saver = Saver()
        val c = controller(identity, saver, backgroundScope)
        c.submit(token); runCurrent()
        assertEquals(TokenPhase.CHOOSING, c.status.value.phase)
        assertEquals(2, c.status.value.accounts.size)
        assertTrue(saver.saved.isEmpty())
        c.selectAccount(second); runCurrent()
        assertEquals(TokenPhase.CONNECTED, c.status.value.phase)
        assertEquals(listOf(Triple("Two", second, token)), saver.saved)
    }

    @Test fun anAccountOwnedTokenIsNotCalledInvalidAndUsesTheManualPath() = runTest {
        val identity = FakeIdentity(
            userVerify = { throw CloudFailure(CloudFailureCode.CREDENTIAL_EXPIRED, "not a user token") },
            accountTokenVerify = { TokenVerification(TokenKind.ACCOUNT_OWNED, true, null) },
            account = { AccountSummary(it, "Owned account") },
        )
        val saver = Saver()
        val c = controller(identity, saver, backgroundScope)
        c.submit(token); runCurrent()
        assertEquals(TokenPhase.CHOOSING, c.status.value.phase)
        assertTrue(c.status.value.needsAccountId)
        assertEquals(TokenProblem.NONE, c.status.value.problem)
        assertTrue(saver.saved.isEmpty())
        c.useAccountId(first.uppercase()); runCurrent()
        assertEquals(TokenPhase.CONNECTED, c.status.value.phase)
        assertEquals(listOf(Triple("Owned account", first, token)), saver.saved)
        assertEquals(TokenKind.ACCOUNT_OWNED, identity.lastKind)
    }

    @Test fun aTokenThatCannotListAccountsMayStillVerifyOneAccountReadOnly() = runTest {
        val identity = FakeIdentity(
            page = { throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "no account list permission") },
            account = { AccountSummary(it, "Restricted account") },
        )
        val saver = Saver()
        val c = controller(identity, saver, backgroundScope)
        c.submit(token); runCurrent()
        assertTrue(c.status.value.needsAccountId)
        assertEquals(TokenProblem.PERMISSION, c.status.value.problem)
        val notice = assertNotNull(c.status.value.notice)
        assertTrue(notice.contains("Account ID"))
        assertFalse(notice.contains("无效"))
        c.useAccountId(first); runCurrent()
        assertEquals(TokenPhase.CONNECTED, c.status.value.phase)
        assertEquals("Restricted account", saver.saved.single().first)
    }

    @Test fun anUnreadableAccountNameFallsBackToTheVerifiedAccountId() = runTest {
        val identity = FakeIdentity(
            userVerify = { throw CloudFailure(CloudFailureCode.CREDENTIAL_EXPIRED, "account owned") },
            accountTokenVerify = { TokenVerification(TokenKind.ACCOUNT_OWNED, true, null) },
            account = { throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "cannot read account") },
        )
        val saver = Saver()
        val c = controller(identity, saver, backgroundScope)
        c.submit(token); runCurrent()
        c.useAccountId(first); runCurrent()
        assertEquals(TokenPhase.CONNECTED, c.status.value.phase)
        assertEquals(first, saver.saved.single().first)
        assertNotNull(c.status.value.notice)
    }

    @Test fun aTokenThatFailsBothVerificationsIsNeverSaved() = runTest {
        val identity = FakeIdentity(
            userVerify = { throw CloudFailure(CloudFailureCode.CREDENTIAL_EXPIRED, "401") },
            accountTokenVerify = { throw CloudFailure(CloudFailureCode.CREDENTIAL_EXPIRED, "401") },
            account = { throw CloudFailure(CloudFailureCode.CREDENTIAL_EXPIRED, "401") },
        )
        val saver = Saver()
        val c = controller(identity, saver, backgroundScope)
        c.submit(token); runCurrent()
        c.useAccountId(first); runCurrent()
        assertTrue(saver.saved.isEmpty())
        assertEquals(TokenProblem.TOKEN_REJECTED, c.status.value.problem)
        assertNotEquals(TokenPhase.CONNECTED, c.status.value.phase)
    }

    @Test fun networkFailuresAreRetryableAndKeepTheTokenInMemoryOnly() = runTest {
        var offline = true
        val identity = FakeIdentity(userVerify = {
            if (offline) throw CloudFailure(CloudFailureCode.UNAVAILABLE, "offline")
            TokenVerification(TokenKind.USER_OWNED, true, null)
        })
        val saver = Saver()
        val c = controller(identity, saver, backgroundScope)
        c.submit(token); runCurrent()
        assertEquals(TokenProblem.NETWORK, c.status.value.problem)
        assertTrue(c.status.value.canRetry)
        assertTrue(c.status.value.tokenHeld)
        assertFalse(c.status.value.toString().contains(token))
        offline = false
        c.retry(); runCurrent()
        assertEquals(TokenPhase.CONNECTED, c.status.value.phase)
        assertEquals(1, saver.saved.size)
    }

    @Test fun cancellingDuringDiscoveryDropsTheLateResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        val identity = FakeIdentity()
        val c = controller(identity, Saver(), backgroundScope)
        val blocked = object : IdentityPort {
            override suspend fun discover(request: PageRequest): CloudPage<AccountSummary> {
                gate.await(); return CloudPage(listOf(AccountSummary(first, "Late")), null, 1)
            }
            override suspend fun verifyAccount(accountId: String) = AccountSummary(accountId, "Late")
            override suspend fun verifyToken(kind: TokenKind, accountId: String?) =
                TokenVerification(TokenKind.USER_OWNED, true, null)
        }
        val saver = Saver()
        val c2 = TokenConnectionController(backgroundScope, { blocked }, saver::save)
        c2.submit(token); runCurrent()
        assertTrue(c2.status.value.busy)
        c2.cancel(); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(TokenPhase.IDLE, c2.status.value.phase)
        assertTrue(saver.saved.isEmpty())
    }

    @Test fun aNewerSubmissionWinsOverAStaleOne() = runTest {
        val slow = CompletableDeferred<Unit>()
        var call = 0
        val identity = object : IdentityPort {
            override suspend fun discover(request: PageRequest): CloudPage<AccountSummary> {
                if (call++ == 0) slow.await()
                return CloudPage(listOf(AccountSummary(first, "First")), null, 1)
            }
            override suspend fun verifyAccount(accountId: String) = AccountSummary(accountId, "First")
            override suspend fun verifyToken(kind: TokenKind, accountId: String?) =
                TokenVerification(TokenKind.USER_OWNED, true, null)
        }
        val saver = Saver()
        val c = TokenConnectionController(backgroundScope, { identity }, saver::save)
        c.submit(token); runCurrent()
        c.submit(token + "-second"); runCurrent()
        slow.complete(Unit); runCurrent()
        assertEquals(1, saver.saved.size)
        assertEquals(token + "-second", saver.saved.single().third)
    }

    @Test fun paginationAppendsUntilTheProviderReportsNoMorePages() = runTest {
        val identity = FakeIdentity(page = { cursor ->
            if (cursor == null) CloudPage(listOf(AccountSummary(first, "One")), PageToken.Number(2), 3)
            else CloudPage(listOf(AccountSummary(second, "Two")), null, 3)
        })
        val c = controller(identity, Saver(), backgroundScope)
        c.submit(token); runCurrent()
        assertTrue(c.status.value.hasMore)
        c.loadMore(); runCurrent()
        assertEquals(2, c.status.value.accounts.size)
        assertFalse(c.status.value.hasMore)
    }

    @Test fun malformedInputNeverReachesTheNetwork() = runTest {
        val identity = FakeIdentity(); val saver = Saver()
        val c = controller(identity, saver, backgroundScope)
        c.submit("   "); runCurrent()
        assertEquals(TokenProblem.INVALID_INPUT, c.status.value.problem)
        c.submit("has space"); runCurrent()
        assertEquals(TokenProblem.INVALID_INPUT, c.status.value.problem)
        // No request came from the malformed tokens themselves.
        assertEquals(0, identity.discovers)
        assertEquals(0, identity.userVerifies)

        // A held token with an unusable Account ID is rejected locally too.
        val two = FakeIdentity(page = {
            CloudPage(listOf(AccountSummary(first, "One"), AccountSummary(second, "Two")), null, 2)
        })
        val c2 = TokenConnectionController(backgroundScope, { two }, saver::save)
        c2.submit(token); runCurrent()
        c2.useAccountId("not-an-account"); runCurrent()
        assertEquals(TokenProblem.INVALID_INPUT, c2.status.value.problem)
        assertEquals(0, two.accountVerifies)
        assertTrue(saver.saved.isEmpty())
    }

    @Test fun cancellingAfterAConnectionKeepsTheSavedConnection() = runTest {
        val saver = Saver()
        val c = controller(FakeIdentity(), saver, backgroundScope)
        c.submit(token); runCurrent()
        assertEquals(TokenPhase.CONNECTED, c.status.value.phase)
        c.cancel(); runCurrent()
        assertEquals(1, saver.saved.size)
        assertEquals(TokenPhase.CONNECTED, c.status.value.phase)
        assertFalse(c.status.value.tokenHeld)
    }

    @Test fun aSaveThatWasNotStoredKeepsTheTokenAndOffersRetryWithoutClaimingAPartialWrite() = runTest {
        val saver = Saver().apply { outcome = CredentialSaveOutcome.NOT_SAVED }
        val c = controller(FakeIdentity(), saver, backgroundScope)
        c.submit(token); runCurrent()
        val state = c.status.value
        assertNotEquals(TokenPhase.CONNECTED, state.phase)
        assertFalse(state.connected)
        assertTrue(state.tokenHeld)
        assertTrue(state.canRetry)
        val error = assertNotNull(state.error)
        // The message must describe the restored state, never a blanket "nothing was written".
        assertTrue(error.contains("恢复到连接前的状态"))
        assertFalse(error.contains("未写入"))
    }

    @Test fun anUncertainSaveIsFailClosedAndRequiresReconfiguration() = runTest {
        val saver = Saver().apply { outcome = CredentialSaveOutcome.STATE_UNCERTAIN }
        val c = controller(FakeIdentity(), saver, backgroundScope)
        c.submit(token); runCurrent()
        val state = c.status.value
        assertNotEquals(TokenPhase.CONNECTED, state.phase)
        assertFalse(state.connected)
        // Fail closed: the token is dropped and no retry is offered from this state.
        assertFalse(state.tokenHeld)
        assertFalse(state.canRetry)
        val error = assertNotNull(state.error)
        assertTrue(error.contains("不确定"))
        assertFalse(error.contains("未写入"))
    }

    @Test fun aThrowingSaveIsReportedAsNotSavedAndKeepsTheTokenInMemory() = runTest {
        val saver = Saver().apply { throwOnSave = IllegalStateException("storage failure") }
        val c = controller(FakeIdentity(), saver, backgroundScope)
        c.submit(token); runCurrent()
        val state = c.status.value
        assertFalse(state.connected)
        assertTrue(state.tokenHeld)
        assertTrue(state.canRetry)
        assertEquals(null, state.toString().let { if (it.contains(token)) "leak" else null })
    }

    @Test fun tokenVerificationCannotClaimAConnectionWhenTheProviderSaysDisabled() = runTest {
        val identity = FakeIdentity(userVerify = { TokenVerification(TokenKind.USER_OWNED, false, "disabled") })
        val saver = Saver()
        val c = controller(identity, saver, backgroundScope)
        c.submit(token); runCurrent()
        assertEquals(TokenProblem.TOKEN_REJECTED, c.status.value.problem)
        assertTrue(saver.saved.isEmpty())
        assertEquals(0, identity.discovers)
    }
}
