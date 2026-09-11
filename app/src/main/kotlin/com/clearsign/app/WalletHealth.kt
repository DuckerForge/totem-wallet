package com.clearsign.app

/**
 * A single "how safe is my wallet right now" score with the concrete issues
 * behind it. Computed from the owner's token accounts (approvals still live, dust
 * to reclaim) plus the local trust book — every issue maps to a one-tap fix.
 */
data class HealthIssue(val kind: Kind, val count: Int, val detail: String) {
    enum class Kind { UNLIMITED_APPROVAL, LIMITED_APPROVAL, DUST_ACCOUNTS, FROZEN }
}

data class WalletHealth(val score: Int, val issues: List<HealthIssue>, val reclaimableLamports: Long) {
    val band: Band get() = when { score >= 90 -> Band.GREAT; score >= 70 -> Band.OK; score >= 45 -> Band.WEAK; else -> Band.RISKY }
    enum class Band { GREAT, OK, WEAK, RISKY }
    val isClean: Boolean get() = issues.isEmpty()

    companion object {
        /** 100 = nothing an attacker can reuse and nothing wasted. Deductions are capped so one big pile can't zero the score alone. */
        fun of(owner: String, accounts: List<SolanaRpc.TokenAccountInfo>): WalletHealth {
            val unlimited = accounts.filter { it.hasActiveDelegate && it.isUnlimitedDelegation }
            val limited = accounts.filter { it.hasActiveDelegate && !it.isUnlimitedDelegation }
            val dust = accounts.filter { it.isClosableBy(owner) }
            val frozen = accounts.filter { it.isFrozen }

            var score = 100
            score -= (unlimited.size * 22).coerceAtMost(66)   // a standing unlimited spend right is the worst
            score -= (limited.size * 8).coerceAtMost(24)
            score -= (dust.size * 2).coerceAtMost(12)          // hygiene, not danger
            score = score.coerceIn(0, 100)

            val issues = buildList {
                if (unlimited.isNotEmpty()) add(HealthIssue(HealthIssue.Kind.UNLIMITED_APPROVAL, unlimited.size, unlimited.joinToString { TokenSymbols.symbol(it.mint) }))
                if (limited.isNotEmpty()) add(HealthIssue(HealthIssue.Kind.LIMITED_APPROVAL, limited.size, limited.joinToString { TokenSymbols.symbol(it.mint) }))
                if (dust.isNotEmpty()) add(HealthIssue(HealthIssue.Kind.DUST_ACCOUNTS, dust.size, ""))
                if (frozen.isNotEmpty()) add(HealthIssue(HealthIssue.Kind.FROZEN, frozen.size, ""))
            }
            return WalletHealth(score, issues, dust.sumOf { it.lamports })
        }
    }
}
