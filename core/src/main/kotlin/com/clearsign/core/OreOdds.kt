package com.clearsign.core

import java.math.BigInteger
import kotlin.math.roundToLong

/**
 * What a stake on the ORE grid returns, by the program's rules, read in `checkpoint.rs` and
 * `reset.rs` of `regolith-labs/ore` on 22 Sep 2026. Other people's SOL is never won: at round
 * end everyone takes theirs back, minus 1% on the winning square and about 11% on the losers.
 * The prize is the round's ORE, pro rata among those on the winning square, plus the pot once
 * every five hundred rounds. So the square does not change what stays on the table, only the ORE share: the one with less SOL on it pays best. Pure.
 */
object OreOdds {
    const val SQUARES = 25
    /** Once every five hundred rounds the pot goes to the winning square: `rng.reverse_bits() % 500 == 0`. */
    const val MOTHERLODE_ODDS = 500

    /** `max(total / 100, 1)`: a square's admin fee, as `Round::calculate_fees`. */
    fun adminFee(total: Long): Long = maxOf(total / 100, 1L)

    /** `max((total - admin) / 10, 1)`: the protocol fee on a losing square. */
    fun protocolFee(total: Long): Long = maxOf((total - adminFee(total)) / 10, 1L)

    /** What comes back to whoever put [mine] on a square holding [total], if that square wins. */
    fun backIfWin(mine: Long, total: Long): Long =
        if (mine <= 0 || total <= 0) 0L else mulDiv(mine, total - adminFee(total), total)

    /** The same, if the square loses. */
    fun backIfLose(mine: Long, total: Long): Long =
        if (mine <= 0 || total <= 0) 0L else mulDiv(mine, (total - adminFee(total) - protocolFee(total)).coerceAtLeast(0L), total)

    private fun mulDiv(a: Long, b: Long, c: Long): Long =
        BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).divide(BigInteger.valueOf(c)).toLong()

    /**
     * What to expect from [perSquare] lamports on each square of [others], the SOL others already
     * have on each. [reward] is the round's ORE, [motherlode] the pot in the Treasury, both in whole ORE units with eleven decimals.
     */
    data class Outlook(
        val stake: Long,
        /** SOL expected back, fees included, over all the squares. */
        val expectedSolBack: Long,
        /** ORE atteso, undici decimali. */
        val expectedOre: Long,
        /** For each chosen square, the ORE share if it were the one to win. */
        val shares: DoubleArray,
    ) {
        val expectedCost: Long get() = stake - expectedSolBack
        val costFraction: Double get() = if (stake <= 0) 0.0 else expectedCost.toDouble() / stake
    }

    fun outlook(perSquare: Long, others: List<Long>, reward: Long, motherlode: Long): Outlook {
        if (perSquare <= 0 || others.isEmpty()) return Outlook(0L, 0L, 0L, DoubleArray(others.size))
        var back = 0.0
        var ore = 0.0
        val shares = DoubleArray(others.size)
        val prize = reward.toDouble() + motherlode.toDouble() / MOTHERLODE_ODDS
        for ((i, d) in others.withIndex()) {
            val total = d + perSquare
            shares[i] = perSquare.toDouble() / total
            back += backIfWin(perSquare, total) / SQUARES.toDouble() + backIfLose(perSquare, total) * (SQUARES - 1) / SQUARES.toDouble()
            ore += shares[i] * prize / SQUARES
        }
        return Outlook(perSquare * others.size, back.roundToLong(), ore.roundToLong(), shares)
    }

    /** The ORE share with [perSquare] on a square where others hold [others]. */
    fun share(perSquare: Long, others: Long): Double = if (perSquare <= 0) 0.0 else perSquare.toDouble() / (others + perSquare)

    /** The [k] squares with the least SOL on them; ties to fewer miners, then the first ones. */
    fun best(k: Int, deployed: LongArray, count: LongArray): List<Int> =
        deployed.indices.sortedWith(compareBy({ deployed[it] }, { count.getOrElse(it) { 0L } }, { it })).take(k.coerceIn(0, deployed.size))
}
