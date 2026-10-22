package io.github.hyperisland.xposed.hook

import java.lang.ref.WeakReference
import org.junit.Assert.*
import org.junit.Test

class StableIslandGlowFadeTest {
    private class Target(var alpha: Float = 1f) {
        var reads = 0
        var writes = 0
        var failRead = false
        var failBeforeWrite = false
        var failAfterWrite = false
        fun read(): Float {
            reads++
            if (failRead) error("read")
            return alpha
        }
        fun write(value: Float) {
            writes++
            if (failBeforeWrite) { failBeforeWrite = false; error("before write") }
            alpha = value
            if (failAfterWrite) { failAfterWrite = false; error("after write") }
        }
    }
    private fun pair(upper: Target, lower: Target) = StableIslandGlowFadeAlpha(
        upper, lower, Target::read, Target::write,
    )
    private fun alpha(expected: Float, target: Target) = assertEquals(expected, target.alpha, 0f)

    @Test fun nonPositiveDurationsAreRejected() {
        for (duration in listOf(0L, -1L, Long.MIN_VALUE)) {
            try { StableIslandGlowFade(duration); fail("duration=$duration") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun invalidInitialFactorsAreRejected() {
        for (factor in listOf(0f, -0.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            try { StableIslandGlowFade(initialFactor = factor); fail("factor=$factor") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun unarmedSamplingAlwaysPreservesNativeBrightness() {
        val fade = StableIslandGlowFade()
        assertTrue(fade.complete)
        assertFalse(fade.started)
        assertFalse(fade.pending)
        repeat(1000) { assertEquals(1f, fade.sample(it.toLong()), 0f) }
        assertFalse(fade.started)
    }
    @Test fun explicitArmWaitsForFirstNativeFrameWithoutStartingTheClock() {
        val fade = StableIslandGlowFade()
        assertTrue(fade.arm())
        assertTrue(fade.pending)
        assertFalse(fade.started)
        assertFalse(fade.complete)
        assertEquals(0.5f, fade.sample(10000L), 0f)
        assertFalse(fade.pending)
        assertTrue(fade.started)
    }
    @Test fun smoothstepHasExpectedEndpointsAndMidpoint() {
        val fade = StableIslandGlowFade()
        fade.arm()
        assertEquals(0.5f, fade.sample(100L), 0f)
        assertEquals(0.578125f, fade.sample(325L), 0.000001f)
        assertEquals(0.75f, fade.sample(550L), 0.000001f)
        assertEquals(0.921875f, fade.sample(775L), 0.000001f)
        assertEquals(1f, fade.sample(1000L), 0f)
        assertTrue(fade.complete)
    }
    @Test fun defaultFadeTakesNineHundredMillisFromItsFirstFrame() {
        val fade = StableIslandGlowFade()
        fade.arm()
        assertEquals(0.5f, fade.sample(10000L), 0f)
        assertTrue(fade.sample(10320L) < 1f)
        assertFalse(fade.complete)
        assertEquals(0.75f, fade.sample(10450L), 0.000001f)
        assertTrue(fade.sample(10899L) < 1f)
        assertFalse(fade.complete)
        assertEquals(1f, fade.sample(10900L), 0f)
        assertTrue(fade.complete)
    }
    @Test fun samplingNeverDimsOrExtinguishesStartedLight() {
        val fade = StableIslandGlowFade()
        fade.arm()
        var previous = 0.5f
        repeat(1000) {
            val factor = fade.sample(it.toLong())
            assertTrue(factor >= 0.5f && factor <= 1f)
            assertTrue(factor >= previous)
            previous = factor
        }
    }
    @Test fun completedFadeCannotRestartThroughOrdinarySampling() {
        val fade = StableIslandGlowFade()
        fade.arm(); fade.sample(0L); fade.sample(900L)
        repeat(1000) { assertEquals(1f, fade.sample(it.toLong()), 0f) }
        assertTrue(fade.complete)
        assertTrue(fade.started)
        assertFalse(fade.pending)
    }
    @Test fun repeatedTimestampDoesNotAdvanceFade() {
        val fade = StableIslandGlowFade()
        fade.arm(); fade.sample(20L)
        repeat(20) { assertEquals(0.5f, fade.sample(20L), 0f) }
    }
    @Test fun cancelImmediatelyRestoresNativeAndCannotReplayThroughSamples() {
        val fade = StableIslandGlowFade()
        fade.arm(); fade.sample(0L); fade.sample(450L)
        fade.cancel()
        assertTrue(fade.complete)
        assertFalse(fade.started)
        assertFalse(fade.pending)
        repeat(1000) { assertEquals(1f, fade.sample(it.toLong()), 0f) }
    }
    @Test fun cancellingAPendingReturnNeverStartsTheFadeLater() {
        val fade = StableIslandGlowFade()
        fade.arm(); fade.cancel()
        assertEquals(1f, fade.sample(10000L), 0f)
        assertFalse(fade.started)
    }
    @Test fun cancelIsIdempotentBeforeDuringAndAfterCompletion() {
        val fade = StableIslandGlowFade()
        repeat(5) { fade.cancel(); assertEquals(1f, fade.sample(0L), 0f) }
        fade.arm(); fade.sample(0L); fade.sample(900L)
        repeat(5) { fade.cancel(); assertEquals(1f, fade.sample(0L), 0f) }
    }
    @Test fun onlyAnExplicitNewPermissionCanStartAnotherCycle() {
        val fade = StableIslandGlowFade()
        repeat(100) {
            assertTrue(fade.arm())
            assertEquals(0.5f, fade.sample(100L), 0f)
            assertEquals(1f, fade.sample(1000L), 0f)
        }
    }
    @Test fun duplicateArmWhilePendingDoesNotReplaceThePermission() {
        val fade = StableIslandGlowFade()
        assertTrue(fade.arm())
        repeat(10) { assertFalse(fade.arm()) }
        assertTrue(fade.pending)
        assertEquals(0.5f, fade.sample(0L), 0f)
    }
    @Test fun duplicateArmDuringPlaybackCannotMoveBrightnessBackwards() {
        val fade = StableIslandGlowFade()
        fade.arm(); fade.sample(0L)
        val midpoint = fade.sample(450L)
        repeat(10) { assertFalse(fade.arm()) }
        assertEquals(midpoint, fade.sample(450L), 0f)
        assertEquals(1f, fade.sample(900L), 0f)
    }
    @Test fun clockRollbackNeverReducesAnEmittedFactor() {
        val fade = StableIslandGlowFade()
        fade.arm(); fade.sample(1000L)
        assertEquals(0.75f, fade.sample(1450L), 0.000001f)
        assertEquals(0.75f, fade.sample(900L), 0.000001f)
        assertEquals(0.75f, fade.sample(1100L), 0.000001f)
        assertEquals(1f, fade.sample(1900L), 0f)
    }
    @Test fun elapsedOverflowSaturatesAtCompletion() {
        val fade = StableIslandGlowFade()
        fade.arm(); fade.sample(Long.MIN_VALUE)
        assertEquals(1f, fade.sample(Long.MAX_VALUE), 0f)
    }
    @Test fun configuredDurationAndInitialBrightnessAreUsed() {
        val fade = StableIslandGlowFade(20L, 0.8f)
        fade.arm()
        assertEquals(0.8f, fade.sample(0L), 0f)
        assertEquals(0.9f, fade.sample(10L), 0.000001f)
        assertEquals(1f, fade.sample(20L), 0f)
    }
    @Test fun fullBrightnessInitialValueCompletesOnItsFirstFrame() {
        val fade = StableIslandGlowFade(initialFactor = 1f)
        assertTrue(fade.arm())
        assertEquals(1f, fade.sample(100L), 0f)
        assertTrue(fade.complete)
        assertFalse(fade.pending)
    }
    @Test fun initialZeroArmsBothTargetsAndCloseRestores() {
        val upper = Target(); val lower = Target(); val owner = pair(upper, lower)
        assertTrue(owner.apply(0f)); alpha(0f, upper); alpha(0f, lower)
        assertTrue(owner.apply(0.5f)); alpha(0.5f, upper); alpha(0.5f, lower)
        owner.close(); alpha(1f, upper); alpha(1f, lower)
    }
    @Test fun initialNonUnitAlphaIsNotClaimedOrOverwritten() {
        for (value in listOf(0f, 0.5f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val upper = Target(); val lower = Target(value); val owner = pair(upper, lower)
            assertFalse(owner.apply(0f))
            assertEquals(0, upper.writes); assertEquals(0, lower.writes)
            assertFalse(owner.apply(1f))
        }
    }
    @Test fun invalidFactorsCloseAndRestoreAnActiveOwner() {
        for (value in listOf(-0.1f, 1.1f, Float.NaN, Float.NEGATIVE_INFINITY)) {
            val upper = Target(); val lower = Target(); val owner = pair(upper, lower)
            assertTrue(owner.apply(0.25f)); assertFalse(owner.apply(value))
            alpha(1f, upper); alpha(1f, lower)
            assertFalse(owner.apply(0f))
        }
    }
    @Test fun invalidFirstFactorDoesNotWrite() {
        val upper = Target(); val lower = Target(); val owner = pair(upper, lower)
        assertFalse(owner.apply(Float.NaN)); assertEquals(0, upper.writes)
        assertEquals(0, lower.writes)
    }
    @Test fun repeatedFactorChecksOwnershipWithoutRepeatedWrites() {
        val upper = Target(); val lower = Target(); val owner = pair(upper, lower)
        assertTrue(owner.apply(0.5f))
        repeat(20) { assertTrue(owner.apply(0.5f)) }
        assertEquals(1, upper.writes); assertEquals(1, lower.writes)
    }
    @Test fun externalUpperWriteIsPreservedWhileLowerRestores() {
        val upper = Target(); val lower = Target(); val owner = pair(upper, lower)
        owner.apply(0.2f); upper.alpha = 0.7f
        assertFalse(owner.apply(0.5f)); alpha(0.7f, upper); alpha(1f, lower)
    }
    @Test fun externalLowerWriteIsPreservedWhileUpperRestores() {
        val upper = Target(); val lower = Target(); val owner = pair(upper, lower)
        owner.apply(0.2f); lower.alpha = 0.7f
        owner.close(); alpha(1f, upper); alpha(0.7f, lower)
    }
    @Test fun nonFiniteExternalWriteDoesNotGetOverwritten() {
        val upper = Target(); val lower = Target(); val owner = pair(upper, lower)
        owner.apply(0f); upper.alpha = Float.NaN
        assertFalse(owner.apply(0.5f)); assertTrue(upper.alpha.isNaN()); alpha(1f, lower)
    }
    @Test fun lowerWriteFailureRollsBackUpperAndPreviousLower() {
        val upper = Target(); val lower = Target(); val owner = pair(upper, lower)
        owner.apply(0.25f); lower.failBeforeWrite = true
        assertFalse(owner.apply(0.5f)); alpha(1f, upper); alpha(1f, lower)
    }
    @Test fun lowerMutateThenThrowAlsoRollsBackBothTargets() {
        val upper = Target(); val lower = Target(); val owner = pair(upper, lower)
        owner.apply(0.25f); lower.failAfterWrite = true
        assertFalse(owner.apply(0.5f)); alpha(1f, upper); alpha(1f, lower)
    }
    @Test fun upperFailureRestoresBothPreviouslyOwnedTargets() {
        val upper = Target(); val lower = Target(); val owner = pair(upper, lower)
        owner.apply(0.25f); upper.failBeforeWrite = true
        assertFalse(owner.apply(0.5f)); alpha(1f, upper); alpha(1f, lower)
    }
    @Test fun firstWriteMutateThenThrowRestoresAndStopsBeforeLower() {
        val upper = Target(); val lower = Target(); val owner = pair(upper, lower)
        upper.failAfterWrite = true
        assertFalse(owner.apply(0f)); alpha(1f, upper); alpha(1f, lower)
        assertEquals(0, lower.writes)
    }
    @Test fun readFailureDoesNotPreventOtherSideRestoration() {
        val upper = Target(); val lower = Target(); val owner = pair(upper, lower)
        owner.apply(0.25f); upper.failRead = true
        assertFalse(owner.apply(0.5f)); alpha(1f, lower)
        assertFalse(owner.apply(1f))
    }
    @Test fun restoreWriteFailureDoesNotPreventOtherSideRestoration() {
        val upper = Target(); val lower = Target(); val owner = pair(upper, lower)
        owner.apply(0.25f); upper.failBeforeWrite = true
        owner.close(); alpha(1f, lower)
        assertFalse(owner.apply(1f))
    }
    @Test fun closeIsIdempotentAndLateApplyCannotResume() {
        val upper = Target(); val lower = Target(); val owner = pair(upper, lower)
        owner.apply(0f); owner.close()
        val upperWrites = upper.writes; val lowerWrites = lower.writes
        repeat(10) { owner.close(); assertFalse(owner.apply(0f)) }
        assertEquals(upperWrites, upper.writes); assertEquals(lowerWrites, lower.writes)
    }
    @Test fun closeBeforeClaimDoesNotReadOrWriteEitherTarget() {
        val upper = Target(); val lower = Target(); val owner = pair(upper, lower)
        owner.close(); assertFalse(owner.apply(0f))
        assertEquals(0, upper.reads + lower.reads + upper.writes + lower.writes)
    }
    @Test fun clearedWeakTargetRestoresItsSurvivingPartnerWithoutGc() {
        val upper = Target(); val lower = Target(); val owner = pair(upper, lower)
        owner.apply(0.25f)
        val ref = owner.javaClass.getDeclaredField("upperRef").apply { isAccessible = true }
        (ref.get(owner) as WeakReference<*>).clear()
        assertFalse(owner.apply(0.5f)); alpha(1f, lower)
    }
    @Test fun targetsAreOnlyStoredInWeakReferencesAndCloseClearsAccessors() {
        val upper = Target(); val lower = Target(); val owner = pair(upper, lower)
        for (field in owner.javaClass.declaredFields) {
            field.isAccessible = true
            assertNotSame(upper, field.get(owner)); assertNotSame(lower, field.get(owner))
        }
        owner.close()
        for (name in listOf("upperRef", "lowerRef")) {
            val field = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
            assertNull((field.get(owner) as WeakReference<*>).get())
        }
        for (name in listOf("readValue", "writeValue")) {
            val field = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
            assertNull(field.get(owner))
        }
    }
    @Test fun reentrantCloseFromSetterCannotWriteTheOtherTarget() {
        val upper = Target(); val lower = Target()
        lateinit var owner: StableIslandGlowFadeAlpha<Target>
        var cancel = true
        owner = StableIslandGlowFadeAlpha(upper, lower, Target::read) { target, value ->
            target.write(value)
            if (cancel) { cancel = false; owner.close() }
        }
        assertFalse(owner.apply(0f)); alpha(1f, upper); alpha(1f, lower)
        assertEquals(0, lower.writes)
    }
}
