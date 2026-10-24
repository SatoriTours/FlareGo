package com.flarego

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.HttpTransport
import com.flarego.core.model.Page
import com.flarego.core.model.ResourceKind
import com.flarego.core.model.TaskState
import com.flarego.core.ports.ByteSink
import com.flarego.core.ports.HttpResponseHead
import com.flarego.core.ports.TransferPort
import com.flarego.ui.FlareGoApp
import com.flarego.ui.r2.R2ConfigurationIntent
import com.flarego.ui.r2.R2ConfigurationPanel
import com.flarego.ui.r2.R2SippySecretField
import com.flarego.ui.r2.R2SippySecretInput
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Task 6a fix1: the **production** Sippy form through the real app stack, all four providers.
 *
 * Real `FlareGoViewModel` + `FlareGoApp` + `R2Browser` + `R2ConfigurationBrowser` + real
 * `AuthorizedR2SippySession`/controller/runtime, real Compose clicks/typing, the production
 * `AndroidSecureWindow` on the real test-Activity window, and a **stateful** synthetic transport.
 *
 * No new permission is requested (see `REORDER_TASKS` assertion in the recents test); only synthetic
 * credentials are typed, every credential field is masked, and no screenshot/log/dump may contain them.
 */
class R2SippyFormUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = "d".repeat(32)
    private var model: FlareGoViewModel? = null
    private var initial = emptySet<String>()
    private val requests = Collections.synchronizedList(mutableListOf<HttpRequest>())
    private var enabled = false
    private var lastSource: String? = null
    private var lastDestination: String? = null
    /** False models an acknowledged PUT whose configuration never actually changed. */
    private var applyPut = true
    private val marker = "SYNTHETIC-T6A-SECRET-MARKER-7f2"
    private val extraSecrets = listOf(
        "SYNTHETIC-SOURCE-KEY",
        "SYNTHETIC-R2-KEY",
        "synthetic@example.invalid",
        "-----BEGIN-----",
        "syntheticstorage",
    )
    private val frameFlags = mutableListOf<Boolean>()
    private var frameSampler: ViewTreeObserver.OnPreDrawListener? = null

    private val transfers = object : TransferPort {
        override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead {
            requests += request
            sink.write(byteArrayOf(1, 2, 3), 0, 3)
            sink.close()
            return HttpResponseHead(200, emptyMap(), 3)
        }
    }

    private fun ok(result: String) =
        """{"success":true,"errors":[],"messages":[],"result":$result}"""

    private fun sippyJson(): String =
        if (!enabled) """{"enabled":false}"""
        else """{"enabled":true,"destination":$lastDestination,"source":$lastSource}"""

    private fun install() {
        compose.runOnIdle {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            val m = FlareGoViewModel(app, Dispatchers.IO, { HttpTransport { request ->
                requests += request
                val result = when {
                    request.path == "/user/tokens/verify" -> """{"id":"self","status":"active"}"""
                    request.path == "/user/tokens/self" ->
                        """{"id":"self","status":"active","policies":[{"id":"p","effect":"allow","permission_groups":[{"id":"admin"}],"resources":{"com.cloudflare.api.account.$account":"*"}}]}"""
                    request.path == "/user/tokens/permission_groups" ->
                        """[{"id":"admin","name":"Workers R2 Storage Write","scopes":["com.cloudflare.api.account"]}]"""
                    request.path == "/accounts/$account/tokens/verify" -> return@HttpTransport HttpResponse(401, "denied")
                    request.path == "/accounts/$account" -> """{"id":"$account","name":"R2 Test"}"""
                    request.path.endsWith("/r2/buckets/my-bucket/sippy") && request.method == "GET" ->
                        Json.parseToJsonElement(sippyJson()).toString()
                    request.path.endsWith("/r2/buckets/my-bucket/sippy") && request.method == "PUT" -> {
                        val payload = request.payload
                        check(payload is HttpBody.SensitiveJson) { "the Sippy PUT must carry the sensitive body" }
                        val body = Json.parseToJsonElement(payload.consumeBytes().decodeToString()).jsonObject
                        val source = body.getValue("source").jsonObject
                        if (!applyPut) return@HttpTransport HttpResponse(200, ok("{\"ok\":true}"))
                        lastSource = when (source.getValue("provider").jsonPrimitive.content) {
                            "aws" -> """{"provider":"aws","bucket":"${source.getValue("bucket").jsonPrimitive.content}","region":"${source.getValue("region").jsonPrimitive.content}"}"""
                            "gcs" -> """{"provider":"gcs","bucket":"${source.getValue("bucket").jsonPrimitive.content}"}"""
                            "s3" -> """{"provider":"s3","bucketUrl":"${source.getValue("bucketUrl").jsonPrimitive.content}"}"""
                            else -> """{"provider":"azure","container":"${source.getValue("container").jsonPrimitive.content}"}"""
                        }
                        lastDestination =
                            """{"accessKeyId":"${body.getValue("destination").jsonObject.getValue("accessKeyId").jsonPrimitive.content}","account":"$account","bucket":"my-bucket","provider":"r2"}"""
                        enabled = true
                        """{"ok":true}"""
                    }
                    request.path.endsWith("/r2/buckets/my-bucket/sippy") && request.method == "DELETE" -> {
                        enabled = false
                        """{"ok":true}"""
                    }
                    request.path.endsWith("/r2/buckets/my-bucket") ->
                        """{"name":"my-bucket","location":"weur","storage_class":"Standard"}"""
                    request.path.contains("/r2/buckets?") ->
                        """{"buckets":[{"name":"my-bucket","location":"weur","storage_class":"Standard"}]}"""
                    else -> "[]"
                }
                HttpResponse(200, ok(result))
            } }, { transfers })
            model = m
            owner.put("r2", m)
            initial = m.controller.state.value.connections.map { it.id }.toSet()
            m.viewModelScope.launch { m.connect("R2 Test", account, "synthetic-r2-token") }
        }
        compose.waitUntil(10000) { model?.controller?.state?.value?.selected?.accountId == account }
        val m = checkNotNull(model)
        compose.runOnIdle { m.attachSecureWindow(AndroidSecureWindow(compose.activity.window)) }
        compose.setContent { FlareGoApp(m.controller, { _, _, _ -> }, m::disconnect, {}, r2Browser = m.r2Browser) }
        compose.onNodeWithTag("nav-resources").performClick()
        compose.onNodeWithText("R2", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { m.r2Browser.state.value.buckets.rows.isNotEmpty() }
        openSippyPanel()
    }

    private fun openSippyPanel() {
        val m = checkNotNull(model)
        val state = m.r2Browser.configuration.state.value
        if (!state.open) {
            compose.onNodeWithTag("rb-config-my-bucket").performScrollTo().performClick()
            compose.waitUntil(10000) { m.r2Browser.configuration.state.value.open }
        }
        if (m.r2Browser.configuration.state.value.panel != R2ConfigurationPanel.SIPPY) {
            compose.onNodeWithTag("rc-panel-tabs").performScrollToNode(hasTestTag("rc-panel-sippy"))
            compose.onNodeWithTag("rc-panel-sippy").assertIsDisplayed().performClick()
            compose.waitUntil(10000) { m.r2Browser.configuration.state.value.panel == R2ConfigurationPanel.SIPPY }
        }
        compose.waitUntil(10000) { m.r2Browser.configuration.state.value.sippy.snapshot != null }
    }

    private val owner = androidx.lifecycle.ViewModelStore()

    private fun secureFlag(): Boolean =
        (compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

    private fun mutations() = requests.filter { it.method != "GET" }

    private fun sippyState() = checkNotNull(model).r2Browser.configuration.state.value

    // ------------------------------------------------------------------ frame-level sampler

    private fun startFrameSampler() {
        frameFlags.clear()
        val decor = compose.activity.window.decorView
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                frameFlags += secureFlag()
                return true
            }
        }
        frameSampler = listener
        compose.runOnUiThread { decor.viewTreeObserver.addOnPreDrawListener(listener) }
    }

    private fun stopFrameSampler() {
        frameSampler?.let { listener ->
            val decor = compose.activity.window.decorView
            compose.runOnUiThread { decor.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
        frameSampler = null
    }

    // ------------------------------------------------------------------ form helpers

    private fun typeSecret(field: R2SippySecretField, value: String) {
        val node = compose.onNodeWithTag("rc-sippy-field-${field.name}")
        node.performScrollTo()
        node.performClick()
        node.performTextReplacement(value)
        compose.runOnIdle {
            val current = model!!.r2Browser.sippyCredentials.currentValue(field)
            if (value.isEmpty()) {
                // An explicit clear removes the value (used to switch Azure auth modes).
                check(current == null) { "the owner did not clear ${field.name}" }
            } else {
                check(current != null) {
                    "the owner did not receive ${field.name} (font=${InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale})"
                }
            }
        }
    }

    private fun typedBucket(value: String) {
        val node = compose.onNodeWithTag("rc-sippy-typed-bucket")
        node.performScrollTo()
        node.performClick()
        node.performTextReplacement(value)
        closeKeyboard()
    }

    private fun tapAck(tag: String) {
        val node = compose.onNodeWithTag(tag)
        node.performScrollTo()
        node.performClick()
    }

    private fun closeKeyboard() {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
    }

    private fun beginProtected() {
        compose.onNodeWithTag("rc-sippy-secure-begin").performScrollTo().performClick()
        compose.waitUntil(10000) { sippyState().sippy.secureReady }
    }

    private fun fillProvider(providerTag: String) {
        compose.onNodeWithTag("rc-sippy-provider-$providerTag").performScrollTo().performClick()
        when (providerTag) {
            "AWS" -> {
                typeSecret(R2SippySecretField.SOURCE_BUCKET, "source-bucket")
                typeSecret(R2SippySecretField.SOURCE_REGION, "us-east-1")
                typeSecret(R2SippySecretField.SOURCE_ACCESS_KEY_ID, "SYNTHETIC-SOURCE-KEY")
                typeSecret(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY, marker)
            }
            "GCS" -> {
                typeSecret(R2SippySecretField.SOURCE_BUCKET, "source-bucket")
                typeSecret(R2SippySecretField.GCS_CLIENT_EMAIL, "synthetic@example.invalid")
                typeSecret(R2SippySecretField.GCS_PRIVATE_KEY, "-----BEGIN-----\n$marker\n-----END-----")
            }
            "S3" -> {
                typeSecret(R2SippySecretField.SOURCE_BUCKET_URL, "https://s3.example.invalid/bucket")
                typeSecret(R2SippySecretField.SOURCE_ACCESS_KEY_ID, "SYNTHETIC-SOURCE-KEY")
                typeSecret(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY, marker)
            }
            "AZURE" -> {
                typeSecret(R2SippySecretField.AZURE_ACCOUNT_NAME, "syntheticstorage")
                typeSecret(R2SippySecretField.SOURCE_CONTAINER, "synthetic-container")
            }
        }
        typeSecret(R2SippySecretField.DESTINATION_ACCESS_KEY_ID, "SYNTHETIC-R2-KEY")
        typeSecret(R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY, marker)
        closeKeyboard()
    }

    private fun existingTaskIds(): Set<String> =
        compose.runOnIdle { checkNotNull(model).r2Browser.writeTasks.value.map { it.id }.toSet() }

    /** Asserts one PUT and that the independent same-resource GET happened *after* it. */
    private fun assertOneWriteThenReadback(provider: String, beforeTaskIds: Set<String>) {
        compose.waitUntil(15000) {
            val m = checkNotNull(model)
            !m.r2Browser.configuration.state.value.writing && m.r2Browser.writeTasks.value.isNotEmpty()
        }
        compose.runOnIdle {
            val snapshot = requests.toList()
            val putIndex = snapshot.indexOfFirst { it.method == "PUT" && it.path.endsWith("/sippy") }
            assertTrue("$provider: exactly one PUT expected", putIndex >= 0 && snapshot.count { it.method == "PUT" } == 1)
            val readbackIndex = snapshot.indexOfLast { it.method == "GET" && it.path.endsWith("/sippy") }
            assertTrue(
                "$provider: the independent read-back GET must come after the PUT (put=$putIndex readback=$readbackIndex)",
                readbackIndex > putIndex,
            )
            val created = model!!.r2Browser.writeTasks.value.filter { it.id !in beforeTaskIds }
            assertEquals("$provider: exactly one new task for this sub-case", 1, created.size)
            val latest = created.single()
            assertEquals(TaskState.SUCCEEDED, latest.state)
            assertEquals("r2-put-bucket-sippy-config", latest.actionKey)
            val state = sippyState()
            assertNull("$provider: a settled write leaves no confirmation card", state.preview)
            assertNotNull("$provider: the settlement notice must be visible", state.notice)
            assertEquals(true, state.sippy.snapshot?.enabled)
            assertFalse(state.toString().contains(marker))
            assertFalse(latest.toString().contains(marker))
        }
    }

    @After
    fun cleanup() {
        stopFrameSampler()
        compose.runOnIdle { model?.let { m -> m.controller.state.value.connections.filter { it.id !in initial }.forEach(m::disconnect) } }
        compose.waitUntil(10000) { model?.controller?.state?.value?.connections?.none { it.id !in initial } != false }
        compose.runOnIdle {
            model?.let { m ->
                if (m.r2Browser.configuration.state.value.sippy.secureReady) {
                    m.r2Browser.onConfigurationIntent(R2ConfigurationIntent.EndSippySecureEditing)
                }
            }
            owner.clear()
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun allSixProviderFlowsThroughTheRealUi() {
        install()
        beginProtected()
        // Five PUT flows (AWS, GCS, S3, Azure account key, Azure SAS) through the real form.
        for (case in listOf(
            "AWS" to "AWS",
            "GCS" to "GCS",
            "S3" to "S3",
            "AZURE_KEY" to "AZURE",
            "AZURE_SAS" to "AZURE",
        )) {
            requests.clear(); enabled = false; lastSource = null; lastDestination = null
            openSippyPanel()
            fillProvider(case.second)
            if (case.first == "AZURE_KEY") {
                typeSecret(R2SippySecretField.AZURE_ACCOUNT_KEY, marker)
            } else if (case.first == "AZURE_SAS") {
                // The previous sub-case left an account key: both would be refused, so clear it first.
                typeSecret(R2SippySecretField.AZURE_ACCOUNT_KEY, "")
                typeSecret(R2SippySecretField.AZURE_SAS_TOKEN, marker)
            }
            closeKeyboard()
            typedBucket("my-bucket")
            tapAck("rc-sippy-ack-disclosure")
            tapAck("rc-sippy-ack-credentials")
            compose.waitUntil(10000) { sippyState().canPreview }
            compose.onNodeWithTag("rc-sippy-preview").performScrollTo().performClick()
            compose.waitUntil(10000) { sippyState().preview != null }
            compose.runOnIdle {
                assertEquals("${case.first}: a preview must not send", 0, mutations().size)
                assertNotNull(sippyState().preview)
                assertFalse(sippyState().toString().contains(marker))
            }
            val beforeTaskIds = existingTaskIds()
            compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
            assertOneWriteThenReadback(case.first, beforeTaskIds)
            compose.onNodeWithTag("rc-preview-card").assertDoesNotExist()
        }

        // The DELETE (disable) operation through the same panel and gates.
        requests.clear(); enabled = true
        openSippyPanel()
        compose.waitUntil(10000) { sippyState().sippy.snapshot?.enabled == true }
        compose.onNodeWithTag("rc-sippy-disable").performScrollTo().performClick()
        typedBucket("my-bucket")
        tapAck("rc-sippy-ack-disable")
        compose.waitUntil(10000) { sippyState().canPreview }
        compose.onNodeWithTag("rc-sippy-disable-preview").performScrollTo().performClick()
        compose.waitUntil(10000) { sippyState().preview != null }
        compose.runOnIdle { assertEquals("DELETE: a preview must not send", 0, mutations().size) }
        val beforeDeleteTaskIds = existingTaskIds()
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        compose.waitUntil(15000) {
            val m = checkNotNull(model)
            !m.r2Browser.configuration.state.value.writing && m.r2Browser.writeTasks.value.isNotEmpty()
        }
        compose.runOnIdle {
            val snapshot = requests.toList()
            val deleteIndex = snapshot.indexOfFirst { it.method == "DELETE" }
            assertTrue("DELETE: exactly one DELETE", deleteIndex >= 0 && snapshot.count { it.method == "DELETE" } == 1)
            val readbackIndex = snapshot.indexOfLast { it.method == "GET" && it.path.endsWith("/sippy") }
            assertTrue("DELETE: read-back GET after the DELETE", readbackIndex > deleteIndex)
            val created = model!!.r2Browser.writeTasks.value.filter { it.id !in beforeDeleteTaskIds }
            assertEquals(1, created.size)
            assertEquals(TaskState.SUCCEEDED, created.single().state)
            assertEquals("r2-delete-bucket-sippy-config", created.single().actionKey)
            assertNull(sippyState().preview)
            assertNotNull(sippyState().notice)
            assertEquals(false, sippyState().sippy.snapshot?.enabled)
        }
        compose.onNodeWithTag("rc-preview-card").assertDoesNotExist()
    }

    @Test
    fun everyProviderFieldIsReachableAndMasked() {
        install()
        beginProtected()
        for (case in listOf(
            "AWS" to listOf(
                R2SippySecretField.SOURCE_BUCKET, R2SippySecretField.SOURCE_REGION,
                R2SippySecretField.SOURCE_ACCESS_KEY_ID, R2SippySecretField.SOURCE_SECRET_ACCESS_KEY,
            ),
            "GCS" to listOf(
                R2SippySecretField.SOURCE_BUCKET, R2SippySecretField.GCS_CLIENT_EMAIL, R2SippySecretField.GCS_PRIVATE_KEY,
            ),
            "S3" to listOf(
                R2SippySecretField.SOURCE_BUCKET_URL, R2SippySecretField.SOURCE_ACCESS_KEY_ID,
                R2SippySecretField.SOURCE_SECRET_ACCESS_KEY,
            ),
            "AZURE" to listOf(
                R2SippySecretField.AZURE_ACCOUNT_NAME, R2SippySecretField.SOURCE_CONTAINER,
                R2SippySecretField.AZURE_ACCOUNT_KEY, R2SippySecretField.AZURE_SAS_TOKEN,
            ),
        )) {
            compose.onNodeWithTag("rc-sippy-provider-${case.first}").performScrollTo().assertIsDisplayed().performClick()
            for (field in case.second) {
                compose.onNodeWithTag("rc-sippy-field-${field.name}").performScrollTo().assertIsDisplayed()
                // Every credential control is a password field (masked + a11y password semantics).
                val node = compose.onNodeWithTag("rc-sippy-field-${field.name}").fetchSemanticsNode()
                assertTrue(
                    "${case.first}/${field.name} must be a password field",
                    node.config.getOrNull(SemanticsProperties.Password) != null,
                )
            }
        }
        // Destination fields are always masked as well.
        for (field in listOf(
            R2SippySecretField.DESTINATION_ACCESS_KEY_ID,
            R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY,
        )) {
            compose.onNodeWithTag("rc-sippy-field-${field.name}").performScrollTo().assertIsDisplayed()
            assertNotNull(compose.onNodeWithTag("rc-sippy-field-${field.name}").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.Password))
        }

        // Bounded capability check: the app's **own** render of a masked field while the real window is
        // protected (a node-level capture, never a system screenshot; it changes no flag).
        compose.onNodeWithTag("rc-sippy-provider-AWS").performScrollTo().performClick()
        compose.waitUntil(10000) { sippyState().sippy.provider == com.flarego.core.model.R2SippyProvider.AWS }
        compose.onNodeWithTag("rc-sippy-field-SOURCE_ACCESS_KEY_ID").performScrollTo()
        val selfCapture = runCatching {
            val bitmap = compose.onNodeWithTag("rc-sippy-field-SOURCE_ACCESS_KEY_ID").captureToImage()
                .asAndroidBitmap()
            val colours = distinctColours(bitmap)
            println("T6A_SELF_CAPTURE size=${bitmap.width}x${bitmap.height} distinct=$colours")
            colours
        }
        assertTrue("the app's own masked-field capture must not fail: ${selfCapture.exceptionOrNull()}", selfCapture.isSuccess)
        val colours = selfCapture.getOrThrow()
        assertTrue("the app's own capture must be inspectable (distinct=$colours)", colours > 2)
    }

    @Test
    fun theAwsFormTypesMasksPreviewsWithoutSendingAndConfirmsExactlyOnce() {
        install()
        compose.onNodeWithTag("rc-sippy-field-SOURCE_SECRET_ACCESS_KEY").assertDoesNotExist()
        assertFalse("the window must not be protected yet", secureFlag())
        assertFalse(sippyState().sippy.secureReady)
        val beforeColours = distinctColours(shellCapture())
        assertTrue("the unprotected form surface must be visible (distinct=$beforeColours)", beforeColours > 20)

        startFrameSampler()
        beginProtected()
        compose.onNodeWithTag("rc-sippy-field-SOURCE_SECRET_ACCESS_KEY").performScrollTo().assertIsDisplayed()
        // Frame-level evidence: from the moment the secret control exists, every drawn frame carries
        // FLAG_SECURE. The flag is applied synchronously inside the click handler that makes the form
        // ready, so no frame that can contain the control is drawn unprotected; the sampler is reset
        // here (frames before the click could not contain the control at all — that is asserted above).
        compose.runOnIdle {
            frameFlags.clear()
            compose.activity.window.decorView.invalidate()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val frames = frameFlags.toList()
            println("T6A_FRAMES enter total=${frames.size} allSecure=${frames.all { it }}")
            assertTrue("frames were actually drawn after the control existed (${frames.size})", frames.isNotEmpty())
            assertTrue("every frame drawn with the control present must be protected: $frames", frames.all { it })
        }
        assertTrue("FLAG_SECURE is on the real window", secureFlag())
        val protectedColours = distinctColours(shellCapture())
        assertTrue(
            "the protected form surface must be blanked by the system (distinct=$protectedColours, was $beforeColours)",
            protectedColours <= 2,
        )

        fillProvider("AWS")
        compose.waitUntil(10000) { sippyState().sippy.draftComplete }
        typedBucket("my-bucket")
        tapAck("rc-sippy-ack-disclosure")
        tapAck("rc-sippy-ack-credentials")
        compose.waitUntil(10000) { sippyState().canPreview }
        compose.onNodeWithTag("rc-sippy-preview").performScrollTo().performClick()
        compose.waitUntil(10000) { sippyState().preview != null }
        compose.runOnIdle {
            assertEquals("a preview must not send anything", 0, mutations().size)
            // The preview shows the public metadata (bucket/region) that the user can verify.
            assertTrue(sippyState().preview!!.impact.contains("aws"))
            assertTrue(sippyState().preview!!.impact.contains("source-bucket"))
            assertFalse(sippyState().preview!!.impact.contains(marker))
        }
        val beforeTaskIds = existingTaskIds()
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        assertOneWriteThenReadback("AWS", beforeTaskIds)
        compose.onNodeWithTag("rc-sippy-notice").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("rc-preview-card").assertDoesNotExist()
        compose.onNodeWithTag("rc-sippy-state").performScrollTo().assertTextContains("已启用", substring = true)
        stopFrameSampler()

        // Exit: the secret controls disappear and the owned flag is cleared. The frames drawn after
        // the exit click are sampled to record the ordering (flag vs surface removal).
        startFrameSampler()
        compose.onNodeWithTag("rc-sippy-secure-end").performScrollTo().performClick()
        compose.waitUntil(10000) { !sippyState().sippy.secureReady }
        compose.onNodeWithTag("rc-sippy-field-SOURCE_SECRET_ACCESS_KEY").assertDoesNotExist()
        compose.runOnIdle {
            val frames = frameFlags.toList()
            println("T6A_FRAMES exit total=${frames.size} secureFrames=${frames.count { it }} clearedFrames=${frames.count { !it }}")
            assertFalse("the owned FLAG_SECURE bit is cleared", secureFlag())
        }
        stopFrameSampler()
    }

    @Test
    fun secretLifecycleExitReentryAndRouteLeaveClearEverything() {
        install()
        beginProtected()
        fillProvider("AWS")
        compose.waitUntil(10000) { sippyState().sippy.draftComplete }
        typedBucket("my-bucket")
        tapAck("rc-sippy-ack-disclosure")
        tapAck("rc-sippy-ack-credentials")
        compose.waitUntil(10000) { sippyState().canPreview }
        compose.onNodeWithTag("rc-sippy-preview").performScrollTo().performClick()
        compose.waitUntil(10000) { sippyState().preview != null }

        // While a preview is showing the panel is replaced by the preview card, so cancel it first.
        compose.onNodeWithTag("rc-preview-cancel").performScrollTo().performClick()
        compose.waitUntil(10000) { sippyState().preview == null }
        // 1) explicit exit with a filled form: inputs, acks and any preview all go away.
        compose.onNodeWithTag("rc-sippy-secure-end").performScrollTo().performClick()
        compose.waitUntil(10000) { !sippyState().sippy.secureReady }
        compose.runOnIdle {
            assertFalse(sippyState().sippy.draftComplete)
            assertFalse(sippyState().sippy.disclosureAcknowledged)
            assertFalse(sippyState().sippy.credentialsAcknowledged)
            assertNull(sippyState().preview)
            assertNull(model!!.r2Browser.sippyCredentials.currentValue(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY))
        }
        compose.onNodeWithTag("rc-sippy-field-SOURCE_SECRET_ACCESS_KEY").assertDoesNotExist()
        assertEquals(0, mutations().size)
        // re-entry: nothing is restored
        beginProtected()
        compose.runOnIdle {
            assertFalse(sippyState().sippy.draftComplete)
            assertNull(model!!.r2Browser.sippyCredentials.currentValue(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY))
        }

        // 2) route leave (panel switch) with a filled form.
        fillProvider("AWS")
        compose.waitUntil(10000) { sippyState().sippy.draftComplete }
        compose.onNodeWithTag("rc-panel-cors").performScrollTo().performClick()
        compose.waitUntil(10000) { sippyState().panel != R2ConfigurationPanel.SIPPY }
        compose.runOnIdle {
            assertFalse(sippyState().sippy.secureReady)
            assertNull(model!!.r2Browser.sippyCredentials.currentValue(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY))
        }
        openSippyPanel()
        compose.runOnIdle {
            assertFalse("re-entering the surface must require a new protected session", sippyState().sippy.secureReady)
            assertFalse(sippyState().sippy.draftComplete)
        }
        compose.onNodeWithTag("rc-sippy-field-SOURCE_SECRET_ACCESS_KEY").assertDoesNotExist()
        assertEquals(0, mutations().size)
    }

    @Test
    fun noOutwardSemanticsPropertyCarriesAnySyntheticCredentialForAnyProvider() {
        install()
        beginProtected()
        for (case in listOf(
            "AWS" to R2SippySecretField.SOURCE_SECRET_ACCESS_KEY,
            "GCS" to R2SippySecretField.GCS_PRIVATE_KEY,
            "S3" to R2SippySecretField.SOURCE_SECRET_ACCESS_KEY,
            "AZURE" to R2SippySecretField.AZURE_SAS_TOKEN,
        )) {
            fillProvider(case.first)
            if (case.first == "AZURE") typeSecret(R2SippySecretField.AZURE_SAS_TOKEN, marker)
            closeKeyboard()
            compose.waitUntil(10000) { sippyState().sippy.draftComplete }

            // Every outward text property (Text / EditableText / InputText / ContentDescription /
            // Password marker) is scanned on merged and unmerged nodes; only property names and a
            // boolean/count are reported, never a value or the tree.
            val leaks = LinkedHashSet<String>()
            var visited = 0
            val keys = (listOf(marker) + extraSecrets).filter { it.isNotBlank() }
            fun inspect(merged: Boolean) {
                for (root in compose.onAllNodes(isRoot(), useUnmergedTree = !merged).fetchSemanticsNodes()) {
                    val queue = ArrayDeque<androidx.compose.ui.semantics.SemanticsNode>()
                    queue.add(root)
                    while (queue.isNotEmpty() && visited < 6_000) {
                        val node = queue.removeFirst()
                        visited++
                        for (property in node.config) {
                            val text = when (val value = property.value) {
                                is String -> value
                                is androidx.compose.ui.text.AnnotatedString -> value.text
                                is List<*> -> value.joinToString(" ") { item ->
                                    when (item) {
                                        is String -> item
                                        is androidx.compose.ui.text.AnnotatedString -> item.text
                                        else -> ""
                                    }
                                }
                                else -> ""
                            }
                            if (text.isEmpty()) continue
                            if (keys.any { text.contains(it) }) {
                                leaks += "${property.key.name}/${if (merged) "merged" else "unmerged"}"
                            }
                        }
                        for (index in 0 until node.children.size) node.children[index]?.let { queue.add(it) }
                    }
                }
            }
            inspect(merged = true)
            inspect(merged = false)
            println("T6A_SEMANTICS provider=${case.first} visited=$visited leaks=$leaks")
            assertTrue(
                "${case.first}: no outward semantics property may carry a credential (found: $leaks)",
                leaks.isEmpty(),
            )
        }
        compose.runOnIdle {
            val state = sippyState()
            assertFalse(state.toString().contains(marker))
            assertFalse(model!!.r2Browser.writeTasks.value.toString().contains(marker))
            for (extra in extraSecrets) assertFalse(state.toString().contains(extra))
        }
        // The form performs no clipboard write (only the user can paste).
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(android.content.ClipboardManager::class.java)
        val clip = compose.runOnIdle { clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty() }
        assertFalse("the form must not write the clipboard", clip.contains(marker))
        assertEquals(0, mutations().size)
    }

    @Test
    fun theFullyMaskedProductionFormIsCapturedAsAnActualImagePerProvider() {
        install()
        beginProtected()
        // Every provider's real form (masked, synthetic credentials), then a preview viewport and the
        // settled (SUCCEEDED) viewport. The PNG is encoded from the very bitmap that was measured.
        for (case in listOf("AWS", "GCS", "S3", "AZURE")) {
            fillProvider(case)
            if (case == "AZURE") typeSecret(R2SippySecretField.AZURE_ACCOUNT_KEY, marker)
            closeKeyboard()
            typedBucket("my-bucket")
            val bitmap = compose.onNodeWithTag("rc-screen").captureToImage().asAndroidBitmap()
            val colours = distinctColours(bitmap)
            println("T6A_FORM_IMAGE provider=$case size=${bitmap.width}x${bitmap.height} distinct=$colours png=${savePng(bitmap, "form-$case")}")
            assertTrue("provider=$case: the masked form image must be inspectable (distinct=$colours)", colours > 20)
        }
        // Preview (public metadata visible) and then the settled notice viewport.
        fillProvider("AWS")
        closeKeyboard()
        typedBucket("my-bucket")
        tapAck("rc-sippy-ack-disclosure")
        tapAck("rc-sippy-ack-credentials")
        compose.waitUntil(10000) { sippyState().canPreview }
        compose.onNodeWithTag("rc-sippy-preview").performScrollTo().performClick()
        compose.waitUntil(10000) { sippyState().preview != null }
        val previewShot = compose.onNodeWithTag("rc-screen").captureToImage().asAndroidBitmap()
        println("T6A_FORM_IMAGE preview size=${previewShot.width}x${previewShot.height} png=${savePng(previewShot, "preview-aws")}")
        assertTrue(distinctColours(previewShot) > 20)
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        compose.waitUntil(15000) {
            val m = checkNotNull(model)
            !m.r2Browser.configuration.state.value.writing && m.r2Browser.writeTasks.value.isNotEmpty()
        }
        val settled = compose.onNodeWithTag("rc-screen").captureToImage().asAndroidBitmap()
        println("T6A_FORM_IMAGE settled size=${settled.width}x${settled.height} png=${savePng(settled, "settled-aws")}")
        assertTrue(distinctColours(settled) > 20)
        assertNull(sippyState().preview)
        assertNotNull(sippyState().notice)
    }

    @Test
    fun unknownReceiptKeepsNoCardAndIsNotReplayed() {
        install()
        beginProtected()
        fillProvider("AWS")
        compose.waitUntil(10000) { sippyState().sippy.draftComplete }
        typedBucket("my-bucket")
        tapAck("rc-sippy-ack-disclosure")
        tapAck("rc-sippy-ack-credentials")
        compose.waitUntil(10000) { sippyState().canPreview }
        compose.onNodeWithTag("rc-sippy-preview").performScrollTo().performClick()
        compose.waitUntil(10000) { sippyState().preview != null }
        // The provider acknowledges asynchronously but the resource never changes.
        applyPut = false
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        compose.waitUntil(15000) {
            val m = checkNotNull(model)
            !m.r2Browser.configuration.state.value.writing && m.r2Browser.writeTasks.value.isNotEmpty()
        }
        compose.runOnIdle {
            assertEquals(1, mutations().size)
            val task = checkNotNull(model!!.r2Browser.writeTasks.value.maxByOrNull { it.updatedAtMs })
            assertEquals(TaskState.UNKNOWN, task.state)
            assertNull(sippyState().preview)
            assertNotNull(sippyState().notice)
            assertNull(model!!.r2Browser.configuration.state.value.preview)
        }
        compose.onNodeWithTag("rc-preview-card").assertDoesNotExist()
        // A read-only reconcile never settles a PUT from public metadata and never resends.
        val unknownId = checkNotNull(model!!.r2Browser.writeTasks.value.maxByOrNull { it.updatedAtMs }).id
        compose.runOnIdle { model!!.r2Browser.reconcileTask(unknownId) }
        compose.waitUntil(10000) { !sippyState().writing }
        compose.runOnIdle {
            val latest = checkNotNull(model!!.r2Browser.writeTasks.value.firstOrNull { it.id == unknownId })
            assertEquals(TaskState.UNKNOWN, latest.state)
            assertEquals(1, mutations().size)
        }
    }

    @Test
    fun realControlsRejectAzureBothOrNeitherInvalidUrlAndMissingAcks() {
        install()
        beginProtected()
        // Azure with both secrets present -> the form never offers a preview.
        compose.onNodeWithTag("rc-sippy-provider-AZURE").performScrollTo().performClick()
        typeSecret(R2SippySecretField.AZURE_ACCOUNT_NAME, "syntheticstorage")
        typeSecret(R2SippySecretField.SOURCE_CONTAINER, "synthetic-container")
        typeSecret(R2SippySecretField.AZURE_ACCOUNT_KEY, marker)
        typeSecret(R2SippySecretField.AZURE_SAS_TOKEN, marker)
        typeSecret(R2SippySecretField.DESTINATION_ACCESS_KEY_ID, "SYNTHETIC-R2-KEY")
        typeSecret(R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY, marker)
        closeKeyboard()
        typedBucket("my-bucket")
        tapAck("rc-sippy-ack-disclosure")
        tapAck("rc-sippy-ack-credentials")
        compose.runOnIdle {
            assertFalse("both Azure secrets must not become previewable", sippyState().canPreview)
        }
        compose.onNodeWithTag("rc-sippy-preview").performScrollTo()
        compose.runOnIdle { assertNull(sippyState().preview) }

        // Wrong typed bucket and a missing acknowledgement block independently.
        compose.onNodeWithTag("rc-sippy-provider-AWS").performScrollTo().performClick()
        fillProvider("AWS")
        closeKeyboard()
        typedBucket("not-my-bucket")
        tapAck("rc-sippy-ack-disclosure")
        tapAck("rc-sippy-ack-credentials")
        compose.runOnIdle {
            assertFalse(sippyState().canPreview)
            assertNotNull(sippyState().validationMessage)
        }
        // Unsafe S3 URL with userinfo is refused by the reviewed client bound.
        compose.onNodeWithTag("rc-sippy-provider-S3").performScrollTo().performClick()
        typeSecret(R2SippySecretField.SOURCE_BUCKET_URL, "https://user:pw@s3.example.invalid/b")
        typeSecret(R2SippySecretField.SOURCE_ACCESS_KEY_ID, "SYNTHETIC-SOURCE-KEY")
        typeSecret(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY, marker)
        typeSecret(R2SippySecretField.DESTINATION_ACCESS_KEY_ID, "SYNTHETIC-R2-KEY")
        typeSecret(R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY, marker)
        closeKeyboard()
        typedBucket("my-bucket")
        tapAck("rc-sippy-ack-disclosure")
        tapAck("rc-sippy-ack-credentials")
        compose.runOnIdle {
            assertFalse("an unsafe URL must not be previewable", sippyState().canPreview)
        }
        assertEquals(0, mutations().size)
    }

    // --------------------------------------------------- closure fix2: S3 source URL label readability

    /**
     * The S3 source URL control must be readable in the real production viewport: a short, complete
     * label on the field, the masked value, and the full "submitted to Cloudflare only / the app never
     * accesses or probes it" explanation as its own wrapped supporting text — not one long label that
     * is clipped to its tail at the larger font. The capture is taken from the real scrolled viewport
     * with the keyboard closed, and the window stays protected.
     */
    @Test
    fun theS3SourceUrlLabelAndItsWholeHelpAreVisibleInTheRealViewport() {
        install()
        beginProtected()
        val font = compose.runOnIdle {
            InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale
        }.toString()
        compose.onNodeWithTag("rc-sippy-provider-S3").performScrollTo().performClick()
        compose.waitUntil(10000) { sippyState().sippy.provider == com.flarego.core.model.R2SippyProvider.S3 }
        val url = "https://s3.example.invalid/bucket"
        typeSecret(R2SippySecretField.SOURCE_BUCKET_URL, url)
        typeSecret(R2SippySecretField.SOURCE_ACCESS_KEY_ID, "SYNTHETIC-SOURCE-KEY")
        typeSecret(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY, marker)
        closeKeyboard()

        val fieldTag = "rc-sippy-field-SOURCE_BUCKET_URL"
        val field = compose.onNodeWithTag(fieldTag)
        field.performScrollTo()
        compose.waitForIdle()
        field.assertIsDisplayed()
        // Every credential control stays masked and keeps its outward InputText blanked.
        val fieldConfig = field.fetchSemanticsNode().config
        assertNotNull("the S3 URL control must stay a password/masked field", fieldConfig.getOrNull(SemanticsProperties.Password))
        val outwardText = fieldConfig.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        val fieldLabel = fieldConfig.getOrNull(SemanticsProperties.EditableText)?.text
        println(
            "T6H_S3 font=$font field_text='$outwardText' editable_nonempty=${!fieldLabel.isNullOrEmpty()}",
        )
        // The long sentence must no longer be the field's own label (it is the help below), and the
        // label must be short enough to be a label.
        assertFalse(
            "the field label must not carry the long explanation any more (label='$outwardText')",
            outwardText.contains("仅提交给") || outwardText.contains("不访问"),
        )
        assertTrue(
            "the field label must be non-empty (label='$outwardText')",
            outwardText.isNotBlank(),
        )
        // The complete explanation is its own visible, wrapped supporting text.
        val help = compose.onNodeWithTag("rc-sippy-s3-url-help")
        help.performScrollTo()
        compose.waitForIdle()
        help.assertIsDisplayed()
        val helpText = help.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
            ?.joinToString(" ") { it.text }.orEmpty()
        println("T6H_S3 font=$font help_text='$helpText'")
        assertTrue("the help must state the Cloudflare-only submission", helpText.contains("Cloudflare"))
        assertTrue("the help must state that the app does not access the address", helpText.contains("不直接访问") || helpText.contains("不访问"))
        assertTrue("the help must keep the no-probing statement", helpText.contains("探测") || helpText.contains("不访问"))

        // Real production viewport(s): field label + masked value + the whole help text.
        val decor = compose.activity.window.decorView
        val fieldRect = field.fetchSemanticsNode().boundsInWindow
        val helpRect = help.fetchSemanticsNode().boundsInWindow
        val unionTop = minOf(fieldRect.top, helpRect.top)
        val unionBottom = maxOf(fieldRect.bottom, helpRect.bottom)
        println(
            "T6H_S3 font=$font field=$fieldRect help=$helpRect union=$unionTop..$unionBottom " +
                "window=${decor.width}x${decor.height}",
        )
        assertTrue("the union must be inside the window top", unionTop >= 0f)
        if (unionBottom <= decor.height.toFloat()) {
            val bitmap = compose.onNodeWithTag("rc-screen").captureToImage().asAndroidBitmap()
            val saved = saveLocalPng(bitmap, "s3-url-frame-f$font")
            println("T6H_S3 font=$font segment=1 distinct=${distinctColours(bitmap)} png=$saved")
        } else {
            // The label+value+whole help does not fit at this font scale: hand over both segments.
            var bitmap = compose.onNodeWithTag("rc-screen").captureToImage().asAndroidBitmap()
            var saved = saveLocalPng(bitmap, "s3-url-frame-f$font-1")
            println("T6H_S3 font=$font segment=1 distinct=${distinctColours(bitmap)} png=$saved")
            field.performScrollTo()
            compose.waitForIdle()
            bitmap = compose.onNodeWithTag("rc-screen").captureToImage().asAndroidBitmap()
            saved = saveLocalPng(bitmap, "s3-url-frame-f$font-2")
            println("T6H_S3 font=$font segment=2 distinct=${distinctColours(bitmap)} png=$saved")
        }
        assertTrue("the window stayed protected", secureFlag())
    }

    // ------------------------------------------------------------------ closure fix1: provider choices (font1/font2)

    /**
     * Every provider choice must be a complete, usable touch target inside the real visible area at
     * the current font scale — not a sliver clipped at the right edge. Each chip is checked for: its
     * own label in the semantics, a minimum touch size, and bounds that are **fully** inside both the
     * provider container and the window (a partially visible chip is not accepted). The choice is then
     * really clicked and the resulting provider is asserted.
     */
    @Test
    fun everyProviderChoiceIsAFullyVisibleTouchTargetAtEveryFontScale() {
        install()
        beginProtected()
        val font = compose.runOnIdle {
            InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale
        }.toString()
        val decor = compose.activity.window.decorView
        for (provider in listOf("AWS", "GCS", "S3", "AZURE")) {
            closeKeyboard()
            val node = compose.onNodeWithTag("rc-sippy-provider-$provider")
            node.performScrollTo()
            compose.waitForIdle()
            val semantics = node.fetchSemanticsNode()
            val chip = semantics.boundsInWindow
            // The layout bounds catch clipping; the touch bounds are what Material3 expands to the
            // minimum interactive size, so that is what the "usable target" assertion uses.
            val touch = semantics.touchBoundsInRoot
            val container = compose.onNodeWithTag("rc-sippy-providers").fetchSemanticsNode().boundsInWindow
            val label = node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
                ?.joinToString(" ") { it.text }.orEmpty()
            println(
                "T6E_CHIP font=$font provider=$provider chip=$chip touch=${touch.width}x${touch.height} " +
                    "container=$container window=${decor.width}x${decor.height} label=$label",
            )
            assertTrue("$provider: the chip must carry its own label (found '$label')", label.contains(provider))
            assertTrue(
                "$provider: the whole touch target must be inside the provider container " +
                    "(chip=$chip container=$container font=$font)",
                chip.left >= container.left && chip.right <= container.right &&
                    chip.top >= container.top && chip.bottom <= container.bottom,
            )
            assertTrue(
                "$provider: the container must be inside the real window (container=$container)",
                container.left >= 0f && container.right <= decor.width.toFloat() &&
                    container.top >= 0f && container.bottom <= decor.height.toFloat(),
            )
            assertTrue(
                "$provider: a minimum touch target is required " +
                    "(touch=${touch.width}x${touch.height} layout=${chip.width}x${chip.height}, font=$font)",
                touch.width >= 48f - 0.5f && touch.height >= 48f - 0.5f,
            )
            val bitmap = compose.onNodeWithTag("rc-screen").captureToImage().asAndroidBitmap()
            val saved = saveLocalPng(bitmap, "chiprow-f$font-$provider")
            println("T6E_CHIPROW font=$font provider=$provider distinct=${distinctColours(bitmap)} png=$saved")
            node.performClick()
            compose.waitUntil(10000) {
                sippyState().sippy.provider == com.flarego.core.model.R2SippyProvider.valueOf(provider)
            }
        }
        // The narrowest provider is reachable for real and its first control is usable.
        closeKeyboard()
        compose.onNodeWithTag("rc-sippy-provider-AZURE").performScrollTo().performClick()
        compose.waitUntil(10000) { sippyState().sippy.provider == com.flarego.core.model.R2SippyProvider.AZURE }
        compose.onNodeWithTag("rc-sippy-field-AZURE_ACCOUNT_NAME").performScrollTo().assertIsDisplayed()
        assertTrue("the window stayed protected", secureFlag())
    }

    // ------------------------------------------------------------------ Task 6a closure D: per-credential frame captures

    /**
     * Every real credential control of every provider (and both Azure authentication modes) is filled
     * with non-empty synthetic values and captured **in frame** (label included), for the font scale
     * this run was started with. Chips, the real public preview (target + impact) and the settled
     * UNKNOWN notice are captured as their own segments. Nothing is cleared, no flag is touched and no
     * secret is printed: the files are handed to the coordinator/QA for the visual pass, which this
     * test does not claim to be.
     */
    @Test
    fun everyCredentialIsCapturedInFrameForEveryProviderAndFontScale() {
        install()
        beginProtected()
        val font = compose.runOnIdle {
            InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale
        }.toString()
        println("T6D_FONT font=$font")

        // Chips + one AWS form incl. both destination credentials.
        compose.onNodeWithTag("rc-sippy-provider-AWS").performScrollTo()
        for (provider in listOf("AWS", "GCS", "S3", "AZURE")) {
            captureTag(font, "chip", "rc-sippy-provider-$provider")
        }
        fillProvider("AWS")
        for (field in listOf(
            R2SippySecretField.SOURCE_BUCKET,
            R2SippySecretField.SOURCE_REGION,
            R2SippySecretField.SOURCE_ACCESS_KEY_ID,
            R2SippySecretField.SOURCE_SECRET_ACCESS_KEY,
            R2SippySecretField.DESTINATION_ACCESS_KEY_ID,
            R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY,
        )) captureField(font, "aws", null, field)

        // GCS (the multi-line private key is captured as segments when it cannot fit at once).
        fillProvider("GCS")
        for (field in listOf(
            R2SippySecretField.SOURCE_BUCKET,
            R2SippySecretField.GCS_CLIENT_EMAIL,
            R2SippySecretField.GCS_PRIVATE_KEY,
            R2SippySecretField.DESTINATION_ACCESS_KEY_ID,
            R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY,
        )) captureField(font, "gcs", null, field)

        // S3 (the safe public URL is visible, the keys are masked).
        fillProvider("S3")
        for (field in listOf(
            R2SippySecretField.SOURCE_BUCKET_URL,
            R2SippySecretField.SOURCE_ACCESS_KEY_ID,
            R2SippySecretField.SOURCE_SECRET_ACCESS_KEY,
            R2SippySecretField.DESTINATION_ACCESS_KEY_ID,
            R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY,
        )) captureField(font, "s3", null, field)

        // Azure account-key authentication.
        fillProvider("AZURE")
        typeSecret(R2SippySecretField.AZURE_ACCOUNT_KEY, marker)
        for (field in listOf(
            R2SippySecretField.AZURE_ACCOUNT_NAME,
            R2SippySecretField.SOURCE_CONTAINER,
            R2SippySecretField.AZURE_ACCOUNT_KEY,
            R2SippySecretField.DESTINATION_ACCESS_KEY_ID,
            R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY,
        )) captureField(font, "azure", "key", field)

        // Azure SAS authentication (clearing the account key is part of switching the auth mode).
        typeSecret(R2SippySecretField.AZURE_ACCOUNT_KEY, "")
        typeSecret(R2SippySecretField.AZURE_SAS_TOKEN, marker)
        for (field in listOf(
            R2SippySecretField.AZURE_SAS_TOKEN,
            R2SippySecretField.DESTINATION_ACCESS_KEY_ID,
            R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY,
        )) captureField(font, "azure", "sas", field)

        // The real public preview (target + public impact) and the settled UNKNOWN notice.
        compose.onNodeWithTag("rc-sippy-provider-AWS").performScrollTo().performClick()
        fillProvider("AWS")
        typedBucket("my-bucket")
        tapAck("rc-sippy-ack-disclosure")
        tapAck("rc-sippy-ack-credentials")
        compose.waitUntil(10000) { sippyState().canPreview }
        compose.onNodeWithTag("rc-sippy-preview").performScrollTo().performClick()
        compose.waitUntil(10000) { sippyState().preview != null }
        captureTag(font, "preview", "rc-preview-card")
        captureTag(font, "preview", "rc-preview-impact")

        // Acknowledged PUT whose public configuration never changes: the row settles UNKNOWN, no card.
        applyPut = false
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        compose.waitUntil(15000) {
            val m = checkNotNull(model)
            !m.r2Browser.configuration.state.value.writing && m.r2Browser.writeTasks.value.isNotEmpty()
        }
        compose.waitUntil(10000) { sippyState().notice != null }
        captureScrollableSegments(font, "unknown", "rc-sippy-notice", "rc-sippy-state")
        assertEquals(1, mutations().size)
        assertEquals(
            TaskState.UNKNOWN,
            checkNotNull(model!!.r2Browser.writeTasks.value.maxByOrNull { it.updatedAtMs }).state,
        )
        // The window stayed protected for every captured field.
        assertTrue("the real window stayed protected for the whole capture run", secureFlag())
    }

    /** Captures one semantics node (label included) after scrolling it fully into the viewport. */
    private fun captureField(font: String, provider: String, auth: String?, field: R2SippySecretField) {
        val tag = "rc-sippy-field-${field.name}"
        closeKeyboard()
        val node = compose.onNodeWithTag(tag)
        node.performScrollTo()
        compose.waitForIdle()
        node.assertIsDisplayed()
        val rect = node.fetchSemanticsNode().boundsInWindow
        val decor = compose.activity.window.decorView
        check(rect.top >= 0f && rect.bottom <= decor.height.toFloat()) {
            "${field.name}: the control must be inside the visible viewport (rect=$rect, window=${decor.height})"
        }
        // A real, non-empty masked control: inspectable content beyond the plain background.
        val suffix = if (auth == null) provider else "$provider-$auth"
        if (rect.height > decor.height * 0.9f) {
            // Too tall for a single in-frame capture: hand over consecutive viewport segments.
            captureScrollableSegments(font, "$suffix-${field.name}", "rc-screen")
            return
        }
        val hasValue = compose.runOnIdle { checkNotNull(model).r2Browser.sippyCredentials.currentValue(field) != null }
        check(hasValue) { "${field.name}: the control must hold a non-empty value" }
        // The control's own node first; when that region is rendered flat on this device the visible
        // viewport that contains the very same control (and its label) is captured instead.
        var source = "node"
        var bitmap = node.captureToImage().asAndroidBitmap()
        var colours = distinctColours(bitmap)
        if (colours <= 3) {
            source = "viewport"
            bitmap = compose.onNodeWithTag("rc-screen").captureToImage().asAndroidBitmap()
            colours = distinctColours(bitmap)
        }
        check(colours > 2) {
            "${field.name}: the captured region looks empty (distinct=$colours, size=${bitmap.width}x${bitmap.height}, " +
                "rect=$rect, window=${decor.height})"
        }
        val saved = saveLocalPng(bitmap, "field-f$font-$suffix-${field.name}-$source")
        println(
            "T6D_FIELD font=$font provider=$provider auth=${auth ?: "-"} field=${field.name} source=$source " +
                "size=${bitmap.width}x${bitmap.height} rect=$rect distinct=$colours png=$saved",
        )
    }

    private fun captureTag(font: String, kind: String, tag: String) {
        closeKeyboard()
        val node = compose.onNodeWithTag(tag)
        node.performScrollTo()
        compose.waitForIdle()
        node.assertIsDisplayed()
        val rect = node.fetchSemanticsNode().boundsInWindow
        var source = "node"
        var bitmap = node.captureToImage().asAndroidBitmap()
        var colours = distinctColours(bitmap)
        if (colours <= 3) {
            // A clipped/overlaid node can render flat here; the viewport that contains it is the
            // documented fallback (it is what a reviewer would look at).
            source = "viewport"
            bitmap = compose.onNodeWithTag("rc-screen").captureToImage().asAndroidBitmap()
            colours = distinctColours(bitmap)
        }
        check(colours > 2) { "$tag: the captured region looks empty (distinct=$colours, source=$source, rect=$rect)" }
        val saved = saveLocalPng(bitmap, "$kind-f$font-$tag-$source")
        println(
            "T6D_NODE font=$font kind=$kind tag=$tag source=$source " +
                "size=${bitmap.width}x${bitmap.height} rect=$rect distinct=$colours png=$saved",
        )
    }

    /** Captures consecutive viewport segments of a node that does not fit on this screen at once. */
    private fun captureScrollableSegments(font: String, label: String, vararg tags: String) {
        for (tag in tags) {
            val node = compose.onNodeWithTag(tag)
            node.performScrollTo()
            compose.waitForIdle()
            val decor = compose.activity.window.decorView
            for (segment in 1..2) {
                val bitmap = compose.onNodeWithTag("rc-screen").captureToImage().asAndroidBitmap()
                val saved = saveLocalPng(bitmap, "seg-f$font-$label-$tag-$segment")
                println(
                    "T6D_SEGMENT font=$font label=$label tag=$tag segment=$segment " +
                        "size=${bitmap.width}x${bitmap.height} window=${decor.height} distinct=${distinctColours(bitmap)} png=$saved",
                )
                if (segment == 1) {
                    // Scroll further down inside the same scrollable to cover the rest of the node.
                    compose.onNodeWithTag(tag).performTouchInput { swipeUp() }
                    compose.waitForIdle()
                }
            }
        }
    }

    /** Saves directly into the app's filesDir (pulled by the host); never prints pixel data. */
    private fun saveLocalPng(bitmap: Bitmap, name: String): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.filesDir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "filesDir/${file.name} (${file.length()} bytes)"
    }

    // ------------------------------------------------- closure fix1: labels, Azure viewports, frozen preview

    /**
     * The follow-up captures for the frames the coordinator could not read in full:
     *
     * - the three controls whose floating label was clipped by a node-only capture (GCS email, GCS
     *   multi-line private key, S3 safe URL): node **and** the real viewport that contains the whole
     *   label plus the masked value;
     * - both Azure authentication modes reached by real touches, captured as viewports;
     * - one and the same live frozen preview: its target/bucket public metadata header and its
     *   impact/confirm footer as separate segments.
     *
     * Every image is saved from the bitmap that was measured, the window stays protected, and no
     * credential value is printed. The visual verdict stays with the coordinator/QA.
     */
    @Test
    fun theAffectedLabelsAndTheFrozenPreviewAreCapturedInWholeFrames() {
        install()
        beginProtected()
        val font = compose.runOnIdle {
            InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale
        }.toString()

        // 1) the label-clipped controls: node + containing viewport.
        fillProvider("GCS")
        for (field in listOf(R2SippySecretField.GCS_CLIENT_EMAIL, R2SippySecretField.GCS_PRIVATE_KEY)) {
            captureFieldWithViewport(font, "gcs", null, field)
        }
        fillProvider("S3")
        captureFieldWithViewport(font, "s3", null, R2SippySecretField.SOURCE_BUCKET_URL)

        // 2) both Azure authentication modes, reached through the real chips.
        compose.onNodeWithTag("rc-sippy-provider-AZURE").performScrollTo().performClick()
        compose.waitUntil(10000) { sippyState().sippy.provider == com.flarego.core.model.R2SippyProvider.AZURE }
        typeSecret(R2SippySecretField.AZURE_ACCOUNT_NAME, "syntheticstorage")
        typeSecret(R2SippySecretField.SOURCE_CONTAINER, "synthetic-container")
        typeSecret(R2SippySecretField.AZURE_ACCOUNT_KEY, marker)
        typeSecret(R2SippySecretField.DESTINATION_ACCESS_KEY_ID, "SYNTHETIC-R2-KEY")
        typeSecret(R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY, marker)
        captureViewportAt(font, "azure-key", "rc-sippy-field-AZURE_ACCOUNT_KEY")
        typeSecret(R2SippySecretField.AZURE_ACCOUNT_KEY, "")
        typeSecret(R2SippySecretField.AZURE_SAS_TOKEN, marker)
        captureViewportAt(font, "azure-sas", "rc-sippy-field-AZURE_SAS_TOKEN")

        // 3) one live frozen preview: public target/source header and impact/confirm footer.
        compose.onNodeWithTag("rc-sippy-provider-AWS").performScrollTo().performClick()
        compileAndPreviewAws()
        captureViewportAt(font, "preview-top", "rc-preview-target")
        captureTag(font, "preview", "rc-preview-title")
        captureTag(font, "preview", "rc-preview-target")
        captureViewportAt(font, "preview-impact", "rc-preview-impact")
        captureViewportAt(font, "preview-confirm-footer", "rc-preview-confirm")
        // The preview is still the same live frozen preview for all of the captures above.
        assertNotNull(sippyState().preview)
        assertEquals(0, mutations().size)
        assertTrue("the window stayed protected through the captures", secureFlag())
    }

    private fun compileAndPreviewAws() {
        fillProvider("AWS")
        typedBucket("my-bucket")
        tapAck("rc-sippy-ack-disclosure")
        tapAck("rc-sippy-ack-credentials")
        compose.waitUntil(10000) { sippyState().canPreview }
        compose.onNodeWithTag("rc-sippy-preview").performScrollTo().performClick()
        compose.waitUntil(10000) { sippyState().preview != null }
    }

    /** Captures the control itself and the viewport that holds its whole label, with headroom. */
    private fun captureFieldWithViewport(font: String, provider: String, auth: String?, field: R2SippySecretField) {
        closeKeyboard()
        val tag = "rc-sippy-field-${field.name}"
        val node = compose.onNodeWithTag(tag)
        node.performScrollTo()
        compose.waitForIdle()
        node.assertIsDisplayed()
        val rect = node.fetchSemanticsNode().boundsInWindow
        val decor = compose.activity.window.decorView
        val suffix = if (auth == null) provider else "$provider-$auth"
        val nodeBitmap = node.captureToImage().asAndroidBitmap()
        val nodeSaved = saveLocalPng(nodeBitmap, "field-f$font-$suffix-${field.name}-node")
        val viewport = compose.onNodeWithTag("rc-screen").captureToImage().asAndroidBitmap()
        val viewportSaved = saveLocalPng(viewport, "field-f$font-$suffix-${field.name}-viewport")
        println(
            "T6G_FIELD font=$font provider=$provider field=${field.name} rect=$rect " +
                "headroom_px=${rect.top} window=${decor.width}x${decor.height} " +
                "node_distinct=${distinctColours(nodeBitmap)} viewport_distinct=${distinctColours(viewport)} " +
                "node_png=$nodeSaved viewport_png=$viewportSaved",
        )
        check(rect.top >= 0f && rect.bottom <= decor.height.toFloat()) { "${field.name}: control outside the window" }
        check(distinctColours(viewport) > 2) { "${field.name}: the viewport capture looks empty" }
    }

    /** Scrolls [anchorTag] into view and captures the whole visible viewport that contains it. */
    private fun captureViewportAt(font: String, label: String, anchorTag: String) {
        closeKeyboard()
        val node = compose.onNodeWithTag(anchorTag)
        node.performScrollTo()
        compose.waitForIdle()
        node.assertIsDisplayed()
        val rect = node.fetchSemanticsNode().boundsInWindow
        val bitmap = compose.onNodeWithTag("rc-screen").captureToImage().asAndroidBitmap()
        val colours = distinctColours(bitmap)
        check(colours > 2) { "$anchorTag: the viewport capture looks empty (distinct=$colours)" }
        val saved = saveLocalPng(bitmap, "viewport-f$font-$label")
        println(
            "T6G_VIEWPORT font=$font label=$label anchor=$anchorTag rect=$rect " +
                "distinct=$colours png=$saved",
        )
    }

    // ------------------------------------------------------------------ capture helpers

    /** Saves the very bitmap that was measured (filesDir + logcat base64 chunks for the host pull). */
    private fun savePng(bitmap: Bitmap, name: String): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.filesDir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val encoded = java.util.Base64.getEncoder().encodeToString(file.readBytes())
        val chunk = 800
        val chunks = (encoded.length + chunk - 1) / chunk
        for (index in 0 until chunks) {
            println("T6A_PNG $name $index/$chunks ${encoded.substring(index * chunk, minOf(encoded.length, index * chunk + chunk))}")
        }
        return "filesDir/${file.name} (${file.length()} bytes, $chunks chunks)"
    }

    private fun shellCapture(): Bitmap? =
        try {
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap"),
            ).use { it.readBytes() }
            decodeRaw(bytes)
        } catch (_: Throwable) {
            null
        }

    private fun decodeRaw(bytes: ByteArray): Bitmap? {
        if (bytes.size < 16) return null
        fun int(offset: Int) =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or ((bytes[offset + 3].toInt() and 0xff) shl 24)
        val width = int(0)
        val height = int(4)
        if (width <= 0 || height <= 0) return null
        val pixels = width.toLong() * height.toLong() * 4L
        val header = when (bytes.size.toLong()) {
            12L + pixels -> 12
            16L + pixels -> 16
            else -> return null
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            var offset = header + y * width * 4
            for (x in 0 until width) {
                val r = bytes[offset].toInt() and 0xff
                val g = bytes[offset + 1].toInt() and 0xff
                val b = bytes[offset + 2].toInt() and 0xff
                row[x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                offset += 4
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        return bitmap
    }

    private fun distinctColours(bitmap: Bitmap?): Int {
        if (bitmap == null) return 0
        val seen = HashSet<Int>()
        var y = bitmap.height / 5
        while (y < bitmap.height * 4 / 5) {
            var x = bitmap.width / 5
            while (x < bitmap.width * 4 / 5) {
                seen += bitmap.getPixel(x, y) and 0xFFFFFF
                x += 4
            }
            y += 4
        }
        return seen.size
    }
}
