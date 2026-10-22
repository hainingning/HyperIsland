package io.github.hyperisland.xposed.hook

import io.github.hyperisland.xposed.hook.FocusTemplateCleanupQueue.Outcome
import io.github.hyperisland.xposed.hook.FocusTemplateLifetime.Presence
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Field

/**
 * Uses the production reflection, cache transaction, queue, and writer coordinator together.
 * Fixtures model the observed six adapters with nullable module slots, not Android View behavior.
 */
class FocusTemplateCleanupRegressionTest {
    open class Holder {
        var detachCalls = 0
        open fun onDetach() { detachCalls++ }
    }
    class UnknownHolder : Holder() {
        override fun onDetach() = super.onDetach()
    }
    class Adapter {
        @JvmField var holders: MutableMap<Any?, Any?> = linkedMapOf()
        @JvmField var tinyHolders: MutableMap<Any?, Any?> = linkedMapOf()
        @JvmField var decoHolders: MutableMap<Any?, Any?> = linkedMapOf()
        @JvmField var decoLandHolders: MutableMap<Any?, Any?> = linkedMapOf()
        @JvmField var dataMap: MutableMap<Any?, Any?> = linkedMapOf()
    }
    private class Builder(val adapters: List<Adapter>)
    private data class Slot(val adapter: Adapter, val field: Field, val key: String, val holder: Holder)
    private val holderFields = listOf("holders", "tinyHolders", "decoHolders", "decoLandHolders")
        .map { Adapter::class.java.getField(it) }
    private val dataField = Adapter::class.java.getField("dataMap")
    private val detachMethods = setOf(Holder::class.java.getMethod("onDetach"))

    private fun capture(builders: List<Any>): List<Slot> = buildList {
        builders.forEach { builder ->
            (builder as Builder).adapters.forEach { adapter ->
                FocusTemplateHolderMaps.visit(
                    adapter, holderFields, Holder::class.java, detachMethods,
                ) { field, key, holder -> add(Slot(adapter, field, key, holder as Holder)) }
            }
        }
    }

    private fun removeSlot(slot: Slot) = FocusTemplateHolderMaps.removeCompletedSlot(
        slot.adapter, holderFields, dataField, slot.field, slot.key, slot.holder,
    )

    private fun closed(lifetime: FocusTemplateLifetime, key: String = "notification") =
        lifetime.begin(Any(), FocusTemplateLifetime.Stamp(key, 1, 10001, 7, null))!!
            .also { lifetime.retire(it) }

    private inner class Harness {
        val lifetime = FocusTemplateLifetime()
        val coordinator = FocusTemplateWorkCoordinator(lifetime)
        val owner = Any()
        val ticket = closed(lifetime)
        val queue = FocusTemplateCleanupQueue()
        val adapters = List(6) {
            Adapter().apply {
                holders["placeholder"] = null
                holders["text"] = Holder()
                dataMap["text"] = Any()
            }
        }
        val builder = Builder(adapters)
        val maps: List<MutableMap<Any?, Any?>> = List(5) { linkedMapOf() }
        val plans = mutableListOf<List<Slot>>()
        var failCapture = false
        var rejectDispatch = false
        var presence = Presence.ABSENT
        var errors = 0
        var exhausted = 0
        init {
            maps[0][ticket.stamp.key] = builder
            maps[1][ticket.stamp.key] = null // Explicit null differs from an absent map key.
            maps[4][ticket.stamp.key] = Any()
            coordinator.exit(coordinator.enterMutation(owner))
            queue.enqueue(ticket)
        }
        fun drain(now: Long) = queue.drain(
            now, { it.closed && lifetime.canClean(it.stamp.key) },
            attempt = { lease ->
                FocusTemplateCleanupQueue.attemptOwners(presence, listOf(owner), { factory ->
                    coordinator.tryCommitCleanup(factory, lease) {
                        FocusTemplateCacheTransaction.unlink(
                            maps, lease.stamp.key,
                            capture = { if (failCapture) null else capture(it) },
                            retire = { if (rejectDispatch) false else { plans += it; true } },
                            abandon = {},
                        )
                    }
                }, { errors++ })
            },
            onFailure = { errors++ }, onExhausted = { exhausted++ },
        )
    }

    @Test fun sixAdaptersWithNullSlotsCompleteTheRealMapAndQueuePath() {
        val h = Harness()
        val holders = h.adapters.map { it.holders["text"] as Holder }
        h.drain(0)
        assertEquals(0, h.errors)
        assertEquals(0, h.queue.size)
        assertTrue(h.maps.all { !it.containsKey(h.ticket.stamp.key) })
        assertEquals(6, h.plans.single().size)
        h.plans.single().forEach { it.holder.onDetach(); removeSlot(it) }
        assertTrue(holders.all { it.detachCalls == 1 })
        assertTrue(h.adapters.all { !FocusTemplateHolderMaps.hasLiveHolders(it, holderFields) })
        assertTrue(h.adapters.all { it.dataMap.isEmpty() })
    }

