package com.clearsign.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyticsTest {
    private val sol = com.clearsign.core.NATIVE_SOL_MINT
    private fun entry(at: Long, out: List<Leg> = emptyList(), inn: List<Leg> = emptyList(), price: Map<String, Double>) =
        LedgerEntry("id$at", "g", at, "swap", "Jupiter", "jup.ag", null, "mainnet", "me", "S$at", true, 0, 1, out, inn, 5000L, true, emptyList(), null, null, emptyList(),
            mapOf("EUR" to FiatSnapshot("EUR", price[sol] ?: 0.0, price.filterKeys { it != sol }, at, "spot")))

    @Test fun buyLowSellHighIsPositive() {
        val a = AnalyticsEngine.of(listOf(
            // buy 100 USDC paying, receiving into position: model USDC as inflow at €1 each
            entry(1, inn = listOf(Leg("USDC", "USDC", 6, 100_000_000)), price = mapOf("USDC" to 1.0)),
            // later dispose 100 USDC at €1.10 each → +€10 realized
            entry(2, out = listOf(Leg("USDC", "USDC", 6, -100_000_000)), price = mapOf("USDC" to 1.10)),
        ), "EUR")
        val u = a.tokens.single { it.symbol == "USDC" }
        assertEquals(10.0, u.realized, 1e-6)
        assertEquals(0.0, u.heldUnits, 1e-9)
        assertTrue(a.totalRealized > 9.9)
    }

    @Test fun partialSaleLeavesHeldPosition() {
        val a = AnalyticsEngine.of(listOf(
            entry(1, inn = listOf(Leg("SKR", "SKR", 6, 1_000_000_000)), price = mapOf("SKR" to 0.03)),   // 1000 SKR @ 0.03
            entry(2, out = listOf(Leg("SKR", "SKR", 6, -400_000_000)), price = mapOf("SKR" to 0.05)),      // sell 400 @ 0.05 → proceeds 20, cost 12 → +8
        ), "EUR")
        val s = a.tokens.single { it.symbol == "SKR" }
        assertEquals(8.0, s.realized, 1e-6)
        assertEquals(600.0, s.heldUnits, 1e-6)
    }
}
