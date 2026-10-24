package com.flarego

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.core.cloudflare.HttpBody
import com.flarego.core.cloudflare.HttpRequest
import com.flarego.core.cloudflare.HttpResponse
import com.flarego.core.cloudflare.HttpTransport
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Task 6a fix2: the **real** Activity recreation path plus the two send-timing cases that the old
 * core-only evidence could not cover.
 *
 * The VM and the app graph stay alive (the test host owns them, like `FlareGoViewModel` survives a
 * configuration change); every Activity instance gets its own **production** `AndroidSecureWindow`,
 * and the production order is reproduced: old `onSecureWindowClosed` + `close()` during destruction,
 * new `attachSecureWindow` on the new window. Interactions before the recreation are real Compose
 * clicks; after the recreation the same production root intents drive the route (the composition is
 * not re-created by the test rule, which is stated as a limit, not hidden).
 */
class R2SippyRecreateTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = "e".repeat(32)
    private var model: FlareGoViewModel? = null
    private var initial = emptySet<String>()
    private val requests = Collections.synchronizedList(mutableListOf<HttpRequest>())
    private var enabled = false
    private var lastSource: String? = null
    private var lastDestination: String? = null
    private var applyPut = true
    /** Non-null while a PUT is held open: the admitted transport is suspended at the network step. */
    private var putGate: CountDownLatch? = null
    private var putEntered: CountDownLatch? = null
    /** Non-null while the pre-send configuration GET is held open (before any PUT is sent). */
    private var preSendGetGate: CountDownLatch? = null
    private var preSendGetEntered: CountDownLatch? = null
    private var holdNextSippyGet = false
    private val marker = "SYNTHETIC-RECREATE-MARKER-4d1"

    private val transfers = object : TransferPort {
        override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead {
            requests += request
            sink.write(byteArrayOf(1, 2, 3), 0, 3)
            sink.close()
            return HttpResponseHead(200, emptyMap(), 3)
        }
    }

    private fun ok(result: String) = """{"success":true,"errors":[],"messages":[],"result":$result}"""

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
                    request.path.endsWith("/r2/buckets/my-bucket/sippy") && request.method == "GET" -> {
                        if (holdNextSippyGet) {
                            holdNextSippyGet = false
                            preSendGetEntered?.countDown()
                            // The gate suspends a real IO worker, never the UI thread (a test that
                            // recreates the Activity needs the main thread free while the call hangs).
                            withContext(Dispatchers.IO) { preSendGetGate?.await(180, TimeUnit.SECONDS) }
                        }
                        Json.parseToJsonElement(sippyJson()).toString()
                    }
                    request.path.endsWith("/r2/buckets/my-bucket/sippy") && request.method == "PUT" -> {
                        val payload = request.payload
                        check(payload is HttpBody.SensitiveJson)
                        putEntered?.countDown()
                        // The gate suspends a real IO worker, never the UI thread.
                        withContext(Dispatchers.IO) { putGate?.await(180, TimeUnit.SECONDS) }
                        val body = Json.parseToJsonElement(payload.consumeBytes().decodeToString()).jsonObject
                        if (applyPut) {
                            val source = body.getValue("source").jsonObject
                            lastSource =
                                """{"provider":"aws","bucket":"${source.getValue("bucket").jsonPrimitive.content}","region":"${source.getValue("region").jsonPrimitive.content}"}"""
                            lastDestination =
                                """{"accessKeyId":"${body.getValue("destination").jsonObject.getValue("accessKeyId").jsonPrimitive.content}","account":"$account","bucket":"my-bucket","provider":"r2"}"""
                            enabled = true
                        }
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
        compose.onNodeWithTag("rb-config-my-bucket").performScrollTo().performClick()
        compose.waitUntil(10000) { m.r2Browser.configuration.state.value.open }
        compose.onNodeWithTag("rc-panel-tabs").performScrollToNode(hasTestTag("rc-panel-sippy"))
        compose.onNodeWithTag("rc-panel-sippy").assertIsDisplayed().performClick()
        compose.waitUntil(10000) { m.r2Browser.configuration.state.value.panel == R2ConfigurationPanel.SIPPY }
        compose.waitUntil(10000) { m.r2Browser.configuration.state.value.sippy.snapshot != null }
        installHostLifecycle(m)
    }

    /**
     * The test host maps the production Activity lifecycle onto the app's secure window, for **every**
     * Activity instance of this scenario (the first one is installed by [install] itself):
     *
     * - a new `ComponentActivity` gets its own production `AndroidSecureWindow`, is attached, and then
     *   hosts the same `FlareGoApp` root with the same ViewModel (this is `Activity.setContent`, not a
     *   second `composeRule.setContent`);
     * - a destroyed one runs the production order `onSecureWindowClosed` → `closeForTeardown`.
     *
     * This is a host mapping, not a claim about `MainActivity` end to end.
     */
    private fun installHostLifecycle(m: FlareGoViewModel) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        firstHost = compose.activity
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity.javaClass != ComponentActivity::class.java) return
                if (activity === firstHost) return
                val host = activity as ComponentActivity
                val port = AndroidSecureWindow(host.window)
                hostPorts[activity] = port
                m.attachSecureWindow(port)
                host.setContent {
                    FlareGoApp(m.controller, { _, _, _ -> }, m::disconnect, {}, r2Browser = m.r2Browser)
                }
            }

            override fun onActivityPreDestroyed(activity: Activity) {
                val port = hostPorts.remove(activity) ?: return
                // Production order: drop readiness/material of *this* generation, then teardown.
                m.onSecureWindowClosed(port)
                port.closeForTeardown()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        hostCallbacks = callbacks
    }

    private val owner = androidx.lifecycle.ViewModelStore()

    /** The production window of every host Activity instance, keyed by the instance. */
    private val hostPorts = java.util.Collections.synchronizedMap(
        java.util.IdentityHashMap<Activity, AndroidSecureWindow>(),
    )
    private var hostCallbacks: Application.ActivityLifecycleCallbacks? = null
    /** The rule's own first host Activity (its content was installed by [install], not by the callback). */
    private var firstHost: Activity? = null

    private fun state() = checkNotNull(model).r2Browser.configuration.state.value
    private fun mutations() = requests.filter { it.method != "GET" }

    private fun fillByIntent() {
        val m = checkNotNull(model)
        val browser = m.r2Browser
        browser.onConfigurationIntent(R2ConfigurationIntent.SippyProviderSelected(com.flarego.core.model.R2SippyProvider.AWS))
        for ((field, value) in listOf(
            R2SippySecretField.SOURCE_BUCKET to "source-bucket",
            R2SippySecretField.SOURCE_REGION to "us-east-1",
            R2SippySecretField.SOURCE_ACCESS_KEY_ID to "SYNTHETIC-SOURCE-KEY",
            R2SippySecretField.SOURCE_SECRET_ACCESS_KEY to marker,
            R2SippySecretField.DESTINATION_ACCESS_KEY_ID to "SYNTHETIC-R2-KEY",
            R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY to marker,
        )) {
            browser.onConfigurationIntent(R2ConfigurationIntent.SippyFieldChanged(field, R2SippySecretInput.of(value)))
        }
        browser.onConfigurationIntent(R2ConfigurationIntent.SippyTypedBucketChanged("my-bucket"))
        browser.onConfigurationIntent(R2ConfigurationIntent.SippyDisclosureAcknowledgedChanged(true))
        browser.onConfigurationIntent(R2ConfigurationIntent.SippyCredentialsAcknowledgedChanged(true))
    }

    private fun typeSecret(field: R2SippySecretField, value: String) {
        val node = compose.onNodeWithTag("rc-sippy-field-${field.name}")
        node.performScrollTo()
        node.performClick()
        node.performTextReplacement(value)
        compose.runOnIdle {
            val current = checkNotNull(model).r2Browser.sippyCredentials.currentValue(field)
            check(if (value.isEmpty()) current == null else current != null) {
                "the owner did not receive ${field.name} through the form"
            }
        }
    }

    private fun closeKeyboard() {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
    }

    /** The real form of the *current* host tree (AWS), through real clicks and real text input. */
    private fun fillByUi() {
        compose.onNodeWithTag("rc-sippy-provider-AWS").performScrollTo().performClick()
        for ((field, value) in listOf(
            R2SippySecretField.SOURCE_BUCKET to "source-bucket",
            R2SippySecretField.SOURCE_REGION to "us-east-1",
            R2SippySecretField.SOURCE_ACCESS_KEY_ID to "SYNTHETIC-SOURCE-KEY",
            R2SippySecretField.SOURCE_SECRET_ACCESS_KEY to marker,
            R2SippySecretField.DESTINATION_ACCESS_KEY_ID to "SYNTHETIC-R2-KEY",
            R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY to marker,
        )) typeSecret(field, value)
        closeKeyboard()
    }

    private fun typedBucketByUi(value: String) {
        val node = compose.onNodeWithTag("rc-sippy-typed-bucket")
        node.performScrollTo()
        node.performClick()
        node.performTextReplacement(value)
        closeKeyboard()
    }

    private fun tapAck(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun previewByUi() {
        compose.waitUntil(10000) { state().canPreview }
        compose.onNodeWithTag("rc-sippy-preview").performScrollTo().performClick()
        compose.waitUntil(10000) { state().preview != null }
    }

    private fun existingTaskIds(): Set<String> =
        compose.runOnIdle { checkNotNull(model).r2Browser.writeTasks.value.map { it.id }.toSet() }

    private fun flagOf(window: android.view.Window): Boolean =
        (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

    /** Asserts this scenario has exactly one PUT, a read-back GET after it, and one SUCCEEDED task. */
    private fun assertOnePutThenReadback(beforeTaskIds: Set<String>) {
        compose.waitUntil(20000) {
            val m = checkNotNull(model)
            !m.r2Browser.configuration.state.value.writing && m.r2Browser.writeTasks.value.isNotEmpty()
        }
        compose.runOnIdle {
            val snapshot = requests.toList()
            val putIndex = snapshot.indexOfFirst { it.method == "PUT" && it.path.endsWith("/sippy") }
            assertTrue("exactly one PUT expected", putIndex >= 0 && snapshot.count { it.method == "PUT" } == 1)
            val readbackIndex = snapshot.indexOfLast { it.method == "GET" && it.path.endsWith("/sippy") }
            assertTrue("the read-back GET must come after the PUT", readbackIndex > putIndex)
            val created = model!!.r2Browser.writeTasks.value.filter { it.id !in beforeTaskIds }
            assertEquals("exactly one new task for this operation", 1, created.size)
            assertEquals(TaskState.SUCCEEDED, created.single().state)
            assertEquals("r2-put-bucket-sippy-config", created.single().actionKey)
            assertNull(state().preview)
            assertEquals(true, state().sippy.snapshot?.enabled)
            assertFalse(state().toString().contains(marker))
        }
    }

    private fun beginByClick() {
        compose.onNodeWithTag("rc-sippy-secure-begin").performScrollTo().performClick()
        compose.waitUntil(10000) { state().sippy.secureReady }
    }

    @After
    fun cleanup() {
        hostCallbacks?.let {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
            app.unregisterActivityLifecycleCallbacks(it)
        }
        hostCallbacks = null
        putGate?.countDown()
        compose.runOnIdle { model?.let { m -> m.controller.state.value.connections.filter { it.id !in initial }.forEach(m::disconnect) } }
        compose.waitUntil(10000) { model?.controller?.state?.value?.connections?.none { it.id !in initial } != false }
        compose.runOnIdle { owner.clear() }
    }

    @Test
    fun aRealRecreationRebuildsTheProductionHostAndTheNewTreeCompletesTheWrite() {
        install()
        beginByClick()
        fillByUi()
        compose.waitUntil(10000) { state().sippy.draftComplete }
        typedBucketByUi("my-bucket")
        tapAck("rc-sippy-ack-disclosure")
        tapAck("rc-sippy-ack-credentials")
        previewByUi()
        val oldPort = checkNotNull(securePortOf())
        val oldEpoch = compose.runOnIdle { state().sippy.secureEpoch }
        assertNotNull("the mounted surface captured its session epoch", oldEpoch)

        // A real Activity recreation. The host callbacks run the production order for the old window
        // (onSecureWindowClosed -> closeForTeardown) and install a new production owner + the same
        // FlareGoApp root on the new Activity.
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15000) { model?.attachedSecureWindowForTest() !== oldPort }
        val newPort = checkNotNull(securePortOf())
        assertTrue("the new host has its own window generation", newPort.generation != oldPort.generation)
        compose.waitUntil(10000) { !state().sippy.secureReady }
        var newActivity: ComponentActivity? = null
        compose.activityRule.scenario.onActivity { newActivity = it }
        val host = checkNotNull(newActivity)
        compose.runOnIdle {
            val m = checkNotNull(model)
            val st = m.r2Browser.configuration.state.value
            assertFalse("readiness must not survive the recreation", st.sippy.secureReady)
            assertFalse(st.sippy.draftComplete)
            assertFalse(st.sippy.disclosureAcknowledged)
            assertFalse(st.sippy.credentialsAcknowledged)
            assertNull(st.preview)
            assertNull(m.r2Browser.sippyCredentials.currentValue(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY))
            assertFalse("the destroyed window keeps no live handle", oldPort.isProtected())
            assertFalse("the new window is not protected by the old handle", flagOf(host.window))
        }
        // A stale confirmation of the pre-recreation preview can never be dispatched.
        compose.runOnIdle { checkNotNull(model).r2Browser.confirmPreview() }
        compose.waitUntil(10000) { !state().writing }
        assertEquals("a pre-recreation preview must never be sent", 0, mutations().size)

        // The *new* tree is a real production tree: click begin, type into its fields, ack, preview.
        beginByClick()
        compose.runOnIdle {
            val st = state()
            println(
                "T6C_RECREATE new_tree generation=${model!!.attachedSecureWindowForTest()?.generation} " +
                    "secure=${st.sippy.secureReady} epoch=${st.sippy.secureEpoch} flag=${flagOf(host.window)}",
            )
        }
        assertTrue("the new window now carries the real protection", flagOf(host.window))
        fillByUi()
        compose.waitUntil(10000) { state().sippy.draftComplete }
        typedBucketByUi("my-bucket")
        tapAck("rc-sippy-ack-disclosure")
        tapAck("rc-sippy-ack-credentials")
        // A late report/release of the *old* surface and a late detach of the old window cannot touch
        // the new session.
        compose.runOnIdle {
            val m = checkNotNull(model)
            m.onSecureWindowClosed(oldPort)
            m.r2Browser.sippyCredentials.onSecretSurfaceRemoved(oldEpoch)
            assertTrue("the new session stays ready", state().sippy.secureReady)
            assertTrue("the new draft stays complete", state().sippy.draftComplete)
        }
        previewByUi()
        val beforeTaskIds = existingTaskIds()
        compose.onNodeWithTag("rc-preview-confirm").performScrollTo().performClick()
        assertOnePutThenReadback(beforeTaskIds)
    }

    @Test
    fun anAdmittedSuspendedSendStillCompletesAfterTheUiRouteGoesAway() {
        install()
        beginByClick()
        fillByIntent()
        compose.waitUntil(10000) { state().sippy.draftComplete }
        compose.runOnIdle { checkNotNull(model).r2Browser.onConfigurationIntent(R2ConfigurationIntent.Preview) }
        compose.waitUntil(10000) { state().preview != null }
        val beforeTaskIds = compose.runOnIdle { checkNotNull(model).r2Browser.writeTasks.value.map { it.id }.toSet() }
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        putEntered = entered
        putGate = gate
        compose.runOnIdle { checkNotNull(model).r2Browser.confirmPreview() }
        assertTrue("the admitted PUT must reach the transport", entered.await(20, TimeUnit.SECONDS))
        // The UI route goes away while the app-owned transport is suspended.
        compose.runOnIdle {
            val m = checkNotNull(model)
            m.r2Browser.onConfigurationIntent(R2ConfigurationIntent.Close)
            m.r2Browser.onConfigurationIntent(R2ConfigurationIntent.EndSippySecureEditing)
        }
        gate.countDown()
        compose.waitUntil(20000) {
            val m = checkNotNull(model)
            m.r2Browser.writeTasks.value.any { it.id !in beforeTaskIds }
        }
        compose.runOnIdle {
            val created = checkNotNull(model!!.r2Browser.writeTasks.value.filter { it.id !in beforeTaskIds })
            assertEquals(1, created.size)
            assertEquals(TaskState.SUCCEEDED, created.single().state)
            assertEquals(1, mutations().size)
        }
    }

    @Test
    fun anAdmittedSuspendedPutCompletesOnceAcrossARealRecreation() {
        install()
        beginByClick()
        fillByUi()
        compose.waitUntil(10000) { state().sippy.draftComplete }
        typedBucketByUi("my-bucket")
        tapAck("rc-sippy-ack-disclosure")
        tapAck("rc-sippy-ack-credentials")
        previewByUi()
        val beforeTaskIds = existingTaskIds()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        putEntered = entered
        putGate = gate
        val job = compose.runOnIdle { checkNotNull(model).r2Browser.confirmPreview() }
        assertNotNull("the confirmation returned its own app-owned job", job)
        assertTrue("the admitted PUT must reach the transport", entered.await(20, TimeUnit.SECONDS))

        // A real Activity recreation while the app-owned PUT is suspended in the network step.
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15000) { !state().sippy.secureReady }
        compose.runOnIdle {
            // The UI session is retired, but the operation stays admitted: exactly the one PUT that
            // already reached the transport, no resend and no second plan.
            println(
                "T6C_ADMIT after_recreate puts=${requests.count { it.method == "PUT" }} " +
                    "active=${job?.isActive} completed=${job?.isCompleted} cancelled=${job?.isCancelled} " +
                    "writing=${state().writing} secure=${state().sippy.secureReady}",
            )
            assertEquals(1, requests.count { it.method == "PUT" })
            assertTrue("the confirmation is still running", checkNotNull(job).isActive)
            assertFalse("the app-owned operation must not be cancelled by the UI", checkNotNull(job).isCancelled)
        }
        gate.countDown()
        // The app-owned operation finishes on its own (bounded join of the *real* job, then the real
        // outcome from the task store; an early "not writing" is never taken as completion).
        compose.waitUntil(20000) { checkNotNull(job).isCompleted }
        compose.runOnIdle { assertFalse("the confirmation job was not cancelled", checkNotNull(job).isCancelled) }
        compose.waitUntil(20000) { !state().writing }
        compose.runOnIdle {
            val snapshot = requests.toList()
            val putIndex = snapshot.indexOfFirst { it.method == "PUT" && it.path.endsWith("/sippy") }
            assertTrue("exactly one PUT: $putIndex", putIndex >= 0 && snapshot.count { it.method == "PUT" } == 1)
            val readbackIndex = snapshot.indexOfLast { it.method == "GET" && it.path.endsWith("/sippy") }
            assertTrue("the read-back GET must come after the PUT", readbackIndex > putIndex)
            val created = model!!.r2Browser.writeTasks.value.filter { it.id !in beforeTaskIds }
            assertEquals("exactly one task was created by the admitted operation", 1, created.size)
            assertEquals(TaskState.SUCCEEDED, created.single().state)
            assertEquals("r2-put-bucket-sippy-config", created.single().actionKey)
        }
    }

    @Test
    fun aCredentialRevokeBeforeDispatchObservesTheRealConfirmationJobAndWritesNothing() {
        install()
        beginByClick()
        fillByIntent()
        compose.waitUntil(10000) { state().sippy.draftComplete }
        compose.runOnIdle { checkNotNull(model).r2Browser.onConfigurationIntent(R2ConfigurationIntent.Preview) }
        compose.waitUntil(10000) { state().preview != null }
        val beforeTaskIds = existingTaskIds()
        // Hold the pre-send configuration GET (the independent read that runs before the final guard)
        // and revoke the credential/route while it is suspended: no PUT may ever be sent.
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        preSendGetEntered = entered
        preSendGetGate = gate
        holdNextSippyGet = true
        val job = compose.runOnIdle { checkNotNull(model).r2Browser.confirmPreview() }
        assertNotNull("the confirmation returned its own app-owned job", job)
        assertTrue("the pre-send read must be reached", entered.await(20, TimeUnit.SECONDS))
        val revoke = compose.runOnIdle {
            // Before the gate is released the operation is still running: "not writing" is not
            // completion and must not be used to declare the 0-PUT result.
            println(
                "T6F_REVOKE before_release active=${checkNotNull(job).isActive} " +
                    "completed=${job.isCompleted} cancelled=${job.isCancelled} " +
                    "writing=${state().writing} puts=${requests.count { it.method == "PUT" }}",
            )
            assertTrue("the admitted operation is still running before the gate is released", job.isActive)
            assertFalse("the admitted operation must not be cancelled", job.isCancelled)
            val m = checkNotNull(model)
            val connection = m.controller.state.value.selected
            assertNotNull("a bound connection is required for the revoke", connection)
            m.disconnect(connection!!)
            "revoked"
        }
        assertEquals("revoked", revoke)
        gate.countDown()
        // Bounded join of the *real* confirmation job. If it were cancelled, the join alone would not
        // prove app-owned completion, so cancellation is asserted separately and the outcome is read
        // from the real task/response state, never forced.
        compose.waitUntil(20000) { checkNotNull(job).isCompleted }
        compose.runOnIdle {
            assertFalse("the confirmation job must end on its own, not by cancellation", checkNotNull(job).isCancelled)
        }
        compose.waitUntil(10000) { !state().writing }
        compose.runOnIdle {
            val created = model!!.r2Browser.writeTasks.value.filter { it.id !in beforeTaskIds }
            println(
                "T6F_REVOKE after_join puts=${requests.count { it.method == "PUT" }} " +
                    "created=${created.map { it.actionKey + ":" + it.state }} preview=${state().preview != null} " +
                    "writing=${state().writing} secure=${state().sippy.secureReady}",
            )
            // Nothing was sent and nothing of this operation can be SUCCEEDED. Depending on when the
            // connection is invalidated the row is settled FAILED/UNKNOWN, stays conservatively
            // RUNNING, or is never created at all — the conservative terminal semantics are never
            // forced by the test.
            assertEquals("no PUT may be sent by a revoked draft", 0, requests.count { it.method == "PUT" })
            assertTrue(
                "no task of this operation may be SUCCEEDED (found ${created.map { it.state }})",
                created.none { it.state == TaskState.SUCCEEDED },
            )
            assertNull("the revoked preview must not survive", state().preview)
            assertFalse("the revoked credential session must not stay ready", state().sippy.secureReady)
        }
    }

    private fun securePortOf(): AndroidSecureWindow? =
        model?.attachedSecureWindowForTest() as? AndroidSecureWindow
}