    @Test fun snapshotFailureKeepsAllMapsAndRetriesWithoutAnotherNotificationEvent() {
        val h = Harness()
        val saved = h.maps.map { it.toMap() }
        h.failCapture = true
        h.drain(0)
        assertEquals(saved, h.maps)
        assertEquals(1, h.queue.size)
        assertEquals(250L, h.queue.nextRetryAt)
        h.failCapture = false
        h.drain(249)
        assertTrue(h.plans.isEmpty())
        h.drain(250)
        assertEquals(0, h.queue.size)
        assertEquals(1, h.plans.size)
    }

    @Test fun rejectedRetirementDispatchRestoresExplicitNullAndAbsentKeysThenRetries() {
        val h = Harness()
        val saved = h.maps.map { it.toMap() }
        h.rejectDispatch = true
        h.drain(0)
        assertEquals(saved, h.maps)
        assertTrue(h.maps[1].containsKey(h.ticket.stamp.key))
        assertFalse(h.maps[2].containsKey(h.ticket.stamp.key))
        assertEquals(1, h.queue.size)
        h.rejectDispatch = false
        h.drain(250)
        assertEquals(0, h.queue.size)
        assertEquals(1, h.plans.size)
    }

    @Test fun corruptNonNullHolderDoesNotPermitPartialCacheRemoval() {
        val h = Harness()
        h.adapters.last().holders["invalid"] = Any()
        val saved = h.maps.map { it.toMap() }
        h.drain(0)
        assertEquals(saved, h.maps)
        assertTrue(h.plans.isEmpty())
        assertEquals(1, h.errors)
        h.adapters.last().holders.remove("invalid")
        h.drain(250)
        assertEquals(0, h.queue.size)
    }

    @Test fun allFourHolderMapsAcceptNullPlaceholders() {
        val adapter = Adapter()
        holderFields.forEach { field ->
            @Suppress("UNCHECKED_CAST")
            val map = field.get(adapter) as MutableMap<Any?, Any?>
            map["empty"] = null
            map["live"] = Holder()
        }
        assertEquals(4, capture(listOf(Builder(listOf(adapter)))).size)
    }

    @Test fun nullOnlyAndEmptyAdaptersHaveNoLiveHolders() {
        val adapter = Adapter()
        assertFalse(FocusTemplateHolderMaps.hasLiveHolders(adapter, holderFields))
        adapter.holders["empty"] = null
        adapter.tinyHolders["empty"] = null
        assertTrue(capture(listOf(Builder(listOf(adapter)))).isEmpty())
        assertFalse(FocusTemplateHolderMaps.hasLiveHolders(adapter, holderFields))
        adapter.decoLandHolders["live"] = Holder()
        assertTrue(FocusTemplateHolderMaps.hasLiveHolders(adapter, holderFields))
    }

    @Test fun invalidKeyIsNotHiddenByANullPlaceholder() {
        val adapter = Adapter().apply { holders[5] = null }
        assertThrows(IllegalStateException::class.java) {
            capture(listOf(Builder(listOf(adapter))))
        }
    }

    @Test fun unknownDetachOverrideIsStillRejected() {
        val adapter = Adapter().apply { holders["live"] = UnknownHolder() }
        assertThrows(IllegalStateException::class.java) {
            capture(listOf(Builder(listOf(adapter))))
        }
    }

    @Test fun completedSlotNeverRemovesItsReplacementOrItsData() {
        val adapter = Adapter()
        val old = Holder()
        val replacement = Holder()
        val data = Any()
        adapter.holders["text"] = replacement
        adapter.dataMap["text"] = data
        removeSlot(Slot(adapter, holderFields[0], "text", old))
        assertSame(replacement, adapter.holders["text"])
        assertSame(data, adapter.dataMap["text"])
    }

    @Test fun anotherVariantKeepsSharedDataUntilItsLastHolderCompletes() {
        val adapter = Adapter()
        val first = Holder()
        val second = Holder()
        adapter.holders["text"] = first
        adapter.tinyHolders["text"] = second
        adapter.decoHolders["text"] = null
        adapter.dataMap["text"] = Any()
        removeSlot(Slot(adapter, holderFields[0], "text", first))
        assertTrue(adapter.dataMap.containsKey("text"))
        removeSlot(Slot(adapter, holderFields[1], "text", second))
        assertFalse(adapter.dataMap.containsKey("text"))
        assertFalse(FocusTemplateHolderMaps.hasLiveHolders(adapter, holderFields))
    }

