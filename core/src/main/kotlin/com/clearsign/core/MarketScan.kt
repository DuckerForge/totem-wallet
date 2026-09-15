package com.clearsign.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Which coin is worth looking at, out of everything trading right now.
 *
 * [TokenSafety] answers "is this a trap". This answers the harder question the
 * agent has to answer before it can propose anything: out of thousands of
 * coins, which handful is even worth a quote. It is ported from the pool
 * builder in the MEGAGEN app (same author, same phone), which was tuned against
 * real outcomes over months, and it runs on the fields Jupiter's token registry
 * already returns, so a scan costs one network call and no API key.
 *
 * Two halves, and the order matters:
 *
 *  * [passesGate] is the veto. It is made of hard facts about how a coin can
 *    hurt you: a live freeze authority, a market cap with no liquidity under it,
 *    supply sitting in three wallets. A veto is never a low score, because a
 *    high score somewhere else must not be able to buy it back.
 *  * [runnerScore] is the ranking, for what survived. Eight weighted components
 *    for demand and shape, then multipliers that can only ever push a score
 *    *down*. That asymmetry is deliberate: the multipliers are quality
 *    controls, not preferences, and none of them can turn a bad coin good.
 *
 * The house rule throughout, kept from the original: **missing data is
 * neutral, never bad**. Every check is presence-guarded, because an honest coin
 * launched an hour ago looks exactly like a coin with nothing to show.
 */

/** One time window of trading, as Jupiter reports it. Everything optional. */
data class ScanWindow(
    val priceChange: Double? = null,
    val liquidityChange: Double? = null,
    val volume: Double? = null,
    val buyVolume: Double? = null,
    val sellVolume: Double? = null,
    val buyOrganicVolume: Double? = null,
    val sellOrganicVolume: Double? = null,
    val numBuys: Int? = null,
    val numTraders: Int? = null,
    val numOrganicBuyers: Int? = null,
    val numNetBuyers: Int? = null,
    val holderChange: Double? = null,
)

/** A coin as it arrives from the registry, before anybody has judged it. */
data class Candidate(
    val mint: String,
    val symbol: String,
    val name: String = "",
    val decimals: Int = 6,
    val usd: Double? = null,
    val liquidity: Double = 0.0,
    val mcap: Double? = null,
    val fdv: Double? = null,
    val holders: Int? = null,
    val organicScore: Double? = null,
    val verified: Boolean = false,
    val canMint: Boolean = false,
    val canFreeze: Boolean = false,
    /** Token-2022, which is the only hint that the mint may carry extensions worth reading. */
    val token2022: Boolean = false,
    val topHoldersPct: Double? = null,
    val devMints: Int = 0,
    /** Minutes since the first pool opened, or null when we cannot tell. */
    val ageMinutes: Double? = null,
    val s5m: ScanWindow? = null,
    val s1h: ScanWindow? = null,
    val s6h: ScanWindow? = null,
    val s24h: ScanWindow? = null,
)

/**
 * How much risk the scan is allowed to hand back.
 *
 * Two, not four. The numbers are MEGAGEN's, which were set against real trades:
 * [CAREFUL] is its "spicy" lane and [BOLD] its "degen" lane. What changes
 * between them is the size of coin the scan will look at at all, and how much
 * concentration and thinness it will tolerate below that.
 */
