package com.clearsign.core

/**
 * Decides how much a counterparty address can be trusted and, crucially,
 * detects address-poisoning "look-alikes".
 *
 * Wallet UIs almost always abbreviate addresses as `first…last`. Poisoners mine
 * vanity addresses whose visible prefix and suffix match an address you have
 * already paid, hoping you copy the wrong one from your history. ClearSign
 * compares the *full* address and warns when only the shown ends collide.
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

    /**
     * Returns a known address that `address` is impersonating, or null.
     * A match means the visible ends collide but the full string differs.
     */
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
