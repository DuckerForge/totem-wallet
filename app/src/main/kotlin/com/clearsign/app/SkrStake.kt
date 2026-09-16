package com.clearsign.app

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SKR staked with the Seeker Guardians.
 *
 * "Claim and stake" on the Seed Vault Wallet puts the airdrop straight into
 * the staking program, so those SKR never touch the wallet's token account
 * and the token list cannot see them. The program keeps one UserStake account
 * per wallet (169 bytes: the wallet at byte 41, the guardian pool at 73, the
 * shares as a u128 at 105) and one config account with the share price (a
 * u128 at byte 137, scaled by a billion). Staked SKR = shares × price / 1e9.
 *
 * Layout and constants from the open read-only indexer `skr-ecosystem-eyes`,
 * and checked on the 15th of September 2026 against the vault: the formula
 * over all shares gives 4.98 billion SKR, the vault holds 5.01.
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
    ) {
        val ui: Double get() = rawSkr / 1e6
    }

    /**
     * What staking really pays, measured rather than assumed.
     *
     * This used to be derived from the published tokenomics: ten percent yearly
     * inflation, all of it to stakers, spread over what is staked. That gave
     * 21.25% against the 15.40% the Seeker wallet itself shows for the same
     * stake, so the app was promising a third more than the chain pays. The
     * assumption was the problem, not the arithmetic: we cannot see from here
     * how much of the emission reaches this pool.
     *
     * What we can see is the share price. Rewards are paid by that number
     * growing, so two readings far enough apart are the yield, exactly, with
     * nothing assumed. Null until there are two: a number we cannot stand behind
     * is worse than no number on a screen about somebody's money.
     */
    const val MIN_SAMPLE_MS = 6 * 3_600_000L

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

    /**
     * The share price, remembered, so the yield can be measured instead of
     * guessed. One reading kept per device: the oldest one is the most useful,
     * because a longer span is a steadier rate.
     */
    fun observedAprPct(ctx: android.content.Context, price: Double, now: Long = System.currentTimeMillis()): Double? {
        if (price <= 0.0) return null
        val p = ctx.getSharedPreferences("skr_yield", android.content.Context.MODE_PRIVATE)
        val oldPrice = p.getFloat("price", 0f).toDouble()
        val oldAt = p.getLong("at", 0L)
        if (oldPrice <= 0.0 || oldAt <= 0L || price < oldPrice) {
            // First look, or the price went backwards, which means the pool was
            // reset rather than that it lost money. Start the clock again.
            p.edit().putFloat("price", price.toFloat()).putLong("at", now).apply()
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
