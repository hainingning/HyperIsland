package io.github.hyperisland.xposed.hook

import org.junit.Assert.*
import org.junit.Test

class MarqueeScrollRangeTest {
    @Test fun nativeTimerNarrowRightAlignedViewStartsAtVisibleLeftAndShowsTheTail() {
        // TimerTextEffectSpan draws LTR full-line text at max(0, x - textWidth).
        // After the native view contracts to its viewport, x is 413, not 838.
        val range = MarqueeScrollRange()
        assertTrue(range.updateMeasured(838f, 413f, 0f, 1f, 0f, 413f, false))
        assertEquals(0f, 0f - range.start, 0f)
        assertEquals(413f, 838f - range.end, 0f)
        assertEquals(425f, range.distance, 0f)
    }
    @Test fun nativeTimerWidthContractionPreservesBothVisibleEndpoints() {
        val range = MarqueeScrollRange()
        for (layoutWidth in listOf(838f, 413f, 600f, 838f)) {
            val visibleLeft = maxOf(0f, layoutWidth - 413f)
            assertTrue(range.updateMeasured(838f, layoutWidth, 0f, 1f,
                visibleLeft, layoutWidth, false))
            assertEquals(visibleLeft, -range.start, 0f)
            assertEquals(layoutWidth, 838f - range.end, 0f)
        }
    }
    @Test fun nativeTimerNarrowRightAlignedViewIncludesCompoundPadding() {
        val range = MarqueeScrollRange()
        assertTrue(range.updateMeasured(838f, 413f, 12f, 1f, 12f, 425f, false))
        assertEquals(12f, 12f - range.start, 0f)
        assertEquals(425f, 850f - range.end, 0f)
    }
    @Test fun nativeTimerCenteredOverflowKeepsItsNegativeDrawingOrigin() {
        val range = MarqueeScrollRange()
        assertTrue(range.updateMeasured(800f, 400f, 0f, 0.5f, 0f, 400f, false))
        assertEquals(-200f, range.start, 0f)
        assertEquals(200f, range.end, 0f)
    }
    @Test fun nativeTimerRtlOverflowRetainsTheNativeRightOrigin() {
        val range = MarqueeScrollRange()
        assertTrue(range.updateMeasured(800f, 400f, 0f, 1f, 0f, 400f, true))
        assertEquals(0f, range.start, 0f)
        assertEquals(-400f, range.end, 0f)
    }
    @Test fun nativeTimerRightAlignedMeasuredGlyphsReachRightEdge() {
        val range = MarqueeScrollRange()
        assertTrue(range.updateMeasured(820.5f, 821f, 0f, 1f, 408f, 821f, false))
        assertEquals(-407.5f, range.start, 0f)
        assertEquals(0f, range.end, 0f)
    }
    @Test fun nativeTimerLeftAlignedMeasurementIncludesClippedTail() {
        val range = MarqueeScrollRange()
        assertTrue(range.updateMeasured(373.5f, 374f, 0f, 0f, 0f, 346f, false))
        assertEquals(0f, range.start, 0f)
        assertEquals(27.5f, range.end, 0f)
    }
    @Test fun measuredCenteredRtlTextReversesDirection() {
        val range = MarqueeScrollRange()
        assertTrue(range.updateMeasured(800f, 1000f, 10f, 0.5f, 210f, 710f, true))
        assertEquals(200f, range.start, 0f)
        assertEquals(-100f, range.end, 0f)
    }
    @Test fun measuredShorterReplacementTextStopsScrolling() {
        val range = MarqueeScrollRange()
        assertFalse(range.updateMeasured(200f, 821f, 0f, 1f, 408f, 821f, false))
        assertEquals(0f, range.distance, 0f)
    }
    @Test fun rightAlignedOversizedViewEndsAtZeroWithoutBlankTail() {
        val range = MarqueeScrollRange()
        assertTrue(range.update(0f, 833f, 544f, 833f, false))
        assertEquals(-544f, range.start, 0f)
        assertEquals(0f, range.end, 0f)
        assertEquals(833f, 833f - range.position(9999f), 0f)
    }
    @Test fun leftAlignedViewKeepsNormalPositiveScroll() {
        val range = MarqueeScrollRange()
        assertTrue(range.update(0f, 374f, 0f, 221f, false))
        assertEquals(0f, range.start, 0f)
        assertEquals(153f, range.end, 0f)
        assertEquals(221f, 374f - range.end, 0f)
    }
    @Test fun centeredClippingNeedsBothNegativeAndPositiveOffsets() {
        val range = MarqueeScrollRange()
        assertTrue(range.update(0f, 800f, 200f, 600f, false))
        assertEquals(-200f, range.start, 0f)
        assertEquals(200f, range.end, 0f)
    }
    @Test fun rtlReadsFromRightAndStopsAtTheLeftEdge() {
        val range = MarqueeScrollRange()
        assertTrue(range.update(0f, 800f, 200f, 600f, true))
        assertEquals(200f, range.start, 0f)
        assertEquals(-200f, range.end, 0f)
        assertEquals(-200f, range.position(9999f), 0f)
    }
    @Test fun paddingAndLayoutAlignmentArePartOfTheCoordinates() {
        val range = MarqueeScrollRange()
        assertTrue(range.update(25f, 825f, 125f, 625f, false))
        assertEquals(-100f, range.start, 0f)
        assertEquals(200f, range.end, 0f)
    }
    @Test fun fittingOrInvisibleTextClearsThePreviousRange() {
        val range = MarqueeScrollRange()
        range.update(0f, 833f, 544f, 833f, false)
        assertFalse(range.update(0f, 100f, 0f, 100f, false))
        assertEquals(0f, range.distance, 0f)
        assertFalse(range.update(0f, 833f, 10f, 10f, false))
        assertFalse(range.update(Float.NaN, 833f, 0f, 100f, false))
    }
    @Test fun resizingAtEndClampsToNewEdge() {
        val range = MarqueeScrollRange()
        range.update(0f, 833f, 544f, 833f, false)
        val oldDistance = range.distance
        range.update(0f, 833f, 300f, 833f, false)
        assertEquals(0f, range.position(oldDistance), 0f)
    }
    @Test fun everyPointStaysBetweenEndpointsWithoutOvershoot() {
        val range = MarqueeScrollRange()
        for (rtl in listOf(false,true)) {
            range.update(0f, 833f, 544f, 833f, rtl)
            for (travel in -100..1000) assertTrue(range.position(travel.toFloat()) in -544f..0f)
        }
    }
}
