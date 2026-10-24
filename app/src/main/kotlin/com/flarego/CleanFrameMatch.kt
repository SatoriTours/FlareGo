package com.flarego

/**
 * The pure matching rule of the API 26–28 clean-frame barrier.
 *
 * `FrameMetrics.VSYNC_TIMESTAMP` and `System.nanoTime()` share the `CLOCK_MONOTONIC` timebase, so the
 * comparison is a real same-domain comparison of *frame start* against *the draw that no longer
 * contained the sensitive surface*:
 *
 * - a frame whose vsync is **before** that clean draw may still contain the removed surface (it is the
 *   frame that was already in flight) and must never release the protection;
 * - a frame whose vsync is at or after it started after the clean draw, so its content cannot contain
 *   the surface, and its metrics callback means it was submitted.
 *
 * Anything unknown (no clean draw recorded, a non-positive/garbage value) is rejected: the caller keeps
 * protecting instead of releasing on missing evidence.
 */
internal object CleanFrameMatch {
    fun accepts(cleanDrawNanos: Long, frameVsyncNanos: Long): Boolean =
        cleanDrawNanos > 0L && frameVsyncNanos > 0L && frameVsyncNanos >= cleanDrawNanos
}
