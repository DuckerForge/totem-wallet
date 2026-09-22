package com.clearsign.app

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Il pool senza rete: l'ordine, la tabella di verita' delle risposte, e quello
 * che si impara da ognuna. Sono le decisioni piu' rischiose del file RPC, e
 * finora non ne aveva nessuna sotto test.
 */
class RpcPoolTest {
    private val helius = RpcPool.Provider("helius", "https://h", perSecond = 10, weight = 10, das = true)
    private val alchemy = RpcPool.Provider("alchemy", "https://a", perSecond = 15, weight = 20)
    private val chainstack = RpcPool.Provider("chainstack", "https://c", perSecond = 25, weight = 30)
    private val rpcfast = RpcPool.Provider("rpcfast", "https://r", perSecond = 15, weight = 15, unsupported = setOf("getTokenAccountsByOwner"))
    private val drpc = RpcPool.Provider("drpc", "https://d", perSecond = 20, weight = 25, sends = false)
    private val mainnet = RpcPool.Provider("mainnet", "https://m", perSecond = 5, weight = 1, lastResort = true)
    private val all = listOf(helius, alchemy, chainstack, rpcfast, drpc, mainnet)
    private val own = RpcPool.Provider("own", "https://mine", perSecond = 20, weight = 1)
    private val now = 1_800_000_000_000L

    private class MemStore : RpcPool.Store {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
    }

    @Test fun `rpcfast non serve mai i conti token, e la spiaggia sta in fondo`() {
        for (seed in 1L..40L) {
            val lanes = RpcPool(all, seed).lanes("getTokenAccountsByOwner", now)
            assertTrue(lanes.none { it.name == "rpcfast" }, "seed $seed")
            assertEquals("mainnet", lanes.last().name)
            assertEquals(5, lanes.size)
        }
    }

    @Test fun `semi diversi danno prime scelte diverse, lo stesso seme e' stabile`() {
        val firsts = (1L..60L).map { RpcPool(all, it).lanes("getBalance", now).first().name }.toSet()
        assertTrue(firsts.size >= 3, "spread: $firsts")
        assertEquals(RpcPool(all, 7L).lanes("getBalance", now), RpcPool(all, 7L).lanes("getBalance", now))
    }

    @Test fun `chi pesa di piu' esce prima piu' spesso`() {
        val counts = (1L..600L).groupingBy { RpcPool(all, it).lanes("getBalance", now).first().name }.eachCount()
        assertTrue((counts["chainstack"] ?: 0) > (counts["helius"] ?: 0), "$counts")
    }

    @Test fun `il nodo proprio viene primo e il pool resta dietro`() {
        val lanes = RpcPool(all, 3L).lanes("getBalance", now, own)
        assertEquals("own", lanes.first().name)
        assertEquals(all.size + 1, lanes.size)
    }

    @Test fun `DAS solo da chi ce l'ha, invii solo da chi li accetta`() {
        val pool = RpcPool(all, 3L)
        assertEquals(listOf("helius"), pool.lanes("getAssetBatch", now).map { it.name })
        assertTrue(pool.lanes("sendTransaction", now).none { it.name == "drpc" })
    }

    // ---- la tabella di verita' ----------------------------------------------------

    private fun err(code: Int, msg: String) = JSONObject().put("code", code).put("message", msg)

    @Test fun `classify, tutte le righe`() {
        assertEquals(RpcPool.Outcome.TRANSPORT, RpcPool.classify(null, null))
        assertEquals(RpcPool.Outcome.OK, RpcPool.classify(200, null))
        assertEquals(RpcPool.Outcome.RATE_LIMITED, RpcPool.classify(429, null))
        assertEquals(RpcPool.Outcome.EXHAUSTED, RpcPool.classify(402, null))
        assertEquals(RpcPool.Outcome.FORBIDDEN, RpcPool.classify(403, null))
        assertEquals(RpcPool.Outcome.FORBIDDEN, RpcPool.classify(401, null))
        assertEquals(RpcPool.Outcome.SERVER, RpcPool.classify(503, null))
        assertEquals(RpcPool.Outcome.RATE_LIMITED, RpcPool.classify(200, err(429, "Too many requests")))
        assertEquals(RpcPool.Outcome.RATE_LIMITED, RpcPool.classify(200, err(-32429, "rate limited")))
        assertEquals(RpcPool.Outcome.UNSUPPORTED, RpcPool.classify(200, err(-32099, "Method not available")))
        assertEquals(RpcPool.Outcome.UNSUPPORTED, RpcPool.classify(200, err(-32601, "Method not found")))
        assertEquals(RpcPool.Outcome.EXHAUSTED, RpcPool.classify(200, err(-32000, "Monthly credits exceeded, upgrade your plan")))
        assertEquals(RpcPool.Outcome.REFUSED, RpcPool.classify(200, err(-32002, "Transaction simulation failed: Blockhash not found")))
        assertEquals(RpcPool.Outcome.REFUSED, RpcPool.classify(200, err(-32602, "Invalid param: WrongSize")))
        assertEquals(RpcPool.Outcome.REFUSED, RpcPool.classify(200, err(-32002, "Transaction simulation failed: Error processing Instruction 0: custom program error: 0x1")))
    }

    // ---- imparare ------------------------------------------------------------------

