package com.clearsign.app

import com.clearsign.core.BalanceDelta
import com.clearsign.core.NATIVE_SOL_MINT
import com.clearsign.core.Receipt
import com.clearsign.core.Severity
import com.clearsign.core.TrustLevel
import com.clearsign.app.Positions.Position
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

    // ---- the book against the chain -----------------------------------------
    // The night this was written the agent had spent sixteen hours trying to sell 1214 LEVERCAT
    // belonging to a closed budget: the new key never held them, every simulation moved nothing,
    // the collar read that as lying, and the tick stopped there. The chain always wins.

    private val old = "Old11111111111111111111111111111111111111111"

    private fun book(vararg p: Position) = p.toList()
    private fun Position.on(raw: Long) = mint to raw

    @Test fun aPositionFromAClosedBudgetIsNotOurs() {
        val ghost = pos().copy(owner = old)
        val r = Positions.reconcile(book(ghost), env, mapOf(ghost.on(100_000_000L)), emptySet())
        assertEquals(listOf(ghost), r.gone, "coins on chain do not make it ours: that key is gone")
        assertTrue(r.keep.isEmpty())
        assertTrue(r.changed)
    }

    @Test fun aCoinTheWalletDoesNotHoldIsNotAPosition() {
        val p = pos().copy(owner = env)
        val r = Positions.reconcile(book(p), env, emptyMap(), emptySet())
        assertEquals(listOf(p), r.gone)
    }

    /** Parked in a Trigger order: out of the wallet, not out of the book. */
    @Test fun coinsInsideAnOrderAreKept() {
        val p = pos().copy(owner = env, triggerOrder = "OrderXXXX")
        val r = Positions.reconcile(book(p), env, emptyMap(), setOf(p.mint))
        assertTrue(r.gone.isEmpty())
        assertTrue(r.keep.single().parked)
    }

    /**
     * A cancel that Jupiter accepted is not a cancel that landed. The key stays
     * until Jupiter no longer lists the order and the coins are back.
     */
    @Test fun anOrderKeyIsDroppedOnlyOnJupitersWord() {
        val p = pos().copy(owner = env, triggerOrder = "OrderXXXX")
        // Coins back, Jupiter asked, order not there: the key goes.
        val cleared = Positions.reconcile(book(p), env, mapOf(p.on(100_000_000L)), emptySet(), liveByMint = emptyMap())
        assertNull(cleared.keep.single().triggerOrder)
        assertTrue(cleared.changed)
        // Coins back, Jupiter not asked: nothing is guessed.
        val kept = Positions.reconcile(book(p), env, mapOf(p.on(100_000_000L)), emptySet(), liveByMint = null)
        assertEquals("OrderXXXX", kept.keep.single().triggerOrder)
        // Coins back, but Jupiter still lists the order: the escrow has not caught up. Keep it.
        val still = Positions.reconcile(book(p), env, mapOf(p.on(100_000_000L)), emptySet(), liveByMint = mapOf(p.mint to "OrderXXXX"))
        assertEquals("OrderXXXX", still.keep.single().triggerOrder)
    }

    /** Parked in an order the row has no key for: take the key Jupiter has, so it can be cancelled. */
    @Test fun aParkedRowWithoutAKeyTakesJupiters() {
        val p = pos().copy(owner = env, triggerOrder = null)
        val r = Positions.reconcile(book(p), env, emptyMap(), setOf(p.mint), liveByMint = mapOf(p.mint to "OrderYYYY"))
        assertTrue(r.keep.single().parked)
        assertEquals("OrderYYYY", r.keep.single().triggerOrder)
    }

    @Test fun aRowWithNoOwnerIsAdoptedWhenTheCoinsAreThere() {
        val p = pos()
        val r = Positions.reconcile(book(p), env, mapOf(p.on(100_000_000L)), emptySet())
        assertEquals(env, r.keep.single().owner)
    }

    /**
     * Half the coin was sold somewhere else. The book follows the chain down, and
     * the cost follows the units, so the entry price stays what we actually paid.
     */
    @Test fun aSmallerBalanceShrinksThePositionAndItsCost() {
        val p = pos(units = 1_000.0, cost = 10_000_000L).copy(owner = env)
        val r = Positions.reconcile(book(p), env, mapOf(p.on(50_000_000L)), emptySet())
        val k = r.keep.single()
        assertEquals(500.0, k.units, 0.001)
        assertEquals(5_000_000L, k.costLamports)
        assertEquals(10_000.0, k.entryLamports!!, 0.001, "the price we paid per coin does not change")
    }

    /**
     * The other direction is refused on purpose. More coins against the same cost
     * reads as a lower entry price, and a lower entry price sells at a target the
     * position never reached.
     */
    @Test fun aBiggerBalanceDoesNotMoveTheEntryPrice() {
        val p = pos(units = 1_000.0, cost = 10_000_000L).copy(owner = env)
        val r = Positions.reconcile(book(p), env, mapOf(p.on(400_000_000L)), emptySet())
        val k = r.keep.single()
        assertEquals(1_000.0, k.units, 0.001)
        assertEquals(10_000.0, k.entryLamports!!, 0.001)
    }

    /** A tick that died mid-sale must not hide the position for ever. */
    @Test fun aHalfClosedRowWithItsCoinsStillThereIsReopened() {
        val p = pos().copy(owner = env, closing = true)
        val r = Positions.reconcile(book(p), env, mapOf(p.on(100_000_000L)), emptySet())
        assertTrue(!r.keep.single().closing)
        assertTrue(r.changed)
    }

    @Test fun nothingToCorrectIsNotAChange() {
        val p = pos().copy(owner = env)
        val r = Positions.reconcile(book(p), env, mapOf(p.on(100_000_000L)), emptySet())
        assertEquals(listOf(p), r.keep)
        assertTrue(!r.changed)
    }

    // ---- how often to try again ---------------------------------------------

    @Test fun theFirstThreeTriesAreAtFullSpeed() {
        assertEquals(0L, Positions.retryDelay(0))
        assertEquals(0L, Positions.retryDelay(2))
        assertEquals(0L, pos().copy(fails = 2, lastTryAt = 1_000L).readyAt)
    }

    @Test fun afterThatItBacksOffAndStops() {
        assertEquals(5 * 60_000L, Positions.retryDelay(3))
        assertEquals(10 * 60_000L, Positions.retryDelay(4))
        assertEquals(6 * 3_600_000L, Positions.retryDelay(30), "capped at six hours, not doubling for ever")
        assertEquals(1_000L + 5 * 60_000L, pos().copy(fails = 3, lastTryAt = 1_000L).readyAt)
    }

    @Test fun severityIsUntouched() {
        // A guard that the receipt shape we read has not drifted underneath us.
        assertEquals(Severity.INFO, receipt(emptyList(), emptyList()).highestSeverity)
        assertTrue(Positions.fromReceipt(receipt(emptyList(), emptyList()), env, 30, 15) == null)
    }
}
