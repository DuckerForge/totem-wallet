package com.clearsign.core

import kotlin.math.abs

/**
 * The collar: what an agent may do with its budget on its own.
 *
 * The budget ("paghetta" in Italian) is a separate hot key with a capped balance (the hard boundary:
 * it holds even if the agent's machine is compromised). This policy is the soft
 * boundary on top of it, applied by the wallet on the phone before it signs with
 * that key. It protects against the agent — hallucinated addresses, prompt
 * injection, runaway loops — not against a compromised phone. It is not enforced
 * on chain, and nothing here should make anyone believe it is.
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
     * Let the agent trade any coin on Solana instead of a fixed short list.
     *
     * The short list was a cautious default, and it also meant the agent could
     * not buy anything at all: six hand-written symbols, of which the collar
     * allowed two. Opening it changes nothing else — the per-move and daily
     * caps, the destination list, the rate limit and the untouchable vault all
     * still apply. A hand-written list of symbols was never what protected the
     * money; the caps are, and for an unknown coin the question that matters is
     * whether it can be sold back, which is checked before a proposal gets here.
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

/**
 * The verdict. [code] is the stable machine-readable half — an agent can branch
 * on it without parsing a sentence — and [reason] is the human half, in the
 * user's language.
 */
sealed class Decision(val code: String) {
    object Auto : Decision("auto")
    class Ask(code: String, val reason: String) : Decision(code) {
        override fun toString() = "Ask($code: $reason)"
    }
    class Refuse(code: String, val reason: String) : Decision(code) {
        override fun toString() = "Refuse($code: $reason)"
    }
}

object PolicyEngine {
    private val EXCHANGE_PROGRAMS get() = AgentPolicy.EXCHANGE_PROGRAMS

