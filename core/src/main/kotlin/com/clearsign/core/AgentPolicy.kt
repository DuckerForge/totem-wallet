package com.clearsign.core

import kotlin.math.abs

/**
 * The collar: what an agent may do with its budget on its own. The budget is a separate hot
 * key with a capped balance, the hard boundary that holds even if the agent's machine is
 * compromised; this policy is the soft boundary on top, applied by the wallet before it signs
 * with that key. It protects against the agent (hallucinated addresses, prompt injection,
 * runaway loops), not against a compromised phone, and it is not enforced on chain.
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
     * Let the agent trade any coin instead of a fixed short list. The list was six hand-written
     * symbols of which the collar allowed two, so the agent could buy nothing. Opening it changes
     * nothing else: caps, destination list, rate limit and untouchable vault still apply. The
     * caps protect the money, not a list of symbols; for an unknown coin what matters is whether it can be sold back, checked before a proposal gets here.
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
    class Refuse(code: String, val reason: String) : Decision(code) {
        override fun toString() = "Refuse($code: $reason)"
    }
}

/**
 * A round trip home has spent nothing. The daily cap limits what can leave in a day, but it
 * counted turnover: buy and sell back left the pocket as it was and ate the cap twice, so on a
 * small budget the agent made one trip and then asked for the print at every move (measured
 * 17 Sep). The condition is [isExchange], so it cannot be gamed: a different coin must come back
 * into the same pocket; a disguised transfer is stopped by rule five, or ten when the route is ours. Transfers out and the per-move cap are not exempt.
 */
fun staysInPocket(receipt: Receipt, policy: AgentPolicy, routeIsOurs: Boolean = false): Boolean {
    if (!isExchange(receipt, policy, routeIsOurs)) return false
    val outs = receipt.outflows.filter { d -> d.rawAmount < 0 }
    val ins = receipt.inflows.filter { d -> d.rawAmount > 0 && !d.createdAccount }
    if (outs.isEmpty() || ins.isEmpty()) return false
    return ins.any { d -> outs.none { o -> o.mint == d.mint } }
}

