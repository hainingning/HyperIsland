package io.github.hyperisland.xposed.hook

/** Logical text viewport before the whole island's transition scale/clip is applied. */
internal class MarqueeViewport {
    var left = 0f
        private set
    var right = 0f
        private set
    private var offset = 0f
    private var scale = 1f

    /** Avoid the dense half of the native edge fade without reserving its whole strip. */
    fun insetForEdgeShade(shadeLeft: Float, shadeRight: Float, parentWidth: Int, gap: Float) {
        if (!shadeLeft.isFinite() || !shadeRight.isFinite() || !gap.isFinite() || gap < 0f || scale <= 0f) return
        val shadeWidth = shadeRight - shadeLeft
        if (shadeWidth <= 0f || shadeWidth >= parentWidth / 2f) return
        if (shadeLeft <= 0f && shadeRight > 0f) {
            val clearEdge = (shadeRight + gap - offset) / scale
            left = maxOf(left, (left + clearEdge) / 2f)
        } else if (shadeRight >= parentWidth && shadeLeft < parentWidth) {
            val clearEdge = (shadeLeft - gap - offset) / scale
            right = minOf(right, (right + clearEdge) / 2f)
        }
    }

    fun reset(width: Int, paddingLeft: Int, paddingRight: Int) {
        left = paddingLeft.toFloat()
        right = (width - paddingRight).toFloat()
        offset = 0f
        scale = 1f
    }

    fun parent(childLeft: Int, scrollX: Int, width: Int, paddingLeft: Int, paddingRight: Int,
               clipChildren: Boolean, clipToPadding: Boolean,
               translationX: Float = 0f, scaleX: Float = 1f, pivotX: Float = 0f): Boolean {
        // Map text-local coordinates into this parent before inverting its clip.
        // MIUI right-text holders use both a 0.79 scale and a translation; left
        // bounds alone describe their layout box, not the rendered viewport.
        offset = offset * scaleX + childLeft + translationX + pivotX * (1f - scaleX) - scrollX
        scale *= scaleX
        if (!offset.isFinite() || !scale.isFinite() || scale <= 0f) {
            left = 0f
            right = 0f
            return false
        }
        if (clipChildren) {
            left = maxOf(left, -offset / scale)
            right = minOf(right, (width - offset) / scale)
        }
        if (clipToPadding && (paddingLeft > 0 || paddingRight > 0)) {
            left = maxOf(left, (paddingLeft - offset) / scale)
            right = minOf(right, (width - paddingRight - offset) / scale)
        }
        return right > left
    }
}
