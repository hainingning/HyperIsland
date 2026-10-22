package io.github.hyperisland.xposed.hook

import kotlin.math.abs

/** Reused per controller: no per-frame objects or references to a View. */
internal class MarqueeScrollRange {
    var start = 0f
        private set
    var end = 0f
        private set
    val distance: Float get() = abs(end - start)

    /** For MIUI's TimerTextEffectView, whose span line bounds omit drawn glyphs. */
    fun updateMeasured(textWidth: Float, layoutWidth: Float, paddingLeft: Float, alignment: Float,
                       visibleLeft: Float, visibleRight: Float, rtl: Boolean): Boolean {
        val alignedLeft = (layoutWidth - textWidth) * alignment
        // A fixed-width TimerTextEffectSpan reports a zero-width placeholder.
        // For LTR RIGHT text its draw() receives x=layoutWidth and clamps
        // x-textWidth to zero. Native rebinding can shrink the view from the
        // full text width to the viewport; a negative assumed glyph origin
        // would then shift the first glyph right and never reveal the tail.
        // Centered text and RTL (x=0) follow different native branches.
        val textLeft = paddingLeft + if (alignment == 1f && !rtl) maxOf(0f, alignedLeft) else alignedLeft
        return update(textLeft, textLeft + textWidth, visibleLeft, visibleRight, rtl)
    }

    fun update(textLeft: Float, textRight: Float, visibleLeft: Float, visibleRight: Float, rtl: Boolean): Boolean {
        start = 0f
        end = 0f
        if (!textLeft.isFinite() || !textRight.isFinite() || !visibleLeft.isFinite() || !visibleRight.isFinite()) return false
        if (visibleRight <= visibleLeft || textRight - textLeft <= visibleRight - visibleLeft) return false
        val leftAligned = textLeft - visibleLeft
        val rightAligned = textRight - visibleRight
        start = if (rtl) rightAligned else leftAligned
        end = if (rtl) leftAligned else rightAligned
        return true
    }

    fun position(distanceScrolled: Float): Float {
        val progress = distanceScrolled.coerceIn(0f, distance)
        return start + if (end >= start) progress else -progress
    }
}