    /**
     * Decide what to do with a transaction the agent submitted, given the
     * *simulated* receipt (never the agent's claim) and the intent check.
     *
     * [valueLamports] prices one outflow in SOL-equivalent lamports, or null when
     * no price is known — an unknown value is never signed silently.
     *
     * Rules run in order; the first that fires wins, and a refusal always beats
     * a question: the user must never be asked to confirm a lie.
     */
    fun decide(
        policy: AgentPolicy, receipt: Receipt, guard: Risk, history: SpendHistory,
        valueLamports: (BalanceDelta) -> Long?, now: Long = System.currentTimeMillis(), locale: String = "en",
        /** The Seed Vault account. The agent may pay it, never spend from it. */
        vault: String? = null,
        /** Accounts this transaction can modify, from the decoded message. */
        writableKeys: Set<String> = emptySet(),
    ): Decision {
        val it = locale == "it"

        // 1. Not running.
        if (policy.mode == AgentMode.OFF) return Decision.Refuse("paused", if (it) "l'agente è in pausa" else "the agent is paused")
        if (now > policy.expiresAt) return Decision.Refuse("expired", if (it) "la paghetta è scaduta" else "the budget has expired")
        if (policy.mode == AgentMode.READ_ONLY) return Decision.Refuse("read_only", if (it) "l'agente è in sola lettura" else "the agent is read-only")

        // 2. The claim does not hold, or the transaction is dangerous on its own.
        if (guard.flag == RiskFlag.AGENT_INTENT_MISMATCH) return Decision.Refuse("intent_mismatch", guard.detail)
        receipt.risks.firstOrNull { r -> r.severity == Severity.DANGER }?.let { r -> return Decision.Refuse("danger", r.detail) }

        // 3. The vault is out of bounds. The envelope key cannot authorise the vault
        //    to pay, so a vault that only receives is fine ("send the winnings home").
        //    Anything else — the vault losing value, or being writable without
        //    receiving — means this transaction is reaching for the wrong pocket.
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

        // 5. An exchange pays a pool, not a wallet, so the destination list only
        //    governs transfers. A transaction counts as an exchange only when it
        //    actually invokes an allowed exchange program: a plain transfer
        //    dressed up with a dust inflow does not qualify.
        val programs = receipt.stats?.programs.orEmpty()
        val exchange = programs.any { p -> p in EXCHANGE_PROGRAMS && p in policy.allowedPrograms }
        val outs = receipt.outflows.filter { d -> d.rawAmount < 0 }
        val ins = receipt.inflows.filter { d -> d.rawAmount > 0 && !d.createdAccount }
        if (!exchange) {
            val payees = receipt.distributions.filter { s -> !s.isNewAccount }.map { s -> s.address } +
                listOfNotNull(receipt.primaryRecipient)
            for (a in payees.distinct()) {
                if (a in policy.allowedDestinations || a in policy.allowedPrograms) continue
                return Decision.Refuse("destination", if (it) "${short(a)} non è fra i destinatari ammessi" else "${short(a)} is not an allowed destination")
            }
        }

        // 6. Assets: nothing unlisted leaves, and buying something new is the human's call.
        for (d in outs) if (!allowedMint(policy, d)) {
            return Decision.Refuse("asset_out", if (it) "${d.symbol} non è fra gli asset ammessi" else "${d.symbol} is not an allowed asset")
        }
        for (d in ins) if (!allowedMint(policy, d)) {
            return Decision.Ask("asset_in", if (it) "vuole acquistare ${d.symbol}, un token non in lista" else "wants to acquire ${d.symbol}, a token not on the list")
        }

        // 7. A program the collar has never seen.
        programs.firstOrNull { p -> p !in policy.allowedPrograms }?.let { p ->
            return Decision.Ask("program", if (it) "usa un programma non in lista: ${short(p)}" else "uses a program not on the list: ${short(p)}")
        }

        // 8. Value. Nothing of unknown worth is signed silently.
        var total = 0L
        for (d in outs) {
            val v = valueLamports(d) ?: return Decision.Ask("unknown_value", if (it) "non so quanto vale ${trim(abs(d.uiAmount))} ${d.symbol}" else "cannot value ${trim(abs(d.uiAmount))} ${d.symbol}")
            total += v
        }
        // 9. An exchange must give back most of what it takes: a swap at a
        //    terrible rate is how a transfer hides inside a swap.
        if (exchange) {
            var back = 0L
            for (d in ins) {
                val v = valueLamports(d) ?: return Decision.Ask("unknown_value", if (it) "non so quanto vale ciò che riceve (${d.symbol})" else "cannot value what comes back (${d.symbol})")
                back += v
            }
            if (back < total / 2) {
                return Decision.Refuse("rate_quality", if (it) "non è uno scambio: manda ${sol(total)} SOL e riceve l'equivalente di ${sol(back)} SOL" else "this is not an exchange: sends ${sol(total)} SOL and gets back the equivalent of ${sol(back)} SOL")
            }
            if (back < total * 9 / 10) {
                return Decision.Ask("rate", if (it) "cambio sfavorevole: manda ${sol(total)} SOL e riceve l'equivalente di ${sol(back)} SOL" else "poor rate: sends ${sol(total)} SOL and gets back the equivalent of ${sol(back)} SOL")
            }
        }
        if (total > policy.perTxLamports) {
            return Decision.Ask("per_tx", if (it) "${sol(total)} SOL supera il tetto per operazione di ${sol(policy.perTxLamports)} SOL" else "${sol(total)} SOL is over the per-transaction cap of ${sol(policy.perTxLamports)} SOL")
        }
        if (history.spentLast24hLamports + total > policy.dailyLamports) {
            return Decision.Ask("daily", if (it) "supererebbe il tetto giornaliero di ${sol(policy.dailyLamports)} SOL" else "would exceed the daily cap of ${sol(policy.dailyLamports)} SOL")
        }
        if (policy.mode == AgentMode.ASK_ALWAYS) return Decision.Ask("ask_always", if (it) "hai scelto di essere sempre interpellato" else "you chose to always be asked")
        if (total > policy.askAboveLamports) {
            return Decision.Ask("silent_threshold", if (it) "${sol(total)} SOL supera la soglia silenziosa di ${sol(policy.askAboveLamports)} SOL" else "${sol(total)} SOL is over the silent threshold of ${sol(policy.askAboveLamports)} SOL")
        }
        return Decision.Auto
    }

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
 * The other half of a machine-readable verdict.
 *
 * [IntentGuard.summary] renders what the agent *said*. This renders what the
 * simulation says will actually *happen*, in one line, so a caller can show the
 * two side by side and see for itself whether they agree.
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
