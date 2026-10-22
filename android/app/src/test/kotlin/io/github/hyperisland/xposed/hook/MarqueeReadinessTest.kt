package io.github.hyperisland.xposed.hook

import org.junit.Assert.*
import org.junit.Test

class MarqueeReadinessTest {
    @Test fun firstFrameWithoutLayoutResumesAfterDraw() {
        val state = MarqueeReadiness()
        state.start()
        assertTrue(state.awaitDraw())
        assertFalse(state.onDraw(true, true, false))
        assertTrue(state.active)
        assertTrue(state.waiting)
        assertTrue(state.onDraw(true, true, true))
        assertFalse(state.waiting)
    }
    @Test fun transientHiddenFramesDoNotDuplicateSubscriptions() {
        val state = MarqueeReadiness()
        state.start()
        assertTrue(state.awaitDraw())
        repeat(100) {
            assertFalse(state.awaitDraw())
            assertFalse(state.onDraw(true, true, false))
        }
        assertTrue(state.onDraw(true, true, true))
        assertFalse(state.onDraw(true, true, true))
    }
    @Test fun detachCannotRestartFromLateDraw() {
        val state = MarqueeReadiness()
        state.start(); state.awaitDraw()
        assertFalse(state.onDraw(false, true, true))
        state.stop()
        assertFalse(state.onDraw(true, true, true))
        assertFalse(state.awaitDraw())
    }
    @Test fun configDisableCannotRevivePreviousPlayback() {
        val state = MarqueeReadiness()
        state.start(); state.awaitDraw()
        assertFalse(state.onDraw(true, false, true))
        state.stop()
        assertFalse(state.active)
        assertFalse(state.waiting)
    }
    @Test fun notificationReplacementStartsWithIndependentReadiness() {
        val state = MarqueeReadiness()
        state.start(); state.awaitDraw(); state.stop(); state.start()
        assertFalse(state.onDraw(true, true, true))
        assertTrue(state.awaitDraw())
        assertTrue(state.onDraw(true, true, true))
    }
    @Test fun laterTransitionCanPauseAndResumeAgain() {
        val state = MarqueeReadiness()
        state.start()
        repeat(20) {
            assertTrue(state.awaitDraw())
            assertTrue(state.onDraw(true, true, true))
        }
    }
}
