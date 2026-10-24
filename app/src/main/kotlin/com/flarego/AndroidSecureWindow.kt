package com.flarego

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import com.flarego.ui.r2.SecurePurpose
import com.flarego.ui.r2.SecureWindowOwnership
import com.flarego.ui.r2.SecureWindowPort
import com.flarego.ui.r2.SecureWindowSession
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The production secure-window owner for exactly one Android `Window`.
 *
 * It composes `FLAG_SECURE` ownership by purpose through [SecureWindowOwnership]:
 * - the pre-existing connection/token dialog and the Sippy credential editor never clear each
 *   other's flag;
 * - a `FLAG_SECURE` that was already set before this owner first touched the window is preserved and
 *   never cleared by it;
 * - no other window flag is read, added or cleared;
 * - every handle is bound to this owner's [generation], so a handle from a destroyed Activity can
 *   never modify a new window, and a leaving purpose only ever clears the bit this owner set last.
 *
 * Like any Android window-flag change, `request`/`release`/`setPurpose`/`close` must run on the main
 * thread; the production call sites are the Compose composition and the Activity lifecycle.
 */
class AndroidSecureWindow(private val window: Window) : SecureWindowPort {
    private val baselineSecure: Boolean =
        (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

    override val generation: Long = NEXT_GENERATION.getAndIncrement()

    private val ownership = SecureWindowOwnership(generation, baselineSecure)
    private val handles = Collections.newSetFromMap(IdentityHashMap<SecureWindowSession, Boolean>())

    /** Only the handle the *legacy boolean* callback owns; explicit handles never live here. */
    private val legacyHandles = IdentityHashMap<SecurePurpose, Handle>()
    /** Handles whose release waits for a committed frame that no longer contains the surface. */
    private class Retirement(val armedGeneration: Long) {
        var awaitingCommit = false
    }

    private val retiring = IdentityHashMap<SecureWindowSession, Retirement>()

    /** Test-only observation of the deferred release; production never sets this. */
    internal var onCleanFrameReleaseForTest: ((generation: Long) -> Unit)? = null

    /** Test-only: simulate a window that cannot observe frame commits (API<29/software/failure). */
    internal var suppressCleanFrameCommitForTest = false

    /** Test-only: force the API 26–28 compatibility path on this (newer) device. */
    internal var forceCompatCleanFrameForTest = false

    /** Test-only negative: the clean draw itself never happens (no usable evidence). */
    internal var suppressCompatCleanDrawForTest = false

    /** Test-only observation of the compatibility path's actual evidence pair. */
    internal var onCompatCleanFrameForTest: ((generation: Long, cleanDrawNanos: Long, vsyncNanos: Long) -> Unit)? = null
    private var closed = false

    override fun request(purpose: SecurePurpose): SecureWindowSession? {
        if (closed) return null
        val mustSet = ownership.acquire(purpose)
        if (mustSet) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val handle = Handle(purpose)
        handles.add(handle)
        return handle
    }

    override fun release(session: SecureWindowSession) {
        if (closed) return
        // Only a live handle this owner really issued may change this window: a foreign, stale or
        // already released handle is a no-op and never clears somebody else's protection.
        if (!handles.remove(session)) return
        (session as? Handle)?.markReleased()
        if (legacyHandles[session.purpose] === session) legacyHandles.remove(session.purpose)
        if (ownership.release(session.purpose)) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /**
     * Two-phase retirement, step 1: arm it for exactly this live handle. **The protection is kept.**
     *
     * Nothing is released here: `publish`/state changes are not a frame commit, and a window that
     * cannot observe commits also keeps protecting (fail-closed).
     */
    override fun armRetirement(session: SecureWindowSession): Boolean {
        if (closed) return false
        if (!handles.contains(session)) return false
        if (retiring.containsKey(session)) return true
        retiring[session] = Retirement(armedGeneration = session.generation)
        return true
    }

    /**
     * Two-phase retirement, step 2: the composition no longer contains the sensitive surface.
     *
     * Only here is the next committed frame bound, so the commit that releases is a frame *after* the
     * removal — never the dirty frame. API 29+ hardware only; when the callback is unavailable, the
     * window is software rendered, or registration fails, the handle simply keeps protecting (no
     * timer, no release). A late or duplicate callback releases at most that exact handle once.
     */
    override fun contentRemoved(session: SecureWindowSession) {
        if (closed) return
        val state = retiring[session] ?: return
        if (!handles.contains(session)) return
        if (state.awaitingCommit) return
        if (suppressCleanFrameCommitForTest) return
        val decor = window.decorView
        if (!decor.isHardwareAccelerated) return
        val compatibility = forceCompatCleanFrameForTest || Build.VERSION.SDK_INT < FRAME_COMMIT_MIN_SDK
        if (compatibility) {
            // API 26–28 (or a forced run of that policy): the commit callback does not exist there.
            registerCompatCleanFrame(session, state, decor)
            return
        }
        try {
            state.awaitingCommit = true
            decor.viewTreeObserver.registerFrameCommitCallback {
                // Exact generation+handle only; a stale callback cannot release a newer session.
                if (closed) return@registerFrameCommitCallback
                if (session.generation != generation) return@registerFrameCommitCallback
                if (!handles.contains(session)) return@registerFrameCommitCallback
                retiring.remove(session)
                onCleanFrameReleaseForTest?.invoke(generation)
                release(session)
            }
        } catch (_: Throwable) {
            // Keep protecting: no reliable commit evidence means the flag stays.
            state.awaitingCommit = false
        }
    }

    /**
     * The API 26–28 barrier, using only public APIs of the *same* clock domain.
     *
     * Two frames, both drawn after the composition reported the removal:
     * 1. the draw pass that follows the removal records `System.nanoTime()` (CLOCK_MONOTONIC, the same
     *    timebase as `FrameMetrics.VSYNC_TIMESTAMP`), then schedules one more draw;
     * 2. the next frame's vsync is strictly later than that draw, so a metrics callback whose
     *    `VSYNC_TIMESTAMP >= cleanDrawNanos` describes a frame that started **after** the clean draw and
     *    has already been submitted. The dirty/earlier frame (whose vsync precedes the clean draw) can
     *    never satisfy the comparison.
     *
     * Frame metrics can be dropped, delayed or never delivered (no further draw, software renderer,
     * dead window). None of those release the handle: this path never uses a timeout or a fixed delay,
     * and a callback for another generation/handle is ignored. Registration or delivery failure keeps
     * the protection. A frame commit is still not a proof that the compositor presented it.
     */
    private fun registerCompatCleanFrame(
        session: SecureWindowSession,
        state: Retirement,
        decor: android.view.View,
    ) {
        // `FrameMetrics.VSYNC_TIMESTAMP` exists from API 26 and the app's `minSdk` is 26, so every
        // supported platform has the whole compatibility path.
        val main = Handler(Looper.getMainLooper())
        if (Looper.myLooper() != main.looper) {
            main.post { compatCleanFrameOnMain(session, state, decor) }
        } else {
            compatCleanFrameOnMain(session, state, decor)
        }
    }

    private fun compatCleanFrameOnMain(
        session: SecureWindowSession,
        state: Retirement,
        decor: android.view.View,
    ) {
        if (closed || !handles.contains(session) || state.awaitingCommit) return
        val main = Handler(Looper.getMainLooper())
        val observer = decor.viewTreeObserver
        var cleanDrawNanos = 0L
        var done = false
        var metricsListener: Window.OnFrameMetricsAvailableListener? = null
        var drawListener: ViewTreeObserver.OnDrawListener? = null

        val detach = {
            main.post {
                val m = metricsListener
                val d = drawListener
                if (observer.isAlive) {
                    if (d != null) runCatching { observer.removeOnDrawListener(d) }
                    if (m != null) runCatching { window.removeOnFrameMetricsAvailableListener(m) }
                }
                metricsListener = null
                drawListener = null
            }
            Unit
        }

        val metrics = object : Window.OnFrameMetricsAvailableListener {
            override fun onFrameMetricsAvailable(
                window: Window,
                frameMetrics: FrameMetrics,
                dropCountSinceLastInvocation: Int,
            ) {
                if (done) return
                if (closed || session.generation != generation || !handles.contains(session)) {
                    done = true
                    detach()
                    return
                }
                val drawn = cleanDrawNanos
                if (drawn <= 0L) return
                val vsync =
                    try {
                        frameMetrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
                    } catch (_: Throwable) {
                        return
                    }
                if (!CleanFrameMatch.accepts(drawn, vsync)) return
                done = true
                retiring.remove(session)
                onCompatCleanFrameForTest?.invoke(generation, drawn, vsync)
                detach()
                onCleanFrameReleaseForTest?.invoke(generation)
                release(session)
            }
        }
        val draws = ViewTreeObserver.OnDrawListener {
            if (closed || done || cleanDrawNanos > 0L) return@OnDrawListener
            if (suppressCompatCleanDrawForTest) {
                // Negative evidence: without a real clean draw nothing can ever be matched.
                detach()
                return@OnDrawListener
            }
            cleanDrawNanos = System.nanoTime()
            // A listener cannot remove itself during dispatch, and the next frame may only be
            // scheduled once this draw pass has finished.
            main.post {
                val d = drawListener
                if (d != null && observer.isAlive) runCatching { observer.removeOnDrawListener(d) }
                drawListener = null
                if (!closed) runCatching { decor.invalidate() }
            }
        }
        metricsListener = metrics
        drawListener = draws

        try {
            state.awaitingCommit = true
            window.addOnFrameMetricsAvailableListener(metrics, main)
            observer.addOnDrawListener(draws)
            decor.invalidate()
        } catch (_: Throwable) {
            state.awaitingCommit = false
            detach()
        }
    }

    /**
     * Boolean-style set/release for the pre-existing connect-dialog callback.
     *
     * It owns exactly one internal handle per purpose, so repeated `true` is idempotent and `false`
     * only releases that handle — never an explicit handle of the same purpose.
     */
    fun setPurpose(purpose: SecurePurpose, held: Boolean) {
        if (closed) return
        if (held) {
            if (legacyHandles[purpose] == null) {
                val handle = request(purpose) as? Handle ?: return
                legacyHandles[purpose] = handle
            }
        } else {
            legacyHandles.remove(purpose)?.let(::release)
        }
    }

    /** True while the real window currently carries the protection (any purpose). */
    fun isProtected(): Boolean =
        !closed && (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

    /**
     * Window teardown: cancel this owner's eligibility and bookkeeping but **never clear the physical
     * flag**, because clearing it while the dying window's surface may still be shown would expose the
     * last frame. The window destruction removes the layer itself.
     */
    fun closeForTeardown() {
        if (closed) return
        closed = true
        for (handle in handles) (handle as? Handle)?.markReleased()
        handles.clear()
        legacyHandles.clear()
        retiring.clear()
        ownership.close() // bookkeeping only; the flag is intentionally left as it is
    }

    /** Releases every purpose this owner holds; a flag it does not own is preserved. */
    fun close() {
        if (closed) return
        closed = true
        for (handle in handles) (handle as? Handle)?.markReleased()
        handles.clear()
        legacyHandles.clear()
        if (ownership.close()) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private inner class Handle(override val purpose: SecurePurpose) : SecureWindowSession {
        private var released = false

        override val generation: Long get() = this@AndroidSecureWindow.generation

        fun markReleased() { released = true }

        override fun isProtected(): Boolean =
            !released && !closed && generation == this@AndroidSecureWindow.generation &&
                this@AndroidSecureWindow.isProtected()
    }

    private companion object {
        private val NEXT_GENERATION = AtomicLong(1L)

        /** `ViewTreeObserver.registerFrameCommitCallback` exists from API 29 (hardware only). */
        const val FRAME_COMMIT_MIN_SDK = 29

    }
}
