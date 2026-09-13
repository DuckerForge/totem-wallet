package com.clearsign.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntentGuardTest {
    private val me = "DEM0ownerWa11etF0rTests0n1yNotARea1Key111jQ"
    private val bob = "8ncUqW9x4kXn2v1JZp7m3Qe6TtLbR5sYd2FhGkMuz8z"
    private val usdc = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

    private fun d(owner: String, mint: String, sym: String, dec: Int, ui: Double) =
        BalanceDelta(owner, mint, sym, dec, Math.round(ui * Math.pow(10.0, dec.toDouble())))

    private fun receipt(outs: List<BalanceDelta>, ins: List<BalanceDelta> = emptyList(), to: String? = null, risks: List<Risk> = emptyList(), fee: Long = 5_000) =
        Receipt(primaryRecipient = to, recipientLabel = null, recipientTrust = TrustLevel.NEW, outflows = outs, inflows = ins, feeLamports = fee, risks = risks)

    @Test fun transferMatches() {
        val r = receipt(listOf(d(me, usdc, "USDC", 6, -5.0)), to = bob)
        val risk = IntentGuard.check(AgentIntent("transfer", outMint = "USDC", outAmount = 5.0, to = bob, agent = "bot"), r, me)
        assertEquals(RiskFlag.AGENT_INTENT_OK, risk.flag)
        assertEquals(Severity.INFO, risk.severity)
    }

    @Test fun transferAmountLieIsBlocked() {
        val r = receipt(listOf(d(me, usdc, "USDC", 6, -50.0)), to = bob)
        val risk = IntentGuard.check(AgentIntent("transfer", outMint = "USDC", outAmount = 5.0, to = bob), r, me)
        assertEquals(RiskFlag.AGENT_INTENT_MISMATCH, risk.flag)
        assertEquals(Severity.DANGER, risk.severity)
        assertTrue(risk.detail.contains("50"), risk.detail)
    }

    @Test fun transferWrongRecipientIsBlocked() {
        val r = receipt(listOf(d(me, usdc, "USDC", 6, -5.0)), to = "AttackerXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX")
        val risk = IntentGuard.check(AgentIntent("transfer", outMint = "USDC", outAmount = 5.0, to = bob), r, me)
        assertEquals(RiskFlag.AGENT_INTENT_MISMATCH, risk.flag)
    }

    @Test fun undeclaredExtraOutflowIsBlocked() {
        val r = receipt(listOf(d(me, usdc, "USDC", 6, -5.0), d(me, NATIVE_SOL_MINT, "SOL", 9, -2.0)), to = bob)
        val risk = IntentGuard.check(AgentIntent("transfer", outMint = "USDC", outAmount = 5.0, to = bob), r, me)
        assertEquals(RiskFlag.AGENT_INTENT_MISMATCH, risk.flag)
        assertTrue(risk.detail.contains("SOL"), risk.detail)
    }

    @Test fun solTransferToleratesFeeAndRent() {
        val r = receipt(listOf(d(me, NATIVE_SOL_MINT, "SOL", 9, -0.102)), to = bob, fee = 5_000)
        val risk = IntentGuard.check(AgentIntent("transfer", outMint = "SOL", outAmount = 0.1, to = bob), r, me)
        assertEquals(RiskFlag.AGENT_INTENT_OK, risk.flag)
    }

    @Test fun swapMatchesWithinSlippage() {
        val r = receipt(listOf(d(me, NATIVE_SOL_MINT, "SOL", 9, -0.1)), ins = listOf(d(me, usdc, "USDC", 6, 9.9)))
        val risk = IntentGuard.check(AgentIntent("swap", outMint = "SOL", outAmount = 0.1, inMint = "USDC", inAmount = 10.0), r, me)
        assertEquals(RiskFlag.AGENT_INTENT_OK, risk.flag)
    }

    @Test fun swapWithNoInflowIsBlocked() {
        val r = receipt(listOf(d(me, NATIVE_SOL_MINT, "SOL", 9, -0.1)), to = bob)
        val risk = IntentGuard.check(AgentIntent("swap", outMint = "SOL", outAmount = 0.1, inMint = "USDC", inAmount = 10.0), r, me)
        assertEquals(RiskFlag.AGENT_INTENT_MISMATCH, risk.flag)
    }

    @Test fun burnMatchesAndRentBackIsFine() {
        val r = receipt(listOf(d(me, "RKSmint", "RKS", 6, -1000.0)), ins = listOf(d(me, NATIVE_SOL_MINT, "SOL", 9, 0.002)), to = me)
        val risk = IntentGuard.check(AgentIntent("burn", outMint = "RKS", outAmount = 1000.0), r, me)
        assertEquals(RiskFlag.AGENT_INTENT_OK, risk.flag)
    }

    @Test fun otherWithHiddenApprovalIsBlocked() {
        val r = receipt(emptyList(), risks = listOf(Risk(RiskFlag.UNLIMITED_APPROVAL, Severity.DANGER, "x")))
        val risk = IntentGuard.check(AgentIntent("other", agent = "bot"), r, me)
        assertEquals(RiskFlag.AGENT_INTENT_MISMATCH, risk.flag)
    }

    @Test fun italianSummaryReadsNaturally() {
        val s = IntentGuard.summary(AgentIntent("swap", outMint = "SOL", outAmount = 0.1, inMint = "USDC"), it = true)
        assertEquals("swap 0.1 SOL → USDC", s)
    }
}
