package io.github.hyperisland.xposed.hook

import io.github.hyperisland.xposed.hook.FocusTemplateLifetime.Presence
import io.github.hyperisland.xposed.hook.FocusTemplateLifetime.Ticket

/** Main-thread only. Retains tickets (no Views), including explicitly exhausted failures. */
internal class FocusTemplateCleanupQueue(private val capacity: Int = 1024) {
    enum class Outcome { COMPLETE, BUSY, RETRY }

    private class Entry(val ticket: Ticket) {
        val retry = FocusTemplateCleanupRetry()
    }

    private val entries = LinkedHashMap<String, Entry>()
    @Volatile var hasEntries = false
        private set
    private var sweep: Iterator<Pair<String, Entry>>? = null
    private var rescan = false
    val size get() = entries.size
    val exhaustedCount get() = entries.values.count { it.retry.exhausted }
    val nextRetryAt: Long?
        get() = entries.values.mapNotNull { it.retry.nextAt }.minOrNull()

    init { require(capacity > 0) }

    fun enqueue(ticket: Ticket): Boolean {
        val key = ticket.stamp.key
        // A duplicate callback must not restart the retry budget or replace pending ownership.
        if (key in entries) return true
        if (entries.size >= capacity) return false
        entries[key] = Entry(ticket)
        hasEntries = true
        return true
    }

    fun clear() {
        entries.clear()
        hasEntries = false
        sweep = null
        rescan = false
    }

    fun drain(
        now: Long,
        isValid: (Ticket) -> Boolean,
        attempt: (Ticket) -> Outcome,
        onFailure: (Throwable) -> Unit,
        onExhausted: () -> Unit,
    ) {
        // Compatibility for synchronous callers/tests; production uses bounded drainSlice.
        drainSlice(now, true, Int.MAX_VALUE, { false }, isValid, attempt, onFailure, onExhausted)
    }

    /** A sweep visits each captured entry once. BUSY work never keeps the continuation alive. */
    fun drainSlice(
        now: Long,
        requested: Boolean,
        maxEntries: Int,
        shouldYield: () -> Boolean,
        isValid: (Ticket) -> Boolean,
        attempt: (Ticket) -> Outcome,
        onFailure: (Throwable) -> Unit,
        onExhausted: () -> Unit,
    ): Boolean {
        require(maxEntries > 0)
        if (requested) rescan = true
        if (sweep == null && rescan) {
            sweep = entries.toList().iterator()
            rescan = false
        }
        val cursor = sweep ?: return false
        var visited = 0
        while (visited < maxEntries && cursor.hasNext()) {
            if (visited > 0 && shouldYield()) break
            val (key, entry) = cursor.next()
            visited++
            if (entries[key] !== entry) continue
            // Invalidate even exhausted/backoff entries when the same key is posted again.
            if (!isValid(entry.ticket)) {
                entries.remove(key)
                continue
            }
            if (!entry.retry.takeAttempt(now)) continue
            val outcome = runCatching { attempt(entry.ticket) }.getOrElse {
                onFailure(it)
                Outcome.RETRY
            }
            if (entries[key] !== entry) continue
            when (outcome) {
                Outcome.COMPLETE -> entries.remove(key)
                Outcome.BUSY -> Unit // Writer exit will notify; no polling and no retry cost.
                Outcome.RETRY -> if (!entry.retry.failed(now)) onExhausted()
            }
        }
        if (!cursor.hasNext()) sweep = null
        hasEntries = entries.isNotEmpty()
        if (!hasEntries) { sweep = null; rescan = false }
        return hasEntries && (sweep != null || rescan)
    }

    companion object {
        /** Empty/unknown owners and unknown collection state are not successful cleanup. */
        fun <T> attemptOwners(
            presence: Presence,
            owners: Collection<T>,
            commit: (T) -> Boolean,
            onFailure: (Throwable) -> Unit,
        ): Outcome {
            if (presence != Presence.ABSENT || owners.isEmpty()) return Outcome.RETRY
            var busy = false
            var failed = false
            owners.forEach { owner ->
                runCatching { commit(owner) }
                    .onSuccess { if (!it) busy = true }
                    .onFailure { failed = true; onFailure(it) }
            }
            return when {
                failed -> Outcome.RETRY
                busy -> Outcome.BUSY
                else -> Outcome.COMPLETE
            }
        }
    }
}

/** Three delayed retries. Exhaustion remains visible and cannot be reset by frequent events. */
internal class FocusTemplateCleanupRetry {
    private var failures = 0
    var nextAt: Long? = null
        private set
    var exhausted = false
        private set

    fun takeAttempt(now: Long): Boolean {
        if (exhausted || nextAt?.let { now < it } == true) return false
        nextAt = null
        return true
    }

    fun failed(now: Long): Boolean {
        val delay = when (failures++) { 0 -> 250L; 1 -> 1000L; 2 -> 4000L; else -> null }
        nextAt = delay?.let { now + it }
        exhausted = delay == null
        return !exhausted
    }
}
