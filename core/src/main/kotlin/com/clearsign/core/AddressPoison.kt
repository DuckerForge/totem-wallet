package com.clearsign.core

/**
 * Address poisoning: an attacker sends dust from an address that starts and
 * ends like one you already deal with, so that the next time you copy from
 * your history you copy theirs. Wallets show the ends and hide the middle,
 * which is exactly where the two differ.
 *
 * The check is small and pure: a candidate that shares the first and last
 * four characters with a known address, and is not that address, is a
 * lookalike. Known means the addresses this wallet has a reason to trust:
 * contacts, its own accounts, the people it has paid before.
 */
object AddressPoison {
    const val ENDS = 4

    data class Lookalike(val candidate: String, val of: String)

    fun lookalike(candidate: String, known: Collection<String>): Lookalike? {
        val c = candidate.trim()
        if (c.length < ENDS * 2 + 1) return null
        for (k in known) {
            if (k == c || k.length < ENDS * 2 + 1) continue
            if (k.startsWith(c.take(ENDS)) && k.endsWith(c.takeLast(ENDS))) return Lookalike(c, k)
        }
        return null
    }
}
