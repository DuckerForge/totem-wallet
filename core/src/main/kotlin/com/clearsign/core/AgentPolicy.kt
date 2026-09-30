package com.clearsign.core

import kotlin.math.abs

/**
 * What an agent may do with its budget unattended. The budget is a separate hot key with a capped
 * balance (the hard limit); this policy is the soft limit the wallet checks before signing with it.
 * It guards against the agent (bad addresses, prompt injection, runaway loops), not against a
 * compromised phone. Not enforced on chain.
 */
enum class AgentMode { OFF, READ_ONLY, AUTONOMOUS, ASK_ALWAYS }

data class AgentPolicy(
    val mode: AgentMode,
    /** Cap per transaction, in SOL-equivalent lamports. */
    val perTxLamports: Long,
    /** Cap over any rolling 24 hours, same unit. */
    val dailyLamports: Long,
    /** Below this the signature is silent; from here up the user is asked. */
    val askAboveLamports: Long,
    val allowedPrograms: Set<String>,
    /** Mints the agent may move out AND may acquire. No long tail. */
    val allowedMints: Set<String>,
    /**
     * Trade any coin, not just [allowedMints]. Caps, destinations, rate limit and the vault rule
     * still apply; whether an unknown coin can be sold back is checked before a proposal gets here.
     */
    val allowAnyMint: Boolean = false,
    /** Wallets that may receive value: the owner, the budget, the address book. */
    val allowedDestinations: Set<String>,
    val maxTxPerHour: Int,
    val expiresAt: Long,
) {
    companion object {
        const val SYSTEM = "11111111111111111111111111111111"
        const val TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        const val TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
        const val ATA = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
        const val MEMO = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"
        const val COMPUTE_BUDGET = "ComputeBudget111111111111111111111111111111"
        const val JUPITER_V6 = "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4"
        const val USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val WSOL = "So11111111111111111111111111111111111111112"

        val BASE_PROGRAMS = setOf(SYSTEM, TOKEN, TOKEN_2022, ATA, MEMO, COMPUTE_BUDGET, JUPITER_V6)
        /** Programs whose presence makes a transaction an exchange rather than a payment. */
        val EXCHANGE_PROGRAMS = setOf(JUPITER_V6)
        val BASE_MINTS = setOf(NATIVE_SOL_MINT, WSOL, USDC)

        /** The "Prudent" preset: a fifth of the cap per move, half per day. */
        fun prudent(capLamports: Long, owner: String, envelope: String, contacts: Collection<String>, expiresAt: Long) = AgentPolicy(
            mode = AgentMode.AUTONOMOUS,
            perTxLamports = capLamports / 5, dailyLamports = capLamports / 2, askAboveLamports = capLamports / 5,
            allowedPrograms = BASE_PROGRAMS, allowedMints = BASE_MINTS, allowAnyMint = true,
            allowedDestinations = (contacts + owner + envelope).toSet(),
            maxTxPerHour = 20, expiresAt = expiresAt,
        )

        /** The "Trader" preset: half the cap per move, all of it per day, faster. */
        fun trader(capLamports: Long, owner: String, envelope: String, contacts: Collection<String>, expiresAt: Long) =
            prudent(capLamports, owner, envelope, contacts, expiresAt).copy(
                perTxLamports = capLamports / 2, dailyLamports = capLamports, askAboveLamports = capLamports / 2, maxTxPerHour = 60,
            )
    }
}

/** What the agent already did, from the wallet's own ledger. */
data class SpendHistory(val spentLast24hLamports: Long, val txLastHour: Int)

/** The verdict. [code] is the stable machine-readable half an agent can branch on; [reason] is the human half, in the user's language. */
sealed class Decision(val code: String) {
    object Auto : Decision("auto")
    class Ask(code: String, val reason: String) : Decision(code) {
        override fun toString() = "Ask($code: $reason)"
    }
    /** [text] is the reason as a key plus arguments, so a stored refusal can be shown in another language. */
    class Refuse(code: String, val reason: String, val text: Refusals.Text? = null) : Decision(code) {
        override fun toString() = "Refuse($code: $reason)"
    }
}

/**
 * Policy refusals as keys and arguments, so a stored one follows the current app language. Each
 * sentence is kept in both languages and parsed back from either, which covers old rows that stored
 * only the sentence. Intent check and risk engine reasons are not here; they stay as written.
 */
