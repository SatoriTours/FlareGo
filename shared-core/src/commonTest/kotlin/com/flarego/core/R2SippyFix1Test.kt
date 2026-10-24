package com.flarego.core

import com.flarego.core.application.operations.ExecutionReceipt
import com.flarego.core.application.r2.R2SippyAwsSourceSecrets
import com.flarego.core.application.r2.R2SippyDestinationSecrets
import com.flarego.core.application.r2.R2SippyLeaseState
import com.flarego.core.application.r2.R2SippySecrets
import com.flarego.core.application.r2.draftForTest
import com.flarego.core.application.r2.encodeR2SippyEnableBody
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.HttpTransport
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.cloudflare.r2.CloudflareR2SippyClient
import com.flarego.core.model.AccountScope
import com.flarego.core.model.CloudFailure
import com.flarego.core.model.CloudFailureCode
import com.flarego.core.model.Connection
import com.flarego.core.model.R2SippyProvider
import com.flarego.core.model.R2WriteTarget
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 5a fix1: deterministic ownership/reference release (no GC timing), the nullable pinned
 * Sippy GET shape, and sensitive error boundaries. Only synthetic credentials are used and no
 * assertion prints a secret value.
 */
class R2SippyFix1Test {
    private val connection = Connection("r2-local", "fixture", "a".repeat(32))
    private val account = AccountScope(connection.id, connection.accountId)
    private val target = R2WriteTarget(account, "my-bucket")
    private val path = "/accounts/${connection.accountId}/r2/buckets/my-bucket/sippy"
    private val bucketPath = "/accounts/${connection.accountId}/r2/buckets/my-bucket"
    private val secret = "SYNTHETIC-FIX1-SECRET-1"
    private val keyId = "SYNTHETIC-FIX1-KEY-ID-1"
    private val destSecret = "SYNTHETIC-FIX1-DEST-SECRET-1"

    private fun source() = R2SippyAwsSourceSecrets(keyId, secret, "source-bucket", "us-east-1")
    private fun destination() = R2SippyDestinationSecrets("SYNTHETIC-FIX1-DEST-KEY", destSecret)
    private fun draft(id: String = "binding-fix1") = draftForTest(source(), destination()) { id }

    private fun envelope(result: String) = """{"success":true,"errors":[],"messages":[],"result":$result}"""

    private val requests = mutableListOf<HttpRequest>()

    private fun clientFor(respond: suspend (HttpRequest) -> HttpResponse): CloudflareR2SippyClient =
        CloudflareR2SippyClient(
            connection,
            CloudflareGateway(
                HttpTransport { request ->
                    requests += request
                    respond(request)
                },
                sleeper = {},
            ),
        )

    private fun okClient(sippyResult: String): CloudflareR2SippyClient =
        clientFor { request ->
            when (request.path) {
                bucketPath -> HttpResponse(200, envelope("""{"name":"my-bucket"}"""))
                path -> HttpResponse(200, envelope(sippyResult))
                else -> HttpResponse(500, "{}")
            }
        }

    // ------------------------------------------------------------------ F1 material ownership

    @Test
    fun retiringOrConsumingALeaseDropsItsMaterialReferenceDeterministically() {
        val retired = draft("draft-retire")
        assertTrue(retired.retainsMaterial)
        assertTrue(retired.retire())
        assertEquals(R2SippyLeaseState.RETIRED, retired.leaseState)
        assertFalse(retired.retainsMaterial, "a retired draft must not keep the secret material")
        assertNull(retired.admitForTransport())

        val admitted = draft("draft-admit")
        assertTrue(admitted.retainsMaterial)
        val material = assertNotNull(admitted.admitForTransport())
        assertFalse(admitted.retainsMaterial, "admission transfers ownership out of the lease")
        assertFalse(admitted.retire(), "an admitted transport is not closed by the editor")
        admitted.completeTransport()
        assertEquals(R2SippyLeaseState.CONSUMED, admitted.leaseState)
        assertFalse(admitted.retainsMaterial)
        // The transferred material is the same private instance the encoder used.
        assertEquals(R2SippyProvider.AWS, material.source.provider)
    }

