package com.flarego

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure negatives for the API 26–28 clean-frame matching rule (no device, no window).
 *
 * The rule must only ever accept a frame that started *at or after* the clean draw, and must reject
 * every "no/missing/duplicate-looking" evidence instead of releasing on it.
 */
class CleanFrameMatchTest {
    @Test
    fun aFrameThatStartedBeforeTheCleanDrawIsNeverAccepted() {
        val cleanDraw = 10_000_000_000L
        assertFalse(CleanFrameMatch.accepts(cleanDraw, cleanDraw - 1), "the in-flight dirty frame")
        assertFalse(CleanFrameMatch.accepts(cleanDraw, cleanDraw - 16_666_666), "one refresh earlier")
        assertFalse(CleanFrameMatch.accepts(cleanDraw, 0L), "a zero/unknown vsync")
        assertFalse(CleanFrameMatch.accepts(cleanDraw, -1L), "a negative vsync")
    }

    @Test
    fun onlyAFrameAtOrAfterTheCleanDrawIsAccepted() {
        val cleanDraw = 10_000_000_000L
        assertTrue(CleanFrameMatch.accepts(cleanDraw, cleanDraw), "the same instant is not earlier")
        assertTrue(CleanFrameMatch.accepts(cleanDraw, cleanDraw + 16_666_666), "the next frame")
        assertTrue(CleanFrameMatch.accepts(cleanDraw, cleanDraw + 5_000_000_000L), "a later frame is fine")
    }

    @Test
    fun noCleanDrawMeansNoEvidenceAndNeverReleases() {
        assertFalse(CleanFrameMatch.accepts(0L, 1_000_000_000L), "no draw was observed")
        assertFalse(CleanFrameMatch.accepts(-5L, 1_000_000_000L), "garbage draw timestamp")
    }
}
