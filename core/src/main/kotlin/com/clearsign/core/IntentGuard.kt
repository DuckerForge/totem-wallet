package com.clearsign.core

import kotlin.math.abs

/** What an agent claims a transaction will do. Parsed by the app from the Agent Gate link; the core only compares. */
data class AgentIntent(
    val action: String,                 // "transfer" | "swap" | "burn" | "other"
    val outMint: String? = null,        // mint address or symbol of what leaves the wallet
    val outAmount: Double? = null,      // in UI units (e.g. 0.1 SOL, 5 USDC)
    val inMint: String? = null,         // for swaps: what should come back
    val inAmount: Double? = null,       // for swaps: the expected amount (slippage-tolerant)
    val to: String? = null,             // for transfers: the recipient (base58)
    val agent: String? = null,          // who is asking (shown to the user)
    val reason: String? = null,         // one line of "why", shown verbatim
)

/**
 * The Agent Gate's brain: the declared intent against the simulated effect. A hallucinating
 * or compromised agent cannot lie past this, the receipt is what the network says will happen.
 * Returns one risk: [RiskFlag.AGENT_INTENT_OK] (INFO) when the claim holds, or
 * [RiskFlag.AGENT_INTENT_MISMATCH] (DANGER, blocks approval) listing every discrepancy.
 */
object IntentGuard {
    private const val WSOL = "So11111111111111111111111111111111111111112"

    /** Instruction-level risks an agent is never allowed to leave undeclared. */
    private val FORBIDDEN = setOf(
        RiskFlag.UNLIMITED_APPROVAL, RiskFlag.LIMITED_APPROVAL, RiskFlag.AUTHORITY_CHANGE,
        RiskFlag.WALLET_OWNER_CHANGE, RiskFlag.DURABLE_NONCE,
    )

    fun check(
        intent: AgentIntent, receipt: Receipt, myWallet: String,
        locale: String = "en", tolerance: Double = 0.01, inflowSlippage: Double = 0.03,
    ): Risk {
        val it = locale == "it"
        val problems = ArrayList<String>()
        val outs = receipt.outflows.filter { d -> d.rawAmount < 0 }
        val ins = receipt.inflows.filter { d -> d.rawAmount > 0 && !d.createdAccount }
        val action = intent.action.lowercase()

        // Nothing moved because nothing was simulated. Comparing a claim against an empty list
        // finds every field missing and calls it a lie: that is how a dropped connection became
        // "the agent is not telling the truth" and stopped the loop. Two silences: when the node ran
        // it and it failed, the receipt already carries the reason and that is the answer (repeating
        // "did not answer" hid the cause and made the loop retry the same broken swap); when the
        // node could not be asked, the claim is simply unverifiable.
        if (outs.isEmpty() && ins.isEmpty()) {
            receipt.risks.firstOrNull { r -> r.flag == RiskFlag.SIMULATION_FAILED }?.let { r ->
                return Risk(RiskFlag.SIMULATION_FAILED, Severity.DANGER, r.detail)
            }
            if (receipt.risks.any { r -> r.flag == RiskFlag.SIMULATION_UNAVAILABLE }) {
                return Risk(
                    RiskFlag.SIMULATION_UNAVAILABLE,
                    Severity.DANGER,
                    if (it) "non posso verificare quello che dice l'agente: la simulazione non ha risposto"
                    else "cannot check what the agent says: the simulation did not answer",
                )
            }
        }

        fun fmt(d: BalanceDelta) = trim(abs(d.uiAmount)) + " " + d.symbol
        fun same(claim: String?, d: BalanceDelta): Boolean {
            if (claim == null) return false
            if (claim.equals(d.mint, true) || claim.equals(d.symbol, true)) return true
            val claimSol = claim.equals("SOL", true) || claim == WSOL
            val dSol = d.mint == NATIVE_SOL_MINT || d.mint == WSOL || d.symbol.equals("SOL", true)
            return claimSol && dSol
        }
        /**
         * How much more SOL than declared may leave before it counts as a lie. "Swap 0.031 SOL"
         * spends 0.036: network fee, priority fee, and the rent for the accounts it opens, about
         * 0.002 SOL each, two for a swap through wrapped SOL. A flat 0.003 refused every purchase of
         * a new coin as a lie, and the loop read that as "the person said no". The numbers now come
         * from the simulation itself; a transfer to a stranger is still caught to the lamport.
         */
        fun slackFor(d: BalanceDelta, amount: Double): Double {
            val isSol = d.mint == NATIVE_SOL_MINT || d.mint == WSOL
            if (!isSol) return amount * tolerance
            val fee = maxOf(receipt.feeLamports, receipt.stats?.totalFeeLamports ?: 0L)
            val rent = receipt.distributions.filter { s -> s.isNewAccount }.sumOf { s -> abs(s.delta.rawAmount) }
            return amount * tolerance + (fee + rent) / 1e9 + 0.0005
        }

        // 1. Every outflow must be the declared one (amount within slack); anything else is undeclared.
        val declaredOut = outs.firstOrNull { same(intent.outMint, it) }
        for (d in outs) {
            if (d === declaredOut) {
                val want = intent.outAmount
                if (want != null && abs(abs(d.uiAmount) - want) > slackFor(d, want)) {
                    problems += if (it) "esce ${fmt(d)} invece di ${trim(want)} ${d.symbol}" else "sends ${fmt(d)} instead of ${trim(want)} ${d.symbol}"
                }
            } else {
                problems += if (it) "uscita non dichiarata: −${fmt(d)}" else "undeclared outflow: −${fmt(d)}"
            }
        }
        if (intent.outMint != null && declaredOut == null && action != "other") {
            problems += if (it) "nessuna uscita di ${intent.outMint}" else "no outflow of ${intent.outMint}"
        }

        // 2. Per-action expectations.
        when (action) {
            "transfer" -> {
                val to = intent.to
                if (to != null) {
                    val actual = receipt.primaryRecipient
                    val reaches = actual == to || receipt.distributions.any { s -> s.address == to }
                    if (!reaches) problems += if (it) "destinatario ${short(actual)} invece di ${short(to)}" else "recipient ${short(actual)} instead of ${short(to)}"
                }
                for (d in ins) if (!(d.mint == NATIVE_SOL_MINT || d.mint == WSOL)) {
                    problems += if (it) "entrata inattesa: +${fmt(d)}" else "unexpected inflow: +${fmt(d)}"
                }
            }
            "swap" -> {
                val got = ins.firstOrNull { same(intent.inMint, it) }
                if (intent.inMint != null && got == null) {
                    problems += if (it) "nessuna entrata di ${intent.inMint}" else "no inflow of ${intent.inMint}"
                } else if (got != null && intent.inAmount != null && got.uiAmount < intent.inAmount * (1 - inflowSlippage)) {
                    problems += if (it) "riceve solo ${fmt(got)} (atteso ≥ ${trim(intent.inAmount * (1 - inflowSlippage))})" else "receives only ${fmt(got)} (expected ≥ ${trim(intent.inAmount * (1 - inflowSlippage))})"
                }
            }
            "burn" -> {
                val rcpt = receipt.primaryRecipient
                if (rcpt != null && rcpt != myWallet) {
                    problems += if (it) "manda fondi a ${short(rcpt)} (un burn non ha destinatari)" else "sends funds to ${short(rcpt)} (a burn has no recipient)"
                }
                for (d in ins) if (!(d.mint == NATIVE_SOL_MINT || d.mint == WSOL)) {
                    problems += if (it) "entrata inattesa: +${fmt(d)}" else "unexpected inflow: +${fmt(d)}"
                }
            }
            else -> { /* "other": rule 1 already forbids any undeclared outflow */ }
        }

        // 3. Dangerous instructions an agent must never smuggle in.
        for (r in receipt.risks) if (r.flag in FORBIDDEN) {
            problems += if (it) "contiene un'operazione non dichiarata (${name(r.flag, true)})" else "contains an undeclared operation (${name(r.flag, false)})"
        }

        val claim = summary(intent, it)
        return if (problems.isEmpty()) {
            Risk(RiskFlag.AGENT_INTENT_OK, Severity.INFO, Localization.riskDetail(RiskFlag.AGENT_INTENT_OK, locale, intent.agent ?: (if (it) "sconosciuto" else "unknown"), claim))
        } else {
            Risk(RiskFlag.AGENT_INTENT_MISMATCH, Severity.DANGER, Localization.riskDetail(RiskFlag.AGENT_INTENT_MISMATCH, locale, claim, problems.distinct().joinToString("; ")))
        }
    }

