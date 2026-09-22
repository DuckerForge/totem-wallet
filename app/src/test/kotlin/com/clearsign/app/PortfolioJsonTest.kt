package com.clearsign.app

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** La cache del portafoglio va e torna con il dettaglio, e legge anche i file di prima, senza. */
class PortfolioJsonTest {
    private fun roundTrip(p: DefiPosition): DefiPosition =
        assertNotNull(PortfolioJson.decodePosition(JSONObject(PortfolioJson.encodePosition(p).toString())))

    @Test fun `lo stake nativo`() {
        val p = DefiPosition(DefiPosition.Kind.STAKE, "SOL", "Seeker", "SOL", 1.5, 240.0, image = "https://x/sol.png", state = "active", aprPct = 7.1,
            detail = DefiPosition.Detail.Stake("StakeAcc111", "SKRuTecmFDZHjs2DxRTJNEK7m7hunKGTWJiaZ3tMVVA", 800L, Long.MAX_VALUE, 812L))
        assertEquals(p, roundTrip(p))
    }

    @Test fun `i Guardiani e Jupiter Lend`() {
        val g = DefiPosition(DefiPosition.Kind.STAKE, "SKR", "Seeker Guardians", "SKR", 39_962.0, 716.0, aprPct = null,
            detail = DefiPosition.Detail.Guardians("UserStake111", "Guardian111", "123456789012345678901234", 1.0203, 5_010_000_000.0))
        assertEquals(g, roundTrip(g))
        val l = DefiPosition(DefiPosition.Kind.LEND, "USDC", "Jupiter Lend", "USDC", 100.0, 92.0, aprPct = 5.2, detail = DefiPosition.Detail.Lend("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"))
        assertEquals(l, roundTrip(l))
    }

    @Test fun `una cache di prima, senza dettaglio, si legge lo stesso`() {
        val old = JSONObject("""{"k":"STAKE","l":"SOL","sub":"Seeker","s":"SOL","ui":1.5,"f":240.0,"i":null,"st":"active","apr":7.1}""")
        val p = assertNotNull(PortfolioJson.decodePosition(old))
        assertNull(p.detail)
        assertEquals(1.5, p.ui)
        assertNull(PortfolioJson.decodePosition(JSONObject("""{"k":"BOH"}""")))
    }

    @Test fun `da quando, in epoche e giorni`() {
        val (epochs, days) = Portfolio.stakeSince(812L, 800L)
        assertEquals(12L, epochs)
        // Dodici epoche da 432.000 slot a 260 ms: circa quindici giorni e mezzo.
        assertEquals(15.6, days, 0.1)
        assertEquals(0L, Portfolio.stakeSince(800L, 812L).first)
    }
}