data class ScanGate(
    val name: String,
    val liquidityMinUsd: Double,
    val organicScoreMin: Double,
    val mcapMaxUsd: Double,
    val topHoldersMaxPct: Double,
    val holderCountMin: Int,
    /** Below this share of buys in the window the demand score is held down, not vetoed. */
    val numBuysFloor: Int,
    /** Liquidity under this fraction of market cap is a market cap you cannot sell into. */
    val liqMcFloorPct: Double,
    /** Whether a thin liquidity-to-mcap ratio is a veto here, or only a heavy penalty. */
    val thinIsVeto: Boolean,
    /** [rampStart, peakStart, peakEnd, zeroAt] in minutes: which age this lane wants. */
    val ageBand: List<Double>,
    /**
     * Above this one-hour move, the entry is refused.
     *
     * The score rewards momentum, so left alone it picks whatever went vertical in
     * the last hour — which, with a target at +30% and a stop at −15%, is a coin
     * flip taken at the top of a candle. A veto rather than a penalty because a
     * veto can be read out loud: "it has already gone up too much this hour".
     */
    val hourlySpikeMaxPct: Double = 35.0,
    val weights: ScanWeights,
    val concStart: Double = 30.0,
    val concFloor: Double = 0.5,
    val authMult: Double = 0.6,
    val structFloor: Double = 0.5,
) {
    companion object {
        /** Vetted small caps. Real holders, real depth, nothing minted this morning. */
        val CAREFUL = ScanGate(
            name = "careful",
            liquidityMinUsd = 30_000.0, organicScoreMin = 10.0, mcapMaxUsd = 50_000_000.0,
            topHoldersMaxPct = 30.0, holderCountMin = 150, numBuysFloor = 40,
            liqMcFloorPct = 0.001, thinIsVeto = true,
            ageBand = listOf(10.0, 360.0, 20_160.0, 43_200.0),
            hourlySpikeMaxPct = 35.0,
            weights = ScanWeights(0.20, 0.18, 0.12, 0.12, 0.12, 0.06, 0.10, 0.10),
        )

        /** Fresh trending micro caps. Wilder on purpose, and never past the vetoes. */
        val BOLD = ScanGate(
            name = "bold",
            liquidityMinUsd = 15_000.0, organicScoreMin = 5.0, mcapMaxUsd = 10_000_000.0,
            topHoldersMaxPct = 45.0, holderCountMin = 75, numBuysFloor = 25,
            liqMcFloorPct = 0.001, thinIsVeto = false,
            ageBand = listOf(10.0, 60.0, 1_440.0, 10_080.0),
            // The wild lane is allowed to chase harder, not to chase anything.
            hourlySpikeMaxPct = 70.0,
            weights = ScanWeights(0.20, 0.12, 0.18, 0.12, 0.12, 0.12, 0.08, 0.06),
        )

        /** Liquidity below 2% of market cap: most of that cap is paper. */
        const val MIN_LIQ_MCAP_RATIO = 0.02
    }
}

/** The eight components, summing to one. Names match the original. */
data class ScanWeights(
    /** Share of traders who were distinct organic buyers: demand with many faces. */
    val breadth: Double,
    /** Share of buy volume that was organic rather than bot: demand that is real. */
    val orgShare: Double,
    /** Decay-weighted price move across the windows. */
    val mom: Double,
    /** Volume over liquidity: turnover in the healthy band, not the wash band. */
    val vl: Double,
    /** Holder growth. */
    val hGrow: Double,
    /** Age, scored against the lane's own window. */
    val age: Double,
    /** Buy volume over total volume. */
    val pressure: Double,
    /** Jupiter's own organic score. */
    val org: Double,
)

/** A coin that survived, with why it ranked where it did. */
data class Scored(val c: Candidate, val score: Double, val notes: List<String>)

private fun clamp01(v: Double) = max(0.0, min(1.0, v))

/**
 * The veto. Returns null when the coin may be looked at, or the reason it may
 * not, in words the agent can repeat to a person.
 *
 * Returning the reason rather than a boolean is the point: "nothing found" is a
 * useless answer, and "eleven coins were thrown out, nine of them for having a
 * live freeze authority" is a true one.
 */
