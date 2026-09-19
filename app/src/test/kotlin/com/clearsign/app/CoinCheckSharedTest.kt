package com.clearsign.app

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La regola che rende condivisibile un verdetto pagato da qualcun altro.
 *
 * Un no vale da chiunque, un sì vale da due. Non è simmetria mancata: è che le
 * due bugie possibili non costano uguale. Vedi [CoinCheck.Shared].
 */
class CoinCheckSharedTest {
    private val now = 1_700_000_000_000L

    private fun row(vararg entries: Triple<String, Int, Long>): JSONObject {
        val v = JSONObject()
        entries.forEach { (id, s, at) -> v.put(id, JSONObject().put("s", s).put("at", at).put("w", "rug")) }
        return JSONObject().put("at", now).put("v", v)
    }

    @Test fun nobodyKnowsYet() {
        assertEquals(CoinCheck.Shared.Say.Ask, CoinCheck.Shared.read(null, now))
        assertEquals(CoinCheck.Shared.Say.Ask, CoinCheck.Shared.read(JSONObject(), now))
    }

    @Test fun oneStopIsEnough() {
        val say = CoinCheck.Shared.read(row(Triple("a", 1, now - 60_000L)), now)
        assertTrue(say is CoinCheck.Shared.Say.Stop && say.reason == "rug", say.toString())
    }

    @Test fun oneCleanIsNotEnough() {
        // Il punto di tutto: una sola installazione non può spegnere l'ultima
        // rete a tutti gli altri.
        assertEquals(CoinCheck.Shared.Say.Ask, CoinCheck.Shared.read(row(Triple("a", 0, now - 60_000L)), now))
    }

    @Test fun twoCleansAreEnough() {
        assertEquals(
            CoinCheck.Shared.Say.Clean,
            CoinCheck.Shared.read(row(Triple("a", 0, now - 60_000L), Triple("b", 0, now - 120_000L)), now),
        )
    }

    @Test fun aStopBeatsAnyNumberOfCleans() {
        val say = CoinCheck.Shared.read(
            row(Triple("a", 0, now - 60_000L), Triple("b", 0, now - 60_000L), Triple("c", 1, now - 60_000L)),
            now,
        )
        assertTrue(say is CoinCheck.Shared.Say.Stop, say.toString())
    }

    @Test fun aCleanGoesStaleInSixHours() {
        val old = now - CoinCheck.Shared.CLEAN_TTL_MS - 1
        assertEquals(CoinCheck.Shared.Say.Ask, CoinCheck.Shared.read(row(Triple("a", 0, old), Triple("b", 0, old)), now))
    }

    @Test fun aStopLastsAWeekAndThenIsAskedAgain() {
        val week = now - CoinCheck.Shared.STOP_TTL_MS + 3600_000L
        assertTrue(CoinCheck.Shared.read(row(Triple("a", 1, week)), now) is CoinCheck.Shared.Say.Stop)
        val older = now - CoinCheck.Shared.STOP_TTL_MS - 1
        assertEquals(CoinCheck.Shared.Say.Ask, CoinCheck.Shared.read(row(Triple("a", 1, older)), now))
    }

    @Test fun rubbishIsNotAVerdict() {
        // Una riga rotta, un orario nel futuro, un campo che non c'è: niente di
        // tutto questo è un verdetto, e niente di tutto questo esplode.
        val v = JSONObject().put("a", "non un oggetto").put("b", JSONObject().put("s", 0).put("at", now + 86_400_000L))
        assertEquals(CoinCheck.Shared.Say.Ask, CoinCheck.Shared.read(JSONObject().put("v", v), now))
    }
}
