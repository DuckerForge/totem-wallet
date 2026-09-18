package com.clearsign.app

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Seeing what the loop sees.
 *
 * A full screen that never sleeps, meant to sit next to a computer: one chart
 * per coin in play with the three lines that matter (where it came in, where it
 * sells, where it stops) and the price ticking against them; under that, the
 * loop's own reasoning arriving line by line, typed, as it happens. A pulse
 * says the loop is alive; a ring leaves the screen when it acts.
 *
 * Everything on it already exists. The lines are [AgentTrace], written by the
 * loop as it works, not by a model asked to narrate. The prices are Jupiter's,
 * the history is GeckoTerminal's. No key, no credit, nothing new leaves the
 * phone.
 */
@Composable
internal fun EyesDialog(onClose: () -> Unit, onStart: () -> Unit = {}) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The whole point is a screen that stays on.
        val view = LocalView.current
        LaunchedEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        EyesScreen(onClose, onStart)
    }
}

@Composable
internal fun EyesScreen(onClose: () -> Unit, onStart: () -> Unit = {}) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val open = remember(refresh) { Positions.open(ctx) }
    val cfg = remember(refresh) { TraderLoop.config(ctx) }
    var tickAt by remember { mutableStateOf(TraderLoop.lastTickAt(ctx)) }
    // The clock the rings run on, ten times a second: enough for an arc to
    // look alive, few enough not to redraw the charts for nothing.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(100) } }
    var solUsd by remember { mutableStateOf<Double?>(null) }
    var spot by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    // A sale the chain prices lower than Jupiter's quote: the question stays
    // on screen with the real number until the person answers or moves on.
    var worse by remember { mutableStateOf<Pair<Positions.Position, SessionActions.Sale.Worse>?>(null) }
    // A question typed under the thoughts, answered in the same stream.
    var question by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<String?>(null) }
    val turns = remember { mutableListOf<Brain.Turn>() }
    var voice by remember { mutableStateOf(Settings.eyesVoice(ctx)) }
    val scope = rememberCoroutineScope()

    // The voice: on while this screen is open and the switch is on. It reads
    // the lines worth hearing as they land, and nothing that was already there.
    DisposableEffect(voice) {
        if (voice) Voice.warm(ctx)
        onDispose { Voice.stop() }
    }
    val scanning = stringResource(R.string.trace_scanning)
    /**
     * La voce dice le cose che contano, non tutte.
     *
     * Leggeva ogni riga trovata o fatta. Le righe arrivano piu' in fretta di
     * quanto una voce parli, quindi partiva, si fermava a meta', ripartiva su
     * un'altra: dava l'impressione di una macchina che balbetta invece di una
     * che lavora. E leggere venti righe non e' venti volte piu' informativo di
     * leggerne una.
     *
     * Quindi: le cose fatte coi soldi si dicono sempre, perche' sono quelle che
     * uno vorrebbe sentire anche dall'altra stanza. Del resto si dice una riga
     * ogni dodici secondi, l'ultima arrivata, e le altre si guardano e basta.
     * Una voce che tace mentre lo schermo scorre non sta nascondendo niente: sta
     * lasciando leggere.
     */
    LaunchedEffect(voice) {
        if (!voice) return@LaunchedEffect
        var seen = AgentTrace.lines.size
        var lastSaid = 0L
        while (true) {
            val lines = AgentTrace.lines
            if (lines.size < seen) seen = 0
            val fresh = lines.drop(seen)
            seen = lines.size
            // Tutto quello che ha mosso dei soldi, sempre e in ordine.
            fresh.filter { it.kind == AgentTrace.Kind.ACTED }.forEach { Voice.add(ctx, it.text) }
            // Del resto, una sola, e non piu' spesso di una ogni dodici secondi.
            val rest = fresh.lastOrNull { it.kind != AgentTrace.Kind.ACTED && (it.kind == AgentTrace.Kind.FOUND || it.kind == AgentTrace.Kind.REFUSED || it.text == scanning) }
            val now = System.currentTimeMillis()
            if (rest != null && now - lastSaid > 12_000L) {
                Voice.add(ctx, rest.text)
                lastSaid = now
            }
            delay(500)
        }
    }

    fun run(label: String, block: suspend () -> String) {
        if (busy != null) return
        busy = label; said = null; worse = null
        scope.launch {
            said = runCatching { block() }.getOrElse { ctx.getString(R.string.trader_net_down) }
            busy = null; refresh++
        }
    }
    val sellingLabel = stringResource(R.string.trader_selling)
    val buyingLabel = stringResource(R.string.eyes_buying)
    val loopOff = stringResource(R.string.eyes_loop_off_hunt)
    val hunting = stringResource(R.string.eyes_hunting)

    // One clock for the page: the book and the tick every two seconds, the
    // prices every fifteen. The loop's own reads are its own business.
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            refresh++
            tickAt = TraderLoop.lastTickAt(ctx)
            if (n % 7 == 0) {
                val mints = Positions.open(ctx).map { it.mint } + com.clearsign.core.NATIVE_SOL_MINT
                val q = withContext(Dispatchers.IO) { runCatching { Prices.usd(mints) }.getOrDefault(emptyMap()) }
                if (q.isNotEmpty()) { spot = q; solUsd = q[com.clearsign.core.NATIVE_SOL_MINT] ?: solUsd }
            }
            n++
            delay(2_000)
        }
    }

    val acted = AgentTrace.lines.count { it.kind == AgentTrace.Kind.ACTED }
    val ring = rememberReveal(acted, durationMs = 1400)
    val looked = TraderLoop.lookedAt.longValue
    val hunted = TraderLoop.huntedAt.longValue
    val pulse = rememberReveal(looked, durationMs = 1600)
    // Time left to the next look and the next hunt, as a share of the wait.
    val lookLeft = if (looked == 0L) 0L else (looked + TraderLoop.EXIT_EVERY_MS - now).coerceIn(0L, TraderLoop.EXIT_EVERY_MS)
    val huntLeft = if (hunted == 0L) 0L else (hunted + TraderLoop.HUNT_EVERY_MS - now).coerceIn(0L, TraderLoop.HUNT_EVERY_MS)
    val breathe by rememberInfiniteTransition(label = "eyes").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "breathe",
    )

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Halo.ground2, Halo.ground))),
    ) {
        // The ring that leaves the middle of the screen when the loop signs.
        Canvas(Modifier.fillMaxSize()) {
            if (ring in 0.01f..0.99f) {
                val r = size.minDimension * (0.1f + 0.9f * ring)
                drawCircle(Halo.mint.copy(alpha = (1f - ring) * 0.5f), r, center, style = Stroke(2.dp.toPx()))
                drawCircle(Halo.mint.copy(alpha = (1f - ring) * 0.12f), r * 0.6f, center)
            }
        }

        // In cima si scansava la barra di stato, in fondo niente: non c'era
        // niente in fondo da scansare. Da quando il tasto di accensione sta li',
        // la barra dei gesti gli passava sopra e la parola veniva tagliata a
        // meta' dal bordo dello schermo.
        // Le due barre di sistema, tolte a mano perche' qui dentro nessuno le
        // toglie. Vedi [systemBars].
        val bars = systemBars()
        Column(
            Modifier.padding(top = bars.top, bottom = bars.bottom)
                .fillMaxWidth().height(bars.usable)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The clock: an outer arc that drains to the next look, an inner
                // arc to the next hunt, and the dot in the middle that breathes
                // and swells once each time the loop looks.
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = 3.dp.toPx()
                        val inset = stroke
                        val outer = androidx.compose.ui.geometry.Rect(inset, inset, size.width - inset, size.height - inset)
                        val inner = androidx.compose.ui.geometry.Rect(inset * 3.2f, inset * 3.2f, size.width - inset * 3.2f, size.height - inset * 3.2f)
                        drawArc(Halo.stroke, -90f, 360f, false, outer.topLeft, outer.size, style = Stroke(stroke))
                        if (cfg.on && looked > 0L) {
                            drawArc(
                                Halo.mint, -90f, 360f * lookLeft / TraderLoop.EXIT_EVERY_MS, false, outer.topLeft, outer.size,
                                style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                            )
                        }
                        drawArc(Halo.stroke.copy(alpha = 0.6f), -90f, 360f, false, inner.topLeft, inner.size, style = Stroke(stroke * 0.6f))
                        if (cfg.on && hunted > 0L) {
                            drawArc(
                                Halo.cyan, -90f, 360f * huntLeft / TraderLoop.HUNT_EVERY_MS, false, inner.topLeft, inner.size,
                                style = Stroke(stroke * 0.6f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                            )
                        }
                        val base = 2.5f.dp.toPx()
                        val swell = 1f - pulse
                        drawCircle(Halo.mint.copy(alpha = 0.10f + 0.10f * (1f - breathe)), base * (1.6f + 1.4f * breathe), center)
                        drawCircle(Halo.mint.copy(alpha = 0.55f * swell), base * (2f + 3f * pulse), center, style = Stroke(1.5f.dp.toPx()))
                        drawCircle(if (cfg.on) Halo.mint else Halo.muted, base, center)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.eyes_title).uppercase(), style = HaloType.label, color = Halo.muted)
                    Text(
                        if (!cfg.on) stringResource(R.string.eyes_off)
                        else if (AgentTrace.busy.value) stringResource(R.string.eyes_working)
                        else stringResource(R.string.eyes_watching, open.size),
                        fontFamily = Inter, fontSize = 12.sp, color = if (cfg.on) Halo.ink else Halo.amber,
                    )
                    if (cfg.on && looked > 0L) {
                        Row {
                            Text(stringResource(R.string.eyes_next_look, (lookLeft / 1000).toString()), fontFamily = Mono, fontSize = 10.5.sp, color = Halo.mint, style = Tabular)
                            Spacer(Modifier.width(10.dp))
                            // Pieno vuol dire che non caccia, e dirgli quando
                            // caccia la prossima volta era una promessa che non
                            // aveva intenzione di mantenere: il ciclo si ferma da
                            // solo quando i posti sono occupati e ricompra solo
                            // dopo che ha venduto. Adesso lo dice invece di
                            // mostrare un conto alla rovescia verso niente.
                            val full = open.size >= cfg.maxPositions
                            if (full) Text(
                                stringResource(R.string.eyes_full, open.size, cfg.maxPositions),
                                fontFamily = Mono, fontSize = 10.5.sp, color = Halo.amber, style = Tabular,
                            ) else if (hunted > 0L) Text(
                                stringResource(R.string.eyes_next_hunt, String.format(Locale.ROOT, "%d:%02d", huntLeft / 60_000, huntLeft / 1000 % 60)),
                                fontFamily = Mono, fontSize = 10.5.sp, color = Halo.cyan, style = Tabular,
                            )
                        }
                    }
                }
                Box(Modifier.clip(rs(999)).clickable { onClose() }.padding(8.dp)) { HaloIcon(HIcon.CLOSE, Halo.muted, 18.dp) }
            }
            busy?.let { Working(it) }
            said?.let { Banner(it, Halo.amber, HIcon.INFO) }
            worse?.let { (pos, w) ->
                GhostButton(stringResource(R.string.trader_sell_anyway, fmtSol(w.realLamports, 4)), Modifier.fillMaxWidth(), HIcon.SWAP, tint = Halo.red) {
                    run(sellingLabel) { SessionActions.sellSaid(ctx, pos, AgentBroker.Job.Source.IN_APP, acceptReal = true).text }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Sideways on the bedside table: two charts per row, the thoughts under them.
            val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (open.isEmpty()) {
                    GlassCard { Text(stringResource(R.string.eyes_none), style = HaloType.small, color = Halo.muted, lineHeight = 17.sp) }
                }
                open.chunked(if (landscape) 2 else 1).forEach { rowOf -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { rowOf.forEach { pos -> Box(Modifier.weight(1f)) {
                    val entryUsd = pos.entryLamports?.let { e -> solUsd?.let { e / 1e9 * it } }
                    val now = spot[pos.mint]
                    val move = if (entryUsd != null && now != null && entryUsd > 0) (now - entryUsd) / entryUsd * 100 else null
                    GlassCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val targets = if (entryUsd == null) emptyList() else listOf(
                                ChartTarget(entryUsd, stringResource(R.string.eyes_entry), Halo.muted, "e" + pos.mint),
                                ChartTarget(entryUsd * (1 + pos.takeProfitPct / 100.0), "+" + pos.takeProfitPct + "%", Halo.mint, "t" + pos.mint),
                                ChartTarget(entryUsd * (1 - pos.stopLossPct / 100.0), "−" + pos.stopLossPct + "%", Halo.red, "s" + pos.mint),
                            )
                            PriceChart(pos.mint, pos.symbol, targets)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (move == null) "…" else String.format(Locale.ROOT, "%+.1f%%", move),
                                    fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                                    color = if (move == null) Halo.muted else if (move >= 0) Halo.mint else Halo.red, style = Tabular,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.eyes_since_entry, fmtSol(pos.costLamports, 4)), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted)
                            }
                            // Who sells this one, said plainly: an order on the chain
                            // that fires with the phone off, or the loop alone, which
                            // needs the phone on and the loop running.
                            val guarded = pos.triggerOrder != null
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HaloIcon(if (guarded) HIcon.SHIELD_LOCK else HIcon.WARNING, if (guarded) Halo.mint else Halo.amber, 12.dp)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (guarded) stringResource(R.string.eyes_guard_chain, pos.takeProfitPct)
                                    else if (cfg.on) stringResource(R.string.eyes_guard_loop)
                                    else stringResource(R.string.eyes_guard_none),
                                    fontFamily = Inter, fontSize = 10.5.sp, color = if (guarded) Halo.mint else Halo.amber, lineHeight = 14.sp,
                                )
                            }
                            // Three things a person can do while watching. No more.
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SmallChip(stringResource(R.string.eyes_sell), HIcon.SWAP, tint = Halo.red) {
                                    run(sellingLabel) {
                                        val r = SessionActions.sellSaid(ctx, pos, AgentBroker.Job.Source.IN_APP)
                                        r.worse?.let { worse = pos to it }
                                        r.text
                                    }
                                }
                                SmallChip(stringResource(R.string.eyes_more), HIcon.DOWNLOAD, tint = Halo.mint) {
                                    run(buyingLabel) { TraderLoop.buyMore(ctx, pos.mint) }
                                }
                                SmallChip(stringResource(R.string.eyes_next), HIcon.SEARCH, tint = Halo.cyan) {
                                    run(sellingLabel) {
                                        val r = SessionActions.sellSaid(ctx, pos, AgentBroker.Job.Source.IN_APP)
                                        r.worse?.let { worse = pos to it }
                                        val (ok, m) = r.ok to r.text
                                        when {
                                            !ok -> m
                                            !TraderLoop.config(ctx).on -> loopOff
                                            else -> { busy = hunting; TraderLoop.tick(ctx, mayHunt = true).summary }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } } ; if (rowOf.size == 1 && landscape) Spacer(Modifier.weight(1f)) } }

                // The reasoning, newest at the bottom, typed as it lands.
                Box(
                    Modifier.fillMaxWidth().clip(rs(14)).background(Halo.ground)
                        .border(1.dp, Halo.cyan.copy(alpha = 0.20f), rs(14)),
                ) {
                    TubeGlass(Modifier.matchParentSize())
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.eyes_thoughts).uppercase(), style = HaloType.label, color = Halo.muted, modifier = Modifier.weight(1f))
                            // The voice, said in words: on or off, and where it belongs, next to the lines it reads.
                            SmallChip(
                                stringResource(if (voice) R.string.eyes_voice_on else R.string.eyes_voice_off), HIcon.AGENT,
                                tint = if (voice) Halo.mint else Halo.muted,
                            ) { voice = !voice; Settings.setEyesVoice(ctx, voice); Haptics.tick(ctx) }
                        }
                        val lines = AgentTrace.lines.takeLast(14)
                        if (lines.isEmpty()) Text(stringResource(R.string.eyes_quiet), style = HaloType.small, color = Halo.muted)
                        lines.forEachIndexed { i, l -> TypedLine(l, last = i == lines.lastIndex && live == null) }
                        live?.let { TypedLine(AgentTrace.Line(System.currentTimeMillis(), it, AgentTrace.Kind.FOUND), last = true) }
                        if (AgentTrace.busy.value || asking) Caret()
                        // Ask it something, here, in the same stream. This one costs your model's credits.
                        if (Brain.configured(ctx)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.OutlinedTextField(
                                    value = question, onValueChange = { question = it }, modifier = Modifier.weight(1f), singleLine = true,
                                    placeholder = { Text(stringResource(R.string.eyes_ask_hint), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted) },
                                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Inter, fontSize = 13.sp, color = Halo.ink), colors = pickerField(), shape = rs(12),
                                )
                                Spacer(Modifier.width(8.dp))
                                SmallChip(stringResource(R.string.eyes_ask), HIcon.SEND, tint = if (question.isBlank() || asking) Halo.muted else Halo.mint) {
                                    val q = question.trim(); if (q.isEmpty() || asking) return@SmallChip
                                    question = ""; asking = true
                                    AgentTrace.say(ctx.getString(R.string.eyes_you, q))
                                    turns.add(Brain.Turn("user", q))
                                    scope.launch {
                                        val r = Brain.ask(ctx, ChatHistory.context(turns.toList()), "Apex eyes") { partial -> live = partial }
                                        live = null; asking = false
                                        when (r) {
                                            is Brain.Reply.Ok -> { turns.addAll(r.turns); r.turns.lastOrNull { it.role == "assistant" }?.text?.let { AgentTrace.say(it, AgentTrace.Kind.FOUND) } }
                                            is Brain.Reply.Failed -> AgentTrace.say(r.message, AgentTrace.Kind.REFUSED)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // Il tasto che accende, appena sotto la riga in cui gli si parla.
                //
                // Era in cima, sopra tutto, con due terzi di schermo vuoto sotto. Poi
                // inchiodato in fondo allo schermo, che e' peggio: da solo in mezzo al
                // vuoto, lontano da tutto quello a cui si riferisce. Le due cose che si
                // fanno qui sono chiedergli una cosa e accenderlo, e stanno una sotto
                // l'altra a un dito di distanza.
                if (!cfg.on) {
                ArmBar(stringResource(R.string.agent_start)) { onStart() }
            } else {
                // Acceso, la barra non spariva: restava un piede vuoto.
                //
                // E mancavano le due cose che servono appena hai venduto. I tre
                // tasti (vendi, compra ancora, vendine una e cercane un'altra)
                // stanno **sulla riga della moneta**, quindi appena la riga
                // sparisce non c'e' piu' niente: ne' un modo di dirgli "cerca
                // adesso", ne' un modo di fermarlo. Restava aspettare fino a sei
                // minuti davanti a uno schermo senza tasti.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        ArmBar(stringResource(R.string.eyes_hunt_now)) {
                            run(hunting) { TraderLoop.tick(ctx, mayHunt = true).summary }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.trader_stop_action),
                        fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.5.sp,
                        color = Halo.amber, style = Tabular,
                        modifier = Modifier.clip(rs(6)).background(Halo.amber.copy(alpha = 0.10f))
                            .border(1.dp, Halo.amber.copy(alpha = 0.45f), rs(6))
                            .clickable {
                                TraderLoop.stopSelf(ctx, ctx.getString(R.string.trader_stopped_by_you))
                                TraderKeeper.sync(ctx); refresh++
                            }
                            .padding(horizontal = 14.dp, vertical = 16.dp),
                    )
                }
            }
                if (open.size > 1 && busy == null) {
                    GhostButton(stringResource(R.string.eyes_sell_all), Modifier.fillMaxWidth(), HIcon.SWAP, tint = Halo.amber) {
                        run(sellingLabel) {
                            val stuck = SessionActions.sellAll(ctx)
                            if (stuck.isEmpty()) ctx.getString(R.string.env_sell_all_done) else ctx.getString(R.string.env_sell_all_stuck, stuck.joinToString(", "))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

/**
 * Una riga della traccia, scritta come la scriverebbe una macchina.
 *
 * Era in carattere proporzionale dentro una scheda di vetro, e si leggeva come
 * un racconto. Ma qui non sta raccontando: sta lavorando, e quello che si vuole
 * guardare e' proprio il lavoro. Monospaziato, ogni riga con il suo segno
 * davanti, e i segni sono quelli di un terminale perche' quella grammatica la
 * conoscono tutti senza spiegarla: la freccia e' un passo, il piu' e' una cosa
 * trovata, la ics e' una cosa rifiutata, il dollaro e' una cosa fatta coi soldi.
 *
 * L'ultima si batte da sola. Le vecchie sono gia' scritte: ribatterle a ogni
 * ridisegno direbbe che stanno succedendo di nuovo.
 */
@Composable
private fun TypedLine(l: AgentTrace.Line, last: Boolean) {
    val tint = when (l.kind) {
        AgentTrace.Kind.STEP -> Halo.muted
        AgentTrace.Kind.FOUND -> Halo.cyan
        AgentTrace.Kind.REFUSED -> Halo.red
        AgentTrace.Kind.WARN -> Halo.amber
        AgentTrace.Kind.ACTED -> Halo.mint
    }
    val sigil = when (l.kind) {
        AgentTrace.Kind.STEP -> ">"
        AgentTrace.Kind.FOUND -> "+"
        AgentTrace.Kind.REFUSED -> "x"
        AgentTrace.Kind.WARN -> "!"
        AgentTrace.Kind.ACTED -> "$"
    }
    val shown = remember(l) { Animatable(if (last) 0f else 1f) }
    LaunchedEffect(l) { if (last) shown.animateTo(1f, tween((l.text.length * 16).coerceIn(300, 2200), easing = LinearEasing)) }
    val n = (l.text.length * shown.value).toInt().coerceIn(0, l.text.length)
    Row(verticalAlignment = Alignment.Top) {
        Text(clock.format(Date(l.at)), fontFamily = Mono, fontSize = 9.5.sp, color = Halo.muted.copy(alpha = 0.55f), style = Tabular)
        Spacer(Modifier.width(7.dp))
        Text(sigil, fontFamily = Mono, fontSize = 11.sp, color = tint.copy(alpha = 0.8f), style = Tabular)
        Spacer(Modifier.width(6.dp))
        Text(
            l.text.take(n), fontFamily = Mono, fontSize = 11.sp, color = tint, lineHeight = 16.sp,
            fontWeight = if (l.kind == AgentTrace.Kind.ACTED) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Il vetro del tubo: righe di scansione e un alone, dietro la console.
 *
 * Non aggiunge nessuna informazione ed e' esattamente per questo che ci sta: da'
 * alle righe un posto dove stare che sembri uno strumento acceso invece di una
 * scheda dell'interfaccia. La stessa lingua dello scontrino terminale e
 * dell'attesa di Scout.
 */
@Composable
private fun TubeGlass(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        var y = 0f
        while (y < size.height) {
            drawLine(Halo.cyan.copy(alpha = 0.045f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1f)
            y += 3f
        }
    }
}

@Composable
private fun Caret() {
    val blink by rememberInfiniteTransition(label = "caret").animateFloat(
        0f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "blink",
    )
    Box(Modifier.padding(start = 66.dp).width(8.dp).height(13.dp).background(Halo.mint.copy(alpha = blink)))
}

/**
 * L'interruttore di accensione, in fondo e per tutta la riga.
 *
 * Questa pagina e' l'unica dell'app che sta a guardare una macchina mentre
 * lavora, e il suo linguaggio e' gia' quello: monospaziato, righe con un segno
 * davanti, un anello che pulsa. Il tasto per accenderla era un rettangolo con
 * un bordo che respira, cioe' un bottone qualunque con le parentesi intorno.
 *
 * Adesso e' un quadro strumenti: quattro angoli invece di una cornice chiusa,
 * una luce che scorre da sinistra a destra come una scansione, e la parola in
 * mezzo con le lettere larghe. Gli angoli sono la differenza che si legge senza
 * accorgersene: una cornice intera e' un bottone, quattro angoli sono una cosa
 * **inquadrata**, cioe' una macchina che sta per partire.
 *
 * Niente di piu'. Un colore solo, quello del tema, e nessun bagliore addosso al
 * testo: la pagina sotto e' fatta di numeri, e se il tasto brilla piu' dei
 * numeri la pagina l'ha persa.
 */
@Composable
private fun ArmBar(label: String, onStart: () -> Unit) {
    val anim = rememberInfiniteTransition(label = "arm")
    val sweep by anim.animateFloat(
        -0.25f, 1.25f,
        infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "sweep",
    )
    val pulse by anim.animateFloat(
        0.34f, 0.9f,
        infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "armPulse",
    )
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(rs(6)).clickable { onStart() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            drawRect(Halo.mint.copy(alpha = 0.07f))
            // La scansione: una banda tenue che attraversa e riparte. E' quello
            // che fa capire, senza scriverlo, che la macchina e' pronta e ferma.
            val x = w * sweep
            drawRect(
                Brush.horizontalGradient(
                    listOf(androidx.compose.ui.graphics.Color.Transparent, Halo.mint.copy(alpha = 0.20f), androidx.compose.ui.graphics.Color.Transparent),
                    startX = x - w * 0.22f, endX = x + w * 0.22f,
                ),
            )
            val k = 15.dp.toPx()
            val sw = 1.7.dp.toPx()
            val i = sw / 2f
            val c = Halo.mint.copy(alpha = pulse)
            fun corner(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
                drawLine(c, androidx.compose.ui.geometry.Offset(x1, y1), androidx.compose.ui.geometry.Offset(x2, y2), sw)
                drawLine(c, androidx.compose.ui.geometry.Offset(x2, y2), androidx.compose.ui.geometry.Offset(x3, y3), sw)
            }
            corner(i, i + k, i, i, i + k, i)
            corner(w - i - k, i, w - i, i, w - i, i + k)
            corner(w - i, h - i - k, w - i, h - i, w - i - k, h - i)
            corner(i + k, h - i, i, h - i, i, h - i - k)
        }
        Text(
            label.uppercase(),
            fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            color = Halo.mint, style = Tabular, letterSpacing = 4.sp,
        )
    }
}

/** Le due barre di sistema e quello che resta in mezzo. Vedi [systemBars]. */
private data class Bars(
    val top: androidx.compose.ui.unit.Dp,
    val bottom: androidx.compose.ui.unit.Dp,
    val usable: androidx.compose.ui.unit.Dp,
)

/**
 * Quanto si prendono le barre di sistema, e quanto schermo resta.
 *
 * Questa pagina vive dentro un `Dialog`, e li' dentro le cose normali non
 * funzionano, per tre motivi che si sommavano. Tutti e tre finivano nello
 * stesso punto: il tasto di accensione in fondo, tagliato a meta' dal bordo.
 *
 *  * `navigationBarsPadding()` vale **zero** dentro un Dialog. Due schermate
 *    prese prima e dopo averlo aggiunto avevano il tasto sulla stessa identica
 *    riga di pixel;
 *  * il primo conto fatto a mano tornava zero lo stesso, perche' li' dentro
 *    `LocalContext` non e' l'Activity ma un involucro e il cast falliva in
 *    silenzio. Il difetto peggiore di un numero e' tornare zero invece di
 *    rompersi;
 *  * e anche con i numeri giusti, un margine qui **sposta in giu' senza
 *    accorciare**: la colonna si prendeva comunque tutta l'altezza della
 *    finestra, e usciva sotto di quanto l'avevi spostata.
 *
 * Quindi niente margini che dovrebbero accorciare: si misura la finestra vera,
 * si tolgono le due barre, e l'altezza che resta si da' alla colonna come
 * numero. Un'altezza esatta non la sposta nessuno.
 */
@Composable
private fun systemBars(): Bars {
    val ctx = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val view = androidx.compose.ui.platform.LocalView.current
    return remember(view) {
        var c: android.content.Context? = ctx
        while (c != null && c !is android.app.Activity) c = (c as? android.content.ContextWrapper)?.baseContext
        val decor = (c as? android.app.Activity)?.window?.decorView
        val insets = decor?.rootWindowInsets?.let {
            androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(it)
                .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        val top = insets?.top ?: 0
        val bottom = insets?.bottom ?: 0
        val tall = decor?.height?.takeIf { it > 0 } ?: ctx.resources.displayMetrics.heightPixels
        with(density) { Bars(top.toDp(), bottom.toDp(), (tall - top - bottom).coerceAtLeast(0).toDp()) }
    }
}
