package com.flarego

import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.flarego.ui.r2.SecurePurpose
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Task 5c fix2: **identity-anchored**, bounded real-Overview check with the public colour marker.
 *
 * Addressing (what fix1 lacked):
 * - the test binds the exact `ActivityRecord` identity at the start: package, component and `taskId`;
 * - "back in front" is never assumed: the top-resumed record must be *this* component *and* task id,
 *   and the foreground marker must be visible again before anything is measured or protected;
 * - the Overview is read through the **in-process** `UiAutomation.rootInActiveWindow` tree (the
 *   external `uiautomator dump` binary cannot attach while the instrumentation owns UiAutomation; it
 *   returned an empty tree — measured, not assumed). The card is identified by the unique
 *   `TaskDescription` label this test activity sets, which this launcher exposes as
 *   `snapshot.content-desc = "<app label> <probe label>"`, together with that node's `bounds`;
 * - a second card belonging to the *test runner* (`com.flarego.test` / InstrumentationActivityInvoker)
 *   is expected and is never measured, which is also why a package-prefix match such as
 *   `contains("com.flarego")` is not used anywhere;
 * - resolution is measured on the **same bitmap** that is written as the PNG; the card region must be
 *   stable across two bounded consecutive frames before a measurement is accepted;
 * - stale cards of other tasks are left alone and never contribute to the measurement.
 *
 * Only the public colour marker is ever on screen: no credential exists here, so no frame or log can
 * leak one. `UiAutomation.takeScreenshot()` (a privileged capture that bypasses FLAG_SECURE) is not
 * used as an exclusion proof, and no MediaProjection consent is requested.
 *
 * If the identity anchor cannot be confirmed within the bounded attempt, the test fails with the
 * recorded focus/a11y evidence instead of falling back to a different region.
 */
/**
 * The stability contract of the Overview measurement: a count is accepted only when the *two
 * consecutive* frames agree. Returns the index of the later frame of the first agreeing pair, or null.
 */
internal fun stableIndex(counts: List<Int>): Int? {
    for (index in 1 until counts.size) if (counts[index] == counts[index - 1]) return index
    return null
}