    @Test fun completedMetadataCanRetryAfterPartialFailureWithoutRepeatingDetach() {
        val adapter = Adapter()
        val holder = Holder()
        var fail = true
        adapter.holders["text"] = holder
        adapter.dataMap = object : LinkedHashMap<Any?, Any?>() {
            override fun remove(key: Any?): Any? {
                if (fail) { fail = false; error("temporary map failure") }
                return super.remove(key)
            }
        }.apply { put("text", Any()) }
        val slot = Slot(adapter, holderFields[0], "text", holder)
        holder.onDetach()
        assertThrows(IllegalStateException::class.java) { removeSlot(slot) }
        assertFalse(adapter.holders.containsKey("text"))
        removeSlot(slot)
        assertTrue(adapter.dataMap.isEmpty())
        assertEquals(1, holder.detachCalls)
    }

    @Test fun missingCacheEntryDoesNotStartAnotherRetirement() {
        val maps: List<MutableMap<Any?, Any?>> = List(5) { linkedMapOf() }
        FocusTemplateCacheTransaction.unlink(
            maps, "gone", capture = { error("must not capture") },
            retire = { error("must not retire") }, abandon = { error("must not abandon") },
        )
    }

    @Test fun removalExceptionRestoresEveryOriginalMap() {
        val maps = MutableList<MutableMap<Any?, Any?>>(5) { linkedMapOf("key" to Any()) }
        var fail = true
        maps[2] = object : LinkedHashMap<Any?, Any?>(maps[2]) {
            override fun remove(key: Any?): Any? {
                if (fail) { fail = false; error("temporary remove failure") }
                return super.remove(key)
            }
        }
        val saved = maps.map { it.toMap() }
        var abandoned = 0
        assertThrows(IllegalStateException::class.java) {
            FocusTemplateCacheTransaction.unlink(
                maps, "key", { Any() }, { true }, { abandoned++ },
            )
        }
        assertEquals(saved, maps)
        assertEquals(1, abandoned)
    }

    @Test fun rollbackFailureOnOneMapDoesNotSkipTheOtherMapsOrAbandon() {
        val maps = MutableList<MutableMap<Any?, Any?>>(5) { linkedMapOf("key" to Any()) }
        var refuseRestore = false
        maps[0] = object : LinkedHashMap<Any?, Any?>(maps[0]) {
            override fun put(key: Any?, value: Any?): Any? {
                if (refuseRestore) error("restore failure")
                return super.put(key, value)
            }
        }
        val saved = maps.map { it.toMap() }
        var abandoned = 0
        val failure = assertThrows(IllegalStateException::class.java) {
            FocusTemplateCacheTransaction.unlink(
                maps, "key", { Any() }, { refuseRestore = true; false }, { abandoned++ },
            )
        }
        assertEquals(saved.drop(1), maps.drop(1))
        assertEquals(1, abandoned)
        assertEquals(1, failure.suppressed.size)
    }

    @Test fun unknownCollectionDoesNotDropPendingCleanup() {
        val h = Harness()
        h.presence = Presence.UNKNOWN
        h.drain(0)
        assertEquals(1, h.queue.size)
        assertTrue(h.plans.isEmpty())
        h.presence = Presence.ABSENT
        h.drain(250)
        assertEquals(0, h.queue.size)
    }

    @Test fun collectionStillPresentDoesNotAuthorizeDeletion() {
        val h = Harness()
        h.presence = Presence.PRESENT
        h.drain(0)
        assertEquals(1, h.queue.size)
        assertTrue(h.maps[0].isNotEmpty())
    }

    @Test fun busyFactoryWaitsForWriterExitWithoutSpendingRetryBudget() {
        val h = Harness()
        val writer = h.coordinator.enterMutation(h.owner)
        repeat(20) { h.drain(it * 1000L) }
        assertEquals(1, h.queue.size)
        assertEquals(0, h.queue.exhaustedCount)
        assertNull(h.queue.nextRetryAt)
        assertTrue(h.coordinator.exit(writer))
        h.drain(21000)
        assertEquals(0, h.queue.size)
    }

    @Test fun noReadyFactoryIsNotSuccessfulCleanup() {
        assertEquals(Outcome.RETRY, FocusTemplateCleanupQueue.attemptOwners(
            Presence.ABSENT, emptyList<Any>(), { error("no owner") }, { throw it },
        ))
    }

    @Test fun failureThenBusyFactoryRecoversOnWriterExitWithoutLosingItsPendingEntry() {
        val h = Harness()
        h.failCapture = true
        h.drain(0)
        h.failCapture = false
        val writer = h.coordinator.enterMutation(h.owner)
        h.drain(250)
        assertEquals(1, h.queue.size)
        assertNull(h.queue.nextRetryAt)
        assertEquals(0, h.queue.exhaustedCount)
        h.coordinator.exit(writer)
        h.drain(300)
        assertEquals(0, h.queue.size)
        assertEquals(1, h.plans.size)
    }