fun passesGate(c: Candidate, gate: ScanGate): String? {
    // A live freeze authority means your balance can be locked where it sits, and
    // a live mint authority means the supply can be doubled behind you. These are
    // vetoes at every level, including the wild one. Only a confirmed-live
    // authority vetoes; an audit we never got stays neutral.
    if (c.canFreeze) return "the creator can still freeze balances"
    if (c.canMint) return "the creator can still mint more supply"
    if (c.liquidity < gate.liquidityMinUsd) return "liquidity under $" + gate.liquidityMinUsd.toLong()
    if (gate.organicScoreMin > 0 && c.organicScore != null && c.organicScore < gate.organicScoreMin) {
        return "almost no real trading activity"
    }
    // Market cap falls back to fully diluted value: a coin with no mcap reported
    // would otherwise read as zero and sail under every ceiling below.
    val mc = c.mcap ?: c.fdv ?: 0.0
    if (mc > gate.mcapMaxUsd) return "too big for this scan"
    if (gate.liqMcFloorPct > 0 && mc > 0 && c.liquidity > 0 && c.liquidity < gate.liqMcFloorPct * mc) {
        return "a market cap with almost no liquidity under it"
    }
    if (gate.thinIsVeto && mc > 0 && c.liquidity > 0 && c.liquidity / mc < ScanGate.MIN_LIQ_MCAP_RATIO) {
        return "too little liquidity for its size"
    }
    // The pool being pulled out from under it.
    //
    // Added after a real one got through: DRANK, up 288% on the day and down 64%
    // over six hours with its liquidity down 44% in the same window. Nothing
    // about the coin was fatal (no mint authority, no freeze authority, 413
    // holders, supply not concentrated) so every check here passed it. What was
    // happening was visible only in the shape: the price falling is people
    // selling, and the liquidity falling with it is the other side of the book
    // walking away.
    //
    // Set at 40% because the most-traded list has a few honest coins at 35 and a
    // veto that fires on them is a veto nobody keeps.
    c.s6h?.liquidityChange?.let { if (it <= -40.0) return "liquidity drained " + (-it).toInt() + "% in six hours" }
    // Already vertical. The score's own momentum term is what puts this coin at
    // the top of the list, and buying the top of an hourly candle with a stop
    // fifteen percent below it is how a scan that looks clever loses money.
    c.s1h?.priceChange?.let {
        if (gate.hourlySpikeMaxPct > 0 && it > gate.hourlySpikeMaxPct) {
            return "already up " + it.toInt() + "% in the last hour"
        }
    }
    // A day deep in the red with a green hour is not a turn, it is the people who
    // are still holding finding a bid. Both windows have to be present to say it.
    val day = c.s24h?.priceChange
    val hour = c.s1h?.priceChange
    if (day != null && hour != null && day < -35.0 && hour > 0.0) {
        return "down " + (-day).toInt() + "% on the day, this is a bounce not a turn"
    }
    c.topHoldersPct?.let { if (gate.topHoldersMaxPct > 0 && it > gate.topHoldersMaxPct) return "top holders own " + it.toInt() + "%" }
    c.holders?.let { if (gate.holderCountMin > 0 && it < gate.holderCountMin) return "only " + it + " holders" }
    return null
}

/** Decay-weighted momentum across the windows present, or null when none are. */
internal fun momentumBlend(c: Candidate): Double? {
    val windows = listOf(c.s1h?.priceChange to 0.5, c.s6h?.priceChange to 0.3, c.s24h?.priceChange to 0.2)
    var sum = 0.0
    var wsum = 0.0
    for ((pc, w) in windows) if (pc != null) { sum += pc * w; wsum += w }
    return if (wsum > 0) sum / wsum else null
}

/**
 * Age against the lane's own window. Unknown age is neutral: a coin whose first
 * pool we cannot date is not thereby suspicious.
 */
internal fun ageScore(minutes: Double?, gate: ScanGate): Double {
    if (minutes == null) return 0.5
    val (r, p0, p1, z) = gate.ageBand.let { listOf(it[0], it[1], it[2], it[3]) }
    return when {
        minutes <= r -> 0.4
        minutes < p0 -> 0.4 + 0.6 * ((minutes - r) / (p0 - r))
        minutes <= p1 -> 1.0
        minutes < z -> clamp01(1 - (minutes - p1) / (z - p1))
        else -> 0.15
    }
}

