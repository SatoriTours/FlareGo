package com.flarego.platform

import com.flarego.core.cloudflare.*
import com.flarego.core.model.CloudException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.Test

/** Synthetic IDs deliberately differ from labels: production must use IDs from the authenticated catalog. */
class OAuthReadScopesTest {
    private val rows = listOf(
        "Account Settings Read" to "fixture.account.read",
        "Zone Read" to "fixture.zone.read",
        "DNS Read" to "fixture.dns.read",
        "Workers Metadata Read-Only" to "fixture.worker-metadata.read",
        "Workers KV Storage Read" to "fixture.kv.read",
        "Workers R2 Storage Read" to "fixture.r2.read",
    )
    private fun body(items: List<Pair<String, String>> = rows, total: Int = items.size) = buildJsonObject {
        put("success", true)
        put("result", buildJsonArray {
            items.forEach { (name, id) -> add(buildJsonObject {
                put("id", id); put("name", name); put("category", "fixture")
                put("scopes", buildJsonArray { add("#fixture:read") })
            }) }
        })
        put("result_info", buildJsonObject { put("total_count", total) })
    }.toString()
    private fun reader(response: String, status: Int = 200) = OAuthReadScopes(HttpTransport { request ->
        assertEquals("GET", request.method)
        assertEquals("/oauth/scopes", request.path)
        HttpResponse(status, response)
    })

    @Test fun matchesOnlySixReadNamesAndUsesTheirActualIds() = runBlocking {
        val catalog = rows.reversed() + listOf(
            "D1 Read" to "fixture.d1.read",
            "Billing Read" to "fixture.billing.read",
            "Workers Admin" to "fixture.worker.admin",
            "Workers R2 Storage Write" to "fixture.r2.write",
            "Workers R2 Storage Bucket Item Read" to "fixture.r2-items.read")
        assertEquals(rows.map { it.second }, reader(body(catalog)).resolve())
    }

    @Test fun missingAmbiguousInvalidOrPartialCatalogCannotBroadenPermissions() = runBlocking {
        for (invalid in listOf(
            body(rows.dropLast(1)),
            body(rows + ("Zone Read" to "fixture.other-zone.read")),
            body(rows.map { if (it.first == "Zone Read") it.first to "fixture.account.read" else it }),
            body(rows.map { if (it.first == "Zone Read") it.first to "unsafe scope" else it }),
            body(rows, total = rows.size + 1),
            """{"success":false,"result":[]}""",
            """{"success":true,"result":"secret-fixture"}""",
        )) {
            val failure = assertFailsWith<CloudException> { reader(invalid).resolve() }
            assertFalse(failure.publicMessage.contains("secret-fixture"))
        }
    }

    @Test fun errorsNeverEchoProviderBodyAndNeverRetry() = runBlocking {
        for (status in listOf(302, 401, 403, 429, 500)) {
            var requests = 0
            val source = OAuthReadScopes(HttpTransport {
                requests++
                HttpResponse(status, "Bearer secret-fixture")
            })
            val failure = assertFailsWith<CloudException> { source.resolve() }
            assertFalse(failure.publicMessage.contains("secret-fixture"))
            assertEquals(1, requests)
        }
    }

    @Test fun bootstrapAndRenewalScopesCannotBeSmuggledThroughCatalogNames() = runBlocking {
        val bad = rows.map { if (it.first == "Zone Read") it.first to "offline_access" else it }
        assertFailsWith<CloudException> { reader(body(bad)).resolve() }
        Unit
    }
}
