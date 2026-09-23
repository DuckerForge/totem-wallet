package com.clearsign.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OreOddsTest {
    @Test fun `le fee sono quelle del programma, con il minimo di un lamport`() {
        assertEquals(1L, OreOdds.adminFee(50))
        assertEquals(10L, OreOdds.adminFee(1_000))
        assertEquals(99L, OreOdds.protocolFee(1_000))
        assertEquals(1L, OreOdds.protocolFee(5))
    }

    @Test fun `da solo sulla casella riprendo il 99 se vince e l'89 e rotti se perde`() {
        assertEquals(990L, OreOdds.backIfWin(1_000, 1_000))
        assertEquals(891L, OreOdds.backIfLose(1_000, 1_000))
        // In company the return is pro rata, with the same fee on the total.
        assertEquals(495L, OreOdds.backIfWin(500, 1_000))
    }

    @Test fun `la quota di ORE e' la mia parte della casella, e l'atteso ne prende un venticinquesimo`() {
        val o = OreOdds.outlook(1_000_000, listOf(0L), Ore.ONE_ORE, 0L)
        assertEquals(1.0, o.shares[0])
        assertEquals(Ore.ONE_ORE / 25, o.expectedOre)
        val crowded = OreOdds.outlook(1_000_000, listOf(3_000_000L), Ore.ONE_ORE, 0L)
        assertEquals(0.25, crowded.shares[0])
        assertEquals(Ore.ONE_ORE / 100, crowded.expectedOre)
    }

    @Test fun `il costo atteso e' circa il dieci e mezzo per cento, qualunque casella`() {
        val empty = OreOdds.outlook(1_000_000, listOf(0L), Ore.ONE_ORE, 0L)
        val crowded = OreOdds.outlook(1_000_000, listOf(50_000_000L), Ore.ONE_ORE, 0L)
        assertTrue(empty.costFraction in 0.104..0.106, "vuota: ${empty.costFraction}")
        assertTrue(crowded.costFraction in 0.104..0.106, "piena: ${crowded.costFraction}")
        assertEquals(3_000_000L, OreOdds.outlook(1_000_000, listOf(0L, 1L, 2L), Ore.ONE_ORE, 0L).stake)
    }

    @Test fun `la pentola pesa un cinquecentesimo`() {
        val o = OreOdds.outlook(1_000_000, listOf(0L), 0L, 500 * Ore.ONE_ORE)
        assertEquals(Ore.ONE_ORE / 25, o.expectedOre)
    }

    @Test fun `le migliori sono le piu' vuote, a parita' le meno affollate`() {
        val deployed = longArrayOf(5, 0, 3, 0, 9)
        val count = longArrayOf(1, 2, 1, 1, 1)
        assertEquals(listOf(3, 1, 2), OreOdds.best(3, deployed, count))
        assertEquals(emptyList(), OreOdds.best(0, deployed, count))
    }
}
