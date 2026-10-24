package com.flarego

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.ui.r2.SecurePurpose
import com.flarego.ui.r2.SecureWindowSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Task 5c native proof: the production `AndroidSecureWindow` on a **real Android window**, with a
 * real, executable screen capture.
 *
 * The marker is an obviously non-secret solid colour block. No credential, synthetic or otherwise,
 * is ever rendered here, so nothing can leak through a screenshot, a failure bitmap or a log.
 *
 * Note what this test does **not** claim: the recents/overview thumbnail is not inspected (the
 * platform exposes no supported pixel API for it), so that part stays explicitly unverified.
 */
class R2SippySecureWindowTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val markerArgb = 0xFF00C000.toInt()

    private fun drawMarker() {
        rule.setContent {
            Box(Modifier.fillMaxSize().background(Color(markerArgb)))
        }
        rule.waitForIdle()
    }

    private fun captureOrNull(): Bitmap? =
        try {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        } catch (_: Throwable) {
            null
        }

    /**
     * A shell-level capture (the same path as `adb shell screencap`), executed from the test so the
     * window state and the capture are synchronised.
     */
    private fun shellCaptureOrNull(): Bitmap? =
        try {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap"))
                .use { it.readBytes() }
            rawScreencapToBitmap(bytes)
        } catch (_: Throwable) {
            null
        }

    /** Decodes the raw `screencap` stream: width, height, format, [colorspace,] RGBA_8888 pixels. */
    private fun rawScreencapToBitmap(bytes: ByteArray): Bitmap? {
        if (bytes.size < 16) return null
        fun int(offset: Int) =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or ((bytes[offset + 3].toInt() and 0xff) shl 24)
        val width = int(0)
        val height = int(4)
        if (width <= 0 || height <= 0) return null
        val pixels = width.toLong() * height.toLong() * 4L
        val header = if (bytes.size.toLong() == 12L + pixels) 12 else if (bytes.size.toLong() == 16L + pixels) 16 else return null
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

    /** Samples the middle band of the screen (never the status/navigation bars). */
    private fun markerPixels(bitmap: Bitmap?): Int {
        if (bitmap == null) return 0
        var count = 0
        val y0 = bitmap.height / 5
        val y1 = bitmap.height * 4 / 5
        val x0 = bitmap.width / 5
        val x1 = bitmap.width * 4 / 5
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                if (g > 140 && r < 110 && b < 110) count++
                x += 4
            }
            y += 4
        }
        return count
    }

    /** Window flag changes are main-thread only, exactly like the production UI call sites. */
    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun secureFlagSet(activity: ComponentActivity): Boolean =
        (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

    @Test
    fun theOwnerAppliesTheRealFlagAndTheSystemCaptureBlanksTheNonSecretMarker() {
        drawMarker()
        val activity = rule.activity
        lateinit var owner: AndroidSecureWindow
        onMain { owner = AndroidSecureWindow(activity.window) }
        assertFalse("the window starts unprotected", secureFlagSet(activity))

        // Positive control: the shell capture path works and the (non-secret) marker is visible.
        val visible = shellCaptureOrNull()
        assertNotNull("the shell capture must produce a decodable frame", visible)
        assertTrue("the marker must be visible without protection", markerPixels(visible) > 50)

        var session: SecureWindowSession? = null
        onMain { session = owner.request(SecurePurpose.SIPPY_CREDENTIALS) }
        assertNotNull("the owner must issue a session for a real window", session)
        assertTrue("the real window flag is applied", secureFlagSet(activity))
        assertTrue(session!!.isProtected())

        // Negative control: with FLAG_SECURE the system blanks the app region (zero marker pixels).
        val secured = shellCaptureOrNull()
        assertNotNull("the shell capture must still produce a frame while protected", secured)
        assertEquals(
            "a protected app region must not expose the marker",
            0,
            markerPixels(secured),
        )

        onMain { owner.release(session!!) }
        assertFalse("the owned bit is cleared on release", secureFlagSet(activity))
        val restored = shellCaptureOrNull()
        assertNotNull("the shell capture must still work after release", restored)
        assertTrue("the marker is visible again after release", markerPixels(restored) > 50)
        println(
            "SIPPY_WINDOW_CAPTURE unprotected=${markerPixels(visible)} secured=${markerPixels(secured)} " +
                "restored=${markerPixels(restored)}",
        )
    }

    @Test
    fun thePrivilegedInstrumentedCaptureIsNotTheSecureExclusionPath() {
        drawMarker()
        val activity = rule.activity
        lateinit var owner: AndroidSecureWindow
        onMain { owner = AndroidSecureWindow(activity.window) }
        var session: SecureWindowSession? = null
        onMain { session = owner.request(SecurePurpose.SIPPY_CREDENTIALS) }
        assertTrue(secureFlagSet(activity))

        // Recorded platform behaviour: `UiAutomation.takeScreenshot()` is a privileged debug capture
        // that bypasses FLAG_SECURE on this emulator, so it can never be used as the exclusion proof
        // (the shell capture above is). Pinned so a silent change is noticed.
        val instrumented = captureOrNull()
        assertNotNull("the instrumented capture returns a frame", instrumented)
        assertTrue(
            "the privileged instrumented capture is expected to bypass FLAG_SECURE on this device",
            markerPixels(instrumented) > 50,
        )
        println("SIPPY_WINDOW_INSTRUMENTED_CAPTURE marker=${markerPixels(instrumented)} (privileged bypass)")
        onMain { owner.release(session!!) }
    }

    @Test
    fun theConnectAndSippyPurposesNeverClearEachOtherAndLeaveOtherFlagsAlone() {
        drawMarker()
        val activity = rule.activity
        val window = activity.window
        lateinit var owner: AndroidSecureWindow
        onMain {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            owner = AndroidSecureWindow(window)
            // The pre-existing connect dialog purpose and the Sippy credential purpose run together.
            owner.setPurpose(SecurePurpose.CONNECTION_TOKEN, true)
            owner.setPurpose(SecurePurpose.SIPPY_CREDENTIALS, true)
        }
        assertTrue(secureFlagSet(activity))
        onMain { owner.setPurpose(SecurePurpose.CONNECTION_TOKEN, false) }
        assertTrue("the Sippy purpose still holds the flag", secureFlagSet(activity))
        onMain { owner.setPurpose(SecurePurpose.SIPPY_CREDENTIALS, false) }
        assertFalse(secureFlagSet(activity))
        assertTrue(
            "an unrelated window flag is never cleared by this owner",
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0,
        )
        onMain { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    @Test
    fun aPreExistingFlagIsNeverClearedByThisOwner() {
        val activity = rule.activity
        val window = activity.window
        onMain {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            val owner = AndroidSecureWindow(window)
            owner.setPurpose(SecurePurpose.SIPPY_CREDENTIALS, true)
            owner.setPurpose(SecurePurpose.SIPPY_CREDENTIALS, false)
            owner.close()
        }
        assertTrue("a FLAG_SECURE that existed before this owner is preserved", secureFlagSet(activity))
        onMain { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    @Test
    fun mainActivityAttachesTheSippyCapableOwnerToItsRealWindow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // The Activity starts without a secure flag; nothing pretends to be protected.
                assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
                val model = ViewModelProvider(activity)[FlareGoViewModel::class.java]
                val attached = model.attachedSecureWindowForTest()
                assertNotNull("MainActivity must attach its real-window owner to the app owner", attached)
                val port = attached!!
                val session = port.request(SecurePurpose.SIPPY_CREDENTIALS)
                assertNotNull(session)
                assertTrue(
                    "the app-wired owner really applies FLAG_SECURE to the Activity window",
                    (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0,
                )
                port.release(session!!)
                assertEquals(
                    0,
                    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE,
                )
            }
        }
    }

    @Test
    fun twoHandlesForTheSamePurposeKeepTheRealFlagUntilTheLastOneIsReleased() {
        val activity = rule.activity
        lateinit var owner: AndroidSecureWindow
        onMain { owner = AndroidSecureWindow(activity.window) }
        var first: SecureWindowSession? = null
        var second: SecureWindowSession? = null
        onMain {
            first = owner.request(SecurePurpose.SIPPY_CREDENTIALS)
            second = owner.request(SecurePurpose.SIPPY_CREDENTIALS)
        }
        assertTrue(secureFlagSet(activity))
        assertTrue(first!!.isProtected())
        assertTrue(second!!.isProtected())

        onMain { owner.release(first!!) }
        assertTrue("one released reference must not clear the real flag", secureFlagSet(activity))
        assertFalse("a released handle is never proof of protection", first!!.isProtected())
        assertTrue(second!!.isProtected())

        onMain { owner.release(second!!) }
        assertFalse(secureFlagSet(activity))
        assertFalse(second!!.isProtected())
        // A repeated release is a no-op.
        onMain { owner.release(second!!) }
        assertFalse(secureFlagSet(activity))
    }

    @Test
    fun aReleasedHandleIsNotProtectedWhileAnotherPurposeKeepsTheRealFlag() {
        val activity = rule.activity
        lateinit var owner: AndroidSecureWindow
        onMain { owner = AndroidSecureWindow(activity.window) }
        var sippy: SecureWindowSession? = null
        onMain {
            sippy = owner.request(SecurePurpose.SIPPY_CREDENTIALS)
            owner.setPurpose(SecurePurpose.CONNECTION_TOKEN, true)
        }
        assertTrue(secureFlagSet(activity))
        onMain { owner.release(sippy!!) }
        assertTrue("the connect purpose still holds the real flag", secureFlagSet(activity))
        assertFalse("the released Sippy handle must not authorize anything", sippy!!.isProtected())
        // The still-live connect purpose keeps its own protection.
        onMain { owner.setPurpose(SecurePurpose.CONNECTION_TOKEN, false) }
        assertFalse(secureFlagSet(activity))
    }

    @Test
    fun theLegacyBooleanAndExplicitHandlesNeverStealEachOthersProtection() {
        val activity = rule.activity
        lateinit var owner: AndroidSecureWindow
        onMain { owner = AndroidSecureWindow(activity.window) }
        var explicit: SecureWindowSession? = null
        onMain {
            // The legacy boolean is idempotent and keeps its own single reference.
            owner.setPurpose(SecurePurpose.SIPPY_CREDENTIALS, true)
            owner.setPurpose(SecurePurpose.SIPPY_CREDENTIALS, true)
            explicit = owner.request(SecurePurpose.SIPPY_CREDENTIALS)
        }
        assertTrue(secureFlagSet(activity))
        // Releasing the legacy boolean must not release the explicit handle.
        onMain { owner.setPurpose(SecurePurpose.SIPPY_CREDENTIALS, false) }
        assertTrue("the explicit handle still holds the flag", secureFlagSet(activity))
        assertTrue(explicit!!.isProtected())
        // Releasing the explicit handle clears the last reference.
        onMain { owner.release(explicit!!) }
        assertFalse(secureFlagSet(activity))
        assertFalse(explicit!!.isProtected())
    }

    @Test
    fun twoRealWindowsKeepIndependentProtection() {
        val firstActivity = rule.activity
        lateinit var firstOwner: AndroidSecureWindow
        onMain { firstOwner = AndroidSecureWindow(firstActivity.window) }
        ActivityScenario.launch(ComponentActivity::class.java).use { second ->
            second.onActivity { activity ->
                val secondOwner = AndroidSecureWindow(activity.window)
                firstOwner.setPurpose(SecurePurpose.SIPPY_CREDENTIALS, true)
                assertTrue(secureFlagSet(firstActivity))
                // The new window must acquire its own protection; it inherits nothing.
                assertFalse((activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0)
                assertFalse(secondOwner.isProtected())
                secondOwner.setPurpose(SecurePurpose.SIPPY_CREDENTIALS, true)
                assertTrue(secureFlagSet(activity))
                firstOwner.close()
                // Closing the first window never touches the second.
                assertTrue((activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0)
                secondOwner.close()
                assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    @Test
    fun aRecreatedWindowIsAFreshWindowAndTheStaleOwnerCannotChangeIt() {
        var staleOwner: AndroidSecureWindow? = null
        var staleSession: SecureWindowSession? = null
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.onActivity { activity ->
                val owner = AndroidSecureWindow(activity.window)
                staleOwner = owner
                staleSession = owner.request(SecurePurpose.SIPPY_CREDENTIALS)
                assertNotNull(staleSession)
                assertTrue(secureFlagSet(activity))
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                // A new window is never born protected, and it is a different window object.
                assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
                assertNotNull(staleOwner)
                assertNotNull(staleSession)
                // The Activity's onDestroy equivalent: closing the old owner never touches this window.
                staleOwner!!.close()
                assertFalse(staleSession!!.isProtected())
                assertEquals(
                    "a stale owner must not change the new window",
                    0,
                    activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE,
                )
            }
        } finally {
            scenario.close()
        }
    }
}