object Refusals {
    data class Text(val key: String, val args: List<String> = emptyList())

    private val SAID = mapOf(
        "paused" to ("l'agente è in pausa" to "the agent is paused"),
        "expired" to ("la paghetta è scaduta" to "the budget has expired"),
        "read_only" to ("l'agente è in sola lettura" to "the agent is read-only"),
        "vault_takes" to ("toccherebbe il tuo conto principale" to "it would take from your main account"),
        "vault_writable" to ("il tuo conto principale è modificabile da questa transazione" to "your main account is writable in this transaction"),
        "rate" to ("ritmo superato: {0} operazioni nell'ultima ora" to "rate exceeded: {0} transactions in the last hour"),
        // Before "destination": when parsing back, the plain form would match this one too.
        "destination_route" to ("{0} non è fra i destinatari ammessi (rotta: {1})" to "{0} is not an allowed destination (route: {1})"),
        "destination" to ("{0} non è fra i destinatari ammessi" to "{0} is not an allowed destination"),
        "asset_out" to ("{0} non è fra gli asset ammessi" to "{0} is not an allowed asset"),
        "rate_quality" to ("non è uno scambio: scambia {0} SOL e riceve l'equivalente di {1} SOL" to "this is not an exchange: swaps {0} SOL and gets back the equivalent of {1} SOL"),
    )

    /** The sentence in [locale], or null for a key this table does not know. */
    fun say(t: Text, locale: String): String? {
        val (itText, enText) = SAID[t.key] ?: return null
        var s = if (locale == "it") itText else enText
        t.args.forEachIndexed { i, a -> s = s.replace("{$i}", a) }
        return s
    }

    private val READ = SAID.flatMap { (key, pair) -> listOf(pair.first, pair.second).map { key to pattern(it) } }

    private fun pattern(template: String): Regex {
        // Escape both braces: the JVM accepts a bare "}", Android's ICU regex throws on it (crash on open).
        val parts = template.split(Regex("\\{\\d\\}"))
        return Regex("^" + parts.joinToString("(.+?)") { Regex.escape(it) } + "$")
    }

    /** A stored sentence back to its key, in either language, or null when it is not one of these. */
    fun read(note: String): Text? {
        val s = note.trim()
        for ((key, re) in READ) re.find(s)?.let { m -> return Text(key, m.groupValues.drop(1)) }
        return null
    }
}

/**
 * A swap back into the same pocket spends nothing, so it stays off the daily cap. Counting turnover
 * charged a buy and its sale twice; on a small budget the agent got one trip, then asked for a
 * fingerprint on every move (measured 17 Sep). Needs [isExchange] and a different coin coming back;
 * disguised transfers fail rule 5, or rule 10 when the route is ours. Transfers out and the per-move
 * cap are not exempt.
 */
fun staysInPocket(receipt: Receipt, policy: AgentPolicy, routeIsOurs: Boolean = false): Boolean {
    if (!isExchange(receipt, policy, routeIsOurs)) return false
    val outs = receipt.outflows.filter { d -> d.rawAmount < 0 }
    val ins = receipt.inflows.filter { d -> d.rawAmount > 0 && !d.createdAccount }
    if (outs.isEmpty() || ins.isEmpty()) return false
    return ins.any { d -> outs.none { o -> o.mint == d.mint } }
}

/**
 * An exchange, not a payment. Agent bytes qualify only through a listed exchange program. Routes we
 * requested (Jupiter, this budget as taker) are judged by shape, something out and a different coin
 * back into the same pocket, because Ultra sometimes fills via a market maker with no aggregator in
 * the transaction and rule 5 refused those sales. Rule 10 still compares what leaves with what returns.
 */
internal fun isExchange(receipt: Receipt, policy: AgentPolicy, routeIsOurs: Boolean): Boolean {
    val programs = receipt.stats?.programs.orEmpty()
    if (programs.any { p -> p in AgentPolicy.EXCHANGE_PROGRAMS && p in policy.allowedPrograms }) return true
    if (!routeIsOurs) return false
    val outs = receipt.outflows.filter { d -> d.rawAmount < 0 }
    val ins = receipt.inflows.filter { d -> d.rawAmount > 0 && !d.createdAccount }
    return outs.isNotEmpty() && ins.any { d -> outs.none { o -> o.mint == d.mint } }
}

