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

    data class Position(val guardian: String, val shares: BigInteger, val rawSkr: Long) {
        val ui: Double get() = rawSkr / 1e6
    }

    /** Pure: the user's account bytes and the config bytes in, the staked SKR out. */
    fun decode(userStake: ByteArray, config: ByteArray): Position? {
        if (userStake.size < 121 || config.size < 153) return null
        val guardian = Base58.encode(userStake.copyOfRange(73, 105))
        val shares = u128(userStake, 105)
        val price = u128(config, 137)
        val raw = shares.multiply(price).divide(SHARE_SCALE)
        return Position(guardian, shares, raw.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong())
    }

    private fun u128(b: ByteArray, at: Int): BigInteger {
        val lo = ByteBuffer.wrap(b, at, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val hi = ByteBuffer.wrap(b, at + 8, 8).order(ByteOrder.LITTLE_ENDIAN).long
        return BigInteger.valueOf(hi).and(MASK).shiftLeft(64).or(BigInteger.valueOf(lo).and(MASK))
    }
    private val MASK = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
}
