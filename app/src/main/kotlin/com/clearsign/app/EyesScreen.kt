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
import androidx.compose.foundation.layout.statusBarsPadding
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
internal fun EyesDialog(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The whole point is a screen that stays on.
        val view = LocalView.current
        LaunchedEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        EyesScreen(onClose)
    }
}

@Composable
internal fun EyesScreen(onClose: () -> Unit) {
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
    var voice by remember { mutableStateOf(Settings.eyesVoice(ctx)) }
    val scope = rememberCoroutineScope()

    // The voice: on while this screen is open and the switch is on. It reads
    // the lines worth hearing as they land, and nothing that was already there.
    DisposableEffect(voice) {
        if (voice) Voice.warm(ctx)
        onDispose { Voice.stop() }
    }
    val scanning = stringResource(R.string.trace_scanning)
    LaunchedEffect(voice) {
        if (!voice) return@LaunchedEffect
        var seen = AgentTrace.lines.size
        while (true) {
            val lines = AgentTrace.lines
            if (lines.size < seen) seen = 0
            lines.drop(seen).forEach { l ->
                if (l.kind == AgentTrace.Kind.ACTED || l.kind == AgentTrace.Kind.FOUND || l.text == scanning) Voice.add(ctx, l.text)
            }
            seen = lines.size
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

        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
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
                            if (hunted > 0L) Text(
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

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (open.isEmpty()) {
                    GlassCard { Text(stringResource(R.string.eyes_none), style = HaloType.small, color = Halo.muted, lineHeight = 17.sp) }
                }
                open.forEach { pos ->
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
                }

                // The reasoning, newest at the bottom, typed as it lands.
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.eyes_thoughts).uppercase(), style = HaloType.label, color = Halo.muted, modifier = Modifier.weight(1f))
                            // The voice, said in words: on or off, and where it belongs, next to the lines it reads.
                            SmallChip(
                                stringResource(if (voice) R.string.eyes_voice_on else R.string.eyes_voice_off), HIcon.PIGEON,
                                tint = if (voice) Halo.mint else Halo.muted,
                            ) { voice = !voice; Settings.setEyesVoice(ctx, voice); Haptics.tick(ctx) }
                        }
                        val lines = AgentTrace.lines.takeLast(14)
                        if (lines.isEmpty()) Text(stringResource(R.string.eyes_quiet), style = HaloType.small, color = Halo.muted)
                        lines.forEachIndexed { i, l -> TypedLine(l, last = i == lines.lastIndex) }
                        if (AgentTrace.busy.value) Caret()
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

/** One line of the trace. The last one types itself out; the older ones are already written. */
@Composable
private fun TypedLine(l: AgentTrace.Line, last: Boolean) {
    val tint = when (l.kind) {
        AgentTrace.Kind.STEP -> Halo.muted
        AgentTrace.Kind.FOUND -> Halo.cyan
        AgentTrace.Kind.REFUSED -> Halo.red
        AgentTrace.Kind.ACTED -> Halo.mint
    }
    val shown = remember(l) { Animatable(if (last) 0f else 1f) }
    LaunchedEffect(l) { if (last) shown.animateTo(1f, tween((l.text.length * 14).coerceIn(300, 1800), easing = FastOutSlowInEasing)) }
    val n = (l.text.length * shown.value).toInt().coerceIn(0, l.text.length)
    Row(verticalAlignment = Alignment.Top) {
        Text(clock.format(Date(l.at)), fontFamily = Mono, fontSize = 10.sp, color = Halo.muted.copy(alpha = 0.7f), style = Tabular)
        Spacer(Modifier.width(8.dp))
        Text(
            l.text.take(n), fontFamily = Inter, fontSize = 12.sp, color = tint, lineHeight = 16.sp,
            fontWeight = if (l.kind == AgentTrace.Kind.ACTED) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Caret() {
    val blink by rememberInfiniteTransition(label = "caret").animateFloat(
        0f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "blink",
    )
    Box(Modifier.padding(start = 66.dp).width(8.dp).height(13.dp).background(Halo.mint.copy(alpha = blink)))
}
