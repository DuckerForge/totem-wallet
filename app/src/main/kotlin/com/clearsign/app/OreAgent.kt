package com.clearsign.app

import android.content.Context
import com.clearsign.core.Ore
import com.clearsign.core.OreCrowd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * L'agente che scava.
 *
 * Non un giro al minuto dal telefono: sarebbero millequattrocento chiamate
 * al giorno, il contrario di tutto quello che il pool RPC esiste per non
 * fare. L'agente **affida** una parte della paghetta a un esecutore aperto
 * con `Automate`: tanto SOL su tante caselle a ogni giro, finche' il
 * deposito dura, con una fee per giro a chi esegue. Poi guarda il conto una
 * volta per ciclo, come guarda le posizioni, e riscuote quando c'e' da
 * riscuotere.
 *
 * Il collare vale come per ogni altra mossa: il deposito e' una spesa dalla
 * paghetta, sotto il tetto per operazione, contata nel giorno, con lo
 * scontrino nel registro. Alla chiusura della paghetta l'automazione si
 * ferma, il resto torna, e l'ORE scavato va nel portafoglio principale.
 *
 * Le fee misurate: sulla catena il 22 settembre 2026 le automazioni vive
 * pagano 7.000 lamport a giro, che e' anche la costante del compound nel
 * programma. Con questa fee un giro costa sempre almeno quello, e un tetto
 * al giorno troppo basso non basta nemmeno per una casella: lo si dice.
 */
object OreAgent {
    /** Quanto paga a chi esegue, per giro. Misurato sulle automazioni vive. */
    const val FEE_PER_ROUND = 7_000L
    const val ROUNDS_PER_DAY = 1_440
    /** Sotto questo per casella non vale il giro. */
    const val MIN_PER_SQUARE = 5_000L
    /** Quello che si lascia sempre nella paghetta per le fee delle uscite. */
    const val RESERVE_LAMPORTS = 3_000_000L
    /** Si riscuote quando c'e' almeno questo, per non pagare una transazione per le briciole. */
    const val CLAIM_ORE_MIN = Ore.ONE_ORE / 20
    const val CLAIM_SOL_MIN = 5_000_000L
    /** Sopra questo costo per ORE, in lamport, l'esecutore non gioca. Circa il doppio della media vista. */
    const val MAX_PRODUCTION_COST = 1_000_000_000L

    private const val PREFS = "apex_ore_agent"
    /** Ogni quanto si guarda dove scava la rete e si spostano le caselle. Un Automate costa una fee di rete. */
    const val RENEW_EVERY_MS = 15 * 60_000L
    /** Sotto questi giri in archivio lo storico non dice niente e le caselle restano. */
    const val RENEW_MIN_ROUNDS = 10

    /** Quanto mettere per giro e quanto affidare, dai due cursori e da quello che c'e'. */
    data class Sizing(val amountPerSquare: Long, val squares: Int, val deposit: Long, val rounds: Int) {
        val perRound: Long get() = amountPerSquare * squares + FEE_PER_ROUND
    }

    /**
     * Pura. [lamportsPerDay] e' il tetto scelto, [squares] le caselle,
     * [ceiling] il tetto per operazione del collare, [free] il SOL libero
     * nella paghetta. Null quando non basta per un giro decente.
     */
    fun sizing(lamportsPerDay: Long, squares: Int, ceiling: Long, free: Long): Sizing? {
        val n = squares.coerceIn(1, Ore.SQUARES)
        val perRoundBudget = lamportsPerDay / ROUNDS_PER_DAY
        val amount = (perRoundBudget - FEE_PER_ROUND) / n
        if (amount < MIN_PER_SQUARE) return null
        val perRound = amount * n + FEE_PER_ROUND
        val deposit = minOf(lamportsPerDay, ceiling, free - RESERVE_LAMPORTS)
        val rounds = (deposit / perRound).toInt()
        if (rounds < 10) return null
        return Sizing(amount, n, rounds * perRound, rounds)
    }

