package io.github.hyperisland.xposed.hook

/**
 * Counts synchronous factory mutations without holding the metadata lock while they run.
 *
 * A factory's five maps are shared by all keys, so cleanup requires the entire factory
 * to be idle. Writers, including writers for different keys, never wait for one another.
 * The factory is only a weak identity key; neither a scope nor a permit retains it.
 */
internal class FocusTemplateWorkCoordinator(private val lifetime: FocusTemplateLifetime) {
    internal class Scope {
        var activeWriters = 0
    }

    class Writer internal constructor(internal val scope: Scope) {
        internal var released = false
    }

    private val factories = WeakIdentityMap<Scope>()

    /**
     * Returns null only for a known obsolete ticket when stale rejection is enabled.
     * Unknown tickets and hooks that are not fully ready must keep native behavior.
     * The returned permit must be released in finally after the original call returns.
     */
    fun enterWriter(
        factory: Any,
        ticket: FocusTemplateLifetime.Ticket?,
        rejectStale: Boolean = true,
    ): Writer? = synchronized(lifetime.lock) {
        if (rejectStale && ticket != null && lifetime.isStale(ticket)) {
            return@synchronized null
        }
        enter(factory)
    }

    /** Native removeTemplate/clean must run, even if the notification ticket is stale. */
    fun enterMutation(factory: Any): Writer = synchronized(lifetime.lock) {
        enter(factory)
    }

    /**
     * Returns true when this release makes its factory idle, so the caller can retry
     * queued cleanup. Duplicate release is harmless and does not consume another permit.
     * Any retry/pump is performed by the caller after this short locked operation returns.
     */
    fun exit(writer: Writer): Boolean = synchronized(lifetime.lock) {
        if (writer.released) return@synchronized false
        writer.released = true
        writer.scope.activeWriters--
        writer.scope.activeWriters == 0
    }

    /**
     * Attempts one short, atomic map commit; never waits for in-flight factory work.
     *
     * [action] may snapshot/remove cache map entries, register a small holder freeze,
     * and enqueue its Handler task. It must not invoke native hooks, build/detach
     * views, perform Binder calls, or otherwise wait. Actual holder cleanup belongs
     * after this method returns. Sharing the lifetime lock makes the final generation
     * check and map commit atomic with new posts and writer admission.
     *
     * A closed ticket is necessary but does not replace the runtime's collection/NMS
     * absence checks. Unknown factories are not cleanup candidates.
     */
    fun tryCommitCleanup(
        factory: Any,
        ticket: FocusTemplateLifetime.Ticket,
        action: () -> Unit,
    ): Boolean = synchronized(lifetime.lock) {
        if (!ticket.closed || !lifetime.canClean(ticket.stamp.key)) {
            return@synchronized false
        }
        val scope = factories[factory] ?: return@synchronized false
        if (scope.activeWriters != 0) return@synchronized false
        action()
        true
    }

    /** Caller holds lifetime.lock. */
    private fun enter(factory: Any): Writer {
        val scope = factories[factory] ?: Scope().also { factories[factory] = it }
        scope.activeWriters++
        return Writer(scope)
    }
}
