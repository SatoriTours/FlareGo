package com.flarego.core

import com.flarego.core.cloudflare.protocol.*
import kotlin.test.*

class KvPolicyTest {
    private val base = "/accounts/{account_id}/storage/kv/namespaces"
    private val rows = listOf(
        Triple("list-namespaces", "GET", ""),
        Triple("create-a-namespace", "POST", ""),
        Triple("get-a-namespace", "GET", "/{namespace_id}"),
        Triple("rename-a-namespace", "PUT", "/{namespace_id}"),
        Triple("remove-a-namespace", "DELETE", "/{namespace_id}"),
        Triple("list-a-namespace'-s-keys", "GET", "/{namespace_id}/keys"),
        Triple("read-the-metadata-for-a-key", "GET", "/{namespace_id}/metadata/{key_name}"),
        Triple("read-key-value-pair", "GET", "/{namespace_id}/values/{key_name}"),
        Triple("write-key-value-pair-with-metadata", "PUT", "/{namespace_id}/values/{key_name}"),
        Triple("delete-key-value-pair", "DELETE", "/{namespace_id}/values/{key_name}"),
        Triple("get-multiple-key-value-pairs", "POST", "/{namespace_id}/bulk/get"),
        Triple("write-multiple-key-value-pairs", "PUT", "/{namespace_id}/bulk"),
        Triple("delete-multiple-key-value-pairs", "POST", "/{namespace_id}/bulk/delete"),
    )

    @Test fun allThirteenCurrentOperationsMatchLiteralAccountRoutes() {
        for ((suffix, method, path) in rows) {
            val id = "workers-kv-namespace-$suffix"
            val actual = assertNotNull(ActionPolicies.registry.registration(id), id)
            assertEquals(method, actual.method, id)
            assertEquals(base + path, actual.pathTemplate, id)
            assertEquals(ActionScope.ACCOUNT, actual.scope, id)
            assertFalse(actual.trailingPlaceholderSpansSegments, id)
        }
    }

    @Test fun bulkPostReadIsReadAndAllSevenWritesCannotRetry() {
        val policies = rows.map { assertNotNull(ActionPolicies.policy("workers-kv-namespace-${it.first}")) }
        assertEquals(6, policies.count { it.semantics == ActionSemantics.READ })
        assertEquals(7, policies.count { it.semantics == ActionSemantics.WRITE })
        assertEquals(ActionSemantics.READ, ActionPolicies.policy("workers-kv-namespace-get-multiple-key-value-pairs")?.semantics)
        policies.filter { it.semantics == ActionSemantics.WRITE }.forEach { assertFalse(it.retry.enabled) }
        assertEquals(RiskLevel.DESTRUCTIVE, ActionPolicies.policy("workers-kv-namespace-delete-multiple-key-value-pairs")?.risk)
    }

    @Test fun deprecatedDeleteBulkIsNeverRegisteredOrRouted() {
        assertNull(ActionPolicies.policy("workers-kv-namespace-delete-multiple-key-value-pairs-deprecated"))
        val path = "/accounts/${"a".repeat(32)}/storage/kv/namespaces/${"b".repeat(32)}/bulk"
        assertFalse(ActionPolicies.registry.registrations.values.any { it.matches("DELETE", path) })
    }
}
