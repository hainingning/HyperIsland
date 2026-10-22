package io.github.hyperisland.xposed.hook.SystemUI.geometry

import org.junit.Assert.*
import org.junit.Test
import io.github.hyperisland.compose.data.channel.ChannelSettings
import io.github.hyperisland.compose.data.channel.ChannelSettingsPatch
import io.github.hyperisland.compose.data.channel.toFullPatch
import io.github.hyperisland.compose.data.channel.withPatch

class IslandWidthPolicyTest {
    @Test fun sourceMarkerWithoutDuplicateChannelKeepsNativeChannel() {
        val source = IslandWidthPolicy.source("fun.fiona.adbtunnel", "adb_tunnel", "fun.fiona.adbtunnel", null, null)
        assertEquals("fun.fiona.adbtunnel", source.packageName)
        assertEquals("adb_tunnel", source.channelId)
    }
    @Test fun actualTunnelPayloadUsesOriginalChannelMarker() {
        assertEquals("adb_tunnel", IslandWidthPolicy.source(
            "fun.fiona.adbtunnel", "adb_tunnel", "fun.fiona.adbtunnel", null, "adb_tunnel",
        ).channelId)
    }
    @Test fun forwardedNotificationsUseSourceChannelOverProxyChannel() {
        val source = IslandWidthPolicy.source("com.android.systemui", "proxy", "app.source", "channel.source", "legacy")
        assertEquals(IslandWidthPolicy.Source("app.source", "channel.source"), source)
    }
    @Test fun forwardedNotificationsSupportLegacyOriginalChannelMarker() {
        assertEquals(IslandWidthPolicy.Source("app.source", "channel.original"),
            IslandWidthPolicy.source("com.android.systemui", "proxy", "app.source", null, "channel.original"))
    }
    @Test fun missingForwardedChannelCannotApplyUnrelatedProxyChannelRule() {
        assertEquals(IslandWidthPolicy.Source("app.source", ""),
            IslandWidthPolicy.source("com.android.systemui", "proxy", "app.source", null, null))
    }
    @Test fun nativeNotificationsWithoutSourceMarkersKeepTheirIdentity() {
        assertEquals(IslandWidthPolicy.Source("app.native", "channel.native"),
            IslandWidthPolicy.source("app.native", "channel.native", " ", null, " "))
    }
    @Test fun channelWinsOverAppAndGlobal() {
        assertEquals(320, IslandWidthPolicy.resolve("320", "280", 240))
    }
    @Test fun channelCanExplicitlyRestoreSystemDespiteAppOverride() {
        assertEquals(0, IslandWidthPolicy.resolve("0", "320", 280))
    }
    @Test fun missingChannelInheritsAppIncludingSystem() {
        for (channel in listOf(null, "", "default", "garbage", "1001", "-1", "2147483648")) {
            assertEquals(320, IslandWidthPolicy.resolve(channel, "320", 280))
            assertEquals(0, IslandWidthPolicy.resolve(channel, "0", 280))
        }
    }
    @Test fun unconfiguredAppsUseNativeWhenGlobalIsReset() {
        assertEquals(0, IslandWidthPolicy.resolve("default", "default", 0))
        assertEquals(280, IslandWidthPolicy.resolve(null, null, 280))
    }
    @Test fun invalidImportedValuesCannotOverflowWidth() {
        assertEquals(0, IslandWidthPolicy.resolve("999999999999999", "-100", -5))
        assertEquals(1000, IslandWidthPolicy.resolve("default", "default", 1001))
        assertEquals(1, IslandWidthPolicy.resolve("1", "1000", 0))
    }
    @Test fun switchingAppsAndChannelsDoesNotReusePreviousValue() {
        repeat(100) {
            assertEquals(320, IslandWidthPolicy.resolve("320", null, 0))
            assertEquals(0, IslandWidthPolicy.resolve(null, null, 0))
            assertEquals(240, IslandWidthPolicy.resolve(null, "240", 0))
            assertEquals(0, IslandWidthPolicy.resolve("0", "240", 0))
        }
    }
    @Test fun nestedCalculationsRestoreTheirParentAndRemoveThreadValue() {
        val scope = IslandWidthScope()
        scope.withWidth(320) {
            assertEquals(320, scope.current())
            scope.withWidth(0) { assertEquals(0, scope.current()) }
            assertEquals(320, scope.current())
        }
        assertNull(scope.current())
    }
    @Test fun failedCalculationCannotAffectNextApp() {
        val scope = IslandWidthScope()
        runCatching { scope.withWidth(320) { error("native measurement failed") } }
        assertNull(scope.current())
        scope.withWidth(0) { assertEquals(0, scope.current()) }
        assertNull(scope.current())
    }
    @Test fun calculationThreadsAreIsolated() {
        val scope = IslandWidthScope()
        scope.withWidth(320) {
            var other: Int? = -1
            Thread { other = scope.current() }.apply { start(); join() }
            assertNull(other)
            assertEquals(320, scope.current())
        }
    }
    @Test fun batchNoChangeAndExplicitSystemRemainDistinct() {
        val existing = ChannelSettings(maxWidth = "320", marquee = "on", timeout = "999999999")
        assertEquals(existing, existing.withPatch(ChannelSettingsPatch()))
        assertEquals(existing.copy(maxWidth = "0"), existing.withPatch(ChannelSettingsPatch(maxWidth = "0")))
        assertEquals(existing.copy(maxWidth = "default"), existing.withPatch(ChannelSettingsPatch(maxWidth = "default")))
        assertEquals(existing, ChannelSettings().withPatch(existing.toFullPatch()))
    }
}
