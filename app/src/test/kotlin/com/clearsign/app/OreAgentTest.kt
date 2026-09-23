package com.clearsign.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How much to hand over, from the two sliders: the pure part of the digging agent. */
class OreAgentTest {
    @Test fun `un tetto normale su tre caselle`() {
        val s = assertNotNull(OreAgent.sizing(50_000_000L, 3, 100_000_000L, 200_000_000L))
        // 50,000,000 / 1440 = 34,722 per round, minus 7,000 of fee, split over 3 squares.
        assertEquals(9_240L, s.amountPerSquare)
        assertEquals(3, s.squares)
        assertEquals(3 * 9_240L + 7_000L, s.perRound)
        assertTrue(s.deposit <= 50_000_000L)
        assertEquals(s.rounds.toLong() * s.perRound, s.deposit)
        assertTrue(s.rounds > 1_000)
    }

    @Test fun `il tetto del collare e il SOL libero limitano il deposito`() {
        val s = assertNotNull(OreAgent.sizing(100_000_000L, 2, 10_000_000L, 200_000_000L))
        assertTrue(s.deposit <= 10_000_000L)
        val t = assertNotNull(OreAgent.sizing(100_000_000L, 2, 100_000_000L, 8_000_000L))
        assertTrue(t.deposit <= 8_000_000L - OreAgent.RESERVE_LAMPORTS)
    }

    @Test fun `troppo poco per un giro`() {
        // 0.02 SOL a day on three squares: the fee eats almost everything, 2,296 lamports per square remain.
        assertNull(OreAgent.sizing(20_000_000L, 3, 100_000_000L, 200_000_000L))
        assertNull(OreAgent.sizing(50_000_000L, 3, 100_000_000L, 3_100_000L))
    }

    @Test fun `le caselle stanno fra una e venticinque`() {
        assertEquals(25, assertNotNull(OreAgent.sizing(200_000_000L, 40, 200_000_000L, 300_000_000L)).squares)
        assertEquals(1, assertNotNull(OreAgent.sizing(50_000_000L, 0, 200_000_000L, 300_000_000L)).squares)
    }
}
