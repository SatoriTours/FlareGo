package com.flarego

import android.app.Application
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
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
import com.flarego.core.ports.ByteSink
import com.flarego.core.ports.HttpResponseHead
import com.flarego.core.ports.TransferPort
import com.flarego.ui.FlareGoApp
import com.flarego.ui.r2.R2ConfigurationIntent
import com.flarego.ui.r2.R2ConfigurationPanel
import com.flarego.ui.r2.R2SippySecretField
import com.flarego.ui.r2.R2SippySecretInput
import com.flarego.ui.r2.SecurePurpose
import com.flarego.ui.r2.SecureWindowPort
import com.flarego.ui.r2.SecureWindowSession
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
 * Task 6a frame-fix, part A: the minimal **real** observation of the exit ordering.
 *
 * Per frame (preDraw, on the same decor) and at every release/retirement call it records only
 * fixed TestTags / attached / placed / bounds, the actual FLAG_SECURE and the frame index — never a
 * text value. The unmerged root semantics node is taken from the compose `ViewRootForTest` **before**
 * the protected session starts, and the traversal is a direct child walk (no test API, no waiting)
 * so it is legal inside a draw callback.
 *
 * Test 1 documents the old one-phase behaviour (flag dropped while the secret nodes are still placed).
 * Test 2 asserts the approved two-phase behaviour: eligibility/material first, and the exact handle is
 * released only after a committed frame that no longer contains the sensitive nodes.
 * Test 3 pins the teardown boundary: a dying window never has its physical flag cleared.
 */
