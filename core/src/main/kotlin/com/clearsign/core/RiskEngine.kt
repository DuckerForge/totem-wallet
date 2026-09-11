package com.clearsign.core

import kotlin.math.abs

/**
 * Turns decoded instructions + simulation + address trust + an external scan
 * into an ordered list of [Risk]s. This is the safety brain of ClearSign:
 * anything that produces a DANGER risk blocks one-tap approval.
 *
 * Two passes: [assess] judges the *instructions* (what the tx asks for) and
 * [assessEffects] judges the *effects* (what the simulation says will happen),
 * which is how a plain SOL transfer that empties the wallet into a brand-new
 * address stops reading as "no risk".
 */
class RiskEngine(private val locale: String = "en") {

    private fun risk(flag: RiskFlag, severity: Severity, vararg args: Any) =
        Risk(flag, severity, Localization.riskDetail(flag, locale, *args))

    fun assess(
        instructions: List<DecodedInstruction>,
        trust: AddressTrust,
        scan: ScanResult = ScanResult(),
        simulationSucceeded: Boolean = true,
        myWallet: String? = null,
        otherSigners: List<String> = emptyList(),
    ): List<Risk> {
        val risks = mutableListOf<Risk>()

        if (!simulationSucceeded) risks += risk(RiskFlag.SIMULATION_FAILED, Severity.DANGER)

        // A payment request should be signable by you alone. Extra required signers
        // mean the transaction only completes once someone else also signs — the
        // Solana Pay "unexpected signer" trap, or a co-sign you didn't expect.
        val others = otherSigners.filter { myWallet == null || it != myWallet }.distinct()
        if (others.isNotEmpty()) {
            risks += risk(RiskFlag.EXTRA_SIGNERS, Severity.WARN, others.size, "${others[0].take(4)}…${others[0].takeLast(4)}")
        }

        if (scan.malicious) {
            risks += Risk(RiskFlag.BLOCKED_MALICIOUS, Severity.DANGER, scan.reason ?: Localization.riskDetail(RiskFlag.BLOCKED_MALICIOUS, locale))
        }

        for (ix in instructions) {
            val dest = ix.destination

            if (dest != null && trust.isSanctioned(dest) || dest != null && dest in scan.sanctioned) {
                risks += risk(RiskFlag.SANCTIONED, Severity.DANGER, dest!!)
            }

            when (ix.kind) {
                InstructionKind.TOKEN_APPROVE ->
                    risks += if (ix.isUnlimitedApproval) risk(RiskFlag.UNLIMITED_APPROVAL, Severity.DANGER)
                    else risk(RiskFlag.LIMITED_APPROVAL, Severity.WARN)
                InstructionKind.SET_AUTHORITY -> risks += risk(RiskFlag.AUTHORITY_CHANGE, Severity.DANGER)
                InstructionKind.CLOSE_ACCOUNT -> if (dest != null && trust.level(dest) == TrustLevel.NEW) {
                    risks += risk(RiskFlag.ACCOUNT_CLOSE, Severity.WARN)
                }
                InstructionKind.ASSIGN_OWNER ->
                    // Re-assigning *your wallet* to a program is a total takeover;
                    // assigning some other fresh account is how PDAs/ATAs get created.
                    if (myWallet != null && ix.subject == myWallet) risks += risk(RiskFlag.WALLET_OWNER_CHANGE, Severity.DANGER)
                InstructionKind.DURABLE_NONCE -> risks += risk(RiskFlag.DURABLE_NONCE, Severity.WARN)
                else -> {}
            }

            if (dest != null && ix.kind != InstructionKind.ASSIGN_OWNER) {
                trust.lookalikeOf(dest)?.let { impersonated ->
                    risks += risk(RiskFlag.LOOKALIKE_ADDRESS, Severity.DANGER, impersonated)
                }
                if (trust.level(dest) == TrustLevel.NEW) {
                    risks += risk(RiskFlag.NEW_UNKNOWN_RECIPIENT, Severity.WARN)
                }
            }
        }

        return dedupe(risks).sortedByDescending { it.severity.ordinal }
    }

    /**
     * Judge the simulated effects. Drain = an outflow of at least [DRAIN_SHARE]
     * of the pre-balance of that asset; it is DANGER when the money goes to an
     * address you don't know and that has no history, WARN otherwise (moving
     * everything to your own new wallet is legitimate — save it as a contact).
     */
    fun assessEffects(deltas: List<BalanceDelta>, ctx: EffectContext, trust: AddressTrust): List<Risk> {
        val risks = mutableListOf<Risk>()
        val recipientTrust = ctx.primaryRecipient?.let { trust.level(it) }
        val recipientUnknown = recipientTrust == null || recipientTrust == TrustLevel.NEW
        val brandNew = ctx.recipientBrandNew == true && recipientUnknown

        if (brandNew) risks += risk(RiskFlag.BRAND_NEW_RECIPIENT, Severity.WARN)

        deltas.filter { it.owner == ctx.myWallet && it.rawAmount < 0 }.forEach { out ->
            val pre = ctx.preBalances[out.mint] ?: return@forEach
            if (pre <= 0) return@forEach
            val share = abs(out.rawAmount).toDouble() / pre.toDouble()
            if (share >= DRAIN_SHARE) {
                val severity = if (recipientUnknown && (brandNew || out.mint == NATIVE_SOL_MINT)) Severity.DANGER else Severity.WARN
                risks += risk(RiskFlag.DRAINS_BALANCE, severity, (share * 100).toInt(), out.symbol)
            }
        }

        val payer = ctx.feePayer
        if (payer != null && payer != ctx.myWallet) {
            risks += risk(RiskFlag.FOREIGN_FEE_PAYER, Severity.WARN, "${payer.take(4)}…${payer.takeLast(4)}")
        }

        // Fee sanity: a priority price far above the network median AND a fee that
        // actually hurts. A quiet network (median 0) makes any real fee "∞×", so the
        // multiplier is measured against at least 1 µlamport.
        val price = ctx.priorityPriceMicroLamports; val median = ctx.medianPriorityPriceMicroLamports; val pfee = ctx.priorityFeeLamports
        if (price != null && median != null && pfee != null && pfee > FEE_PAIN_LAMPORTS) {
            val mult = price.toDouble() / maxOf(median, 1L).toDouble()
            if (mult >= FEE_MULTIPLIER) {
                risks += risk(RiskFlag.FEE_EXCESSIVE, Severity.WARN, mult.toLong().toString(), lamportsToSol(pfee))
            }
        }
        return risks.sortedByDescending { it.severity.ordinal }
    }

    /** The same flag from repeated instructions reads once. */
    private fun dedupe(risks: List<Risk>): List<Risk> = risks.distinctBy { it.flag to it.detail }

    companion object {
        const val DRAIN_SHARE = 0.9
        const val FEE_MULTIPLIER = 10.0
        const val FEE_PAIN_LAMPORTS = 500_000L   // 0.0005 SOL: below this nobody cares

        private fun lamportsToSol(l: Long): String {
            val s = String.format(java.util.Locale.ROOT, "%.6f", l / 1_000_000_000.0)
            return s.trimEnd('0').trimEnd('.')
        }
    }
}