object PolicyEngine {
    /**
     * Decide on a transaction the agent submitted, from the simulated receipt (never the agent's
     * claim) and the intent check. [valueLamports] prices one outflow in SOL-equivalent lamports, null
     * when unknown; unknown value is never signed silently. Rules run in order, first match wins, and
     * refusals come before questions so the user is never asked to approve a mismatch.
     */
    fun decide(
        policy: AgentPolicy, receipt: Receipt, guard: Risk, history: SpendHistory,
        valueLamports: (BalanceDelta) -> Long?, now: Long = System.currentTimeMillis(), locale: String = "en",
        /** The Seed Vault account. The agent may pay it, never spend from it. */
        vault: String? = null,
        /** Accounts this transaction can modify, from the decoded message. */
        writableKeys: Set<String> = emptySet(),
        /**
         * True when these bytes come from a route we asked for (Jupiter, this budget as taker), not
         * from an agent. See [isExchange]: it changes how an exchange is recognized, not what it may do.
         */
        routeIsOurs: Boolean = false,
    ): Decision {
        val it = locale == "it"
        fun refuse(code: String, key: String, vararg args: String): Decision.Refuse {
            val t = Refusals.Text(key, args.toList())
            return Decision.Refuse(code, Refusals.say(t, locale) ?: key, t)
        }

        // 1. Not running.
        if (policy.mode == AgentMode.OFF) return refuse("paused", "paused")
        if (now > policy.expiresAt) return refuse("expired", "expired")
        if (policy.mode == AgentMode.READ_ONLY) return refuse("read_only", "read_only")

        // Read once: the rules below and the drain exemption all need these.
        val programs = receipt.stats?.programs.orEmpty()
        val exchange = isExchange(receipt, policy, routeIsOurs)
        val outs = receipt.outflows.filter { d -> d.rawAmount < 0 }
        val ins = receipt.inflows.filter { d -> d.rawAmount > 0 && !d.createdAccount }
        /**
         * An exchange with something coming back to this pocket. [RiskFlag.DRAINS_BALANCE] (almost all of
         * one asset out) is a drainer on a person's wallet but a normal trade on the budget, where the
         * slice is most of the balance (0.033 of 0.036 SOL). Unexempted, it flagged every trade DANGER and
         * stopped the loop. Safe to exempt: a swap that takes the money still fails rule 5, 10 or 11.
         */
        val exchangeWithReturn = exchange && outs.isNotEmpty() && ins.isNotEmpty()

        // 2. The claim does not hold, or the transaction is dangerous on its own.
        if (guard.flag == RiskFlag.AGENT_INTENT_MISMATCH) return Decision.Refuse("intent_mismatch", guard.detail)
        receipt.risks.firstOrNull { r ->
            r.severity == Severity.DANGER && !(r.flag == RiskFlag.DRAINS_BALANCE && exchangeWithReturn)
        }?.let { r ->
            // No blind signing, but distinct codes: no simulation is worth a retry, a failed run is a
            // bad proposal to skip, anything else is danger.
            val code = when (r.flag) {
                RiskFlag.SIMULATION_UNAVAILABLE -> "no_simulation"
                RiskFlag.SIMULATION_FAILED -> "sim_failed"
                else -> "danger"
            }
            return Decision.Refuse(code, r.detail)
        }

        // 3. Vault. The budget key cannot make the vault pay, so a vault that only receives is fine
        //    ("send the winnings home"). A vault losing value, or writable without receiving, is refused.
        if (vault != null) {
            val vaultDeltas = receipt.deltas.filter { d -> d.owner == vault }
            if (vaultDeltas.any { d -> d.rawAmount < 0 }) {
                return refuse("vault_touched", "vault_takes")
            }
            if (vault in writableKeys && vaultDeltas.none { d -> d.rawAmount > 0 }) {
                return refuse("vault_touched", "vault_writable")
            }
        }

        // 4. A loop, or an injected "do it again".
        if (history.txLastHour >= policy.maxTxPerHour) {
            return refuse("rate", "rate", policy.maxTxPerHour.toString())
        }

        // 5. An exchange pays a pool, not a wallet, so the destination list only governs transfers.
        //    An exchange must actually invoke an allowed exchange program: a plain transfer dressed
        //    up with a dust inflow does not qualify.
        if (!exchange) {
            val payees = receipt.distributions.filter { s -> !s.isNewAccount }.map { s -> s.address } +
                listOfNotNull(receipt.primaryRecipient)
            for (a in payees.distinct()) {
                if (a in policy.allowedDestinations || a in policy.allowedPrograms) continue
                // If something leaves and a different coin comes back, the payee is almost always a swap
                // route. Keep the "destination" code (tests pin it, it catches transfers posing as swaps)
                // but name the programs seen, so nobody has to guess a program id into the allow list.
                val swapShaped = outs.isNotEmpty() && ins.any { d -> outs.none { o -> o.mint == d.mint } }
                val seen = if (!swapShaped) "" else programs.filter { p -> p !in AgentPolicy.BASE_PROGRAMS }.take(2).joinToString(", ") { short(it) }
                return if (seen.isEmpty()) refuse("destination", "destination", short(a)) else refuse("destination", "destination_route", short(a), seen)
            }
        }

        // 6. Assets: nothing unlisted leaves, and buying something new is the human's call.
        for (d in outs) if (!allowedMint(policy, d)) {
            return refuse("asset_out", "asset_out", d.symbol)
        }
        for (d in ins) if (!allowedMint(policy, d)) {
            return Decision.Ask("asset_in", if (it) "vuole acquistare ${d.symbol}, un token non in lista" else "wants to acquire ${d.symbol}, a token not on the list")
        }

        // 7. Unknown program. Skipped on an exchange we built, as in rule 5: Ultra picks the route live,
        //    so its programs cannot be listed ahead. Rules 2, 10 and the caps check the money. See [isExchange].
        if (!(routeIsOurs && exchange)) {
            programs.firstOrNull { p -> p !in policy.allowedPrograms }?.let { p ->
                return Decision.Ask("program", if (it) "usa un programma non in lista: ${short(p)}" else "uses a program not on the list: ${short(p)}")
            }
        }

        // 8. Unwind: a held coin sold back into base money, landing in the same pocket.
        val unwind = isUnwind(policy, receipt, routeIsOurs)

        // 9. Value. Nothing of unknown worth is signed silently, except an unwind: what arrives
        //    there is SOL, which always has a price.
        outs.firstOrNull { d -> valueLamports(d) == null }?.let { d ->
            if (!unwind) return Decision.Ask("unknown_value", if (it) "non so quanto vale ${trim(abs(d.uiAmount))} ${d.symbol}" else "cannot value ${trim(abs(d.uiAmount))} ${d.symbol}")
        }
        val total = outs.sumOf { d -> valueLamports(d) ?: 0L }
        // 10. An exchange must return most of its value; a terrible rate is a transfer hidden in a swap.
        if (exchange) {
            ins.firstOrNull { d -> valueLamports(d) == null }?.let { d ->
                if (!unwind) return Decision.Ask("unknown_value", if (it) "non so quanto vale ciò che riceve (${d.symbol})" else "cannot value what comes back (${d.symbol})")
            }
            val back = ins.sumOf { d -> valueLamports(d) ?: 0L }

            // Only what went into the swap: a first buy also pays the fee and ~0.002 SOL rent per new
            // account. Counted in, 0.0312 SOL into coins worth 0.0311 read as 0.0359 out, 0.0303 back (84%).
            val rent = receipt.distributions.filter { s -> s.isNewAccount }.sumOf { s -> abs(s.delta.rawAmount) }
            val swapped = (total - rent - receipt.feeLamports).coerceAtLeast(1L)

            if (back < swapped / 2) {
                return refuse("rate_quality", "rate_quality", sol(swapped), sol(back))
            }
            // Selling out often costs more than 10% in spread and impact. Unwinds skip the question:
            // the loop runs unattended, so asking would mean refusing.
            if (!unwind && back < swapped * 9 / 10) {
                return Decision.Ask("rate", if (it) "cambio sfavorevole: scambia ${sol(swapped)} SOL e riceve l'equivalente di ${sol(back)} SOL" else "poor rate: swaps ${sol(swapped)} SOL and gets back the equivalent of ${sol(back)} SOL")
            }
        }
        // 11. Caps bound what the budget can lose; unwinds are exempt. Capping them left a position
        //     unsellable exactly when it had grown enough to sell.
        if (!unwind) {
            if (total > policy.perTxLamports) {
                return Decision.Ask("per_tx", if (it) "${sol(total)} SOL supera il tetto per operazione di ${sol(policy.perTxLamports)} SOL" else "${sol(total)} SOL is over the per-transaction cap of ${sol(policy.perTxLamports)} SOL")
            }
            if (history.spentLast24hLamports + total > policy.dailyLamports) {
                return Decision.Ask("daily", if (it) "supererebbe il tetto giornaliero di ${sol(policy.dailyLamports)} SOL" else "would exceed the daily cap of ${sol(policy.dailyLamports)} SOL")
            }
        }
        // "Always ask" covers every move, unwinds included.
        if (policy.mode == AgentMode.ASK_ALWAYS) return Decision.Ask("ask_always", if (it) "hai scelto di essere sempre interpellato" else "you chose to always be asked")
        if (!unwind && total > policy.askAboveLamports) {
            return Decision.Ask("silent_threshold", if (it) "${sol(total)} SOL supera la soglia silenziosa di ${sol(policy.askAboveLamports)} SOL" else "${sol(total)} SOL is over the silent threshold of ${sol(policy.askAboveLamports)} SOL")
        }
        return Decision.Auto
    }