    @Test
    fun aRefusedWriteReleasesTheTemporaryBodyAndKeepsAnExplicitLeasePolicy() = runTest {
        // beforeSend failure: nothing sent, the draft is not admitted and still owns its material.
        val beforeSendFailure = okClient("""{"enabled":false}""")
        val draftA = draft("draft-before-send")
        assertFailsWith<IllegalStateException> {
            beforeSendFailure.writeSippy(
                com.flarego.core.application.r2.R2SippyWrite.PutSippy(target, draftA),
            ) { throw IllegalStateException("synthetic product guard") }
        }
        assertTrue(requests.none { it.method == "PUT" })
        assertEquals(R2SippyLeaseState.DRAFT, draftA.leaseState)
        assertTrue(draftA.retainsMaterial)

        // Final-guard failure after admission: nothing sent, the lease is consumed and drops material.
        requests.clear()
        val guardFailure = CloudflareR2SippyClient(
            connection,
            CloudflareGateway(
                HttpTransport { request ->
                    requests += request
                    when (request.path) {
                        bucketPath -> HttpResponse(200, envelope("""{"name":"my-bucket"}"""))
                        else -> HttpResponse(200, envelope("""{"enabled":false}"""))
                    }
                },
                sleeper = {},
            ),
        ) { throw IllegalStateException("synthetic final guard") }
        val draftB = draft("draft-guard")
        assertFailsWith<IllegalStateException> {
            guardFailure.putSippy(target, draftB)
        }
        assertTrue(requests.none { it.method == "PUT" })
        assertEquals(R2SippyLeaseState.CONSUMED, draftB.leaseState)
        assertFalse(draftB.retainsMaterial)
    }

    // ------------------------------------------------------------------ F2 sensitive body holder

    @Test
    fun aConsumedOrDiscardedSensitiveBodyDropsItsTemporaryText() {
        val consumed = HttpBody.SensitiveJson("""{"x":"SYNTHETIC"}""", "application/json; charset=utf-8")
        assertTrue(consumed.retainsContent)
        assertEquals("""{"x":"SYNTHETIC"}""", consumed.consumeBytes().decodeToString())
        assertFalse(consumed.retainsContent, "the holder must not keep the text after a single send")
        assertFailsWith<CloudFailure> { consumed.consumeBytes() }

        val discarded = HttpBody.SensitiveJson("""{"x":"SYNTHETIC"}""", "application/json; charset=utf-8")
        discarded.discard()
        assertFalse(discarded.retainsContent, "a refused request must drop its temporary encoded value")
        assertFailsWith<CloudFailure> { discarded.consumeBytes() }
    }

    // ------------------------------------------------------------------ F3 pinned nullable GET shape

    @Test
    fun aDisabledSippyWithoutPublicDetailsIsProjectedAsDisabledNotAbsent() = runTest {
        val client = okClient("""{"enabled":false}""")
        val snapshot = client.getSippy(target)
        assertFalse(snapshot.enabled)
        assertTrue(snapshot.editable, "a disabled config with no public details can be replaced by an explicit full config")
        assertFalse(snapshot.detailsProvided)
        assertNull(snapshot.destination)
        assertNull(snapshot.source)
        assertNull(snapshot.readOnlyReason)

        // The explicit full PUT is allowed on that shape (bucket proof + final guard still apply).
        val receipt = client.putSippy(target, draft("disabled-shape"))
        assertTrue(receipt is ExecutionReceipt.Completed)
        assertEquals(1, requests.count { it.method == "PUT" })

        // enabled=true without details is not interpretable: read-only, never guessed.
        assertFalse(okClient("""{"enabled":true}""").getSippy(target).editable)

        // A 404 is still not an absence: zero write.
        requests.clear()
        val notFound = clientFor { request ->
            when (request.path) {
                bucketPath -> HttpResponse(200, envelope("""{"name":"my-bucket"}"""))
                else -> HttpResponse(404, envelope("""null"""))
            }
        }
        assertFailsWith<CloudFailure> { notFound.putSippy(target, draft("not-found")) }
        assertTrue(requests.none { it.method == "PUT" })
    }

    @Test
    fun anAwsSourceWithPinnedNullableFieldsStaysReadableAndContradictionsStayReadOnly() = runTest {
        // Pinned r2_sippy.source keeps one uniform nullable set: the non-applicable fields may be
        // present as null for an AWS source.
        val nullableAws =
            """{"enabled":true,"destination":{"accessKeyId":"k","account":"${connection.accountId}","bucket":"my-bucket","provider":"r2"},"source":{"provider":"aws","bucket":"source-bucket","region":null,"bucketUrl":null,"container":null}}"""
        val snapshot = okClient(nullableAws).getSippy(target)
        assertTrue(snapshot.editable)
        assertEquals(R2SippyProvider.AWS, snapshot.source?.provider)
        assertNull(snapshot.source?.region)

        // A non-null provider-specific field from another provider is a contradiction.
        val contradictory =
            """{"enabled":true,"destination":{"accessKeyId":"k","account":"${connection.accountId}","bucket":"my-bucket","provider":"r2"},"source":{"provider":"aws","bucket":"source-bucket","bucketUrl":"https://s3.example.invalid/b"}}"""
        assertFalse(okClient(contradictory).getSippy(target).editable)

        // An unknown member (a potential echo) is still unknown and read-only.
        val echoed =
            """{"enabled":true,"destination":{"accessKeyId":"k","account":"${connection.accountId}","bucket":"my-bucket","provider":"r2"},"source":{"provider":"aws","bucket":"source-bucket","secretAccessKey":"$secret"}}"""
        val echoedSnapshot = okClient(echoed).getSippy(target)
        assertFalse(echoedSnapshot.editable)
        assertFalse(echoedSnapshot.toString().contains(secret))
    }

