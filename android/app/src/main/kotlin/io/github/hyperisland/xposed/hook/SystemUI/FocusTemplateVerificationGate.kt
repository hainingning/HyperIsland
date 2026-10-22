package io.github.hyperisland.xposed.hook

/**
 * Holds completion continuations while a background notification-service query is pending.
 *
 * This class only returns waiters to its caller; it never invokes or resumes them. The
 * runtime must park and resume on the same main queue, and always enqueue a resume rather
 * than resume inline, so a waiter has returned COROUTINE_SUSPENDED before it can be resumed.
 * Every request also needs an external timeout that calls [cancel].
 *
 * Both requests and retained waiters are bounded. Any failed/unknown verification,
 * capacity limit, timeout, or cancellation releases waiters without closing their ticket.
 */
internal class FocusTemplateVerificationGate<W : Any>(
    private val lifetime: FocusTemplateLifetime,
    private val maxPending: Int = 128,
    private val maxWaitersPerToken: Int = 8,
) {
    class Token internal constructor(
        val ticket: FocusTemplateLifetime.Ticket,
        val listenerReason: Int?,
    )

    data class Start<W>(
        val token: Token?,
        val newRequest: Boolean,
        val released: List<W>,
    )

    data class Parking<W>(
        val parked: Boolean,
        val released: List<W>,
    )

    data class Resolution<W>(
        val accepted: Boolean,
        val retired: Boolean,
        val released: List<W>,
    )

    private class Pending<W>(val token: Token) {
        val waiters = ArrayList<W>()
    }

    private val pending = HashMap<String, Pending<W>>()

    init {
        require(maxPending > 0)
        require(maxWaitersPerToken > 0)
    }

    /**
     * A null reason is reserved for successful final collection removal. Positive
     * reasons denote listener removal; synthetic reason 0 is never an authorization.
     * This only records a valid removal candidate; a listener candidate can precede
     * collection removal. Both absence checks are still mandatory in [resolve].
     */
    fun begin(
        ticket: FocusTemplateLifetime.Ticket,
        listenerReason: Int?,
    ): Start<W> = synchronized(lifetime.lock) {
        if ((listenerReason != null && listenerReason <= 0) ||
            ticket.closed || !lifetime.canVerifyRemoval(ticket)
        ) {
            return@synchronized Start(null, false, emptyList())
        }
        val key = ticket.stamp.key
        val previous = pending[key]
        if (previous?.token?.ticket === ticket) {
            return@synchronized Start(previous.token, false, emptyList())
        }
        val released = if (previous != null) take(previous) else emptyList()
        if (pending.size >= maxPending) {
            return@synchronized Start(null, false, released)
        }
        val token = Token(ticket, listenerReason)
        pending[key] = Pending(token)
        Start(token, true, released)
    }

    /**
     * A false result means the current completion must retain its original behavior.
     * On overflow the current waiter is NOT in released: it has not been suspended.
     * Already-registered waiters are compared by identity to avoid double resume.
     */
    fun park(ticket: FocusTemplateLifetime.Ticket, waiter: W): Parking<W> =
        synchronized(lifetime.lock) {
            val request = pending[ticket.stamp.key]
            if (request == null || request.token.ticket !== ticket) {
                return@synchronized Parking(false, emptyList())
            }
            if (ticket.closed || !lifetime.canVerifyRemoval(ticket)) {
                return@synchronized Parking(false, take(request))
            }
            if (request.waiters.any { it === waiter }) {
                return@synchronized Parking(true, emptyList())
            }
            if (request.waiters.size >= maxWaitersPerToken) {
                return@synchronized Parking(false, take(request))
            }
            request.waiters.add(waiter)
            Parking(true, emptyList())
        }

    /** Avoids constructing a suspension object when there is no matching live request. */
    fun hasPending(ticket: FocusTemplateLifetime.Ticket): Boolean = synchronized(lifetime.lock) {
        pending[ticket.stamp.key]?.token?.ticket === ticket &&
            !ticket.closed && lifetime.canVerifyRemoval(ticket)
    }

    /**
     * A natively canceled continuation already resumes through its cancellation path.
     * Remove only that waiter by identity; keep the request and never resume it again.
     */
    fun forgetWaiter(ticket: FocusTemplateLifetime.Ticket, waiter: W): Boolean =
        synchronized(lifetime.lock) {
            val request = pending[ticket.stamp.key]
            if (request?.token?.ticket !== ticket) return@synchronized false
            val index = request.waiters.indexOfFirst { it === waiter }
            if (index < 0) false else {
                request.waiters.removeAt(index)
                true
            }
        }

    /**
     * Result acceptance, retirement, and releasing waiters are one metadata transaction.
     * A stale result cannot close a replacement, or even a newer request for the same
     * ticket after a previous request was canceled. The runtime resumes [Resolution.released]
     * after this returns, so confirmed-absent completions see an already-closed ticket.
     */
    fun resolve(
        token: Token,
        collectionPresence: FocusTemplateLifetime.Presence,
        servicePresence: FocusTemplateLifetime.Presence,
    ): Resolution<W> = synchronized(lifetime.lock) {
        val request = pending[token.ticket.stamp.key]
        if (request?.token !== token) {
            return@synchronized Resolution(false, false, emptyList())
        }
        val retired = if (token.listenerReason == null) {
            lifetime.retireAfterCollectionRemoval(
                token.ticket, true, collectionPresence, servicePresence,
            )
        } else {
            lifetime.retireAfterConfirmedRemoval(
                token.ticket, token.listenerReason, collectionPresence, servicePresence,
            )
        }
        Resolution(true, retired, take(request))
    }

    /** Call after a post has successfully established its current ticket. */
    fun onPost(ticket: FocusTemplateLifetime.Ticket): List<W> = synchronized(lifetime.lock) {
        if (lifetime.isStale(ticket)) return@synchronized emptyList()
        val request = pending[ticket.stamp.key] ?: return@synchronized emptyList()
        if (request.token.ticket === ticket) emptyList() else take(request)
    }

    /** Timeout/failure cancellation is identity-scoped and never closes a ticket. */
    fun cancel(token: Token): List<W> = synchronized(lifetime.lock) {
        val request = pending[token.ticket.stamp.key]
        if (request?.token !== token) emptyList() else take(request)
    }

    /** Queue/deadline bookkeeping only; this does not authorize retiring a ticket. */
    fun isCurrent(token: Token): Boolean = synchronized(lifetime.lock) {
        pending[token.ticket.stamp.key]?.token === token
    }

    /** Used when runtime suspension/query compatibility fails and protection is disabled. */
    fun cancelAll(): List<W> = synchronized(lifetime.lock) {
        val released = pending.values.flatMap { it.waiters }
        pending.clear()
        released
    }

    /** Caller holds lifetime.lock and request is the identity-current map entry. */
    private fun take(request: Pending<W>): List<W> {
        pending.remove(request.token.ticket.stamp.key)
        return request.waiters.toList().also { request.waiters.clear() }
    }
}
