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
        // 10% inflation on 10.595 billion, spread over 4.98 billion staked: about 21% a year.
        assertEquals(21.3, p.aprPct(10_595_157_477_710_037L, now = 1_789_500_000_000L)!!, 0.2)
    }
}
