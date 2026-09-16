package com.clearsign.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The bytes of one real UserStake account and of the stake config, read on 15 September 2026. */
class SkrStakeTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val user = hex("6635a36b098a5799ff30c77438562d45ef5b79292f61cf0f3514359d8ce0b336e59766ad0889b1bcde090999bb90ae98ef7b0c86fc14e8274db75b456db43abec39f88aff8eeacaa6bb80252d18db52b8e351589bd02fb8d1cb3265705f5ae0b00d29ba798e1c25de145a86180100000000000000000000000ed6c46400000000000000000000000000000000000000000000000000000000000000000000000000000000000000000")
    private val config = hex("ee972b030b973fb0fff7dfd1981588a142a7042269a73ded0f72a2aa6d7dc0694dadfc6aa39f0a9f23067c5a3e05fe414712a7a2eafe42be7610bcd90cbf571627758373cb8ad0d8a472bbb771f12954e2f7f42197be2cf84e1d972744d7bdbc441b4a499e7f7f2d9340420f000000000000a3020000000000aa20319acf8a0f000000000000000000b990df4300000000000000000000000000000000000000000000000000000000000000000000000000000000000000009ab9c9efa9cc1100")

    @Test fun sharesTimesPriceIsTheStake() {
        val p = SkrStake.decode(user, config)
        assertNotNull(p)
        assertEquals(java.math.BigInteger("70873360453"), p!!.shares)
        assertEquals(80_705.27, p.ui, 0.01)
        assertEquals("DPJ58trLsF9yPrBa2pk6UaRkvqW8hWUYjawe788WBuqr", p.guardian)
        // All shares times the price: what the vault holds, give or take the queue.
        assertEquals(4_981_636_156.0, p.totalStakedRaw / 1e6, 1.0)
        assertEquals(1.138725, p.sharePrice, 0.000001)
    }

    /**
     * The yield is read off the share price, because the tokenomics guess was
     * wrong: it gave 21.3% where the Seeker wallet shows 15.40% for the same
     * stake. Two readings of a number that only goes up cannot be wrong in that
     * way.
     */
    @Test fun growthOverTimeIsTheYield() {
        val day = 86_400_000L
        // A hundredth of a percent over ten days is a bit over a third of a percent a year.
        val apr = SkrStake.growthAprPct(1.0, 0L, 1.0001, 10 * day)
        assertEquals(0.365, apr!!, 0.005)
    }

    @Test fun tooShortASpanSaysNothing() {
        // Rewards land every forty-eight hours, so a day of growth is one step or
        // none, and either way it is not a rate.
        assertEquals(null, SkrStake.growthAprPct(1.0, 0L, 1.01, 86_400_000L))
    }

    @Test fun aPriceThatDidNotMoveIsNotAYield() {
        assertEquals(null, SkrStake.growthAprPct(1.0, 0L, 1.0, 10 * 86_400_000L))
    }

    @Test fun anImpossibleJumpIsRefused() {
        assertEquals(null, SkrStake.growthAprPct(1.0, 0L, 3.0, 10 * 86_400_000L))
    }

    /** The two readings taken in September, a couple of days apart. */
    @Test fun theRealSharePriceLandsNearTheOfficialNumber() {
        val apr = SkrStake.growthAprPct(1.138725049, 0L, 1.139766368, 8 * 86_400_000L)
        assertEquals(4.2, apr!!, 0.3)
    }
}