    @Test fun `un metodo rifiutato si toglie a quel fornitore e non agli altri, e resta scritto`() {
        val store = MemStore()
        val pool = RpcPool(all, 3L, store)
        pool.onOutcome("alchemy", "getProgramAccounts", RpcPool.Outcome.UNSUPPORTED, now)
        assertTrue(pool.lanes("getProgramAccounts", now).none { it.name == "alchemy" })
        assertTrue(pool.lanes("getProgramAccounts", now).any { it.name == "chainstack" })
        assertTrue(pool.lanes("getBalance", now).any { it.name == "alchemy" })
        // Un altro avvio, stesse preferenze: lo sa ancora.
        assertTrue(RpcPool(all, 3L, store).lanes("getProgramAccounts", now).none { it.name == "alchemy" })
    }

    @Test fun `un 402 esaurisce fino al primo del mese`() {
        val store = MemStore()
        val pool = RpcPool(all, 3L, store)
        pool.onOutcome("helius", "getBalance", RpcPool.Outcome.EXHAUSTED, now)
        assertEquals(RpcPool.State.EXHAUSTED, pool.state("helius", now))
        assertEquals(RpcPool.State.EXHAUSTED, pool.state("helius", now + 5 * RpcPool.DAY_MS))
        assertEquals(RpcPool.State.OK, pool.state("helius", RpcPool.monthEnd(now) + 1))
        assertTrue(pool.lanes("getBalance", now).none { it.name == "helius" })
        // Anche dopo un riavvio.
        assertEquals(RpcPool.State.EXHAUSTED, RpcPool(all, 3L, store).state("helius", now))
    }

    @Test fun `un 429 raffredda per minuti, sei di fila sono un mese finito`() {
        val pool = RpcPool(all, 3L)
        pool.onOutcome("chainstack", "getBalance", RpcPool.Outcome.RATE_LIMITED, now)
        assertEquals(RpcPool.State.COLD, pool.state("chainstack", now + 60_000))
        assertEquals(RpcPool.State.OK, pool.state("chainstack", now + RpcPool.COLD_RATE_MS + 1))
        pool.onOutcome("chainstack", "getBalance", RpcPool.Outcome.OK, now + 1)
        assertEquals(RpcPool.State.OK, pool.state("chainstack", now + 2))
        var t = now
        repeat(RpcPool.RATE_STREAK_EXHAUSTED) { t += 1; pool.onOutcome("chainstack", "getBalance", RpcPool.Outcome.RATE_LIMITED, t) }
        assertEquals(RpcPool.State.EXHAUSTED, pool.state("chainstack", t + 3 * RpcPool.DAY_MS))
    }

    @Test fun `il trasporto caduto raffredda mezzo minuto e poi si riprova`() {
        val pool = RpcPool(all, 3L)
        pool.onOutcome("alchemy", "getBalance", RpcPool.Outcome.TRANSPORT, now)
        assertTrue(pool.lanes("getBalance", now + 1_000).none { it.name == "alchemy" })
        assertTrue(pool.lanes("getBalance", now + RpcPool.COLD_TRANSPORT_MS + 1).any { it.name == "alchemy" })
    }

    @Test fun `un rifiuto deterministico non tocca la salute`() {
        val pool = RpcPool(all, 3L)
        repeat(20) { pool.onOutcome("helius", "simulateTransaction", RpcPool.Outcome.REFUSED, now) }
        assertEquals(RpcPool.State.OK, pool.state("helius", now))
        assertEquals(20L, pool.report(now).first { it.name == "helius" }.calls)
        assertEquals(0L, pool.report(now).first { it.name == "helius" }.failures)
    }

    @Test fun `la spiaggia resta quando tutti i fornitori con chiave sono freddi`() {
        val pool = RpcPool(all, 3L)
        for (p in all.filter { !it.lastResort }) pool.onOutcome(p.name, "getBalance", RpcPool.Outcome.SERVER, now)
        assertEquals(listOf("mainnet"), pool.lanes("getBalance", now).map { it.name })
    }

    // ---- il tetto ---------------------------------------------------------------------

    @Test fun `il tetto conta le chiavi condivise, non la spiaggia, e si azzera a mezzanotte`() {
        val store = MemStore()
        val pool = RpcPool(all, 3L, store)
        pool.cap = 3
        repeat(5) { pool.countCall(mainnet, now) }
        assertFalse(pool.overBudget(now))
        repeat(3) { pool.countCall(helius, now) }
        assertTrue(pool.overBudget(now))
        assertEquals(3, pool.usedToday(now))
        assertFalse(pool.overBudget(now + RpcPool.DAY_MS))
        assertEquals(0, pool.usedToday(now + RpcPool.DAY_MS))
        // Il tetto resta scritto, e zero vuol dire nessun tetto.
        assertEquals(3, RpcPool(all, 3L, store).cap)
        pool.cap = 0
        assertFalse(pool.overBudget(now))
    }

    @Test fun `la fine del mese e' il primo del prossimo, UTC`() {
        // 22 settembre 2026, 07:00 UTC → 1 ottobre 2026, 00:00 UTC
        val sep22 = 1_790_060_400_000L
        val end = RpcPool.monthEnd(sep22)
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = end }
        assertEquals(1, c.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(java.util.Calendar.OCTOBER, c.get(java.util.Calendar.MONTH))
        assertEquals(0, c.get(java.util.Calendar.HOUR_OF_DAY))
    }
}
