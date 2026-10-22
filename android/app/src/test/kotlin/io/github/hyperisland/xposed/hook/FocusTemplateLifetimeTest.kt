package io.github.hyperisland.xposed.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FocusTemplateLifetimeTest {
    private fun stamp(key: String = "notification", time: Long = 1L) =
        FocusTemplateLifetime.Stamp(key, time, uid = 10001, id = 7, tag = null)

    @Test
    fun pluginBuildBeforeCollectionKeepsTheSameGeneration() {
        val lifetime = FocusTemplateLifetime()
        val source = Any()
        val ticket = lifetime.begin(source, stamp())!!

        assertFalse(lifetime.canClean(ticket.stamp.key))
        assertSame(ticket, lifetime.observeCollection(source, stamp()))
        assertFalse(lifetime.isStale(ticket))
    }

    @Test
    fun collectionOnlyPostCanStartAGeneration() {
        val lifetime = FocusTemplateLifetime()
        val source = Any()
        val ticket = lifetime.observeCollection(source, stamp())!!

        assertSame(ticket, lifetime.lookup(source, stamp()))
        assertFalse(lifetime.canClean(ticket.stamp.key))
    }

    @Test
    fun repeatedPluginPostForTheSameSourceDoesNotReplaceIt() {
        val lifetime = FocusTemplateLifetime()
        val source = Any()
        val ticket = lifetime.begin(source, stamp())!!

        assertSame(ticket, lifetime.begin(source, stamp()))
        assertFalse(lifetime.isStale(ticket))
    }

    @Test
    fun distinctPostsWithEqualTimestampsStillReplaceTheGeneration() {
        val lifetime = FocusTemplateLifetime()
        val oldSource = Any()
        val old = lifetime.begin(oldSource, stamp())!!
        val replacementSource = Any()
        val replacement = lifetime.begin(replacementSource, stamp())!!

        assertNotSame(old, replacement)
        assertTrue(old.replaced)
        assertFalse(old.closed)
        assertTrue(lifetime.isStale(old))
        assertSame(old, lifetime.lookup(oldSource, stamp()))
        assertSame(replacement, lifetime.lookup(replacementSource, stamp()))
    }

    @Test
    fun delayedKnownSourceCannotReviveItsReplacedGeneration() {
        val lifetime = FocusTemplateLifetime()
        val oldSource = Any()
        val old = lifetime.begin(oldSource, stamp())!!
        val replacementSource = Any()
        val replacement = lifetime.begin(replacementSource, stamp(time = 2L))!!

        assertSame(old, lifetime.begin(oldSource, stamp()))
        assertSame(old, lifetime.observeCollection(oldSource, stamp()))
        assertSame(replacement, lifetime.lookup(replacementSource, stamp(time = 2L)))
        assertTrue(lifetime.isStale(old))
        assertFalse(lifetime.canClean(old.stamp.key))
    }

    @Test
    fun unknownOldCollectionDeliveryCannotReplaceANewerPluginPost() {
        val lifetime = FocusTemplateLifetime()
        val replacementSource = Any()
        val replacement = lifetime.begin(replacementSource, stamp(time = 2L))!!

        assertNull(lifetime.observeCollection(Any(), stamp(time = 1L)))
        assertSame(replacement, lifetime.lookup(replacementSource, stamp(time = 2L)))
        assertFalse(lifetime.isStale(replacement))
        assertFalse(lifetime.canClean(replacement.stamp.key))
    }

    @Test
    fun retiringOldCollectionEntryKeepsNewPluginBuildAlive() {
        val lifetime = FocusTemplateLifetime()
        val old = lifetime.begin(Any(), stamp())!!
        val oldEntrySbn = Any()
        lifetime.associate(oldEntrySbn, old)
        val replacementSource = Any()
        val replacement = lifetime.begin(replacementSource, stamp(time = 2L))!!

        lifetime.retire(lifetime.lookup(oldEntrySbn, stamp())!!)

        assertTrue(old.closed)
        assertFalse(replacement.closed)
        assertFalse(lifetime.canClean(old.stamp.key))
        assertSame(replacement, lifetime.observeCollection(replacementSource, stamp(time = 2L)))
    }

    @Test
    fun finalRemovalClosesTheGenerationWithoutRevivingOldAliases() {
        val lifetime = FocusTemplateLifetime()
        val source = Any()
        val ticket = lifetime.begin(source, stamp())!!
        lifetime.retire(ticket)

        assertTrue(lifetime.canClean(ticket.stamp.key))
        assertTrue(lifetime.isStale(ticket))
        assertSame(ticket, lifetime.begin(source, stamp()))
        assertSame(ticket, lifetime.observeCollection(source, stamp()))
        assertSame(ticket, lifetime.lookup(source, stamp()))
        assertTrue(lifetime.canClean(ticket.stamp.key))
    }

    @Test
    fun unknownLookupDoesNotInventAStaleOrClosedGeneration() {
        val lifetime = FocusTemplateLifetime()
        val source = Any()

        assertNull(lifetime.lookup(source, stamp()))
        // canClean is only a veto check; there is no closed ticket authorizing cleanup.
        assertTrue(lifetime.canClean(stamp().key))

        val ticket = lifetime.begin(Any(), stamp())!!
        assertNull(lifetime.lookup(source, stamp(time = 2L)))
        assertFalse(lifetime.isStale(ticket))
    }

    @Test
    fun lookupBindsAnAliasBeforeAnEqualTimestampReplacement() {
        val lifetime = FocusTemplateLifetime()
        val old = lifetime.begin(Any(), stamp())!!
        val wrapper = Any()
        assertSame(old, lifetime.lookup(wrapper, stamp()))
        val replacement = lifetime.begin(Any(), stamp())!!

        assertSame(old, lifetime.lookup(wrapper, stamp()))
        assertTrue(lifetime.isStale(old))
        assertFalse(lifetime.isStale(replacement))
    }

    @Test
    fun associatingAWrapperCannotRebindAnOldAliasToANewTicket() {
        val lifetime = FocusTemplateLifetime()
        val wrapper = Any()
        val old = lifetime.begin(Any(), stamp())!!
        lifetime.associate(wrapper, old)
        val replacement = lifetime.begin(Any(), stamp())!!
        lifetime.associate(wrapper, replacement)

        assertSame(old, lifetime.lookup(wrapper, stamp()))
        assertTrue(lifetime.isStale(old))
        assertFalse(lifetime.isStale(replacement))
    }

    @Test
    fun capacityLimitPreservesLiveEntriesAndFinalRemovalFreesCapacity() {
        val lifetime = FocusTemplateLifetime(maxEntries = 2)
        val first = lifetime.begin(Any(), stamp("first"))!!
        val second = lifetime.begin(Any(), stamp("second"))!!
        val thirdSource = Any()

        assertNull(lifetime.begin(thirdSource, stamp("third")))
        assertNull(lifetime.observeCollection(thirdSource, stamp("third")))
        assertFalse(lifetime.isStale(first))
        assertFalse(lifetime.isStale(second))
        assertFalse(lifetime.canClean("first"))

        val replacement = lifetime.begin(Any(), stamp("first", 2L))!!
        assertTrue(lifetime.isStale(first))
        lifetime.retire(first)
        assertNull(lifetime.begin(thirdSource, stamp("third")))

        lifetime.retire(replacement)
        val third = lifetime.begin(thirdSource, stamp("third"))!!
        assertFalse(lifetime.isStale(third))
        assertFalse(lifetime.canClean("third"))
    }

    @Test
    fun sourceAliasesUseObjectIdentityRatherThanEquals() {
        class EqualSource {
            override fun equals(other: Any?) = other is EqualSource
            override fun hashCode() = 1
        }
        val lifetime = FocusTemplateLifetime()
        val oldSource = EqualSource()
        val replacementSource = EqualSource()
        val old = lifetime.begin(oldSource, stamp())!!
        val replacement = lifetime.begin(replacementSource, stamp())!!

        assertNotSame(old, replacement)
        assertSame(old, lifetime.lookup(oldSource, stamp()))
        assertSame(replacement, lifetime.lookup(replacementSource, stamp()))
        assertTrue(lifetime.isStale(old))
    }

    @Test
    fun equalTemplateObjectsKeepDistinctTicketsInWeakIdentityMap() {
        data class Template(val content: Int)
        val tickets = WeakIdentityMap<Any>()
        val firstTemplate = Template(7)
        val secondTemplate = Template(7)
        val firstTicket = Any()
        val secondTicket = Any()
        tickets[firstTemplate] = firstTicket
        tickets[secondTemplate] = secondTicket

        assertSame(firstTicket, tickets[firstTemplate])
        assertSame(secondTicket, tickets[secondTemplate])
        assertNull(tickets[Template(7)])
        assertNull(tickets[null])
    }

    @Test
    fun changingTemplateHashCodeDoesNotLoseItsWeakIdentityMapping() {
        data class MutableTemplate(var content: Int)
        val tickets = WeakIdentityMap<Any>()
        val template = MutableTemplate(7)
        val ticket = Any()
        tickets[template] = ticket
        template.content = 42

        assertSame(ticket, tickets[template])

        val updatedTicket = Any()
        tickets[template] = updatedTicket
        assertSame(updatedTicket, tickets[template])
        assertNull(tickets[MutableTemplate(42)])
    }

    @Test
    fun syntheticOrUnknownRemovalReasonsCannotAuthorizeRetirement() {
        val lifetime = FocusTemplateLifetime()
        val ticket = lifetime.begin(Any(), stamp())!!
        val absent = FocusTemplateLifetime.Presence.ABSENT

        for (reason in listOf(-1, 0)) {
            assertFalse(lifetime.retireAfterConfirmedRemoval(ticket, reason, absent, absent))
        }
        assertFalse(lifetime.isStale(ticket))
        assertFalse(lifetime.canClean(ticket.stamp.key))
    }

    @Test
    fun listenerRemovalRequiresBothSuccessfulAbsenceChecks() {
        val absent = FocusTemplateLifetime.Presence.ABSENT
        for (collection in FocusTemplateLifetime.Presence.entries) {
            for (service in FocusTemplateLifetime.Presence.entries) {
                if (collection == absent && service == absent) continue
                val lifetime = FocusTemplateLifetime()
                val ticket = lifetime.begin(Any(), stamp())!!

                assertFalse(
                    "$collection / $service must preserve the notification",
                    lifetime.retireAfterConfirmedRemoval(ticket, 2, collection, service),
                )
                assertFalse(lifetime.isStale(ticket))
                assertFalse(lifetime.canClean(ticket.stamp.key))
            }
        }
    }

    @Test
    fun confirmedServiceRemovalCanRetireAPluginOnlyNotification() {
        val lifetime = FocusTemplateLifetime()
        val ticket = lifetime.begin(Any(), stamp())!!
        val absent = FocusTemplateLifetime.Presence.ABSENT

        assertTrue(lifetime.retireAfterConfirmedRemoval(ticket, 2, absent, absent))
        assertTrue(ticket.closed)
        assertTrue(lifetime.canClean(ticket.stamp.key))
    }

    @Test
    fun newPluginGenerationVetoesOldConfirmedListenerRemoval() {
        val lifetime = FocusTemplateLifetime()
        val old = lifetime.begin(Any(), stamp())!!
        val replacementSource = Any()
        val replacement = lifetime.begin(replacementSource, stamp(time = 2L))!!
        val absent = FocusTemplateLifetime.Presence.ABSENT

        assertFalse(lifetime.retireAfterConfirmedRemoval(old, 2, absent, absent))
        assertFalse(replacement.closed)
        assertFalse(lifetime.isStale(replacement))
        assertFalse(lifetime.canClean(replacement.stamp.key))
        assertSame(replacement, lifetime.observeCollection(replacementSource, stamp(time = 2L)))
    }

    @Test
    fun missingCurrentStateDoesNotAuthorizeClosingAnUnretiredOldTicket() {
        val lifetime = FocusTemplateLifetime()
        val old = lifetime.begin(Any(), stamp())!!
        val replacement = lifetime.begin(Any(), stamp(time = 2L))!!
        lifetime.retire(replacement)
        val absent = FocusTemplateLifetime.Presence.ABSENT

        assertTrue(lifetime.canClean(old.stamp.key))
        assertFalse(lifetime.retireAfterConfirmedRemoval(old, 2, absent, absent))
        assertFalse(old.closed)
        assertTrue(lifetime.retireAfterConfirmedRemoval(replacement, 2, absent, absent))
    }

    @Test
    fun collectionRemovalCannotCloseOrBlockAServiceOwnedNotification() {
        val absent = FocusTemplateLifetime.Presence.ABSENT
        for (service in listOf(
            FocusTemplateLifetime.Presence.PRESENT,
            FocusTemplateLifetime.Presence.UNKNOWN,
        )) {
            val lifetime = FocusTemplateLifetime()
            val source = Any()
            val ticket = lifetime.begin(source, stamp())!!

            assertFalse(lifetime.retireAfterCollectionRemoval(ticket, true, absent, service))
            assertFalse(ticket.closed)
            assertFalse(lifetime.isStale(ticket))
            assertSame(ticket, lifetime.lookup(source, stamp()))
            assertFalse(lifetime.canClean(ticket.stamp.key))
        }
    }

    @Test
    fun unsuccessfulCollectionRemovalCannotAuthorizeRetirement() {
        val lifetime = FocusTemplateLifetime()
        val ticket = lifetime.begin(Any(), stamp())!!
        val absent = FocusTemplateLifetime.Presence.ABSENT

        assertFalse(lifetime.retireAfterCollectionRemoval(ticket, false, absent, absent))
        assertFalse(lifetime.isStale(ticket))
        assertFalse(lifetime.canClean(ticket.stamp.key))
    }

    @Test
    fun successfulCollectionRemovalWithBothAbsencesClosesTheTicket() {
        val lifetime = FocusTemplateLifetime()
        val ticket = lifetime.begin(Any(), stamp())!!
        val absent = FocusTemplateLifetime.Presence.ABSENT

        assertTrue(lifetime.retireAfterCollectionRemoval(ticket, true, absent, absent))
        assertTrue(ticket.closed)
        assertTrue(lifetime.isStale(ticket))
        assertTrue(lifetime.canClean(ticket.stamp.key))
    }

    @Test
    fun newPluginGenerationVetoesAnOlderCollectionRemoval() {
        val lifetime = FocusTemplateLifetime()
        val old = lifetime.begin(Any(), stamp())!!
        val replacement = lifetime.begin(Any(), stamp(time = 2L))!!
        val absent = FocusTemplateLifetime.Presence.ABSENT

        assertFalse(lifetime.retireAfterCollectionRemoval(old, true, absent, absent))
        assertFalse(replacement.closed)
        assertFalse(lifetime.isStale(replacement))
        assertFalse(lifetime.canClean(replacement.stamp.key))
    }

    @Test
    fun currentGenerationMayQueueRemovalVerification() {
        val lifetime = FocusTemplateLifetime()
        val ticket = lifetime.begin(Any(), stamp())!!

        assertTrue(lifetime.canVerifyRemoval(ticket))
        assertFalse(ticket.closed)
        assertFalse(lifetime.canClean(ticket.stamp.key))
    }

    @Test
    fun replacedGenerationCannotQueueRemovalVerification() {
        val lifetime = FocusTemplateLifetime()
        val old = lifetime.begin(Any(), stamp())!!
        val replacement = lifetime.begin(Any(), stamp(time = 2L))!!

        assertTrue(old.replaced)
        assertFalse(lifetime.canVerifyRemoval(old))
        assertTrue(lifetime.canVerifyRemoval(replacement))

        lifetime.retire(old)
        lifetime.retire(replacement)
        assertFalse(lifetime.canVerifyRemoval(old))
    }

    @Test
    fun closedGenerationCannotDisplaceLaterActiveGenerationVerification() {
        val lifetime = FocusTemplateLifetime()
        val old = lifetime.begin(Any(), stamp())!!
        lifetime.retire(old)
        val replacement = lifetime.begin(Any(), stamp(time = 2L))!!

        // Retirement removed old from current before replacement began, so the
        // replaced flag alone cannot detect this obsolete removal task.
        assertFalse(old.replaced)
        assertFalse(lifetime.canVerifyRemoval(old))
        assertTrue(lifetime.canVerifyRemoval(replacement))
    }

    @Test
    fun closedUnreplacedGenerationMayBeVerifiedAgainWhenNoCurrentExists() {
        val lifetime = FocusTemplateLifetime()
        val ticket = lifetime.begin(Any(), stamp())!!
        lifetime.retire(ticket)

        assertTrue(ticket.closed)
        assertFalse(ticket.replaced)
        assertTrue(lifetime.canVerifyRemoval(ticket))
    }

    @Test
    fun onlyCurrentTrackedKeysParticipateInRuntimePostTracking() {
        val lifetime = FocusTemplateLifetime()
        assertFalse(lifetime.hasCurrent("notification"))
        val ticket = lifetime.begin(Any(), stamp())!!
        assertTrue(lifetime.hasCurrent("notification"))
        lifetime.retire(ticket)
        assertFalse(lifetime.hasCurrent("notification"))
    }

    @Test
    fun cleanupWaitsForAnAlreadyRunningWriterAfterItsTicketCloses() {
        val lifetime = FocusTemplateLifetime()
        val work = FocusTemplateWorkCoordinator(lifetime)
        val factory = Any()
        val ticket = lifetime.begin(Any(), stamp())!!
        val writer = work.enterWriter(factory, ticket)!!
        lifetime.retire(ticket)
        var commits = 0

        assertFalse(work.tryCommitCleanup(factory, ticket) { commits++ })
        assertTrue(commits == 0)
        assertNull(work.enterWriter(factory, ticket))
        assertTrue(work.exit(writer))
        assertTrue(work.tryCommitCleanup(factory, ticket) { commits++ })
        assertTrue(commits == 1)
    }

    @Test
    fun anotherKeyInTheSameFactoryAlsoVetoesStructuralMapCleanup() {
        val lifetime = FocusTemplateLifetime()
        val work = FocusTemplateWorkCoordinator(lifetime)
        val factory = Any()
        val first = lifetime.begin(Any(), stamp(key = "first"))!!
        val second = lifetime.begin(Any(), stamp(key = "second"))!!
        val firstWriter = work.enterWriter(factory, first)!!
        val secondWriter = work.enterWriter(factory, second)!!
        lifetime.retire(first)

        assertFalse(work.exit(firstWriter))
        assertFalse(work.tryCommitCleanup(factory, first) { error("Factory is still writing") })
        assertTrue(work.exit(secondWriter))
        assertTrue(work.tryCommitCleanup(factory, first) {})
    }

    @Test
    fun independentFactoriesDoNotBlockEachOthersCleanup() {
        val lifetime = FocusTemplateLifetime()
        val work = FocusTemplateWorkCoordinator(lifetime)
        val firstFactory = Any()
        val secondFactory = Any()
        val ticket = lifetime.begin(Any(), stamp())!!
        val firstWriter = work.enterWriter(firstFactory, ticket)!!
        val secondWriter = work.enterWriter(secondFactory, ticket)!!
        lifetime.retire(ticket)

        assertTrue(work.exit(firstWriter))
        assertTrue(work.tryCommitCleanup(firstFactory, ticket) {})
        assertFalse(work.tryCommitCleanup(secondFactory, ticket) { error("Second factory is busy") })
        assertTrue(work.exit(secondWriter))
    }

    @Test
    fun unknownWriterKeepsNativeBehaviorAndStillPreventsConcurrentCleanup() {
        val lifetime = FocusTemplateLifetime()
        val work = FocusTemplateWorkCoordinator(lifetime)
        val factory = Any()
        val ticket = lifetime.begin(Any(), stamp())!!
        lifetime.retire(ticket)
        val unknownWriter = work.enterWriter(factory, null)!!

        assertFalse(work.tryCommitCleanup(factory, ticket) { error("Unknown writer is active") })
        assertTrue(work.exit(unknownWriter))
        assertTrue(work.tryCommitCleanup(factory, ticket) {})
    }

    @Test
    fun partiallyInstalledHooksMayNotRejectEvenAKnownStaleWriter() {
        val lifetime = FocusTemplateLifetime()
        val work = FocusTemplateWorkCoordinator(lifetime)
        val factory = Any()
        val ticket = lifetime.begin(Any(), stamp())!!
        lifetime.retire(ticket)
        val writer = work.enterWriter(factory, ticket, rejectStale = false)!!

        assertFalse(work.tryCommitCleanup(factory, ticket) { error("Native work must finish") })
        assertTrue(work.exit(writer))
    }

    @Test
    fun replacingAGenerationDoesNotForgetItsAlreadyRunningWriter() {
        val lifetime = FocusTemplateLifetime()
        val work = FocusTemplateWorkCoordinator(lifetime)
        val factory = Any()
        val old = lifetime.begin(Any(), stamp())!!
        val oldWriter = work.enterWriter(factory, old)!!
        val replacement = lifetime.begin(Any(), stamp(time = 2L))!!
        val newWriter = work.enterWriter(factory, replacement)!!
        lifetime.retire(replacement)

        assertNull(work.enterWriter(factory, old))
        assertFalse(work.exit(newWriter))
        assertFalse(work.tryCommitCleanup(factory, replacement) { error("Old writer still runs") })
        assertTrue(work.exit(oldWriter))
        assertTrue(work.tryCommitCleanup(factory, replacement) {})
    }

    @Test
    fun newGenerationVetoesCleanupEvenAfterTheFactoryBecomesIdle() {
        val lifetime = FocusTemplateLifetime()
        val work = FocusTemplateWorkCoordinator(lifetime)
        val factory = Any()
        val old = lifetime.begin(Any(), stamp())!!
        val writer = work.enterWriter(factory, old)!!
        lifetime.retire(old)
        val replacement = lifetime.begin(Any(), stamp(time = 2L))!!
        assertTrue(work.exit(writer))

        assertFalse(work.tryCommitCleanup(factory, old) { error("New generation owns the key") })
        assertFalse(replacement.closed)
    }

    @Test
    fun duplicateReleaseDoesNotConsumeAnotherWritersPermit() {
        val lifetime = FocusTemplateLifetime()
        val work = FocusTemplateWorkCoordinator(lifetime)
        val factory = Any()
        val ticket = lifetime.begin(Any(), stamp())!!
        val first = work.enterWriter(factory, ticket)!!
        val second = work.enterWriter(factory, ticket)!!
        lifetime.retire(ticket)

        assertFalse(work.exit(first))
        assertFalse(work.exit(first))
        assertFalse(work.tryCommitCleanup(factory, ticket) { error("Second writer still runs") })
        assertTrue(work.exit(second))
        assertFalse(work.exit(second))
        assertTrue(work.tryCommitCleanup(factory, ticket) {})
    }

    @Test
    fun nativeRemovalAlwaysRunsButCountsAsAnActiveFactoryMutation() {
        val lifetime = FocusTemplateLifetime()
        val work = FocusTemplateWorkCoordinator(lifetime)
        val factory = Any()
        val ticket = lifetime.begin(Any(), stamp())!!
        lifetime.retire(ticket)
        val nativeRemoval = work.enterMutation(factory)

        assertFalse(work.tryCommitCleanup(factory, ticket) { error("Native removal is active") })
        assertTrue(work.exit(nativeRemoval))
        assertTrue(work.tryCommitCleanup(factory, ticket) {})
    }

    @Test
    fun equalFactoriesStillHaveIndependentWriterScopes() {
        data class Factory(val value: Int)
        val lifetime = FocusTemplateLifetime()
        val work = FocusTemplateWorkCoordinator(lifetime)
        val firstFactory = Factory(1)
        val secondFactory = Factory(1)
        val ticket = lifetime.begin(Any(), stamp())!!
        val first = work.enterMutation(firstFactory)
        val second = work.enterMutation(secondFactory)
        lifetime.retire(ticket)

        assertTrue(work.exit(first))
        assertTrue(work.tryCommitCleanup(firstFactory, ticket) {})
        assertFalse(work.tryCommitCleanup(secondFactory, ticket) { error("Different identity is busy") })
        assertTrue(work.exit(second))
    }

    @Test(timeout = 5000L)
    fun aRunningWriterDoesNotHoldTheMetadataLockOrBlockAnotherKey() {
        val lifetime = FocusTemplateLifetime()
        val work = FocusTemplateWorkCoordinator(lifetime)
        val factory = Any()
        val first = lifetime.begin(Any(), stamp(key = "first"))!!
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val task = executor.submit {
            val writer = work.enterWriter(factory, first)!!
            try {
                entered.countDown()
                check(release.await(4L, TimeUnit.SECONDS))
            } finally {
                work.exit(writer)
            }
        }
        try {
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            // The worker is deliberately still inside its original-call body here.
            val second = lifetime.begin(Any(), stamp(key = "second"))!!
            val otherWriter = work.enterWriter(factory, second)!!
            assertFalse(work.exit(otherWriter))
            lifetime.retire(first)
            assertFalse(work.tryCommitCleanup(factory, first) { error("First worker still runs") })
            release.countDown()
            task.get(2L, TimeUnit.SECONDS)
            assertTrue(work.tryCommitCleanup(factory, first) {})
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun cleanupCommitRequiresAKnownFactoryAndRunsInsideTheMetadataTransaction() {
        val lifetime = FocusTemplateLifetime()
        val work = FocusTemplateWorkCoordinator(lifetime)
        val factory = Any()
        val ticket = lifetime.begin(Any(), stamp())!!
        lifetime.retire(ticket)

        assertFalse(work.tryCommitCleanup(factory, ticket) { error("Unknown factory") })
        assertTrue(work.exit(work.enterMutation(factory)))
        assertTrue(work.tryCommitCleanup(factory, ticket) {
            assertTrue(Thread.holdsLock(lifetime.lock))
        })
        assertFalse(Thread.holdsLock(lifetime.lock))
    }

    @Test
    fun duplicateRemovalEventsShareOneIdentityRequest() {
        val lifetime = FocusTemplateLifetime()
        val gate = FocusTemplateVerificationGate<Any>(lifetime)
        val ticket = lifetime.begin(Any(), stamp())!!
        val first = gate.begin(ticket, listenerReason = null)
        val repeated = gate.begin(ticket, listenerReason = 2)

        assertTrue(first.newRequest)
        assertSame(ticket, first.token!!.ticket)
        assertNull(first.token.listenerReason)
        assertSame(first.token, repeated.token)
        assertFalse(repeated.newRequest)
        assertTrue(gate.isCurrent(first.token))
    }

    @Test
    fun syntheticRemovalCannotStartACompletionGate() {
        val lifetime = FocusTemplateLifetime()
        val gate = FocusTemplateVerificationGate<Any>(lifetime)
        val ticket = lifetime.begin(Any(), stamp())!!

        assertNull(gate.begin(ticket, listenerReason = 0).token)
        assertFalse(gate.park(ticket, Any()).parked)
        assertFalse(ticket.closed)
    }

    @Test
    fun servicePresenceOrFailureReleasesWaitersWithoutClosingTheirGeneration() {
        for (service in listOf(
            FocusTemplateLifetime.Presence.PRESENT,
            FocusTemplateLifetime.Presence.UNKNOWN,
        )) {
            val lifetime = FocusTemplateLifetime()
            val gate = FocusTemplateVerificationGate<Any>(lifetime)
            val ticket = lifetime.begin(Any(), stamp())!!
            val token = gate.begin(ticket, listenerReason = 1).token!!
            val waiter = Any()
            assertTrue(gate.park(ticket, waiter).parked)

            val result = gate.resolve(token, FocusTemplateLifetime.Presence.ABSENT, service)
            assertTrue(result.accepted)
            assertFalse(result.retired)
            assertSame(waiter, result.released.single())
            assertFalse(ticket.closed)
            assertFalse(gate.isCurrent(token))
            assertFalse(gate.park(ticket, waiter).parked)
        }
    }

    @Test
    fun confirmedAbsenceClosesTheGenerationBeforeReturningWaitersToResume() {
        val lifetime = FocusTemplateLifetime()
        val gate = FocusTemplateVerificationGate<Any>(lifetime)
        val ticket = lifetime.begin(Any(), stamp())!!
        val token = gate.begin(ticket, listenerReason = null).token!!
        val waiter = Any()
        assertTrue(gate.park(ticket, waiter).parked)

        val result = gate.resolve(
            token, FocusTemplateLifetime.Presence.ABSENT, FocusTemplateLifetime.Presence.ABSENT,
        )
        assertTrue(result.accepted)
        assertTrue(result.retired)
        assertTrue(ticket.closed)
        assertSame(waiter, result.released.single())
        assertFalse(gate.isCurrent(token))
    }

    @Test
    fun collectionReappearanceOrLookupFailureVetoesGateRetirement() {
        for (collection in listOf(
            FocusTemplateLifetime.Presence.PRESENT,
            FocusTemplateLifetime.Presence.UNKNOWN,
        )) {
            val lifetime = FocusTemplateLifetime()
            val gate = FocusTemplateVerificationGate<Any>(lifetime)
            val ticket = lifetime.begin(Any(), stamp())!!
            val token = gate.begin(ticket, listenerReason = 1).token!!
            val waiter = Any()
            assertTrue(gate.park(ticket, waiter).parked)

            val result = gate.resolve(token, collection, FocusTemplateLifetime.Presence.ABSENT)
            assertFalse(result.retired)
            assertSame(waiter, result.released.single())
            assertFalse(ticket.closed)
        }
    }

    @Test
    fun replacementPostReleasesOldWaitersAndOldResultCannotCloseTheReplacement() {
        val lifetime = FocusTemplateLifetime()
        val gate = FocusTemplateVerificationGate<Any>(lifetime)
        val old = lifetime.begin(Any(), stamp())!!
        val oldToken = gate.begin(old, listenerReason = null).token!!
        val waiter = Any()
        assertTrue(gate.park(old, waiter).parked)
        val replacement = lifetime.begin(Any(), stamp(time = 2L))!!

        assertSame(waiter, gate.onPost(replacement).single())
        assertFalse(gate.isCurrent(oldToken))
        val result = gate.resolve(
            oldToken, FocusTemplateLifetime.Presence.ABSENT, FocusTemplateLifetime.Presence.ABSENT,
        )
        assertFalse(result.accepted)
        assertFalse(result.retired)
        assertTrue(result.released.isEmpty())
        assertFalse(replacement.closed)
    }

    @Test
    fun lateOldPostOrRemovalCannotDisplaceTheCurrentGenerationsGate() {
        val lifetime = FocusTemplateLifetime()
        val gate = FocusTemplateVerificationGate<Any>(lifetime)
        val old = lifetime.begin(Any(), stamp())!!
        val replacement = lifetime.begin(Any(), stamp(time = 2L))!!
        val token = gate.begin(replacement, listenerReason = 1).token!!
        val waiter = Any()
        assertTrue(gate.park(replacement, waiter).parked)

        assertNull(gate.begin(old, listenerReason = 1).token)
        assertTrue(gate.onPost(old).isEmpty())
        assertFalse(gate.park(old, Any()).parked)
        assertTrue(gate.isCurrent(token))
        assertSame(waiter, gate.cancel(token).single())
    }

    @Test
    fun gateResolutionRechecksTheGenerationEvenIfPostInvalidationWasMissed() {
        val lifetime = FocusTemplateLifetime()
        val gate = FocusTemplateVerificationGate<Any>(lifetime)
        val old = lifetime.begin(Any(), stamp())!!
        val token = gate.begin(old, listenerReason = null).token!!
        val waiter = Any()
        assertTrue(gate.park(old, waiter).parked)
        val replacement = lifetime.begin(Any(), stamp(time = 2L))!!

        val result = gate.resolve(
            token, FocusTemplateLifetime.Presence.ABSENT, FocusTemplateLifetime.Presence.ABSENT,
        )
        assertTrue(result.accepted)
        assertFalse(result.retired)
        assertSame(waiter, result.released.single())
        assertFalse(replacement.closed)
    }

    @Test
    fun timeoutReleasesWaitersAndOldResultCannotCloseANewRequestForTheSameTicket() {
        val lifetime = FocusTemplateLifetime()
        val gate = FocusTemplateVerificationGate<Any>(lifetime)
        val ticket = lifetime.begin(Any(), stamp())!!
        val oldToken = gate.begin(ticket, listenerReason = 1).token!!
        val oldWaiter = Any()
        assertTrue(gate.park(ticket, oldWaiter).parked)
        assertSame(oldWaiter, gate.cancel(oldToken).single())
        val newToken = gate.begin(ticket, listenerReason = 1).token!!
        val newWaiter = Any()
        assertTrue(gate.park(ticket, newWaiter).parked)

        assertNotSame(oldToken, newToken)
        assertTrue(gate.cancel(oldToken).isEmpty())
        val oldResult = gate.resolve(
            oldToken, FocusTemplateLifetime.Presence.ABSENT, FocusTemplateLifetime.Presence.ABSENT,
        )
        assertFalse(oldResult.accepted)
        assertFalse(ticket.closed)
        assertTrue(gate.isCurrent(newToken))
        assertSame(newWaiter, gate.cancel(newToken).single())
    }

    @Test
    fun pendingRequestCapacityDoesNotEvictAnotherKeysSuspendedWaiters() {
        val lifetime = FocusTemplateLifetime()
        val gate = FocusTemplateVerificationGate<Any>(lifetime, maxPending = 1)
        val first = lifetime.begin(Any(), stamp(key = "first"))!!
        val second = lifetime.begin(Any(), stamp(key = "second"))!!
        val firstToken = gate.begin(first, listenerReason = 1).token!!
        val waiter = Any()
        assertTrue(gate.park(first, waiter).parked)

        assertNull(gate.begin(second, listenerReason = 1).token)
        assertFalse(gate.park(second, Any()).parked)
        assertTrue(gate.isCurrent(firstToken))
        assertSame(waiter, gate.cancel(firstToken).single())
        assertTrue(gate.begin(second, listenerReason = 1).newRequest)
    }

    @Test
    fun waiterOverflowReleasesExistingWaitersButDoesNotResumeTheUnsuspendedCaller() {
        val lifetime = FocusTemplateLifetime()
        val gate = FocusTemplateVerificationGate<Any>(lifetime, maxWaitersPerToken = 1)
        val ticket = lifetime.begin(Any(), stamp())!!
        val token = gate.begin(ticket, listenerReason = 1).token!!
        val parked = Any()
        val caller = Any()
        assertTrue(gate.park(ticket, parked).parked)

        val overflow = gate.park(ticket, caller)
        assertFalse(overflow.parked)
        assertSame(parked, overflow.released.single())
        assertFalse(gate.isCurrent(token))
        val result = gate.resolve(
            token, FocusTemplateLifetime.Presence.ABSENT, FocusTemplateLifetime.Presence.ABSENT,
        )
        assertFalse(result.accepted)
        assertFalse(ticket.closed)
    }

    @Test
    fun waitersUseIdentityAndTheSameCoroutineIsReleasedOnlyOnce() {
        data class Waiter(val value: Int)
        val lifetime = FocusTemplateLifetime()
        val gate = FocusTemplateVerificationGate<Waiter>(lifetime)
        val ticket = lifetime.begin(Any(), stamp())!!
        val token = gate.begin(ticket, listenerReason = 1).token!!
        val first = Waiter(1)
        val equalButDistinct = Waiter(1)
        assertTrue(gate.park(ticket, first).parked)
        assertTrue(gate.park(ticket, first).parked)
        assertTrue(gate.park(ticket, equalButDistinct).parked)

        val released = gate.cancel(token)
        assertTrue(released.size == 2)
        assertSame(first, released[0])
        assertSame(equalButDistinct, released[1])
        assertTrue(gate.cancel(token).isEmpty())
    }

    @Test
    fun disablingVerificationReleasesEveryWaiterWithoutClosingAnyTicket() {
        val lifetime = FocusTemplateLifetime()
        val gate = FocusTemplateVerificationGate<Any>(lifetime)
        val first = lifetime.begin(Any(), stamp(key = "first"))!!
        val second = lifetime.begin(Any(), stamp(key = "second"))!!
        val firstToken = gate.begin(first, listenerReason = 1).token!!
        val secondToken = gate.begin(second, listenerReason = 1).token!!
        val firstWaiter = Any()
        val secondWaiter = Any()
        assertTrue(gate.park(first, firstWaiter).parked)
        assertTrue(gate.park(second, secondWaiter).parked)

        val released = gate.cancelAll()
        assertTrue(released.size == 2)
        assertTrue(released.any { it === firstWaiter })
        assertTrue(released.any { it === secondWaiter })
        assertFalse(first.closed)
        assertFalse(second.closed)
        assertFalse(gate.isCurrent(firstToken))
        assertFalse(gate.isCurrent(secondToken))
        assertTrue(gate.cancelAll().isEmpty())
    }

    @Test
    fun beginningANewerRequestReleasesOldWaitersEvenWithoutAnOnPostCallback() {
        val lifetime = FocusTemplateLifetime()
        val gate = FocusTemplateVerificationGate<Any>(lifetime)
        val old = lifetime.begin(Any(), stamp())!!
        val oldToken = gate.begin(old, listenerReason = null).token!!
        val waiter = Any()
        assertTrue(gate.park(old, waiter).parked)
        val replacement = lifetime.begin(Any(), stamp(time = 2L))!!

        val next = gate.begin(replacement, listenerReason = 1)
        assertTrue(next.newRequest)
        assertSame(waiter, next.released.single())
        assertFalse(gate.isCurrent(oldToken))
        assertTrue(gate.isCurrent(next.token!!))
    }

    @Test
    fun pendingGateRequiresTheMatchingStillCurrentGeneration() {
        val lifetime = FocusTemplateLifetime()
        val gate = FocusTemplateVerificationGate<Any>(lifetime)
        val old = lifetime.begin(Any(), stamp())!!
        assertFalse(gate.hasPending(old))
        val token = gate.begin(old, listenerReason = 1).token!!
        assertTrue(gate.hasPending(old))
        val replacement = lifetime.begin(Any(), stamp(time = 2L))!!

        assertFalse(gate.hasPending(old))
        assertFalse(gate.hasPending(replacement))
        assertTrue(gate.cancel(token).isEmpty())
    }

    @Test
    fun canceledWaiterIsForgottenByIdentityWithoutCancelingVerificationOrResumingItAgain() {
        data class Waiter(val value: Int)
        val lifetime = FocusTemplateLifetime()
        val gate = FocusTemplateVerificationGate<Waiter>(lifetime)
        val ticket = lifetime.begin(Any(), stamp())!!
        val token = gate.begin(ticket, listenerReason = 1).token!!
        val canceled = Waiter(1)
        val other = Waiter(1)
        assertTrue(gate.park(ticket, canceled).parked)
        assertTrue(gate.park(ticket, other).parked)

        assertTrue(gate.forgetWaiter(ticket, canceled))
        assertFalse(gate.forgetWaiter(ticket, canceled))
        assertTrue(gate.hasPending(ticket))
        assertTrue(gate.isCurrent(token))
        val result = gate.resolve(
            token, FocusTemplateLifetime.Presence.ABSENT, FocusTemplateLifetime.Presence.ABSENT,
        )
        assertTrue(result.retired)
        assertSame(other, result.released.single())
    }

    @Test
    fun weakIdentityRemovalRemovesOnlyTheExactKeyAndUpdatesSize() {
        data class Key(var value: Int)
        val map = WeakIdentityMap<Any>()
        val first = Key(1)
        val equalButDistinct = Key(1)
        val firstValue = Any()
        val secondValue = Any()
        map[first] = firstValue
        map[equalButDistinct] = secondValue
        assertTrue(map.size == 2)

        first.value = 2
        assertSame(firstValue, map.remove(first))
        assertTrue(map.size == 1)
        assertSame(secondValue, map[equalButDistinct])
        assertNull(map.remove(first))
        assertNull(map.remove(Key(1)))
        assertNull(map.remove(null))
        assertTrue(map.size == 1)
        assertSame(secondValue, map.remove(equalButDistinct))
        assertTrue(map.size == 0)
    }

    @Test
    fun sourceCapacityFailureDoesNotReplaceTheCurrentGenerationOrEvictOldAliases() {
        val lifetime = FocusTemplateLifetime(maxSources = 2)
        val originalSource = Any()
        val alias = Any()
        val ticket = lifetime.begin(originalSource, stamp())!!
        assertSame(ticket, lifetime.lookup(alias, stamp()))

        assertNull(lifetime.begin(Any(), stamp(time = 2L)))
        assertTrue(lifetime.overflowed)
        assertFalse(ticket.replaced)
        assertFalse(ticket.closed)
        assertTrue(lifetime.hasCurrent(ticket.stamp.key))
        assertSame(ticket, lifetime.lookup(originalSource, stamp()))
        assertSame(ticket, lifetime.lookup(alias, stamp()))
        assertNull(lifetime.lookup(Any(), stamp()))
    }

    @Test
    fun overflowingWrapperAssociationNeverRebindsKnownIdentityAliases() {
        val lifetime = FocusTemplateLifetime(maxSources = 2)
        val firstSource = Any()
        val secondSource = Any()
        val first = lifetime.begin(firstSource, stamp(key = "first"))!!
        val second = lifetime.begin(secondSource, stamp(key = "second"))!!

        assertFalse(lifetime.associate(firstSource, second))
        assertFalse(lifetime.overflowed)
        assertFalse(lifetime.associate(Any(), second))
        assertTrue(lifetime.overflowed)
        assertSame(first, lifetime.lookup(firstSource, stamp(key = "first")))
        assertSame(second, lifetime.lookup(secondSource, stamp(key = "second")))
    }

    @Test
    fun sourceOverflowVetoesRetirementAndCleanupUntilRuntimeDisablesProtection() {
        val lifetime = FocusTemplateLifetime(maxSources = 1)
        val source = Any()
        val ticket = lifetime.begin(source, stamp())!!
        assertNull(lifetime.lookup(Any(), stamp()))
        assertTrue(lifetime.overflowed)
        assertFalse(lifetime.canVerifyRemoval(ticket))
        assertFalse(lifetime.retireAfterCollectionRemoval(
            ticket, true, FocusTemplateLifetime.Presence.ABSENT,
            FocusTemplateLifetime.Presence.ABSENT,
        ))
        assertFalse(ticket.closed)
        lifetime.retire(ticket)
        assertFalse(lifetime.canClean(ticket.stamp.key))
        assertSame(ticket, lifetime.lookup(source, stamp()))
        assertNull(lifetime.begin(Any(), stamp(key = "other")))
    }

    @Test
    fun knownOnlyLookupPreservesExactStaleTicketsWithoutConsumingSourceCapacity() {
        val lifetime = FocusTemplateLifetime(maxSources = 1)
        val source = Any()
        val ticket = lifetime.begin(source, stamp())!!
        lifetime.retire(ticket)

        assertNull(lifetime.lookupKnown(Any(), stamp()))
        assertFalse(lifetime.overflowed)
        assertSame(ticket, lifetime.lookupKnown(source, stamp()))
        assertTrue(lifetime.isStale(ticket))
        assertNull(lifetime.begin(Any(), stamp(key = "other")))
        assertTrue(lifetime.overflowed)
        assertSame(ticket, lifetime.lookupKnown(source, stamp()))
        assertTrue(lifetime.isStale(ticket))
    }
}