    /**
     * Unwind: a coin the budget holds swapped back into base money. Caps bound losses and a sale loses
     * nothing; capped sales let the agent buy a coin it could not sell, and made a stop-loss wait 90 s
     * for a fingerprint. Narrow on purpose: a real exchange ([isExchange]), no base money out, only base
     * money in. [receipt] is the simulated one.
     */
    fun isUnwind(policy: AgentPolicy, receipt: Receipt, routeIsOurs: Boolean = false): Boolean {
        if (!isExchange(receipt, policy, routeIsOurs)) return false
        val outs = receipt.outflows.filter { d -> d.rawAmount < 0 }
        val ins = receipt.inflows.filter { d -> d.rawAmount > 0 && !d.createdAccount }
        if (outs.isEmpty() || ins.isEmpty()) return false
        if (outs.any { d -> isBase(d.mint) }) return false
        return ins.all { d -> isBase(d.mint) }
    }

    /** The money a budget is kept in: SOL, wrapped SOL, USDC. */
    private fun isBase(mint: String) = mint in AgentPolicy.BASE_MINTS

    private fun allowedMint(p: AgentPolicy, d: BalanceDelta): Boolean =
        p.allowAnyMint ||
            d.mint in p.allowedMints || d.symbol.equals("SOL", true) && (NATIVE_SOL_MINT in p.allowedMints || AgentPolicy.WSOL in p.allowedMints)

