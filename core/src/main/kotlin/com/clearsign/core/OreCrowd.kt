package com.clearsign.core

/**
 * Dove sta scavando la rete, giro dopo giro.
 *
 * I giri chiusi li pubblica il worker nell'archivio, uno al minuto; qui si
 * leggono per rispondere a tre domande: quali caselle sono di solito le meno
 * affollate, quali sono uscite, e se una esce piu' delle altre. La prima e'
 * l'unica che conta per i soldi: la quota di ORE e' la propria parte della
 * casella, e una casella che la rete lascia vuota rende di piu' a parita' di
 * costo. Puro, senza JSON e senza rete.
 */
data class PastRound(
    val id: Long,
    /** La casella uscita, da zero. */
    val win: Int,
    /** Il premio si e' diviso pro quota; altrimenti l'ha preso uno solo. */
    val split: Boolean,
    val deployed: LongArray,
    val count: LongArray,
    val miners: Long,
    /** Chi ha preso tutto, nei giri a uno solo. */
    val top: String? = null,
)

object OreCrowd {
    /** L'affollamento di ogni casella: la sua quota sul totale del giro, in media sui giri. Zero senza giri. */
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

    /** Il SOL medio per casella sui giri, per scaldare la griglia come col giro in corso. */
    fun averageDeployed(rounds: List<PastRound>): LongArray {
        val out = LongArray(Ore.SQUARES)
        val good = rounds.filter { it.deployed.size >= Ore.SQUARES }
        if (good.isEmpty()) return out
        for (s in 0 until Ore.SQUARES) out[s] = good.sumOf { it.deployed[s] } / good.size
        return out
    }

    /** Le [k] caselle che la rete lascia piu' vuote, a parita' le prime. */
    fun best(k: Int, rounds: List<PastRound>): List<Int> {
        val c = crowding(rounds)
        return c.indices.sortedWith(compareBy({ c[it] }, { it })).take(k.coerceIn(0, Ore.SQUARES))
    }

    /** Le caselle uscite, dal giro piu' recente. */
    fun winners(rounds: List<PastRound>): List<Int> = rounds.sortedByDescending { it.id }.map { it.win }

    /** La casella uscita piu' volte e quante, o null senza giri. */
    fun hottest(rounds: List<PastRound>): Pair<Int, Int>? =
        rounds.groupingBy { it.win }.eachCount().entries.maxWithOrNull(compareBy({ it.value }, { -it.key }))?.let { it.key to it.value }
}
