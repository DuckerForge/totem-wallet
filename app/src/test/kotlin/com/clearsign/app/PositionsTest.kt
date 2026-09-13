package com.clearsign.app

import com.clearsign.core.BalanceDelta
import com.clearsign.core.NATIVE_SOL_MINT
import com.clearsign.core.Receipt
import com.clearsign.core.Severity
import com.clearsign.core.TrustLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The exit arithmetic, and the reading of a buy out of a receipt.
 *
 * These are the two places where a mistake spends real money in the dark: a
 * wrong entry price sells a winner as if it were a loser, and a misread receipt
 * creates a position that does not match what is actually in the wallet.
 */
class PositionsTest {

    private val env = "Env11111111111111111111111111111111111111111"
    private val bonk = "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263"

    private fun pos(units: Double = 1_000.0, cost: Long = 10_000_000L, tp: Int = 30, sl: Int = 15) =
        Positions.Position(
            mint = bonk, symbol = "BONK", decimals = 5, units = units, costLamports = cost,
            openedAt = 0L, takeProfitPct = tp, stopLossPct = sl,
        )

    @Test fun entryPriceIsCostOverUnits() {
        assertEquals(10_000.0, pos().entryLamports!!, 0.0001)
    }

    @Test fun aboveTheTargetItSells() {
        val v = pos().verdict(13_500.0)
        assertEquals(Positions.Exit.Why.TARGET, v?.why)
        assertEquals(35.0, v!!.movePct, 0.001)
    }

    @Test fun belowTheStopItSells() {
        val v = pos().verdict(8_000.0)
        assertEquals(Positions.Exit.Why.STOP, v?.why)
        assertEquals(-20.0, v!!.movePct, 0.001)
    }

    @Test fun inBetweenItHolds() {
        assertNull(pos().verdict(11_000.0))
        assertNull(pos().verdict(9_000.0))
    }

    /** Exactly on the line counts: a target reached is a target. */
    @Test fun theBoundariesCount() {
        assertEquals(Positions.Exit.Why.TARGET, pos().verdict(13_000.0)?.why)
        assertEquals(Positions.Exit.Why.STOP, pos().verdict(8_500.0)?.why)
    }

    /**
     * The one that matters most: no price means hold. A tick that could not
     * reach Jupiter must never read that as a coin gone to zero.
     */
    @Test fun anUnknownPriceNeverSells() {
        assertNull(pos().verdict(null))
        assertNull(pos().verdict(0.0))
        assertNull(pos().verdict(-1.0))
        assertNull(pos(units = 0.0).verdict(50_000.0), "an empty position has no entry price")
    }

    @Test fun aStopOfZeroMeansNoStop() {
        assertNull(pos(sl = 0).verdict(1.0), "down 99.99% and still no stop, because none was set")
        assertEquals(Positions.Exit.Why.TARGET, pos(sl = 0).verdict(20_000.0)?.why)
    }

    // ---- reading a buy out of a receipt -------------------------------------

    private fun d(owner: String, mint: String, sym: String, dec: Int, raw: Long) =
        BalanceDelta(owner, mint, sym, dec, raw)

    private fun receipt(outs: List<BalanceDelta>, ins: List<BalanceDelta>, fee: Long = 5_000L) = Receipt(
        primaryRecipient = null, recipientLabel = null, recipientTrust = TrustLevel.NEW,
        outflows = outs, inflows = ins, feeLamports = fee, risks = emptyList(),
    )

    @Test fun aSwapBecomesAPosition() {
        val r = receipt(
            outs = listOf(d(env, NATIVE_SOL_MINT, "SOL", 9, -10_000_000L)),
            ins = listOf(d(env, bonk, "BONK", 5, 100_000_000L)),
        )
        val p = Positions.fromReceipt(r, env, 30, 15)!!
        assertEquals("BONK", p.symbol)
        assertEquals(1_000.0, p.units, 0.0001)
        // The fee is part of what the position has to earn back.
        assertEquals(10_005_000L, p.costLamports)
    }

    @Test fun aPlainTransferIsNotAPosition() {
        val r = receipt(
            outs = listOf(d(env, NATIVE_SOL_MINT, "SOL", 9, -10_000_000L)),
            ins = emptyList(),
        )
        assertNull(Positions.fromReceipt(r, env, 30, 15))
    }

    /** Two tokens back at once is not something this store can describe honestly. */
    @Test fun aMultiLegSwapIsLeftAlone() {
        val r = receipt(
            outs = listOf(d(env, NATIVE_SOL_MINT, "SOL", 9, -10_000_000L)),
            ins = listOf(d(env, bonk, "BONK", 5, 100_000L), d(env, "Jup1", "JUP", 6, 1_000L)),
        )
        assertNull(Positions.fromReceipt(r, env, 30, 15))
    }

    @Test fun sellingBackIsNotABuy() {
        val r = receipt(
            outs = listOf(d(env, bonk, "BONK", 5, -100_000_000L)),
            ins = listOf(d(env, NATIVE_SOL_MINT, "SOL", 9, 12_000_000L)),
        )
        assertNull(Positions.fromReceipt(r, env, 30, 15), "no SOL left the budget, so nothing was bought")
    }

    /** Somebody else's legs in the same transaction are not ours. */
    @Test fun onlyTheBudgetsOwnLegsCount() {
        val other = "Oth11111111111111111111111111111111111111111"
        val r = receipt(
            outs = listOf(d(other, NATIVE_SOL_MINT, "SOL", 9, -10_000_000L)),
            ins = listOf(d(other, bonk, "BONK", 5, 100_000_000L)),
        )
        assertNull(Positions.fromReceipt(r, env, 30, 15))
    }

    @Test fun severityIsUntouched() {
        // A guard that the receipt shape we read has not drifted underneath us.
        assertEquals(Severity.INFO, receipt(emptyList(), emptyList()).highestSeverity)
        assertTrue(Positions.fromReceipt(receipt(emptyList(), emptyList()), env, 30, 15) == null)
    }
}
