package io.github.hyperisland.xposed.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class IslandMarqueePolicyTest {
    @Test fun ongoingChannelOptInScrollsWithoutInheritedAutoHide() {
        for (setting in listOf("off", "1", "2", "1_override", "2_override")) {
            assertEquals(setting, IslandMarqueePolicy(true, 0, false),
                IslandMarqueePolicy.resolve(true, true, true, setting))
        }
    }

    @Test fun ongoingNotificationsKeepTheirExistingGlobalDefault() {
        assertEquals(IslandMarqueePolicy(false, 0, false),
            IslandMarqueePolicy.resolve(true, false, true, "2"))
    }

    @Test fun disablingOrFilteringMarqueeClearsScrollingAndAutoHide() {
        for (ongoing in listOf(true, false, null)) {
            assertEquals(IslandMarqueePolicy(false, 0, false),
                IslandMarqueePolicy.resolve(false, true, ongoing, "2_override"))
        }
    }

    @Test fun ordinaryNotificationsStillFollowTheirAutoHideChoice() {
        assertEquals(IslandMarqueePolicy(true, 2, false),
            IslandMarqueePolicy.resolve(true, false, false, "2"))
        assertEquals(IslandMarqueePolicy(true, 1, true),
            IslandMarqueePolicy.resolve(true, true, false, "1_override"))
    }

    @Test fun offAndUnrecognizedAutoHideNeverCreateAnOverrideSession() {
        for (setting in listOf("off", "default", "invalid_override")) {
            assertEquals(IslandMarqueePolicy(true, 0, false),
                IslandMarqueePolicy.resolve(true, false, false, setting))
        }
    }

    @Test fun missingNotificationMetadataNeverAllowsAutoHide() {
        assertEquals(IslandMarqueePolicy(true, 0, false),
            IslandMarqueePolicy.resolve(true, true, null, "2_override"))
        assertEquals(IslandMarqueePolicy(false, 0, false),
            IslandMarqueePolicy.resolve(true, false, null, "2_override"))
    }

    @Test fun ongoingAndOrdinaryUpdatesDoNotSharePreviousPolicy() {
        val ongoing = IslandMarqueePolicy.resolve(true, true, true, "2_override")
        val ordinary = IslandMarqueePolicy.resolve(true, true, false, "2_override")
        assertEquals(IslandMarqueePolicy(true, 0, false), ongoing)
        assertEquals(IslandMarqueePolicy(true, 2, true), ordinary)
        assertEquals(ongoing, IslandMarqueePolicy.resolve(true, true, true, "2_override"))
    }
}
