package com.clearsign.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which pool gets read, with the real numbers of 20 Sep 2026 for EDEL and SOL from
 * GeckoTerminal. Kept because the old rule, the deepest pool, did not fail loudly: it picked a
 * pool idle since May, drew a plausible chart at a third of the price and opened the link on it. An error that looks like an answer is what a test is for.
 */
class GeckoPoolTest {
    private fun pool(id: String, liq: Double, vol: Double) =
        Gecko.Pool(id = id, dex = null, liquidityUsd = liq.takeIf { it > 0 }, volume24Usd = vol.takeIf { it > 0 }, buys24 = null, sells24 = null, fdvUsd = null)

    @Test fun `la pool piu' profonda ma ferma non vince`() {
        // EDEL: the deepest one has zero trades in a day.
        val pools = listOf(
            pool("Fg2LFE", 127_092.61, 14_341.86),
            pool("41S2du", 5_490.94, 247.16),
            pool("8gCUy6", 179_001.21, 0.0),
            pool("G1vH3T", 154_957.98, 0.0),
            pool("8iMF1q", 4.45, 0.0),
        )
        assertEquals("Fg2LFE", Gecko.busiest(pools)?.id)
    }

    @Test fun `una pool vuota con molto volume e' due portafogli che si palleggiano`() {
        // One fiftieth of the maximum depth is the floor: below it, volume is not a market.
        val pools = listOf(
            pool("vera", 100_000.0, 5_000.0),
            pool("guscio", 500.0, 900_000.0),
        )
        assertEquals("vera", Gecko.busiest(pools)?.id)
    }

    @Test fun `se non si scambia da nessuna parte resta la piu' profonda`() {
        val pools = listOf(pool("piccola", 10.0, 0.0), pool("grande", 8_000.0, 0.0))
        assertEquals("grande", Gecko.busiest(pools)?.id)
    }

    @Test fun `nessuna pool, nessuna risposta`() {
        assertNull(Gecko.busiest(emptyList()))
    }
}
