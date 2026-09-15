package com.clearsign.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The five rules, over price series written by hand.
 *
 * Written as series rather than single prices because that is the only way a
 * trailing rule can be wrong in an interesting way: it needs a past.
 */
class ExitRulesTest {

    private val hour = 3_600_000L
    private val entry = 1_000.0

    /** Run [rule] over [prices], one per hour, and return the leg at the end. */
    private fun run(rule: ExitRule, prices: List<Double>): PaperLeg {
        var leg = PaperLeg(rule.id, entry, entry, 0L)
        prices.forEachIndexed { i, p -> leg = rule.step(leg, p, (i + 1) * hour) }
        return leg
    }

    /**
     * Each rule wins in its own world, and that is the whole reason to run all of
     * them. The first version of this test asserted that the trail always beats a
     * fixed target on a run; it failed, and it was the test that was wrong.
     */
    @Test fun theTrailWinsWhenItKeepsGoing() {
        // Up and up: the fixed target sells at the first +30% and watches the rest.
        // The last step has to give back more than the trail's own distance, or
        // the leg is still open and there is nothing to compare. The first version
        // of this test ended at 1800, which is only 10% off the peak.
        val keepsGoing = listOf(1_100.0, 1_300.0, 1_600.0, 2_000.0, 1_650.0)
        val fixed = run(ExitRule.Fixed("t", 30, 15), keepsGoing)
        val trail = run(ExitRule.Trailing("tr", 15), keepsGoing)
        assertEquals(1_300.0, fixed.exitLamports, 0.1, "sells at the first +30%")
        assertEquals("trailing", trail.why)
        assertTrue(trail.exitLamports > fixed.exitLamports, "the trail rode to ${trail.exitLamports}")
    }

    @Test fun theFixedTargetWinsWhenItGivesItAllBack() {
        // Up 60% then down to +20%: a 15% trail hands back more than the target took.
        val retraces = listOf(1_100.0, 1_300.0, 1_600.0, 1_450.0, 1_200.0)
        val fixed = run(ExitRule.Fixed("t", 30, 15), retraces)
        val trail = run(ExitRule.Trailing("tr", 15), retraces)
        assertEquals("target", fixed.why)
        assertEquals("trailing", trail.why)
        assertTrue(fixed.exitLamports > trail.exitLamports, "taking +30% beat giving back from 1600 to ${trail.exitLamports}")
    }

    /** Straight down. Everything stops out, and the tightest stop loses least. */
    @Test fun onACollapseTheQuickRuleLosesLeast() {
        val series = listOf(950.0, 880.0, 800.0, 700.0)
        val quick = run(ExitRule.Fixed("q", 15, 10), series)
        val patient = run(ExitRule.Fixed("p", 50, 20), series)
        assertEquals("stop", quick.why)
        assertEquals("stop", patient.why)
        assertTrue(quick.exitLamports > patient.exitLamports, "quick out at ${quick.exitLamports}, patient at ${patient.exitLamports}")
    }

    /** Flat forever: only the clock closes it. */
    @Test fun onAFlatCoinOnlyTheTimedRuleCloses() {
        val flat = List(8) { 1_001.0 }
        assertTrue(run(ExitRule.Fixed("f", 30, 15), flat).open)
        assertTrue(run(ExitRule.Trailing("tr", 15), flat).open)
        val timed = run(ExitRule.Timed("ti", 6), flat)
        assertTrue(!timed.open)
        assertEquals("time", timed.why)
    }

    /** A dip before any profit is not a trailing exit: that is what a stop is for. */
    @Test fun theTrailDoesNotFireBeforeItHasEverBeenUp() {
        val dip = listOf(900.0, 820.0, 800.0)
        assertTrue(run(ExitRule.Trailing("tr", 15), dip).open, "down 20% from entry, never up: the trail has no peak to give back")
    }

    /** An unknown price holds, exactly as the live loop holds. */
    @Test fun aMissingPriceNeverCloses() {
        val rule = ExitRule.Fixed("f", 30, 15)
        var leg = PaperLeg("f", entry, entry, 0L)
        leg = rule.step(leg, null, hour)
        leg = rule.step(leg, 0.0, 2 * hour)
        assertTrue(leg.open)
    }

    // ---- the costs, which are what make this honest --------------------------

    @Test fun theRoundTripPaysFeesAndRent() {
        val size = 30_000_000L                       // 0.03 SOL
        val leg = PaperLeg("f", 1_000.0, 1_300.0, 0L, closedAt = hour, exitLamports = 1_300.0)
        val free = leg.netLamports(size, feeLamports = 0, rentLamports = 0)
        val real = leg.netLamports(size)
        assertEquals(9_000_000L, free, "a 30% gain on 0.03 SOL is 0.009 before costs")
        assertEquals(free - 2 * 5_000L - 2_040_000L, real, "and the costs are two fees plus the account rent")
    }

    /** The case from this morning: on a small slice the rent eats the trade. */
    @Test fun onASmallSliceTheRentIsMostOfTheTrade() {
        val slice = 7_800_000L                       // the old 0.0078 SOL slice
        val leg = PaperLeg("f", 1_000.0, 1_150.0, 0L, closedAt = hour, exitLamports = 1_150.0)
        val net = leg.netLamports(slice)
        assertTrue(net < 0, "up 15% and still down after costs: $net lamports")
    }

    @Test fun priceImpactIsChargedToo() {
        val size = 30_000_000L
        val leg = PaperLeg("f", 1_000.0, 1_300.0, 0L, closedAt = hour, exitLamports = 1_300.0)
        assertTrue(leg.netLamports(size, impactPct = 2.0) < leg.netLamports(size, impactPct = 0.0))
    }
}
