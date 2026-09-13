package com.clearsign.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenSafetyTest {
    private val bonk = TokenFacts(
        verified = true, organic = "high", canMint = false, canFreeze = false,
        topHoldersPct = 30.1, devPct = 0.0, devMints = 10, holders = 1_016_793,
        liquidityUsd = 1_028_773.0, sellable = true,
    )

    @Test
    fun `a real, liquid, verified coin grades good`() {
        val s = assessToken(bonk)
        assertEquals(SafetyBand.GOOD, s.band)
        assertTrue(s.flags.isEmpty(), "unexpected flags: ${s.flags}")
    }

    @Test
    fun `no route back out floors everything else`() {
        val s = assessToken(bonk.copy(sellable = false))
        assertEquals(SafetyBand.BAD, s.band)
        assertTrue(s.score <= 6)
        assertEquals(SafetyFlag.NO_WAY_OUT, s.flags.first())
    }

    @Test
    fun `a live freeze authority is a ceiling, not a penalty`() {
        // Every healthy signal at once must not lift a token that can be frozen.
        val s = assessToken(bonk.copy(canFreeze = true, verified = false))
        assertTrue(s.score <= 38, "score was ${s.score}")
        assertTrue(SafetyFlag.CAN_FREEZE in s.flags)
    }

    @Test
    fun `USDC is not a scam for being freezable`() {
        // Real values from Jupiter: mint AND freeze authority live, because Circle
        // stands behind it. Flagging the most used coin on the network red would
        // only teach people to ignore the warnings that matter.
        val usdc = TokenFacts(
            verified = true, organic = "high", canMint = true, canFreeze = true,
            topHoldersPct = 25.0, devMints = 1, holders = 5_248_202, liquidityUsd = 410_750_782.0, sellable = true,
        )
        val s = assessToken(usdc)
        assertEquals(SafetyBand.GOOD, s.band)
        assertEquals(listOf(SafetyFlag.ISSUER_CONTROLLED), s.flags)
        assertTrue(SafetyFlag.CAN_FREEZE !in s.flags)
    }

    @Test
    fun `unknown is cautious, never fatal`() {
        // Nothing known at all: a brand-new honest coin looks exactly like this.
        val s = assessToken(TokenFacts())
        assertEquals(SafetyBand.MID, s.band)
        assertTrue(SafetyFlag.UNVERIFIED in s.flags)
    }

    @Test
    fun `the worst vector comes first in the list`() {
        val s = assessToken(bonk.copy(canFreeze = true, canMint = true, sellable = false, verified = false))
        assertEquals(listOf(SafetyFlag.NO_WAY_OUT, SafetyFlag.CAN_FREEZE, SafetyFlag.CAN_MINT, SafetyFlag.UNVERIFIED), s.flags)
    }

    @Test
    fun `SKR keeps a mint authority and still reads as an issued coin`() {
        val skr = TokenFacts(
            verified = true, organic = "medium", canMint = true, canFreeze = false,
            topHoldersPct = 59.2, devMints = 1, holders = 46_004, liquidityUsd = 694_349.0, sellable = true,
        )
        assertEquals(listOf(SafetyFlag.ISSUER_CONTROLLED), assessToken(skr).flags)
    }

    @Test
    fun `a thin, concentrated launch lands in the middle or below`() {
        val s = assessToken(TokenFacts(verified = false, topHoldersPct = 88.0, holders = 40, liquidityUsd = 4_000.0, devMints = 30))
        assertTrue(s.score <= 40, "score was ${s.score}")
        assertTrue(SafetyFlag.WHALE in s.flags && SafetyFlag.THIN in s.flags)
    }
}
