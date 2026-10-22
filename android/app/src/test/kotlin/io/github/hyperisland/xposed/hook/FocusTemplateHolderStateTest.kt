package io.github.hyperisland.xposed.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusTemplateHolderStateTest {
    @Test
    fun rejectedCheckDispatchIsNotCompletionAndNativeCleanupCanStillFinish() {
        val state = FocusTemplateHolderState()
        assertTrue(state.requestCheck())
        state.failDetach()
        assertFalse(state.isFinished())
        assertFalse(state.takeCheck())
        state.enterNativeDetach()
        assertTrue(state.exitNativeDetach(success = true))
        assertTrue(state.isFinished())
    }

    @Test
    fun baseSuccessThenSubclassFailureNeverAuthorizesSlotRemoval() {
        val state = FocusTemplateHolderState()
        assertTrue(state.beginDetach(viewAttached = false, rootAttached = false))
        state.enterNativeDetach() // subclass
        state.enterNativeDetach() // base
        assertFalse(state.exitNativeDetach(success = true))
        assertFalse(state.isFinished())
        assertFalse(state.exitNativeDetach(success = false))
        assertFalse(state.isFinished())
        assertFalse(state.requestCheck())
        assertFalse(state.takeCheck())
    }

    @Test
    fun completeNestedSuccessAuthorizesSlotRemovalOnlyOnce() {
        val state = FocusTemplateHolderState()
        state.enterNativeDetach()
        state.enterNativeDetach()
        assertFalse(state.exitNativeDetach(success = true))
        assertTrue(state.exitNativeDetach(success = true))
        assertTrue(state.isFinished())
        state.enterNativeDetach()
        assertFalse(state.exitNativeDetach(success = true))
    }

    @Test
    fun laterNativeRetryCanFinishAfterPartialFailure() {
        val state = FocusTemplateHolderState()
        state.enterNativeDetach()
        assertFalse(state.exitNativeDetach(success = false))
        assertFalse(state.isFinished())
        state.enterNativeDetach()
        assertTrue(state.exitNativeDetach(success = true))
        assertTrue(state.isFinished())
    }

    @Test
    fun transientDetachAndReattachBeforeQueuedCheckKeepsTheHolderAlive() {
        val state = FocusTemplateHolderState()
        assertTrue(state.requestCheck())
        assertFalse(state.requestCheck())
        assertTrue(state.takeCheck())
        assertFalse(state.beginDetach(viewAttached = true, rootAttached = true))
        assertTrue(state.requestCheck())
        assertTrue(state.takeCheck())
        assertTrue(state.beginDetach(viewAttached = false, rootAttached = false))
    }

    @Test
    fun detachedChildDoesNotPermitCleanupOfAnAttachedRoot() {
        val state = FocusTemplateHolderState()
        assertFalse(state.beginDetach(viewAttached = false, rootAttached = true))
        assertFalse(state.beginDetach(viewAttached = true, rootAttached = false))
        assertTrue(state.beginDetach(viewAttached = false, rootAttached = false))
    }

    @Test
    fun neverAttachedPrebuiltHolderCanCompleteWithoutAnAttachEvent() {
        val state = FocusTemplateHolderState()
        assertTrue(state.requestCheck())
        assertTrue(state.takeCheck())
        assertTrue(state.beginDetach(viewAttached = false, rootAttached = false))
        state.finish()
        assertTrue(state.isFinished())
    }

    @Test
    fun nativeCleanupBeforeOurQueuedCheckPreventsASecondInvocation() {
        val state = FocusTemplateHolderState()
        assertTrue(state.requestCheck())
        state.finish()
        assertFalse(state.takeCheck())
        assertFalse(state.beginDetach(viewAttached = false, rootAttached = false))
        assertFalse(state.requestCheck())
    }

    @Test
    fun reentrantDetachCallbacksCannotScheduleCleanupWhileOnDetachIsRunning() {
        val state = FocusTemplateHolderState()
        assertTrue(state.beginDetach(viewAttached = false, rootAttached = false))
        assertFalse(state.requestCheck())
        assertFalse(state.beginDetach(viewAttached = false, rootAttached = false))
        state.finish()
        state.finish()
        assertFalse(state.requestCheck())
    }
}
