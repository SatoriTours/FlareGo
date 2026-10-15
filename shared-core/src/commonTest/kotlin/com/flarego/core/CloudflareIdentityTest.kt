package com.flarego.core

import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.identity.*
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CloudflareIdentityTest {
    private fun ok(result: String, info: String = "{}") =
        HttpResponse(200, """{"success":true,"result":$result,"result_info":$info}""")
    private fun client(send: suspend (HttpRequest) -> HttpResponse) =
        CloudflareIdentityClient(CloudflareGateway(HttpTransport(send)))

    @Test fun discoveryReadsOnlyTheRequestedPageAndKeepsMetadata() = runTest {
        val paths = mutableListOf<String>()
        val api = client { r ->
            paths += r.path
            ok(if ("page=2" in r.path) """[{"id":"b","name":"Second","type":"standard"}]"""
                else """[{"id":"a","name":"First","type":"standard"}]""",
                """{"total_pages":2,"total_count":2}""")
        }
        val first = api.discover(PageRequest())
        assertEquals(listOf(AccountSummary("a", "First")), first.items)
        assertEquals(PageToken.Number(2), first.next)
        assertEquals(2L, first.total)
        assertEquals(listOf("/accounts?page=1&per_page=50"), paths)
        val second = api.discover(PageRequest(token = first.next))
        assertEquals(listOf(AccountSummary("b", "Second")), second.items)
        assertNull(second.next)
        assertEquals("/accounts?page=2&per_page=50", paths.last())
    }

    @Test fun discoveryDeniedDoesNotPreventManualAccountVerification() = runTest {
        val paths = mutableListOf<String>()
        val api = client { r ->
            paths += r.path
            if (r.path.startsWith("/accounts?")) HttpResponse(403, "provider-private-marker")
            else ok("""{"id":"manual","name":"Manual","type":"standard"}""")
        }
        assertEquals(CloudFailureCode.PERMISSION_REQUIRED, assertFailsWith<CloudFailure> { api.discover() }.code)
        assertEquals(AccountSummary("manual", "Manual"), api.verifyAccount("manual"))
        assertEquals(listOf("/accounts?page=1&per_page=50", "/accounts/manual"), paths)
    }

    @Test fun userTokenUsesOnlyUserVerification() = runTest {
        val paths = mutableListOf<String>()
        val api = client { r -> paths += r.path; ok("""{"id":"token-id","status":"active"}""") }
        assertEquals(true, api.verifyToken(TokenKind.USER_OWNED, "ignored-account").active)
        assertEquals(listOf("/user/tokens/verify"), paths)
    }

    @Test fun accountTokenUsesOnlyItsExplicitAccountVerification() = runTest {
        val paths = mutableListOf<String>()
        val api = client { r -> paths += r.path; ok("""{"id":"token-id","status":"active"}""") }
        val result = api.verifyToken(TokenKind.ACCOUNT_OWNED, "account-id")
        assertEquals(TokenKind.ACCOUNT_OWNED, result.kind)
        assertEquals(true, result.active)
        assertEquals(listOf("/accounts/account-id/tokens/verify"), paths)
    }

    @Test fun accountTokenRequiresAnAccountBeforeSending() = runTest {
        var sends = 0
        val api = client { sends++; ok("{}") }
        for (account in listOf(null, "", " ")) {
            assertEquals(CloudFailureCode.INVALID_INPUT,
                assertFailsWith<CloudFailure> { api.verifyToken(TokenKind.ACCOUNT_OWNED, account) }.code)
        }
        assertEquals(0, sends)
    }

    @Test fun unknownTokenKindDoesNotGuessVerificationOrPermissions() = runTest {
        var sends = 0
        val result = client { sends++; ok("{}") }.verifyToken(TokenKind.UNKNOWN)
        assertEquals(TokenKind.UNKNOWN, result.kind)
        assertNull(result.active)
        assertNotNull(result.notice)
        assertEquals(0, sends)
    }

    @Test fun tokenVerificationNeverSubstitutesForAccountAccess() = runTest {
        val paths = mutableListOf<String>()
        val api = client { r ->
            paths += r.path
            if (r.path=="/user/tokens/verify") ok("""{"id":"token-id","status":"active"}""")
            else HttpResponse(403, "private-provider-body")
        }
        assertEquals(true, api.verifyToken(TokenKind.USER_OWNED).active)
        assertEquals(CloudFailureCode.PERMISSION_REQUIRED,
            assertFailsWith<CloudFailure> { api.verifyAccount("denied") }.code)
        assertEquals(listOf("/user/tokens/verify", "/accounts/denied"), paths)
    }

    @Test fun nonActiveAndFutureTokenStatesAreConservative() = runTest {
        for ((status, want) in listOf("disabled" to false, "expired" to false, "future-state" to null)) {
            val result = client { ok("""{"id":"token-id","status":"$status"}""") }.verifyToken(TokenKind.USER_OWNED)
            assertEquals(want, result.active)
            assertNotNull(result.notice)
        }
    }

    @Test fun malformedIdentityAndPartialEnvelopesNeverBecomeSuccess() = runTest {
        for (body in listOf(
            """{"success":true,"result":{}}""",
            """{"success":true,"result":{"id":"another","name":"Other","type":"standard"}}""",
            """{"success":false,"result":{"id":"expected","name":"Expected","type":"standard"}}""",
            """{"success":true,"result":{"id":"expected","name":42}}""",
        )) {
            val failure = assertFailsWith<CloudFailure> { client { HttpResponse(200, body) }.verifyAccount("expected") }
            assertEquals(CloudFailureCode.INVALID_RESPONSE, failure.code)
            assertFalse(failure.message.orEmpty().contains("another"))
        }
    }

    @Test fun unsupportedPaginationIsRejectedWithoutRequests() = runTest {
        var sends=0
        val api=client { sends++; ok("[]") }
        for (request in listOf(PageRequest(4),PageRequest(51),PageRequest(token=PageToken.Cursor("cursor")))) {
            assertEquals(CloudFailureCode.INVALID_INPUT, assertFailsWith<CloudFailure> { api.discover(request) }.code)
        }
        assertEquals(0,sends)
    }

    @Test fun discoveryMissingTotalsStaysUnknownAndBadRowsFail() = runTest {
        val empty = client { ok("[]") }.discover()
        assertNull(empty.total)
        assertNull(empty.next)
        for (result in listOf("{}", "[null]", """[{"id":"x"}]""", """[{"id":"","name":"X"}]""")) {
            assertEquals(CloudFailureCode.INVALID_RESPONSE, assertFailsWith<CloudFailure> { client { ok(result) }.discover() }.code)
        }
    }

    @Test fun transportFailuresAreSanitizedAndCancellationPropagates() = runTest {
        val failure = assertFailsWith<CloudFailure> { client { throw IllegalStateException("synthetic-secret-marker") }.discover() }
        assertEquals(CloudFailureCode.UNAVAILABLE,failure.code)
        assertFalse(failure.toString().contains("synthetic-secret-marker"))
        assertNull(failure.cause)
        assertFailsWith<CancellationException> { client { throw CancellationException() }.discover() }
    }

    private val connection=Connection("local", "Name", "account")
    private val scope=AccountScope("local", "account")
    private val details="accounts-account-details"
    private fun capabilities(now: () -> Long = { 1_000 }, send: suspend (HttpRequest) -> HttpResponse) =
        CloudflareCapabilityClient(connection,CloudflareGateway(HttpTransport(send)),now)

    @Test fun capability403MeansPermissionRequiredWithoutPlanGuessing() = runTest {
        val result=capabilities { HttpResponse(403,"private-response") }.status(scope,details)
        assertEquals(CapabilityAvailability.PERMISSION_REQUIRED,result.availability)
        assertEquals(scope,result.scope)
        assertEquals(1_000L,result.observedAtMs)
        assertFalse(result.reason.orEmpty().contains("private-response"))
    }

    @Test fun capabilityCacheExpiresIndependentlyAndIsCredentialClientLocal() = runTest {
        var now=1_000L
        val paths=mutableListOf<String>()
        val api=capabilities({now}) { r -> paths+=r.path; ok("{}") }
        assertEquals(CapabilityAvailability.AVAILABLE,api.status(scope,details).availability)
        now=2_000
        assertEquals(1_000L,api.status(scope,details).observedAtMs)
        assertEquals(1,paths.size)
        now=61_000
        assertEquals(61_000L,api.status(scope,details).observedAtMs)
        assertEquals(2,paths.size)
        val changedCredential=capabilities({now}) { HttpResponse(403,"") }
        assertEquals(CapabilityAvailability.PERMISSION_REQUIRED,changedCredential.status(scope,details).availability)
        assertEquals(listOf("/accounts/account","/accounts/account"),paths)
    }

    @Test fun capabilitiesNeverProbeWritesOrUnknownActions() = runTest {
        var sends=0
        val api=capabilities { sends++; ok("{}") }
        for (key in listOf("dns-records-for-a-zone-create-dns-record","unknown-action")) {
            assertEquals(CapabilityAvailability.UNKNOWN,api.status(scope,key).availability)
        }
        assertEquals(0,sends)
    }

    @Test fun capabilityScopeMismatchSendsNothing() = runTest {
        var sends=0
        val api=capabilities { sends++; ok("{}") }
        for (other in listOf(AccountScope("other","account"),AccountScope("local","other"))) {
            assertEquals(CloudFailureCode.SCOPE_MISMATCH, assertFailsWith<CloudFailure> { api.status(other,details) }.code)
        }
        assertEquals(0,sends)
    }

    @Test fun capabilityCachesActionsSeparatelyAndNeverProbesAnotherScopeKind() = runTest {
        val paths=mutableListOf<String>()
        val api=capabilities { r -> paths+=r.path;ok("[]") }
        api.status(scope,details)
        api.status(scope,"listWorkers")
        assertEquals(listOf("/accounts/account","/accounts/account/workers/workers?page=1&per_page=1"),paths)
        assertEquals(CapabilityAvailability.UNKNOWN,api.status(UserScope("local","user"),details).availability)
        assertEquals(2,paths.size)
    }

    @Test fun capabilityFailuresAndPartialSuccessDoNotBecomeAvailable() = runTest {
        val credential=capabilities { HttpResponse(401,"") }.status(scope,details)
        assertEquals(CapabilityAvailability.CREDENTIAL_REQUIRED,credential.availability)
        val partial=capabilities { HttpResponse(200,"""{"success":false,"result":{}}""") }.status(scope,details)
        assertEquals(CapabilityAvailability.TEMPORARILY_UNAVAILABLE,partial.availability)
        val absent=capabilities { HttpResponse(404,"") }.status(scope,details)
        assertEquals(CapabilityAvailability.UNKNOWN,absent.availability)
    }

    @Test fun backwardsClockDoesNotExtendAnOldCapabilityCache() = runTest {
        var now=1_000L;var sends=0
        val api=capabilities({now}) { sends++;ok("{}") }
        api.status(scope,details);now=999;api.status(scope,details)
        assertEquals(2,sends)
    }
}
