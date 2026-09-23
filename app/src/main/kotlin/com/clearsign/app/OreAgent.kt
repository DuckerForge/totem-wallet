package com.clearsign.app

import android.content.Context
import com.clearsign.core.Ore
import com.clearsign.core.OreCrowd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The agent that digs. Not one round a minute from the phone (1,400 calls a day): it hands part
 * of the budget to an executor opened with `Automate`, so much SOL on so many squares each round
 * while the deposit lasts, then checks the account once per tick and claims. The collar applies:
 * the deposit is a spend under the caps, with a receipt. On close the automation stops, the rest
 * returns, the ORE goes home. Measured 22 Sep 2026: executors take 7,000 lamports a round, so a cap too low for one square is said, not ignored.
 */
object OreAgent {
    /** What it pays the executor, per round. Measured on live automations. */
    const val FEE_PER_ROUND = 7_000L
    const val ROUNDS_PER_DAY = 1_440
    /** Below this per square the round is not worth it. */
    const val MIN_PER_SQUARE = 5_000L
    /** What always stays in the budget for the exits' fees. */
    const val RESERVE_LAMPORTS = 3_000_000L
    /** Claim only when there is at least this, so no transaction is paid for crumbs. */
    const val CLAIM_ORE_MIN = Ore.ONE_ORE / 20
    const val CLAIM_SOL_MIN = 5_000_000L
    /** Above this cost per ORE, in lamports, the executor does not play. About twice the average seen. */
    const val MAX_PRODUCTION_COST = 1_000_000_000L

    private const val PREFS = "apex_ore_agent"
    /** How often to look where the network digs and move the squares. An Automate costs a network fee. */
    const val RENEW_EVERY_MS = 15 * 60_000L
    /** Under this many archived rounds the history says nothing and the squares stay. */
    const val RENEW_MIN_ROUNDS = 10

    /** How much per round and how much to hand over, from the two sliders and what is there. */
    data class Sizing(val amountPerSquare: Long, val squares: Int, val deposit: Long, val rounds: Int) {
        val perRound: Long get() = amountPerSquare * squares + FEE_PER_ROUND
    }

    /**
     * Pure. [lamportsPerDay] is the chosen cap, [squares] the squares, [ceiling] the collar's
     * per-move cap, [free] the budget's free SOL. Null when not enough for a decent round.
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

    /** This budget's squares: picked once at random, then always the same, so the cost per round is certain. */
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
     * The squares follow the network. The ORE share is your part of the square, so at equal cost
     * the square others leave empty pays more. Every quarter hour the closed rounds are read from
     * the archive, the least crowded squares taken, and if they differ from now an Automate goes
     * out with the same amount, same fee, zero deposit: the program updates the mask in place.
     * Through the collar like every move, one network fee. Without enough rounds, nothing changes.
     */
    private suspend fun renew(ctx: Context, envelope: ByteArray, auto: Ore.Automation): TraderLoop.Tick? {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (now - p.getLong("renewAt", 0L) < RENEW_EVERY_MS) return null
        p.edit().putLong("renewAt", now).apply()
        // Only automations made here, with fixed squares: a random one, or one made
        // elsewhere, is not ours to rewrite.
        if (auto.strategy != Ore.STRATEGY_PREFERRED.toLong() || auto.mask == 0L) return null
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

    /** One step of the loop. Null when there is nothing to say or do; a Tick when the agent acted or has something to report. */
    suspend fun step(ctx: Context, cfg: TraderLoop.Config, session: SessionWallet.Session, ceiling: Long): TraderLoop.Tick? {
        val rpc = SolanaRpc.urlFor(null)
        val envelope = Base58.decodePubkey(session.pubkey) ?: return null
        val v = withContext(Dispatchers.IO) { runCatching { OreMiner.read(rpc, session.pubkey, withRound = false) }.getOrNull() } ?: return null
        val auto = v.automation
        val alive = auto != null && auto.balance >= auto.perRound

        // First what there is to claim: money sitting on the Miner account does not work.
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
            // The account of what happened, only when something did.
            val p = prefs(ctx)
            val spent = auto!!.totalSolSpent; val earned = auto.totalOreEarned
            if (spent != p.getLong("spent", -1L) || earned != p.getLong("earned", -1L)) {
                p.edit().putLong("spent", spent).putLong("earned", earned).apply()
                AgentTrace.say(ctx.getString(R.string.trace_ore_progress, Ore.sol(spent), Ore.ore(earned), auto.roundsLeft))
            }
            return renew(ctx, envelope, auto)
        }

        // Nothing live: hand over, if the numbers allow.
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
     * On budget close: stop the automation and take back the rest, claim, bring the ORE to the
     * main wallet. Signed by the budget key, like the sweep. An error, or null.
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
        // Whatever ORE is in the budget, all to the owner.
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

    /** A proposal to the collar, like every move of the agent. */
    private suspend fun submit(ctx: Context, envelope: ByteArray, ixs: List<WalletTx.Instruction>, intent: JSONObject): AgentBroker.Verdict {
        val rpc = SolanaRpc.urlFor(null)
        val bh = withContext(Dispatchers.IO) { SolanaRpc.latestBlockhash(rpc) } ?: return AgentBroker.Verdict.Refused(ctx.getString(R.string.wa_no_blockhash))
        val tx = WalletTx.build(envelope, Base58.decode(bh.hash), ixs)
        return TraderLoop.handle(ctx, tx, intent, AgentBroker.Job.Source.IN_APP)
    }

    /** Signed by the budget key, no collar: this is the close, and the money goes home. */
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

    /** A thing that does not change is said once a day, not every round. */
    private fun sayOnce(ctx: Context, key: String, text: String) {
        val p = prefs(ctx)
        val day = System.currentTimeMillis() / 86_400_000L
        if (p.getLong("said:$key", -1L) == day) return
        p.edit().putLong("said:$key", day).apply()
        AgentTrace.say(text, AgentTrace.Kind.WARN)
    }

    /** When a new budget starts, the squares and the counters start over. */
    fun reset(ctx: Context) { prefs(ctx).edit().clear().apply() }
}
