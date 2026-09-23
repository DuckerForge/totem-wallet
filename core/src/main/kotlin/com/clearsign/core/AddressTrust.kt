package com.clearsign.core

/**
 * How much a counterparty address can be trusted, and the address-poisoning lookalikes.
 * Wallets abbreviate as `first…last`; poisoners mine vanity addresses whose visible ends match
 * one you paid, hoping you copy the wrong one. This compares the full address and warns when only the ends collide.
 */
class AddressTrust(
    private val allowlist: Map<String, String> = emptyMap(), // address -> contact label
    private val history: Set<String> = emptySet(),           // addresses transacted with before
    private val flagged: Set<String> = emptySet(),           // known malicious
    private val sanctioned: Set<String> = emptySet(),        // sanctions list
) {
    fun level(address: String): TrustLevel = when {
        address in flagged || address in sanctioned -> TrustLevel.FLAGGED
        address in allowlist -> TrustLevel.TRUSTED
        address in history -> TrustLevel.KNOWN
        else -> TrustLevel.NEW
    }

    fun label(address: String): String? = allowlist[address]

    fun isSanctioned(address: String): Boolean = address in sanctioned

    /** A known address that `address` impersonates, or null: the visible ends collide, the full string differs. */
    fun lookalikeOf(address: String, shownChars: Int = 4): String? {
        if (address in allowlist || address in history) return null // it *is* known
        val known = allowlist.keys + history
        val pre = address.take(shownChars)
        val suf = address.takeLast(shownChars)
        return known.firstOrNull { candidate ->
            candidate != address &&
                candidate.take(shownChars) == pre &&
                candidate.takeLast(shownChars) == suf
        }
    }
}