/**
 * An exchange, not a payment. An agent's bytes count as one when they pass through a listed
 * exchange program. Bytes we asked for ourselves (route chosen here, taker is this budget, answer
 * from Jupiter) are judged by shape: Ultra sometimes fills a sale through a market maker with no
 * aggregator in the transaction, and rule five refused it (19 Sep: "sell now" refused, the second
 * tap passed because the route changed, a die, not a collar). Shape means something out and a different coin back into the same pocket; rule ten still compares what leaves with what returns.
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
     * Decide what to do with a transaction the agent submitted, from the simulated receipt (never
     * the agent's claim) and the intent check. [valueLamports] prices one outflow in SOL-equivalent
     * lamports, null when unknown, and an unknown value is never signed silently. Rules run in
     * order, the first that fires wins, and a refusal beats a question: never ask a person to confirm a lie.
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

        // 1. Not running.
        if (policy.mode == AgentMode.OFF) return Decision.Refuse("paused", if (it) "l'agente è in pausa" else "the agent is paused")
        if (now > policy.expiresAt) return Decision.Refuse("expired", if (it) "la paghetta è scaduta" else "the budget has expired")
        if (policy.mode == AgentMode.READ_ONLY) return Decision.Refuse("read_only", if (it) "l'agente è in sola lettura" else "the agent is read-only")

        // What the transaction is, read once: the rules below all need it, and so
        // does the drain exemption immediately after.
        val programs = receipt.stats?.programs.orEmpty()
        val exchange = isExchange(receipt, policy, routeIsOurs)
        val outs = receipt.outflows.filter { d -> d.rawAmount < 0 }
        val ins = receipt.inflows.filter { d -> d.rawAmount > 0 && !d.createdAccount }
        /**
         * An exchange where something comes back into this same pocket. [RiskFlag.DRAINS_BALANCE] means
         * "this sends out almost everything you have of one thing": on a person's wallet the shape of a
         * drainer, on the budget the shape of an ordinary trade, since the slice is most of it by design.
         * It fired on every coin as DANGER and switched the loop off for the night (0.033 of a 0.036 SOL
         * balance into a swap). Exempting it costs nothing: a swap that takes the money fails the rules that measure, allowed program (5), most of it back (10), the caps (11).
         */
        val exchangeWithReturn = exchange && outs.isNotEmpty() && ins.isNotEmpty()

        // 2. The claim does not hold, or the transaction is dangerous on its own.
        if (guard.flag == RiskFlag.AGENT_INTENT_MISMATCH) return Decision.Refuse("intent_mismatch", guard.detail)
        receipt.risks.firstOrNull { r ->
            r.severity == Severity.DANGER && !(r.flag == RiskFlag.DRAINS_BALANCE && exchangeWithReturn)
        }?.let { r ->
            // A transaction nobody could simulate is refused like anything else, this wallet does not
            // sign blind, but under its own name: "the network was down" is something to retry, "the
            // node ran it and it failed" is a bad proposal to skip, "this moves your money elsewhere" is neither.
            val code = when (r.flag) {
                RiskFlag.SIMULATION_UNAVAILABLE -> "no_simulation"
                RiskFlag.SIMULATION_FAILED -> "sim_failed"
                else -> "danger"
            }
            return Decision.Refuse(code, r.detail)
        }

        // 3. The vault is out of bounds. The budget key cannot authorize the vault to pay, so a
        //    vault that only receives is fine ("send the winnings home"); a vault losing value, or
        //    writable without receiving, means this transaction reaches for the wrong pocket.
        if (vault != null) {
            val vaultDeltas = receipt.deltas.filter { d -> d.owner == vault }
            if (vaultDeltas.any { d -> d.rawAmount < 0 }) {
                return Decision.Refuse("vault_touched", if (it) "toccherebbe il tuo conto principale" else "it would take from your main account")
            }
            if (vault in writableKeys && vaultDeltas.none { d -> d.rawAmount > 0 }) {
                return Decision.Refuse("vault_touched", if (it) "il tuo conto principale è modificabile da questa transazione" else "your main account is writable in this transaction")
            }
        }

        // 4. A loop, or an injected "do it again".
        if (history.txLastHour >= policy.maxTxPerHour) {
            return Decision.Refuse("rate", if (it) "ritmo superato: ${policy.maxTxPerHour} operazioni nell'ultima ora" else "rate exceeded: ${policy.maxTxPerHour} transactions in the last hour")
        }

        // 5. An exchange pays a pool, not a wallet, so the destination list only governs transfers.
        //    An exchange must actually invoke an allowed exchange program: a plain transfer dressed
        //    up with a dust inflow does not qualify.
        if (!exchange) {
            val payees = receipt.distributions.filter { s -> !s.isNewAccount }.map { s -> s.address } +
                listOfNotNull(receipt.primaryRecipient)
            for (a in payees.distinct()) {
                if (a in policy.allowedDestinations || a in policy.allowedPrograms) continue
                // When something leaves and another coin comes back, this refusal is almost always a
                // route, not a payee: the pool cashing a swap belongs in no destination list. The code
                // stays "destination", which guards against transfers disguised as swaps and is what the
                // tests pin down, but the message now names the programs seen: without that the only way
                // to make a sale pass would be guessing a program id into a safety list.
                val swapShaped = outs.isNotEmpty() && ins.any { d -> outs.none { o -> o.mint == d.mint } }
                val seen = if (!swapShaped) "" else programs.filter { p -> p !in AgentPolicy.BASE_PROGRAMS }.take(2).joinToString(", ") { short(it) }
                val extra = if (seen.isEmpty()) "" else if (it) " (rotta: $seen)" else " (route: $seen)"
                return Decision.Refuse("destination", (if (it) "${short(a)} non è fra i destinatari ammessi" else "${short(a)} is not an allowed destination") + extra)
            }
        }

        // 6. Assets: nothing unlisted leaves, and buying something new is the human's call.
        for (d in outs) if (!allowedMint(policy, d)) {
            return Decision.Refuse("asset_out", if (it) "${d.symbol} non è fra gli asset ammessi" else "${d.symbol} is not an allowed asset")
        }
        for (d in ins) if (!allowedMint(policy, d)) {
            return Decision.Ask("asset_in", if (it) "vuole acquistare ${d.symbol}, un token non in lista" else "wants to acquire ${d.symbol}, a token not on the list")
        }

        // 7. A program the collar has never seen. Skipped on an exchange we built, for the same
        //    reason as rule five: Ultra picks the route at the moment, so the programs are not
        //    predictable and listing them would be guessing ids. The question is not who signed this
        //    program but what happens to the money: rule two, rule ten and the caps say. See [isExchange].
        if (!(routeIsOurs && exchange)) {
            programs.firstOrNull { p -> p !in policy.allowedPrograms }?.let { p ->
                return Decision.Ask("program", if (it) "usa un programma non in lista: ${short(p)}" else "uses a program not on the list: ${short(p)}")
            }
        }

        // 8. Is the agent coming home? A coin it holds, turned back into the money
        //    the budget is kept in, landing in the same pocket it left.
        val unwind = isUnwind(policy, receipt, routeIsOurs)

        // 9. Value. Nothing of unknown worth is signed silently. A coming home is
        //    the exception: what matters there is what arrives, and what arrives
        //    is SOL, which is never unpriceable.
        outs.firstOrNull { d -> valueLamports(d) == null }?.let { d ->
            if (!unwind) return Decision.Ask("unknown_value", if (it) "non so quanto vale ${trim(abs(d.uiAmount))} ${d.symbol}" else "cannot value ${trim(abs(d.uiAmount))} ${d.symbol}")
        }
        val total = outs.sumOf { d -> valueLamports(d) ?: 0L }
        // 10. An exchange must give back most of what it takes: a swap at a
        //     terrible rate is how a transfer hides inside a swap.
        if (exchange) {
            ins.firstOrNull { d -> valueLamports(d) == null }?.let { d ->
                if (!unwind) return Decision.Ask("unknown_value", if (it) "non so quanto vale ciò che riceve (${d.symbol})" else "cannot value what comes back (${d.symbol})")
            }
            val back = ins.sumOf { d -> valueLamports(d) ?: 0L }

            // What went into the exchange, which is not everything that left the wallet: a first buy
            // also pays the fee and about 0.002 SOL of rent per new account. Counting it made a clean
            // trade look bad: 0.0312 SOL into coins worth 0.0311 read as "sends 0.0359, gets back
            // 0.0303", 84%, and the agent asked for a fingerprint over a spread that did not exist.
            val rent = receipt.distributions.filter { s -> s.isNewAccount }.sumOf { s -> abs(s.delta.rawAmount) }
            val swapped = (total - rent - receipt.feeLamports).coerceAtLeast(1L)

            if (back < swapped / 2) {
                return Decision.Refuse("rate_quality", if (it) "non è uno scambio: scambia ${sol(swapped)} SOL e riceve l'equivalente di ${sol(back)} SOL" else "this is not an exchange: swaps ${sol(swapped)} SOL and gets back the equivalent of ${sol(back)} SOL")
            }
            // Getting out of a coin pays the spread and the impact, and that is
            // often more than a tenth. Asking here is the same as refusing: the
            // loop runs with nobody in front of the phone.
            if (!unwind && back < swapped * 9 / 10) {
                return Decision.Ask("rate", if (it) "cambio sfavorevole: scambia ${sol(swapped)} SOL e riceve l'equivalente di ${sol(back)} SOL" else "poor rate: swaps ${sol(swapped)} SOL and gets back the equivalent of ${sol(back)} SOL")
            }
        }
        // 11. The caps bound what the budget can lose. A coming home is the opposite: the coin
        //     becomes money again in the same pocket. Measuring it against the per-move cap left a
        //     position unsellable exactly when it had grown enough to be worth selling.
        if (!unwind) {
            if (total > policy.perTxLamports) {
                return Decision.Ask("per_tx", if (it) "${sol(total)} SOL supera il tetto per operazione di ${sol(policy.perTxLamports)} SOL" else "${sol(total)} SOL is over the per-transaction cap of ${sol(policy.perTxLamports)} SOL")
            }
            if (history.spentLast24hLamports + total > policy.dailyLamports) {
                return Decision.Ask("daily", if (it) "supererebbe il tetto giornaliero di ${sol(policy.dailyLamports)} SOL" else "would exceed the daily cap of ${sol(policy.dailyLamports)} SOL")
            }
        }
        // An explicit "always ask me" is a choice about every move, including
        // this one. It is the one rule a coming home does not walk past.
        if (policy.mode == AgentMode.ASK_ALWAYS) return Decision.Ask("ask_always", if (it) "hai scelto di essere sempre interpellato" else "you chose to always be asked")
        if (!unwind && total > policy.askAboveLamports) {
            return Decision.Ask("silent_threshold", if (it) "${sol(total)} SOL supera la soglia silenziosa di ${sol(policy.askAboveLamports)} SOL" else "${sol(total)} SOL is over the silent threshold of ${sol(policy.askAboveLamports)} SOL")
        }
        return Decision.Auto
    }

    /**
     * The agent coming home: a coin the budget holds, swapped back into the money the budget is kept
     * in. Every cap bounds what the budget can lose, and a sale loses nothing. Running sales past
     * the caps meant the agent could buy a coin it was then forbidden to sell, and a stop-loss became
     * a ninety-second wait for a fingerprint nobody was there to give. Narrow on purpose: a real exchange ([isExchange]), no base money leaving, only base money arriving. [receipt] is the simulated one.
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
