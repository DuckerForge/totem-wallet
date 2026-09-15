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

    private fun receipt(
        outs: List<BalanceDelta>, ins: List<BalanceDelta> = emptyList(), to: String? = null,
        risks: List<Risk> = emptyList(), fee: Long = 5_000, newAccounts: Int = 0,
    ) = Receipt(
        primaryRecipient = to, recipientLabel = null, recipientTrust = TrustLevel.NEW,
        outflows = outs, inflows = ins, feeLamports = fee, risks = risks,
        // Rent for an account this transaction opens. The simulation reports it as
        // a share going to a brand new address, which is how the guard tells rent
        // apart from a payment.
        distributions = (0 until newAccounts).map { i ->
            RecipientShare(
                "NewAcct$i", null, TrustLevel.NEW,
                d("NewAcct$i", NATIVE_SOL_MINT, "SOL", 9, 0.00204), 0.0, isNewAccount = true,
            )
        },
    )

    /**
     * Two silences, two answers. When the node ran the transaction and it
     * failed, the reason is already in the receipt and the guard passes it on.
     * When the node could not be asked, there is nothing to pass on, and the
     * guard says the claim is unverifiable. It used to say "did not answer" for
     * both, and the loop retried a swap that failed on slippage every round.
     */
    @Test fun aSimulationThatFailedKeepsItsReason() {
        val failed = Risk(RiskFlag.SIMULATION_FAILED, Severity.DANGER, "slippage tolerance exceeded")
        val r = receipt(emptyList(), risks = listOf(failed))
        val risk = IntentGuard.check(AgentIntent("swap", outMint = "SOL", outAmount = 0.01, inMint = "BONK"), r, me)
        assertEquals(RiskFlag.SIMULATION_FAILED, risk.flag)
        assertEquals(Severity.DANGER, risk.severity)
        assertEquals("slippage tolerance exceeded", risk.detail)
    }

    @Test fun aSimulationNobodyCouldRunIsUnverifiable() {
        val down = Risk(RiskFlag.SIMULATION_UNAVAILABLE, Severity.WARN, "node unreachable")
        val r = receipt(emptyList(), risks = listOf(down))
        val risk = IntentGuard.check(AgentIntent("swap", outMint = "SOL", outAmount = 0.01, inMint = "BONK"), r, me)
        assertEquals(RiskFlag.SIMULATION_UNAVAILABLE, risk.flag)
        assertEquals(Severity.DANGER, risk.severity)
        assertTrue(risk.detail.contains("did not answer"), risk.detail)
    }

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

    /**
     * The rent for accounts this transaction opens is not a lie. An agent writing
     * "swap 0.031 SOL" cannot know that buying a coin it has never held will also
     * cost two account deposits, and refusing that as dishonesty stopped the loop
     * dead on every new coin.
     */
    @Test fun solTransferToleratesFeeAndTheRentItCanSee() {
        val r = receipt(listOf(d(me, NATIVE_SOL_MINT, "SOL", 9, -0.1051)), to = bob, newAccounts = 2)
        val risk = IntentGuard.check(AgentIntent("transfer", outMint = "SOL", outAmount = 0.1, to = bob), r, me)
        assertEquals(RiskFlag.AGENT_INTENT_OK, risk.flag)
    }

    /**
     * The other half, and the reason the slack is not a constant: SOL that leaves
     * with nothing in the simulation to explain it is exactly what this guard is
     * for. The old flat allowance waved through 0.003 SOL on every transfer.
     */
    @Test fun unexplainedExtraSolIsStillALie() {
        val r = receipt(listOf(d(me, NATIVE_SOL_MINT, "SOL", 9, -0.102)), to = bob)
        val risk = IntentGuard.check(AgentIntent("transfer", outMint = "SOL", outAmount = 0.1, to = bob), r, me)
        assertEquals(RiskFlag.AGENT_INTENT_MISMATCH, risk.flag)
    }

    /** The case that broke the loop, to the lamport it actually cost. */
    @Test fun buyingABrandNewCoinIsNotALie() {
        val r = receipt(
            outs = listOf(d(me, NATIVE_SOL_MINT, "SOL", 9, -0.035953)),
            ins = listOf(d(me, "BUMmint", "BUM", 6, 3340.42611)),
            newAccounts = 2, fee = 650_000,
        )
        val risk = IntentGuard.check(
            AgentIntent("swap", outMint = "SOL", outAmount = 0.031225, inMint = "BUM", inAmount = 3340.42611),
            r, me,
        )
        assertEquals(RiskFlag.AGENT_INTENT_OK, risk.flag, risk.detail)
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
