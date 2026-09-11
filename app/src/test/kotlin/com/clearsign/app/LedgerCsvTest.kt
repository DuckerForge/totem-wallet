package com.clearsign.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LedgerCsvTest {
    private val sol = com.clearsign.core.NATIVE_SOL_MINT
    private fun entry(
        id: String = "id1", at: Long = 1_789_000_000_000L, kind: String = "tx", out: List<Leg> = emptyList(), inn: List<Leg> = emptyList(),
        fee: Long = 5_000L, feeMine: Boolean = true, tags: List<String> = emptyList(), fiat: Map<String, FiatSnapshot> = emptyMap(), label: String? = "Alice",
    ) = LedgerEntry(id, "g", at, kind, "Jupiter", "jup.ag", null, null, "me", "SIG$id", true, 0, 1, out, inn, fee, feeMine, emptyList(), "9WzD", label, emptyList(), fiat, null, null, "", tags)

    private val swap = entry(out = listOf(Leg(sol, "SOL", 9, -500_000_000)), inn = listOf(Leg("EPjF", "USDC", 6, 68_120_000)), fiat = mapOf("EUR" to FiatSnapshot("EUR", 142.5, mapOf("EPjF" to 0.92), 0, "spot")))

    @Test fun koinlySwapIsOneRowWithFeeAndNetWorth() {
        val lines = LedgerCsv.koinly(listOf(swap), "EUR").trim().lines()
        assertEquals(LedgerCsv.KOINLY_HEADER, lines[0])
        assertEquals(2, lines.size)
        assertEquals("2026-09-10 00:26:40,0.5,SOL,68.12,USDC,0.000005,SOL,71.25,EUR,,Jupiter · tx · Alice,SIGid1", lines[1])
    }

    @Test fun feeOnlyOnceAndOnlyWhenPaid() {
        val two = entry(out = listOf(Leg(sol, "SOL", 9, -100_000_000), Leg("EPjF", "USDC", 6, -1_000_000)))
        val lines = LedgerCsv.koinly(listOf(two), "EUR").trim().lines().drop(1)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains(",0.000005,SOL,"))
        assertTrue(lines[1].contains(",,,") && !lines[1].contains("0.000005"))
        val foreign = entry(out = listOf(Leg(sol, "SOL", 9, -100_000_000)), feeMine = false)
        assertTrue(!LedgerCsv.koinly(listOf(foreign), "EUR").contains("0.000005"))
    }

    @Test fun labelsQuotingAndSkips() {
        val gift = entry(id = "g", out = listOf(Leg(sol, "SOL", 9, -1)), tags = listOf("gift"), label = "Bob, the \"builder\"")
        val row = LedgerCsv.koinly(listOf(gift), "USD").trim().lines()[1]
        assertTrue(row.endsWith(",gift,\"Jupiter · tx · Bob, the \"\"builder\"\"\",SIGg"), row)
        assertTrue(row.contains(",,,gift,"))   // no fiat without a USD snapshot
        val login = entry(id = "l", kind = "signin")
        assertEquals(1, LedgerCsv.koinly(listOf(login), "USD").trim().lines().size)
        val theme = entry(id = "t", kind = "theme", out = listOf(Leg("SKRb", "SKR", 6, -5_000_000)))
        assertTrue(LedgerCsv.koinly(listOf(theme), "USD").contains(",cost,"))
    }

    @Test fun coinTrackerFormat() {
        val lines = LedgerCsv.coinTracker(listOf(swap)).trim().lines()
        assertEquals(LedgerCsv.COINTRACKER_HEADER, lines[0])
        assertEquals("09/10/2026 00:26:40,68.12,USDC,0.5,SOL,0.000005,SOL,", lines[1])
    }
}
