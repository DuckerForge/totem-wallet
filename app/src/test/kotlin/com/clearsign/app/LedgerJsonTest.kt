package com.clearsign.app

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LedgerJsonTest {
    private val sol = com.clearsign.core.NATIVE_SOL_MINT
    private val entry = LedgerEntry(
        id = "abc123", groupId = "g1", at = 1_789_000_000_000L, kind = "tx",
        dApp = "Jupiter", host = "jup.ag", pkg = "ag.jup.jupiter.android", cluster = "mainnet-beta", wallet = "cHAH",
        signature = "5xSig", sent = false, txIndex = 1, txCount = 3,
        outflows = listOf(Leg(sol, "SOL", 9, -500_000_000L)), inflows = listOf(Leg("EPjF", "USDC", 6, 68_120_000L)),
        feeLamports = 5_000L, feePaidByMe = true,
        counterparties = listOf(Counterparty("9WzD", "Alice", "TRUSTED", false, false, 450_000_000L, sol, "SOL", 9)),
        primaryRecipient = "9WzD", recipientLabel = "Alice",
        risks = listOf(RiskNote("NEW_UNKNOWN_RECIPIENT", "WARN", "First time")),
        fiat = mapOf("EUR" to FiatSnapshot("EUR", 142.5, mapOf("EPjF" to 0.92), 1_789_000_001_000L, "spot")),
        attestation = "{\"v\":1}", attestationSig = "MEUC", note = "dinner, split", tags = listOf("expense"),
    )

    @Test fun roundTrip() {
        val back = LedgerJson.decode(JSONObject(LedgerJson.encode(entry).toString()))
        assertEquals(entry, back)
        assertEquals(-0.5, back.outflows[0].uiAmount, 1e-12)
        assertEquals(0.5 * 142.5, back.fiatValue("EUR")!!, 1e-9)
        assertNull(back.fiatValue("USD"))
    }

    @Test fun unknownAndMissingFieldsAreTolerated() {
        val o = LedgerJson.encode(entry).put("future", "x").also { it.remove("fiat"); it.remove("tags") }
        val back = LedgerJson.decode(o)
        assertEquals(entry.copy(fiat = emptyMap(), tags = emptyList()), back)
        assertEquals("2026-09", Ledger.yearMonth(entry.at))
    }
}
