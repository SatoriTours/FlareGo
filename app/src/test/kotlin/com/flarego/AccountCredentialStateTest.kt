package com.flarego

import com.flarego.core.model.CredentialKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account capability state must follow what is actually stored. A row without a credential, a
 * legacy OAuth record, or a commit whose result is unknown all mean "needs a Token" — never
 * "connected".
 */
class AccountCredentialStateTest {
    @Test fun aTokenIsTheOnlyKindThatCountsAsConfigured() {
        assertTrue(needsTokenConfiguration(CredentialKind.TOKEN, demo = false).not())
        assertTrue(needsTokenConfiguration(CredentialKind.OAUTH, demo = false))
        assertTrue(needsTokenConfiguration(CredentialKind.ABSENT, demo = false))
        // Demo connections never need a credential.
        assertFalse(needsTokenConfiguration(CredentialKind.ABSENT, demo = true))
    }

    @Test fun anUncertainCommitIsTreatedAsAbsentUntilReconfigured() {
        assertTrue(needsTokenConfiguration(effectiveCredentialKind(CredentialKind.TOKEN, uncertain = true), demo = false))
        // Fail closed: an uncertain commit must not silently become usable again.
        assertTrue(effectiveCredentialKind(CredentialKind.TOKEN, uncertain = true) == CredentialKind.ABSENT)
        assertTrue(effectiveCredentialKind(CredentialKind.TOKEN, uncertain = false) == CredentialKind.TOKEN)
    }
}
