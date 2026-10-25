package com.flarego.core

import com.flarego.core.cloudflare.protocol.*
import kotlin.test.*

class D1PolicyTest {
    private val base = "/accounts/{account_id}/d1/database"
    private val rows = listOf(
        Triple("d1-list-databases", "GET", ""),
        Triple("d1-create-database", "POST", ""),
        Triple("d1-get-database", "GET", "/{database_id}"),
        Triple("d1-update-database", "PUT", "/{database_id}"),
        Triple("d1-update-partial-database", "PATCH", "/{database_id}"),
        Triple("d1-delete-database", "DELETE", "/{database_id}"),
        Triple("d1-undelete-database", "POST", "/{database_id}/undelete"),
        Triple("d1-query-database", "POST", "/{database_id}/query"),
        Triple("d1-raw-database-query", "POST", "/{database_id}/raw"),
        Triple("d1-export-database", "POST", "/{database_id}/export"),
        Triple("d1-import-database", "POST", "/{database_id}/import"),
        Triple("d1-time-travel-get-bookmark", "GET", "/{database_id}/time_travel/bookmark"),
        Triple("d1-time-travel-restore", "POST", "/{database_id}/time_travel/restore"),
    )

    @Test fun thirteenLiteralOperationsHaveExactRoutesAndAccountScope() {
        rows.forEach { (key, method, suffix) ->
            val actual = assertNotNull(ActionPolicies.registry.registration(key), key)
            assertEquals(method, actual.method, key)
            assertEquals(base + suffix, actual.pathTemplate, key)
            assertEquals(ActionScope.ACCOUNT, actual.scope, key)
            assertFalse(actual.trailingPlaceholderSpansSegments, key)
        }
        assertEquals(13, ActionPolicies.registry.registrations.keys.count { it.startsWith("d1-") })
    }

    @Test fun arbitrarySqlAndAllAvailabilityMutationsAreWritesWithoutRetries() {
        rows.forEach { (key, method, _) ->
            val policy = assertNotNull(ActionPolicies.policy(key), key)
            assertEquals(if (method == "GET") ActionSemantics.READ else ActionSemantics.WRITE, policy.semantics, key)
            if (method != "GET") assertFalse(policy.retry.enabled, key)
        }
        for (key in listOf("d1-query-database", "d1-raw-database-query", "d1-import-database", "d1-time-travel-restore", "d1-undelete-database")) {
            assertEquals(RiskLevel.DESTRUCTIVE, ActionPolicies.policy(key)?.risk, key)
        }
    }

    @Test fun registrationsCannotRouteAlternateMethodsOrForeignPaths() {
        rows.forEach { (key, method, suffix) ->
            val route = assertNotNull(ActionPolicies.registry.registration(key), key)
            val path = (base + suffix).replace("{account_id}", "a".repeat(32))
                .replace("{database_id}", "12345678-1234-4321-abcd-123456789abc")
            assertTrue(route.matches(method, path), key)
            assertFalse(route.matches(if (method == "POST") "DELETE" else "POST", path), key)
            assertFalse(route.matches(method, path.replace("/d1/database", "/storage/kv/namespaces")), key)
        }
    }
}
