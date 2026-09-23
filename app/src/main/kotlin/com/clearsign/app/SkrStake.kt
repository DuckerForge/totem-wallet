package com.clearsign.app

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SKR staked with the Seeker Guardians. "Claim and stake" puts the airdrop straight into the
 * staking program, so the token list cannot see it. One UserStake account per wallet (169 bytes:
 * wallet at 41, guardian pool at 73, shares as u128 at 105) and one config account with the share
 * price (u128 at 137, scaled by 1e9). Staked SKR = shares × price / 1e9. Layout from the indexer `skr-ecosystem-eyes`, checked 15 Sep 2026: all shares give 4.98 billion SKR, the vault holds 5.01.
 */
object SkrStake {
    const val PROGRAM = "SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ"
    const val CONFIG = "4HQy82s9CHTv1GsYKnANHMiHfhcqesYkK6sB3RDSYyqw"
    const val SKR_MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
    const val USER_STAKE_SIZE = 169
    const val OWNER_OFFSET = 41
    private val SHARE_SCALE = BigInteger.valueOf(1_000_000_000L)

    data class Position(
        val guardian: String,
        val shares: BigInteger,
        val rawSkr: Long,
        val totalStakedRaw: Long,
        /** What one share is worth now. Rewards arrive by this number going up. */
        val sharePrice: Double = 0.0,
        /** The stake account on chain, for the link. Empty when unknown. */
        val account: String = "",
    ) {
        val ui: Double get() = rawSkr / 1e6
    }

    /**
     * What staking really pays, measured. Derived from tokenomics (ten percent yearly inflation to
     * stakers) it gave 21.25% against the 15.40% the Seeker wallet shows. The share price we can see,
     * and rewards are paid by it growing, so two readings far enough apart are the yield exactly;
     * null until there are two. A week apart, because rewards land every forty-eight hours and the price steps. Checked on 1.138725049 to 1.139766368 two days apart: near 15.40%.
     */
    const val MIN_SAMPLE_MS = 7 * 24 * 3_600_000L

    fun growthAprPct(oldPrice: Double, oldAt: Long, newPrice: Double, newAt: Long): Double? {
        val span = newAt - oldAt
        if (span < MIN_SAMPLE_MS || oldPrice <= 0.0 || newPrice <= 0.0) return null
        val growth = newPrice / oldPrice - 1.0
        if (growth <= 0.0) return null
        val perYear = growth * (31_557_600_000.0 / span)
        // A pool cannot pay a thousand percent. A jump that large is the program
        // having been changed under us, not a windfall, and it should not be
        // reported as one.
        return (perYear * 100.0).takeIf { it.isFinite() && it < 200.0 }
    }

    /** Pure: the user's account bytes and the config bytes in, the staked SKR out. */
    fun decode(userStake: ByteArray, config: ByteArray): Position? {
        if (userStake.size < 121 || config.size < 153) return null
        val guardian = Base58.encode(userStake.copyOfRange(73, 105))
        val shares = u128(userStake, 105)
        val price = u128(config, 137)
        val raw = shares.multiply(price).divide(SHARE_SCALE)
        val total = u128(config, 121).multiply(price).divide(SHARE_SCALE)
        return Position(
            guardian,
            shares,
            raw.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong(),
            total.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong(),
            price.toDouble() / 1e9,
        )
    }

    /** The share price, remembered, so the yield is measured, not guessed. One reading per device, the oldest: a longer span is a steadier rate. */
    fun observedAprPct(ctx: android.content.Context, price: Double, now: Long = System.currentTimeMillis()): Double? {
        if (price <= 0.0) return null
        val p = ctx.getSharedPreferences("skr_yield", android.content.Context.MODE_PRIVATE)
        // Kept the way the chain keeps it, scaled by a billion, because a week of
        // rewards moves this number in its fourth decimal and a float would spend
        // its precision on the 1 in front.
        val oldScaled = p.getLong("price_e9", 0L)
        val oldPrice = oldScaled / 1e9
        val oldAt = p.getLong("at", 0L)
        if (oldScaled <= 0L || oldAt <= 0L || price < oldPrice) {
            // First look, or the price went backwards, which means the pool was
            // reset rather than that it lost money. Start the clock again.
            p.edit().putLong("price_e9", (price * 1e9).toLong()).putLong("at", now).apply()
            return null
        }
        return growthAprPct(oldPrice, oldAt, price, now)
    }

    private fun u128(b: ByteArray, at: Int): BigInteger {
        val lo = ByteBuffer.wrap(b, at, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val hi = ByteBuffer.wrap(b, at + 8, 8).order(ByteOrder.LITTLE_ENDIAN).long
        return BigInteger.valueOf(hi).and(MASK).shiftLeft(64).or(BigInteger.valueOf(lo).and(MASK))
    }
    private val MASK = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
}
