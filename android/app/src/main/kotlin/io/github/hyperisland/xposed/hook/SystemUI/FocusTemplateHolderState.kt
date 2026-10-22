package io.github.hyperisland.xposed.hook

/** No Android objects: the caller serializes this state, then performs cleanup outside its lock. */
internal class FocusTemplateHolderState {
    private enum class Phase { WAITING, DETACHING, FAILED, FINISHED }

    private var phase = Phase.WAITING
    private var queued = false
    private var nativeDepth = 0
    private var nativeFailed = false

    fun requestCheck(): Boolean {
        if (phase != Phase.WAITING || queued) return false
        queued = true
        return true
    }

    fun takeCheck(): Boolean {
        queued = false
        return phase == Phase.WAITING
    }

    fun beginDetach(viewAttached: Boolean, rootAttached: Boolean): Boolean {
        if (phase != Phase.WAITING || viewAttached || rootAttached) return false
        phase = Phase.DETACHING
        return true
    }

    fun enterNativeDetach() {
        if (nativeDepth == 0) nativeFailed = false
        nativeDepth++
    }

    /**
     * A successful super.onDetach does not complete a subclass's lifecycle. Only the entire
     * outermost call may authorize deleting its adapter slots; failures preserve native retry.
     */
    fun exitNativeDetach(success: Boolean): Boolean {
        check(nativeDepth > 0)
        if (!success) nativeFailed = true
        nativeDepth--
        if (nativeDepth != 0) return false
        if (nativeFailed) {
            failDetach()
            return false
        }
        if (isFinished()) return false
        finish()
        return true
    }

    /** Stop automatic retries after a partial cleanup, but allow a later native call to finish. */
    fun failDetach() {
        if (phase != Phase.FINISHED) phase = Phase.FAILED
        queued = false
    }

    /** Native onDetach and our own successful invocation share the same completion signal. */
    fun finish() {
        phase = Phase.FINISHED
        queued = false
    }

    fun isFinished() = phase == Phase.FINISHED
}