/**
 * The shape of the chart, from the windows we already have.
 *
 * This exists because of a specific failure: a coin can look excellent on every
 * number and be bleeding right now, and buying it reads to a person as the
 * agent having ignored the chart. Neutral when there is too little to say.
 */
internal fun structureScore(c: Candidate): Double {
    val p5 = c.s5m?.priceChange
    val p1 = c.s1h?.priceChange
    val ord = listOfNotNull(p5, p1, c.s6h?.priceChange, c.s24h?.priceChange)
    if (ord.size < 2) return 0.5
    val consistency = ord.count { it >= 0 }.toDouble() / ord.size
    val shorts = listOfNotNull(p5, p1)
    val recentOk = if (shorts.isEmpty()) 0.5 else shorts.count { it >= 0 }.toDouble() / shorts.size
    val flow = c.s1h ?: c.s5m
    val accel = when {
        p5 == null -> 0.5
        p5 > 0 && (flow?.liquidityChange ?: 0.0) >= 0 -> 1.0
        p5 >= 0 -> 0.5
        else -> 0.0
    }
    return clamp01(0.45 * consistency + 0.3 * recentOk + 0.25 * accel)
}

/**
 * Rank a coin that already passed the gate. Higher is better, and the number
 * means nothing on its own: it only orders one scan against itself.
 */
