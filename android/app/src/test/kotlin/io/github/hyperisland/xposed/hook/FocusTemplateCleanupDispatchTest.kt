package io.github.hyperisland.xposed.hook

import io.github.hyperisland.xposed.hook.FocusTemplateCleanupQueue.Outcome
import org.junit.Assert.*
import org.junit.Test

class FocusTemplateCleanupDispatchTest {
    private class MainQueue {
        val tasks = ArrayDeque<Pair<Runnable, Boolean>>()
        var posts = 0
        var reject = false
        var throwPost = false
        fun post(action: Runnable, continuation: Boolean): Boolean {
            posts++
            if (throwPost) error("post threw")
            if (reject) return false
            tasks.addLast(action to continuation)
            return true
        }
        fun step() = tasks.removeFirst().first.run()
        fun finish() { var limit = 2000; while (tasks.isNotEmpty()) { check(limit-- > 0); step() } }
    }
    private class Harness {
        val main = MainQueue()
        val lifetime = FocusTemplateLifetime()
        val queue = FocusTemplateCleanupQueue()
        val attempted = mutableListOf<String>()
        val counts = mutableListOf<Int>()
        var outcome = Outcome.COMPLETE
        var time = 0L
        var cost = 0L
        var errors = 0
        var valid: (FocusTemplateLifetime.Ticket) -> Boolean = { it.closed && lifetime.canClean(it.stamp.key) }
        var during: (() -> Unit)? = null
        val dispatch = FocusTemplateCleanupDispatch(
            { queue.hasEntries }, main::post,
            { requested ->
                val start = time
                val old = attempted.size
                val more = queue.drainSlice(time, requested, 4, { time - start >= 2 }, valid, {
                    attempted += it.stamp.key
                    time += cost
                    during?.also { during = null }?.invoke()
                    outcome
                }, { errors++ }, {})
                counts += attempted.size - old
                more
            }, { errors++ },
        )
        fun add(key: String) {
            val ticket = lifetime.begin(Any(), FocusTemplateLifetime.Stamp(key, 1, 1, 1, null))!!
            lifetime.retire(ticket)
            check(queue.enqueue(ticket))
            dispatch.request()
        }
    }

