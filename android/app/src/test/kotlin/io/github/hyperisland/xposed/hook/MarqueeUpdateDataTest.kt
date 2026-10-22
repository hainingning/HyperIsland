package io.github.hyperisland.xposed.hook

import miui.systemui.dynamicisland.window.content.DynamicIslandContentView
import org.junit.Assert.*
import org.junit.Test

class MarqueeUpdateDataTest {
    private data class NotificationData(val ongoing: Boolean)
    private fun owner(data: Any?) = DynamicIslandContentView().apply { currentIslandData = data }
    private fun resume(owner: DynamicIslandContentView, data: Any?) =
        DynamicIslandContentView.`updateBigIslandView$1`(owner, data)
    private fun policy(update: MarqueeUpdateData, explicit: Boolean = false) =
        IslandMarqueePolicy.resolve(true, explicit,
            (update.islandData as? NotificationData)?.ongoing, "2")

    @Test fun ordinaryNotificationKeepsGlobalMarqueeAcrossSuspensionAndResume() {
        val data = NotificationData(false)
        val owner = owner(data)
        val first = MarqueeUpdateData.resolve(owner, data, Any())
        val continuation = resume(owner, data)
        val resumed = MarqueeUpdateData.resolve(owner, null, continuation)
        // Native proceed consumes the high resume bit; recovery must precede it.
        continuation.label = 1
        assertTrue(resumed.canApplyAfter(owner))
        assertSame(data, resumed.islandData)
        assertEquals(IslandMarqueePolicy(true, 2, false), policy(first))
        assertEquals(policy(first), policy(resumed))
        // Negative control: the previous argument-only lookup disabled scrolling.
        assertEquals(IslandMarqueePolicy(false, 0, false),
            IslandMarqueePolicy.resolve(true, false, null, "2"))
    }

    @Test fun explicitOngoingMarqueeKeepsScrollingWithoutAutoHideAfterResume() {
        val data = NotificationData(true)
        val owner = owner(data)
        val update = MarqueeUpdateData.resolve(owner, null, resume(owner, data))
        assertEquals(IslandMarqueePolicy(true, 0, false), policy(update, true))
    }

    @Test fun ongoingNotificationStillDoesNotInheritGlobalMarquee() {
        val data = NotificationData(true)
        val owner = owner(data)
        assertEquals(IslandMarqueePolicy(false, 0, false),
            policy(MarqueeUpdateData.resolve(owner, null, resume(owner, data))))
    }

    @Test fun directDataTakesPrecedenceOverContinuationAndPreviousData() {
        val old = NotificationData(true)
        val fresh = NotificationData(false)
        val owner = owner(old)
        val update = MarqueeUpdateData.resolve(owner, fresh, resume(owner, old))
        assertSame(fresh, update.islandData)
        assertTrue(update.canApplyAfter(owner))
    }

    @Test fun realNullUpdateDoesNotBorrowPreviousNotification() {
        val owner = owner(NotificationData(false))
        for (continuation in listOf(null, Any())) {
            val update = MarqueeUpdateData.resolve(owner, null, continuation)
            assertTrue(update.shouldApply)
            assertNull(update.islandData)
            assertEquals(IslandMarqueePolicy(false, 0, false), policy(update))
        }
    }

    @Test fun staleResumeCannotStopOrReapplyTheReplacementIsland() {
        val old = NotificationData(false)
        val owner = owner(NotificationData(false)) // Equal fields, distinct notification identity.
        val update = MarqueeUpdateData.resolve(owner, null, resume(owner, old))
        assertFalse(update.shouldApply)
        assertFalse(update.canApplyAfter(owner))
    }

    @Test fun replacementDuringNativeProceedInvalidatesRecoveredData() {
        val data = NotificationData(false)
        val owner = owner(data)
        val update = MarqueeUpdateData.resolve(owner, null, resume(owner, data))
        owner.currentIslandData = NotificationData(false)
        assertTrue(update.shouldApply)
        assertFalse(update.canApplyAfter(owner))
    }

    @Test fun clearedIslandRejectsAResume() {
        val data = NotificationData(false)
        val owner = owner(null)
        assertFalse(MarqueeUpdateData.resolve(owner, null, resume(owner, data)).shouldApply)
    }

    @Test fun ownerAndSavedReceiverMustBothMatch() {
        val data = NotificationData(false)
        val owner = owner(data)
        val another = owner(data)
        val continuation = resume(owner, data)
        continuation.`this$0` = another
        assertFalse(MarqueeUpdateData.resolve(owner, null, continuation).shouldApply)
        continuation.`this$0` = owner
        continuation.`L$0` = another
        assertFalse(MarqueeUpdateData.resolve(owner, null, continuation).shouldApply)
    }

    @Test fun onlyTheVerifiedSuspensionPointCanRecoverData() {
        val data = NotificationData(false)
        val owner = owner(data)
        val continuation = resume(owner, data)
        for (label in listOf(0, 1, Int.MIN_VALUE, Int.MIN_VALUE or 2)) {
            continuation.label = label
            assertFalse(MarqueeUpdateData.resolve(owner, null, continuation).shouldApply)
        }
    }

    @Test fun absentSavedDataDoesNotFallBackToCurrentOrCachedData() {
        val owner = owner(NotificationData(false))
        assertFalse(MarqueeUpdateData.resolve(owner, null, resume(owner, null)).shouldApply)
    }

    @Test fun failedNativeFieldLookupLeavesTheUpdateUntouched() {
        val data = NotificationData(false)
        val owner = owner(data)
        owner.failRead = true
        assertFalse(MarqueeUpdateData.resolve(owner, null, resume(owner, data)).shouldApply)
    }

    @Test fun failedPostUpdateLookupCannotReapplyScrolling() {
        val data = NotificationData(false)
        val owner = owner(data)
        val update = MarqueeUpdateData.resolve(owner, null, resume(owner, data))
        owner.failRead = true
        assertFalse(update.canApplyAfter(owner))
    }
}
