package com.flarego.core

import com.flarego.core.application.operations.OperationClock
import com.flarego.core.application.operations.OperationIds
import com.flarego.core.application.r2.R2Command
import com.flarego.core.application.r2.R2LiveSelection
import com.flarego.core.application.r2.R2SippyAwsSourceSecrets
import com.flarego.core.application.r2.R2SippyDestinationSecrets
import com.flarego.core.application.r2.R2SippyLeaseState
import com.flarego.core.application.r2.R2SippySecretLease
import com.flarego.core.application.r2.R2SippySecrets
import com.flarego.core.application.r2.R2SippyWrite
import com.flarego.core.application.r2.R2WriteRuntime
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.HttpTransport
import com.flarego.core.cloudflare.identity.AuthorizedR2SippySession
import com.flarego.core.cloudflare.identity.R2TokenAuthorityIssuer
import com.flarego.core.cloudflare.protocol.CloudflareGateway
import com.flarego.core.model.AccountScope
import com.flarego.core.model.Connection
import com.flarego.core.model.Operation
import com.flarego.core.model.OperationTask
import com.flarego.core.model.R2WriteTarget
import com.flarego.core.model.TaskState
import com.flarego.core.ports.FingerprintPort
import com.flarego.core.ports.OperationStore
import com.flarego.core.ports.TokenKind
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 5b fix1: the real secret-ownership chain, without a fake session.
 *
 * The test drives the production `R2WriteRuntime` + `R2SippyController` + `AuthorizedR2SippySession`
 * + `CloudflareR2SippyClient` over a synthetic HTTP transport. Only the provider is simulated, so
 * any assertion here is about the real claim/admission order rather than a test-local re-enactment.
 *
 * Nothing prints or compares a credential value: ownership is observed through the deterministic
 * lease state and the material-reference flags, which never expose the secret.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R2SippyCrossLayerOwnershipTest {
    private val connection = Connection("r2-local", "fixture", "a".repeat(32))
    private val account = AccountScope(connection.id, connection.accountId)
    private val target = R2WriteTarget(account, "my-bucket")
    private val accountResource = "com.cloudflare.api.account.${connection.accountId}"
    private val accountScope = "com.cloudflare.api.account"
    private val sourceSecret = "SYNTHETIC-CROSSLAYER-SOURCE-SECRET-4b1"
    private val destinationSecret = "SYNTHETIC-CROSSLAYER-DEST-SECRET-8e2"

    private class Store : OperationStore {
        val rows = mutableMapOf<String, OperationTask>()
        override fun get(id: String) = rows[id]
        override fun list(connectionId: String) = rows.values.filter { it.connectionId == connectionId }
        override fun put(task: OperationTask) { rows[task.id] = task }
        override fun putWithSummary(task: OperationTask, summary: Operation) = put(task)
        override fun removeConnection(connectionId: String) {
            rows.entries.removeAll { it.value.connectionId == connectionId }
        }
    }

    private fun envelope(result: JsonElement) = HttpResponse(200, buildJsonObject {
        put("success", true); put("errors", JsonArray(emptyList())); put("messages", JsonArray(emptyList()))
        put("result", result)
    }.toString())

    private fun draft() = R2SippySecrets.draft(
        R2SippyAwsSourceSecrets("SYNTHETIC-CROSSLAYER-SOURCE-KEY", sourceSecret, "source-bucket", "us-east-1"),
        R2SippyDestinationSecrets("SYNTHETIC-CROSSLAYER-DEST-KEY", destinationSecret),
    )

    private inner class Harness(scope: TestScope) {
        // The runtime credential revision and the issuer's own revision must agree in this harness.
        var actual = R2LiveSelection(connection, 7, 1, null)
        var revision = 7L
        var now = 1_000L
        var ids = 0
        val store = Store()

        /** Set while a Sippy `PUT` was actually sent; the body is consumed once, never printed. */
        var puts = 0
        var putBodyBytes = 0
        var gets = 0
        var enabled = false
        private var gateArmed = false
        private var readGate: CompletableDeferred<Unit>? = null

        /** Suspends the next Sippy configuration read (after the app task claim). */
        fun suspendNextSippyRead(gate: CompletableDeferred<Unit>) {
            readGate = gate
            gateArmed = true
        }

        private fun sippyJson(): String =
            if (!enabled) """{"enabled":false}"""
            else """{"enabled":true,"destination":{"accessKeyId":"r2-echo-key-id","account":"${connection.accountId}","bucket":"my-bucket","provider":"r2"},"source":{"provider":"aws","bucket":"source-bucket","region":"us-east-1"}}"""

        private fun catalogRow(id: String, name: String, scope: String) = buildJsonObject {
            put("id", id); put("name", name); put("scopes", JsonArray(listOf(JsonPrimitive(scope))))
        }

        val issuer = R2TokenAuthorityIssuer(connection, 7, { revision }, OperationClock { now }, TokenKind.USER_OWNED)
        val gateway = CloudflareGateway(HttpTransport { request ->
            when {
                request.path == "/user/tokens/verify" ->
                    envelope(buildJsonObject { put("id", "self-token"); put("status", "active") })
                request.path.endsWith("/permission_groups") ->
                    envelope(JsonArray(listOf(
                        catalogRow("admin", "Workers R2 Storage Write", accountScope),
                        catalogRow("reader", "Workers R2 Storage Read", accountScope),
                        catalogRow("item", "Workers R2 Storage Bucket Item Write", "com.cloudflare.edge.r2.bucket"),
                    )))
                request.path.endsWith("/self-token") -> envelope(buildJsonObject {
                    put("id", "self-token"); put("status", "active"); put("name", "synthetic")
                    put("policies", JsonArray(listOf(buildJsonObject {
                        put("id", "p-allow"); put("effect", "allow")
                        put("permission_groups", JsonArray(listOf(buildJsonObject { put("id", "admin") })))
                        put("resources", buildJsonObject { put(accountResource, JsonPrimitive("*")) })
                    })))
                })
                request.path == "/accounts/${connection.accountId}" ->
                    envelope(buildJsonObject { put("id", connection.accountId) })
                request.path.endsWith("/r2/buckets/my-bucket") ->
                    envelope(buildJsonObject { put("name", "my-bucket") })
                request.path.endsWith("/sippy") && request.method == "GET" -> {
                    gets++
                    if (gateArmed) { gateArmed = false; readGate!!.await() }
                    envelope(kotlinx.serialization.json.Json.parseToJsonElement(sippyJson()))
                }
                request.path.endsWith("/sippy") && request.method == "PUT" -> {
                    val payload = request.payload
                    assertTrue(payload is HttpBody.SensitiveJson, "the sensitive body reaches the transport")
                    putBodyBytes += payload.consumeBytes().size
                    puts++
                    enabled = true
                    envelope(buildJsonObject { put("ok", true) })
                }
                request.path.endsWith("/sippy") && request.method == "DELETE" -> {
                    enabled = false
                    envelope(buildJsonObject { put("ok", true) })
                }
                else -> error("unexpected synthetic request ${request.method} ${request.path}")
            }
        }, sleeper = {})
        val authorized = AuthorizedR2SippySession(connection, gateway, issuer)
        val runtime = R2WriteRuntime(
            scope.backgroundScope, store, StandardTestDispatcher(scope.testScheduler),
            FingerprintPort { bytes ->
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            },
            OperationClock { now }, OperationIds { "runtime-${++ids}" },
            { actual }, { listOf(connection) }, { revision }, { error("no batch A session in this test") },
            sippySession = { authorized },
        )

        fun select(command: R2Command) { actual = actual.copy(command = command) }

        fun put(lease: R2SippySecretLease) = R2Command.Sippy(R2SippyWrite.PutSippy(target, lease))
    }

    @Test
    fun theTaskClaimMovesTheMaterialAwayFromTheEditorDraft() {
        val draft = draft()
        assertTrue(draft.retainsMaterial)
        assertEquals(R2SippyLeaseState.DRAFT, draft.leaseState)

        val claimed = assertNotNull(draft.claimForTask())
        // The editor draft is spent: it can neither release nor admit what the task now owns.
        assertEquals(R2SippyLeaseState.CLAIMED, draft.leaseState)
        assertFalse(draft.retainsMaterial)
        assertFalse(draft.retire())
        assertNull(draft.claimForTask())
        assertNull(draft.admitForTransport())

        // The task-owned lease keeps the same binding (so the plan fingerprint stays valid) and is
        // the only holder of the material; it admits and consumes exactly once.
        assertEquals(draft.bindingId, claimed.bindingId)
        assertEquals(R2SippyLeaseState.DRAFT, claimed.leaseState)
        assertTrue(claimed.retainsMaterial)
        assertTrue(claimed.retire())
        assertFalse(claimed.retainsMaterial)
        assertNull(claimed.admitForTransport())
    }

    @Test
    fun aClaimedTaskKeepsItsMaterialWhileTheDraftUiRetiresDuringTheConfigurationRead() = runTest {
        val h = Harness(this)
        val draft = draft()
        val command = h.put(draft)
        h.select(command)
        val preview = h.runtime.prepare(command)
        assertEquals(0, h.puts)

        // The app-owned task claim happens before the authority/configuration reads; the read is held
        // open so the UI can try to retire its own (already spent) draft in that window.
        val gate = CompletableDeferred<Unit>()
        h.suspendNextSippyRead(gate)
        val waiter = launch { h.runtime.confirm(preview.planId) }
        runCurrent()
        assertEquals(0, h.puts, "the configuration read is still in flight")
        assertEquals(R2SippyLeaseState.CLAIMED, draft.leaseState, "the task already owns the material")
        assertFalse(draft.retainsMaterial)
        assertFalse(draft.retire(), "an editor retirement can never take a claimed task's material")

        // The UI waiter disappears and the preview is discarded; neither may close the task.
        waiter.cancel()
        runCurrent()
        h.runtime.discardPreview(preview.planId)

        gate.complete(Unit)
        runCurrent()
        assertEquals(1, h.puts, "exactly one PUT after the claim")
        assertTrue(h.putBodyBytes > 0, "the claimed material reached the transport")
        val task = h.store.rows.values.single()
        assertEquals(TaskState.SUCCEEDED, task.state)
        assertEquals(R2SippyLeaseState.CLAIMED, draft.leaseState)

        // A duplicate confirmation shares the cached task: no second consumption and no second PUT.
        assertEquals(task.id, h.runtime.confirm(preview.planId).id)
        runCurrent()
        assertEquals(1, h.puts)
        assertEquals(1, h.store.rows.size)
    }

    @Test
    fun aGenuineRouteRevokeInThatSameWindowStillSendsNothing() = runTest {
        val h = Harness(this)
        val draft = draft()
        val command = h.put(draft)
        h.select(command)
        val preview = h.runtime.prepare(command)

        val gate = CompletableDeferred<Unit>()
        h.suspendNextSippyRead(gate)
        val waiter = launch { h.runtime.confirm(preview.planId) }
        runCurrent()
        assertEquals(R2SippyLeaseState.CLAIMED, draft.leaseState)
        // The route really changed while the app task owns the material: the final guard must still
        // refuse. Keeping the material is never a way around the proof/current-command/epoch checks.
        h.actual = h.actual.copy(routeEpoch = 2)
        waiter.cancel()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(0, h.puts, "a revoked route leaves zero sends")
        val task = h.store.rows.values.single()
        assertEquals(TaskState.FAILED, task.state, "a pre-send refusal is a trusted not-sent outcome")
        assertEquals(R2SippyLeaseState.CLAIMED, draft.leaseState)
        // The refused draft cannot be reused for a new preview either.
        h.select(h.put(draft))
        assertFailsWith<com.flarego.core.model.CloudFailure> { h.runtime.prepare(h.put(draft)) }
        assertEquals(0, h.puts)
    }

    @Test
    fun theClaimedMaterialIsReleasedWhenTheGuardRefusesBeforeTheSend() = runTest {
        val h = Harness(this)
        val draft = draft()
        val command = h.put(draft)
        h.select(command)
        val preview = h.runtime.prepare(command)

        val gate = CompletableDeferred<Unit>()
        h.suspendNextSippyRead(gate)
        val waiter = launch { h.runtime.confirm(preview.planId) }
        runCurrent()
        // Credential revocation in the same window: still zero sends.
        h.revision = -1
        waiter.cancel()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(0, h.puts)
        assertEquals(R2SippyLeaseState.CLAIMED, draft.leaseState)
        assertFalse(draft.retainsMaterial)
    }
}