fun runnerScore(c: Candidate, gate: ScanGate): Pair<Double, List<String>> {
    val s = c.s1h ?: c.s5m ?: c.s24h ?: return 0.0001 to listOf("no trading data")
    val notes = ArrayList<String>()
    val bv = s.buyVolume ?: 0.0
    val sv = s.sellVolume ?: 0.0

    // Breadth: distinct organic buyers against all traders. Only ever divided by a
    // wallet count, never by a transaction count, which would deflate it for every
    // busy coin; neutral when there is no wallet denominator.
    val buyers = (s.numOrganicBuyers ?: s.numNetBuyers ?: 0).toDouble()
    val breadth = if ((s.numTraders ?: 0) > 0) clamp01(buyers / s.numTraders!!) else 0.3

    // Organic share of buying. Neutral when the field is missing: reading it as
    // "all organic" would hand a perfect score to exactly the wash-traded coins
    // this is meant to bury.
    val buyOrg = s.buyOrganicVolume
    val orgShare = if (bv > 0 && buyOrg != null) clamp01(buyOrg / bv) else 0.5
    val pressure = if (bv + sv > 0) bv / (bv + sv) else 0.5

    val pc = momentumBlend(c) ?: s.priceChange ?: 0.0
    val mom = (max(-1.0, min(1.0, pc / 150.0)) + 1) / 2

    // Turnover. Healthy is roughly half to eight times liquidity a day; far above
    // that is where wash trading lives, so the curve decays instead of climbing.
    val vol24 = c.s24h?.volume ?: s.volume ?: 0.0
    val liq = c.liquidity
    val vl = if (liq > 0) vol24 / liq else 0.0
    val vlScore = when {
        vl in 0.5..8.0 -> 1.0
        vl < 0.5 -> clamp01(vl / 0.5)
        else -> clamp01(1 - (vl - 8) / 12)
    }

    val hGrow = clamp01((s.holderChange ?: 0.0) / 0.2)
    val ageS = ageScore(c.ageMinutes, gate)
    val orgN = clamp01((c.organicScore ?: 0.0) / 100.0)

    val w = gate.weights
    val base = w.breadth * breadth + w.orgShare * orgShare + w.mom * mom + w.vl * vlScore +
        w.hGrow * hGrow + w.age * ageS + w.pressure * pressure + w.org * orgN

    // Multipliers below can only lower the score. None of them can lift a coin.

    // Still going up right now, against an hour that already went up. The veto in
    // [passesGate] throws out the vertical hour; this is the softer half of the
    // same idea, for the coin that is mid-candle at the moment we look. Buying
    // while it is still running is buying from whoever is about to stop.
    val runMult = run {
        val h = c.s1h?.priceChange
        val m5 = c.s5m?.priceChange
        if (h == null || m5 == null || h <= 5.0) 1.0
        else if (m5 > h / 3.0) { notes += "still running, up " + m5.toInt() + "% in five minutes"; 0.75 }
        else 1.0
    }

    // A wash fingerprint: nearly all the volume on the buy side while the price
    // does not move is one actor trading with themselves.
    val washMult = if (bv + sv > 0 && bv >= 0.9 * (bv + sv) && abs(pc) < 3) 0.05 else 1.0
    val vlWash = if (vl > 15 && abs(pc) < 3) 0.1 else 1.0
    if (washMult < 1 || vlWash < 1) notes += "volume looks washed"

    // A coin down hard on the day. The taper starts late because this asset class
    // swings in hundreds of percent and a 20% day is ordinary noise here.
    val pc24 = c.s24h?.priceChange ?: 0.0
    val dumpTrig = -35.0
    val dumpStart = dumpTrig * 0.55
    val dumpFloor = 0.12
    val dumpMult = when {
        pc24 <= dumpTrig -> dumpFloor
        pc24 < dumpStart -> dumpFloor + (1 - dumpFloor) * ((pc24 - dumpTrig) / (dumpStart - dumpTrig))
        else -> 1.0
    }
    if (dumpMult < 1) notes += "down " + (-pc24).toInt() + "% on the day"

    val struct = structureScore(c)
    val structMult = gate.structFloor + (1 - gate.structFloor) * struct
    val p5 = c.s5m?.priceChange
    val p1 = c.s1h?.priceChange
    val pd = c.s24h?.priceChange
    val allRed = p5 != null && p1 != null && pd != null && p5 < 0 && p1 < 0 && pd < 0
    if (allRed) notes += "red on every window"
    val knifeMult = if (allRed) 0.1 else 1.0

    val authMult = if (c.canMint || c.canFreeze) gate.authMult else 1.0
    val devMult = if (c.devMints > 1) 0.85 else 1.0
    if (c.devMints > 1) notes += "the creator has minted " + c.devMints + " tokens"

    // Top-holder concentration, the single best predictor of a coin going to
    // zero. The hard ceiling is in the gate; this is the gradual part below it.
    val conc = c.topHoldersPct ?: 0.0
    val concMult = if (conc > 0) clamp01(1 - max(0.0, conc - gate.concStart) / 60) * (1 - gate.concFloor) + gate.concFloor else 1.0
    if (conc > gate.concStart) notes += "top holders own " + conc.toInt() + "%"

    val breadthMin = if ((s.numBuys ?: 0) < gate.numBuysFloor) 0.4 else 1.0
    if (breadthMin < 1) notes += "very few buys in the last hour"

    // Total volume can look bullish while the real wallets are selling into it.
    val sellOrg = s.sellOrganicVolume
    val orgFlowMult = if (buyOrg != null && sellOrg != null && buyOrg + sellOrg > 0) {
        clamp01(0.5 + buyOrg / (buyOrg + sellOrg))
    } else 1.0
    if (orgFlowMult < 0.9) notes += "real wallets are net sellers"

    // Short of the veto, liquidity walking away still counts against it.
    val drain = c.s6h?.liquidityChange ?: c.s1h?.liquidityChange ?: 0.0
    val drainMult = if (drain < -10.0) clamp01(1 + (drain + 10.0) / 30.0) * 0.7 + 0.3 else 1.0
    if (drainMult < 1) notes += "liquidity is leaving"

    val mcR = c.mcap ?: c.fdv ?: 0.0
    val liqMcMult = if (!gate.thinIsVeto && mcR > 0 && liq > 0 && liq / mcR < ScanGate.MIN_LIQ_MCAP_RATIO) 0.25 else 1.0
    if (liqMcMult < 1) notes += "thin liquidity for its market cap"

    if (notes.isEmpty()) {
        if (breadth >= 0.5) notes += "demand spread across many wallets"
        if (orgShare >= 0.6) notes += "most buying is organic"
        if (struct >= 0.7) notes += "chart holding up"
    }

    val score = max(
        0.0001,
        base * structMult * knifeMult * washMult * vlWash * dumpMult * authMult * devMult * concMult * breadthMin * orgFlowMult * liqMcMult * drainMult * runMult,
    )
    return score to notes
}