    // ------------------------------------------------------------------ F4 sensitive error boundary

    @Test
    fun aLowLevelTransportFailureWithAnEchoedSecretIsSanitizedAtTheSippyBoundary() = runTest {
        val echoed = CloudFailure(
            CloudFailureCode.UNKNOWN_RESULT,
            "provider echo $secret",
            uncertain = true,
            requestId = "req-$secret",
        ).apply { initCause(IllegalStateException("cause echo $secret")) }
        val client = clientFor { throw echoed }
        val thrown = assertFailsWith<CloudFailure> { client.putSippy(target, draft("transport-failure")) }
        assertEquals(CloudFailureCode.UNKNOWN_RESULT, thrown.code)
        assertTrue(thrown.uncertain, "a possibly-sent failure keeps its uncertain classification")
        assertFalse(thrown.publicMessage.contains(secret))
        assertFalse(thrown.toString().contains(secret))
        assertFalse(thrown.cause?.toString().orEmpty().contains(secret))
        assertNull(thrown.requestId?.takeIf { it.contains(secret) })

        // The same boundary at the send stage.
        requests.clear()
        val sendFailure = clientFor { request ->
            when (request.path) {
                bucketPath -> HttpResponse(200, envelope("""{"name":"my-bucket"}"""))
                path -> throw CloudFailure(CloudFailureCode.INVALID_RESPONSE, "send echo $secret", uncertain = false)
                else -> HttpResponse(500, "{}")
            }
        }
        val sendThrown = assertFailsWith<CloudFailure> { sendFailure.putSippy(target, draft("send-failure")) }
        assertEquals(CloudFailureCode.INVALID_RESPONSE, sendThrown.code)
        assertFalse(sendThrown.publicMessage.contains(secret))
        assertFalse(sendThrown.cause?.toString().orEmpty().contains(secret))
    }

    @Test
    fun aMalformedSensitiveResponseEchoNeverReachesTheError() = runTest {
        // A malformed body that contains the synthetic secret: the fixed decoder text is used.
        val malformed = okClient("""{"success":true,"result":{"enabled":true,"echo":"$secret"""")
        val failure = assertFailsWith<CloudFailure> { malformed.getSippy(target) }
        assertFalse(failure.publicMessage.contains(secret))
        assertFalse(failure.toString().contains(secret))
        assertFalse(failure.cause?.toString().orEmpty().contains(secret))
    }

    // ------------------------------------------------------------------ binding seam

    @Test
    fun eachDraftGetsAFreshRandomBindingAndTheProductionEntryHasNoIdParameter() {
        val first = R2SippySecrets.draft(source(), destination())
        val second = R2SippySecrets.draft(source(), destination())
        assertNotEquals(first.bindingId, second.bindingId)
        // draftForTest is an internal seam only; the production factory above always randomises.
        assertNotEquals("binding-old", first.bindingId)
        val fixed = draftForTest(source(), destination()) { "binding-test" }
        assertEquals("binding-test", fixed.bindingId)
    }

    @Test
    fun theEncodedBodyIsTheOnlyPlaceTheSecretAppearsAndItIsOneShot() {
        val lease = draft("encode")
        val body = encodeR2SippyEnableBody(lease)
        assertEquals("SensitiveJson(redacted)", body.toString())
        assertTrue(body.retainsContent)
        val parsed = Json.parseToJsonElement(body.consumeBytes().decodeToString()).jsonObject
        assertEquals("r2", parsed.getValue("destination").jsonObject.getValue("provider").jsonPrimitive.content)
        assertTrue(parsed.getValue("destination").jsonObject.getValue("secretAccessKey").jsonPrimitive.content == destSecret)
        assertFalse(body.retainsContent)
        assertFalse(lease.toString().contains(secret))
    }
}
