package com.clearsign.core

/**
 * How dangerous is the coin itself? The receipt says what the transaction does; this says
 * what you are buying: an honest transaction can hand you a token whose creator can freeze
 * it, mint infinite supply, or that nobody buys back. Scoring ported from the MEGAGEN gates,
 * reduced to what Jupiter's registry already returns, no extra call. Two rules kept: a fatal
 * vector is a ceiling, not a penalty, no liquidity lifts a freezable token; and unknown is not dangerous, missing data lands mid-scale and warns, never blocks.
 */
enum class SafetyBand { GOOD, MID, BAD }

/** Why the grade came out the way it did, worst first. The app words these. */
enum class SafetyFlag {
    /** Jupiter has no route back to SOL: you could buy it and never sell it. */
    NO_WAY_OUT,
    /** The creator can still freeze your balance where it sits. */
    CAN_FREEZE,
    /**
     * The same powers on a coin Jupiter verified: USDC and USDT are mintable and freezable by
     * design, an issuer stands behind them. Worth saying, most people do not know Circle can freeze their stablecoin, but disclosed, not a scam signal.
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
    /**
     * Someone can take this token out of your wallet whenever they like: the Token-2022
     * permanent delegate. On an anonymous coin it is the whole scam in one field and no second
     * wallet protects you; on a verified issuer's token it is how a regulated stablecoin works, so it is graded like freeze and mint.
     */
    SEIZABLE,
    /** A program of the creator's choosing runs on every transfer, and can block sells. */
    TRANSFER_HOOK,
    /** The token takes a cut of every transfer, yours included. */
    TRANSFER_TAX,
    /** It cannot be transferred at all. */
    NON_TRANSFERABLE,
    /** New accounts start frozen: buying is allowed, selling is the creator's decision. */
    DEFAULT_FROZEN,
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
    /** What the mint account itself says it can do, read from the chain. The registry reports "Token-2022" and stops; the powers that empty a wallet after the purchase live here. */
    val ext: MintExtensions = MintExtensions.NONE,
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
    // The same on-chain fact means two things: on an anonymous coin a live authority is the
    // classic rug, on a verified one it is how a centrally-issued token works, and calling USDC dangerous teaches people to ignore the warning that matters.
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
    // The extensions, read from the mint itself. A fatal vector is a ceiling and
    // not a penalty: no amount of liquidity, holders or organic score buys its way
    // past a creator who can take the coin back out of your wallet.
    if (f.ext.nonTransferable) cap(4, SafetyFlag.NON_TRANSFERABLE)
    if (f.ext.permanentDelegate) {
        // Same fact, two meanings, exactly as with freeze and mint above: PYUSD
        // and EURC need this to exist at all, an anonymous coin uses it to burn
        // your balance seconds after you buy.
        if (f.verified) cap(72, SafetyFlag.ISSUER_CONTROLLED) else cap(8, SafetyFlag.SEIZABLE)
    }
    if (f.ext.defaultFrozen && !f.verified) cap(12, SafetyFlag.DEFAULT_FROZEN)
    if (f.ext.transferHook && !f.verified) cap(20, SafetyFlag.TRANSFER_HOOK)
    f.ext.transferFeeBps?.let { bps ->
        if (bps >= 1_000) cap(25, SafetyFlag.TRANSFER_TAX) else if (bps > 0) cap(60, SafetyFlag.TRANSFER_TAX)
    }
    // Token-2022 on its own, with none of the above, is just a newer standard.
    if (f.token2022 && !f.ext.any) cap(68, SafetyFlag.NEW_TOKEN_PROGRAM)
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
