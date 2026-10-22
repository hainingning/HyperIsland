package io.github.hyperisland.xposed.hook

import java.util.concurrent.atomic.AtomicBoolean

/** Coalesces cross-thread events. Continuations are finite slices, never retries of BUSY work. */
internal class FocusTemplateCleanupDispatch(
    private val hasWork: () -> Boolean,
    private val post: (Runnable, Boolean) -> Boolean,
    private val slice: (Boolean) -> Boolean,
    private val failed: () -> Unit,
) {
    private val requested = AtomicBoolean()
    private val scheduled = AtomicBoolean()
    private val action = Runnable {
        var more = false
        try {
            more = slice(requested.getAndSet(false))
        } catch (_: Throwable) {
            failed()
        } finally {
            scheduled.set(false)
            if (hasWork() && (more || requested.get())) schedule(true)
        }
    }

    fun request() {
        // This is a volatile summary, not a cross-thread read of the main-thread queue.
        if (!hasWork()) return
        requested.set(true)
        schedule(false)
    }

    private fun schedule(continuation: Boolean) {
        if (!scheduled.compareAndSet(false, true)) return
        if (!runCatching { post(action, continuation) }.getOrDefault(false)) {
            scheduled.set(false)
            failed() // Retain work; only a later real event may retry a rejected dispatch.
        }
    }
}

/** Bounded main-thread batches; callbacks themselves stay outside the queue lock. */
internal class FocusTemplateCleanupBatch(
    private val post: (Runnable, Boolean) -> Boolean,
    private val now: () -> Long,
    private val failed: () -> Unit,
    private val maxItems: Int = 8,
    private val budgetMillis: Long = 2,
    private val capacity: Int = 20480,
) {
    private val lock = Any()
    private val tasks = ArrayDeque<Runnable>()
    private var scheduled = false
    private val action = Runnable { runSlice() }

    init { require(maxItems > 0 && budgetMillis > 0 && capacity > 0) }

    fun submit(task: Runnable) = submitAll(listOf(task))

    fun submitAll(additions: List<Runnable>): Boolean = synchronized(lock) {
        if (additions.isEmpty()) return true
        if (tasks.size + additions.size > capacity) return false
        additions.forEach(tasks::addLast)
        if (scheduled) return true
        scheduled = true
        if (runCatching { post(action, false) }.getOrDefault(false)) return true
        scheduled = false
        // Nothing in this call ran; callers may roll back their metadata transaction.
        repeat(additions.size) { tasks.removeLast() }
        false
    }

    fun remove(task: Runnable) = synchronized(lock) { tasks.remove(task) }

    private fun runSlice() {
        val start = now()
        var count = 0
        try {
            while (count < maxItems) {
                val task = synchronized(lock) { tasks.removeFirstOrNull() } ?: break
                runCatching { task.run() }.onFailure { failed() }
                count++
                if (now() - start >= budgetMillis) break
            }
        } finally {
            val rejected = synchronized(lock) {
                scheduled = false
                if (tasks.isEmpty()) false else {
                    scheduled = true
                    if (runCatching { post(action, true) }.getOrDefault(false)) false
                    else { scheduled = false; true }
                }
            }
            if (rejected) failed()
        }
    }
}
