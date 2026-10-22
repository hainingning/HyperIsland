package io.github.hyperisland.compose.page.apps.channel

import io.github.hyperisland.R
import org.junit.Assert.assertEquals
import org.junit.Test

class IslandWidthLabelsTest {
    @Test fun appEditorUsesAppTitle() {
        assertEquals(R.string.island_width_app, islandWidthTitleRes(appLevel = true))
    }

    @Test fun channelEditorUsesChannelTitle() {
        assertEquals(R.string.island_width_channel, islandWidthTitleRes(appLevel = false))
    }
}
