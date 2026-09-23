package com.clearsign.core

/**
 * Where the network is digging, round after round. The worker publishes the closed rounds in
 * the archive, one a minute; read here to answer three questions: which squares are usually
 * least crowded, which came out, whether one comes out more than others. Only the first counts
 * for money: the ORE share is your part of the square, and a square the network leaves empty pays more at the same cost. Pure, no JSON, no network.
 */
data class PastRound(
    val id: Long,
    /** The square that came out, zero-based. */
    val win: Int,
    /** The prize was split pro rata; otherwise one miner took it. */
    val split: Boolean,
    val deployed: LongArray,
    val count: LongArray,
    val miners: Long,
    /** Who took everything, in the single-winner rounds. */
    val top: String? = null,
)

object OreCrowd {
    /** How crowded each square is: its share of the round's total, averaged over rounds. Zero with no rounds. */
    fun crowding(rounds: List<PastRound>): DoubleArray {
        val out = DoubleArray(Ore.SQUARES)
        if (rounds.isEmpty()) return out
        var used = 0
        for (r in rounds) {
            val total = r.deployed.sum().toDouble()
            if (total <= 0.0 || r.deployed.size < Ore.SQUARES) continue
            used++
            for (s in 0 until Ore.SQUARES) out[s] += r.deployed[s] / total
        }
        if (used > 0) for (s in 0 until Ore.SQUARES) out[s] /= used
        return out
    }

    /** The average SOL per square over the rounds, to warm the grid as with the live round. */
    fun averageDeployed(rounds: List<PastRound>): LongArray {
        val out = LongArray(Ore.SQUARES)
        val good = rounds.filter { it.deployed.size >= Ore.SQUARES }
        if (good.isEmpty()) return out
        for (s in 0 until Ore.SQUARES) out[s] = good.sumOf { it.deployed[s] } / good.size
        return out
    }

    /** The [k] squares the network leaves emptiest, ties to the first ones. */
    fun best(k: Int, rounds: List<PastRound>): List<Int> {
        val c = crowding(rounds)
        return c.indices.sortedWith(compareBy({ c[it] }, { it })).take(k.coerceIn(0, Ore.SQUARES))
    }

    /** The squares that came out, most recent round first. */
    fun winners(rounds: List<PastRound>): List<Int> = rounds.sortedByDescending { it.id }.map { it.win }

    /** The square that came out most often and how many times, or null with no rounds. */
    fun hottest(rounds: List<PastRound>): Pair<Int, Int>? =
        rounds.groupingBy { it.win }.eachCount().entries.maxWithOrNull(compareBy({ it.value }, { -it.key }))?.let { it.key to it.value }
}
