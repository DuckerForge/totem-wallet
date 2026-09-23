package com.clearsign.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The answers Rugcheck gave on 15 September 2026, and what the gate must make of them. */
class RugCheckTest {
    private fun summary(score: Int, lp: Double, vararg risks: Pair<String, String>) = JSONObject()
        .put("score_normalised", score).put("lpLockedPct", lp)
        .put("risks", org.json.JSONArray().apply { risks.forEach { (n, l) -> put(JSONObject().put("name", n).put("level", l)) } })

    private fun full(rugged: Boolean, liquidity: Double) = JSONObject().put("rugged", rugged).put("totalMarketLiquidity", liquidity)

    @Test fun burnedPoolPumpCoinIsFine() {
        val v = RugCheck.judge(summary(1, 96.9), full(false, 1_157_618.0), italian = true)
        assertTrue(v is RugCheck.Verdict.Ok)
        assertEquals(1, (v as RugCheck.Verdict.Ok).score)
    }

    @Test fun copycatStops() {
        val v = RugCheck.judge(summary(29, 81.1, "Copycat token" to "warn"), full(false, 50_000.0), italian = true)
        assertEquals(RugCheck.Verdict.Stop("copia di una moneta verificata"), v)
    }

    @Test fun deepPoolWithUnlockedLpIsFine() {
        // JUP: LP 1.5% locked, but the pools are deep. A blue chip is not a rug because nobody burned its LP.
        val v = RugCheck.judge(summary(7, 1.5, "Mutable metadata" to "warn"), full(false, 20_000_000.0), italian = false)
        assertTrue(v is RugCheck.Verdict.Ok)
    }

    @Test fun shallowPoolWithUnlockedLpStops() {
        val v = RugCheck.judge(summary(20, 12.0), full(false, 40_000.0), italian = false)
        assertEquals(RugCheck.Verdict.Stop("LP only 12% locked"), v)
    }

    @Test fun dangerAndRuggedStop() {
        assertTrue(RugCheck.judge(summary(10, 99.0, "Freeze Authority still enabled" to "danger"), null, false) is RugCheck.Verdict.Stop)
        assertEquals(RugCheck.Verdict.Stop("already rugged"), RugCheck.judge(summary(5, 99.0), full(true, 1.0), false))
    }

    @Test fun dangerNameReadsItalian() {
        val v = RugCheck.judge(summary(10, 99.0, "Freeze Authority still enabled" to "danger"), null, italian = true)
        assertEquals(RugCheck.Verdict.Stop("autorità di freeze ancora attiva"), v)
        // A name we do not know stays as Rugcheck wrote it: better English than a guess.
        assertEquals("Some New Risk", RugCheck.italianRiskName("Some New Risk"))
    }

    @Test fun highScoreStops() {
        assertTrue(RugCheck.judge(summary(75, 99.0), full(false, 1_000_000.0), false) is RugCheck.Verdict.Stop)
    }
}