    private fun short(a: String) = if (a.length > 10) a.take(4) + "…" + a.takeLast(4) else a
    private fun sol(l: Long) = trim(l / 1e9)
    private fun trim(v: Double): String {
        val s = String.format(java.util.Locale.ROOT, "%.6f", v).trimEnd('0').trimEnd('.')
        return if (s.isEmpty() || s == "-0") "0" else s
    }
}

/**
 * The other half of a machine-readable verdict: [IntentGuard.summary] renders what the agent
 * said, this renders what the simulation says will happen, one line each, side by side.
 */
object Effects {
    fun summary(receipt: Receipt, locale: String = "en"): String {
        val it = locale == "it"
        val parts = ArrayList<String>()
        receipt.outflows.filter { d -> d.rawAmount < 0 }.forEach { d -> parts += "−" + amount(d) }
        receipt.inflows.filter { d -> d.rawAmount > 0 && !d.createdAccount }.forEach { d -> parts += "+" + amount(d) }
        if (parts.isEmpty()) parts += if (it) "nessuno spostamento di valore" else "no value moves"
        val to = receipt.primaryRecipient?.let { a -> (if (it) " a " else " to ") + short(a) }.orEmpty()
        val fee = (if (it) ", commissione " else ", fee ") + trim(receipt.feeLamports / 1e9) + " SOL"
        return parts.joinToString(", ") + to + fee
    }

    private fun amount(d: BalanceDelta) = trim(abs(d.uiAmount)) + " " + d.symbol
    private fun short(a: String) = if (a.length > 10) a.take(4) + "…" + a.takeLast(4) else a
    private fun trim(v: Double): String {
        val s = String.format(java.util.Locale.ROOT, "%.6f", v).trimEnd('0').trimEnd('.')
        return if (s.isEmpty() || s == "-0") "0" else s
    }
}
