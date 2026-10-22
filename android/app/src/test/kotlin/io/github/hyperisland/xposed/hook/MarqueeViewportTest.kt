package io.github.hyperisland.xposed.hook

import org.junit.Assert.*
import org.junit.Test

class MarqueeViewportTest {
    @Test fun rightFadeKeepsStartAndHalvesTheAddedEndGap() {
        val viewport = MarqueeViewport()
        viewport.reset(833, 0, 0)
        viewport.parent(-420, 0, 422, 0, 9, false, true, 244.07f, 0.79f, 833f)
        viewport.parent(0, 0, 422, 0, 0, true, false)
        val originalLeft = viewport.left
        viewport.insetForEdgeShade(368f, 422f, 422, 3.25f)
        val range = MarqueeScrollRange()
        assertTrue(range.update(0f, 833f, viewport.left, viewport.right, false))
        assertEquals(originalLeft, viewport.left, 0f)
        val drawnEnd = -420f + 244.07f + 833f * 0.21f + (833f - range.end) * 0.79f
        assertEquals(388.875f, drawnEnd, 0.001f)
    }
    @Test fun rtlFadeUsesTheSameCompactTailGap() {
        val viewport = MarqueeViewport()
        viewport.reset(800, 0, 0)
        viewport.parent(0, 0, 400, 0, 0, true, false)
        viewport.insetForEdgeShade(0f, 54f, 400, 3f)
        val range = MarqueeScrollRange()
        assertTrue(range.update(0f, 800f, viewport.left, viewport.right, true))
        assertEquals(400f, 800f - range.start, 0f)
        assertEquals(28.5f, -range.end, 0f)
    }
    @Test fun absentOrMiddleOrOversizedOverlayDoesNotAddBlankSpace() {
        val viewport = MarqueeViewport()
        viewport.reset(800, 0, 0)
        viewport.parent(0, 0, 400, 0, 0, true, false)
        for ((left, right) in listOf(400f to 400f, 100f to 150f, 0f to 400f, Float.NaN to 400f)) {
            viewport.insetForEdgeShade(left, right, 400, 3f)
            assertEquals(0f, viewport.left, 0f)
            assertEquals(400f, viewport.right, 0f)
        }
    }
    @Test fun hiddenFadeOnNextFrameRestoresTheFullViewport() {
        val viewport = MarqueeViewport()
        viewport.reset(800, 0, 0)
        viewport.parent(0, 0, 400, 0, 0, true, false)
        viewport.insetForEdgeShade(346f, 400f, 400, 3f)
        assertEquals(371.5f, viewport.right, 0f)
        viewport.reset(800, 0, 0)
        viewport.parent(0, 0, 400, 0, 0, true, false)
        assertEquals(400f, viewport.right, 0f)
    }
    @Test fun shadeGuardIsNotMultipliedByTextScale() {
        for (scale in listOf(1f, 0.79f, 0.5f)) {
            val viewport = MarqueeViewport()
            viewport.reset(1000, 0, 0)
            viewport.parent(0, 0, 400, 0, 0, true, false, 0f, scale)
            viewport.insetForEdgeShade(346f, 400f, 400, 3f)
            assertEquals(371.5f, viewport.right * scale, 0.001f)
        }
    }
    @Test fun parentClippingCannotUndoTheShadeInset() {
        val viewport = MarqueeViewport()
        viewport.reset(800, 0, 0)
        viewport.parent(0, 0, 400, 0, 0, true, false)
        viewport.insetForEdgeShade(346f, 400f, 400, 3f)
        viewport.parent(100, 0, 1000, 0, 0, true, false)
        assertEquals(371.5f, viewport.right, 0f)
    }
    @Test fun aViewportAlreadyClearOfTheFadeNeverExpands() {
        val viewport = MarqueeViewport()
        viewport.reset(800, 0, 0)
        viewport.parent(0, 0, 400, 70, 70, true, true)
        viewport.insetForEdgeShade(346f, 400f, 400, 3f)
        viewport.insetForEdgeShade(0f, 54f, 400, 3f)
        assertEquals(70f, viewport.left, 0f)
        assertEquals(330f, viewport.right, 0f)
    }
    @Test fun nativeRightHolderTransformAlignsTheFirstAndLastGlyphs() {
        val viewport = MarqueeViewport()
        viewport.reset(833, 0, 0)
        assertTrue(viewport.parent(-420, 0, 422, 0, 9, false, true,
            244.07f, 0.79f, 833f))
        val range = MarqueeScrollRange()
        assertTrue(range.updateMeasured(833f, 833f, 0f, 1f, viewport.left, viewport.right, false))
        fun screenX(glyph: Float, scroll: Float) = -420f + 244.07f + 833f * 0.21f + (glyph - scroll) * 0.79f
        assertEquals(0f, screenX(0f, range.start), 0.001f)
        assertEquals(413f, screenX(833f, range.end), 0.001f)
        assertTrue(range.start > -2f)
        assertTrue(range.end in 308f..310f)
    }
    @Test fun nativeLeftHolderScaledTitleFitsAndMustNotScroll() {
        val viewport = MarqueeViewport()
        viewport.reset(374, 0, 0)
        viewport.parent(9, 0, 355, 9, 0, false, true, 0f, 0.79f, 0f)
        val range = MarqueeScrollRange()
        assertFalse(range.updateMeasured(374f, 374f, 0f, 0f, viewport.left, viewport.right, false))
    }
    @Test fun nestedTransformsComposeBeforeInverseClipping() {
        val viewport = MarqueeViewport()
        viewport.reset(1000, 0, 0)
        viewport.parent(-100, 0, 1000, 0, 0, false, false, 50f, 0.5f, 0f)
        viewport.parent(20, 10, 300, 0, 0, true, false, 30f, 2f, 0f)
        // parent x = 2 * (0.5 * local - 50) + 20 + 30 - 10 = local - 60.
        assertEquals(60f, viewport.left, 0f)
        assertEquals(360f, viewport.right, 0f)
    }
    @Test fun translationOfAnUnclippedIntermediateViewStillMatters() {
        val viewport = MarqueeViewport()
        viewport.reset(800, 0, 0)
        viewport.parent(-400, 0, 300, 0, 0, false, false, 400f)
        viewport.parent(0, 0, 300, 0, 0, true, false)
        assertEquals(0f, viewport.left, 0f)
        assertEquals(300f, viewport.right, 0f)
    }
    @Test fun zeroOrInvalidScaleWaitsAndCanRecoverOnTheNextLayout() {
        val viewport = MarqueeViewport()
        for (scale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            viewport.reset(800, 0, 0)
            assertFalse(viewport.parent(0, 0, 300, 0, 0, true, false, 0f, scale))
            assertEquals(0f, viewport.right - viewport.left, 0f)
        }
        viewport.reset(800, 0, 0)
        assertTrue(viewport.parent(0, 0, 300, 0, 0, true, false))
        assertEquals(300f, viewport.right, 0f)
    }
    @Test fun resizedNativeRightHolderKeepsBothEndsAcrossAnimationScales() {
        val viewport = MarqueeViewport()
        val range = MarqueeScrollRange()
        for (scale in listOf(1f, 0.9f, 0.79f)) {
            // Native compensation keeps the first glyph at the parent origin.
            val translation = 420f - 833f * (1f - scale)
            viewport.reset(833, 0, 0)
            viewport.parent(-420, 0, 422, 0, 9, false, true, translation, scale, 833f)
            assertTrue(range.update(0f, 833f, viewport.left, viewport.right, false))
            assertEquals(0f, -range.start * scale, 0.001f)
            assertEquals(413f, (833f - range.end) * scale, 0.001f)
        }
    }
    @Test fun deviceRightAlignedTextUsesNegativeOriginAt300dp() {
        val viewport = MarqueeViewport()
        viewport.reset(818, 0, 0)
        viewport.parent(-405, 0, 422, 0, 9, false, true)
        viewport.parent(0, 0, 422, 0, 0, true, true)
        val range = MarqueeScrollRange()
        assertTrue(range.update(0f, 818f, viewport.left, viewport.right, false))
        assertEquals(-405f, range.start, 0f)
        assertEquals(0f, range.end, 0f)
    }
    @Test fun deviceLeftTextIncludesOffsetAndParentPadding() {
        val viewport = MarqueeViewport()
        viewport.reset(374, 0, 0)
        viewport.parent(9, 0, 355, 9, 0, false, true)
        viewport.parent(0, 0, 355, 0, 0, true, true)
        assertEquals(0f, viewport.left, 0f)
        assertEquals(346f, viewport.right, 0f)
    }
    @Test fun nonClippingIntermediateParentStillContributesOffset() {
        val viewport = MarqueeViewport()
        viewport.reset(800, 0, 0)
        viewport.parent(-400, 0, 300, 0, 0, false, false)
        viewport.parent(100, 0, 500, 0, 0, true, false)
        assertEquals(300f, viewport.left, 0f)
        assertEquals(800f, viewport.right, 0f)
    }
    @Test fun ancestorScrollingMovesViewportButTextScrollDoesNot() {
        val viewport = MarqueeViewport()
        viewport.reset(800, 0, 0)
        viewport.parent(0, 100, 300, 0, 0, true, false)
        assertEquals(100f, viewport.left, 0f)
        assertEquals(400f, viewport.right, 0f)
    }
    @Test fun resetAfterWidthChangeDropsPreviousOffsets() {
        val viewport = MarqueeViewport()
        viewport.reset(833, 0, 0)
        viewport.parent(-544, 0, 298, 0, 9, false, true)
        viewport.reset(818, 0, 0)
        viewport.parent(-405, 0, 422, 0, 9, false, true)
        assertEquals(405f, viewport.left, 0f)
        assertEquals(818f, viewport.right, 0f)
    }
    @Test fun textPaddingAndNestedClipsIntersect() {
        val viewport = MarqueeViewport()
        viewport.reset(800, 10, 20)
        viewport.parent(-200, 0, 500, 15, 25, true, true)
        viewport.parent(-100, 0, 300, 0, 0, true, false)
        assertEquals(300f, viewport.left, 0f)
        assertEquals(600f, viewport.right, 0f)
    }
}
