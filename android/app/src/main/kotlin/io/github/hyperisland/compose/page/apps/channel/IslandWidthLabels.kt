package io.github.hyperisland.compose.page.apps.channel

import io.github.hyperisland.R

/** Shared by the preference row and its editor, including batch editing. */
internal fun islandWidthTitleRes(appLevel: Boolean): Int =
    if (appLevel) R.string.island_width_app else R.string.island_width_channel
