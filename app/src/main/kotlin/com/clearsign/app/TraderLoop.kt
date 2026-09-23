package com.clearsign.app

import android.content.Context
import androidx.compose.ui.graphics.toArgb
import com.clearsign.core.AgentMode
import com.clearsign.core.NATIVE_SOL_MINT
import com.clearsign.core.ScanGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The agent working on its own, app closed. The pieces existed and were proven
 * headless ([SessionWallet.sign], [AgentBroker.handle], [scanMarket]); this file
 * only decides when. From a service the collar's `Ask` cannot open a screen, so
 * every move must fit under the silent threshold and inside every cap, or the loop
 * says so and stops. One tick: exits first, then the harvest, then the hunt.
 */
object TraderLoop {
    private const val PREFS = "apex_trader"
    private const val FEE = 5_000L

    /** What the network charges to open a token account: the hidden cost of a coin never held. Back when the account is closed. */
    private const val ATA_RENT = 2_040_000L

    /** How much of a tick we skip when nothing needs doing. */
    const val EXIT_EVERY_MS = 90_000L
    /**
     * Hunt cadence: six minutes on an own node, twelve on the shared keys. Exits stay
     * at ninety seconds either way, a stop-loss waits for nobody.
     */
    private const val HUNT_OWN_MS = 360_000L
    private const val HUNT_SHARED_MS = 720_000L
    val HUNT_EVERY_MS: Long get() = if (Rpc.ownNode != null) HUNT_OWN_MS else HUNT_SHARED_MS

    /**
     * How long until the next tick. Ninety seconds exist for the stop-loss; with no
     * position there is nothing to watch fall, and the short breath cost 960 balance
     * reads a day per idle phone on the shared node. Empty hands breathe at the hunt's
     * pace, the only thing that can happen anyway.
     */
    fun breath(ctx: Context): Long = if (Positions.open(ctx).isEmpty()) HUNT_EVERY_MS else EXIT_EVERY_MS

    /** Every how many ticks the book is compared with the chain. See `tickInner`. */
    private const val RECONCILE_EVERY = 5
    private var sinceReconcile = 0

