package com.flarego.ui.r2

import com.flarego.core.application.operations.OperationClock
import com.flarego.core.application.operations.OperationIds
import com.flarego.core.application.r2.R2Command
import com.flarego.core.application.r2.R2LiveSelection
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
import com.flarego.core.model.TaskState
import com.flarego.core.ports.OperationStore
import com.flarego.core.ports.TokenKind
import com.flarego.core.ports.FingerprintPort
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 5c: the Sippy family on the **real root route**.
 *
 * The test drives `R2Browser` + `R2ConfigurationBrowser` + `R2WriteRuntime` + `R2SippyController` +
 * `AuthorizedR2SippySession` + `CloudflareR2SippyClient` over a synthetic transport. Only the
 * provider is simulated, so the preview/confirm/settlement path asserted here is the production one
 * and not an `emitIntent` re-enactment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R2SippyBrowserTest {
    private val connection = Connection("r2-local", "fixture", "a".repeat(32))
    private val account = AccountScope(connection.id, connection.accountId)
    private val bucket = "my-bucket"
    private val accountResource = "com.cloudflare.api.account.${connection.accountId}"
    private val accountScope = "com.cloudflare.api.account"
    private val secretMarker = "SYNTHETIC-BROWSER-SECRET-MARKER-6a1"

    private val requests = mutableListOf<HttpRequest>()
    private var enabled = false
    /** False models a provider acknowledgement whose configuration did not actually change. */
    private var applyPut = true
    private var puts = 0
    private var deletes = 0

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

    private fun envelope(result: JsonElement) =
        HttpResponse(200, buildJsonObject {
            put("success", true)
            put("errors", JsonArray(emptyList()))
            put("messages", JsonArray(emptyList()))
            put("result", result)
        }.toString())

    private fun sippyJson(): String =
        if (!enabled) """{"enabled":false}"""
        else """{"enabled":true,"destination":{"accessKeyId":"r2-echo-key","account":"${connection.accountId}","bucket":"$bucket","provider":"r2"},"source":{"provider":"aws","bucket":"source-bucket","region":"us-east-1"}}"""

    private fun catalogRow(id: String, name: String, scope: String) = buildJsonObject {
        put("id", id); put("name", name); put("scopes", JsonArray(listOf(JsonPrimitive(scope))))
    }

    private inner class Harness(
        scope: TestScope,
        wired: Boolean = true,
        secureGrant: Boolean = true,
    ) {
        var live: R2BrowserContext? = R2BrowserContext(connection, 7, 1)
        var issuerRevision = 7L
        var now = 1_000L
        var ids = 0
        val store = Store()
        val secure = FakeSecureWindowPort(grant = secureGrant)
        private var browserRef: R2Browser? = null

        private fun transport(request: HttpRequest): HttpResponse {
            requests += request
            return when {
                request.path == "/user/tokens/verify" ->
                    envelope(buildJsonObject { put("id", "self-token"); put("status", "active") })
                request.path.endsWith("/permission_groups") ->
                    envelope(
                        JsonArray(
                            listOf(
                                catalogRow("admin", "Workers R2 Storage Write", accountScope),
                                catalogRow("reader", "Workers R2 Storage Read", accountScope),
                                catalogRow("item", "Workers R2 Storage Bucket Item Write", "com.cloudflare.edge.r2.bucket"),
                            ),
                        ),
                    )
                request.path.endsWith("/self-token") -> envelope(buildJsonObject {
                    put("id", "self-token"); put("status", "active"); put("name", "synthetic")
                    put(
                        "policies",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("id", "p-allow"); put("effect", "allow")
                                    put("permission_groups", JsonArray(listOf(buildJsonObject { put("id", "admin") })))
                                    put("resources", buildJsonObject { put(accountResource, JsonPrimitive("*")) })
                                },
                            ),
                        ),
                    )
                })
                request.path == "/accounts/${connection.accountId}" ->
                    envelope(buildJsonObject { put("id", connection.accountId) })
                request.path.endsWith("/r2/buckets/$bucket") ->
                    envelope(buildJsonObject { put("name", bucket) })
                request.path.endsWith("/sippy") && request.method == "GET" ->
                    envelope(Json.parseToJsonElement(sippyJson()))
                request.path.endsWith("/sippy") && request.method == "PUT" -> {
                    val payload = request.payload
                    assertTrue(payload is HttpBody.SensitiveJson, "the sensitive body reaches the transport")
                    payload.consumeBytes()
                    puts++
                    if (applyPut) enabled = true
                    envelope(buildJsonObject { put("ok", true) })
                }
                request.path.endsWith("/sippy") && request.method == "DELETE" -> {
                    deletes++
                    enabled = false
                    envelope(buildJsonObject { put("ok", true) })
                }
                // The bucket browser is not exercised here.
                request.path.contains("/buckets") -> envelope(JsonArray(emptyList()))
                else -> error("unexpected synthetic request ${request.method} ${request.path}")
            }
        }

        val issuer = R2TokenAuthorityIssuer(connection, 7, { issuerRevision }, OperationClock { now }, TokenKind.USER_OWNED)
        val gateway = CloudflareGateway(HttpTransport { request -> transport(request) }, sleeper = {})
        val authorized = AuthorizedR2SippySession(connection, gateway, issuer)
        val runtime = R2WriteRuntime(
            scope.backgroundScope, store, StandardTestDispatcher(scope.testScheduler),
            FingerprintPort { bytes ->
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            },
            OperationClock { now }, OperationIds { "runtime-${++ids}" },
            actual = {
                live?.let { context ->
                    R2LiveSelection(context.connection, context.credentialRevision, context.routeEpoch,
                        browserRef?.liveCommand())
                }
            },
            connections = { listOf(connection) },
            revision = { issuerRevision },
            session = { error("no batch A session in this test") },
            sippySession = if (wired) ({ authorized }) else null,
        )
        val browser =
            R2Browser(
                scope = scope,
                actual = { live },
                writeRuntime = runtime,
                sippySession = if (wired) ({ authorized }) else null,
                sippySecureWindow = { secure },
                session = { error("no batch A session in this test") },
            )

        init { browserRef = browser }

        fun openSippy() {
            browser.refreshContext()
            browser.onConfigurationIntent(R2ConfigurationIntent.PanelSelected(R2ConfigurationPanel.SIPPY))
            browser.onConfigurationIntent(R2ConfigurationIntent.Open(bucket))
        }

        fun fillDraft() {
            val editor = browser.sippyCredentials
            assertTrue(editor.beginSecureEditing())
            assertTrue(editor.setField(R2SippySecretField.SOURCE_BUCKET, R2SippySecretInput.of("source-bucket")))
            assertTrue(editor.setField(R2SippySecretField.SOURCE_REGION, R2SippySecretInput.of("us-east-1")))
            assertTrue(editor.setField(R2SippySecretField.SOURCE_ACCESS_KEY_ID, R2SippySecretInput.of("SYNTHETIC-SOURCE-KEY-ID")))
            assertTrue(editor.setField(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY, R2SippySecretInput.of(secretMarker)))
            assertTrue(editor.setField(R2SippySecretField.DESTINATION_ACCESS_KEY_ID, R2SippySecretInput.of("SYNTHETIC-R2-KEY-ID")))
            assertTrue(editor.setField(R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY, R2SippySecretInput.of(secretMarker)))
            // The form gates: exact target name + the explicit secret-submission disclosures.
            browser.onConfigurationIntent(R2ConfigurationIntent.SippyTypedBucketChanged(bucket))
            browser.onConfigurationIntent(R2ConfigurationIntent.SippyDisclosureAcknowledgedChanged(true))
            browser.onConfigurationIntent(R2ConfigurationIntent.SippyCredentialsAcknowledgedChanged(true))
        }

        val sippy get() = browser.configuration.state.value.sippy
        val state get() = browser.configuration.state.value
    }

    @Test
    fun theRootSippyPathPreviewsConfirmsAndKeepsTheResultNotice() = runTest {
        val h = Harness(this)
        h.openSippy()
        runCurrent()
        assertEquals(false, h.sippy.snapshot?.enabled)
        assertTrue(h.sippy.available)
        h.fillDraft()
        runCurrent()
        assertTrue(h.sippy.draftComplete)
        assertTrue(h.sippy.secureReady)

        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        val preview = assertNotNull(h.state.preview, "the shared preview card shows the Sippy write")
        assertEquals("default/$bucket/SIPPY", preview.target)
        assertEquals(0, puts)

        h.browser.confirmPreview()
        runCurrent()
        assertEquals(1, puts)
        assertEquals(1, h.store.rows.size)
        assertEquals(TaskState.SUCCEEDED, h.store.rows.values.single().state)
        assertNull(h.state.preview, "a settled write leaves no confirmation card")
        assertNotNull(h.state.notice)
        // The succeeded reload keeps the operation notice visible.
        assertNotNull(h.sippy.notice)
        assertEquals(true, h.sippy.snapshot?.enabled)
        // The credential value never reaches the surface state or the persisted task.
        assertFalse(h.state.toString().contains(secretMarker))
        assertFalse(h.store.rows.values.single().toString().contains(secretMarker))
    }

    @Test
    fun anUnknownSippyWriteKeepsNoConfirmationCardAndIsNeverResent() = runTest {
        val h = Harness(this)
        h.openSippy()
        runCurrent()
        h.fillDraft()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNotNull(h.state.preview)
        // The provider acknowledges but the same-lock read-back does not show the requested state.
        applyPut = false
        h.browser.confirmPreview()
        runCurrent()
        assertEquals(1, puts)
        val task = h.store.rows.values.single()
        assertEquals(TaskState.UNKNOWN, task.state)
        assertNull(h.state.preview)
        assertNotNull(h.state.notice)
        // A read-only reconcile of a PUT is never settled from public metadata, and nothing is resent.
        h.browser.reconcileTask(task.id)
        runCurrent()
        assertEquals(TaskState.UNKNOWN, h.store.rows.values.single().state)
        assertEquals(1, puts)
    }

    @Test
    fun theDisableOperationUsesTheSameRootPath() = runTest {
        enabled = true
        val h = Harness(this)
        h.openSippy()
        runCurrent()
        assertEquals(true, h.sippy.snapshot?.enabled)
        assertTrue(h.browser.sippyCredentials.beginSecureEditing())
        h.browser.onConfigurationIntent(R2ConfigurationIntent.RequestDisableSippy)
        h.browser.onConfigurationIntent(R2ConfigurationIntent.SippyTypedBucketChanged(bucket))
        h.browser.onConfigurationIntent(R2ConfigurationIntent.SippyDisableAcknowledgedChanged(true))
        runCurrent()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNotNull(h.state.preview)
        h.browser.confirmPreview()
        runCurrent()
        assertEquals(1, deletes)
        assertEquals(0, puts)
        assertEquals(TaskState.SUCCEEDED, h.store.rows.values.single().state)
        assertNull(h.state.preview)
        assertEquals(false, h.sippy.snapshot?.enabled)
    }

    @Test
    fun withoutTheSecureWindowOrACompleteDraftNoPreviewIsEverRequested() = runTest {
        // No protection: the surface refuses before the root is asked.
        val denied = Harness(this, secureGrant = false)
        denied.openSippy()
        runCurrent()
        assertFalse(denied.browser.sippyCredentials.beginSecureEditing())
        assertFalse(
            denied.browser.sippyCredentials.setField(
                R2SippySecretField.SOURCE_BUCKET,
                R2SippySecretInput.of("source-bucket"),
            ),
        )
        assertTrue(denied.browser.sippyCredentials.publicState.filled.isEmpty())
        denied.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNull(denied.state.preview)
        assertEquals(0, puts)
        assertNotNull(denied.state.validationMessage)

        // Protected but with an incomplete draft: still no request.
        val incomplete = Harness(this)
        incomplete.openSippy()
        runCurrent()
        assertTrue(incomplete.browser.sippyCredentials.beginSecureEditing())
        incomplete.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNull(incomplete.state.preview)
        assertEquals(0, puts)
        assertNotNull(incomplete.state.validationMessage)
    }

    @Test
    fun withoutAWiredSippySessionTheSurfaceStaysReadOnly() = runTest {
        val h = Harness(this, wired = false)
        h.openSippy()
        runCurrent()
        assertFalse(h.sippy.available)
        h.browser.sippyCredentials.beginSecureEditing()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNull(h.state.preview)
        assertEquals(0, puts)
        assertEquals(0, deletes)
        // No Sippy request was attempted at all (not even a read through a missing session).
        assertEquals(0, requests.count { it.path.endsWith("/sippy") })
    }

    @Test
    fun editingTheDraftOrChangingTheRouteRevokesTheIssuedPreview() = runTest {
        val h = Harness(this)
        h.openSippy()
        runCurrent()
        h.fillDraft()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNotNull(h.state.preview)

        // A credential edit revokes the issued preview before the root can confirm it.
        assertTrue(
            h.browser.sippyCredentials.setField(
                R2SippySecretField.SOURCE_REGION,
                R2SippySecretInput.of("eu-west-1"),
            ),
        )
        runCurrent()
        assertNull(h.state.preview)
        h.browser.confirmPreview()
        runCurrent()
        assertEquals(0, puts)

        // A route/credential change withdraws the private material as well.
        h.fillDraft()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNotNull(h.state.preview)
        h.live = R2BrowserContext(connection, 8, 1)
        h.issuerRevision = 8
        h.browser.refreshContext()
        runCurrent()
        assertNull(h.state.preview)
        assertFalse(h.browser.sippyCredentials.publicState.complete)
        h.browser.confirmPreview()
        runCurrent()
        assertEquals(0, puts)
    }

    @Test
    fun leavingTheSurfaceReleasesTheWindowPurposeAndThePrivateMaterial() = runTest {
        val h = Harness(this)
        h.openSippy()
        runCurrent()
        h.fillDraft()
        assertTrue(h.sippy.secureReady)
        assertEquals(setOf(SecurePurpose.SIPPY_CREDENTIALS), h.secure.purposes)
        val surfaceEpoch = assertNotNull(h.sippy.secureEpoch, "the mounted surface captures its session epoch")

        h.browser.onConfigurationIntent(
            R2ConfigurationIntent.PanelSelected(R2ConfigurationPanel.CORS),
        )
        runCurrent()
        // Fail-closed: the session is revoked immediately, the protection waits for the composition
        // signal + a clean committed frame (the production panel sends that signal).
        assertTrue(h.secure.purposes.isNotEmpty(), "the protection is kept until the clean-frame signal")
        h.browser.sippyCredentials.onSecretSurfaceRemoved(surfaceEpoch)
        assertTrue(h.secure.purposes.isEmpty(), "leaving the surface releases its window purpose after the signal")
        assertFalse(h.browser.sippyCredentials.publicState.secureReady)
        assertFalse(h.browser.sippyCredentials.publicState.complete)
        assertNull(h.browser.sippyCredentials.liveWrite(h.runtimeTarget()))
        assertEquals(0, puts)
    }

    private fun Harness.runtimeTarget() = com.flarego.core.model.R2WriteTarget(account, bucket)

    @Test
    fun aRefusedOversizedEditRevokesTheIssuedPreviewAndItsAcknowledgements() = runTest {
        val h = Harness(this)
        h.openSippy()
        runCurrent()
        h.fillDraft()
        runCurrent()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNotNull(h.state.preview, "the complete draft must be previewable first")
        assertTrue(h.state.sippy.disclosureAcknowledged && h.state.sippy.credentialsAcknowledged)

        // A refused >8 KiB edit is still an edit: the preview and the acknowledgements are gone.
        h.browser.onConfigurationIntent(
            R2ConfigurationIntent.SippyFieldChanged(
                R2SippySecretField.SOURCE_SECRET_ACCESS_KEY,
                R2SippySecretInput.of("y".repeat(9 * 1024)),
            ),
        )
        runCurrent()
        assertNull(h.state.preview)
        assertFalse(h.state.sippy.disclosureAcknowledged)
        assertFalse(h.state.sippy.credentialsAcknowledged)
        assertFalse(h.state.canPreview)
        assertNotNull(h.state.sippy.error)
        h.browser.confirmPreview()
        runCurrent()
        assertEquals(0, puts, "the old draft must not be submitted after a refused edit")
        // The rejected value never appears in the surface state or the error.
        assertFalse(h.state.toString().contains("yyyy"))
    }

    @Test
    fun theSippyCommandIsResolvedFromTheLiveEditorNotFromAPreparedPlan() = runTest {
        val h = Harness(this)
        h.openSippy()
        runCurrent()
        h.fillDraft()
        h.browser.onConfigurationIntent(R2ConfigurationIntent.Preview)
        runCurrent()
        assertNotNull(h.state.preview)
        val command = h.browser.liveCommand()
        assertNotNull(command)
        assertTrue(command is R2Command.Sippy)
        // Editing the draft changes the live command (new binding) and drops the old preview.
        val before = (command as R2Command.Sippy).write
        assertTrue(h.browser.sippyCredentials.setField(R2SippySecretField.SOURCE_REGION, R2SippySecretInput.of("us-west-2")))
        runCurrent()
        assertNull(h.state.preview)
        val after = assertNotNull(h.browser.liveCommand() as R2Command.Sippy).write
        assertFalse(
            (before as com.flarego.core.application.r2.R2SippyWrite.PutSippy).lease ===
                (after as com.flarego.core.application.r2.R2SippyWrite.PutSippy).lease,
        )
    }
}