    /** Le caselle di questa paghetta: scelte una volta a caso, poi sempre quelle, cosi' il costo per giro e' certo. */
    private fun squaresFor(ctx: Context, n: Int): List<Int> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = p.getString("squares", null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.filter { it in 0 until Ore.SQUARES }
        if (saved != null && saved.size == n) return saved
        val fresh = (0 until Ore.SQUARES).shuffled(java.security.SecureRandom()).take(n).sorted()
        p.edit().putString("squares", fresh.joinToString(",")).apply()
        return fresh
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Le caselle seguono la rete.
     *
     * La quota di ORE e' la propria parte della casella, quindi a parita' di
     * costo rende di piu' la casella che gli altri lasciano vuota. Ogni quarto
     * d'ora si leggono i giri chiusi dall'archivio, si prendono le meno
     * affollate, e se sono diverse da quelle di adesso si manda un Automate
     * con la stessa cifra, la stessa fee e deposito zero: il programma
     * aggiorna la maschera sul posto. Passa dal collare come ogni mossa, e
     * costa una fee di rete. Senza abbastanza giri in archivio non si tocca.
     */
    private suspend fun renew(ctx: Context, envelope: ByteArray, auto: Ore.Automation): TraderLoop.Tick? {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (now - p.getLong("renewAt", 0L) < RENEW_EVERY_MS) return null
        p.edit().putLong("renewAt", now).apply()
        val rounds = withContext(Dispatchers.IO) { runCatching { OreArchive.rounds() }.getOrDefault(emptyList()) }
        if (rounds.size < RENEW_MIN_ROUNDS) return null
        val n = auto.squares.coerceIn(1, Ore.SQUARES)
        val best = OreCrowd.best(n, rounds).sorted()
        val current = Ore.squaresOf(auto.mask.toInt()).sorted()
        if (best == current) return null
        val ix = OreMiner.automate(
            envelope, auto.amountPerSquare, best, deposit = 0L, fee = auto.fee, reload = auto.reload,
            maxProductionCost = auto.maxProductionCost, executor = auto.executor,
        )
        val intent = JSONObject().put("action", "other").put("outMint", "SOL").put("outAmount", 0.0)
            .put("agent", TraderLoop.AGENT).put("reason", ctx.getString(R.string.ore_why_renew))
        val verdict = submit(ctx, envelope, listOf(ix), intent)
        if (verdict is AgentBroker.Verdict.SignedSilently) {
            p.edit().putString("squares", best.joinToString(",")).apply()
            val m = ctx.getString(R.string.trace_ore_renew, best.joinToString(", ") { (it + 1).toString() }, rounds.size)
            AgentTrace.say(m, AgentTrace.Kind.ACTED)
            return TraderLoop.Tick(m, acted = true)
        }
        sayRefusal(ctx, verdict)
        return null
    }

    /**
     * Un passo del ciclo. Null quando non c'e' niente da dire ne' da fare;
     * un Tick quando l'agente ha agito o ha qualcosa da riferire.
     */
    suspend fun step(ctx: Context, cfg: TraderLoop.Config, session: SessionWallet.Session, ceiling: Long): TraderLoop.Tick? {
        val rpc = SolanaRpc.urlFor(null)
        val envelope = Base58.decodePubkey(session.pubkey) ?: return null
        val v = withContext(Dispatchers.IO) { runCatching { OreMiner.read(rpc, session.pubkey, withRound = false) }.getOrNull() } ?: return null
        val auto = v.automation
        val alive = auto != null && auto.balance >= auto.perRound

        // Prima quello che c'e' da riscuotere: soldi fermi sul conto Miner non lavorano.
        if (v.claimableOre >= CLAIM_ORE_MIN || v.claimableSol >= CLAIM_SOL_MIN || (v.needsCheckpoint && !alive)) {
            val ixs = OreMiner.claimInstructions(envelope, v)
            if (ixs.isNotEmpty()) {
                val intent = JSONObject().put("action", "other").put("outMint", "SOL").put("outAmount", 0.0)
                    .put("agent", TraderLoop.AGENT).put("reason", ctx.getString(R.string.ore_why_claim))
                val verdict = submit(ctx, envelope, ixs, intent)
                if (verdict is AgentBroker.Verdict.SignedSilently) {
                    val m = ctx.getString(R.string.trace_ore_claimed, Ore.ore(v.claimableOre), Ore.sol(v.claimableSol))
                    AgentTrace.say(m, AgentTrace.Kind.ACTED)
                    return TraderLoop.Tick(m, acted = true)
                }
                sayRefusal(ctx, verdict)
            }
        }

        if (alive) {
            // Il racconto di quello che e' successo, solo quando e' successo qualcosa.
            val p = prefs(ctx)
            val spent = auto!!.totalSolSpent; val earned = auto.totalOreEarned
            if (spent != p.getLong("spent", -1L) || earned != p.getLong("earned", -1L)) {
                p.edit().putLong("spent", spent).putLong("earned", earned).apply()
                AgentTrace.say(ctx.getString(R.string.trace_ore_progress, Ore.sol(spent), Ore.ore(earned), auto.roundsLeft))
            }
            return renew(ctx, envelope, auto)
        }

        // Niente di vivo: si affida, se i numeri lo permettono.
        val free = withContext(Dispatchers.IO) { runCatching { SolanaRpc.getBalance(rpc, session.pubkey) }.getOrNull() } ?: return null
        val size = sizing(cfg.oreLamportsPerDay, cfg.oreSquares, ceiling, free)
        if (size == null) {
            sayOnce(ctx, "small", ctx.getString(R.string.trace_ore_small, Ore.sol(cfg.oreLamportsPerDay), cfg.oreSquares))
            return null
        }
        val squares = squaresFor(ctx, size.squares)
        val ix = OreMiner.automate(envelope, size.amountPerSquare, squares, size.deposit, FEE_PER_ROUND, reload = true, maxProductionCost = MAX_PRODUCTION_COST)
        val intent = JSONObject().put("action", "other").put("outMint", "SOL").put("outAmount", size.deposit / 1e9)
            .put("agent", TraderLoop.AGENT).put("reason", ctx.getString(R.string.ore_why_automate, size.rounds))
        val verdict = submit(ctx, envelope, listOf(ix), intent)
        if (verdict is AgentBroker.Verdict.SignedSilently) {
            prefs(ctx).edit().putLong("spent", -1L).putLong("earned", -1L).apply()
            val m = ctx.getString(R.string.trace_ore_start, Ore.sol(size.deposit), Ore.sol(size.amountPerSquare), size.squares, size.rounds)
            AgentTrace.say(m, AgentTrace.Kind.ACTED)
            return TraderLoop.Tick(m, acted = true)
        }
        sayRefusal(ctx, verdict)
        return null
    }

    /**
     * Alla chiusura della paghetta: ferma l'automazione e riprendi il resto,
     * riscuoti, e porta l'ORE nel portafoglio principale. Firmato dalla
     * chiave della paghetta, come lo sweep. Torna un errore, o null.
     */
    suspend fun bringHome(ctx: Context, owner: String): String? = withContext(Dispatchers.IO) {
        val session = SessionWallet.current(ctx) ?: return@withContext null
        val envelope = Base58.decodePubkey(session.pubkey) ?: return@withContext null
        val ownerKey = Base58.decodePubkey(owner) ?: return@withContext null
        val rpc = SolanaRpc.urlFor(null)
        val v = runCatching { OreMiner.read(rpc, session.pubkey, withRound = false) }.getOrNull() ?: return@withContext null
        if (v.miner == null && v.automation == null) return@withContext null
        val ixs = ArrayList<WalletTx.Instruction>()
        if (v.automation != null) ixs += OreMiner.stopAutomation(envelope)
        ixs += OreMiner.claimInstructions(envelope, v)
        if (ixs.isNotEmpty()) sendDirect(ctx, envelope, ixs, ctx.getString(R.string.ore_log_home))?.let { return@withContext it }
        // Quello che c'e' in ORE nella paghetta, tutto al proprietario.
        val ata = Pda.associatedTokenAddress(envelope, OreMiner.MINT, WalletTx.TOKEN_PROGRAM)
        val held = runCatching { SolanaRpc.tokenAccountsOf(rpc, session.pubkey, force = true) }.getOrDefault(emptyList())
            .firstOrNull { it.mint == Ore.MINT && it.amount > 0 } ?: return@withContext null
        val ownerAta = Pda.associatedTokenAddress(ownerKey, OreMiner.MINT, WalletTx.TOKEN_PROGRAM)
        val move = listOf(
            WalletTx.createAtaIdempotent(envelope, ownerAta, ownerKey, OreMiner.MINT, WalletTx.TOKEN_PROGRAM),
            WalletTx.tokenTransferChecked(ata, OreMiner.MINT, ownerAta, envelope, held.amount, Ore.DECIMALS, WalletTx.TOKEN_PROGRAM),
        )
        sendDirect(ctx, envelope, move, ctx.getString(R.string.ore_log_home))
    }

    /** Una proposta al collare, come ogni mossa dell'agente. */
    private suspend fun submit(ctx: Context, envelope: ByteArray, ixs: List<WalletTx.Instruction>, intent: JSONObject): AgentBroker.Verdict {
        val rpc = SolanaRpc.urlFor(null)
        val bh = withContext(Dispatchers.IO) { SolanaRpc.latestBlockhash(rpc) } ?: return AgentBroker.Verdict.Refused(ctx.getString(R.string.wa_no_blockhash))
        val tx = WalletTx.build(envelope, Base58.decode(bh.hash), ixs)
        return TraderLoop.handle(ctx, tx, intent, AgentBroker.Job.Source.IN_APP)
    }

    /** Firmato dalla chiave della paghetta, senza collare: e' la chiusura, e i soldi tornano a casa. */
    private suspend fun sendDirect(ctx: Context, envelope: ByteArray, ixs: List<WalletTx.Instruction>, label: String): String? = withContext(Dispatchers.IO) {
        val rpc = SolanaRpc.urlFor(null)
        val bh = SolanaRpc.latestBlockhash(rpc) ?: return@withContext ctx.getString(R.string.wa_no_blockhash)
        val tx = WalletTx.build(envelope, Base58.decode(bh.hash), ixs)
        val sim = SolanaRpc.simulate(rpc, tx)
        if (sim != null && !sim.ok) return@withContext ctx.getString(R.string.wa_sim_failed, sim.err ?: "?")
        val signature = SessionWallet.sign(ctx, SolanaTx.messageBytes(tx)) ?: return@withContext ctx.getString(R.string.env_key_missing)
        val signed = SolanaTx.attachSignature(tx, 0, signature)
        val out = SolanaRpc.send(rpc, signed)
        val sig = out.signature ?: return@withContext ctx.getString(R.string.wa_send_failed, out.error ?: "?")
        if (!SolanaRpc.confirmed(rpc, sig)) return@withContext ctx.getString(R.string.env_sweep_unconfirmed)
        val receipt = runCatching { ReceiptEngine.analyze(ctx, BlocklistScanner(ctx), signed, Base58.encode(envelope), null, requireSim = false).receipt }.getOrNull()
        LedgerRecorder.record(
            ctx,
            LedgerRecorder.fromReceipt(
                at = System.currentTimeMillis(), kind = "envelope", dApp = ctx.getString(R.string.env_title), host = null, pkg = ctx.packageName,
                cluster = null, wallet = Base58.encode(envelope), r = receipt, signature = sig, sent = true,
                txIndex = 0, txCount = 1, groupId = LedgerRecorder.newId(), attestation = null, attestationSig = null, recipientLabelFallback = label,
            ),
        )
        null
    }

    private fun sayRefusal(ctx: Context, v: AgentBroker.Verdict) {
        val why = v.reason ?: v.code
        sayOnce(ctx, "refused:$why", ctx.getString(R.string.trace_ore_refused, why))
    }

    /** Una cosa che non cambia si dice una volta al giorno, non a ogni giro. */
    private fun sayOnce(ctx: Context, key: String, text: String) {
        val p = prefs(ctx)
        val day = System.currentTimeMillis() / 86_400_000L
        if (p.getLong("said:$key", -1L) == day) return
        p.edit().putLong("said:$key", day).apply()
        AgentTrace.say(text, AgentTrace.Kind.WARN)
    }

    /** Alla partenza di una paghetta nuova le caselle e i contatori ripartono. */
    fun reset(ctx: Context) { prefs(ctx).edit().clear().apply() }
}
