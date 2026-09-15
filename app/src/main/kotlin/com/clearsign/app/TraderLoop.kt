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

    /**
     * The loop switching itself off, said out loud.
     *
     * [stop] is also what the person's own Ferma button calls, and that one
     * needs no notification: they are looking at the screen. This one is for
     * the loop deciding on its own, from a service, with the app closed. It
     * used to write a note into the card and nothing else, so an agent that
     * stopped at four in the afternoon was found at six by somebody opening
     * the app, with a position it had left unwatched in between.
     */
    fun stopSelf(ctx: Context, why: String) {
        stop(ctx, why)
        val unwatched = Positions.open(ctx).count { !it.parked && it.triggerOrder == null }
        val body = if (unwatched > 0) why + "\n" + ctx.getString(R.string.trader_unwatched, unwatched) else why
        AgentBroker.warn(ctx, ctx.getString(R.string.pulse_stopped), body)
    }

    /** Switch on, and forget what the last stop said: that was about a run that is over. */
    fun start(ctx: Context, cfg: Config) {
        setConfig(ctx, cfg.copy(on = true))
        prefs(ctx).edit().remove("note").apply()
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
    suspend fun tick(ctx: Context, mayHunt: Boolean): Tick = AgentTrace.working { tickInner(ctx, mayHunt) }

    private suspend fun tickInner(ctx: Context, mayHunt: Boolean): Tick {
        val cfg = config(ctx)
        if (!cfg.on) return Tick("off", acted = false)

        val s = SessionWallet.current(ctx)
        val p = SessionWallet.policy(ctx)
        if (s == null || p == null) {
            stopSelf(ctx, ctx.getString(R.string.trader_stop_nobudget))
            return Tick(ctx.getString(R.string.trader_stop_nobudget), acted = false, stopped = true)
        }
        if (s.expired) {
            stopSelf(ctx, ctx.getString(R.string.trader_stop_closed))
            return Tick(ctx.getString(R.string.trader_stop_closed), acted = false, stopped = true)
        }
        // Paused is not stopped. The notification's Pause button sets the mode
        // to OFF and its Resume button sets it back, and this used to switch
        // trading off for good at the first tick in between: Resume then brought
        // back an agent that would not trade until somebody found the switch in
        // the rules. While paused the loop waits, touches nothing, and picks up
        // where it was the moment the mode comes back.
        if (p.mode == AgentMode.OFF || p.mode == AgentMode.READ_ONLY) {
            return Tick(ctx.getString(R.string.pulse_paused), acted = false)
        }

        // A move the collar would want confirmed cannot happen without a person,
        // so the slice is measured against the silent threshold, not the cap.
        val ceiling = minOf(p.perTxLamports, p.askAboveLamports.takeIf { it > 0 } ?: p.perTxLamports)
        val slice = (ceiling * cfg.slicePercent / 100).coerceAtLeast(0L)
        if (slice <= FEE * 4) {
            stopSelf(ctx, ctx.getString(R.string.trader_stop_toosmall))
            return Tick(ctx.getString(R.string.trader_stop_toosmall), acted = false, stopped = true)
        }

        // The book is written from simulations, and the chain is what is. Put the
        // two side by side before a single decision is priced off the book.
        val ghosts = reconcile(ctx, s.pubkey)

        // The shadow book moves with the same market, on its own slower clock.
        // Nothing here signs: it is a notebook that costs one quote per open row.
        runCatching {
            Paper.step(ctx, cfg.takeProfitPct, cfg.stopLossPct) { pos ->
                val units = if (pos.entryOf() > 0) pos.sizeLamports / pos.entryOf() else 0.0
                val raw = (units * Math.pow(10.0, pos.decimals.toDouble())).toLong()
                if (raw <= 0) null else withContext(Dispatchers.IO) {
                    runCatching { Jupiter.quote(pos.mint, Jupiter.SOL_MINT, raw, feeBps = 0) }.getOrNull()
                        ?.let { q -> if (units > 0) q.outAmount / units else null }
                }
            }
        }

        val exit = exits(ctx, s.pubkey)
        exit.did?.let { note(ctx, it); return Tick(it, acted = true) }

        // A coin that could not be sold is worth saying, and it is not a reason
        // to skip the rest of the round: the harvest and the hunt have nothing
        // to do with it. This used to return here, so one stuck position stopped
        // the agent from doing anything at all, for as long as it stayed stuck.
        val problem = exit.problem ?: ghosts
        fun finish(t: Tick): Tick {
            if (t.acted || t.stopped || problem == null) return t
            note(ctx, problem)
            return t.copy(summary = problem)
        }

        Settings.watchWallet(ctx)?.let { owner ->
            val took = runCatching { SessionActions.harvest(ctx, owner) }.getOrNull()
            if (took != null && took > 0) {
                val m = ctx.getString(R.string.trader_harvested, fmtSol(took, 5))
                note(ctx, m)
                return Tick(m, acted = true)
            }
        }

        if (!mayHunt) return finish(Tick("watching", acted = false))

        val open = Positions.open(ctx)
        if (open.size >= cfg.maxPositions) return finish(Tick("full", acted = false))

        val rpc = SolanaRpc.urlFor(null)
        val balance = withContext(Dispatchers.IO) { runCatching { SolanaRpc.getBalance(rpc, s.pubkey) }.getOrNull() } ?: 0L
        if (balance < slice + FEE * 4) return finish(Tick("no room", acted = false))

        return finish(hunt(ctx, cfg, slice, open.map { it.mint }.toSet(), p))
    }

    // ---- the book against the chain ------------------------------------------

    /**
     * Believe the chain, not the book.
     *
     * Everything the loop does is priced off the position list, and that list is
     * written from simulations: promises about one moment. Between then and now a
     * budget can be closed and remade, an order can fill, a coin can be sold from
     * the chat. A row that is no longer true is not a cosmetic problem. It is an
     * agent proposing to sell something it does not have, being refused for
     * lying, and doing that every ninety seconds while everything else waits.
     *
     * Nothing is dropped on a network failure: not reaching Jupiter is not
     * evidence that a coin is gone. Returns a line to show, or null.
     */
    private suspend fun reconcile(ctx: Context, envelope: String): String? {
        val book = Positions.all(ctx)
        if (book.isEmpty()) return null
        val held = SessionActions.heldRaw(ctx, envelope) ?: return null
        // Jupiter is asked when a row has nothing behind it (is an order holding
        // the coins?) and when a row carries an order key (is it still live?).
        // A key is only ever dropped on Jupiter's word: a cancel that was
        // accepted and never landed used to leave the coins in escrow and the
        // row without a key, and nothing could reach it again.
        val missing = book.filter { (held[it.mint] ?: 0L) <= 0L }
        val ask = missing.isNotEmpty() || book.any { it.triggerOrder != null }
        val live = if (!ask) JupiterTrigger.Live(emptySet(), emptySet())
        else withContext(Dispatchers.IO) { runCatching { JupiterTrigger.live(envelope) }.getOrNull() } ?: return null
        val parked = live.mints + missing.filter { it.triggerOrder != null && it.triggerOrder in live.orders }.map { it.mint }
        val gone = Positions.reconcile(ctx, envelope, held, parked, if (ask) live.byMint else null)
        if (gone.isEmpty()) return null
        val line = ctx.getString(R.string.trader_gone, gone.joinToString(", ") { it.symbol })
        // A holding leaving the list is worth one notification. It happens rarely,
        // it is always about money, and the alternative is a line in a card that
        // the next thing the loop does will overwrite within the minute.
        AgentBroker.warn(ctx, ctx.getString(R.string.trader_gone_title), line)
        return line
    }

    // ---- exits ---------------------------------------------------------------

    /** What the exit half of a tick did: one action, or one problem worth saying. */
    private data class ExitOutcome(val did: String? = null, val problem: String? = null)

    /**
     * Sell anything that reached its target or its stop.
     *
     * One action per tick on purpose: two sales in the same minute would both be
     * priced against a market the first one just moved. But one coin that cannot
     * be sold must never stand in front of the others, so a failure walks on to
     * the next position instead of ending the round.
     */
    private suspend fun exits(ctx: Context, envelope: String): ExitOutcome {
        val at = System.currentTimeMillis()
        var problem: String? = null
        for (pos in Positions.open(ctx)) {
            // Failed three times already: it gets a slower lane, not the same
            // ninety seconds forever.
            if (at < pos.readyAt) continue
            val now = unitPriceLamports(pos) ?: continue
            val exit = pos.verdict(now) ?: continue

            // The coins are in Jupiter's escrow, held by a take-profit order, so
            // no swap of ours can move them. Take the order back first and sell
            // on the next tick. Without this the stop-loss could never fire on a
            // position that had an on-chain target, which is exactly the position
            // most worth protecting.
            if (pos.parked || pos.triggerOrder != null) {
                val order = pos.triggerOrder
                if (order == null) {
                    problem = ctx.getString(R.string.trader_parked_unknown, pos.symbol)
                    continue
                }
                val back = withContext(Dispatchers.IO) { runCatching { JupiterTrigger.cancel(ctx, envelope, order) }.getOrDefault(false) }
                if (!back) {
                    val why = ctx.getString(R.string.trader_order_stuck)
                    Positions.noteFailure(ctx, pos.mint, why)
                    problem = ctx.getString(R.string.trader_sell_failed, pos.symbol, why)
                    continue
                }
                // The key stays. "Accepted" is not "landed", and the next
                // reconcile drops it only once Jupiter no longer lists the order
                // and the coins are back in the wallet. If the cancel fell
                // through, the row is still parked with its key and this runs
                // again, instead of being parked with no key for ever.
                return ExitOutcome(did = ctx.getString(R.string.trader_order_cancelled, pos.symbol))
            }

            val moved = (if (exit.movePct >= 0) "+" else "") + String.format("%.1f", exit.movePct) + "%"
            val why = ctx.getString(
                if (exit.why == Positions.Exit.Why.TARGET) R.string.trader_why_target else R.string.trader_why_stop,
                pos.symbol,
            )
            Positions.markClosing(ctx, pos.mint)
            val sale = SessionActions.sellNow(ctx, pos, why, AgentBroker.Job.Source.LINK)
            val v = (sale as? SessionActions.Sale.Judged)?.verdict
            if (v is AgentBroker.Verdict.SignedSilently || v is AgentBroker.Verdict.Confirmed) {
                Positions.remove(ctx, pos.mint)
                return ExitOutcome(
                    did = ctx.getString(
                        if (exit.why == Positions.Exit.Why.TARGET) R.string.trader_sold_target else R.string.trader_sold_stop,
                        pos.symbol, moved,
                    ),
                )
            }
            // Put it back rather than stranding it: a failed sale is a sale to
            // retry, not a position that has left the books. And the reason comes
            // back with it. "Could not sell, trying again shortly" repeated
            // eighty-four times is how sixteen hours went by with nobody knowing
            // the coin was not even in the wallet.
            Positions.reopen(ctx, pos.mint)
            // Two of the answers are not failures of this coin and are not counted
            // against it: a node that did not answer will answer next time, and a
            // coin that is not in the wallet is a row the next reconcile removes.
            // Counting them used to push a live stop-loss into the six-hour lane
            // after three blinks of the network.
            when (sale) {
                is SessionActions.Sale.Unreachable -> {
                    problem = ctx.getString(R.string.trader_sell_failed, pos.symbol, ctx.getString(R.string.trader_net_down))
                    continue
                }
                is SessionActions.Sale.Nothing -> {
                    problem = ctx.getString(R.string.trader_sell_failed, pos.symbol, ctx.getString(R.string.trader_no_coins))
                    continue
                }
                else -> Unit
            }
            val reason = reasonOf(ctx, v)
            val fails = Positions.noteFailure(ctx, pos.mint, reason)
            problem = ctx.getString(R.string.trader_sell_failed, pos.symbol, reason)
            // Said once, where the quick retries stop. Not every ninety seconds.
            if (fails == 3) {
                AgentBroker.warn(ctx, ctx.getString(R.string.trader_stuck_title, pos.symbol), problem)
            }
        }
        return ExitOutcome(problem = problem)
    }

    /** Why a sale did not happen, in the words the person reads. */
    private fun reasonOf(ctx: Context, v: AgentBroker.Verdict?): String = when {
        v == null -> ctx.getString(R.string.trader_no_route)
        v is AgentBroker.Verdict.Timeout -> ctx.getString(R.string.trader_needed_you)
        else -> v.reason ?: ctx.getString(R.string.trader_refused_plain)
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

    // ---- the hunt ------------------------------------------------------------

    private suspend fun hunt(ctx: Context, cfg: Config, slice: Long, held: Set<String>, p: com.clearsign.core.AgentPolicy): Tick {
        AgentTrace.say(ctx.getString(R.string.trace_scanning))
        val pool = withContext(Dispatchers.IO) { runCatching { JupiterTokens.pool() }.getOrDefault(emptyList()) }
        if (pool.isEmpty()) {
            AgentTrace.say(ctx.getString(R.string.trace_market_down), AgentTrace.Kind.REFUSED)
            return Tick("market unreachable", acted = false)
        }
        val scan = com.clearsign.core.scanMarket(pool.filter { it.mint !in held }, cfg.gate, limit = 5)
        val picks = scan.picks
        AgentTrace.say(ctx.getString(R.string.trace_scanned, pool.size, picks.size))
        // The commonest reason the whole field was thrown out, said once. It is
        // the difference between "found nothing" and "found nothing because every
        // coin out there today can still be frozen by its creator".
        scan.rejected.maxByOrNull { it.value }?.let { (why, n) ->
            if (picks.isEmpty()) AgentTrace.say(ctx.getString(R.string.trace_rejected, n, why), AgentTrace.Kind.REFUSED)
        }
        for (pick in picks) {
            val t = pick.c
            // The scan says it is worth looking at. Before money moves, the other
            // question: can this be sold back at all.
            AgentTrace.say(ctx.getString(R.string.trace_looking, t.symbol), AgentTrace.Kind.FOUND)
            val sellable = withContext(Dispatchers.IO) { runCatching { Jupiter.sellableBack(t.mint, t.decimals, t.usd) }.getOrNull() }
            if (sellable == false) {
                AgentTrace.say(ctx.getString(R.string.trace_no_exit, t.symbol), AgentTrace.Kind.REFUSED)
                continue
            }
            // What the mint can still do once the coin is ours. A permanent
            // delegate burns the balance out of the budget wherever it sits, so
            // this has to be asked before the money moves, not after.
            val ext = withContext(Dispatchers.IO) { TokenExtensions.of(t.mint, t.token2022) }
            val safety = com.clearsign.core.assessToken(
                com.clearsign.core.TokenFacts(
                    verified = t.verified, canMint = t.canMint, canFreeze = t.canFreeze,
                    token2022 = t.token2022,
                    topHoldersPct = t.topHoldersPct, devMints = t.devMints, holders = t.holders ?: 0,
                    liquidityUsd = t.liquidity, sellable = sellable,
                    ext = ext ?: com.clearsign.core.MintExtensions.NONE,
                ),
            )
            if (safety.bad) {
                AgentTrace.say(ctx.getString(R.string.trace_unsafe, t.symbol, safety.score), AgentTrace.Kind.REFUSED)
                continue
            }

            val s = SessionWallet.current(ctx) ?: return Tick("no budget", acted = false)
            val quote = withContext(Dispatchers.IO) { runCatching { Jupiter.quote(Jupiter.SOL_MINT, t.mint, slice, feeBps = 0) }.getOrNull() } ?: continue

            // What one whole coin costs at this moment, straight out of the quote
            // the trade would have used. The shadow book starts here and nowhere
            // better: no invented price, no price we were not actually offered.
            val units = quote.outAmount / Math.pow(10.0, t.decimals.toDouble())
            val entry = if (units > 0) slice / units else 0.0
            fun shadow(blockedBy: String?) = Paper.open(
                ctx, t.mint, t.symbol, t.decimals, entry, slice, quote.priceImpactPct * 100.0,
                cfg.takeProfitPct, cfg.stopLossPct, blockedBy,
            )

            // The last question, and the only one that is not arithmetic: does the
            // web know something about this coin. Runs here and nowhere else —
            // after everything cheap has already said yes, on the one coin we are
            // about to buy — because it costs about a cent a time and asking it of
            // every candidate would cost more per day than the budget holds. After
            // the quote, so that a coin it stops still enters the shadow book with
            // a real entry price and can be judged later.
            when (val web = CoinCheck.verdict(ctx, t.symbol, t.mint)) {
                is CoinCheck.Verdict.Stop -> {
                    AgentTrace.say(ctx.getString(R.string.trace_web_stop, t.symbol), AgentTrace.Kind.REFUSED)
                    shadow("web")
                    val m = ctx.getString(R.string.trader_web_stop, t.symbol, web.reason)
                    note(ctx, m)
                    continue
                }
                // Ok and Unknown both proceed: a check we could not run is not a
                // reason to stop, and never has been anywhere else in this app.
                else -> Unit
            }

            // The person's own rules, when they wrote any. Same shape as the web
            // check: one coin, after everything cheap said yes, and it can only
            // say no. Runs on whatever model they configured, any provider.
            when (val own = UserRules.verdict(ctx, t, pick.notes, cfg.gate.name, slice)) {
                is UserRules.Verdict.Stop -> {
                    AgentTrace.say(ctx.getString(R.string.trace_rules_stop, t.symbol, own.reason), AgentTrace.Kind.REFUSED)
                    shadow("rules")
                    note(ctx, ctx.getString(R.string.trader_rules_stop, t.symbol, own.reason))
                    continue
                }
                else -> Unit
            }

            // What this trade costs before the price moves at all.
            //
            // The collar refuses to sign an exchange silently when less than nine
            // tenths of the value comes back, and it is right to: on a thin coin
            // one slice moves the price, and you start the position already down.
            // But the loop used to find that out only by proposing the trade and
            // being asked for a fingerprint that nobody was there to give. The
            // same arithmetic is available here, before anything is proposed, so
            // the coin is simply skipped and the hunt goes on.
            val back = withContext(Dispatchers.IO) {
                runCatching {
                    val q = Prices.quotes(listOf(t.mint, com.clearsign.core.NATIVE_SOL_MINT))
                    val solUsd = q[com.clearsign.core.NATIVE_SOL_MINT]?.usd
                    val coinUsd = q[t.mint]?.usd
                    if (solUsd == null || coinUsd == null || solUsd <= 0) null
                    else units * coinUsd / solUsd * 1e9 / slice
                }.getOrNull()
            }
            if (back != null && back < 0.92) {
                AgentTrace.say(ctx.getString(R.string.trace_bad_rate, t.symbol, ((1 - back) * 100).toInt()), AgentTrace.Kind.REFUSED)
                shadow("spread")
                continue
            }

            val tx = withContext(Dispatchers.IO) { runCatching { Jupiter.swapTransaction(quote, s.pubkey, null) }.getOrNull() } ?: continue
            val reason = ctx.getString(R.string.trader_why_buy, t.symbol, pick.notes.firstOrNull() ?: "")
            val intent = JSONObject().put("action", "swap").put("outMint", "SOL").put("outAmount", slice / 1e9)
                .put("inMint", t.symbol).put("inAmount", quote.outAmount / Math.pow(10.0, t.decimals.toDouble()))
                .put("expectMint", t.mint)
                .put("agent", AGENT).put("reason", reason)
            val v = handle(ctx, tx, intent)
            if (v is AgentBroker.Verdict.SignedSilently) {
                AgentTrace.say(ctx.getString(R.string.trader_bought, t.symbol, fmtSol(slice, 4)), AgentTrace.Kind.ACTED)
                shadow(null)
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
            // A timeout means the collar wanted a person and nobody was there.
            //
            // It used to print one canned sentence about the account rent, which
            // was the right diagnosis once and the wrong one for ever after: the
            // real case today was the **daily cap**, already half spent by the
            // morning's trade, and the screen sent the person off to add funds to
            // a budget that had plenty. The collar knows exactly which rule it
            // stopped on; it says so now.
            if (v is AgentBroker.Verdict.Timeout) {
                shadow("collar")
                AgentTrace.say(ctx.getString(R.string.trace_asked, t.symbol), AgentTrace.Kind.REFUSED)
                val m = when (v.rule) {
                    "daily" -> ctx.getString(R.string.trader_daily_full, fmtSol(p.dailyLamports, 4))
                    "per_tx" -> ctx.getString(R.string.trader_per_tx_full, fmtSol(slice, 4), fmtSol(p.perTxLamports, 4))
                    "silent_threshold" -> ctx.getString(R.string.trader_above_silent, fmtSol(slice, 4), fmtSol(p.askAboveLamports, 4))
                    null -> ctx.getString(R.string.trader_too_small_why, fmtSol(slice, 4), fmtSol(ATA_RENT, 4))
                    else -> ctx.getString(R.string.trader_asked_why, t.symbol, v.reason ?: v.rule.orEmpty())
                }
                stopSelf(ctx, m)
                return Tick(m, acted = false, stopped = true)
            }
            // Turned down. By a person, which means they said no to this trade and
            // asking again in six minutes is how an agent becomes a nuisance; or by
            // the collar, which is not an answer from anybody and has to be quoted
            // rather than put in your mouth.
            // The network dropped, not a coin gone wrong. Skip it and carry on:
            // stopping the whole loop over a connection that will be back in
            // ninety seconds is how an agent spends a night switched off.
            if (v is AgentBroker.Verdict.Refused && v.rule == "no_simulation") {
                AgentTrace.say(ctx.getString(R.string.trace_no_sim, t.symbol), AgentTrace.Kind.REFUSED)
                continue
            }
            // The node ran it and it failed: a bad swap for this coin right now,
            // with the node's own reason. Not a network blip, and not a reason to
            // stop either. The reason used to be replaced by "did not answer",
            // and the same broken swap was retried every round.
            if (v is AgentBroker.Verdict.Refused && v.rule == "sim_failed") {
                AgentTrace.say(ctx.getString(R.string.trace_sim_failed, t.symbol, v.reason ?: ""), AgentTrace.Kind.REFUSED)
                shadow("sim")
                continue
            }
            if (v is AgentBroker.Verdict.Refused) {
                when (v.by) {
                    AgentBroker.Verdict.Refused.By.COLLAR -> {
                        shadow("collar")
                        val m = ctx.getString(R.string.trader_refused_by_rules, t.symbol, v.reason ?: "")
                        stopSelf(ctx, m)
                        return Tick(m, acted = false, stopped = true)
                    }
                    AgentBroker.Verdict.Refused.By.PERSON -> {
                        shadow("you")
                        val m = ctx.getString(R.string.trader_declined, t.symbol)
                        stopSelf(ctx, m)
                        return Tick(m, acted = false, stopped = true)
                    }
                    // Nobody said no: the send failed, the key could not be read.
                    // Said out loud and tried again next round. Switching off here
                    // used to blame a person for a node that answered 429.
                    AgentBroker.Verdict.Refused.By.SYSTEM -> {
                        val m = ctx.getString(R.string.trader_buy_failed, t.symbol, v.reason ?: "")
                        AgentTrace.say(m, AgentTrace.Kind.REFUSED)
                        note(ctx, m)
                        return Tick(m, acted = false)
                    }
                }
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
            // fix and nothing to retry, but it is not nothing: it means the only
            // exit that outlives the app is not there, and the position is watched
            // by the loop or by nobody. Said on the row rather than swallowed.
            // Measured on 2026-09-15: a 0.036 SOL position with SOL at 99 dollars
            // is 3.60 dollars, and its target 4.68, both under the floor.
            JupiterTrigger.Placed.TooSmall -> Positions.note(ctx, mint, ctx.getString(R.string.trader_no_onchain_small))
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
