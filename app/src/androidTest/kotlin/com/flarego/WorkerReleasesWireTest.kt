package com.flarego

import com.flarego.core.application.workers.*
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.workers.CloudflareWorkerReleases
import com.flarego.core.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Targeted shared-core contracts on Android; the only double is the provider transport. */
class WorkerReleasesWireTest {
    private val owner = Connection("a", "Synthetic", "a".repeat(32))
    private val a = AccountScope(owner.id, owner.accountId)
    private val id = "c".repeat(32)
    private val row = CloudResource(id, "unsafe-display-name", ResourceKind.WORKER, "Workers",
        resourceRef = ResourceRef(a, "worker", id))
    private val base = "/accounts/${a.accountId}/workers"
    private val lookup = "$base/scripts-search?id=$id&page=1&per_page=2"
    private val script = "$base/scripts/proven-script"
    private val active = "e26eba62-5560-4597-b834-8b7fd69f44d1"
    private val v1 = "c2d711fc-626e-432e-a308-8f57a1ceb111"
    private val v2 = "d2d711fc-626e-432e-a308-8f57a1ceb222"
    private val match = """[{"id":"$id","script_name":"proven-script"}]"""
    private val empty = """{"deployments":[]}"""

    private fun deployment(allocation: String = """[{"version_id":"$v1","percentage":100}]""") =
        """{"deployments":[{"id":"$active","created_on":"2026-11-21T10:00:00Z","source":"api","strategy":"percentage","versions":$allocation}]}"""
    private fun ok(result: String, info: String = "") = HttpResponse(200, """{"success":true,"errors":[],"messages":[],"result":$result$info}""")
    private fun api(connection: Connection = owner, resource: CloudResource = row, read: suspend (String) -> HttpResponse) =
        CloudflareWorkerReleases(connection, resource, CloudflareGateway(HttpTransport { r ->
            assertEquals("GET", r.method)
            read(r.path)
        }, sleeper = {}))

    @Test fun uniqueModernMappingAndExplicitLegacyUseOnlyProvenScriptNames() = runBlocking {
        val paths = mutableListOf<String>()
        val response = api { path ->
            paths += path
            when (path) {
                lookup -> ok(match)
                "$script/deployments?page=1&per_page=10" -> ok(deployment())
                else -> error("Unexpected read")
            }
        }.deployments(1)
        assertEquals(listOf(Allocation(v1, "100")), response.items.single().allocations)
        assertEquals(listOf(lookup, "$script/deployments?page=1&per_page=10"), paths)
        for (rows in listOf("[]", match.replace(id, "other"), match.dropLast(1) + "," + match.drop(1))) {
            paths.clear()
            val error = runCatching { api { path -> paths += path; ok(rows) }.deployments(1) }.exceptionOrNull() as CloudFailure
            assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY, error.code)
            assertEquals(listOf(lookup), paths)
        }
        val wrong = row.copy(resourceRef = ResourceRef(a.copy(connectionId = "another-local"), "worker", id))
        val error = runCatching { api(resource = wrong) { fail("Wrong scope reached transport"); ok(empty) }.deployments(1) }.exceptionOrNull() as CloudFailure
        assertEquals(CloudFailureCode.SCOPE_MISMATCH, error.code)
        val legacy = row.copy(id = "proven-script", legacy = true, resourceRef = ResourceRef(a, "worker_script", "proven-script"))
        val versionRows = (1..10).joinToString(",") { """{"id":"c2d711fc-626e-432e-a308-8f57a1ceb3${10 + it}","number":$it}""" }
        val versions = api(resource = legacy) {
            when (it) {
                "$script/versions?page=1&per_page=10" -> ok("""{"items":[$versionRows]}""")
                "$script/versions?page=2&per_page=10" -> ok("""{"items":[]}""")
                else -> error("Legacy route must not look up an ID")
            }
        }
        assertEquals(2, versions.versions(1).next)
        assertNull(versions.versions(2).next)
    }

    @Test fun paginationAndOfflineRefreshRetainTheLastConfirmedSplitWithoutFabricatingZero() = runBlocking {
        var offline = false
        var throttled = true
        val client = api { path ->
            when (path) {
                lookup -> ok(match)
                "$script/deployments?page=1&per_page=10" -> {
                    if (offline) throw java.io.IOException("offline")
                    ok(deployment("""[{"version_id":"$v1","percentage":90},{"version_id":"$v2","percentage":10}]"""),
                        ""","result_info":{"page":1,"total_pages":2}""")
                }
                "$script/deployments?page=2&per_page=10" -> if (throttled) HttpResponse(429, "{}") else ok(empty)
                else -> error("Unexpected read")
            }
        }
        var clock = 1000L
        val browser = WorkerReleaseBrowser(this, { _, _ -> client }, { clock })
        browser.open(owner, row)
        yield()
        browser.go(WorkerRoute.History)
        browser.load(more = true)
        yield()
        val failedPage = checkNotNull(browser.state.value).deployments
        assertTrue(failedPage.appendFailed)
        assertEquals(CloudFailureCode.RATE_LIMITED, failedPage.error?.code)
        assertEquals(2, failedPage.value?.next)
        throttled = false
        browser.load(more = true)
        yield()
        assertTrue(browser.back())
        clock = 2000L
        offline = true
        browser.load()
        yield()
        val state = checkNotNull(browser.state.value)
        assertEquals(listOf(Allocation(v1, "90"), Allocation(v2, "10")), state.current?.allocations)
        assertEquals(CloudFailureCode.UNAVAILABLE, state.deployments.error?.code)
        assertEquals(1000L, state.deployments.observedAtMs)
        assertNull(state.deployments.value?.next)
        val malformed = runCatching {
            api { if (it == lookup) ok(match) else ok(deployment("[]")) }.deployments(1)
        }.exceptionOrNull() as CloudFailure
        assertEquals(CloudFailureCode.INVALID_RESPONSE, malformed.code)
    }

    @Test fun cancelledOldAccountCannotPublishEvenWhenItsTransportReturnsLate() = runBlocking {
        val oldRequest = CompletableDeferred<Unit>()
        val oldResult = CompletableDeferred<HttpResponse>()
        val browser = WorkerReleaseBrowser(this, { connection, row ->
            api(connection, row) { path ->
                if (path.contains("/scripts-search?")) ok(match)
                else if (connection == owner) {
                    oldRequest.complete(Unit)
                    withContext(NonCancellable) { oldResult.await() }
                } else ok(empty)
            }
        }, { 1000L })
        try {
            browser.open(owner, row)
            withTimeout(3000) { oldRequest.await() }
            val other = Connection("b", "Second account", "b".repeat(32))
            browser.open(other, row.copy(resourceRef = ResourceRef(AccountScope(other.id, other.accountId), "worker", id)))
            yield()
            oldResult.complete(ok(deployment()))
            yield()
            val state = checkNotNull(browser.state.value)
            assertEquals(other.id, (state.worker.resourceRef?.scope as AccountScope).connectionId)
            assertNotNull(state.deployments.value)
            assertNull(state.current)
            assertNull(state.deployments.error)
        } finally {
            oldResult.complete(ok(deployment()))
            browser.clear()
        }
    }
}
