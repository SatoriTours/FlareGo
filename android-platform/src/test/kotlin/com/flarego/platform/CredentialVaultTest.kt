package com.flarego.platform

import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.CredentialKind
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * The vault is the only place a credential is read or written. OAuth is retired: a legacy OAuth
 * record must stay recognizable, must never be returned as if it were an API Token, and a failed
 * replacement must not destroy the previous value.
 */
class CredentialVaultTest {
    private val disk = mutableMapOf<String, String>()
    private fun vault(store: MutableMap<String, String> = disk) = CredentialVault(
        read = { store[it] },
        write = { id, value -> store[id] = value },
        delete = { store.remove(it); Unit },
    )

    @Test fun aPastedTokenRoundTripsAndIsClassifiedAsAToken() = runBlocking {
        val v = vault()
        assertEquals(CredentialKind.ABSENT, v.kind("id"))
        assertNull(v.token("id"))
        v.putToken("id", "synthetic-api-token-value")
        assertEquals(CredentialKind.TOKEN, v.kind("id"))
        assertEquals("synthetic-api-token-value", v.token("id"))
    }

    @Test fun aLegacyOAuthRecordIsRecognizedAndNeverReturnedAsAToken() {
        disk["id"] = CredentialVault.LEGACY_OAUTH_PREFIX + """{"client_id":"c","access_token":"legacy-access"}"""
        val v = vault()
        assertEquals(CredentialKind.OAUTH, v.kind("id"))
        val failure = assertFailsWith<CloudFailure> { runBlocking { v.token("id") } }
        assertEquals(CloudFailureCode.CREDENTIAL_EXPIRED, failure.code)
        assertTrue(failure.publicMessage.contains("Token"))
        // The stored payload itself must never leak through the failure.
        assertFalse(failure.publicMessage.contains("legacy-access"))
    }

    @Test fun aLegacyOAuthPayloadCannotBeStoredAsAPlainToken() = runBlocking {
        disk["id"] = CredentialVault.LEGACY_OAUTH_PREFIX + "{}"
        val v = vault()
        assertFailsWith<CloudFailure> { v.putToken("id", CredentialVault.LEGACY_OAUTH_PREFIX + """{"access_token":"x"}""") }
        // Refusing the write keeps the previous record exactly as it was.
        assertEquals(CredentialKind.OAUTH, v.kind("id"))
        assertEquals(CredentialVault.LEGACY_OAUTH_PREFIX + "{}", disk["id"])
    }

    @Test fun aFailedReplacementKeepsThePreviousToken() {
        val store = mutableMapOf("id" to "original-token")
        val failing = CredentialVault(
            read = { store[it] },
            write = { _, _ -> throw IllegalStateException("disk full") },
            delete = { store.remove(it); Unit },
        )
        assertFailsWith<IllegalStateException> { runBlocking { failing.putToken("id", "replacement-token") } }
        assertEquals("original-token", store["id"])
        assertEquals("original-token", runBlocking { failing.token("id") })
    }

    @Test fun blankOrWhitespaceTokensAreRefusedBeforeTouchingStorage() {
        val v = vault()
        for (invalid in listOf("", "   ", "has space", "line\nbreak")) {
            assertFailsWith<CloudFailure> { runBlocking { v.putToken("id", invalid) } }
        }
        assertNull(disk["id"])
    }

    @Test fun rollbackRestoresTheExactPreviousValueIncludingTheLegacyMarker(): Unit = runBlocking {
        val v = vault()
        v.putToken("id", "original-token-0123456789")
        val handle = (v.replaceTokenOrRestore("id", "replacement-token-0123456789") as TokenWrite.Written).rollback
        assertEquals("replacement-token-0123456789", v.token("id"))
        v.restore(handle)
        assertEquals("original-token-0123456789", v.token("id"))
        // The handle never exposes the value it protects.
        assertFalse(handle.toString().contains("original-token"))
        assertFalse(handle.toString().contains("replacement-token"))
    }

    @Test fun rollbackOfALegacyOauthRecordKeepsItLegacyAfterAFailedReplacement(): Unit = runBlocking {
        disk["id"] = CredentialVault.LEGACY_OAUTH_PREFIX + """{"access_token":"legacy-access"}"""
        val v = vault()
        val handle = (v.replaceTokenOrRestore("id", "replacement-token-0123456789") as TokenWrite.Written).rollback
        assertEquals(CredentialKind.TOKEN, v.kind("id"))
        v.restore(handle)
        assertEquals(CredentialKind.OAUTH, v.kind("id"))
        assertEquals(CredentialVault.LEGACY_OAUTH_PREFIX + """{"access_token":"legacy-access"}""", disk["id"])
        assertFailsWith<CloudFailure> { runBlocking { v.token("id") } }
    }

    @Test fun rollbackOfAnAbsentCredentialDeletesTheNewValueInsteadOfLeavingOrphanCiphertext(): Unit = runBlocking {
        val v = vault()
        val handle = (v.replaceTokenOrRestore("new-id", "fresh-token-0123456789") as TokenWrite.Written).rollback
        assertEquals(CredentialKind.TOKEN, v.kind("new-id"))
        v.restore(handle)
        assertEquals(CredentialKind.ABSENT, v.kind("new-id"))
        assertNull(disk["new-id"])
    }

    @Test fun aStaleRollbackCannotOverwriteANewerCredential(): Unit = runBlocking {
        val v = vault()
        v.putToken("id", "first-token-0123456789")
        val stale = (v.replaceTokenOrRestore("id", "second-token-0123456789") as TokenWrite.Written).rollback
        v.putToken("id", "third-token-0123456789")
        v.putToken("id", "third-token-0123456789")
        // A rollback captured before the newer writes must refuse instead of resurrecting "first".
        assertFailsWith<CloudFailure> { v.restore(stale) }
        assertEquals("third-token-0123456789", v.token("id"))
    }

    @Test fun aGatedConnectionNeverHandsOutItsToken(): Unit = runBlocking {
        disk["id"] = "stored-token-0123456789"
        var blocked = true
        val gated = CredentialVault(
            read = { disk[it] },
            write = { id, value -> disk[id] = value },
            delete = { disk.remove(it); Unit },
            blocked = { blocked },
        )
        val failure = assertFailsWith<CloudFailure> { gated.token("id") }
        assertEquals(CloudFailureCode.CREDENTIAL_EXPIRED, failure.code)
        assertFalse(failure.publicMessage.contains("stored-token"))
        blocked = false
        assertEquals("stored-token-0123456789", gated.token("id"))
    }

    @Test fun aGateReadFailurePropagatesInsteadOfReturningTheToken(): Unit = runBlocking {
        disk["id"] = "stored-token-0123456789"
        val throwing = CredentialVault(
            read = { disk[it] },
            write = { id, value -> disk[id] = value },
            delete = { disk.remove(it); Unit },
            blocked = { throw IllegalStateException("gate unreadable") },
        )
        assertFails { throwing.token("id") }
    }

    @Test fun removalForgetsTheCredentialWithoutTouchingOtherRecords() = runBlocking {
        val v = vault()
        v.putToken("one", "token-one-0123456789")
        v.putToken("two", "token-two-0123456789")
        v.remove("one")
        assertEquals(CredentialKind.ABSENT, v.kind("one"))
        assertEquals("token-two-0123456789", v.token("two"))
    }
}
