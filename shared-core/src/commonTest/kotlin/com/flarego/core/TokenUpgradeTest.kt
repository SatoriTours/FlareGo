package com.flarego.core

import com.flarego.core.application.connection.*
import com.flarego.core.cloudflare.*
import com.flarego.core.cloudflare.identity.CloudflareReadAuthorization
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.*
import com.flarego.core.ports.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TokenUpgradeTest {
    private val account = "1".repeat(32)
    private val oldId = "a".repeat(32)
    private val replacement = "synthetic-new-token"
    private val request = ReadTarget(ReadFeature.D1)
    private val workers = ReadTarget(ReadFeature.WORKERS)

    @Test fun temporaryFailureIsNotEvidenceOfMissingPermission() = runTest {
        val unavailable = CloudFailure(CloudFailureCode.UNAVAILABLE, "fixture")
        assertFailsWith<CloudFailure> {
            readUpgradeEvidence(setOf(request), setOf(request, workers)) { throw unavailable }
        }.also { assertEquals(CloudFailureCode.UNAVAILABLE, it.code) }
    }

    @Test fun missingCapabilityIsCombinedWithPreviouslyReadableResources() = runTest {
        val evidence = readUpgradeEvidence(setOf(request), setOf(request, workers)) {
            if (it == request) throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "fixture")
            setOf("worker-1", "worker-2")
        }
        assertEquals(mapOf(request to emptySet(), workers to setOf("worker-1", "worker-2")), evidence)
        assertFailsWith<CloudFailure> {
            confirmReadUpgrade(evidence) { if (it == workers) setOf("worker-1") else emptySet() }
        }.also { assertEquals(CloudFailureCode.SCOPE_MISMATCH, it.code) }
        assertFailsWith<CloudFailure> {
            confirmReadUpgrade(evidence) {
                if (it == request) throw CloudFailure(CloudFailureCode.PERMISSION_REQUIRED, "fixture")
                setOf("worker-1", "worker-2")
            }
        }
    }

    @Test fun candidateMustCoverLaterPagesInTheExactAccount() = runTest {
        val connection = Connection("test", "Fixture", account)
        fun auth(previous: Boolean, complete: Boolean) = CloudflareReadAuthorization(connection,
            CloudflareGateway(HttpTransport { req ->
                assertEquals("GET", req.method)
                val path = req.path.substringBefore('?')
                fun ok(result: String, info: String = "{}") =
                    HttpResponse(200, """{"success":true,"errors":[],"result":$result,"result_info":$info}""")
                when (path) {
                    "/accounts/$account" -> ok("""{"id":"$account","name":"Fixture"}""")
                    "/zones" -> {
                        assertTrue(req.path.contains("account.id=$account"))
                        ok("[]", """{"total_count":0}""")
                    }
                    "/accounts/$account/workers/workers" -> when {
                        !complete -> ok("""[{"id":"w1","name":"First"}]""", """{"total_pages":1,"total_count":1}""")
                        "page=2" in req.path -> ok("""[{"id":"w2","name":"Second"}]""", """{"total_pages":2,"total_count":2}""")
                        else -> ok("""[{"id":"w1","name":"First"}]""", """{"total_pages":2,"total_count":2}""")
                    }
                    "/accounts/$account/d1/database" -> if (previous) HttpResponse(403, "fixture")
                        else ok("[]")
                    "/accounts/$account/workers/scripts", "/accounts/$account/r2/buckets",
                    "/accounts/$account/storage/kv/namespaces", "/accounts/$account/billing/history" -> HttpResponse(403, "fixture")
                    else -> error("Unexpected request outside the read profile: $path")
                }
            }))
        val evidence = auth(previous = true, complete = true).baseline(setOf(request))
        assertEquals(setOf("w1", "w2"), evidence.getValue(workers))
        assertEquals(CloudFailureCode.SCOPE_MISMATCH, assertFailsWith<CloudFailure> {
            auth(previous = false, complete = false).confirm(evidence)
        }.code)
        auth(previous = false, complete = true).confirm(evidence)
    }

    @Test fun moreThanFiftyZonesAreReadAndTheLastZoneMustBeRetained() = runTest {
        val zones = (1..51).map { it.toString(16).padStart(32, '0') }
        fun authorization(restricted: Boolean = false, incomplete: Boolean = false) = CloudflareReadAuthorization(
            Connection("same", "Fixture", account), CloudflareGateway(HttpTransport { req ->
                val path = req.path.substringBefore('?')
                fun ok(result: String, info: String = "{}") =
                    HttpResponse(200, """{"success":true,"errors":[],"result":$result,"result_info":$info}""")
                when (path) {
                    "/zones" -> {
                        assertTrue(req.path.contains("account.id=$account"))
                        val page = if (restricted || incomplete) zones.take(50)
                            else if ("page=2" in req.path) zones.drop(50) else zones.take(50)
                        val rows = page.joinToString(",", "[", "]") {
                            """{"id":"$it","name":"example.test","account":{"id":"$account"}}"""
                        }
                        val info = when {
                            incomplete -> "{}"
                            restricted -> """{"total_pages":1,"total_count":50}"""
                            else -> """{"total_pages":2,"total_count":51}"""
                        }
                        ok(rows, info)
                    }
                    "/accounts/$account" -> ok("""{"id":"$account","name":"Fixture"}""")
                    "/accounts/$account/d1/database" -> if (restricted) ok("[]") else HttpResponse(403, "")
                    else -> HttpResponse(403, "")
                }
            }))
        val evidence = authorization().baseline(setOf(request))
        assertEquals(zones.toSet(), evidence.getValue(ReadTarget(ReadFeature.ZONES)))
        assertEquals(CloudFailureCode.SCOPE_MISMATCH, assertFailsWith<CloudFailure> {
            authorization(restricted = true).confirm(evidence)
        }.code)
        val failure = assertFailsWith<CloudFailure> { authorization(incomplete = true).baseline(setOf(request)) }
        assertTrue(failure.publicMessage.contains("域名分页"))
        assertEquals(CloudFailureCode.CONFLICT, failure.code)
    }

    @Test fun aDeniedSettingMustBeCheckedAtTheExactEndpointNotAnUnrelatedSetting() = runTest {
        val zone = "e".repeat(32)
        val target = ReadTarget(ReadFeature.ZONE_SETTINGS, zone, ZoneSettingKey.SSL)
        val connection = Connection("test", "Fixture", account)
        var probe = false
        val api = CloudflareReadAuthorization(connection, CloudflareGateway(HttpTransport { req ->
            val path = req.path.substringBefore('?')
            fun ok(result: String, info: String = "{}") =
                HttpResponse(200, """{"success":true,"errors":[],"result":$result,"result_info":$info}""")
            val zoneRow = """{"id":"$zone","name":"example.test","status":"active","paused":false,"type":"full","account":{"id":"$account"}}"""
            when (path) {
                "/accounts/$account" -> ok("""{"id":"$account","name":"Fixture"}""")
                "/zones" -> ok("[$zoneRow]", """{"total_count":1}""")
                "/zones/$zone" -> ok(zoneRow)
                "/zones/$zone/settings/ssl" -> {
                    probe = true
                    HttpResponse(403, "")
                }
                "/zones/$zone/settings/always_use_https" -> ok("""{"id":"always_use_https","value":"on","editable":false}""")
                else -> HttpResponse(403, "")
            }
        }))
        val evidence = api.baseline(setOf(target))
        assertTrue(probe)
        assertEquals(emptySet(), evidence.getValue(target))
        probe = false
        assertFailsWith<CloudFailure> { api.confirm(evidence) }
        assertTrue(probe)
    }

    @Test fun cancellationDuringVerificationDoesNotSaveLateCandidate() = runTest {
        val started = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        var saves = 0
        val ctl = TokenConnectionController(this, { error("not discovery") }, { _, _, _ -> error("not discovery") })
        ctl.startUpgrade {
            TokenUpgradeAttempt(TokenUpgradePlan(account, "Fixture", setOf(ReadFeature.D1), null)) {
                started.complete(Unit)
                hold.await()
                suspend {
                    saves++
                    TokenUpgradeResult(CredentialSaveOutcome.SAVED)
                }
            }
        }
        runCurrent()
        ctl.submit(replacement)
        runCurrent()
        assertTrue(started.isCompleted)
        ctl.cancel()
        hold.complete(Unit)
        runCurrent()
        assertEquals(0, saves)
        assertFalse(ctl.status.value.tokenHeld)
        assertFalse(ctl.status.value.connected)
    }

    @Test fun onlyConfirmedSavePublishesARevocationIdentity() = runTest {
        for (result in CredentialSaveOutcome.entries) {
            val ctl = TokenConnectionController(this, { error("not discovery") }, { _, _, _ -> error("not discovery") })
            ctl.startUpgrade {
                TokenUpgradeAttempt(TokenUpgradePlan(account, "Fixture", setOf(ReadFeature.D1), null)) {
                    suspend { TokenUpgradeResult(result, RetiredToken(oldId, TokenKind.USER_OWNED)) }
                }
            }
            runCurrent()
            ctl.submit(replacement)
            runCurrent()
            val state = ctl.status.value
            assertEquals(result == CredentialSaveOutcome.SAVED, state.connected)
            assertEquals(oldId.takeIf { state.connected }, state.retiredToken?.id)
            assertFalse(state.toString().contains(replacement))
            if (result == CredentialSaveOutcome.STATE_UNCERTAIN) assertFalse(state.tokenHeld)
        }
    }

    @Test fun sameSecretOrProviderIdentityNeverCountsAsReplacement() {
        val old = RetiredToken(oldId, TokenKind.USER_OWNED)
        assertFailsWith<CloudFailure> { requireDifferentToken(replacement, replacement, old, null) }
        assertFailsWith<CloudFailure> { requireDifferentToken("synthetic-old-token", replacement, old, oldId.uppercase()) }
        requireDifferentToken("synthetic-old-token", replacement, old, "b".repeat(32))
    }
}