class R2SippyFrameBarrierTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = "f".repeat(32)
    private var model: FlareGoViewModel? = null
    private var initial = emptySet<String>()
    private val requests = Collections.synchronizedList(mutableListOf<HttpRequest>())
    private val marker = "SYNTHETIC-FRAME-MARKER-8c3"
    private val secretTags = setOf(
        "rc-sippy-field-SOURCE_BUCKET",
        "rc-sippy-field-SOURCE_REGION",
        "rc-sippy-field-SOURCE_ACCESS_KEY_ID",
        "rc-sippy-field-SOURCE_SECRET_ACCESS_KEY",
        "rc-sippy-field-DESTINATION_ACCESS_KEY_ID",
        "rc-sippy-field-DESTINATION_SECRET_ACCESS_KEY",
    )

    private data class Frame(val index: Int, val secure: Boolean, val content: Boolean)
    private data class Call(
        val phase: String,
        val generation: Long,
        val secureBefore: Boolean,
        val secureAfter: Boolean,
        val contentPresent: Boolean,
        val frameIndex: Int,
        val body: Int,
    )

    private val frames = mutableListOf<Frame>()
    private var frameIndex = 0
    private var root: SemanticsNode? = null
    private var listener: ViewTreeObserver.OnPreDrawListener? = null
    private val calls = mutableListOf<Call>()
    private var forceOnePhase = false
    private var production: AndroidSecureWindow? = null
    /** (generation, cleanDrawNanos, vsyncNanos) recorded inside the real compat metrics callback. */
    private val compatEvidence = mutableListOf<Triple<Long, Long, Long>>()
    private var compatNowAtCallback = 0L

    private val transfers = object : TransferPort {
        override suspend fun download(request: HttpRequest, sink: ByteSink): HttpResponseHead {
            requests += request
            sink.write(byteArrayOf(1, 2, 3), 0, 3)
            sink.close()
            return HttpResponseHead(200, emptyMap(), 3)
        }
    }

    inner class RecordingPort(private val inner: AndroidSecureWindow) : SecureWindowPort {
        override val generation: Long get() = inner.generation

        override fun request(purpose: SecurePurpose): SecureWindowSession? = inner.request(purpose)

        override fun release(session: SecureWindowSession) {
            val secureBefore = flag()
            val content = contentNow()
            val index = frameIndex
            inner.release(session)
            calls += Call("release", inner.generation, secureBefore, flag(), content, index, bodyOf(session))
        }

        override fun armRetirement(session: SecureWindowSession): Boolean {
            if (forceOnePhase) {
                // The old behaviour, reproduced exactly for the characterisation assertion: the flag is
                // dropped immediately, without any frame evidence.
                val secureBefore = flag()
                val content = contentNow()
                val index = frameIndex
                inner.release(session)
                calls += Call("release-one-phase", inner.generation, secureBefore, flag(), content, index, bodyOf(session))
                return true
            }
            val content = contentNow()
            val index = frameIndex
            val result = inner.armRetirement(session)
            calls += Call("arm", inner.generation, flag(), flag(), content, index, bodyOf(session))
            return result
        }

        override fun contentRemoved(session: SecureWindowSession) {
            val content = contentNow()
            val index = frameIndex
            calls += Call("content-removed", inner.generation, flag(), flag(), content, index, bodyOf(session))
            inner.contentRemoved(session)
        }

        private fun bodyOf(session: SecureWindowSession): Int = System.identityHashCode(session)
    }

    private fun flag(): Boolean =
        (compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

    private fun composeRoot(): SemanticsNode? {
        val decor = compose.activity.window.decorView as? android.view.ViewGroup ?: return null
        val queue = ArrayDeque<android.view.View>()
        queue.add(decor)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            if (view is ViewRootForTest) return view.semanticsOwner.unmergedRootSemanticsNode
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
        }
        return null
    }

    /** Direct child walk: only the fixed tags and the layout facts, never a text value. */
    private fun contentNow(): Boolean {
        val rootNode = root ?: return true
        val queue = ArrayDeque<SemanticsNode>()
        queue.add(rootNode)
        var visited = 0
        while (queue.isNotEmpty() && visited < 4_000) {
            val node = queue.removeFirst()
            visited++
            val tag = node.config.getOrNull(SemanticsProperties.TestTag)
            if (tag != null && tag in secretTags && node.layoutInfo.isAttached && node.layoutInfo.isPlaced) return true
            for (child in node.children) queue.add(child)
        }
        return false
    }

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
                        """{"enabled":false}"""
                    request.path.endsWith("/r2/buckets/my-bucket") ->
                        """{"name":"my-bucket","location":"weur","storage_class":"Standard"}"""
                    request.path.contains("/r2/buckets?") ->
                        """{"buckets":[{"name":"my-bucket","location":"weur","storage_class":"Standard"}]}"""
                    else -> "[]"
                }
                HttpResponse(200, """{"success":true,"errors":[],"messages":[],"result":$result}""")
            } }, { transfers })
            model = m
            owner.put("r2", m)
            initial = m.controller.state.value.connections.map { it.id }.toSet()
            m.viewModelScope.launch { m.connect("R2 Test", account, "synthetic-r2-token") }
        }
        compose.waitUntil(10000) { model?.controller?.state?.value?.selected?.accountId == account }
        val m = checkNotNull(model)
        compose.runOnIdle {
            val inner = AndroidSecureWindow(compose.activity.window)
            production = inner
            m.attachSecureWindow(RecordingPort(inner))
        }
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
        compose.waitForIdle()
        root = composeRoot()
        assertNotNull("the compose unmerged root must be available before the session starts", root)
        // Record every drawn frame from *before* the first protected session, keeping the first段.
        val decor = compose.activity.window.decorView
        val l = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                frameIndex++
                frames += Frame(frameIndex, flag(), contentNow())
                return true
            }
        }
        listener = l
        compose.runOnUiThread { decor.viewTreeObserver.addOnPreDrawListener(l) }
        production?.onCleanFrameReleaseForTest = { generation ->
            // Runs inside the production frame-commit callback, before the exact handle is released.
            val content = contentNow()
            val secureBefore = flag()
            val index = frameIndex
            compose.activity.window.decorView.post {
                calls += Call("clean-frame-release", generation, secureBefore, flag(), content, index, 0)
            }
        }
    }

    private fun beginProtected() {
        compose.onNodeWithTag("rc-sippy-secure-begin").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.sippy.secureReady }
    }

    private fun typeSecret(field: R2SippySecretField, value: String) {
        val node = compose.onNodeWithTag("rc-sippy-field-${field.name}")
        node.performScrollTo()
        node.performClick()
        node.performTextReplacement(value)
    }

    private fun fillAws() {
        for ((field, value) in listOf(
            R2SippySecretField.SOURCE_BUCKET to "source-bucket",
            R2SippySecretField.SOURCE_REGION to "us-east-1",
            R2SippySecretField.SOURCE_ACCESS_KEY_ID to "SYNTHETIC-SOURCE-KEY",
            R2SippySecretField.SOURCE_SECRET_ACCESS_KEY to marker,
            R2SippySecretField.DESTINATION_ACCESS_KEY_ID to "SYNTHETIC-R2-KEY",
            R2SippySecretField.DESTINATION_SECRET_ACCESS_KEY to marker,
        )) typeSecret(field, value)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
    }

    private val owner = androidx.lifecycle.ViewModelStore()

    @After
    fun cleanup() {
        listener?.let { l ->
            compose.runOnUiThread { compose.activity.window.decorView.viewTreeObserver.removeOnPreDrawListener(l) }
        }
        listener = null
        production?.closeForTeardown()
        compose.runOnIdle { model?.let { m -> m.controller.state.value.connections.filter { it.id !in initial }.forEach(m::disconnect) } }
        compose.waitUntil(10000) { model?.controller?.state?.value?.connections?.none { it.id !in initial } != false }
        compose.runOnIdle { owner.clear() }
    }

    @Test
    fun onePhaseReleaseDropsTheFlagWhileTheSecretNodesAreStillPlaced() {
        install()
        forceOnePhase = true
        beginProtected()
        fillAws()
        compose.onNodeWithTag("rc-sippy-secure-end").performScrollTo().performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.sippy.secureReady }
        compose.waitForIdle()
        val call = calls.firstOrNull { it.phase == "release-one-phase" }
        assertNotNull("the one-phase release must have been observed", call)
        println(
            "T6A_FRAME one_phase generation=${call!!.generation} secure_before=${call.secureBefore} " +
                "secure_after=${call.secureAfter} content_present=${call.contentPresent} frame=${call.frameIndex}",
        )
        // The characterisation of the gap this fix removes: the flag was dropped while the secret
        // nodes were still attached/placed. Recorded, not asserted as acceptable.
        assertTrue("expected the old behaviour to drop the flag with content present", call.contentPresent && !call.secureAfter)
    }

    @Test
    fun twoPhaseReleaseKeepsTheProtectionUntilACleanFrameIsCommitted() {
        install()
        beginProtected()
        fillAws()
        val lastContentFrameBeforeExit = frames.lastOrNull { it.content }?.index ?: 0
        compose.onNodeWithTag("rc-sippy-secure-end").performScrollTo().performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.sippy.secureReady }
        compose.waitUntil(10000) { calls.any { it.phase == "clean-frame-release" } }
        compose.waitForIdle()
        // The deferred release must land on a later frame with the nodes gone.
        val armed = calls.first { it.phase == "arm" }
        val removed = calls.firstOrNull { it.phase == "content-removed" }
        val release = calls.lastOrNull { it.phase == "clean-frame-release" }
        assertNotNull("the clean-frame release must have happened", release)
        println(
            "T6A_FRAME two_phase arm_frame=${armed.frameIndex} arm_content=${armed.contentPresent} " +
                "removed_frame=${removed?.frameIndex} removed_content=${removed?.contentPresent} " +
                "release_frame=${release!!.frameIndex} release_content=${release.contentPresent} " +
                "release_secure_before=${release.secureBefore} release_secure_after=${release.secureAfter} " +
                "last_content_frame=$lastContentFrameBeforeExit",
        )
        assertNotNull("the composition must report the surface removed", removed)
        assertTrue("the retirement was armed while the nodes still existed", armed.contentPresent)
        assertTrue("the protection is kept across the arm", armed.secureAfter)
        assertFalse("the composition signal must come with the nodes gone", removed!!.contentPresent)
        assertTrue("the flag is still held when the composition signal arrives", removed.secureAfter)
        assertFalse("the deferred release must happen with no secret node left", release.contentPresent)
        assertTrue(
            "the release must be later than the last observed content frame " +
                "(release=${release.frameIndex}, lastContent=$lastContentFrameBeforeExit)",
            release.frameIndex > lastContentFrameBeforeExit,
        )
        assertTrue("the protection was still on when the clean frame was committed", release.secureBefore)
        assertFalse("only then is the exact handle released", release.secureAfter)
        // Every frame that ever carried the secret nodes was protected, including the first one.
        val contentFrames = frames.filter { it.content }
        assertTrue("content frames must have been observed (${contentFrames.size})", contentFrames.isNotEmpty())
        assertTrue("the first content frame must be protected: ${contentFrames.first()}", contentFrames.all { it.secure })
    }

    @Test
    fun aDirtyOrEarlyCommitCannotReleaseAnArmedHandle() {
        install()
        // Port-level, no UI: arm a live handle directly and force real frames. The production never binds
        // a commit before the composition reports the surface removed, so an early/dirty commit can
        // never release it.
        val recording = checkNotNull(model).attachedSecureWindowForTest() as RecordingPort
        var session: SecureWindowSession? = null
        compose.runOnIdle {
            val handle = recording.request(SecurePurpose.SIPPY_CREDENTIALS)
            assertNotNull(handle)
            assertTrue(flag())
            assertTrue(recording.armRetirement(handle!!))
            session = handle
        }
        repeat(3) {
            compose.runOnIdle { compose.activity.window.decorView.invalidate() }
            compose.waitForIdle()
        }
        assertTrue("an armed handle must keep the flag across frames", flag())
        assertEquals(0, calls.count { it.phase == "clean-frame-release" })
        // Only the composition-removed signal binds the next commit, and it releases exactly once.
        compose.runOnIdle {
            recording.contentRemoved(checkNotNull(session))
            // The commit callback needs a frame to be drawn after the registration.
            compose.activity.window.decorView.invalidate()
        }
        compose.waitForIdle()
        compose.waitUntil(10000) { calls.any { it.phase == "clean-frame-release" } }
        compose.runOnIdle {
            recording.contentRemoved(checkNotNull(session))
            assertEquals(1, calls.count { it.phase == "clean-frame-release" })
        }
        compose.waitForIdle()
        assertFalse(flag())
    }

    @Test
    fun anUnobservableCleanFrameKeepsTheProtectionAndTheStaleSessionCannotExecute() {
        install()
        checkNotNull(production).suppressCleanFrameCommitForTest = true
        beginProtected()
        fillAws()
        val session = compose.runOnIdle {
            checkNotNull(model).r2Browser.sippyCredentials
        }
        // The mounted surface captures the immutable session epoch it draws.
        val surfaceEpoch = compose.runOnIdle { checkNotNull(model).r2Browser.configuration.state.value.sippy.secureEpoch }
        assertNotNull("the mounted surface must capture its session epoch", surfaceEpoch)
        compose.onNodeWithTag("rc-sippy-secure-end").performScrollTo().performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.sippy.secureReady }
        compose.waitForIdle()
        compose.runOnIdle {
            val m = checkNotNull(model)
            val state = m.r2Browser.configuration.state.value
            // Fail-closed: the session/material are gone, nothing can execute, and the protection stays.
            assertFalse(state.sippy.secureReady)
            assertNull(m.r2Browser.sippyCredentials.currentValue(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY))
            assertTrue("an unobservable window must keep FLAG_SECURE", flag())
        }
        // A late/duplicate composition signal of that same surface still cannot release it.
        compose.runOnIdle { checkNotNull(model).r2Browser.sippyCredentials.onSecretSurfaceRemoved(surfaceEpoch) }
        compose.runOnIdle { checkNotNull(model).r2Browser.sippyCredentials.onSecretSurfaceRemoved(surfaceEpoch) }
        compose.waitForIdle()
        assertTrue("no clean commit evidence means no release", flag())
        assertEquals(0, calls.count { it.phase == "clean-frame-release" })
    }

    @Test
    fun theForcedCompatPolicyReleasesOnlyAfterAFrameThatStartedAfterTheCleanDraw() {
        install()
        // The API 26–28 policy is forced on this API 36 device: the same production code path, real
        // OnDraw + real FrameMetrics from the same window.
        val inner = checkNotNull(production)
        inner.forceCompatCleanFrameForTest = true
        inner.onCompatCleanFrameForTest = { generation, drawn, vsync ->
            compatNowAtCallback = System.nanoTime()
            compatEvidence += Triple(generation, drawn, vsync)
        }
        beginProtected()
        fillAws()
        val lastContentFrame = frames.lastOrNull { it.content }?.index ?: 0
        compose.onNodeWithTag("rc-sippy-secure-end").performScrollTo().performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.sippy.secureReady }
        compose.waitUntil(10000) { calls.any { it.phase == "clean-frame-release" } }
        compose.waitForIdle()
        val evidence = checkNotNull(compatEvidence.singleOrNull()) { "exactly one compat match expected: $compatEvidence" }
        val removed = calls.first { it.phase == "content-removed" }
        val release = checkNotNull(calls.lastOrNull { it.phase == "clean-frame-release" })
        val deltaMs = (evidence.third - evidence.second) / 1_000_000.0
        val clockSkewMs = (compatNowAtCallback - evidence.third) / 1_000_000.0
        println(
            "T6A_COMPAT generation=${evidence.first} clean_draw_ns=${evidence.second} vsync_ns=${evidence.third} " +
                "vsync_minus_draw_ms=$deltaMs callback_skew_ms=$clockSkewMs removed_frame=${removed.frameIndex} " +
                "removed_content=${removed.contentPresent} release_frame=${release.frameIndex} " +
                "release_content=${release.contentPresent} release_secure_before=${release.secureBefore} " +
                "release_secure_after=${release.secureAfter} last_content_frame=$lastContentFrame",
        )
        // The accepted frame is the *next* one (it started strictly after the clean draw), it was
        // submitted (that is what a metrics callback means) and the release happened after it.
        assertTrue("a real clean draw time was recorded", evidence.second > 0L)
        assertTrue("the accepted vsync is not earlier than the clean draw", evidence.third >= evidence.second)
        assertTrue("one frame later, not a wildly later value: $deltaMs ms", deltaMs > 0.0 && deltaMs < 2000.0)
        assertTrue(
            "the vsync timestamp is on the CLOCK_MONOTONIC domain (skew to nanoTime = $clockSkewMs ms)",
            clockSkewMs > -2000.0 && clockSkewMs < 600_000.0,
        )
        assertFalse("the surface was gone when the removal was reported", removed.contentPresent)
        assertTrue("the protection is kept across the pending clean frame", removed.secureAfter)
        assertFalse("nothing sensitive is left when the release happens", release.contentPresent)
        assertTrue("the flag is still on when the accepted frame's commit is observed", release.secureBefore)
        assertFalse("only then is the exact handle released", release.secureAfter)
        assertFalse("no owned protection is left on the live window", flag())
    }

    @Test
    fun theForcedCompatPolicyWithoutARealCleanDrawKeepsTheProtection() {
        install()
        val inner = checkNotNull(production)
        inner.forceCompatCleanFrameForTest = true
        inner.suppressCompatCleanDrawForTest = true
        inner.onCompatCleanFrameForTest = { generation, drawn, vsync ->
            compatEvidence += Triple(generation, drawn, vsync)
        }
        beginProtected()
        fillAws()
        compose.onNodeWithTag("rc-sippy-secure-end").performScrollTo().performClick()
        compose.waitUntil(10000) { !model!!.r2Browser.configuration.state.value.sippy.secureReady }
        // Give the window every chance to produce a wrong release.
        repeat(3) {
            compose.runOnIdle { compose.activity.window.decorView.invalidate() }
            compose.waitForIdle()
        }
        compose.runOnIdle {
            // Fail-closed: eligibility/material are gone, no frame evidence exists, so the flag stays.
            assertFalse(model!!.r2Browser.configuration.state.value.sippy.secureReady)
            assertNull(model!!.r2Browser.sippyCredentials.currentValue(R2SippySecretField.SOURCE_SECRET_ACCESS_KEY))
            assertTrue("missing clean-draw evidence must keep FLAG_SECURE", flag())
        }
        assertTrue("no frame may be accepted without a clean draw: $compatEvidence", compatEvidence.isEmpty())
        assertEquals(0, calls.count { it.phase == "clean-frame-release" })
    }

    @Test
    fun leavingThePanelEntirelyStillReportsTheSurfaceRemoval() {
        install()
        beginProtected()
        fillAws()
        // The whole Sippy panel leaves the composition (tab switch): no recomposition of the panel
        // itself happens, so the removal must be reported by the surface's own disposal.
        compose.onNodeWithTag("rc-panel-cors").performScrollTo().performClick()
        compose.waitUntil(10000) { model!!.r2Browser.configuration.state.value.panel != R2ConfigurationPanel.SIPPY }
        compose.waitUntil(10000) { calls.any { it.phase == "content-removed" } }
        compose.waitUntil(10000) { calls.any { it.phase == "clean-frame-release" } }
        compose.waitForIdle()
        val removed = calls.first { it.phase == "content-removed" }
        val release = checkNotNull(calls.lastOrNull { it.phase == "clean-frame-release" })
        println(
            "T6A_FRAME panel_close removed_frame=${removed.frameIndex} removed_content=${removed.contentPresent} " +
                "release_frame=${release.frameIndex} release_content=${release.contentPresent} " +
                "release_secure_before=${release.secureBefore} release_secure_after=${release.secureAfter}",
        )
        assertFalse("the surface must be gone when the panel left", removed.contentPresent)
        assertTrue("the protection is kept while the clean frame is pending", removed.secureAfter)
        assertFalse("the retired handle is released after that clean frame", release.contentPresent or release.secureAfter)
        assertFalse("no owned protection is left on a live window", flag())
    }

    @Test
    fun teardownNeverClearsThePhysicalFlagOfADyingWindow() {
        install()
        beginProtected()
        fillAws()
        val before = flag()
        assertTrue(before)
        compose.runOnIdle {
            val m = checkNotNull(model)
            // Production order: the app forgets the window (revokes the session), then the dying window
            // is closed without clearing its physical flag.
            m.onSecureWindowClosed(checkNotNull(m.attachedSecureWindowForTest()))
            production!!.closeForTeardown()
        }
        // The window is dying: eligibility is gone, the physical protection is not cleared here.
        assertTrue("a dying window keeps its physical flag", flag())
        compose.runOnIdle {
            assertFalse(model!!.r2Browser.configuration.state.value.sippy.secureReady)
        }
    }
}
