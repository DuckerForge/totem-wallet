package com.clearsign.core

/**
 * When to sell, five ways, so the same position can be judged by all of them at
 * once.
 *
 * The point is not to find a magic rule. It is that a target and a stop are two
 * numbers somebody picked, and nobody who picks them knows whether they are the
 * right ones for the coins this scan actually finds. Running every rule over the
 * same positions, at the same moments, answers that with the person's own trades
 * instead of with a blog post — and because the entry is identical across rules,
 * the only thing being compared is the exit.
 *
 * Pure on purpose: price in, verdict out, no clock of its own and no network. The
 * caller supplies "now", which is what makes this testable over a written series.
 */
sealed class ExitRule(val id: String) {

    /** Sell at [takePct] above entry, or [stopPct] below it. Zero disables that side. */
    class Fixed(id: String, val takePct: Int, val stopPct: Int) : ExitRule(id)

    /**
     * No target: ride it, and sell when it gives back [dropPct] from the highest
     * price it reached. The rule that wins when a coin keeps going and a fixed
     * target would have sold at the first +30%.
     */
    class Trailing(id: String, val dropPct: Int) : ExitRule(id)

    /** Out after [hours], whatever it is doing. The rule that refuses to hold bags. */
    class Timed(id: String, val hours: Int) : ExitRule(id)

    companion object {
        const val YOURS = "yours"
        const val QUICK = "quick"
        const val PATIENT = "patient"
        const val TRAILING = "trailing"
        const val TIMED = "timed"

        /**
         * The five, with [yourTake] and [yourStop] being whatever the person has
         * set right now — so "yours" is always the rule actually running, and the
         * comparison stays honest when they change it.
         */
        fun all(yourTake: Int, yourStop: Int): List<ExitRule> = listOf(
            Fixed(YOURS, yourTake, yourStop),
            Fixed(QUICK, 15, 10),
            Fixed(PATIENT, 50, 20),
            Trailing(TRAILING, 15),
            Timed(TIMED, 6),
        )
    }
}

/** What one rule is watching: where it got in, and the best it has seen since. */
data class PaperLeg(
    val rule: String,
    val entryLamports: Double,
    val peakLamports: Double,
    val openedAt: Long,
    val closedAt: Long = 0L,
    val exitLamports: Double = 0.0,
    val why: String = "",
) {
    val open: Boolean get() = closedAt == 0L
}

/**
 * Advance one leg to [nowLamports] at [now]. Returns the leg, closed if the rule
 * says so.
 *
 * An unknown price never closes anything: the same rule the live loop follows,
 * because a missed quote is not a price of zero.
 */
fun ExitRule.step(leg: PaperLeg, nowLamports: Double?, now: Long): PaperLeg {
    if (!leg.open) return leg
    if (nowLamports == null || nowLamports <= 0 || leg.entryLamports <= 0) return leg
    val peak = maxOf(leg.peakLamports, nowLamports)
    val movePct = (nowLamports - leg.entryLamports) / leg.entryLamports * 100.0
    fun close(why: String) = leg.copy(peakLamports = peak, closedAt = now, exitLamports = nowLamports, why = why)

    return when (this) {
        is ExitRule.Fixed -> when {
            takePct > 0 && movePct >= takePct -> close("target")
            stopPct > 0 && movePct <= -stopPct -> close("stop")
            else -> leg.copy(peakLamports = peak)
        }
        is ExitRule.Trailing -> {
            val fromPeak = if (peak > 0) (nowLamports - peak) / peak * 100.0 else 0.0
            // Only after it has been in profit at all: otherwise this is just a
            // stop-loss wearing a different hat, and it would close every position
            // that dipped in its first minute.
            if (peak > leg.entryLamports && fromPeak <= -dropPct) close("trailing") else leg.copy(peakLamports = peak)
        }
        is ExitRule.Timed ->
            if (now - leg.openedAt >= hours * 3_600_000L) close("time") else leg.copy(peakLamports = peak)
    }
}

/**
 * What a closed leg actually made, in lamports, **after the costs a real trade
 * would have paid**.
 *
 * Without this the whole exercise is a story. A simulated round trip pays the
 * network twice and, on a coin never held before, the rent to open its account —
 * which on a small slice is most of what the trade has to earn back before it is
 * even. [size] is what the trade would have put in.
 */
fun PaperLeg.netLamports(size: Long, feeLamports: Long = 5_000L, rentLamports: Long = 2_040_000L, impactPct: Double = 0.0): Long {
    if (open || entryLamports <= 0) return 0L
    val gross = size * (exitLamports / entryLamports)
    val slip = gross * (impactPct / 100.0)
    return (gross - slip - size - 2 * feeLamports - rentLamports).toLong()
}
