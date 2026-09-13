package com.clearsign.core

/**
 * How dangerous is the coin itself?
 *
 * The receipt answers "what does this transaction do"; this answers the other
 * half of a swap — "what am I buying". A perfectly honest transaction can still
 * hand you a token whose creator can freeze it, mint infinite supply, or that
 * nobody will buy back.
 *
 * The scoring is ported from the gates in the MEGAGEN app (same author, same
 * phone), reduced to the facts Jupiter's token registry already returns, so it
 * costs no extra network call. Two rules from there are kept deliberately:
 *
 *  * a fatal vector is a **ceiling**, not a penalty — no amount of "healthy"
 *    liquidity or holders can lift a token whose owner can freeze your balance;
 *  * unknown is **not** dangerous. Missing data lands mid-scale and warns; it
 *    never blocks, because a brand-new honest token would look identical.
 */
enum class SafetyBand { GOOD, MID, BAD }

/** Why the grade came out the way it did, worst first. The app words these. */
enum class SafetyFlag {
    /** Jupiter has no route back to SOL: you could buy it and never sell it. */
    NO_WAY_OUT,
    /** The creator can still freeze your balance where it sits. */
    CAN_FREEZE,
    /**
     * The same powers, on a coin Jupiter verified: USDC and USDT are mintable and
     * freezable *by design*, because an issuer stands behind them. Worth saying
     * out loud — most people do not know Circle can freeze their stablecoin — but
     * it is a disclosed property, not a scam signal.
     */
    ISSUER_CONTROLLED,
    /** The creator can still mint more of it, diluting what you hold. */
    CAN_MINT,
    /** One non-pool wallet holds enough to crater the price on its own. */
    WHALE,
    /** The creator is still sitting on a large slice of the supply. */
    DEV_HEAVY,
    /** The same creator has launched a long line of other tokens. */
    SERIAL_CREATOR,
    /** Token-2022: the standard allows transfer fees, hooks and seizure. */
    NEW_TOKEN_PROGRAM,
    /** So little liquidity that selling moves the price against you. */
    THIN,
    /** Almost nobody holds it yet. */
    FEW_HOLDERS,
    /** Not on Jupiter's verified list. */
    UNVERIFIED,
}

/** What we know about a coin, all of it optional except the obvious. */
data class TokenFacts(
    val verified: Boolean = false,
    /** Jupiter's organic-score label: "high", "medium", "low". */
    val organic: String? = null,
    val canMint: Boolean = false,
    val canFreeze: Boolean = false,
    val token2022: Boolean = false,
    /** Combined share held by the top holders, percent. */
    val topHoldersPct: Double? = null,
    /** The creator's own share, percent. */
    val devPct: Double? = null,
    /** How many other tokens the same creator has minted. */
    val devMints: Int = 0,
    val holders: Int = 0,
    val liquidityUsd: Double = 0.0,
    /** A real quote back to SOL succeeded. Null when we did not ask. */
    val sellable: Boolean? = null,
)

data class TokenSafety(val score: Int, val band: SafetyBand, val flags: List<SafetyFlag>) {
    val bad: Boolean get() = band == SafetyBand.BAD
}

/** Grade a coin 0..100, higher is safer. */
fun assessToken(f: TokenFacts): TokenSafety {
    // Unknown starts cautious, verified starts trusted: neither is a verdict.
    var score = if (f.verified) 72 else 55
    when (f.organic?.lowercase()) {
        "high" -> score += 10
        "medium" -> score += 4
    }
    if (f.holders >= 1_000) score += 5
    if (f.liquidityUsd >= 250_000) score += 6

    val flags = ArrayList<SafetyFlag>()
    // Ceilings, worst first. Applied after the bonuses so they stay ceilings.
    var cap = 100
    fun cap(limit: Int, flag: SafetyFlag) {
        flags += flag
        if (limit < cap) cap = limit
    }

    if (f.sellable == false) cap(6, SafetyFlag.NO_WAY_OUT)
    // The very same on-chain fact means two different things. On an anonymous coin
    // a live authority is the classic rug; on a verified one it is how a
    // centrally-issued token works, and calling USDC dangerous would only teach
    // people to ignore the warning that matters.
    if (f.verified && (f.canFreeze || f.canMint)) {
        cap(72, SafetyFlag.ISSUER_CONTROLLED)
    } else {
        if (f.canFreeze) cap(38, SafetyFlag.CAN_FREEZE)
        if (f.canMint) cap(38, SafetyFlag.CAN_MINT)
    }
    f.topHoldersPct?.let { p ->
        if (p >= 85) cap(40, SafetyFlag.WHALE) else if (p >= 65) cap(62, SafetyFlag.WHALE)
    }
    f.devPct?.let { p -> if (p >= 30) cap(45, SafetyFlag.DEV_HEAVY) }
    if (f.devMints >= 25) cap(50, SafetyFlag.SERIAL_CREATOR)
    if (f.token2022) cap(62, SafetyFlag.NEW_TOKEN_PROGRAM)
    if (f.liquidityUsd in 0.0..10_000.0) cap(45, SafetyFlag.THIN)
    if (f.holders in 1..99) cap(50, SafetyFlag.FEW_HOLDERS)
    if (!f.verified) cap(75, SafetyFlag.UNVERIFIED)

    score = score.coerceAtMost(cap).coerceIn(0, 100)
    val band = when {
        score >= 70 -> SafetyBand.GOOD
        score >= 40 -> SafetyBand.MID
        else -> SafetyBand.BAD
    }
    return TokenSafety(score, band, flags)
}