    @Test fun emptyQueueWriterExitsDoNotPostOrRunASlice() {
        val h = Harness()
        repeat(1000) { h.dispatch.request() }
        assertEquals(0, h.main.posts)
        assertTrue(h.counts.isEmpty())
    }
    @Test fun largeRemovalIsSplitIntoFourKeysAndEventuallyCompletesOnceEach() {
        val h = Harness()
        repeat(101) { h.add("key$it") }
        assertEquals(1, h.main.tasks.size)
        h.main.step()
        assertEquals(4, h.attempted.size)
        assertTrue(h.main.tasks.single().second)
        h.main.finish()
        assertEquals(101, h.attempted.toSet().size)
        assertEquals(101, h.attempted.size)
        assertTrue(h.counts.all { it <= 4 })
        assertEquals(26, h.main.posts)
        assertFalse(h.queue.hasEntries)
    }
    @Test fun softTimeBudgetYieldsAfterOneSlowKey() {
        val h = Harness(); h.cost = 5
        repeat(9) { h.add("key$it") }
        h.main.finish()
        assertEquals(List(9) { 1 }, h.counts)
    }
    @Test fun busySweepStopsAndWaitsForRealWriterExit() {
        val h = Harness(); h.outcome = Outcome.BUSY
        repeat(9) { h.add("key$it") }; h.main.finish()
        assertEquals(9, h.attempted.size)
        assertEquals(3, h.main.posts)
        assertNull(h.queue.nextRetryAt)
        h.outcome = Outcome.COMPLETE; h.dispatch.request(); h.main.finish()
        assertEquals(0, h.queue.size)
        assertEquals(18, h.attempted.size)
    }
    @Test fun newEventsDoNotRestartTheActiveSweepOrStarveItsTail() {
        val h = Harness(); h.outcome = Outcome.BUSY
        repeat(12) { h.add("old$it") }
        h.main.step()
        repeat(10) { h.dispatch.request() }
        h.main.step()
        assertEquals((0..7).map { "old$it" }, h.attempted)
        h.main.finish()
        assertEquals(24, h.attempted.size) // One coalesced extra sweep, then stop.
    }
    @Test fun reentrantNewKeyIsNotLostWhenCurrentSliceFinishes() {
        val h = Harness(); h.add("first")
        h.during = { h.add("second") }
        h.main.finish()
        assertEquals(listOf("first", "second"), h.attempted)
        assertFalse(h.queue.hasEntries)
    }
    @Test fun changedGenerationBetweenSlicesCannotBeCleaned() {
        val h = Harness(); repeat(5) { h.add("key$it") }; h.main.step()
        h.lifetime.begin(Any(), FocusTemplateLifetime.Stamp("key4", 2, 1, 1, null))
        h.main.finish()
        assertFalse("key4" in h.attempted)
        assertEquals(0, h.queue.size)
    }
    @Test fun backoffDoesNotBecomeAContinuationLoopOrSpendExtraRetry() {
        val h = Harness(); h.outcome = Outcome.RETRY
        repeat(9) { h.add("key$it") }; h.main.finish()
        assertEquals(9, h.attempted.size)
        assertEquals(250L, h.queue.nextRetryAt)
        repeat(30) { h.dispatch.request() }; h.main.finish()
        assertEquals(9, h.attempted.size)
        h.time = 250; h.outcome = Outcome.COMPLETE; h.dispatch.request(); h.main.finish()
        assertEquals(18, h.attempted.size)
    }
    @Test fun rejectedInitialDispatchRetainsWorkUntilAnotherEvent() {
        val h = Harness(); h.main.reject = true; h.add("key")
        assertEquals(1, h.queue.size); assertEquals(1, h.errors)
        h.main.reject = false; h.dispatch.request(); h.main.finish()
        assertEquals(listOf("key"), h.attempted)
    }
    @Test fun throwingInitialDispatchIsNotStuckScheduledAndRetainsWork() {
        val h = Harness(); h.main.throwPost = true; h.add("key")
        assertEquals(1, h.queue.size); assertEquals(1, h.errors)
        h.main.throwPost = false; h.dispatch.request(); h.main.finish()
        assertEquals(listOf("key"), h.attempted)
    }
    @Test fun clearBetweenSlicesPreventsAnyFurtherMutation() {
        val h = Harness(); repeat(10) { h.add("key$it") }; h.main.step()
        h.queue.clear(); h.main.finish()
        assertEquals(4, h.attempted.size)
        repeat(10) { h.dispatch.request() }
        assertEquals(2, h.main.posts)
    }
    @Test fun invalidAndBackoffEntriesAlsoRespectVisitLimit() {
        val h = Harness(); var visits = 0; h.valid = { visits++; false }
        repeat(11) { h.add("key$it") }; h.main.step()
        assertEquals(4, visits); h.main.finish(); assertEquals(11, visits)
    }
    @Test fun batchHasEightItemLimitAndNoEmptyReposting() {
        val main = MainQueue(); var ran = 0
        val batch = FocusTemplateCleanupBatch(main::post, { 0 }, { fail() })
        assertTrue(batch.submitAll(List(33) { Runnable { ran++ } }))
        main.step(); assertEquals(8, ran)
        main.finish(); assertEquals(33, ran); assertEquals(5, main.posts)
        assertTrue(batch.submitAll(emptyList())); assertEquals(5, main.posts)
    }
    @Test fun batchSoftBudgetYieldsWithoutDroppingCallbacks() {
        val main = MainQueue(); var time = 0L; var ran = 0
        val batch = FocusTemplateCleanupBatch(main::post, { time }, { fail() })
        batch.submitAll(List(4) { Runnable { ran++; time += 3 } })
        main.step(); assertEquals(1, ran)
        main.finish(); assertEquals(4, main.posts)
    }
    @Test fun rejectedRetirementBatchCanRollBackWithoutRunningAnyTask() {
        val main = MainQueue(); main.reject = true; var ran = 0
        val batch = FocusTemplateCleanupBatch(main::post, { 0 }, { fail() })
        assertFalse(batch.submitAll(List(20) { Runnable { ran++ } }))
        main.reject = false
        assertTrue(batch.submit(Runnable { ran += 100 }))
        main.finish(); assertEquals(100, ran)
    }
    @Test fun throwingRetirementDispatchRollsBackOnlyTheNewBatch() {
        val main = MainQueue(); main.throwPost = true; var ran = 0
        val batch = FocusTemplateCleanupBatch(main::post, { 0 }, { fail() })
        assertFalse(batch.submitAll(List(20) { Runnable { ran++ } }))
        main.throwPost = false; batch.submit(Runnable { ran += 100 }); main.finish()
        assertEquals(100, ran)
    }
    @Test fun batchCapacityRejectsAtomicallyWithoutEvictingOldWork() {
        val main = MainQueue(); var ran = 0
        val batch = FocusTemplateCleanupBatch(main::post, { 0 }, { fail() }, capacity = 2)
        assertTrue(batch.submit(Runnable { ran++ }))
        assertFalse(batch.submitAll(List(2) { Runnable { ran += 10 } }))
        main.finish(); assertEquals(1, ran)
    }
    @Test fun batchCancellationAndReentrantSubmissionAreSafe() {
        val main = MainQueue(); var ran = 0
        val batch = FocusTemplateCleanupBatch(main::post, { 0 }, { fail() })
        val removed = Runnable { fail() }
        batch.submitAll(listOf(Runnable { batch.submit(Runnable { ran++ }) }, removed))
        batch.remove(removed); main.finish(); assertEquals(1, ran)
    }
    @Test fun batchFailureDoesNotDiscardOtherCleanupOrRetryFailingNativeWork() {
        val main = MainQueue(); var errors = 0; var failedCalls = 0; var ran = 0
        val batch = FocusTemplateCleanupBatch(main::post, { 0 }, { errors++ })
        batch.submitAll(listOf(Runnable { failedCalls++; error("native failed") }, Runnable { ran++ }))
        main.finish(); assertEquals(1, errors); assertEquals(1, failedCalls); assertEquals(1, ran)
    }
    @Test fun rejectedContinuationWaitsForAnEventAndResumesTheExactTail() {
        val main = MainQueue(); var errors = 0; var ran = 0
        val batch = FocusTemplateCleanupBatch(main::post, { 0 }, { errors++ })
        batch.submitAll(List(10) { Runnable { ran++ } })
        main.reject = true; main.step()
        assertEquals(8, ran); assertEquals(1, errors); assertTrue(main.tasks.isEmpty())
        main.reject = false; batch.submit(Runnable { ran++ }); main.finish()
        assertEquals(11, ran)
    }
    @Test fun throwingContinuationRetainsTailAndClearsScheduledFlag() {
        val main = MainQueue(); var errors = 0; var ran = 0
        val batch = FocusTemplateCleanupBatch(main::post, { 0 }, { errors++ })
        batch.submitAll(List(10) { Runnable { ran++ } })
        main.throwPost = true; main.step()
        assertEquals(8, ran); assertEquals(1, errors)
        main.throwPost = false; batch.submit(Runnable { ran++ }); main.finish()
        assertEquals(11, ran)
    }
}