/** What the scan threw out and why, so "nothing found" is never the whole answer. */
data class MarketPicks(val picks: List<Scored>, val rejected: Map<String, Int>, val looked: Int)

/** Gate, score, sort. The whole pipeline in one call. */
/**
 * The other question: not which coin is moving, but which one is standing up.
 *
 * [runnerScore] is built to find a runner — it weights momentum, buy pressure,
 * turnover, and it is right to, because that is what a trading loop is hunting.
 * Asked for "the safest coins of the day" it answers with whatever is climbing
 * fastest, which is the opposite of the question.
 *
 * So this scores the things that do not move: depth of liquidity against market
 * cap, how many people hold it, how little of it the top wallets own, how long it
 * has existed, and Jupiter's own organic score. **Momentum is deliberately absent**
 * — a coin that has done nothing all day is not penalised here, and one that has
 * tripled is not rewarded.
 *
 * Same gate as everything else: nothing reaches this ranking that would not have
 * reached the other one.
 */
fun safestPicks(candidates: List<Candidate>, gate: ScanGate, limit: Int = 3): List<Scored> {
    val kept = ArrayList<Scored>()
    for (c in candidates) {
        if (passesGate(c, gate) != null) continue
        val notes = ArrayList<String>()

        // Depth is the one that matters most: it is what lets you leave.
        val mc = c.mcap ?: c.fdv ?: 0.0
        val depth = if (mc > 0) clamp01(c.liquidity / mc / 0.15) else clamp01(c.liquidity / 500_000.0)
        if (c.liquidity >= 250_000) notes += "deep liquidity"

        // Many holders is many people who are not one person.
        val holders = clamp01(((c.holders ?: 0).toDouble()) / 20_000.0)
        if ((c.holders ?: 0) >= 10_000) notes += (c.holders!! / 1000).toString() + "k holders"

        // Concentration, inverted: 5% in the top wallets is calm, 30% is a cliff.
        val spread = c.topHoldersPct?.let { clamp01(1.0 - (it / 35.0)) } ?: 0.5
        if ((c.topHoldersPct ?: 100.0) <= 12.0) notes += "supply not concentrated"

        // Age, with no upper band: here older is simply better.
        val age = c.ageMinutes?.let { clamp01(it / 43_200.0) } ?: 0.4
        if ((c.ageMinutes ?: 0.0) >= 20_160) notes += "months old"

        val organic = clamp01((c.organicScore ?: 0.0) / 100.0)
        val verified = if (c.verified) 1.0 else 0.6

        val score = (0.34 * depth + 0.22 * holders + 0.18 * spread + 0.14 * age + 0.12 * organic) * verified
        kept += Scored(c, score, notes)
    }
    return kept.sortedByDescending { it.score }.take(limit)
}

fun scanMarket(candidates: List<Candidate>, gate: ScanGate, limit: Int = 5): MarketPicks {
    val rejected = LinkedHashMap<String, Int>()
    val kept = ArrayList<Scored>()
    for (c in candidates) {
        val no = passesGate(c, gate)
        if (no != null) { rejected[no] = (rejected[no] ?: 0) + 1; continue }
        val (score, notes) = runnerScore(c, gate)
        kept += Scored(c, score, notes)
    }
    return MarketPicks(kept.sortedByDescending { it.score }.take(limit), rejected, candidates.size)
}
