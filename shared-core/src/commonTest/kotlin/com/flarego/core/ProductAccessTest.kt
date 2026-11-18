package com.flarego.core

import com.flarego.core.application.inventory.ProductAccess
import com.flarego.core.application.inventory.ProductKey
import com.flarego.core.model.CredentialKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Token-only build: capabilities follow the credential kind, never an OAuth scope list. */
class ProductAccessTest {
    @Test fun aTokenMayAttemptEveryRegisteredReadProduct() {
        assertEquals(ProductKey.entries.toSet(), ProductAccess.tokenReadable)
        assertTrue(ProductKey.BILLING in ProductAccess.tokenReadable)
        assertTrue(ProductKey.D1 in ProductAccess.tokenReadable)
        assertEquals(ProductAccess.tokenReadable, ProductAccess.readable(CredentialKind.TOKEN, demo = false))
    }

    @Test fun aLegacyOauthConnectionMayRequestNothingUntilATokenIsConfigured() {
        // OAuth is retired: keeping the name/record is allowed, sending requests is not.
        assertEquals(emptySet(), ProductAccess.readable(CredentialKind.OAUTH, false))
        assertEquals(emptySet(), ProductAccess.readable(CredentialKind.ABSENT, false))
        assertFalse(ProductKey.ZONES in ProductAccess.readable(CredentialKind.OAUTH, false))
    }

    @Test fun registrarDiscoveryNeedsATokenOrADemoConnection() {
        assertTrue(ProductAccess.registrarReadable(CredentialKind.TOKEN, demo = false))
        assertFalse(ProductAccess.registrarReadable(CredentialKind.OAUTH, demo = false))
        assertFalse(ProductAccess.registrarReadable(CredentialKind.ABSENT, demo = false))
        assertTrue(ProductAccess.registrarReadable(CredentialKind.OAUTH, demo = true))
    }
}