    @Test fun oneFailingFactoryDoesNotHideFailureOrStarveOtherFactories() {
        var secondCommitted = false
        var errors = 0
        val result = FocusTemplateCleanupQueue.attemptOwners(
            Presence.ABSENT, listOf(1, 2),
            { if (it == 1) error("first failed"); secondCommitted = true; true },
            { errors++ },
        )
        assertEquals(Outcome.RETRY, result)
        assertTrue(secondCommitted)
        assertEquals(1, errors)
    }

    @Test fun persistentFailureStopsAfterFourAttemptsButRetainsAnExplicitRecord() {
        val h = Harness()
        h.failCapture = true
        listOf(0L, 250L, 1250L, 5250L).forEach(h::drain)
        assertEquals(4, h.errors)
        assertEquals(1, h.queue.size)
        assertEquals(1, h.queue.exhaustedCount)
        assertEquals(1, h.exhausted)
        assertNull(h.queue.nextRetryAt)
        repeat(20) { h.queue.enqueue(h.ticket); h.drain(100000L + it) }
        assertEquals(4, h.errors)
        assertEquals(1, h.exhausted)
    }

    @Test fun frequentWriterEventsCannotBypassBackoff() {
        val h = Harness()
        h.failCapture = true
        repeat(250) { h.drain(it.toLong()) }
        assertEquals(1, h.errors)
        h.drain(250)
        assertEquals(2, h.errors)
    }

    @Test fun sameKeyNewPostInvalidatesDelayedCleanupBeforeAnyMapMutation() {
        val h = Harness()
        h.failCapture = true
        h.drain(0)
        h.lifetime.begin(Any(), h.ticket.stamp.copy(postTime = 2))
        h.failCapture = false
        h.drain(1)
        assertEquals(0, h.queue.size)
        assertNull(h.queue.nextRetryAt)
        assertTrue(h.maps[0].isNotEmpty())
        assertTrue(h.plans.isEmpty())
    }

    @Test fun sameKeyNewPostAlsoPrunesExhaustedEntries() {
        val h = Harness()
        h.failCapture = true
        listOf(0L, 250L, 1250L, 5250L).forEach(h::drain)
        h.lifetime.begin(Any(), h.ticket.stamp.copy(postTime = 2))
        h.drain(6000)
        assertEquals(0, h.queue.size)
    }

    @Test fun fullQueueDoesNotEvictFailureEvidenceToAcceptNewKeys() {
        val lifetime = FocusTemplateLifetime()
        val queue = FocusTemplateCleanupQueue(1)
        assertTrue(queue.enqueue(closed(lifetime, "first")))
        assertFalse(queue.enqueue(closed(lifetime, "second")))
        assertEquals(1, queue.size)
    }

    @Test fun attemptExceptionIsRetainedEvenOutsideTheFactoryCommit() {
        val lifetime = FocusTemplateLifetime()
        val queue = FocusTemplateCleanupQueue()
        queue.enqueue(closed(lifetime))
        var errors = 0
        queue.drain(0, { true }, { error("lookup failed") }, { errors++ }, {})
        assertEquals(1, errors)
        assertEquals(1, queue.size)
        assertEquals(250L, queue.nextRetryAt)
    }

    @Test fun failedTimerDispatchDoesNotEraseWorkAndLaterEventCanRecover() {
        val h = Harness()
        h.failCapture = true
        h.drain(0)
        // No timer is executed (e.g. Handler rejected it); only a later factory event arrives.
        h.failCapture = false
        h.drain(500)
        assertEquals(0, h.queue.size)
        assertEquals(1, h.plans.size)
    }

    @Test fun queueSelectsTheEarliestRetryAndClearCancelsAllDueTimes() {
        val lifetime = FocusTemplateLifetime()
        val queue = FocusTemplateCleanupQueue()
        queue.enqueue(closed(lifetime, "first"))
        queue.drain(0, { true }, { Outcome.RETRY }, { throw it }, {})
        queue.enqueue(closed(lifetime, "second"))
        queue.drain(100, { true }, { Outcome.RETRY }, { throw it }, {})
        assertEquals(250L, queue.nextRetryAt)
        queue.clear()
        assertEquals(0, queue.size)
        assertNull(queue.nextRetryAt)
    }

    @Test fun drainToleratesReentrantEnqueueWithoutDroppingTheNewWork() {
        val lifetime = FocusTemplateLifetime()
        val queue = FocusTemplateCleanupQueue()
        queue.enqueue(closed(lifetime, "first"))
        queue.drain(0, { true }, {
            queue.enqueue(closed(lifetime, "second")); Outcome.COMPLETE
        }, { throw it }, {})
        assertEquals(1, queue.size)
    }
}