class R2SippyRecentsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val markerArgb = 0xFF00C000.toInt()
    private lateinit var probeLabel: String
    private lateinit var testActivity: ComponentActivity

    // ------------------------------------------------------------------ shell / capture plumbing

    private fun automation() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun runOnMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun shell(command: String): String =
        try {
            ParcelFileDescriptor.AutoCloseInputStream(automation().executeShellCommand(command))
                .use { it.readBytes().decodeToString() }
        } catch (_: Throwable) {
            ""
        }

    private fun rawCapture(): ByteArray? =
        try {
            ParcelFileDescriptor.AutoCloseInputStream(automation().executeShellCommand("screencap"))
                .use { it.readBytes() }
        } catch (_: Throwable) {
            null
        }

    private fun decode(bytes: ByteArray?): Bitmap? {
        if (bytes == null || bytes.size < 16) return null
        fun int(offset: Int) =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or ((bytes[offset + 3].toInt() and 0xff) shl 24)
        val width = int(0)
        val height = int(4)
        if (width <= 0 || height <= 0) return null
        val pixels = width.toLong() * height.toLong() * 4L
        val header =
            when (bytes.size.toLong()) {
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

    /**
     * The PNG is encoded from the very bitmap that was measured (no second capture).
     *
     * The app is uninstalled after the run, so the bytes are also emitted to logcat as indexed base64
     * chunks; the host reassembles exactly these bytes. The file is still written to the private
     * filesDir for the in-device record.
     */
    private fun savePng(bitmap: Bitmap, name: String): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val encoded = java.util.Base64.getEncoder().encodeToString(file.readBytes())
        val chunkSize = 800
        val chunks = (encoded.length + chunkSize - 1) / chunkSize
        for (index in 0 until chunks) {
            val from = index * chunkSize
            val to = minOf(encoded.length, from + chunkSize)
            println("SIPPY_PNG $name $index/$chunks ${encoded.substring(from, to)}")
        }
        return "filesDir/${file.name} (${file.length()} bytes, $chunks logcat chunks)"
    }

    private fun markerPixelsIn(bitmap: Bitmap?, roi: Rect): Int {
        if (bitmap == null) return 0
        var count = 0
        for (y in roi.top until roi.bottom) {
            for (x in roi.left until roi.right) {
                if (x < 0 || y < 0 || x >= bitmap.width || y >= bitmap.height) continue
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                if (g > 140 && r < 110 && b < 110) count++
            }
        }
        return count
    }

    private fun markerPixels(bitmap: Bitmap?): Int =
        if (bitmap == null) 0 else markerPixelsIn(bitmap, Rect(0, 0, bitmap.width, bitmap.height))

    // ------------------------------------------------------------------ identity anchors

    /** The component + task id of the currently top-resumed activity record. */
    private fun topResumed(): Pair<String, Int>? {
        val line = shell("dumpsys activity activities").lineSequence()
            .firstOrNull { it.contains("topResumedActivity=") || it.contains("mResumedActivity=") }
            ?: return null
        val component = Regex("\\bu0 ([^\\s]+/[^\\s]+) t(\\d+)").find(line)
        val short = Regex("\\bu0 ([^\\s]+) t(\\d+)").find(line)
        val match = component ?: short ?: return null
        val name = match.groupValues[1]
        val task = match.groupValues[2].toIntOrNull() ?: return null
        return name to task
    }

    private fun isOurForeground(component: String, taskId: Int): Boolean {
        val top = topResumed() ?: return false
        return top.first == component && top.second == taskId
    }

    private fun focusedWindowLine(): String =
        shell("dumpsys window").lineSequence().firstOrNull { it.contains("mCurrentFocus") }.orEmpty()

    private fun overviewIsUp(): Boolean = focusedWindowLine().contains("nexuslauncher")

    /**
     * Corroborating check only: `dumpsys activity recents` lists this task id.
     *
     * The authoritative card identity is the unique `TaskDescription` label (only this test task sets
     * it) plus the exact component/taskId verified on every foreground transition. The dump's
     * `realActivity` is the task's *base* component, which is the instrumentation's own launcher
     * activity, so requiring it to equal our activity would be wrong; it is logged instead.
     */
    private fun recentsContainsOurTask(taskId: Int): Boolean {
        val block = shell("dumpsys activity recents").lines()
            .let { lines -> lines.indexOfFirst { it.contains("taskId=$taskId") }.let { if (it < 0) "" else lines.drop(it).take(25).joinToString("\n") } }
        if (block.isEmpty()) return false
        // Plain line lookup: no regex (an unmatched brace is rejected by the platform regex engine).
        val baseLine = block.lineSequence().firstOrNull { it.contains("realActivity=") }?.trim().orEmpty()
        println("SIPPY_RECENTS_FIX2 recents_block task=$taskId base=$baseLine")
        return true
    }

    /** What the in-process accessibility tree says about the Overview surface. */
    private data class OverviewTree(
        val rootPackage: String,
        val panelPresent: Boolean,
        val snapshots: List<Pair<String, Rect>>,
        val anchor: Rect?,
    )

    /**
     * Reads the active window tree through the **instrumentation's own** UiAutomation.
     *
     * The launcher marks the Overview with `id/overview_panel` and each task snapshot with
     * `id/snapshot`; the snapshot's `content-desc` is the task title, which this test makes unique by
     * setting a `TaskDescription` label. Only a node carrying that label is accepted as the anchor.
     */
    private fun readOverviewTree(label: String): OverviewTree {
        val root = automation().rootInActiveWindow
            ?: return OverviewTree("<none>", false, emptyList(), null)
        var panel = false
        val snapshots = mutableListOf<Pair<String, Rect>>()
        var anchor: Rect? = null
        val queue = ArrayDeque<Pair<android.view.accessibility.AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < 200) {
            val (node, depth) = queue.removeFirst()
            visited++
            val id = node.viewIdResourceName.orEmpty()
            if (id.endsWith("/overview_panel")) panel = true
            if (id.endsWith("/snapshot")) {
                val desc = node.contentDescription?.toString().orEmpty()
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                snapshots += desc to bounds
                if (desc.contains(label)) anchor = bounds
            }
            if (depth < 20) for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it to depth + 1) }
        }
        return OverviewTree(root.packageName?.toString().orEmpty(), panel, snapshots, anchor)
    }

    private fun awaitCondition(timeoutMs: Long = 5_000, stepMs: Long = 200, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(stepMs)
        }
        return condition()
    }

    // ------------------------------------------------------------------ scenario steps

    private fun openOverview(component: String, taskId: Int): Rect {
        shell("input keyevent KEYCODE_APP_SWITCH")
        var last: OverviewTree? = null
        var lastRecents = false
        val anchored = awaitCondition(timeoutMs = 6_000, stepMs = 300) {
            val tree = readOverviewTree(probeLabel)
            last = tree
            lastRecents = recentsContainsOurTask(taskId)
            tree.panelPresent && tree.anchor != null && lastRecents
        }
        val tree = last
        if (!anchored || tree?.anchor == null) {
            println(
                "SIPPY_RECENTS_FIX2 anchor_failed label=$probeLabel focus=${focusedWindowLine()} " +
                    "recents_task=$lastRecents root=${tree?.rootPackage} panel=${tree?.panelPresent} " +
                    "snapshots=${tree?.snapshots?.joinToString { "${it.first}@${it.second}" }}",
            )
            assertTrue(
                "the Overview must show an anchored card for this task " +
                    "(focus=${focusedWindowLine()}, recents_task=$lastRecents, root=${tree?.rootPackage}, " +
                    "panel=${tree?.panelPresent}, snapshots=${tree?.snapshots?.joinToString { "${it.first}@${it.second}" }})",
                false,
            )
        }
        val resolved = tree!!
        println(
            "SIPPY_RECENTS_FIX2 anchor label=$probeLabel component=$component task=$taskId " +
                "roi=${resolved.anchor} snapshots=${resolved.snapshots.joinToString { "${it.first}@${it.second}" }}",
        )
        return resolved.anchor!!
    }

    /**
     * Returns to *this* component/task. [requireMarker] additionally proves the foreground marker is
     * visible again, which is only meaningful while the window is unprotected (a protected capture is
     * blank by design, so that check is used before applying and after releasing the flag).
     */
    private fun returnToOurActivity(component: String, taskId: Int, requireMarker: Boolean = true): Boolean {
        if (!isOurForeground(component, taskId)) {
            // No permission and no task API is used: the app only toggles the recents key (which the
            // user does anyway) and every attempt is verified against the exact component+taskId.
            var attempt = 0
            while (attempt < 3 && !isOurForeground(component, taskId)) {
                shell("input keyevent KEYCODE_APP_SWITCH")
                awaitCondition(timeoutMs = 1_500, stepMs = 150) { isOurForeground(component, taskId) }
                attempt++
            }
            if (!awaitCondition { isOurForeground(component, taskId) }) {
                shell("input keyevent KEYCODE_BACK")
                awaitCondition { isOurForeground(component, taskId) }
            }
        }
        if (!isOurForeground(component, taskId)) return false
        if (!requireMarker) return true
        return awaitCondition {
            markerPixels(decode(rawCapture())) > 50
        }
    }

    /**
     * One bounded, stable measurement: two consecutive frames must agree on the marker count inside
     * the anchored card region before the last frame is accepted. The accepted bitmap is the one that
     * is written as the PNG.
     */
    private fun stableMeasurement(roi: Rect): Pair<Bitmap, Int> {
        val frames = mutableListOf<Pair<Bitmap, Int>>()
        for (attempt in 0 until 4) {
            val frame = decode(rawCapture())
            if (frame != null) frames += frame to markerPixelsIn(frame, roi)
            val accepted = stableIndex(frames.map { it.second })
            if (accepted != null) return frames[accepted]
            Thread.sleep(200)
        }
        // An unstable card is not silently accepted: the evidence is reported and the measurement
        // fails, exactly as the contract says ("two consecutive equal counts").
        val counts = frames.map { it.second }
        println("SIPPY_RECENTS_FIX2 unstable roi=$roi counts=$counts")
        throw AssertionError("the Overview card did not settle (roi=$roi, counts=$counts); refusing to accept an unstable frame")
    }

    /**
     * Source-side handshake for the unique `TaskDescription`: reads back what the platform currently
     * holds for **this** task id. Corroboration only (a `runCatching` read that needs no permission);
     * the authoritative card anchor stays the Overview snapshot label plus the exact task id.
     */
    private fun taskDescriptionLabel(activity: ComponentActivity): String? =
        runCatching {
            val manager = activity.getSystemService(ActivityManager::class.java)
            manager.appTasks.firstOrNull { it.taskInfo.taskId == activity.taskId }
                ?.taskInfo?.taskDescription?.label?.toString()
        }.getOrNull()

    /** What one release attempt observed; the accepted bitmap is the one that is written as a PNG. */
    private data class ReleaseEvidence(
        val attempts: List<String>,
        val accepted: Bitmap?,
        val acceptedMarker: Int,
        val firstDecoded: Bitmap?,
        val undecoded: Int,
        val draws: Int,
        val commits: Int,
        val elapsedMs: Long,
        val flagAtAccept: Boolean,
        val foregroundAtAccept: Boolean,
        val sizeAtAccept: String,
    )

    /**
     * Clearing the client `FLAG_SECURE` bit is **not** a committed unprotected frame.
     *
     * This waits for the real thing: each attempt forces the actual decor to draw again
     * (`invalidate` + `postInvalidateOnAnimation`), waits (bounded) for that frame to be observed by the
     * window's own draw/commit callbacks, then takes the existing shell capture and counts the public
     * marker. The first capture that shows the marker is accepted and returned with the counters, so a
     * lagging state is measured instead of guessed. No fixed sleep is used as the synchronisation, no
     * threshold/colour/ROI changes, and no privileged screenshot is introduced.
     */
    private fun awaitReleasedForegroundMarker(
        activity: ComponentActivity,
        component: String,
        taskId: Int,
        timeoutMs: Long = 8_000,
    ): ReleaseEvidence {
        val decor = activity.window.decorView
        val draws = AtomicInteger(0)
        val commits = AtomicInteger(0)
        val stop = AtomicBoolean(false)
        val drawListener = ViewTreeObserver.OnDrawListener { draws.incrementAndGet() }
        rule.runOnUiThread {
            decor.viewTreeObserver.addOnDrawListener(drawListener)
            if (Build.VERSION.SDK_INT >= 29) {
                // `registerFrameCommitCallback` is one-shot: re-arm from the commit itself so the
                // counter follows every real commit while this loop runs.
                val rearm =
                    object : Runnable {
                        override fun run() {
                            if (stop.get()) return
                            runCatching {
                                decor.viewTreeObserver.registerFrameCommitCallback {
                                    commits.incrementAndGet()
                                    decor.post(this)
                                }
                            }
                        }
                    }
                rearm.run()
            }
        }
        val started = System.currentTimeMillis()
        val attempts = mutableListOf<String>()
        var accepted: Bitmap? = null
        var acceptedMarker = 0
        var firstDecoded: Bitmap? = null
        var undecoded = 0
        var flagAtAccept = false
        var foregroundAtAccept = false
        var sizeAtAccept = "-"
        try {
            while (System.currentTimeMillis() - started < timeoutMs) {
                val commitsBefore = commits.get()
                val drawsBefore = draws.get()
                rule.runOnUiThread {
                    decor.invalidate()
                    decor.postInvalidateOnAnimation()
                }
                rule.waitForIdle()
                // Bounded wait for the frame this redraw produced (draw on every API, commit on 29+).
                awaitCondition(timeoutMs = 1_500, stepMs = 50) {
                    if (Build.VERSION.SDK_INT >= 29) commits.get() > commitsBefore else draws.get() > drawsBefore
                }
                val bitmap = decode(rawCapture())
                if (bitmap == null) {
                    undecoded++
                    attempts += "#${attempts.size + 1} decode=FAILED draws=${draws.get()} commits=${commits.get()}"
                    continue
                }
                if (firstDecoded == null) firstDecoded = bitmap
                val count = markerPixels(bitmap)
                attempts +=
                    "#${attempts.size + 1} ${bitmap.width}x${bitmap.height} marker=$count " +
                        "flag=${secureFlagSet(activity)} draws=${draws.get()} commits=${commits.get()}"
                if (count > 50) {
                    accepted = bitmap
                    acceptedMarker = count
                    flagAtAccept = secureFlagSet(activity)
                    foregroundAtAccept = isOurForeground(component, taskId)
                    sizeAtAccept = "${bitmap.width}x${bitmap.height}"
                    break
                }
            }
        } finally {
            stop.set(true)
            rule.runOnUiThread { runCatching { decor.viewTreeObserver.removeOnDrawListener(drawListener) } }
        }
        return ReleaseEvidence(
            attempts = attempts,
            accepted = accepted,
            acceptedMarker = acceptedMarker,
            firstDecoded = firstDecoded,
            undecoded = undecoded,
            draws = draws.get(),
            commits = commits.get(),
            elapsedMs = System.currentTimeMillis() - started,
            flagAtAccept = flagAtAccept,
            foregroundAtAccept = foregroundAtAccept,
            sizeAtAccept = sizeAtAccept,
        )
    }

    private fun secureFlagSet(activity: ComponentActivity): Boolean =
        (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

    private fun roiOverlap(a: Rect, b: Rect): Double {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val overlap = maxOf(0, right - left).toLong() * maxOf(0, bottom - top).toLong()
        val smallest = minOf(a.width().toLong() * a.height(), b.width().toLong() * b.height())
        return if (smallest <= 0) 0.0 else overlap.toDouble() / smallest.toDouble()
    }

    @Test
    fun anUnstableCardMeasurementIsRejectedInsteadOfAccepted() {
        // Regression guard for the addressing helper: a non-converging sequence has no accepted frame.
        assertEquals(null, stableIndex(emptyList()))
        assertEquals(null, stableIndex(listOf(7)))
        assertEquals(null, stableIndex(listOf(7, 8, 9, 10)))
        assertEquals(1, stableIndex(listOf(7, 7)))
        assertEquals(2, stableIndex(listOf(7, 8, 8)))
        assertEquals(3, stableIndex(listOf(5, 6, 7, 7)))
    }

    @Test
    fun theOverviewCardOfThisTaskNeverExposesTheSecureMarker() {
        probeLabel = "FlareGoRecentsProbe-" + UUID.randomUUID().toString().take(8)
        rule.setContent { Box(Modifier.fillMaxSize().background(Color(markerArgb))) }
        rule.waitForIdle()
        val activity = rule.activity
        testActivity = activity
        val component = activity.componentName.flattenToShortString()
        val taskId = activity.taskId
        rule.runOnUiThread { activity.setTaskDescription(ActivityManager.TaskDescription(probeLabel)) }
        rule.waitForIdle()
        // Source-side handshake for the unique label: the task-description propagation is asynchronous,
        // so corroborate it (bounded, no permission) and push one real draw before the Overview is
        // opened, instead of assuming the snapshot has already refreshed. The authoritative anchor
        // remains the Overview snapshot label + exact task id below; nothing here weakens it.
        val described = awaitCondition(timeoutMs = 4_000, stepMs = 200) { taskDescriptionLabel(activity) == probeLabel }
        val liveLabel = taskDescriptionLabel(activity)
        rule.runOnUiThread { activity.window.decorView.invalidate() }
        rule.waitForIdle()
        println(
            "SIPPY_RECENTS_RECHECK identity label=$probeLabel component=$component task=$taskId " +
                "platform_task_description=$liveLabel corroborated=$described",
        )

        // P0: this build must not request the task-reordering permission, and it must be denied even
        // if an earlier debug install happened to be granted it.
        assertEquals(
            "the app must not hold android.permission.REORDER_TASKS",
            android.content.pm.PackageManager.PERMISSION_DENIED,
            InstrumentationRegistry.getInstrumentation().targetContext.checkSelfPermission(
                "android.permission.REORDER_TASKS",
            ),
        )
        assertTrue("the test activity must be the focused task at the start", isOurForeground(component, taskId))
        assertTrue("the public marker must be visible before the Overview", markerPixels(decode(rawCapture())) > 50)

        // ---- positive control: this task's own card, identified by the unique label.
        val positiveRoi = openOverview(component, taskId)
        val (positiveFrame, positiveMarker) = stableMeasurement(positiveRoi)
        val positivePng = savePng(positiveFrame, "sippy-recents-fix2-unprotected")
        println(
            "SIPPY_RECENTS_FIX2 positive label=$probeLabel roi=$positiveRoi card_marker=$positiveMarker " +
                "screen_marker=${markerPixels(positiveFrame)} png=$positivePng",
        )
        assertTrue("this task's card must show the non-secret marker (was $positiveMarker)", positiveMarker > 50)
        assertTrue("the app must return to the exact task/component", returnToOurActivity(component, taskId))
        rule.waitForIdle()

        // ---- negative control: the same card of the same task, now protected.
        lateinit var owner: AndroidSecureWindow
        rule.runOnUiThread { owner = AndroidSecureWindow(activity.window) }
        rule.runOnUiThread { owner.setPurpose(SecurePurpose.SIPPY_CREDENTIALS, true) }
        assertTrue("the real window flag is applied", secureFlagSet(activity))

        val negativeRoi = openOverview(component, taskId)
        println("SIPPY_RECENTS_FIX2 negative label=$probeLabel roi=$negativeRoi positive_roi=$positiveRoi overlap=${roiOverlap(positiveRoi, negativeRoi)}")
        assertTrue(
            "the anchored card region must be the same card (overlap=${roiOverlap(positiveRoi, negativeRoi)})",
            roiOverlap(positiveRoi, negativeRoi) >= 0.9,
        )
        val (negativeFrame, negativeMarker) = stableMeasurement(negativeRoi)
        val negativePng = savePng(negativeFrame, "sippy-recents-fix2-protected")
        println(
            "SIPPY_RECENTS_FIX2 negative_result roi=$negativeRoi card_marker=$negativeMarker " +
                "screen_marker=${markerPixels(negativeFrame)} png=$negativePng",
        )
        assertEquals("this task's protected card must not expose the marker (was $negativeMarker)", 0, negativeMarker)

        assertTrue(
            "the app must return to the exact task/component while still protected",
            returnToOurActivity(component, taskId, requireMarker = false),
        )
        rule.runOnUiThread { owner.setPurpose(SecurePurpose.SIPPY_CREDENTIALS, false) }
        assertFalse(secureFlagSet(activity))
        // The immediate capture of the previous (still protected) frame is recorded, not asserted: a
        // cleared client flag is not a committed unprotected buffer.
        val beforeReleaseMarker = markerPixels(decode(rawCapture()))
        val release = awaitReleasedForegroundMarker(activity, component, taskId)
        println(
            "SIPPY_RECENTS_RECHECK release label=$probeLabel component=$component task=$taskId " +
                "before_capture_marker=$beforeReleaseMarker undecoded=${release.undecoded} " +
                "draws=${release.draws} commits=${release.commits} elapsed_ms=${release.elapsedMs} " +
                "accepted_marker=${release.acceptedMarker} accepted_size=${release.sizeAtAccept} " +
                "flag_at_accept=${release.flagAtAccept} foreground_at_accept=${release.foregroundAtAccept} " +
                "attempts=${release.attempts}",
        )
        val releasePng = release.accepted?.let { savePng(it, "sippy-recents-recheck-released-foreground") }
        if (release.accepted == null && release.firstDecoded != null) {
            savePng(checkNotNull(release.firstDecoded), "sippy-recents-recheck-released-first-capture")
        }
        println("SIPPY_RECENTS_RECHECK release_png=$releasePng")
        assertNotNull(
            "the released window must show the marker again (attempts=${release.attempts})",
            release.accepted,
        )
        assertTrue(
            "the foreground marker is visible after release (accepted=${release.acceptedMarker}, " +
                "attempts=${release.attempts})",
            release.acceptedMarker > 50,
        )
        // The visible marker is explained by a *new* frame of this window, not by timing luck: the real
        // decor drew again and, on API 29+, that frame was observed as committed after the flag clear.
        assertTrue(
            "the marker may only be accepted after a real new draw of this window (draws=${release.draws})",
            release.draws >= 1,
        )
        assertTrue(
            "the marker may only be accepted after that frame was committed (commits=${release.commits})",
            Build.VERSION.SDK_INT < 29 || release.commits >= 1,
        )
        assertFalse("the window must still be unprotected when the marker is accepted", release.flagAtAccept)
        assertTrue("the exact task/component must be in front at acceptance", release.foregroundAtAccept)

        // ---- third control: after release the same card shows the marker again.
        val restoredRoi = openOverview(component, taskId)
        val (restoredFrame, restoredMarker) = stableMeasurement(restoredRoi)
        val restoredPng = savePng(restoredFrame, "sippy-recents-fix2-restored")
        println(
            "SIPPY_RECENTS_FIX2 restored roi=$restoredRoi card_marker=$restoredMarker png=$restoredPng",
        )
        assertTrue("the card shows the marker again after release (was $restoredMarker)", restoredMarker > 50)
        assertTrue("the app must return to the exact task/component", returnToOurActivity(component, taskId))
    }
}
