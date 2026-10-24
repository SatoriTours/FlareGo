package com.flarego.ui.r2

import com.flarego.core.application.operations.OperationClock
import com.flarego.core.application.operations.OperationIds
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
import com.flarego.core.model.R2SippyProvider
import com.flarego.core.model.TaskState
import com.flarego.core.ports.FingerprintPort
import com.flarego.core.ports.OperationStore
import com.flarego.core.ports.TokenKind
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 6a: the production Sippy form driven through the real root route.
 *
 * The real `R2Browser` + `R2ConfigurationBrowser` + typed intents + `R2WriteRuntime` +
 * `AuthorizedR2SippySession` + `CloudflareR2SippyClient` are used against a **stateful** synthetic
 * transport that echoes the submitted public metadata (never a secret) from a same-lock GET. Only the
 * Compose widgets are absent here; the AWS path is additionally exercised with real clicks/typing in
 * the native UI test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class R2SippyFormFlowTest {
    private val connection = Connection("r2-local", "fixture", "a".repeat(32))
    private val account = AccountScope(connection.id, connection.accountId)
    private val bucket = "my-bucket"
    private val accountResource = "com.cloudflare.api.account.${connection.accountId}"
    private val accountScope = "com.cloudflare.api.account"
    private val secretMarker = "SYNTHETIC-FORMFLOW-SECRET-MARKER-5c8"

    private val puts = mutableListOf<JsonObject>()
    private val deletes = mutableListOf<String>()
    private var enabled = false
    private var applyMutation = true
    private var putReceiptUnknown = false
    private var lastSource: JsonObject? = null
    private var lastDestination: JsonObject? = null

    private class Store : OperationStore {
        val rows = mutableMapOf<String, OperationTask>()
        override fun get(id: String) = rows[id]
        override fun list(connectionId: String) = rows.values.filter { it.connectionId == connectionId }
        override fun put(task: OperationTask) { rows[task.id] = task }
        override fun putWithSummary(task: OperationTask, summary: Operation) = put(task)
        override fun removeConnection(connectionId: String) { rows.entries.removeAll { it.value.connectionId == connectionId } }
    }

    private fun envelope(result: JsonElement) = HttpResponse(200, buildJsonObject {
        put("success", true); put("errors", JsonArray(emptyList())); put("messages", JsonArray(emptyList())); put("result", result)
    }.toString())

    private fun catalogRow(id: String, name: String, scope: String) = buildJsonObject {
        put("id", id); put("name", name); put("scopes", JsonArray(listOf(JsonPrimitive(scope))))
    }

    /** The provider echoes only the public source/destination metadata, exactly like the wire expects. */
    private fun sippyJson(): String {
        if (!enabled) return """{"enabled":false}"""
        val source = lastSource ?: return """{"enabled":false}"""
        val destination = lastDestination ?: return """{"enabled":false}"""
        return buildJsonObject {
            put("enabled", true)
            put("destination", destination)
            put("source", source)
        }.toString()
    }

    private fun publicSource(body: JsonObject): JsonObject {
        val source = body.getValue("source").jsonObject
        return when (source.getValue("provider").jsonPrimitive.content) {
            "aws" -> buildJsonObject {
                put("provider", "aws"); put("bucket", source.getValue("bucket").jsonPrimitive.content)
                put("region", source.getValue("region").jsonPrimitive.content)
            }
            "gcs" -> buildJsonObject {
                put("provider", "gcs"); put("bucket", source.getValue("bucket").jsonPrimitive.content)
            }
            "s3" -> buildJsonObject {
                put("provider", "s3"); put("bucketUrl", source.getValue("bucketUrl").jsonPrimitive.content)
            }
            "azure" -> buildJsonObject {
                put("provider", "azure"); put("container", source.getValue("container").jsonPrimitive.content)
            }
            else -> error("unexpected provider")
        }
    }

    private fun publicDestination(body: JsonObject): JsonObject {
        val destination = body.getValue("destination").jsonObject
        return buildJsonObject {
            put("accessKeyId", destination.getValue("accessKeyId").jsonPrimitive.content)
            put("account", connection.accountId)
            put("bucket", bucket)
            put("provider", "r2")
        }
    }

    private inner class Harness(scope: TestScope, wired: Boolean = true, grant: Boolean = true) {
        var live: R2BrowserContext? = R2BrowserContext(connection, 7, 1)
        var issuerRevision = 7L
        var now = 1_000L
        var ids = 0
        val store = Store()
        val secure = FakeSecureWindowPort(grant = grant)
        private var browserRef: R2Browser? = null

        private fun transport(request: HttpRequest): HttpResponse {
            return when {
                request.path == "/user/tokens/verify" ->
                    envelope(buildJsonObject { put("id", "self-token"); put("status", "active") })
                request.path.endsWith("/permission_groups") ->
                    envelope(JsonArray(listOf(
                        catalogRow("admin", "Workers R2 Storage Write", accountScope),
                        catalogRow("reader", "Workers R2 Storage Read", accountScope),
                    )))
                request.path.endsWith("/self-token") -> envelope(buildJsonObject {
                    put("id", "self-token"); put("status", "active")
                    put("policies", JsonArray(listOf(buildJsonObject {
                        put("id", "p-allow"); put("effect", "allow")
                        put("permission_groups", JsonArray(listOf(buildJsonObject { put("id", "admin") })))
                        put("resources", buildJsonObject { put(accountResource, JsonPrimitive("*")) })
                    })))
                })
                request.path == "/accounts/${connection.accountId}" ->
                    envelope(buildJsonObject { put("id", connection.accountId) })
                request.path.endsWith("/r2/buckets/$bucket") ->
                    envelope(buildJsonObject { put("name", bucket) })
                request.path.endsWith("/sippy") && request.method == "GET" ->
                    envelope(Json.parseToJsonElement(sippyJson()))
                request.path.endsWith("/sippy") && request.method == "PUT" -> {
                    val payload = request.payload
                    assertTrue(payload is HttpBody.SensitiveJson)
                    val body = Json.parseToJsonElement(payload.consumeBytes().decodeToString()).jsonObject
                    puts += body
                    if (applyMutation) {
                        lastSource = publicSource(body)
                        lastDestination = publicDestination(body)
                        enabled = true
                    }
                    if (putReceiptUnknown) HttpResponse(202, "{}") else envelope(buildJsonObject { put("ok", true) })
                }
                request.path.endsWith("/sippy") && request.method == "DELETE" -> {
                    deletes += request.path
                    if (applyMutation) enabled = false
                    envelope(buildJsonObject { put("ok", true) })
                }
                request.path.contains("/buckets") -> envelope(JsonArray(emptyList()))
                else -> error("unexpected synthetic request ${request.method} ${request.path}")
            }
        }

        val issuer = R2TokenAuthorityIssuer(connection, 7, { issuerRevision }, OperationClock { now }, TokenKind.USER_OWNED)
        val gateway = CloudflareGateway(HttpTransport { request -> transport(request) }, sleeper = {})
        val authorized = AuthorizedR2SippySession(connection, gateway, issuer)
        val runtime = R2WriteRuntime(
            scope.backgroundScope, store, StandardTestDispatcher(scope.testScheduler),
            FingerprintPort { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } },
            OperationClock { now }, OperationIds { "runtime-${++ids}" },
            actual = { live?.let { c -> R2LiveSelection(c.connection, c.credentialRevision, c.routeEpoch, browserRef?.liveCommand()) } },
            connections = { listOf(connection) },
            revision = { issuerRevision },
            session = { error("no batch A session in this test") },
            sippySession = if (wired) ({ authorized }) else null,
        )
        val browser = R2Browser(
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

        fun selectProvider(provider: R2SippyProvider) {
            browser.onConfigurationIntent(R2ConfigurationIntent.SippyProviderSelected(provider))
        }

        fun field(field: R2SippySecretField, value: String) {
            browser.onConfigurationIntent(R2ConfigurationIntent.SippyFieldChanged(field, R2SippySecretInput.of(value)))
        }

        fun acknowledgePut() {
            browser.onConfigurationIntent(R2ConfigurationIntent.SippyDisclosureAcknowledgedChanged(true))
            browser.onConfigurationIntent(R2ConfigurationIntent.SippyCredentialsAcknowledgedChanged(true))
        }

        fun typedBucket(name: String = bucket) {
            browser.onConfigurationIntent(R2ConfigurationIntent.SippyTypedBucketChanged(name))
        }

        fun preview() = browser.onConfigurationIntent(R2ConfigurationIntent.Preview)

        fun fill(provider: R2SippyProvider) {
            selectProvider(provider)
            when (provider) {
                R2SippyProvider.AWS -> {
                    field(R2SippySecretField.SOURCE_BUCKET, "source-bucket")
                    field(R2SippySecretField.SOURCE_REGION, "us-east-1")
                    field(R2SippySecretField.SOURCE_ACCESS_KEY_ID, "SYNTHETIC-SOURCE-KEY")
                    field(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY, secretMarker)
                }
                R2SippyProvider.GCS -> {
                    field(R2SippySecretField.SOURCE_BUCKET, "source-bucket")
                    field(R2SippySecretField.GCS_CLIENT_EMAIL, "synthetic@example.invalid")
                    field(R2SippySecretField.GCS_PRIVATE_KEY, "-----BEGIN-----\n$secretMarker\n-----END-----")
                }
                R2SippyProvider.S3 -> {
                    field(R2SippySecretField.SOURCE_BUCKET_URL, "https://s3.example.invalid/bucket")
                    field(R2SippySecretField.SOURCE_ACCESS_KEY_ID, "SYNTHETIC-SOURCE-KEY")
                    field(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY, secretMarker)
                }
                R2SippyProvider.AZURE -> {
                    field(R2SippySecretField.AZURE_ACCOUNT_NAME, "syntheticstorage")
                    field(R2SippySecretField.SOURCE_CONTAINER, "synthetic-container")
                    field(R2SippySecretField.AZURE_SAS_TOKEN, secretMarker)
                }
            }
            field(R2SippySecretField.DESTINATION_ACCESS_KEY_ID, "SYNTHETIC-R2-KEY")
            field(R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY, secretMarker)
        }

        val sippy get() = browser.configuration.state.value.sippy
        val state get() = browser.configuration.state.value
    }

    private suspend fun TestScope.awaitPreview(h: Harness) {
        h.preview()
        runCurrent()
        assertNotNull(h.state.preview, "the form must request a preview once every gate is satisfied")
    }

    private suspend fun TestScope.confirm(h: Harness) {
        h.browser.confirmPreview()
        runCurrent()
    }

    @Test
    fun allFourProvidersPlusAzureSasAndTheDisableOperationSendExactlyOnce() = runTest {
        for (case in listOf(
            R2SippyProvider.AWS,
            R2SippyProvider.GCS,
            R2SippyProvider.S3,
            R2SippyProvider.AZURE,
        )) {
            puts.clear(); deletes.clear(); enabled = false; lastSource = null; lastDestination = null
            val h = Harness(this)
            h.openSippy(); runCurrent()
            assertTrue(h.browser.sippyCredentials.beginSecureEditing())
            h.fill(case)
            h.typedBucket()
            h.acknowledgePut()
            runCurrent()
            awaitPreview(h)
            assertEquals(0, puts.size, "${case.name}: a preview must not send")
            confirm(h)
            assertEquals(1, puts.size, "${case.name}: exactly one PUT")
            assertEquals(TaskState.SUCCEEDED, h.store.rows.values.single().state)
            assertNotNull(h.state.notice)
            assertEquals(case.wireValue, puts.single().getValue("source").jsonObject.getValue("provider").jsonPrimitive.content)
            // The secret marker is only inside the one-shot body, never in the surface state or the task.
            assertFalse(h.state.toString().contains(secretMarker))
            assertFalse(h.store.rows.values.single().toString().contains(secretMarker))
            // The read-back is a separate GET of the same resource, and the surface shows the result.
            assertTrue(enabled)
            assertEquals(true, h.sippy.snapshot?.enabled)
        }

        // Azure with a SAS token instead of an account key, then the DELETE operation.
        puts.clear(); deletes.clear(); enabled = false; lastSource = null; lastDestination = null
        val sas = Harness(this)
        sas.openSippy(); runCurrent()
        assertTrue(sas.browser.sippyCredentials.beginSecureEditing())
        sas.fill(R2SippyProvider.AZURE)
        sas.typedBucket(); sas.acknowledgePut(); runCurrent()
        awaitPreview(sas); confirm(sas)
        assertEquals(1, puts.size)

        deletes.clear(); enabled = true
        val del = Harness(this)
        del.openSippy(); runCurrent()
        assertEquals(true, del.sippy.snapshot?.enabled)
        assertTrue(del.browser.sippyCredentials.beginSecureEditing())
        del.browser.onConfigurationIntent(R2ConfigurationIntent.RequestDisableSippy)
        del.typedBucket("not-the-bucket")
        del.browser.onConfigurationIntent(R2ConfigurationIntent.SippyDisableAcknowledgedChanged(true))
        runCurrent()
        del.preview(); runCurrent()
        assertNull(del.state.preview, "a wrong typed bucket must not request a preview")
        del.typedBucket(bucket); runCurrent()
        awaitPreview(del)
        assertEquals(0, deletes.size)
        confirm(del)
        assertEquals(1, deletes.size)
        assertEquals(TaskState.SUCCEEDED, del.store.rows.values.single().state)
        assertEquals(false, del.sippy.snapshot?.enabled)
    }

    @Test
    fun everyGateBlocksWithZeroMutations() = runTest {
        // No protection at all.
        val noSecure = Harness(this, grant = false)
        noSecure.openSippy(); runCurrent()
        assertFalse(noSecure.browser.sippyCredentials.beginSecureEditing())
        noSecure.fill(R2SippyProvider.AWS)
        noSecure.typedBucket(); noSecure.acknowledgePut()
        noSecure.preview(); runCurrent()
        assertNull(noSecure.state.preview)
        assertEquals(0, puts.size)

        // Incomplete draft (no destination keys).
        val incomplete = Harness(this)
        incomplete.openSippy(); runCurrent()
        assertTrue(incomplete.browser.sippyCredentials.beginSecureEditing())
        incomplete.selectProvider(R2SippyProvider.AWS)
        incomplete.field(R2SippySecretField.SOURCE_BUCKET, "source-bucket")
        incomplete.field(R2SippySecretField.SOURCE_REGION, "us-east-1")
        incomplete.field(R2SippySecretField.SOURCE_ACCESS_KEY_ID, "SYNTHETIC-SOURCE-KEY")
        incomplete.field(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY, secretMarker)
        incomplete.typedBucket(); incomplete.acknowledgePut(); runCurrent()
        incomplete.preview(); runCurrent()
        assertNull(incomplete.state.preview)
        assertEquals(0, puts.size)
        assertNotNull(incomplete.state.validationMessage)

        // Missing acknowledgements (both) and a wrong typed bucket each block independently.
        for (variant in listOf("no-disclosure", "no-credentials", "wrong-bucket")) {
            val h = Harness(this)
            h.openSippy(); runCurrent()
            assertTrue(h.browser.sippyCredentials.beginSecureEditing())
            h.fill(R2SippyProvider.AWS)
            h.typedBucket(if (variant == "wrong-bucket") "other-bucket" else bucket)
            when (variant) {
                "no-disclosure" ->
                    h.browser.onConfigurationIntent(R2ConfigurationIntent.SippyCredentialsAcknowledgedChanged(true))
                "no-credentials" ->
                    h.browser.onConfigurationIntent(R2ConfigurationIntent.SippyDisclosureAcknowledgedChanged(true))
                else -> h.acknowledgePut()
            }
            runCurrent()
            h.preview(); runCurrent()
            assertNull(h.state.preview, variant)
            assertEquals(0, puts.size, variant)
            assertNotNull(h.state.validationMessage, variant)
        }

        // Azure with both or neither secret is rejected before any request.
        for (azure in listOf("both", "neither")) {
            val h = Harness(this)
            h.openSippy(); runCurrent()
            assertTrue(h.browser.sippyCredentials.beginSecureEditing())
            h.selectProvider(R2SippyProvider.AZURE)
            h.field(R2SippySecretField.AZURE_ACCOUNT_NAME, "syntheticstorage")
            h.field(R2SippySecretField.SOURCE_CONTAINER, "synthetic-container")
            when (azure) {
                "both" -> {
                    h.field(R2SippySecretField.AZURE_ACCOUNT_KEY, secretMarker)
                    h.field(R2SippySecretField.AZURE_SAS_TOKEN, secretMarker)
                }
                else -> Unit
            }
            h.field(R2SippySecretField.DESTINATION_ACCESS_KEY_ID, "SYNTHETIC-R2-KEY")
            h.field(R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY, secretMarker)
            h.typedBucket(); h.acknowledgePut(); runCurrent()
            h.preview(); runCurrent()
            assertNull(h.state.preview, azure)
            assertEquals(0, puts.size, azure)
        }

        // An unsafe source URL is refused by the reviewed client bound.
        val url = Harness(this)
        url.openSippy(); runCurrent()
        assertTrue(url.browser.sippyCredentials.beginSecureEditing())
        url.selectProvider(R2SippyProvider.S3)
        url.field(R2SippySecretField.SOURCE_BUCKET_URL, "https://user:pw@s3.example.invalid/b")
        url.field(R2SippySecretField.SOURCE_ACCESS_KEY_ID, "SYNTHETIC-SOURCE-KEY")
        url.field(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY, secretMarker)
        url.field(R2SippySecretField.DESTINATION_ACCESS_KEY_ID, "SYNTHETIC-R2-KEY")
        url.field(R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY, secretMarker)
        url.typedBucket(); url.acknowledgePut(); runCurrent()
        url.preview(); runCurrent()
        assertNull(url.state.preview)
        assertEquals(0, puts.size)
    }

    @Test
    fun anUnknownPutReceiptIsNeverProvenByIdenticalPublicMetadataAndIsNotReplayed() = runTest {
        val h = Harness(this)
        h.openSippy(); runCurrent()
        assertTrue(h.browser.sippyCredentials.beginSecureEditing())
        h.fill(R2SippyProvider.AWS)
        h.typedBucket(); h.acknowledgePut(); runCurrent()
        awaitPreview(h)
        // The provider acknowledges asynchronously but the resource does not change.
        applyMutation = false
        confirm(h)
        assertEquals(1, puts.size)
        val task = h.store.rows.values.single()
        assertEquals(TaskState.UNKNOWN, task.state)
        assertNull(h.state.preview, "an unknown write must not keep a confirmation card")
        assertNotNull(h.state.notice)
        // A read-only reconcile is never settled from the public metadata, and nothing is resent.
        h.browser.reconcileTask(task.id)
        runCurrent()
        assertEquals(TaskState.UNKNOWN, h.store.rows.values.single().state)
        assertEquals(1, puts.size)
        assertFalse(h.state.toString().contains(secretMarker))
    }

    @Test
    fun aDraftEditOrAReplacedWindowRevokesThePreviewBeforeConfirming() = runTest {
        val h = Harness(this)
        h.openSippy(); runCurrent()
        assertTrue(h.browser.sippyCredentials.beginSecureEditing())
        h.fill(R2SippyProvider.AWS)
        h.typedBucket(); h.acknowledgePut(); runCurrent()
        awaitPreview(h)

        // A credential edit revokes the issued preview and the acknowledgements it carried.
        h.field(R2SippySecretField.SOURCE_REGION, "eu-west-1")
        runCurrent()
        assertNull(h.state.preview)
        assertFalse(h.state.sippy.disclosureAcknowledged)
        assertFalse(h.state.sippy.credentialsAcknowledged)
        h.browser.confirmPreview(); runCurrent()
        assertEquals(0, puts.size)

        // A replaced window withdraws readiness: no new preview and no send.
        assertTrue(h.browser.sippyCredentials.beginSecureEditing())
        h.acknowledgePut(); runCurrent()
        awaitPreview(h)
        h.secure.purposes.clear()
        h.browser.sippyCredentials.onWindowAttached(h.secure.generation + 1)
        runCurrent()
        assertNull(h.state.preview)
        assertFalse(h.sippy.secureReady)
        h.browser.confirmPreview(); runCurrent()
        assertEquals(0, puts.size)
    }

    @Test
    fun aProviderSwitchClearsThePreviousSourceCredentialsAndThePreview() = runTest {
        val h = Harness(this)
        h.openSippy(); runCurrent()
        assertTrue(h.browser.sippyCredentials.beginSecureEditing())
        h.fill(R2SippyProvider.AWS)
        h.typedBucket(); h.acknowledgePut(); runCurrent()
        awaitPreview(h)
        h.selectProvider(R2SippyProvider.GCS)
        runCurrent()
        assertNull(h.state.preview)
        assertFalse(h.sippy.draftComplete)
        assertNull(h.browser.sippyCredentials.currentValue(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY))
        h.preview(); runCurrent()
        assertNull(h.state.preview)
        assertEquals(0, puts.size)
    }
}
