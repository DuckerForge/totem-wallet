package com.clearsign.app

import android.content.Context
import com.clearsign.core.AgentMode
import com.clearsign.core.NATIVE_SOL_MINT
import com.clearsign.core.ScanGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The agent working on its own, with the app closed.
 *
 * Everything this needs already existed and was already proven headless: the
 * budget key signs with no fingerprint ([SessionWallet.sign]), the collar judges
 * every move ([AgentBroker.handle]), the scan finds candidates ([scanMarket]),
 * and the ledger records the result. What was missing was something to decide
 * *when*. That is all this file is.
 *
 * **The constraint that shapes it.** The collar has three answers and only one
 * of them is usable here. `Auto` signs and sends with nobody present. `Ask`
 * opens the gate screen and wants a fingerprint, and from a background service
 * Android blocks the activity start, so the job hangs for ninety seconds and
 * expires. So every move this loop makes has to fit **under the silent
 * threshold and inside every cap**, and when it cannot, the honest thing is to
 * say so and stop rather than to keep proposing moves that quietly time out.
 *
 * One tick, in order, stopping at the first reason:
 *
 *  1. still allowed to run at all;
 *  2. **exits first** — a position that hit its target or its stop is sold
 *     before anything else, because a tick that spends its time hunting while a
 *     holding falls through the stop is worse than a tick that does nothing;
 *  3. **then the gains go home**, if the budget is above its harvest threshold;
 *  4. **then the hunt**, only with room for another position and money to use.
 */
object TraderLoop {
    private const val PREFS = "apex_trader"
    private const val FEE = 5_000L

    /**
     * What the network charges to open a token account, which is what buying a
     * coin you have never held actually costs on top of the coin. Recoverable
     * when the account is closed, but gone for as long as you hold it.
     */
    private const val ATA_RENT = 2_040_000L

    /** How much of a tick we skip when nothing needs doing. */
    const val EXIT_EVERY_MS = 90_000L
    const val HUNT_EVERY_MS = 360_000L

