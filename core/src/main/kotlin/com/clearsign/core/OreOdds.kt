package com.clearsign.core

import java.math.BigInteger
import kotlin.math.roundToLong

/**
 * Quanto rende una puntata sulla griglia di ORE, con le regole del programma.
 *
 * Lette in `program/src/checkpoint.rs` e `reset.rs` di `regolith-labs/ore` il
 * 22 settembre 2026. Il SOL degli altri non si vince mai: a fine giro ognuno
 * riprende il suo, meno l'1 per cento sulla casella vincente e circa l'11 per
 * cento sulle perdenti. Il premio e' l'ORE del giro, diviso pro quota fra chi
 * sta sulla casella vincente, piu' la pentola una volta ogni cinquecento giri.
 *
 * Quindi la casella non cambia quanto si lascia sul tavolo, cambia solo la
 * quota di ORE: conviene quella con meno SOL sopra. Puro, senza rete.
 */
object OreOdds {
    const val SQUARES = 25
    /** Una volta ogni cinquecento giri la pentola va alla casella vincente: `rng.reverse_bits() % 500 == 0`. */
    const val MOTHERLODE_ODDS = 500

    /** `max(total / 100, 1)`: la fee admin di una casella, come `Round::calculate_fees`. */
    fun adminFee(total: Long): Long = maxOf(total / 100, 1L)

    /** `max((total - admin) / 10, 1)`: la fee del protocollo su una casella perdente. */
    fun protocolFee(total: Long): Long = maxOf((total - adminFee(total)) / 10, 1L)

    /** Quanto torna a chi ha messo [mine] su una casella da [total] in tutto, se quella casella vince. */
    fun backIfWin(mine: Long, total: Long): Long =
        if (mine <= 0 || total <= 0) 0L else mulDiv(mine, total - adminFee(total), total)

    /** Lo stesso, se la casella perde. */
    fun backIfLose(mine: Long, total: Long): Long =
        if (mine <= 0 || total <= 0) 0L else mulDiv(mine, (total - adminFee(total) - protocolFee(total)).coerceAtLeast(0L), total)

    private fun mulDiv(a: Long, b: Long, c: Long): Long =
        BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).divide(BigInteger.valueOf(c)).toLong()

    /**
     * Cosa aspettarsi da [perSquare] lamport su ogni casella di [others], dove
     * [others] e' il SOL che gli altri hanno gia' su ognuna. [reward] e' l'ORE
     * del giro, [motherlode] la pentola nel Treasury, tutti e due in unita'
     * intere di ORE a undici decimali.
     */
    data class Outlook(
        val stake: Long,
        /** SOL atteso indietro, fee comprese, sull'insieme delle caselle. */
        val expectedSolBack: Long,
        /** ORE atteso, undici decimali. */
        val expectedOre: Long,
        /** Per ogni casella scelta, la quota dell'ORE se fosse lei a vincere. */
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

    /** La quota di ORE con [perSquare] su una casella dove gli altri hanno [others]. */
    fun share(perSquare: Long, others: Long): Double = if (perSquare <= 0) 0.0 else perSquare.toDouble() / (others + perSquare)

    /** Le [k] caselle con meno SOL sopra; a parita' quelle con meno minatori, poi le prime. */
    fun best(k: Int, deployed: LongArray, count: LongArray): List<Int> =
        deployed.indices.sortedWith(compareBy({ deployed[it] }, { count.getOrElse(it) { 0L } }, { it })).take(k.coerceIn(0, deployed.size))
}
