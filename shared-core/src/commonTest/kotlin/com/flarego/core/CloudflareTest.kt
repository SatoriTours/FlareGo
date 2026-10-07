package com.flarego.core

import com.flarego.core.cloudflare.*
import com.flarego.core.model.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class CloudflareTest {
    private val connection = Connection("live", "My Cloudflare", "account-1")

    private fun ok(result: String, info: String = "{}") =
        HttpResponse(200, """{"success":true,"result":$result,"result_info":$info}""")

    @Test
    fun workersAllResultsAreReadOnceWithoutPaging() = runTest {
        var reads = 0
        val api =
            CloudflareSession(
                connection,
                HttpTransport { r ->
                    when {
                        r.path.contains("workers/scripts") -> {
                            reads++
                            ok(
                                (1..75).joinToString(prefix = "[", postfix = "]") {
                                    """{"id":"worker-$it"}"""
                                }
                            )
                        }
                        r.path.contains("r2/buckets") -> ok("""{"buckets":[]}""")
                        else -> ok("[]")
                    }
                },
            )
        val inventory = api.inventory()
        assertEquals(75, inventory.resources.count { it.kind == ResourceKind.WORKER })
        assertEquals(1, reads)
    }

    @Test
    fun zonesAreScopedAndPaginated() = runTest {
        val requests = mutableListOf<HttpRequest>()
        val api =
            CloudflareSession(
                connection,
                HttpTransport { r ->
                    requests.add(r)
                    when {
                        r.path.startsWith("/zones?") ->
                            ok(
                                if ("page=2" in r.path)
                                    """[{"id":"z2","name":"second.example","status":"active"}]"""
                                else """[{"id":"z1","name":"first.example","status":"active"}]""",
                                """{"total_pages":2}""",
                            )
                        r.path.contains("r2/buckets") -> ok("""{"buckets":[]}""")
                        else -> ok("[]")
                    }
                },
            )
        val result = api.inventory()
        assertEquals(listOf("first.example", "second.example"), result.domains.map { it.name })
        assertTrue(
            requests
                .filter { it.path.startsWith("/zones?") }
                .all { "account.id=account-1" in it.path }
        )
        assertEquals(2, requests.count { it.path.startsWith("/zones?") })
    }

    @Test
    fun r2CursorAndPartialPermissions() = runTest {
        val requests = mutableListOf<HttpRequest>()
        val api =
            CloudflareSession(
                connection,
                HttpTransport { r ->
                    requests.add(r)
                    when {
                        r.path.contains("workers/scripts") ->
                            HttpResponse(
                                403,
                                """{"success":false,"errors":[{"message":"Bearer SECRET"}]}""",
                            )
                        r.path.contains("r2/buckets") && "cursor=" !in r.path ->
                            ok(
                                """{"buckets":[{"name":"media","location":"apac"}]}""",
                                """{"cursor":"next+/="}""",
                            )
                        r.path.contains("r2/buckets") ->
                            ok("""{"buckets":[{"name":"backup","location":"weur"}]}""")
                        else -> ok("[]")
                    }
                },
            )
        val result = api.inventory()
        assertEquals(listOf("media", "backup"), result.resources.map { it.name })
        assertTrue(requests.any { "cursor=next%2B%2F%3D" in it.path })
        assertTrue(result.notices.any { it.contains("权限") })
        assertFalse(result.notices.joinToString().contains("SECRET"))
    }

    @Test
    fun dnsPatchUsesJsonAndSpecifiedRecord() = runTest {
        var request: HttpRequest? = null
        val api =
            CloudflareSession(
                connection,
                HttpTransport { r ->
                    request = r
                    ok("{}")
                },
            )
        api.save(
            "zone",
            "record",
            DnsDraft("TXT", "test.example.com", "quoted \"value\"\nline", 3600),
        )
        assertEquals("PATCH", request?.method)
        assertEquals("/zones/zone/dns_records/record", request?.path)
        val body = Json.parseToJsonElement(request!!.body!!).jsonObject
        assertEquals("quoted \"value\"\nline", body["content"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("priority"))
    }

    @Test
    fun billingHasNoInventedEstimate() = runTest {
        val api =
            CloudflareSession(
                connection,
                HttpTransport {
                    ok(
                        """[{"id":"bill","occurred_at":"2026-09-30","amount":20.99,"currency":"USD","description":"charge"}]"""
                    )
                },
            )
        val billing = api.billing()
        assertNull(billing.estimate)
        assertEquals("20.99", billing.invoices.single().amount)
        assertTrue(billing.notice!!.contains("用户"))
    }

    @Test
    fun serverFailureDuringWriteIsUncertain() = runTest {
        val api = CloudflareSession(connection, HttpTransport { HttpResponse(503, "SECRET") })
        val exception = assertFailsWith<CloudException> { api.delete("zone", "record") }
        assertTrue(exception.uncertain)
        assertFalse(exception.message!!.contains("SECRET"))
    }

    @Test
    fun malformedSuccessfulWriteIsUncertain() = runTest {
        val api =
            CloudflareSession(connection, HttpTransport { HttpResponse(200, "unexpected html") })
        assertTrue(
            assertFailsWith<CloudException> {
                    api.save("zone", null, DnsDraft("A", "example.com", "192.0.2.1"))
                }
                .uncertain
        )
    }

    @Test
    fun structuralWriteResponsesStayUnknown() = runTest {
        listOf("[]", "{}", "null", "\"invalid\"").forEach { success ->
            val api =
                CloudflareSession(
                    connection,
                    HttpTransport { HttpResponse(200, """{"success":$success}""") },
                )
            assertTrue(assertFailsWith<CloudException> { api.delete("zone", "record") }.uncertain)
        }
    }

    @Test
    fun malformedOneServiceDoesNotDiscardOtherResources() = runTest {
        val api =
            CloudflareSession(
                connection,
                HttpTransport { r ->
                    when {
                        r.path.contains("workers/scripts") -> ok("[42]")
                        r.path.contains("r2/buckets") ->
                            ok("""{"buckets":[{"name":"media","location":"apac"}]}""")
                        else -> ok("[]")
                    }
                },
            )
        val result = api.inventory()
        assertEquals(listOf("media"), result.resources.map { it.name })
        assertTrue(result.notices.any { it.contains("Workers") })
    }
}
