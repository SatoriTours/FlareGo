package com.flarego.core

import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.billing.CloudflareBillingClient
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ScopedBillingTest {
    private val connection=Connection("local","Cloudflare","account")
    private val account=AccountScope("local","account")
    private val user=UserScope("local","explicit-user")
    private fun ok(result: String, info: String="{}")=HttpResponse(200,
        """{"success":true,"errors":[],"messages":[],"result":$result,"result_info":$info}""")
    private fun entry(amount: String="20.99", id: String="charge-id")=
        """{"id":"$id","occurred_at":"2026-10-01T12:34:56.1234Z","amount":$amount,"amount_to_pay":0,"currency":"USD","description":"Subscription charge","status":"paid","invoice_id":"invoice-id","receipt_id":"receipt-id","external_invoice_id":"external-id","source_invoice_id":"source-id","hosted_invoice_url":"https://synthetic-untrusted.invalid/invoice","type":"charge","action":"subscription","source":"billing"}"""
    private fun client(owner: UserScope?=null, send: suspend(HttpRequest)->HttpResponse)=
        CloudflareBillingClient(connection,CloudflareGateway(HttpTransport(send)),owner)

    @Test fun accountHistoryPreservesScopeAndAccountSpecificMetadata()=runTest {
        val paths=mutableListOf<String>()
        val page=client { r->paths+=r.path;ok("[${entry()}]","""{"page":1,"per_page":50,"total_count":51}""") }.history(account)
        val row=page.items.single()
        assertEquals(listOf("/accounts/account/billing/history?page=1&per_page=50"),paths)
        assertEquals(account,row.scope);assertEquals(BillingSource.ACCOUNT_HISTORY,row.source)
        assertEquals("charge-id",row.id);assertEquals("20.99",row.amount)
        assertEquals("2026-10-01T12:34:56.1234Z",row.occurredAt)
        assertEquals("USD",row.currency);assertEquals("Subscription charge",row.description)
        assertEquals("paid",row.status);assertEquals("invoice-id",row.invoiceId);assertEquals("receipt-id",row.receiptId)
        assertEquals(PageToken.Number(2),page.next);assertEquals(51L,page.total)
    }

    @Test fun accountLoadsOnlyTheExplicitlyRequestedPage()=runTest {
        val paths=mutableListOf<String>()
        val page=client { r->paths+=r.path;ok("[]","""{"page":3,"per_page":1,"total_count":3}""") }
            .history(account,PageRequest(1,PageToken.Number(3)))
        assertEquals(listOf("/accounts/account/billing/history?page=3&per_page=1"),paths)
        assertNull(page.next);assertEquals(3L,page.total)
    }

    @Test fun amountLexicalTextNeverPassesThroughFloatingPoint()=runTest {
        for(amount in listOf("20.9900","9007199254740993.01","-20.99","0","1.2300e-7")) {
            val row=client { ok("[${entry(amount)}]") }.history(account).items.single()
            assertEquals(amount,row.amount)
        }
    }

    @Test fun missingAmountNeverUsesAmountToPayOrInventsZero()=runTest {
        for(result in listOf("""[{"id":"missing","amount_to_pay":100}]""","""[{"id":"missing","amount":null,"amount_to_pay":0}]""")) {
            val row=client {ok(result)}.history(account).items.single()
            assertNull(row.amount);assertNull(row.currency);assertNull(row.occurredAt)
            assertNull(row.description);assertNull(row.status);assertNull(row.invoiceId);assertNull(row.receiptId)
        }
    }

    @Test fun account403DoesNotReadUserHistoryEvenWhenLegacyOwnerIsConfigured()=runTest {
        val paths=mutableListOf<String>()
        val failure=assertFailsWith<CloudFailure> {
            client(user) {r->paths+=r.path;HttpResponse(403,"synthetic-private-response")}.history(account)
        }
        assertEquals(CloudFailureCode.PERMISSION_REQUIRED,failure.code)
        assertFalse(failure.toString().contains("synthetic-private-response"))
        assertEquals(listOf("/accounts/account/billing/history?page=1&per_page=50"),paths)
    }

    @Test fun explicitlyBoundUserModePreservesUserScopeAndOldSource()=runTest {
        val paths=mutableListOf<String>()
        val legacy="""[{"id":"legacy-charge","occurred_at":"2026-10-01T00:00:00Z","amount":20.99,"currency":"USD","description":"Legacy charge","type":"charge","action":"subscription","zone":{"name":"example.test"}}]"""
        val row=client(user) {r->paths+=r.path;ok(legacy)}.history(user).items.single()
        assertEquals(listOf("/user/billing/history?page=1&per_page=50"),paths)
        assertEquals(user,row.scope);assertEquals(BillingSource.USER_HISTORY_LEGACY,row.source)
        assertNull(row.status);assertNull(row.invoiceId);assertNull(row.receiptId)
        assertEquals("20.99",row.amount)
    }

    @Test fun userModeMustBeExplicitlyEnabledAndMatchItsBoundIdentity()=runTest {
        var sends=0
        val api=client {sends++;ok("[]")}
        assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY,assertFailsWith<CloudFailure>{api.history(user)}.code)
        val explicit=client(user) {sends++;ok("[]")}
        assertEquals(CloudFailureCode.SCOPE_MISMATCH,assertFailsWith<CloudFailure>{explicit.history(UserScope("local","other-user"))}.code)
        assertEquals(0,sends)
    }

    @Test fun foreignAccountOrConnectionIsRejectedBeforeSending()=runTest {
        var sends=0
        val api=client(user) {sends++;ok("[]")}
        for(scope in listOf(AccountScope("other","account"),AccountScope("local","other"),UserScope("other","explicit-user"))) {
            assertEquals(CloudFailureCode.SCOPE_MISMATCH,assertFailsWith<CloudFailure>{api.history(scope)}.code)
        }
        assertEquals(0,sends)
    }

    @Test fun sameEntryIdAcrossScopesHasDifferentIdentity()=runTest {
        val api=client(user) {ok("[${entry()}]")}
        val a=api.history(account).items.single();val b=api.history(user).items.single()
        assertEquals(a.id,b.id);assertNotEquals(a,b);assertEquals(user,b.scope)
    }

    @Test fun unsupportedScopesAndCursorAreRejectedBeforeSending()=runTest {
        var sends=0
        val api=client(user) {sends++;ok("[]")}
        for(scope in listOf(ZoneScope(account,"zone"),OrganizationScope("local","org"))) {
            assertEquals(CloudFailureCode.UNSUPPORTED_CAPABILITY,assertFailsWith<CloudFailure>{api.history(scope)}.code)
        }
        assertEquals(CloudFailureCode.INVALID_INPUT,assertFailsWith<CloudFailure>{api.history(account,PageRequest(token=PageToken.Cursor("cursor")))}.code)
        for(request in listOf(PageRequest(4),PageRequest(51))) {
            assertEquals(CloudFailureCode.INVALID_INPUT,assertFailsWith<CloudFailure>{api.history(user,request)}.code)
        }
        assertEquals(0,sends)
    }

    @Test fun missingTotalsAndEmptyHistoryStayUnknownWithoutExamples()=runTest {
        val page=client {ok("[]")}.history(account)
        assertTrue(page.items.isEmpty());assertNull(page.total);assertNull(page.next)
    }

    @Test fun malformedAmountsAreNotAcceptedAsNumbers()=runTest {
        for(amount in listOf("\"20.99\"","true","{}","[]","\"NaN\"","NaN","Infinity")) {
            assertEquals(CloudFailureCode.INVALID_RESPONSE,assertFailsWith<CloudFailure>{client {ok("[${entry(amount)}]")}.history(account)}.code)
        }
    }

    @Test fun malformedRowsAndWrongOptionalFieldTypesRemainExplicitErrors()=runTest {
        for(result in listOf("{}","null","[null]","[{}]","[{\"id\":42}]","[{\"id\":\"x\",\"currency\":12}]","[{\"id\":\"x\",\"status\":true}]")) {
            assertEquals(CloudFailureCode.INVALID_RESPONSE,assertFailsWith<CloudFailure>{client {ok(result)}.history(account)}.code)
        }
    }

    @Test fun serverFailureAndPartialSuccessNeverReturnInventedInvoices()=runTest {
        var sends=0
        assertEquals(CloudFailureCode.UNAVAILABLE,assertFailsWith<CloudFailure>{client {sends++;HttpResponse(503,"private-body")}.history(account)}.code)
        assertEquals(1,sends)
        assertEquals(CloudFailureCode.INVALID_RESPONSE,assertFailsWith<CloudFailure>{client {HttpResponse(200,"{\"success\":false,\"result\":[]}")}.history(account)}.code)
    }

    @Test fun untrustedHostedInvoiceUrlIsNeverRequestedOrExposedAsARequestTarget()=runTest {
        val paths=mutableListOf<String>()
        val page=client {r->paths+=r.path;ok("[${entry()}]")}.history(account)
        assertEquals("invoice-id",page.items.single().invoiceId)
        assertEquals(listOf("/accounts/account/billing/history?page=1&per_page=50"),paths)
    }

    @Test fun malformedPaginationFailsInsteadOfClaimingACompleteHistory()=runTest {
        for(info in listOf("null","{\"total_count\":\"1\"}","{\"total_count\":-1}")) {
            assertEquals(CloudFailureCode.INVALID_RESPONSE,assertFailsWith<CloudFailure>{client {ok("[]",info)}.history(account)}.code)
        }
    }

    @Test fun transportErrorsAreSanitizedAndCancellationPropagates()=runTest {
        val failure=assertFailsWith<CloudFailure>{client {throw IllegalStateException("synthetic-secret-marker")}.history(account)}
        assertEquals(CloudFailureCode.UNAVAILABLE,failure.code);assertNull(failure.cause)
        assertFalse(failure.toString().contains("synthetic-secret-marker"))
        assertFailsWith<CancellationException>{client {throw CancellationException()}.history(account)}
    }
}