    /**
     * What the person asked for, in one place.
     *
     * [slicePercent] is a share of the per-move cap rather than an absolute
     * amount, so it cannot drift above the collar when the budget is refilled
     * at a different size.
     */
    data class Config(
        val on: Boolean = false,
        val bold: Boolean = false,
        val maxPositions: Int = 3,
        val takeProfitPct: Int = 30,
        val stopLossPct: Int = 15,
        val slicePercent: Int = 80,
    ) {
        val gate: ScanGate get() = if (bold) ScanGate.BOLD else ScanGate.CAREFUL
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun config(ctx: Context): Config = prefs(ctx).let { p ->
        Config(
            on = p.getBoolean("on", false),
            bold = p.getBoolean("bold", false),
            maxPositions = p.getInt("max", 3),
            takeProfitPct = p.getInt("tp", 30),
            stopLossPct = p.getInt("sl", 15),
            slicePercent = p.getInt("slice", 80),
        )
    }

    fun setConfig(ctx: Context, c: Config) {
        prefs(ctx).edit()
            .putBoolean("on", c.on).putBoolean("bold", c.bold)
            .putInt("max", c.maxPositions).putInt("tp", c.takeProfitPct)
            .putInt("sl", c.stopLossPct).putInt("slice", c.slicePercent)
            .apply()
    }

    /**
     * Why it cannot start, in the words a person would use, or null when it can.
     *
     * Checked before switching on, not discovered six minutes later. The loop
     * used to start happily, propose a trade the collar refused as a bad price,
     * wait ninety seconds for a person who was not there, and only then turn
     * itself off with an explanation. By which point nothing had happened and
     * the reason was a paragraph about something you thought you had set up.
     */
    fun cannotStart(ctx: Context): String? {
        val s = SessionWallet.current(ctx) ?: return ctx.getString(R.string.trader_stop_nobudget)
        val p = SessionWallet.policy(ctx) ?: return ctx.getString(R.string.trader_stop_nobudget)
        if (s.expired) return ctx.getString(R.string.trader_stop_closed)
        val ceiling = minOf(p.perTxLamports, p.askAboveLamports.takeIf { it > 0 } ?: p.perTxLamports)
        val slice = ceiling * config(ctx).slicePercent / 100
        // Under about four times the account cost, most of the trade is the cost.
        if (slice < ATA_RENT * 4) {
            return ctx.getString(R.string.trader_too_small_why, fmtSol(slice, 4), fmtSol(ATA_RENT, 4))
        }
        return null
    }

    /** Forget this budget's trading entirely: off, no history, no complaint. */
    fun reset(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        runCatching { TraderKeeper.cancel(ctx) }
    }

    fun stop(ctx: Context, why: String? = null) {
        setConfig(ctx, config(ctx).copy(on = false))
        why?.let { note(ctx, it) }
    }

    /** The last thing the loop did, for the notification, the bubble and the widget. */
    fun lastNote(ctx: Context): String? = prefs(ctx).getString("note", null)
    fun lastTickAt(ctx: Context): Long = prefs(ctx).getLong("tickAt", 0L)

    private fun note(ctx: Context, s: String) {
        prefs(ctx).edit().putString("note", s).putLong("tickAt", System.currentTimeMillis()).apply()
        // The widget is a courtesy, never the thing we are waiting on.
        AppScope.launch { runCatching { HealthWidgetData.refresh(ctx) } }
    }

    /** What one tick did, so the caller can pace itself and say it out loud. */
    data class Tick(val summary: String, val acted: Boolean, val stopped: Boolean = false)

    /**
     * One round. Safe to call as often as you like: it does its own checks and
     * returns without touching the network when there is nothing to do.
     */
    suspend fun tick(ctx: Context, mayHunt: Boolean): Tick {
        val cfg = config(ctx)
        if (!cfg.on) return Tick("off", acted = false)

        val s = SessionWallet.current(ctx)
        val p = SessionWallet.policy(ctx)
        if (s == null || p == null) {
            stop(ctx, ctx.getString(R.string.trader_stop_nobudget))
            return Tick(ctx.getString(R.string.trader_stop_nobudget), acted = false, stopped = true)
        }
        if (p.mode == AgentMode.OFF || p.mode == AgentMode.READ_ONLY || s.expired) {
            stop(ctx, ctx.getString(R.string.trader_stop_closed))
            return Tick(ctx.getString(R.string.trader_stop_closed), acted = false, stopped = true)
        }

        // A move the collar would want confirmed cannot happen without a person,
        // so the slice is measured against the silent threshold, not the cap.
        val ceiling = minOf(p.perTxLamports, p.askAboveLamports.takeIf { it > 0 } ?: p.perTxLamports)
        val slice = (ceiling * cfg.slicePercent / 100).coerceAtLeast(0L)
        if (slice <= FEE * 4) {
            stop(ctx, ctx.getString(R.string.trader_stop_toosmall))
            return Tick(ctx.getString(R.string.trader_stop_toosmall), acted = false, stopped = true)
        }

        exits(ctx, s.pubkey)?.let { note(ctx, it); return Tick(it, acted = true) }

        Settings.watchWallet(ctx)?.let { owner ->
            val took = runCatching { SessionActions.harvest(ctx, owner) }.getOrNull()
            if (took != null && took > 0) {
                val m = ctx.getString(R.string.trader_harvested, fmtSol(took, 5))
                note(ctx, m)
                return Tick(m, acted = true)
            }
        }

        if (!mayHunt) return Tick("watching", acted = false)

        val open = Positions.open(ctx)
        if (open.size >= cfg.maxPositions) return Tick("full", acted = false)

        val rpc = SolanaRpc.urlFor(null)
        val balance = withContext(Dispatchers.IO) { runCatching { SolanaRpc.getBalance(rpc, s.pubkey) }.getOrNull() } ?: 0L
        if (balance < slice + FEE * 4) return Tick("no room", acted = false)

        return hunt(ctx, cfg, slice, open.map { it.mint }.toSet())
    }

    // ---- exits ---------------------------------------------------------------

    /**
     * Sell anything that reached its target or its stop. Returns a line to show,
     * or null when every holding is still in between.
     *
     * One sale per tick on purpose: two sales in the same minute would both be
     * priced against a market the first one just moved.
     */
    private suspend fun exits(ctx: Context, envelope: String): String? {
        for (pos in Positions.open(ctx)) {
            val now = unitPriceLamports(pos) ?: continue
            val exit = pos.verdict(now) ?: continue
            val moved = (if (exit.movePct >= 0) "+" else "") + String.format("%.1f", exit.movePct) + "%"
            Positions.markClosing(ctx, pos.mint)
            val ok = sell(ctx, envelope, pos, exit)
            return if (ok) {
                // The tokens are gone; an order still trying to sell them would
                // sit on chain failing forever.
                pos.triggerOrder?.let { runCatching { JupiterTrigger.cancel(ctx, envelope, it) } }
                Positions.remove(ctx, pos.mint)
                ctx.getString(
                    if (exit.why == Positions.Exit.Why.TARGET) R.string.trader_sold_target else R.string.trader_sold_stop,
                    pos.symbol, moved,
                )
            } else {
                // Put it back rather than stranding it: a failed sale is a sale to
                // retry next tick, not a position that has left the books.
                Positions.reopen(ctx, pos.mint)
                ctx.getString(R.string.trader_sell_failed, pos.symbol)
            }
        }
        return null
    }

    /** What one whole token of [pos] is worth in lamports right now, or null. */
    private suspend fun unitPriceLamports(pos: Positions.Position): Double? {
        if (pos.units <= 0) return null
        val raw = (pos.units * Math.pow(10.0, pos.decimals.toDouble())).toLong()
        if (raw <= 0) return null
        val q = withContext(Dispatchers.IO) { runCatching { Jupiter.quote(pos.mint, Jupiter.SOL_MINT, raw, feeBps = 0) }.getOrNull() } ?: return null
        if (q.outAmount <= 0) return null
        return q.outAmount / pos.units
    }

    private suspend fun sell(ctx: Context, envelope: String, pos: Positions.Position, exit: Positions.Exit): Boolean {
        val raw = (pos.units * Math.pow(10.0, pos.decimals.toDouble())).toLong()
        // Wider slippage on the way out than on the way in: a stop that does not
        // fill because the price moved while we asked is not a stop at all.
        val quote = withContext(Dispatchers.IO) { runCatching { Jupiter.quote(pos.mint, Jupiter.SOL_MINT, raw, slippageBps = 300, feeBps = 0) }.getOrNull() } ?: return false
        val tx = withContext(Dispatchers.IO) { runCatching { Jupiter.swapTransaction(quote, envelope, null) }.getOrNull() } ?: return false
        val why = ctx.getString(
            if (exit.why == Positions.Exit.Why.TARGET) R.string.trader_why_target else R.string.trader_why_stop,
            pos.symbol,
        )
        val intent = JSONObject().put("action", "swap").put("outMint", pos.symbol).put("outAmount", pos.units)
            .put("inMint", "SOL").put("inAmount", quote.outAmount / 1e9)
            .put("agent", AGENT).put("reason", why)
        return handle(ctx, tx, intent) is AgentBroker.Verdict.SignedSilently
    }

    // ---- the hunt ------------------------------------------------------------

    private suspend fun hunt(ctx: Context, cfg: Config, slice: Long, held: Set<String>): Tick {
        val pool = withContext(Dispatchers.IO) { runCatching { JupiterTokens.pool() }.getOrDefault(emptyList()) }
        if (pool.isEmpty()) return Tick("market unreachable", acted = false)
        val picks = com.clearsign.core.scanMarket(pool.filter { it.mint !in held }, cfg.gate, limit = 5).picks
        for (pick in picks) {
            val t = pick.c
            // The scan says it is worth looking at. Before money moves, the other
            // question: can this be sold back at all.
            val sellable = withContext(Dispatchers.IO) { runCatching { Jupiter.sellableBack(t.mint, t.decimals, t.usd) }.getOrNull() }
            if (sellable == false) continue
            val safety = com.clearsign.core.assessToken(
                com.clearsign.core.TokenFacts(
                    verified = t.verified, canMint = t.canMint, canFreeze = t.canFreeze,
                    topHoldersPct = t.topHoldersPct, devMints = t.devMints, holders = t.holders ?: 0,
                    liquidityUsd = t.liquidity, sellable = sellable,
                ),
            )
            if (safety.bad) continue

            val s = SessionWallet.current(ctx) ?: return Tick("no budget", acted = false)
            val quote = withContext(Dispatchers.IO) { runCatching { Jupiter.quote(Jupiter.SOL_MINT, t.mint, slice, feeBps = 0) }.getOrNull() } ?: continue
            val tx = withContext(Dispatchers.IO) { runCatching { Jupiter.swapTransaction(quote, s.pubkey, null) }.getOrNull() } ?: continue
            val reason = ctx.getString(R.string.trader_why_buy, t.symbol, pick.notes.firstOrNull() ?: "")
            val intent = JSONObject().put("action", "swap").put("outMint", "SOL").put("outAmount", slice / 1e9)
                .put("inMint", t.symbol).put("inAmount", quote.outAmount / Math.pow(10.0, t.decimals.toDouble()))
                .put("expectMint", t.mint)
                .put("agent", AGENT).put("reason", reason)
            val v = handle(ctx, tx, intent)
            if (v is AgentBroker.Verdict.SignedSilently) {
                armOnChainExit(ctx, s.pubkey, t.mint)
                val m = ctx.getString(R.string.trader_bought, t.symbol, fmtSol(slice, 4))
                note(ctx, m)
                return Tick(m, acted = true)
            }
            // Anything that is not a silent signature ends the hunt for this
            // tick. It used to walk on to the next coin, which with the app open
            // meant the approval screen appeared five times in a row: you pressed
            // back and the next one arrived. One proposal per tick, always.
            //
            // A timeout means the collar wanted a person and nobody was there. At
            // a small slice the usual cause is rent: holding a coin you have never
            // held costs about 0.002 SOL to open its account, and on a slice of a
            // hundredth of a SOL that is a quarter of the trade gone before the
            // price moves. The next coin would end the same way, so the loop stops
            // and says which number is wrong instead of spinning.
            if (v is AgentBroker.Verdict.Timeout) {
                val m = ctx.getString(R.string.trader_too_small_why, fmtSol(slice, 4), fmtSol(ATA_RENT, 4))
                stop(ctx, m)
                return Tick(m, acted = false, stopped = true)
            }
            // Turned down with a person present: they said no to this trade, and
            // asking again in six minutes is how an agent becomes a nuisance.
            if (v is AgentBroker.Verdict.Refused) {
                val m = ctx.getString(R.string.trader_declined, t.symbol)
                stop(ctx, m)
                return Tick(m, acted = false, stopped = true)
            }
            return Tick("waiting on you", acted = false)
        }
        return Tick("nothing passed", acted = false)
    }

    /**
     * Put the take-profit on chain, when Jupiter will accept it.
     *
     * This is the only exit that outlives the app. It is attempted right after
     * the buy, against the position the broker has just written, and it is
     * allowed to fail quietly: a missing on-chain order means the target is
     * watched by the loop instead, which is a smaller promise, not a broken one.
     */
    private suspend fun armOnChainExit(ctx: Context, envelope: String, mint: String) {
        val pos = Positions.open(ctx).firstOrNull { it.mint == mint } ?: return
        when (val r = runCatching { JupiterTrigger.placeTakeProfit(ctx, envelope, pos) }.getOrNull()) {
            is JupiterTrigger.Placed.Ok -> Positions.setTrigger(ctx, mint, r.order)
            // Under about five dollars Jupiter takes no order at all. Nothing to
            // fix and nothing to warn about every tick: the loop keeps the target.
            else -> Unit
        }
    }

    // ---- the one door --------------------------------------------------------

    const val AGENT = "apex-trader"

    /**
     * Everything goes through the collar, exactly like a move proposed in chat.
     *
     * [AgentBroker.Job.Source.LINK] and not `IN_APP`: with `IN_APP` the broker
     * posts no notification when the collar wants a person, so a background
     * move would wait ninety seconds against a screen that never appeared.
     */
    private suspend fun handle(ctx: Context, tx: ByteArray, intent: JSONObject): AgentBroker.Verdict =
        AgentBroker.handle(
            ctx,
            AgentBroker.Job(
                id = LedgerRecorder.newId(), tx = tx, intentJson = intent.toString(),
                cluster = null, agent = AGENT, source = AgentBroker.Job.Source.LINK,
            ),
        )
}
