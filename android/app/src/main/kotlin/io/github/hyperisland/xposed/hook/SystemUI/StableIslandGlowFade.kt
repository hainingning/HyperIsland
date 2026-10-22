package io.github.hyperisland.xposed.hook

import java.lang.ref.WeakReference

/** A verified return may gently brighten native light; visibility alone never arms it. */
class StableIslandGlowFade(
    private val durationMillis: Long = 900L,
    private val initialFactor: Float = 0.5f,
) {
    init {
        require(durationMillis > 0L) { "durationMillis must be positive" }
        require(initialFactor.isFinite() && initialFactor > 0f && initialFactor <= 1f) {
            "initialFactor must be finite and in (0, 1]"
        }
    }

    private var armed = false
    private var startedAt: Long? = null
    private var lastFactor = 1f
    var started: Boolean = false
        private set
    val pending: Boolean get() = armed && !started
    val complete: Boolean get() = !armed || (started && lastFactor == 1f)

    /** The caller must deduplicate native return events before issuing this permission. */
    fun arm(): Boolean {
        if (!complete) return false
        armed = true
        started = false
        startedAt = null
        lastFactor = initialFactor
        return true
    }

    fun sample(nowMillis: Long): Float {
        if (complete) return 1f
        val start = startedAt ?: run {
            startedAt = nowMillis
            started = true
            return initialFactor
        }
        val elapsed = if (nowMillis <= start) 0L else {
            // Saturate subtraction rather than wrapping at the Long boundary.
            (nowMillis - start).let { if (it < 0L) Long.MAX_VALUE else it }
        }
        val fraction = (elapsed.toDouble() / durationMillis.toDouble()).coerceIn(0.0, 1.0)
        val smooth = (fraction * fraction * (3.0 - 2.0 * fraction)).toFloat()
        lastFactor = maxOf(lastFactor, initialFactor + (1f - initialFactor) * smooth)
        return lastFactor
    }

    /** Hidden/interrupted light is immediately handed back to the native renderer. */
    fun cancel() {
        armed = false
        started = false
        startedAt = null
        lastFactor = 1f
    }
}

/**
 * Owns only two transition-alpha values, never the targets themselves. Accessors
 * must not capture either target. An external alpha write relinquishes ownership.
 */
internal class StableIslandGlowFadeAlpha<T : Any>(
    upper: T,
    lower: T,
    read: (T) -> Float,
    write: (T, Float) -> Unit,
) {
    private val upperRef = WeakReference(upper)
    private val lowerRef = WeakReference(lower)
    private var readValue: ((T) -> Float)? = read
    private var writeValue: ((T, Float) -> Unit)? = write
    private var claimed = false
    private var closed = false
    private var lastUpper = 1f
    private var lastLower = 1f
    // A setter may mutate and then throw. Both its old and attempted values are
    // owned until it returns, so either kind of failure can be restored safely.
    private var pendingUpper: Float? = null
    private var pendingLower: Float? = null

    fun apply(factor: Float): Boolean {
        if (closed) return false
        if (!factor.isFinite() || factor < 0f || factor > 1f) return fail()
        val upper = upperRef.get() ?: return fail()
        val lower = lowerRef.get() ?: return fail()
        val read = readValue ?: return fail()
        val write = writeValue ?: return fail()
        return try {
            val upperAlpha = read(upper)
            val lowerAlpha = read(lower)
            if (closed) return false
            if (!upperAlpha.isFinite() || !lowerAlpha.isFinite() ||
                upperAlpha != lastUpper || lowerAlpha != lastLower
            ) return fail()
            claimed = true
            if (lastUpper != factor) {
                pendingUpper = factor
                write(upper, factor)
                if (closed) return false
                lastUpper = factor
                pendingUpper = null
            }
            if (lastLower != factor) {
                pendingLower = factor
                write(lower, factor)
                if (closed) return false
                lastLower = factor
                pendingLower = null
            }
            true
        } catch (_: Throwable) {
            fail()
        }
    }

    private fun fail(): Boolean {
        close()
        return false
    }

    fun close() {
        if (closed) return
        closed = true
        val read = readValue
        val write = writeValue
        readValue = null
        writeValue = null
        if (claimed && read != null && write != null) {
            restore(upperRef.get(), lastUpper, pendingUpper, read, write)
            restore(lowerRef.get(), lastLower, pendingLower, read, write)
        }
        upperRef.clear()
        lowerRef.clear()
        pendingUpper = null
        pendingLower = null
    }

    private fun restore(
        target: T?, last: Float, pending: Float?, read: (T) -> Float, write: (T, Float) -> Unit,
    ) {
        if (target == null) return
        try {
            val current = read(target)
            if (current != 1f && (current == last || current == pending)) write(target, 1f)
        } catch (_: Throwable) {
            // The other target still receives its own independent restore attempt.
        }
    }
}