    /** "swap 0.1 SOL → USDC", "transfer 5 USDC → 8ncU…uz8z", "burn 1000 RKS", "other (no outflow)". */
    fun summary(intent: AgentIntent, it: Boolean = false): String {
        val out = intent.outAmount?.let { a -> trim(a) + " " }.orEmpty() + (intent.outMint ?: "")
        return when (intent.action.lowercase()) {
            "transfer" -> (if (it) "invio " else "transfer ") + out + (intent.to?.let { " → " + short(it) } ?: "")
            "swap" -> "swap $out → " + (intent.inAmount?.let { a -> trim(a) + " " }.orEmpty() + (intent.inMint ?: "?"))
            "burn" -> "burn $out"
            else -> if (intent.outMint != null) (if (it) "altro: " else "other: ") + out else if (it) "altro (nessuna uscita)" else "other (no outflow)"
        }
    }

    private fun name(f: RiskFlag, it: Boolean) = when (f) {
        RiskFlag.UNLIMITED_APPROVAL -> if (it) "delega illimitata" else "unlimited approval"
        RiskFlag.LIMITED_APPROVAL -> if (it) "delega sui token" else "token approval"
        RiskFlag.AUTHORITY_CHANGE -> if (it) "cambio di autorità" else "authority change"
        RiskFlag.WALLET_OWNER_CHANGE -> if (it) "cambio proprietario del wallet" else "wallet owner change"
        RiskFlag.DURABLE_NONCE -> "durable nonce"
        else -> f.name.lowercase()
    }

    private fun short(a: String?): String = when {
        a == null -> "?"
        a.length <= 12 -> a
        else -> a.take(4) + "…" + a.takeLast(4)
    }

    private fun trim(v: Double): String {
        val s = String.format(java.util.Locale.ROOT, "%.6f", v).trimEnd('0').trimEnd('.')
        return if (s.isEmpty() || s == "-0") "0" else s
    }
}
