package com.clearsign.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Quale pool si guarda, con i numeri veri del 20 settembre 2026.
 *
 * Le cifre sono quelle che GeckoTerminal dava quel giorno per EDEL e per SOL.
 * Le tengo scritte perche' la regola vecchia - la piu' profonda - non sbagliava
 * in modo rumoroso: sceglieva una pool ferma da maggio, disegnava un grafico
 * plausibile a un terzo del prezzo e apriva il link su quella. Un errore che
 * somiglia a una risposta e' quello che serve un test per non farlo tornare.
 */
class GeckoPoolTest {
    private fun pool(id: String, liq: Double, vol: Double) =
        Gecko.Pool(id = id, dex = null, liquidityUsd = liq.takeIf { it > 0 }, volume24Usd = vol.takeIf { it > 0 }, buys24 = null, sells24 = null, fdvUsd = null)

    @Test fun `la pool piu' profonda ma ferma non vince`() {
        // EDEL: la piu' profonda ha zero scambi in un giorno.
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
        // Un cinquantesimo della profondita' massima e' il pavimento: sotto,
        // il volume non e' un mercato.
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
