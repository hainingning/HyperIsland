package io.github.hyperisland.xposed.hook

/**
 * Tracks notification generations without retaining notifications or their views.
 *
 * The focus plugin can begin building a template before NotifCollection receives
 * the notification. A missing collection entry therefore cannot end a generation.
 * Only a confirmed final removal may [retire] its ticket.
 */
internal class FocusTemplateLifetime(
    private val maxEntries: Int = 1024,
    private val maxSources: Int = 32768,
) {
    val lock = Any()

    /** A source-cap failure is latched: dropping old aliases could revive stale work. */
    @Volatile
    var overflowed: Boolean = false
        private set

    data class Stamp(
        val key: String,
        val postTime: Long,
        val uid: Int,
        val id: Int,
        val tag: String?,
    )

    enum class Presence {
        PRESENT,
        ABSENT,
        UNKNOWN,
    }

    class Ticket internal constructor(val stamp: Stamp) {
        @Volatile
        var closed: Boolean = false
            internal set

        @Volatile
        var replaced: Boolean = false
            internal set
    }

    private val current = HashMap<String, Ticket>()
    private val sources = WeakIdentityMap<Ticket>()

    init {
        require(maxEntries > 0)
        require(maxSources > 0)
    }

    /**
     * Called before the focus controller processes a post. A distinct source is
     * a new generation even when its timestamp is equal to the previous post's.
     * Repeated or delayed calls for a known source keep its original ticket.
     */
    fun begin(source: Any, stamp: Stamp): Ticket? = synchronized(lock) {
        sources.drain()
        known(source)?.let { return@synchronized it.takeIf { it.stamp == stamp } }
        create(source, stamp)
    }

    /**
     * Confirms the collection stage of a post without replacing an earlier
     * plugin-stage ticket. An unknown, differently stamped source may be an old
     * collection delivery behind a newer plugin post: leave it untracked.
     */
    fun observeCollection(source: Any, stamp: Stamp): Ticket? = synchronized(lock) {
        sources.drain()
        known(source)?.let { return@synchronized it.takeIf { it.stamp == stamp } }
        val ticket = current[stamp.key]
        if (ticket != null) {
            if (ticket.stamp != stamp || ticket.closed || ticket.replaced) {
                return@synchronized null
            }
            if (!bind(source, ticket)) return@synchronized null
            return@synchronized ticket
        }
        create(source, stamp)
    }

    /** Associates an ExpandedNotification wrapper without rebinding an old alias. */
    fun associate(source: Any, ticket: Ticket): Boolean = synchronized(lock) {
        sources.drain()
        val existing = known(source)
        if (existing != null) existing === ticket else bind(source, ticket)
    }

    /**
     * Resolves known old sources before consulting the current generation.
     * Unknown sources return null, which must not be interpreted as stale.
     */
    fun lookup(source: Any, stamp: Stamp): Ticket? = synchronized(lock) {
        sources.drain()
        known(source)?.let { return@synchronized it.takeIf { it.stamp == stamp } }
        val ticket = current[stamp.key]?.takeIf {
            it.stamp == stamp && !it.closed && !it.replaced
        } ?: return@synchronized null
        if (!bind(source, ticket)) return@synchronized null
        ticket
    }

    /** Reads an exact existing alias after tracking is disabled, without binding a new source. */
    fun lookupKnown(source: Any, stamp: Stamp): Ticket? = synchronized(lock) {
        known(source)?.takeIf { it.stamp == stamp }
    }

    fun isStale(ticket: Ticket): Boolean = synchronized(lock) {
        ticket.closed || ticket.replaced
    }

    /** Runtime uses this to avoid creating state for notifications that never use V3. */
    fun hasCurrent(key: String): Boolean = synchronized(lock) {
        current.containsKey(key)
    }

    /**
     * Vetoes obsolete removal work before it can replace another generation's
     * queued verification. This is not authorization to close or clean a ticket.
     */
    fun canVerifyRemoval(ticket: Ticket): Boolean = synchronized(lock) {
        val active = current[ticket.stamp.key]
        !overflowed && !ticket.replaced &&
            (active === ticket || (active == null && ticket.closed))
    }

    /** Closing an old ticket must not retire a same-key replacement. */
    fun retire(ticket: Ticket) = synchronized(lock) {
        sources.drain()
        ticket.closed = true
        if (current[ticket.stamp.key] === ticket) current.remove(ticket.stamp.key)
    }

    /**
     * Handles a real listener removal for a post consumed before collection.
     * Plugin-synthesized removals use reason 0 on the supported ROM. Neither an
     * absent collection entry alone nor a failed service query ends a lifetime.
     * A newer plugin generation vetoes an old removal even before collection sees it.
     */
    fun retireAfterConfirmedRemoval(
        ticket: Ticket,
        reason: Int,
        collectionPresence: Presence,
        servicePresence: Presence,
    ): Boolean = synchronized(lock) {
        reason > 0 && retireWhenAbsent(ticket, collectionPresence, servicePresence)
    }

    /**
     * Even a successful collection removal can originate from a plugin's
     * synthetic removeNotification while the service still owns the post.
     * Confirm service absence before closing the ticket or blocking its writes.
     */
    fun retireAfterCollectionRemoval(
        ticket: Ticket,
        removed: Boolean,
        collectionPresence: Presence,
        servicePresence: Presence,
    ): Boolean = synchronized(lock) {
        removed && retireWhenAbsent(ticket, collectionPresence, servicePresence)
    }

    private fun retireWhenAbsent(
        ticket: Ticket,
        collectionPresence: Presence,
        servicePresence: Presence,
    ): Boolean {
        sources.drain()
        if (collectionPresence != Presence.ABSENT ||
            servicePresence != Presence.ABSENT
        ) {
            return false
        }
        if (!canVerifyRemoval(ticket)) return false
        retire(ticket)
        return true
    }

    /**
     * Checks only whether a current plugin/collection generation vetoes cleanup.
     * A true result for an unknown key does NOT authorize cleanup. Callers must
     * also require an explicitly closed ticket and a successfully queried absent
     * NotifCollection entry; a missing/failed entry lookup is not final removal.
     */
    fun canClean(key: String): Boolean = synchronized(lock) {
        sources.drain()
        val ticket = current[key]
        !overflowed && (ticket == null || ticket.closed)
    }

    private fun known(source: Any): Ticket? = sources[source]

    private fun bind(source: Any, ticket: Ticket): Boolean {
        if (overflowed) return false
        if (sources.size >= maxSources) {
            overflowed = true
            return false
        }
        sources[source] = ticket
        return true
    }

    /** Caller holds [lock]. Capacity exhaustion preserves existing live state. */
    private fun create(source: Any, stamp: Stamp): Ticket? {
        val previous = current[stamp.key]
        if (previous == null && current.size >= maxEntries) return null
        val ticket = Ticket(stamp)
        // Reserve the identity alias first; failed tracking must not replace a live ticket.
        if (!bind(source, ticket)) return null
        previous?.replaced = true
        current[stamp.key] = ticket
        return ticket
    }
}
