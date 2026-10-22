package io.github.hyperisland.xposed.hook

/** A missing layout/clip during an island transition pauses playback, not its lifetime. */
internal class MarqueeReadiness {
    var active = false
        private set
    var waiting = false
        private set

    fun start() { active = true; waiting = false }
    fun stop() { active = false; waiting = false }

    /** True only when a draw listener must be installed. */
    fun awaitDraw(): Boolean {
        if (!active || waiting) return false
        waiting = true
        return true
    }

    /** A stale draw callback cannot revive a stopped/replaced notification. */
    fun onDraw(attached: Boolean, enabled: Boolean, rangeReady: Boolean): Boolean {
        if (!active || !waiting || !attached || !enabled || !rangeReady) return false
        waiting = false
        return true
    }
}
