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
    /**
     * Ogni quanto si va a caccia: sei minuti col nodo proprio, dodici sulle
     * chiavi condivise. Le uscite restano a novanta secondi in tutti e due i
     * casi, perche' lo stop loss non aspetta nessuno. Le due corsie: chi porta
     * il suo nodo respira piu' in fretta, chi usa quello di tutti ne usa meta'.
     */
    private const val HUNT_OWN_MS = 360_000L
    private const val HUNT_SHARED_MS = 720_000L
    val HUNT_EVERY_MS: Long get() = if (Rpc.ownNode != null) HUNT_OWN_MS else HUNT_SHARED_MS

    /**
     * Quanto aspettare prima del prossimo giro.
     *
     * I novanta secondi esistono per una cosa sola: lo stop loss, che deve
     * accorgersi in fretta se quello che teniamo sta cadendo. **A mani vuote non
     * c'e' niente da guardare cadere.** Il giro faceva lo stesso il suo respiro
     * corto, e ogni volta chiedeva il saldo alla catena per sapere se c'era da
     * raccogliere: 960 chiamate al giorno per non fare niente.
     *
     * Non e' un dettaglio quando le installazioni sono tante. Il nodo compilato
     * dentro l'app e' uno solo per tutti, e la maggior parte dei telefoni, in un
     * momento qualsiasi, non tiene niente in mano: sono proprio quelli che
     * pagavano di piu'. A mani vuote si respira come la caccia, che tanto la
     * caccia e' l'unica cosa che puo' succedere.
     */
    fun breath(ctx: Context): Long = if (Positions.open(ctx).isEmpty()) HUNT_EVERY_MS else EXIT_EVERY_MS

    /** Ogni quanti giri si confronta il libro con la catena. Vedi `tickInner`. */
    private const val RECONCILE_EVERY = 5
    private var sinceReconcile = 0

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
        /**
         * La commissione della moneta che si accetta, in percento. Zero: nessuna.
         *
         * Lo scudo di Jupiter ferma su qualsiasi avviso critico, e marca critica
         * anche la commissione che un token trattiene a ogni trasferimento.
         * Visto il 17/09: cinque monete esaminate di fila, quattro scartate per
         * una commissione fra l'uno e il tre per cento, e l'agente che non
         * comprava niente senza che si potesse fare nulla al riguardo. Una
         * commissione non e' una truffa, e' un costo: chi mette i soldi decide se
         * il costo gli sta bene. Tutto il resto dello scudo continua a fermare.
         */
        val maxFeePct: Int = 0,
        /** L'agente scava ORE: affida una parte della paghetta a un esecutore, sotto il collare. */
        val oreOn: Boolean = false,
        /** Quanto SOL al giorno, al massimo, fra caselle e fee. */
        val oreLamportsPerDay: Long = 50_000_000L,
        /** Su quante caselle a giro. */
        val oreSquares: Int = 3,
        /** Alla chiusura, il guadagno (non il capitale) si scambia in ORE e va a casa in moneta dura. */
        val oreBury: Boolean = false,
    ) {
        // One lane. There used to be two and the agent asked which; on Solana
        // the honest answer is that the wild one is where the money goes to die,
        // and the paid bots that win all run the same safe floor. [bold] stays
        // stored so an old budget still reads, and is ignored.
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
        AgentBroker.warn(ctx, ctx.getString(R.string.pulse_stopped), body, rhythm = AgentBroker.Rhythm.STOP)
    }

    /** Switch on, and forget what the last stop said: that was about a run that is over. */
    /**
     * Acceso vuol dire che parte adesso, non alla prossima sveglia.
     *
     * Accendeva l'interruttore e basta, e il primo giro arrivava quando toccava
     * alla sveglia di sfondo: fino a un minuto e mezzo di schermo fermo dopo aver
     * premuto un tasto che dice Start. Peggio, il conto alla rovescia partiva da
     * dove si trovava, quindi poteva anche crescere sotto gli occhi.
     *
     * Un tasto che dice start deve far partire qualcosa mentre lo stai ancora
     * guardando. La sveglia continua a fare il suo mestiere per tutto il resto
     * del tempo: questo e' solo il primo giro, subito, sotto lo stesso lucchetto
     * di tutti gli altri, quindi non puo' accavallarsi con uno in corso.
     */
    fun start(ctx: Context, cfg: Config) {
        forgetRoom()
        setConfig(ctx, cfg.copy(on = true))
        // Scavare vuol dire pagare il programma di ORE e i suoi conti di questa
        // paghetta: il collare li deve conoscere per nome, o rifiuta ogni mossa.
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

    /**
     * When the last look started and when the last hunt ran, for a screen that
     * shows the clock. In memory and observed by Compose: a window, not a record.
     */

    val lookedAt = androidx.compose.runtime.mutableLongStateOf(0L)
    val huntedAt = androidx.compose.runtime.mutableLongStateOf(0L)

    /**
     * One round. Safe to call as often as you like: it does its own checks and
     * returns without touching the network when there is nothing to do. One at a
     * time, whoever asks, and the lock is shared with every other action that
     * signs with the budget key.
     */
    /**
     * Due giri attaccati sono un giro, non due.
     *
     * Da quando Start fa partire subito il primo giro, quel giro e la sveglia di
     * sfondo possono capitare a pochi secondi l'uno dall'altro: il lucchetto li
     * mette in fila, quindi non si rompe niente, ma sullo schermo si legge due
     * volte "guardo il mercato" e sembra che l'agente balbetti o che stia
     * spendendo il doppio. Nei primi dodici secondi il secondo non fa niente.
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
     * Il SOL libero della paghetta, letto al massimo ogni mezz'ora.
     *
     * Serve a una domanda sola, «c'e' posto per un'altra fetta», e la risposta
     * cambia solo quando l'agente agisce o quando qualcuno ricarica. La prima
     * la sappiamo e si dimentica il numero; la seconda aspetta al massimo una
     * mezz'ora. Erano duecentoquaranta letture al giorno per un numero che non
     * si muoveva.
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
     * One more slice of a coin already in play, asked by a person looking at
     * the screen. The hunt's own road minus the search: the collar judges it,
     * the receipt records it, and the row merges into the one already open.
     * Returns what to tell the person.
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
     * Seppellire il guadagno: [lamports] di SOL della paghetta diventano ORE,
     * con la stessa strada di ogni acquisto (rotta Jupiter, scontrino, collare).
     * Si chiama alla chiusura, quando quello che c'e' sopra il capitale e' gia'
     * tutto in SOL. Torna null se e' andata, altrimenti il perche'.
     *
     * Il collare vale anche qui: la fetta non supera il tetto per mossa, e se
     * la regola chiede una persona alla chiusura non c'e' nessuno, quindi il
     * guadagno resta in SOL e torna a casa cosi'. Meglio di una domanda a vuoto.
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
        //
        // Su un orologio piu' lento del giro, pero'. Costa due letture della
        // catena e girava ogni novanta secondi, cioe' due terzi di tutto quello
        // che un telefono con una posizione aperta chiede in un giorno, per
        // scoprire un disallineamento che capita una volta ogni tanto.
        //
        // Rallentarlo e' sicuro per due ragioni misurate, non sperate. Una
        // vendita legge il borsello **fresco per conto suo** (`heldRaw` con
        // `force`), quindi non decide mai su un libro vecchio. E una riga
        // fantasma, cioe' una moneta che il libro ha e il borsello no, e' gia'
        // gestita qui sotto senza contarla come fallimento: costa una lettura a
        // vuoto per qualche giro, finche' la riconciliazione non passa e la
        // toglie.
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
            val took = runCatching { SessionActions.harvestInner(ctx, owner) }.getOrNull()
            if (took != null && took > 0) {
                val m = ctx.getString(R.string.trader_harvested, fmtSol(took, 5))
                note(ctx, m)
                return Tick(m, acted = true)
            }
        }

        // ORE, quando e' acceso: riscuote, racconta, o affida. Una mossa per giro,
        // come tutto il resto, e prima della caccia perche' e' piu' economica.
        if (cfg.oreOn && mayHunt) {
            runCatching { OreAgent.step(ctx, cfg, s, ceiling) }.getOrNull()?.let { t -> note(ctx, t.summary); return finish(t) }
        }

        if (!mayHunt) return finish(Tick("watching", acted = false))

        val open = Positions.open(ctx)
        if (open.size >= cfg.maxPositions) return finish(Tick("full", acted = false))

        // Il cancello che c'era gia' e non era montato.
        //
        // `MarketMood` legge tre fonti gratuite e si descrive da solo cosi':
        // "useful as a gate — do not go hunting while everything is bleeding —
        // and useless as a forecast". Scritto, documentato, e chiamato **solo**
        // da uno strumento della chat, perche' il modello ne parlasse. Il ciclo
        // non lo importava nemmeno.
        //
        // Sta qui e non piu' in basso perche' riguarda la caccia e nient'altro:
        // le uscite sono gia' passate, e una posizione aperta va guardata anche
        // mentre il mondo brucia. E se la lettura non arriva si va a caccia lo
        // stesso: quello che non si sa non blocca mai.
        val mood = withContext(Dispatchers.IO) { runCatching { MarketMood.read() }.getOrNull() }
        if (mood != null && !mood.riskOn) {
            val said = ctx.getString(R.string.trader_mood_red)
            AgentTrace.say(said, AgentTrace.Kind.WARN)
            note(ctx, said)
            return finish(Tick(said, acted = false))
        }

        // Il tetto del giorno sulle chiavi condivise: la caccia si ferma, le
        // uscite sopra sono gia' passate e non si fermano. Detto una volta al giorno.
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

    /**
     * How the open coins are doing, said where a person can see it without
     * opening the app.
     *
     * Two things, and neither asks a model anything: the prices are the ones
     * the exit check just read. A quiet card, no sound, rewritten every tick:
     * "Bert −2% · ALL +21%". And one real notification each time a coin crosses
     * a new ten percent line it had not reached before, up or down, so a coin
     * at +21% has said "+10" and "+20" once each and then goes silent until
     * +30, where the loop sells it anyway. The lines already crossed are kept
     * per coin and forgotten when the coin is bought again.
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
                return ExitOutcome(did = ctx.getString(R.string.trader_order_cancelled, pos.symbol), moves = moves)
            }

            val moved = (if (exit.movePct >= 0) "+" else "") + String.format("%.1f", exit.movePct) + "%"
            val why = ctx.getString(
                if (exit.why == Positions.Exit.Why.TARGET) R.string.trader_why_target else R.string.trader_why_stop,
                pos.symbol,
            )
            Positions.markClosing(ctx, pos.mint)
            // A stop is about getting out, and a thin coin's real price is
            // whatever the chain gives: the stop accepts it. A target is about
            // the price, so a target that the chain does not confirm is not
            // reached, and the row waits.
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
        // What the Seeker crowd is buying right now, ahead of the registry's
        // ranking. A wallet the person follows bought something, or two whales
        // bought the same thing inside the hour: those coins are looked at first,
        // through exactly the same gates, and with the same slice. This is copy
        // trading with a collar on: the signal says where to look, never what to
        // do, and it arrives a few minutes late by design, which is why the gates
        // are not optional here.
        val scout = scoutPicks(ctx, cfg, held)
        val picks = scout + scan.picks.filter { p -> scout.none { it.c.mint == p.c.mint } }
        AgentTrace.say(ctx.getString(R.string.trace_scanned, pool.size, picks.size))
        // The commonest reason the whole field was thrown out, said once. It is
        // the difference between "found nothing" and "found nothing because every
        // coin out there today can still be frozen by its creator".
        scan.rejected.maxByOrNull { it.value }?.let { (why, n) ->
            if (picks.isEmpty()) AgentTrace.say(ctx.getString(R.string.trace_rejected, n, why), AgentTrace.Kind.REFUSED)
        }
        // Quanto fa la base oggi. Una chiamata sola, prima del ciclo.
        //
        // La domanda giusta non e' "questa moneta batte SOL", e' **nessuna di
        // queste cinque batte SOL**: la prima si risponde cinque volte, la
        // seconda una. Il numero e' gia' in casa comunque, perche' il controllo
        // dello spread qui sotto chiede gia' il prezzo di SOL e di `change24h`
        // non se ne faceva niente.
        val baseMove = withContext(Dispatchers.IO) {
            runCatching { Prices.quotes(listOf(com.clearsign.core.NATIVE_SOL_MINT))[com.clearsign.core.NATIVE_SOL_MINT]?.change24h }.getOrNull()
        }
        var baseBeat: String? = null
        for (pick in picks) {
            val t = pick.c
            // Comprare o tenere quello che hai gia'.
            //
            // Il ciclo sapeva rispondere a "questa e' una trappola?" e a "quale
            // delle cinque e' la migliore?", e non alla terza domanda, che e'
            // quella che conta nelle giornate in cui sale tutto. Il 18/09/2026:
            // paghetta a meno cinque virgola uno per cento mentre SOL faceva
            // piu' dieci virgola quattro, e le commissioni erano lo zero virgola
            // nove per cento della perdita. Non erano i costi: erano le monete.
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
            // Una commissione dentro la soglia scelta si dice e si passa; tutto
            // il resto del critico ferma come prima.
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
                    // Il tetto del giorno, detto in modo che si possa fare
                    // qualcosa. Diceva solo "e' esaurito, alzalo nelle regole",
                    // e quando il tetto e' gia' tutta la paghetta non c'e'
                    // niente da alzare: era un consiglio impossibile davanti a
                    // un cursore gia' al massimo. Adesso dice quanto ne resta,
                    // a che ora si libera, e manda ad alzarlo solo se si puo'.
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
        // Nessuna delle cinque batteva quello che hai gia' in mano. Non e' un
        // fallimento della caccia, e' una risposta: e va detta, se no la scheda
        // resta ferma sull'ultimo acquisto e sembra che non sia successo niente.
        baseBeat?.let {
            val said = ctx.getString(R.string.trader_base_wins, String.format(java.util.Locale.ROOT, "%+.1f%%", baseMove ?: 0.0))
            note(ctx, said)
            return Tick(said, acted = false)
        }
        return Tick("nothing passed", acted = false)
    }

    /**
     * The live feed's candidates, graded like everybody else's.
     *
     * One registry call per signal to turn a mint into a [Candidate] with its
     * trading windows, bounded so a loud hour cannot spend the budget on
     * lookups. Each pick carries the signal in its first note, so the receipt
     * and the trace say why this coin and not another.
     */
    private suspend fun scoutPicks(ctx: Context, cfg: Config, held: Set<String>): List<com.clearsign.core.Scored> {
        // La finestra lenta: nessuno sta guardando, e il segnale della folla
        // arriva in ritardo per scelta. Vedi SeekerFeed.SLOW_FRESH_MS.
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
            JupiterTrigger.Placed.TooSmall -> {
                Positions.note(ctx, mint, ctx.getString(R.string.trader_no_onchain_small))
                AgentTrace.say(ctx.getString(R.string.trader_no_onchain_small), AgentTrace.Kind.WARN)
            }
            // Qualsiasi altro no, detto. Era `else -> Unit`: l'app aveva appena
            // promesso un ordine in catena e poi taceva, e la posizione restava
            // senza uscita che sopravviva all'app senza che nessuno lo dicesse.
            // Un fallimento silenzioso su una promessa fatta e' peggio di un
            // fallimento: e' una bugia a scoppio ritardato.
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
     * Everything goes through the collar, exactly like a move proposed in chat.
     *
     * [AgentBroker.Job.Source.LINK] and not `IN_APP`: with `IN_APP` the broker
     * posts no notification when the collar wants a person, so a background
     * move would wait ninety seconds against a screen that never appeared.
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
