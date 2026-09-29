package com.clearsign.core

/**
 * Spare change into stORE. Every swap rounds the SOL it moves up to the next [STEP]; the
 * difference is only counted, the SOL stays in the wallet. Once the count reaches [MOVE_AT]
 * the wallet offers one swap into stORE, ORE staked through the ORE LST program
 * (regolith-labs/ore-lst), which earns a share of the protocol's buyback.
 */
object Spare {
    const val STORE_MINT = "storenSbvkfzircixnaosc5CbzNZVrHJ6S3EKrS1yqR"
    const val STORE_DECIMALS = 11
    const val STEP = 5_000_000L
    const val MOVE_AT = 20_000_000L

    /** What rounds [lamports] up to the next multiple of [step]; zero when already round. */
    fun roundUp(lamports: Long, step: Long = STEP): Long {
        if (lamports <= 0 || step <= 0) return 0
        val rest = lamports % step
        return if (rest == 0L) 0 else step - rest
    }

    /**
     * What can move now: the jar, capped by what the wallet can spare beyond [reserve]. Zero below
     * [floor]: a swap that small costs more than it moves, and would be offered again and again.
     */
    fun movable(jar: Long, balance: Long, reserve: Long, floor: Long = STEP): Long {
        val m = minOf(jar, (balance - reserve).coerceAtLeast(0))
        return if (m < floor) 0 else m
    }
}
