package com.clearsign.core

/**
 * Address poisoning: an attacker sends dust from an address that starts and ends like one you
 * deal with, so the next copy from your history is theirs. Wallets show the ends and hide the
 * middle, where the two differ. Pure check: same first and last four characters as a known
 * address (contacts, own accounts, people paid before), not that address, is a lookalike.
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
