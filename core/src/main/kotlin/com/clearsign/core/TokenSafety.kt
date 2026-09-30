package com.clearsign.core

/**
 * Risk of the coin itself, not the transaction: a clean transaction can still buy a token its
 * creator can freeze or mint, or that nobody buys back. Scoring ported from the MEGAGEN gates,
 * using only what Jupiter's registry returns. A fatal vector is a ceiling, not a penalty;
 * missing data lands mid-scale and warns, never blocks.
 */
enum class SafetyBand { GOOD, MID, BAD }

/** Why the grade came out the way it did, worst first. The app words these. */
enum class SafetyFlag {
    /** Jupiter has no route back to SOL: you could buy it and never sell it. */
    NO_WAY_OUT,
    /** The creator can still freeze your balance where it sits. */
    CAN_FREEZE,
    /**
     * Mint and freeze on a Jupiter-verified coin, as USDC and USDT have by design. Disclosed,
     * since few know Circle can freeze USDC, but not a scam signal.
     */
    ISSUER_CONTROLLED,
    /** A verified coin whose issuer can still mint more, and nothing else. */
    ISSUER_MINT,
    /** A verified coin whose issuer can freeze balances, and nothing else. */
    ISSUER_FREEZE,
    /**
     * A verified coin with a Token-2022 permanent delegate: its issuer can move or burn your
     * balance. PYUSD and USDG work this way; the power is named, not folded into freeze and mint.
     */
    ISSUER_SEIZE,
    /**
     * A liquid staking token: minted by its stake pool program when someone stakes, burned when
     * they unstake, and nobody can freeze it. A live mint authority is how it works, not a person.
     */
    PROTOCOL_MINTED,
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
     * Token-2022 permanent delegate: someone can take this token from your wallet at will. On an
     * anonymous coin that is the scam; on a verified issuer's token it is how a regulated
     * stablecoin works, reported as [ISSUER_SEIZE].
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
    /** Extensions read from the mint account on chain; the registry only says "Token-2022". */
    val ext: MintExtensions = MintExtensions.NONE,
    /** One of [LiquidStake.MINTS]. */
    val liquidStake: Boolean = false,
)

/**
 * Liquid staking tokens whose mint authority is their stake pool program and whose freeze
 * authority is empty, checked on chain 29 Sep 2026.
 */
object LiquidStake {
    val MINTS = setOf(
        "storenSbvkfzircixnaosc5CbzNZVrHJ6S3EKrS1yqR", // stORE, regolith-labs/ore-lst
        "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So", // mSOL
        "J1toso1uCk3RLmjorhTtrVwY9HJ7X8V9yYac6Y7kGCPn", // JitoSOL
        "bSo13r4TkiE4KumL71LsHTPpL2euBYLFx6h9HP3piy1", // bSOL
        "jupSoLaHXQiZZTSfEWMTRRgpnyFm8f6sZdosWBjx93v", // JupSOL
        "5oVNBeEEQvYi1cX3ir8Dx5n1P7pdxydbGF2X4TxVusJm", // INF
    )
}

data class TokenSafety(val score: Int, val band: SafetyBand, val flags: List<SafetyFlag>) {
    val bad: Boolean get() = band == SafetyBand.BAD
}

/** Grade a coin 0..100, higher is safer. */
fun assessToken(f: TokenFacts): TokenSafety {
    // Base score: cautious for unknown coins, trusted for verified ones.
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
        // Always apply the ceiling; add each flag once.
        if (flag !in flags) flags += flag
        if (limit < cap) cap = limit
    }

    if (f.sellable == false) cap(6, SafetyFlag.NO_WAY_OUT)
    // Same on-chain fact, two meanings: on an anonymous coin a live authority is the classic rug,
    // on a verified one it is how issued tokens work. Flagging USDC teaches people to ignore warnings.
    if (f.liquidStake && f.canMint && !f.canFreeze) {
        // Flagged, not capped: the program mints against stake, and nobody can freeze it.
        flags += SafetyFlag.PROTOCOL_MINTED
    } else if (f.verified && (f.canFreeze || f.canMint)) {
        // Name only the power that is live.
        cap(72, when {
            f.canFreeze && f.canMint -> SafetyFlag.ISSUER_CONTROLLED
            f.canMint -> SafetyFlag.ISSUER_MINT
            else -> SafetyFlag.ISSUER_FREEZE
        })
    } else {
        if (f.canFreeze) cap(38, SafetyFlag.CAN_FREEZE)
        if (f.canMint) cap(38, SafetyFlag.CAN_MINT)
    }
    f.topHoldersPct?.let { p ->
        if (p >= 85) cap(40, SafetyFlag.WHALE) else if (p >= 65) cap(62, SafetyFlag.WHALE)
    }
    f.devPct?.let { p -> if (p >= 30) cap(45, SafetyFlag.DEV_HEAVY) }
    if (f.devMints >= 25) cap(50, SafetyFlag.SERIAL_CREATOR)
    // Mint extensions. A fatal vector is a ceiling, not a penalty: no liquidity, holder
    // count or organic score lifts it.
    if (f.ext.nonTransferable) cap(4, SafetyFlag.NON_TRANSFERABLE)
    if (f.ext.permanentDelegate) {
        // Two meanings, as with freeze and mint: PYUSD and EURC need it; an anonymous
        // coin uses it to burn your balance right after you buy.
        if (f.verified) cap(72, SafetyFlag.ISSUER_SEIZE) else cap(8, SafetyFlag.SEIZABLE)
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