    /**
     * What the person asked for, in one place. [slicePercent] is a share of the
     * per-move cap, not an amount, so a refilled budget cannot push it past the collar.
     */
    data class Config(
        val on: Boolean = false,
        val bold: Boolean = false,
        val maxPositions: Int = 3,
        val takeProfitPct: Int = 30,
        val stopLossPct: Int = 15,
        val slicePercent: Int = 80,
        /**
         * The coin's own transfer fee we accept, in percent; zero means none. Jupiter's
         * shield marks any such fee critical: on 17 Sep four of five candidates were
         * thrown out for a 1 to 3% fee and the agent bought nothing. A fee is a cost,
         * not a scam; the person decides. The rest of the shield still stops.
         */
        val maxFeePct: Int = 0,
        /** The agent digs ORE: hands part of the budget to an executor, under the collar. */
        val oreOn: Boolean = false,
        /** How much SOL a day at most, squares and fees together. */
        val oreLamportsPerDay: Long = 50_000_000L,
        /** How many squares per round. */
        val oreSquares: Int = 3,
        /** At close, the profit (not the capital) is swapped into ORE and goes home as hard money. */
        val oreBury: Boolean = false,
    ) {
        // One lane. The wild one is where the money goes to die, and the paid bots that
        // win all run the same safe floor. [bold] is kept so an old budget still reads.
        val gate: ScanGate get() = ScanGate.CAREFUL
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
            maxFeePct = p.getInt("fee", 0),
            oreOn = p.getBoolean("oreOn", false),
            oreLamportsPerDay = p.getLong("oreDay", 50_000_000L),
            oreSquares = p.getInt("oreSquares", 3),
            oreBury = p.getBoolean("oreBury", false),
        )
    }

    fun setConfig(ctx: Context, c: Config) {
        prefs(ctx).edit()
            .putBoolean("on", c.on).putBoolean("bold", c.bold)
            .putInt("max", c.maxPositions).putInt("tp", c.takeProfitPct)
            .putInt("sl", c.stopLossPct).putInt("slice", c.slicePercent).putInt("fee", c.maxFeePct)
            .putBoolean("oreOn", c.oreOn).putLong("oreDay", c.oreLamportsPerDay).putInt("oreSquares", c.oreSquares)
            .putBoolean("oreBury", c.oreBury)
            .apply()
    }

    /**
     * Why it cannot start, in a person's words, or null. Checked before switching on:
     * the loop used to start, get refused, wait ninety seconds for nobody, then turn
     * itself off with a paragraph.
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
     * The loop switching itself off, said out loud. [stop] is what the Ferma button
     * calls and needs no notification. This is for the service deciding alone, app
     * closed: a note in the card was found two hours later, position unwatched.
     */
    fun stopSelf(ctx: Context, why: String) {
        stop(ctx, why)
        val unwatched = Positions.open(ctx).count { !it.parked && it.triggerOrder == null }
        val body = if (unwatched > 0) why + "\n" + ctx.getString(R.string.trader_unwatched, unwatched) else why
        AgentBroker.warn(ctx, ctx.getString(R.string.pulse_stopped), body, rhythm = AgentBroker.Rhythm.STOP)
    }

    /**
     * Switch on, forget the last stop's reason, and run the first tick now, under the
     * same lock as the alarm's ticks. Before, Start only flipped the switch and the
     * screen sat still for up to ninety seconds while the countdown could even grow.
     */
    fun start(ctx: Context, cfg: Config) {
        forgetRoom()
        setConfig(ctx, cfg.copy(on = true))
        // Digging pays the ORE program and its accounts from this budget: the
        // collar must know them by name or it refuses every move.
        OreAgent.reset(ctx)
        if (cfg.oreOn) {
            val s = SessionWallet.current(ctx); val p = SessionWallet.policy(ctx)
            val key = s?.let { Base58.decodePubkey(it.pubkey) }
            if (p != null && key != null) SessionWallet.setPolicy(
                ctx,
                p.copy(
                    allowedPrograms = p.allowedPrograms + com.clearsign.core.Ore.PROGRAM,
                    allowedDestinations = p.allowedDestinations + Base58.encode(OreMiner.automationPda(key)) + Base58.encode(OreMiner.minerPda(key)),
                ),
            )
        }
        prefs(ctx).edit().remove("note").apply()
        val app = ctx.applicationContext
        AppScope.launch { runCatching { tick(app, mayHunt = true) } }
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

    /** When the last look and the last hunt ran, for the clock on screen. In memory, observed by Compose. */

    val lookedAt = androidx.compose.runtime.mutableLongStateOf(0L)
    val huntedAt = androidx.compose.runtime.mutableLongStateOf(0L)

    /**
     * Two ticks back to back count as one: since Start runs a tick at once, it and the
     * alarm can land seconds apart. The lock keeps them in order, but the screen read
     * "looking at the market" twice. Within twelve seconds the second does nothing.
     * [tick] itself is safe to call any time: it checks, and returns without the
     * network when there is nothing to do, one at a time under the budget key's lock.
     */
    private const val SAME_TICK_MS = 12_000L

    suspend fun tick(ctx: Context, mayHunt: Boolean): Tick = EnvelopeLock.withLock {
        val now = System.currentTimeMillis()
        if (now - lookedAt.longValue < SAME_TICK_MS && lookedAt.longValue > 0L) {
            return@withLock Tick("already looked", acted = false)
        }
        lookedAt.longValue = now
        if (mayHunt) huntedAt.longValue = now
        AgentTrace.working { tickInner(ctx, mayHunt) }.also { if (it.acted) forgetRoom() }
    }

    /**
     * The budget's free SOL, read at most every half hour. It answers one question,
     * is there room for another slice, and only changes when the agent acts (we know)
     * or someone tops up (half an hour is fine). It was 240 reads a day for a number
     * that did not move.
     */
    private const val ROOM_TTL_MS = 30 * 60_000L
    @Volatile private var roomKey: String? = null
    @Volatile private var roomAt = 0L
    @Volatile private var roomLamports = 0L

    private suspend fun roomFor(pubkey: String): Long {
        val now = System.currentTimeMillis()
        if (roomKey == pubkey && now - roomAt < ROOM_TTL_MS) return roomLamports
        val read = withContext(Dispatchers.IO) { runCatching { SolanaRpc.getBalance(SolanaRpc.urlFor(null), pubkey) }.getOrNull() } ?: return 0L
        roomKey = pubkey; roomAt = now; roomLamports = read
        return read
    }

    fun forgetRoom() { roomAt = 0L }

    @Volatile private var budgetSaidDay = -1L

    /**
     * One more slice of a coin already in play, asked by a person at the screen. The
     * hunt's road minus the search: collar, receipt, and the row merges into the open
     * one. Returns what to tell the person.
     */
    suspend fun buyMore(ctx: Context, mint: String): String = EnvelopeLock.withLock {
        AgentTrace.working {
            val cfg = config(ctx)
            val s = SessionWallet.current(ctx) ?: return@working ctx.getString(R.string.trader_stop_nobudget)
            val p = SessionWallet.policy(ctx) ?: return@working ctx.getString(R.string.trader_stop_nobudget)
            val ceiling = minOf(p.perTxLamports, p.askAboveLamports.takeIf { it > 0 } ?: p.perTxLamports)
            val slice = (ceiling * cfg.slicePercent / 100).coerceAtLeast(0L)
            if (slice <= FEE * 4) return@working ctx.getString(R.string.trader_stop_toosmall)
            val t = withContext(Dispatchers.IO) { runCatching { JupiterTokens.candidateOf(mint) }.getOrNull() }
                ?: return@working ctx.getString(R.string.trader_net_down)
            AgentTrace.say(ctx.getString(R.string.trace_more, t.symbol), AgentTrace.Kind.FOUND)
            val rug = RugCheck.verdict(t.mint, deviceLocaleTag() == "it")
            if (rug is RugCheck.Verdict.Stop) {
                AgentTrace.say(ctx.getString(R.string.trace_rug_stop, t.symbol, rug.reason), AgentTrace.Kind.REFUSED)
                return@working ctx.getString(R.string.trace_rug_stop, t.symbol, rug.reason)
            }
            val built = SessionActions.buildSwap(Jupiter.SOL_MINT, t.mint, slice, s.pubkey) ?: return@working ctx.getString(R.string.trader_no_route)
            val quote = built.quote
            val tx = built.tx
            val reason = ctx.getString(R.string.trader_why_more, t.symbol)
            val intent = JSONObject().put("action", "swap").put("outMint", "SOL").put("outAmount", slice / 1e9)
                .put("inMint", t.symbol).put("inAmount", quote.outAmount / Math.pow(10.0, t.decimals.toDouble()))
                .put("expectMint", t.mint)
                .put("agent", AGENT).put("reason", reason)
            when (val v = handle(ctx, tx, intent, AgentBroker.Job.Source.IN_APP, built.ultraRequestId)) {
                is AgentBroker.Verdict.SignedSilently, is AgentBroker.Verdict.Confirmed -> {
                    val m = ctx.getString(R.string.trader_bought, t.symbol, fmtSol(slice, 4))
                    AgentTrace.say(m, AgentTrace.Kind.ACTED)
                    armOnChainExit(ctx, s.pubkey, t.mint)
                    note(ctx, m)
                    m
                }
                is AgentBroker.Verdict.Timeout -> ctx.getString(R.string.trader_needed_you)
                else -> v.reason ?: ctx.getString(R.string.trader_net_down)
            }
        }
    }

    /**
     * Bury the gain: [lamports] of budget SOL become ORE by the usual road (Jupiter
     * route, receipt, collar). Called on close, once everything above the capital is
     * SOL. Null when done, else the reason. If the collar wants a person, nobody is
     * there: the gain stays SOL and goes home that way.
     */
    suspend fun buryInOre(ctx: Context, lamports: Long): String? {
        val s = SessionWallet.current(ctx) ?: return ctx.getString(R.string.trader_stop_nobudget)
        val p = SessionWallet.policy(ctx) ?: return ctx.getString(R.string.trader_stop_nobudget)
        val ceiling = minOf(p.perTxLamports, p.askAboveLamports.takeIf { it > 0 } ?: p.perTxLamports)
        val amount = minOf(lamports, ceiling)
        if (amount <= FEE * 4) return ctx.getString(R.string.trader_stop_toosmall)
        val built = SessionActions.buildSwap(Jupiter.SOL_MINT, com.clearsign.core.Ore.MINT, amount, s.pubkey) ?: return ctx.getString(R.string.trader_no_route)
        val reason = ctx.getString(R.string.trader_why_bury)
        val intent = JSONObject().put("action", "swap").put("outMint", "SOL").put("outAmount", amount / 1e9)
            .put("inMint", "ORE").put("inAmount", built.quote.outAmount / 1e11)
            .put("expectMint", com.clearsign.core.Ore.MINT)
            .put("agent", AGENT).put("reason", reason)
        return when (val v = handle(ctx, built.tx, intent, AgentBroker.Job.Source.IN_APP, built.ultraRequestId)) {
            is AgentBroker.Verdict.SignedSilently, is AgentBroker.Verdict.Confirmed -> {
                val m = ctx.getString(R.string.trader_buried, fmtSol(amount, 4))
                AgentTrace.say(m, AgentTrace.Kind.ACTED)
                note(ctx, m)
                null
            }
            is AgentBroker.Verdict.Timeout -> ctx.getString(R.string.trader_needed_you)
            else -> v.reason ?: ctx.getString(R.string.trader_net_down)
        }
    }

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
            // The budget's time is up: sell, close, bring everything home, and say the account.
            val owner = Settings.watchWallet(ctx)
            val said = if (owner != null) runCatching { SessionActions.closeBudgetInner(ctx, owner) }.getOrNull() else null
            stopSelf(ctx, said?.second ?: ctx.getString(R.string.trader_stop_closed))
            return Tick(said?.second ?: ctx.getString(R.string.trader_stop_closed), acted = false, stopped = true)
        }
        // Paused is not stopped. Pause sets the mode OFF and Resume sets it back; this
        // used to switch trading off for good in between. Paused, the loop waits and
        // touches nothing.
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

        // The book is written from simulations, the chain is what is: compare them before
        // pricing anything off the book, but on a slower clock. It cost two chain reads
        // every ninety seconds, two thirds of an idle phone's daily traffic, to catch a
        // rare mismatch. Safe because a sale reads the wallet fresh (`heldRaw` with
        // `force`) and a ghost row is handled below without counting as a failure.
        val ghosts = if (sinceReconcile++ % RECONCILE_EVERY == 0) reconcile(ctx, s.pubkey) else null

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
        progress(ctx, cfg, exit.moves)
        exit.did?.let { note(ctx, it); return Tick(it, acted = true) }

        // A coin that could not be sold is worth saying, not a reason to skip the harvest
        // and the hunt. This used to return here, and one stuck position froze the agent.
        val problem = exit.problem ?: ghosts
        fun finish(t: Tick): Tick {
            if (t.acted || t.stopped || problem == null) return t
            note(ctx, problem)
            return t.copy(summary = problem)
        }

        Settings.watchWallet(ctx)?.let { owner ->
            val took = runCatching { SessionActions.harvestInner(ctx, owner) }.getOrNull()
            if (took != null && took > 0) {
                val m = ctx.getString(R.string.trader_harvested, fmtSol(took, 5))
                note(ctx, m)
                return Tick(m, acted = true)
            }
        }

        // ORE, when on: claim, report, or hand over. One move per tick like
        // everything else, and before the hunt because it is cheaper.
        if (cfg.oreOn && mayHunt) {
            runCatching { OreAgent.step(ctx, cfg, s, ceiling) }.getOrNull()?.let { t -> note(ctx, t.summary); return finish(t) }
        }

        if (!mayHunt) return finish(Tick("watching", acted = false))

        val open = Positions.open(ctx)
        if (open.size >= cfg.maxPositions) return finish(Tick("full", acted = false))

        // The gate that existed and was never wired: `MarketMood` reads three free sources
        // and calls itself "useful as a gate, useless as a forecast", yet only a chat tool
        // called it. It sits here because it is about the hunt only: exits are done, and an
        // open position is watched even while the world burns. No reading, no block.
        val mood = withContext(Dispatchers.IO) { runCatching { MarketMood.read() }.getOrNull() }
        if (mood != null && !mood.riskOn) {
            val said = ctx.getString(R.string.trader_mood_red)
            AgentTrace.say(said, AgentTrace.Kind.WARN)
            note(ctx, said)
            return finish(Tick(said, acted = false))
        }

        // The daily cap on the shared keys: the hunt stops, the exits above have
        // already run and never stop. Said once a day.
        if (Rpc.overBudget()) {
            val day = System.currentTimeMillis() / RpcPool.DAY_MS
            if (budgetSaidDay != day) {
                budgetSaidDay = day
                val said = ctx.getString(R.string.trader_budget)
                AgentTrace.say(said, AgentTrace.Kind.WARN)
                note(ctx, said)
            }
            return finish(Tick("budget", acted = false))
        }

        val balance = roomFor(s.pubkey)
        if (balance < slice + FEE * 4) return finish(Tick("no room", acted = false))

        return finish(hunt(ctx, cfg, slice, open.map { it.mint }.toSet(), p))
    }

    // ---- the book against the chain ------------------------------------------

    /**
     * Believe the chain, not the book. The position list is written from simulations,
     * and between then and now a budget can be remade, an order can fill, a coin can be
     * sold from the chat. A stale row is an agent proposing to sell what it does not
     * have, every ninety seconds. Nothing is dropped on a network failure: not reaching
     * Jupiter is not evidence a coin is gone. Returns a line to show, or null.
     */
    private suspend fun reconcile(ctx: Context, envelope: String): String? {
        val book = Positions.all(ctx)
        if (book.isEmpty()) return null
        val held = SessionActions.heldRaw(ctx, envelope) ?: return null
        // Jupiter is asked when a row has nothing behind it (an order holding the coins?)
        // and when a row carries an order key (still live?). A key is dropped only on
        // Jupiter's word: a cancel accepted but never landed once left coins in escrow
        // with no key to reach them.
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

    /**
     * How the open coins are doing, where a person sees it without opening the app.
     * A quiet card rewritten every tick ("Bert −2% · ALL +21%"), plus one notification
     * each time a coin crosses a new ten percent line, up or down. Lines already
     * crossed are kept per coin and forgotten when it is bought again.
     */
    private fun progress(ctx: Context, cfg: Config, moves: Map<String, Double>) {
        val open = Positions.open(ctx)
        if (open.isEmpty()) { AgentBroker.progressClear(ctx); return }
        val p = prefs(ctx)
        val lines = open.map { pos ->
            val m = moves[pos.mint]
            val shown = if (m == null) "…" else (if (m >= 0) "+" else "") + String.format("%.0f", m) + "%"
            if (m != null) {
                val bucket = (m / 10).toInt()
                val hi = p.getInt("hi_" + pos.mint, 0)
                val lo = p.getInt("lo_" + pos.mint, 0)
                if (bucket > hi || bucket < lo) {
                    p.edit().putInt(if (bucket > hi) "hi_" + pos.mint else "lo_" + pos.mint, bucket).apply()
                    val art = runCatching { NotifArt.tracks(ctx, listOf(NotifArt.Row(pos.symbol, m, cfg.takeProfitPct, cfg.stopLossPct))) }.getOrNull()
                    AgentBroker.warn(
                        ctx, ctx.getString(R.string.trader_milestone_title, pos.symbol, shown),
                        ctx.getString(R.string.trader_milestone_body, cfg.takeProfitPct, cfg.stopLossPct),
                        picture = art,
                        color = (if (m >= 0) Halo.palette.accent else Halo.palette.red).toArgb(),
                        rhythm = if (bucket > hi) AgentBroker.Rhythm.UP else AgentBroker.Rhythm.DOWN,
                        actions = listOf(
                            android.app.Notification.Action.Builder(null, ctx.getString(R.string.notif_sell_now), AgentActionReceiver.sellIntent(ctx, pos.mint)).build(),
                            android.app.Notification.Action.Builder(null, ctx.getString(R.string.notif_stop), AgentActionReceiver.stopIntent(ctx)).build(),
                        ),
                        id = AgentBroker.MILESTONE_ID,
                        largeIcon = runCatching { NotifArt.logo(ctx, TokenSymbols.image(pos.mint)) }.getOrNull(),
                    )
                }
            }
            pos.symbol + " " + shown
        }
        val art = runCatching {
            NotifArt.tracks(ctx, open.map { NotifArt.Row(it.symbol, moves[it.mint], cfg.takeProfitPct, cfg.stopLossPct) })
        }.getOrNull()
        AgentBroker.progress(
            ctx, ctx.resources.getQuantityString(R.plurals.trader_progress_title, open.size, open.size),
            lines.joinToString(" · "), art,
        )
    }

    /** What the exit half of a tick did: one action, or one problem worth saying. */
    private data class ExitOutcome(val did: String? = null, val problem: String? = null, val moves: Map<String, Double> = emptyMap())

    /**
     * Sell whatever reached its target or its stop. One sale per tick, on purpose: two
     * in the same minute would price against a market the first just moved. A coin that
     * cannot be sold never blocks the others; a failure walks on.
     */
    private suspend fun exits(ctx: Context, envelope: String): ExitOutcome {
        val at = System.currentTimeMillis()
        var problem: String? = null
        val moves = LinkedHashMap<String, Double>()
        // Wallets the person mirrors: when one of them sold a coin we hold in
        // the last hour, we leave too, before any price rule.
        val mirrored = Follows.mirrors(ctx)
        val soldByMirror: Map<String, com.clearsign.core.CrowdSignal.FollowedSell> = if (mirrored.isEmpty()) emptyMap() else runCatching {
            val feed = SeekerFeed.cached(ctx) ?: return@runCatching emptyMap()
            com.clearsign.core.SeekerCrowd.signals(feed.events, mirrored, at).filterIsInstance<com.clearsign.core.CrowdSignal.FollowedSell>().associateBy { it.mint }
        }.getOrDefault(emptyMap())
        for (pos in Positions.open(ctx)) {
            // Failed three times already: it gets a slower lane, not the same
            // ninety seconds forever.
            if (at < pos.readyAt) continue
            val now = unitPriceLamports(pos) ?: continue
            pos.entryLamports?.takeIf { it > 0 }?.let { moves[pos.mint] = (now - it) / it * 100.0 }
            soldByMirror[pos.mint]?.takeIf { it.at > pos.openedAt }?.let { sig ->
                val why = ctx.getString(R.string.scout_signal_sold, com.clearsign.core.SeekerCrowd.nickname(sig.wallet), pos.symbol)
                AgentTrace.say(why, AgentTrace.Kind.FOUND)
                Positions.markClosing(ctx, pos.mint)
                val sale = SessionActions.sellNow(ctx, pos, why, AgentBroker.Job.Source.LINK, acceptReal = true)
                val v = (sale as? SessionActions.Sale.Judged)?.verdict
                if (v is AgentBroker.Verdict.SignedSilently || v is AgentBroker.Verdict.Confirmed) {
                    Positions.remove(ctx, pos.mint)
                    runCatching { SessionActions.closeEmpty(ctx, envelope) }
                    return ExitOutcome(did = ctx.getString(R.string.trader_sold_mirror, pos.symbol, com.clearsign.core.SeekerCrowd.nickname(sig.wallet)), moves = moves)
                }
                val whyNot = v?.reason ?: ctx.getString(R.string.trader_net_down)
                Positions.noteFailure(ctx, pos.mint, whyNot)
                problem = ctx.getString(R.string.trader_sell_failed, pos.symbol, whyNot)
                continue
            }
            val exit = pos.verdict(now) ?: continue

            // The coins sit in Jupiter's escrow under a take-profit order, so no swap of ours
            // can move them: take the order back first, sell next tick. Otherwise the stop
            // could never fire on the very position most worth protecting.
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
                // The key stays. "Accepted" is not "landed": the next reconcile drops it once
                // Jupiter no longer lists the order and the coins are back. A fallen cancel
                // leaves the row parked with its key, and this runs again.
                return ExitOutcome(did = ctx.getString(R.string.trader_order_cancelled, pos.symbol), moves = moves)
            }

            val moved = (if (exit.movePct >= 0) "+" else "") + String.format("%.1f", exit.movePct) + "%"
            val why = ctx.getString(
                if (exit.why == Positions.Exit.Why.TARGET) R.string.trader_why_target else R.string.trader_why_stop,
                pos.symbol,
            )
            Positions.markClosing(ctx, pos.mint)
            // A stop is about getting out, so it accepts whatever the chain gives for a thin
            // coin. A target is about the price: unconfirmed by the chain, it is not reached.
            val sale = SessionActions.sellNow(ctx, pos, why, AgentBroker.Job.Source.LINK, acceptReal = exit.why == Positions.Exit.Why.STOP)
            if (sale is SessionActions.Sale.Worse) {
                val drop = ((1 - sale.realLamports.toDouble() / sale.quotedLamports) * 100).toInt()
                val m = ctx.getString(R.string.trader_target_unreal, pos.symbol, fmtSol(sale.realLamports, 4), drop)
                Positions.note(ctx, pos.mint, m)
                problem = m
                continue
            }
            val v = (sale as? SessionActions.Sale.Judged)?.verdict
            if (v is AgentBroker.Verdict.SignedSilently || v is AgentBroker.Verdict.Confirmed) {
                Positions.remove(ctx, pos.mint)
                // The emptied account's rent goes back into the budget now, not at the end.
                runCatching { SessionActions.closeEmpty(ctx, envelope) }
                return ExitOutcome(
                    did = ctx.getString(
                        if (exit.why == Positions.Exit.Why.TARGET) R.string.trader_sold_target else R.string.trader_sold_stop,
                        pos.symbol, moved,
                    ),
                    moves = moves,
                )
            }
            // Put it back rather than strand it: a failed sale is one to retry, with its
            // reason. "Could not sell, trying again shortly" eighty-four times is how sixteen
            // hours passed with nobody knowing the coin was not in the wallet.
            Positions.reopen(ctx, pos.mint)
            // A node that did not answer and a coin not in the wallet are not failures of this
            // coin: counting them once pushed a live stop-loss into the six-hour lane after
            // three network blinks.
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
        return ExitOutcome(problem = problem, moves = moves)
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
        // What the Seeker crowd is buying, ahead of the registry ranking: a followed wallet
        // bought, or two whales bought the same coin within the hour. Same gates, same
        // slice. Copy trading with a collar: the signal says where to look, never what to
        // do, and it arrives minutes late by design, so the gates are not optional.
        val scout = scoutPicks(ctx, cfg, held)
        val picks = scout + scan.picks.filter { p -> scout.none { it.c.mint == p.c.mint } }
        AgentTrace.say(ctx.getString(R.string.trace_scanned, pool.size, picks.size))
        // The commonest reason the whole field was thrown out, said once. It is
        // the difference between "found nothing" and "found nothing because every
        // coin out there today can still be frozen by its creator".
        scan.rejected.maxByOrNull { it.value }?.let { (why, n) ->
            if (picks.isEmpty()) AgentTrace.say(ctx.getString(R.string.trace_rejected, n, why), AgentTrace.Kind.REFUSED)
        }
        // What the base does today, one call before the loop. The question is not "does
        // this coin beat SOL" five times but "does none of them" once. The SOL price is
        // already fetched for the spread check; `change24h` was simply unused.
        val baseMove = withContext(Dispatchers.IO) {
            runCatching { Prices.quotes(listOf(com.clearsign.core.NATIVE_SOL_MINT))[com.clearsign.core.NATIVE_SOL_MINT]?.change24h }.getOrNull()
        }
        var baseBeat: String? = null
        for (pick in picks) {
            val t = pick.c
            // Buy, or keep what you already hold. The loop knew "is this a trap" and "which of
            // the five is best", not the third question that matters when everything rises.
            // 18 Sep 2026: budget −5.1% while SOL did +10.4%, fees 0.9% of the loss. It was
            // the coins, not the costs.
            val lagsBase = com.clearsign.core.beatsBase(t, baseMove)
            if (lagsBase != null) {
                baseBeat = lagsBase
                AgentTrace.say(lagsBase, AgentTrace.Kind.WARN)
                continue
            }
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
            // The pool's story, from Rugcheck: LP burned or pullable, copies of
            // verified coins, creators who rugged before. Unknown never blocks.
            when (val rug = RugCheck.verdict(t.mint, deviceLocaleTag() == "it")) {
                is RugCheck.Verdict.Stop -> {
                    AgentTrace.say(ctx.getString(R.string.trace_rug_stop, t.symbol, rug.reason), AgentTrace.Kind.REFUSED)
                    continue
                }
                is RugCheck.Verdict.Ok -> AgentTrace.say(
                    ctx.getString(R.string.trace_rug_ok, t.symbol, rug.score, rug.lpLockedPct?.let { String.format(java.util.Locale.ROOT, "%.0f%%", it) } ?: "?"),
                )
                RugCheck.Verdict.Unknown -> AgentTrace.say(ctx.getString(R.string.trace_rug_unknown, t.symbol))
            }
            // Jupiter's own shield: critical stops, a warning is said, info is nothing.
            val shield = withContext(Dispatchers.IO) { runCatching { TokenShield.warnings(t.mint) }.getOrNull() }
            // A fee within the chosen threshold is said and let through; every
            // other critical warning stops as before.
            val stopper = shield?.firstOrNull { w ->
                w.critical && (w.transferFeePct?.let { it > cfg.maxFeePct } ?: true)
            }
            if (stopper != null) {
                AgentTrace.say(ctx.getString(R.string.trace_shield_stop, t.symbol, stopper.message), AgentTrace.Kind.REFUSED)
                continue
            }
            shield?.firstOrNull { it.critical && it.transferFeePct != null }?.let { w ->
                AgentTrace.say(ctx.getString(R.string.trace_fee_ok, t.symbol, w.transferFeePct?.toInt() ?: 0, cfg.maxFeePct))
            }
            shield?.firstOrNull { it.warning }?.let { w -> AgentTrace.say(ctx.getString(R.string.trace_shield_warn, t.symbol, w.message)) }

            val s = SessionWallet.current(ctx) ?: return Tick("no budget", acted = false)
            val built = SessionActions.buildSwap(Jupiter.SOL_MINT, t.mint, slice, s.pubkey) ?: continue
            val quote = built.quote

            // What one whole coin costs at this moment, straight out of the quote
            // the trade would have used. The shadow book starts here and nowhere
            // better: no invented price, no price we were not actually offered.
            val units = quote.outAmount / Math.pow(10.0, t.decimals.toDouble())
            val entry = if (units > 0) slice / units else 0.0
            fun shadow(blockedBy: String?) = Paper.open(
                ctx, t.mint, t.symbol, t.decimals, entry, slice, quote.priceImpactPct * 100.0,
                cfg.takeProfitPct, cfg.stopLossPct, blockedBy,
            )

            // The one question that is not arithmetic: does the web know something about this
            // coin. Only here, after everything cheap said yes, on the one coin about to be
            // bought: it costs about a cent a time. After the quote, so a stopped coin still
            // enters the shadow book with a real entry price.
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

            // What the trade costs before the price moves. The collar refuses a silent
            // exchange returning under nine tenths, rightly: a slice moves a thin coin. The
            // loop used to learn that by proposing and being asked for an absent fingerprint;
            // the same arithmetic here skips the coin and the hunt goes on.
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

            val tx = built.tx
            val reason = ctx.getString(R.string.trader_why_buy, t.symbol, pick.notes.firstOrNull() ?: "")
            val intent = JSONObject().put("action", "swap").put("outMint", "SOL").put("outAmount", slice / 1e9)
                .put("inMint", t.symbol).put("inAmount", quote.outAmount / Math.pow(10.0, t.decimals.toDouble()))
                .put("expectMint", t.mint)
                .put("agent", AGENT).put("reason", reason)
            val v = handle(ctx, tx, intent, ultraRequestId = built.ultraRequestId)
            if (v is AgentBroker.Verdict.SignedSilently) {
                prefs(ctx).edit().remove("hi_" + t.mint).remove("lo_" + t.mint).apply()
                AgentTrace.say(ctx.getString(R.string.trader_bought, t.symbol, fmtSol(slice, 4)), AgentTrace.Kind.ACTED)
                shadow(null)
                armOnChainExit(ctx, s.pubkey, t.mint)
                val m = ctx.getString(R.string.trader_bought, t.symbol, fmtSol(slice, 4))
                note(ctx, m)
                return Tick(m, acted = true)
            }
            // Anything that is not a silent signature ends this tick's hunt: walking on once
            // showed the approval screen five times in a row. A timeout means the collar wanted
            // a person and nobody was there. The message used to blame the account rent every
            // time; the real case was the daily cap, half spent by the morning trade. The collar
            // knows which rule stopped it, and says so.
            if (v is AgentBroker.Verdict.Timeout) {
                shadow("collar")
                AgentTrace.say(ctx.getString(R.string.trace_asked, t.symbol), AgentTrace.Kind.REFUSED)
                val m = when (v.rule) {
                    // The daily cap, said so something can be done. "Exhausted, raise it in the rules"
                    // was impossible advice when the cap already is the whole budget: now it says
                    // how much is left, when it frees, and to raise it only if it can be raised.
                    "daily" -> {
                        val left = (p.dailyLamports - SessionWallet.history(ctx).spentLast24hLamports).coerceAtLeast(0L)
                        val free = SessionWallet.freesAt(ctx)?.let { at ->
                            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(at))
                        } ?: "—"
                        val cap = SessionWallet.current(ctx)?.capLamports ?: 0L
                        if (p.dailyLamports >= cap) ctx.getString(R.string.trader_daily_full_max, fmtSol(p.dailyLamports, 4), fmtSol(left, 4), free)
                        else ctx.getString(R.string.trader_daily_full, fmtSol(p.dailyLamports, 4), fmtSol(left, 4), free)
                    }
                    "per_tx" -> ctx.getString(R.string.trader_per_tx_full, fmtSol(slice, 4), fmtSol(p.perTxLamports, 4))
                    "silent_threshold" -> ctx.getString(R.string.trader_above_silent, fmtSol(slice, 4), fmtSol(p.askAboveLamports, 4))
                    null -> ctx.getString(R.string.trader_too_small_why, fmtSol(slice, 4), fmtSol(ATA_RENT, 4))
                    else -> ctx.getString(R.string.trader_asked_why, t.symbol, v.reason ?: v.rule.orEmpty())
                }
                stopSelf(ctx, m)
                return Tick(m, acted = false, stopped = true)
            }
            // Turned down by a person: asking again in six minutes makes the agent a nuisance.
            // Turned down by the collar: quote it, do not put it in their mouth. A dropped
            // network is neither: skip the coin and carry on, or the loop spends a night off.
            if (v is AgentBroker.Verdict.Refused && v.rule == "no_simulation") {
                AgentTrace.say(ctx.getString(R.string.trace_no_sim, t.symbol), AgentTrace.Kind.REFUSED)
                continue
            }
            // The node ran it and it failed: a bad swap for this coin now, with the node's own
            // reason. Not a blip, not a reason to stop. It used to read "did not answer" and
            // the same broken swap was retried every round.
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
        // None of the five beat what you already hold. Not a failed hunt, an
        // answer, and it has to be said or the card sits on the last buy as if
        // nothing happened.
        baseBeat?.let {
            val said = ctx.getString(R.string.trader_base_wins, String.format(java.util.Locale.ROOT, "%+.1f%%", baseMove ?: 0.0))
            note(ctx, said)
            return Tick(said, acted = false)
        }
        return Tick("nothing passed", acted = false)
    }

    /**
     * The live feed's candidates, graded like everybody else's. One registry call per
     * signal, bounded so a loud hour cannot spend the budget on lookups. Each pick
     * carries its signal in the first note, so receipt and trace say why this coin.
     */
    private suspend fun scoutPicks(ctx: Context, cfg: Config, held: Set<String>): List<com.clearsign.core.Scored> {
        // The slow window: nobody is watching, and the crowd signal arrives late
        // by design. See SeekerFeed.SLOW_FRESH_MS.
        val feed = withContext(Dispatchers.IO) {
            runCatching { SeekerFeed.refresh(ctx, SeekerFeed.SLOW_FRESH_MS) ?: SeekerFeed.cached(ctx) }.getOrNull()
        } ?: return emptyList()
        val follows = Follows.all(ctx)
        val signals = com.clearsign.core.SeekerCrowd.signals(feed.events, follows, System.currentTimeMillis())
            .filter { it.mint !in held }.take(4)
        if (signals.isEmpty()) return emptyList()
        AgentTrace.say(ctx.getString(R.string.trace_scout, signals.size), AgentTrace.Kind.FOUND)
        val out = ArrayList<com.clearsign.core.Scored>()
        for (sig in signals) {
            val c = withContext(Dispatchers.IO) { runCatching { JupiterTokens.candidateOf(sig.mint) }.getOrNull() } ?: continue
            val why = when (sig) {
                is com.clearsign.core.CrowdSignal.Followed -> ctx.getString(
                    R.string.scout_signal_follow, com.clearsign.core.SeekerCrowd.nickname(sig.wallet), c.symbol,
                    ((System.currentTimeMillis() - sig.at) / 60_000L).coerceAtLeast(0L),
                )
                is com.clearsign.core.CrowdSignal.Crowd -> ctx.getString(R.string.scout_signal_crowd, sig.whales, c.symbol)
                // A sale is a reason to leave, not to enter: the exits read those.
                is com.clearsign.core.CrowdSignal.FollowedSell -> continue
            }
            val graded = com.clearsign.core.scanMarket(listOf(c), cfg.gate, limit = 1)
            val pick = graded.picks.firstOrNull()
            if (pick == null) {
                AgentTrace.say(ctx.getString(R.string.trace_scout_rejected, c.symbol, graded.rejected.keys.firstOrNull() ?: ""), AgentTrace.Kind.REFUSED)
                continue
            }
            AgentTrace.say(why, AgentTrace.Kind.FOUND)
            out += pick.copy(notes = listOf(why) + pick.notes)
        }
        return out
    }

    /**
     * Put the take-profit on chain when Jupiter accepts it: the only exit that outlives
     * the app. Tried right after the buy and allowed to fail quietly; a missing order
     * means the loop watches the target instead, a smaller promise, not a broken one.
     */
    private suspend fun armOnChainExit(ctx: Context, envelope: String, mint: String) {
        val pos = Positions.open(ctx).firstOrNull { it.mint == mint } ?: return
        when (val r = runCatching { JupiterTrigger.placeTakeProfit(ctx, envelope, pos) }.getOrNull()) {
            is JupiterTrigger.Placed.Ok -> Positions.setTrigger(ctx, mint, r.order)
            // Under about five dollars Jupiter takes no order. Nothing to retry, but it means
            // the only exit that outlives the app is missing, so it is said on the row.
            // Measured 2026-09-15: 0.036 SOL at 99 dollars is 3.60, its target 4.68, both under.
            JupiterTrigger.Placed.TooSmall -> {
                Positions.note(ctx, mint, ctx.getString(R.string.trader_no_onchain_small))
                AgentTrace.say(ctx.getString(R.string.trader_no_onchain_small), AgentTrace.Kind.WARN)
            }
            // Any other no, said. It was `else -> Unit`: the app had just promised an on-chain
            // order and went quiet. A silent failure on a promise is a lie on a timer.
            is JupiterTrigger.Placed.Failed -> {
                val why = ctx.getString(R.string.trader_no_onchain_why, r.reason)
                Positions.note(ctx, mint, why)
                AgentTrace.say(why, AgentTrace.Kind.WARN)
            }
            null -> {
                val why = ctx.getString(R.string.trader_no_onchain_why, "?")
                Positions.note(ctx, mint, why)
                AgentTrace.say(why, AgentTrace.Kind.WARN)
            }
        }
    }

    // ---- the one door --------------------------------------------------------

    const val AGENT = "apex-trader"

    /**
     * Everything goes through the collar, like a move proposed in chat. Source is LINK,
     * not `IN_APP`: with `IN_APP` the broker posts no notification when it wants a
     * person, and a background move would wait ninety seconds on a screen never shown.
     */
    internal suspend fun handle(
        ctx: Context, tx: ByteArray, intent: JSONObject, source: AgentBroker.Job.Source = AgentBroker.Job.Source.LINK, ultraRequestId: String? = null,
    ): AgentBroker.Verdict =
        AgentBroker.handle(
            ctx,
            AgentBroker.Job(
                id = LedgerRecorder.newId(), tx = tx, intentJson = intent.toString(),
                cluster = null, agent = AGENT, source = source, ultraRequestId = ultraRequestId,
            ),
        )
}
