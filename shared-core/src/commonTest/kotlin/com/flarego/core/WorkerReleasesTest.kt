package com.flarego.core

import com.flarego.core.application.workers.*
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.workers.CloudflareWorkerReleases
import com.flarego.core.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

internal val workerReleaseReadActions = setOf(
    "worker-script-search-workers",
    "worker-deployments-list-deployments",
    "worker-deployments-get-deployment",
    "worker-versions-list-versions",
    "worker-versions-get-version-detail",
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkerReleasesTest {
    private val account = AccountScope("local", "a".repeat(32))
    private val connection = Connection(account.connectionId, "Test", account.accountId)
    private val workerId = "c".repeat(32)
    private val worker = CloudResource(workerId, "display-name", ResourceKind.WORKER, "Workers",
        resourceRef = ResourceRef(account, "worker", workerId))
    private val base = "/accounts/${account.accountId}/workers"
    private val lookup = "$base/scripts-search?id=$workerId&page=1&per_page=2"
    private val script = "$base/scripts/proven-script"
    private val deployment = "e26eba62-5560-4597-b834-8b7fd69f44d1"
    private val first = "c2d711fc-626e-432e-a308-8f57a1ceb111"
    private val second = "c2d711fc-626e-432e-a308-8f57a1ceb222"
    private val newest = "c2d711fc-626e-432e-a308-8f57a1ceb333"
    private val mapping = """[{"id":"$workerId","script_name":"proven-script","created_on":"2026-11-20T00:00:00Z","modified_on":"2026-11-20T00:00:00Z"}]"""
    private fun release(versions: String) = """{"id":"$deployment","created_on":"2026-11-21T10:00:00Z","source":"api","strategy":"percentage","versions":$versions}"""
    private fun ok(result: String, metadata: String = "") =
        HttpResponse(200, """{"success":true,"result":$result$metadata,"errors":[],"messages":[]}""")
    private fun client(resource: CloudResource = worker, handle: suspend (String) -> HttpResponse) =
        CloudflareWorkerReleases(connection, resource, CloudflareGateway(HttpTransport { request ->
            assertEquals("GET", request.method)
            handle(request.path)
        }, sleeper = {}))

    @Test fun matchingIdUsesReturnedScriptNameAndNeverPromotesNewestVersion() = runTest {
        val api = client { path ->
            when (path) {
                lookup -> ok(mapping)
                "$script/deployments?page=1&per_page=10" -> ok("""{"deployments":[${release("""[{"version_id":"$first","percentage":90},{"version_id":"$second","percentage":10}]""")}]}""")
                "$script/versions?page=1&per_page=10" -> ok("""{"items":[{"id":"$newest","number":26},{"id":"$second","number":25}]}""")
                else -> error("Unexpected path: $path")
            }
        }
        val current = api.deployments(1).items.single()
        assertEquals(listOf(Allocation(first, "90"), Allocation(second, "10")), current.allocations)
        assertEquals(listOf(newest, second), api.versions(1).items.map { it.id })
        assertNull(current.allocations.find { it.versionId == newest })
    }

    @Test fun mappingMustMatchExactIdAndNotBeAmbiguousAndScopeIsCheckedBeforeReading() = runTest {
        for (rows in listOf("[]", mapping.replace(workerId, "other-id"), mapping.dropLast(1) + "," + mapping.drop(1))) {
            val paths = mutableListOf<String>()
            val api = client { path -> paths += path; ok(rows) }
            assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY, assertFailsWith<CloudFailure> { api.deployments(1) }.code)
            assertEquals(listOf(lookup), paths)
        }
        val other = worker.copy(resourceRef = ResourceRef(account.copy(connectionId = "other"), "worker", workerId))
        assertEquals(CloudFailureCode.SCOPE_MISMATCH,
            assertFailsWith<CloudFailure> { client(other) { fail("Cross-account request") }.deployments(1) }.code)
    }

    @Test fun emptyDeploymentIsDifferentFromMalformedOrFailedRead() = runTest {
        for ((response, code) in listOf(
            HttpResponse(403, "") to CloudFailureCode.PERMISSION_REQUIRED,
            HttpResponse(429, "") to CloudFailureCode.RATE_LIMITED,
            ok("{}") to CloudFailureCode.INVALID_RESPONSE,
            ok("""{"deployments":[${release("""[{"version_id":"$first","percentage":10}]""")}]}""") to CloudFailureCode.INVALID_RESPONSE,
        )) {
            val api = client { if (it == lookup) ok(mapping) else response }
            assertEquals(code, assertFailsWith<CloudFailure> { api.deployments(1) }.code)
        }
        assertEquals(CloudFailureCode.UNAVAILABLE, assertFailsWith<CloudFailure> {
            client { if (it == lookup) ok(mapping) else throw IllegalStateException("offline") }.deployments(1)
        }.code)
        assertTrue(client { if (it == lookup) ok(mapping) else ok("""{"deployments":[]}""") }.deployments(1).items.isEmpty())
        val one = client { if (it == lookup) ok(mapping) else
            ok("""{"deployments":[${release("""[{"version_id":"$first","percentage":100}]""")}]}""") }
        assertEquals(listOf(Allocation(first, "100")), one.deployments(1).items.single().allocations)
    }

    @Test fun legacyUsesExplicitScriptAndVersionPaginationWithoutMetadata() = runTest {
        val legacy = worker.copy(id = "proven-script", legacy = true, resourceRef = ResourceRef(account, "worker_script", "proven-script"))
        val paged = (1..10).joinToString(",") { """{"id":"c2d711fc-626e-432e-a308-8f57a1ceb3${10 + it}","number":$it}""" }
        val api = client(legacy) {
            when (it) {
                "$script/versions?page=1&per_page=10" -> ok("""{"items":[$paged]}""")
                "$script/versions?page=2&per_page=10" -> ok("""{"items":[]}""")
                "$script/deployments?page=1&per_page=10" -> ok("""{"deployments":[${release("""[{"version_id":"$first","percentage":100}]""")}]}""", ""","result_info":{"total_pages":2}""")
                else -> error("Unexpected path: $it")
            }
        }
        assertEquals(2, api.versions(1).next)
        assertNull(api.versions(2).next)
        assertEquals(2, api.deployments(1).next)
    }

    @Test fun versionAndDeploymentDetailsStayOnExplicitIdsAndOnlyExposeMetadata() = runTest {
        val api = client { path ->
            when (path) {
                lookup -> ok(mapping)
                "$script/deployments/$deployment" -> ok(release("""[{"version_id":"$first","percentage":100}]"""))
                "$script/versions/$first" -> ok("""{"id":"$first","number":24,"metadata":{"source":"wrangler"},"resources":{"script_runtime":{"compatibility_date":"2026-11-01"},"bindings":[{"name":"PRIVATE_CONFIG","type":"plain_text","text":"synthetic-value"}]}}""")
                else -> error("Unexpected path: $path")
            }
        }
        assertEquals(deployment, api.deployment(deployment).id)
        val version = api.version(first)
        assertEquals("2026-11-01", version.compatibilityDate)
        assertEquals("wrangler", version.source)
        assertFalse(version.toString().contains("synthetic-value"))
    }

    @Test fun failedContinuationAndRefreshKeepLastConfirmedCurrentDeployment() = runTest {
        var status = 200
        var offline = false
        val api = client { path ->
            when {
                path == lookup -> ok(mapping)
                path == "$script/deployments?page=1&per_page=10" -> {
                    if (offline) throw IllegalStateException("offline")
                    ok("""{"deployments":[${release("""[{"version_id":"$first","percentage":100}]""")}]}""",
                        ""","result_info":{"total_pages":2}""")
                }
                path == "$script/deployments?page=2&per_page=10" -> if (status == 429) {
                    HttpResponse(429, "{}")
                } else {
                    ok("""{"deployments":[]}""")
                }
                else -> error("Unexpected read")
            }
        }
        var now = 1000L
        val browser = WorkerReleaseBrowser(this, { _, _ -> api }, { now })
        browser.open(connection, worker)
        runCurrent()
        browser.selectTab(WorkerTab.DEPLOYMENTS)
        status = 429
        now = 2000L
        browser.load(more = true)
        runCurrent()
        assertEquals(CloudFailureCode.RATE_LIMITED, browser.state.value?.deployments?.error?.code)
        assertTrue(assertNotNull(browser.state.value).deployments.appendFailed)
        assertEquals(2, browser.state.value?.deployments?.value?.next)
        status = 200
        browser.load(more = true)
        runCurrent()
        assertNull(browser.state.value?.deployments?.error)
        assertNull(browser.state.value?.deployments?.value?.next)
        assertFalse(browser.back())
        offline = true
        browser.load()
        runCurrent()
        val retained = assertNotNull(browser.state.value)
        assertEquals(deployment, retained.current?.id)
        assertEquals(1000L, retained.deployments.observedAtMs)
        assertEquals(CloudFailureCode.UNAVAILABLE, retained.deployments.error?.code)
    }

    @Test fun switchingAwayDropsLateResponseEvenIfTransportCompletesAfterCancellation() = runTest {
        val oldResult = CompletableDeferred<HttpResponse>()
        val started = CompletableDeferred<Unit>()
        val browser = WorkerReleaseBrowser(this, { c, r ->
            CloudflareWorkerReleases(c, r, CloudflareGateway(HttpTransport { request ->
                if (c == connection) {
                    if (request.path == lookup) ok(mapping) else {
                        started.complete(Unit)
                        withContext(NonCancellable) { oldResult.await() }
                    }
                } else {
                    if (request.path.contains("scripts-search")) ok(mapping) else ok("""{"deployments":[]}""")
                }
            }))
        }, { 1000L })
        browser.open(connection, worker)
        runCurrent()
        assertTrue(started.isCompleted)
        browser.clear()
        val other = connection.copy(id = "new-local", accountId = "b".repeat(32))
        browser.open(other, worker.copy(resourceRef = ResourceRef(AccountScope(other.id, other.accountId), "worker", workerId)))
        runCurrent()
        oldResult.complete(ok("""{"deployments":[${release("""[{"version_id":"$first","percentage":100}]""")}]}"""))
        runCurrent()
        val state = assertNotNull(browser.state.value)
        assertEquals(other.id, (state.worker.resourceRef?.scope as AccountScope).connectionId)
        assertTrue(assertNotNull(state.deployments.value).items.isEmpty())
        assertNull(state.deployments.error)
    }
}
