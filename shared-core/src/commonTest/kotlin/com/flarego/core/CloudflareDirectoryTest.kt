package com.flarego.core

import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.inventory.CloudflareDirectoryClient
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.demo.DemoDirectory
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CloudflareDirectoryTest {
    private val connection = Connection("local", "Cloudflare", "account-one")
    private val account = AccountScope("local", "account-one")
    private fun ok(result: String, info: String = "{}") =
        HttpResponse(200, """{"success":true,"errors":[],"messages":[],"result":$result,"result_info":$info}""")
    private fun worker(id: String = "immutable-id", name: String = "script-name", deployed: String = "null") =
        """{"id":"$id","name":"$name","tags":[],"subdomain":{"enabled":false,"previews_enabled":false},"observability":{"enabled":false},"logpush":false,"tail_consumers":[],"created_on":"2026-10-01T00:00:00Z","updated_on":"2026-10-02T00:00:00Z","deployed_on":$deployed,"references":{"workers":[],"domains":[],"dispatch_namespace_outbounds":[],"durable_objects":[],"queues":[]}}"""
    private fun client(mode: WorkersDirectoryMode = WorkersDirectoryMode.MODERN,
        bound: Connection = connection, send: suspend (HttpRequest) -> HttpResponse) =
        CloudflareDirectoryClient(bound, CloudflareGateway(HttpTransport(send)), mode)

    @Test fun modernWorkerKeepsImmutableIdAndDisplayNameSeparateWithoutInventedUsageOrTime() = runTest {
        val paths = mutableListOf<String>()
        val page = client { r -> paths += r.path; ok("[${worker()}]") }.resources(account,ResourceKind.WORKER)
        val item=page.items.single()
        assertEquals("immutable-id",item.id);assertEquals("script-name",item.name)
        assertEquals("listWorkers",item.source);assertFalse(item.legacy)
        assertEquals(ResourceRef(account,"worker","immutable-id"),item.resourceRef)
        assertNull(item.deployedOn);assertNull(item.usage)
        assertEquals("Workers",item.detail)
        assertNull(page.total);assertNull(page.next)
        assertEquals(listOf("/accounts/account-one/workers/workers?page=1&per_page=50"),paths)
    }

    @Test fun modernWorkerPreservesProviderDeploymentTime() = runTest {
        val item=client { ok("[${worker(deployed="\"2026-10-03T12:34:56Z\"")}]") }
            .resources(account,ResourceKind.WORKER).items.single()
        assertEquals("2026-10-03T12:34:56Z",item.deployedOn)
    }

    @Test fun workersFetchOneRequestedPageWithoutAutomaticallyReadingTheRest() = runTest {
        val paths=mutableListOf<String>()
        val api=client { r -> paths+=r.path;ok("[${worker()}]","""{"page":2,"per_page":50,"total_count":101,"total_pages":3}""") }
        val page=api.resources(account,ResourceKind.WORKER,PageRequest(token=PageToken.Number(2)))
        assertEquals(listOf("/accounts/account-one/workers/workers?page=2&per_page=50"),paths)
        assertEquals(PageToken.Number(3),page.next);assertEquals(101L,page.total)
    }

    @Test fun modern403NeverFallsBackToScriptsOrInventsAnEmptyDirectory() = runTest {
        val paths=mutableListOf<String>()
        val failure=assertFailsWith<CloudFailure> {
            client { r -> paths+=r.path;HttpResponse(403,"synthetic-provider-private-marker") }
                .resources(account,ResourceKind.WORKER)
        }
        assertEquals(CloudFailureCode.PERMISSION_REQUIRED,failure.code)
        assertFalse(failure.toString().contains("synthetic-provider-private-marker"))
        assertEquals(listOf("/accounts/account-one/workers/workers?page=1&per_page=50"),paths)
    }

    @Test fun explicitLegacyModeIsUnpagedAndMarksTheScriptIdentity() = runTest {
        val paths=mutableListOf<String>()
        val page=client(WorkersDirectoryMode.LEGACY) { r -> paths+=r.path;ok("""[{"id":"script-name","modified_on":"2026-10-02T00:00:00Z"}]""") }
            .resources(account,ResourceKind.WORKER)
        val item=page.items.single()
        assertEquals(listOf("/accounts/account-one/workers/scripts"),paths)
        assertEquals("script-name",item.id);assertEquals("script-name",item.name)
        assertEquals("worker-script-list-workers",item.source);assertTrue(item.legacy)
        assertEquals("Workers · 旧接口",item.detail)
        assertEquals(ResourceRef(account,"worker_script","script-name"),item.resourceRef)
        assertNull(page.next);assertNull(page.total)
    }

    @Test fun legacyModeDoesNotSilentlyIgnoreAContinuationToken() = runTest {
        var sends=0
        val api=client(WorkersDirectoryMode.LEGACY) { sends++;ok("[]") }
        assertEquals(CloudFailureCode.INVALID_INPUT,assertFailsWith<CloudFailure> {
            api.resources(account,ResourceKind.WORKER,PageRequest(token=PageToken.Number(2)))
        }.code)
        assertEquals(0,sends)
    }

    @Test fun r2UsesOnlyCursorAndDecodesNestedBuckets() = runTest {
        val paths=mutableListOf<String>()
        val api=client { r ->
            paths+=r.path
            if ("cursor=" !in r.path)
                ok("""{"buckets":[{"name":"media","creation_date":"2026-10-01T00:00:00Z","location":"apac","jurisdiction":"default","storage_class":"Standard"}]}""",
                    """{"cursor":"next/+==","per_page":50}""")
            else ok("""{"buckets":[]}""","""{"cursor":"","per_page":50}""")
        }
        val first=api.resources(account,ResourceKind.R2)
        assertEquals("media",first.items.single().id)
        assertEquals(ResourceRef(account,"r2_bucket","media"),first.items.single().resourceRef)
        assertEquals("r2-list-buckets",first.items.single().source)
        assertEquals("R2 · apac",first.items.single().detail)
        assertEquals(PageToken.Cursor("next/+=="),first.next);assertNull(first.total)
        val last=api.resources(account,ResourceKind.R2,PageRequest(token=first.next))
        assertTrue(last.items.isEmpty());assertNull(last.next)
        assertEquals(listOf("/accounts/account-one/r2/buckets?per_page=50",
            "/accounts/account-one/r2/buckets?per_page=50&cursor=next%2F%2B%3D%3D"),paths)
    }

    @Test fun r2DoesNotInferMissingLocationFromTheBucketName() = runTest {
        val item=client { ok("""{"buckets":[{"name":"eu-backups"}]}""") }.resources(account,ResourceKind.R2).items.single()
        assertEquals("R2",item.detail);assertNull(item.usage)
    }

    @Test fun aRepeatedCurrentR2CursorFailsInsteadOfReturningAnotherPage() = runTest {
        assertEquals(CloudFailureCode.INVALID_RESPONSE,assertFailsWith<CloudFailure> {
            client { ok("""{"buckets":[]}""","""{"cursor":"repeat"}""") }
                .resources(account,ResourceKind.R2,PageRequest(token=PageToken.Cursor("repeat")))
        }.code)
    }

    @Test fun kvLoadsOnlyNamespacesAndUsesTitleAsName() = runTest {
        val paths=mutableListOf<String>()
        val page=client { r ->paths+=r.path;ok("""[{"id":"namespace-id","title":"session-cache","supports_url_encoding":true,"jurisdiction":"eu"}]""",
            """{"page":1,"per_page":50,"total_count":51}""") }.resources(account,ResourceKind.KV)
        assertEquals(listOf("/accounts/account-one/storage/kv/namespaces?page=1&per_page=50"),paths)
        val item=page.items.single()
        assertEquals("namespace-id",item.id);assertEquals("session-cache",item.name)
        assertEquals(ResourceRef(account,"kv_namespace","namespace-id"),item.resourceRef)
        assertEquals("workers-kv-namespace-list-namespaces",item.source)
        assertEquals(PageToken.Number(2),page.next);assertEquals(51L,page.total)
    }

    @Test fun d1UsesUuidAndItsOwnNumberPaging() = runTest {
        val paths=mutableListOf<String>()
        val page=client { r ->paths+=r.path;ok("""[{"uuid":"db-uuid","name":"app-database","created_at":"2026-10-01T00:00:00Z","version":"production","jurisdiction":null}]""",
            """{"page":1,"per_page":10,"total_count":11}""") }.resources(account,ResourceKind.D1,PageRequest(10))
        assertEquals(listOf("/accounts/account-one/d1/database?page=1&per_page=10"),paths)
        assertEquals("db-uuid",page.items.single().id)
        assertEquals(ResourceRef(account,"d1_database","db-uuid"),page.items.single().resourceRef)
        assertEquals("d1-list-databases",page.items.single().source)
        assertEquals(PageToken.Number(2),page.next);assertEquals(11L,page.total)
    }

    @Test fun zonesAreAccountFilteredAndRejectAnotherAccountsRows() = runTest {
        val paths=mutableListOf<String>()
        val page=client { r ->paths+=r.path;ok("""[{"id":"zone-id","name":"example.test","status":"active","account":{"id":"account-one","name":"Account"}}]""",
            """{"page":1,"per_page":50,"total_count":1}""") }.zones(account)
        assertEquals(listOf("/zones?account.id=account-one&page=1&per_page=50"),paths)
        assertEquals(Domain("zone-id","example.test","active","local"),page.items.single())
        assertEquals(1L,page.total)
        assertEquals(CloudFailureCode.SCOPE_MISMATCH,assertFailsWith<CloudFailure> {
            client { ok("""[{"id":"zone-id","name":"example.test","account":{"id":"other"}}]""") }.zones(account)
        }.code)
    }

    @Test fun missingZoneAccountProofIsRejectedAndMissingStatusStaysUnknown() = runTest {
        assertEquals(CloudFailureCode.INVALID_RESPONSE,assertFailsWith<CloudFailure> {
            client { ok("""[{"id":"z","name":"example.test"}]""") }.zones(account)
        }.code)
        val page=client { ok("""[{"id":"z","name":"example.test","account":{"id":"account-one"}}]""") }.zones(account)
        assertEquals("unknown",page.items.single().status)
    }

    @Test fun foreignConnectionOrAccountIsRejectedBeforeAnyIoForEveryProduct() = runTest {
        var sends=0
        val api=client { sends++;ok("[]") }
        for (foreign in listOf(AccountScope("other","account-one"),AccountScope("local","other"))) {
            assertEquals(CloudFailureCode.SCOPE_MISMATCH,assertFailsWith<CloudFailure>{api.zones(foreign)}.code)
            for (kind in ResourceKind.entries) assertEquals(CloudFailureCode.SCOPE_MISMATCH,
                assertFailsWith<CloudFailure>{api.resources(foreign,kind)}.code)
        }
        assertEquals(0,sends)
    }

    @Test fun identicalNamesAndExternalIdsAcrossAccountsHaveDifferentFullResourceIdentities() = runTest {
        val a=client { ok("[${worker()}]") }.resources(account,ResourceKind.WORKER).items.single()
        val other=Connection("local-two","Other","account-two")
        val otherScope=AccountScope(other.id,other.accountId)
        val b=client(bound=other) { ok("[${worker()}]") }.resources(otherScope,ResourceKind.WORKER).items.single()
        assertEquals(a.id,b.id);assertEquals(a.name,b.name);assertNotEquals(a,b)
        assertEquals(ResourceRef(otherScope,"worker","immutable-id"),b.resourceRef)
    }

    @Test fun invalidProductPagingIsRejectedBeforeAnyRequest() = runTest {
        var sends=0
        val api=client { sends++;ok("[]") }
        for ((kind,request) in listOf(ResourceKind.WORKER to PageRequest(101),ResourceKind.WORKER to PageRequest(token=PageToken.Cursor("x")),
            ResourceKind.R2 to PageRequest(1001),ResourceKind.R2 to PageRequest(token=PageToken.Number(1)),
            ResourceKind.D1 to PageRequest(9),ResourceKind.D1 to PageRequest(10001),ResourceKind.D1 to PageRequest(token=PageToken.Cursor("x")),
            ResourceKind.KV to PageRequest(1001),ResourceKind.KV to PageRequest(token=PageToken.Cursor("x")))) {
            assertEquals(CloudFailureCode.INVALID_INPUT,assertFailsWith<CloudFailure>{api.resources(account,kind,request)}.code)
        }
        for (request in listOf(PageRequest(4),PageRequest(51),PageRequest(token=PageToken.Cursor("x")))) {
            assertEquals(CloudFailureCode.INVALID_INPUT,assertFailsWith<CloudFailure>{api.zones(account,request)}.code)
        }
        assertEquals(0,sends)
    }

    @Test fun malformedProductRowsAreNotConvertedToEmptySuccessOrSyntheticIds() = runTest {
        for ((kind,result) in listOf(ResourceKind.WORKER to "[{\"name\":\"missing-id\"}]",
            ResourceKind.WORKER to "[{\"id\":\"id\",\"name\":42}]",
            ResourceKind.R2 to "[]",ResourceKind.R2 to "{\"buckets\":[null]}",
            ResourceKind.D1 to "[{\"id\":\"wrong-field\",\"name\":\"Db\"}]",
            ResourceKind.KV to "[{\"id\":\"id\",\"name\":\"wrong-field\"}]")) {
            assertEquals(CloudFailureCode.INVALID_RESPONSE,assertFailsWith<CloudFailure> {
                client { ok(result) }.resources(account,kind)
            }.code)
        }
    }

    @Test fun malformedPaginationDoesNotDiscardTheErrorOrInventATotal() = runTest {
        for (info in listOf("null","[]","{\"total_count\":-1}","{\"total_count\":\"10\"}")) {
            assertEquals(CloudFailureCode.INVALID_RESPONSE,assertFailsWith<CloudFailure> {
                client { ok("[${worker()}]",info) }.resources(account,ResourceKind.WORKER)
            }.code)
        }
    }

    @Test fun failuresForAnyProductRemainExplicitAndNeverReturnDemoData() = runTest {
        val paths=mutableListOf<String>()
        val api=client { r ->paths+=r.path;HttpResponse(503,"synthetic-private-response") }
        for (kind in ResourceKind.entries) assertEquals(CloudFailureCode.UNAVAILABLE,
            assertFailsWith<CloudFailure>{api.resources(account,kind)}.code)
        assertEquals(CloudFailureCode.UNAVAILABLE,assertFailsWith<CloudFailure>{api.zones(account)}.code)
        assertEquals(5,paths.size)
        assertEquals(CloudFailureCode.INVALID_RESPONSE,assertFailsWith<CloudFailure> {
            client { HttpResponse(200,"{\"success\":false,\"result\":[]}") }.resources(account,ResourceKind.KV)
        }.code)
    }

    @Test fun transportFailuresAreSanitizedAndCancellationIsNotSwallowed() = runTest {
        val failure=assertFailsWith<CloudFailure> { client { throw IllegalStateException("synthetic-secret-marker") }.zones(account) }
        assertEquals(CloudFailureCode.UNAVAILABLE,failure.code);assertNull(failure.cause)
        assertFalse(failure.toString().contains("synthetic-secret-marker"))
        assertFailsWith<CancellationException> { client { throw CancellationException() }.resources(account,ResourceKind.R2) }
    }

    @Test fun demoIsExplicitlyScopedPagedAndMarkedAsExample() = runTest {
        val demo=Connection("demo","Demo · 演示","demo",true)
        val scope=AccountScope(demo.id,demo.accountId)
        val api=DemoDirectory(demo)
        val first=api.resources(scope,ResourceKind.WORKER,PageRequest(1))
        assertEquals("worker-api",first.items.single().id);assertEquals(2L,first.total)
        assertEquals(PageToken.Number(2),first.next)
        assertEquals("demo",first.items.single().source)
        assertTrue(first.items.single().detail.contains("示例"))
        assertEquals(ResourceRef(scope,"worker","worker-api"),first.items.single().resourceRef)
        val last=api.resources(scope,ResourceKind.WORKER,PageRequest(1,first.next))
        assertEquals("worker-image",last.items.single().id);assertNull(last.next)
        assertEquals(4L,api.zones(scope).total)
        assertTrue(api.resources(scope,ResourceKind.WORKER,PageRequest(Int.MAX_VALUE,PageToken.Number(Int.MAX_VALUE))).items.isEmpty())
    }

    @Test fun demoRefusesRealConnectionsAndForeignScopes() = runTest {
        assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY,assertFailsWith<CloudFailure>{DemoDirectory(connection)}.code)
        val demo=Connection("demo","Demo","demo",true)
        assertEquals(CloudFailureCode.SCOPE_MISMATCH,assertFailsWith<CloudFailure>{DemoDirectory(demo).zones(account)}.code)
    }
}
