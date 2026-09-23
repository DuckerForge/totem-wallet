package com.clearsign.core

import kotlin.math.abs

/**
 * Anti-TOCTOU. Wallets simulate once, show, sign later, and on-chain state can change in
 * between, so a benign preview can settle as a drain. This re-simulates the instant before the
 * Seed Vault signs and aborts if the effects drifted from what was approved. Drift has a small
 * tolerance, a swap one slot later moves by a few lamports; never tolerated: an asset or a party appearing or vanishing, or any amount moving more than [DEFAULT_TOLERANCE].
 */
object SimulationGuard {

    /** Relative tolerance on each (owner, mint) net amount: 1%. */
    const val DEFAULT_TOLERANCE = 0.01

    /** True when [atApproval] materially differs from the [previewed] effects. */
    fun hasDrifted(
        previewed: List<BalanceDelta>,
        atApproval: List<BalanceDelta>,
        tolerance: Double = DEFAULT_TOLERANCE,
    ): Boolean {
        val a = normalize(previewed)
        val b = normalize(atApproval)
        if (a.keys != b.keys) return true
        return a.any { (k, pre) ->
            val post = b.getValue(k)
            // Sign flip (an inflow became an outflow) is always drift.
            if ((pre < 0) != (post < 0)) return@any true
            val diff = abs(pre.toDouble() - post.toDouble())
            diff > abs(pre.toDouble()) * tolerance
        }
    }

    /** Re-simulate: the confirmed deltas, or a STATE_DRIFT risk with null effects (the caller must not sign). */
    fun confirm(
        previewed: List<BalanceDelta>,
        atApproval: List<BalanceDelta>,
        tolerance: Double = DEFAULT_TOLERANCE,
    ): Result {
        return if (hasDrifted(previewed, atApproval, tolerance)) {
            Result(
                driftDetected = true,
                risk = Risk(
                    RiskFlag.STATE_DRIFT,
                    Severity.DANGER,
                    "On-chain state changed after preview — the outcome no longer matches what you saw.",
                ),
            )
        } else {
            Result(driftDetected = false, risk = null)
        }
    }

    data class Result(val driftDetected: Boolean, val risk: Risk?)

    private fun normalize(deltas: List<BalanceDelta>): Map<Pair<String, String>, Long> =
        deltas.groupBy { it.owner to it.mint }
            .mapValues { (_, v) -> v.sumOf { it.rawAmount } }
            .filterValues { it != 0L }
}
