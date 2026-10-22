package io.github.hyperisland.xposed.hook

/** A persistent notification may scroll without handing its lifetime to the scroller. */
internal data class IslandMarqueePolicy(
    val enabled: Boolean,
    val autoHideLoops: Int,
    val overrideTimeout: Boolean,
) {
    companion object {
        fun resolve(
            enabled: Boolean,
            explicitlyEnabled: Boolean,
            ongoing: Boolean?,
            autoHide: String,
        ): IslandMarqueePolicy {
            // Preserve the global default for ongoing notifications; the channel's
            // explicit "on" opts in. Missing notification metadata cannot authorize hiding.
            val persistentOrUnknown = ongoing != false
            if (!enabled || (persistentOrUnknown && !explicitlyEnabled)) {
                return IslandMarqueePolicy(false, 0, false)
            }
            if (persistentOrUnknown) return IslandMarqueePolicy(true, 0, false)
            val loops = autoHide.removeSuffix("_override").toIntOrNull()?.coerceIn(1, 2) ?: 0
            return IslandMarqueePolicy(true, loops, loops > 0 && autoHide.endsWith("_override"))
        }
    }
}
