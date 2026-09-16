@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.clearsign.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clearsign.core.CrowdBuy
import com.clearsign.core.CrowdRank
import kotlin.math.abs
import kotlin.math.sin

/**
 * What the Seeker crowd is buying.
 *
 * The picture says the thing the numbers cannot: one phone on the left, and a
 * braid of coins leaving it. Ribbon thickness is how many different wallets
 * bought, never how many purchases — a single trader buying ten times draws no
 * thicker a line than a single trader buying once, because he is still one
 * person.
 *
 * Gold means whales were in it. Everything here is the crowd of people holding
 * the same phone you are holding.
 */
private const val LANES = 5

@Composable
internal fun SeekerCard(feed: SeekerFeed.Feed?, onOpen: () -> Unit) {
    if (!SeekerScan.available && !SeekerFeed.available) {
        GlassCard { NothingHere(stringResource(R.string.crowd_no_scan)) }
        return
    }
    val ctx = LocalContext.current
    var ranks by remember { mutableStateOf<List<CrowdRank>>(emptyList()) }
    var followed by remember { mutableStateOf(0) }
    var last by remember { mutableStateOf(0L) }
    var looking by remember { mutableStateOf(false) }

    // The published ranking comes down from the page, which is the only thing on
    // screen that talks to the network. This card used to fetch it here and keep
    // it, while the live feed above read the cached file: same source, two ages,
    // and the feed was the one that looked broken.
    //
    // Without a published feed the card still sweeps for itself. WorkManager may
    // defer a job for a long time, and a card that is empty because nobody ran the
    // scan is indistinguishable from a card that is empty because nothing is
    // happening. One of those is a fact and the other is a bug.
    LaunchedEffect(feed) {
        withContext(Dispatchers.IO) {
            followed = SeekerScan.roster(ctx).size
            if (SeekerFeed.available) {
                feed?.let { ranks = it.rows; last = it.at; if (it.followed > 0) followed = it.followed }
                looking = feed == null
                return@withContext
            }
            ranks = SeekerScan.rank(ctx)
            last = SeekerScan.lastPassAt(ctx)
            if (SeekerScan.stale(ctx)) {
                looking = true
                runCatching { SeekerScan.pass(ctx) }
                ranks = SeekerScan.rank(ctx)
                last = SeekerScan.lastPassAt(ctx)
                looking = false
            }
        }
    }
    // A switch chip that opens onto empty ground reads as broken. Since the
    // ranking became a tab it has to say why it is empty instead of vanishing.
    if (followed == 0) {
        GlassCard { NothingHere(stringResource(R.string.crowd_no_roster)) }
        return
    }

    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HaloIcon(HIcon.COINS, Halo.cyan, 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.crowd_title),
                    fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = Halo.cyan, modifier = Modifier.weight(1f),
                )
                Text(
                    if (looking) stringResource(R.string.crowd_looking)
                    else stringResource(R.string.crowd_followed, followed),
                    fontFamily = Mono, fontSize = 11.sp,
                    color = if (looking) Halo.cyan else Halo.muted, style = Tabular,
                )
            }

            // The braid is only drawn when there is something to braid. Empty, it was
            // a tall box with a phone and a flat line in it, which looks like a
            // feature that failed rather than a crowd that is quiet.
            if (ranks.isNotEmpty()) SeekerFlow(ranks.take(LANES))

            if (ranks.isEmpty() && !looking) {
                // Not a failure, and worth saying in full: on four hundred whales over a
                // whole day, no coin reached three separate buyers. Silence is the honest
                // reading of that, and a list padded to look busy would be the lie.
                Text(
                    stringResource(if (last == 0L) R.string.crowd_never_ran else R.string.crowd_empty),
                    fontFamily = Inter, fontSize = 12.sp, color = Halo.muted, lineHeight = 17.sp,
                )
            } else if (ranks.isNotEmpty()) {
                ranks.take(LANES).forEach { r -> CrowdRow(r, onOpen) }
                Text(
                    stringResource(R.string.crowd_note),
                    fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, lineHeight = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun CrowdRow(r: CrowdRank, onOpen: () -> Unit) {
    val tint = if (r.whales > 0) Halo.amber else Halo.cyan
    Row(
        Modifier.fillMaxWidth().clip(rs(Radius.row)).background(Halo.cardSoft)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TokenLogo(r.mint, r.symbol, JupiterTokens.cached(r.mint)?.icon, 24.dp)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(r.symbol, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Halo.ink)
            Text(
                if (r.whales > 0) stringResource(R.string.crowd_row_whales, r.wallets, r.whales)
                else stringResource(R.string.crowd_row, r.wallets),
                fontFamily = Inter, fontSize = 11.sp, color = tint,
            )
        }
        Text(
            stringResource(R.string.crowd_spent, fmtSol(r.solSpent)),
            fontFamily = Mono, fontSize = 11.5.sp, color = Halo.muted, style = Tabular,
        )
    }
}

/**
 * The braid. One phone, and a ribbon per coin whose width is the number of
 * distinct buyers. The flow animates along the ribbon so it reads as movement
 * rather than as a diagram of something already finished.
 */
@Composable
private fun SeekerFlow(ranks: List<CrowdRank>) {
    // The state, not its value. Reading `.value` here recomposed this whole
    // composable on every frame; read inside the draw lambda it only redraws,
    // which matters now that the braid is a tab people flick between.
    val phaseState = rememberInfiniteTransition(label = "flow").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3400, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val cyan = Halo.cyan
    val amber = Halo.amber
    val stroke = Halo.stroke
    val muted = Halo.muted
    val widest = (ranks.maxOfOrNull { it.wallets } ?: 1).coerceAtLeast(1)

    Canvas(Modifier.fillMaxWidth().height(if (ranks.isEmpty()) 92.dp else 136.dp)) {
        val phase = phaseState.value
        val h = size.height
        val phoneX = 30f * density
        val phoneW = 24f * density
        val phoneH = 42f * density
        val endX = size.width - 26f * density

        // The phone: a plain rounded slab with a light inside it. It is the only
        // thing on the canvas that never moves, because it is you.
        val top = h / 2 - phoneH / 2
        drawRoundRect(
            color = stroke,
            topLeft = Offset(phoneX - phoneW / 2, top),
            size = androidx.compose.ui.geometry.Size(phoneW, phoneH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f * density),
            style = Stroke(width = 1.4f * density),
        )
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(cyan.copy(alpha = 0.26f), cyan.copy(alpha = 0.04f))),
            topLeft = Offset(phoneX - phoneW / 2 + 3f * density, top + 3f * density),
            size = androidx.compose.ui.geometry.Size(phoneW - 6f * density, phoneH - 6f * density),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f * density),
        )

        if (ranks.isEmpty()) {
            // Nothing to braid: one flat line going nowhere, which is the truth.
            drawLine(
                color = muted.copy(alpha = 0.22f),
                start = Offset(phoneX + phoneW, h / 2), end = Offset(endX, h / 2),
                strokeWidth = 1.2f * density, cap = StrokeCap.Round,
            )
            return@Canvas
        }

        val startX = phoneX + phoneW / 2 + 4f * density
        val gap = h / (ranks.size + 1)
        ranks.forEachIndexed { i, r ->
            val y = gap * (i + 1)
            val tint = if (r.whales > 0) amber else cyan
            val w = (1.6f + 2.4f * r.wallets / widest) * density

            val p = Path().apply {
                moveTo(startX, h / 2)
                cubicTo(
                    startX + (endX - startX) * 0.42f, h / 2,
                    startX + (endX - startX) * 0.52f, y,
                    endX - 9f * density, y,
                )
            }
            drawPath(p, tint.copy(alpha = 0.20f), style = Stroke(width = w, cap = StrokeCap.Round))
            // A bright segment travelling the ribbon, offset per lane so the braid
            // pulses instead of marching in step.
            val t = ((phase + i * 0.17f) % 1f)
            drawPath(
                p,
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    (t - 0.10f).coerceIn(0f, 1f) to Color.Transparent,
                    t.coerceIn(0f, 1f) to tint.copy(alpha = 0.85f),
                    (t + 0.10f).coerceIn(0f, 1f) to Color.Transparent,
                    1f to Color.Transparent,
                    startX = startX, endX = endX,
                ),
                style = Stroke(width = w, cap = StrokeCap.Round),
            )
            // The coin at the end, breathing gently so a still list still feels live.
            val pulse = 1f + 0.06f * sin((phase * 2 * Math.PI + i).toFloat())
            drawCircle(tint.copy(alpha = 0.16f), radius = 9f * density * pulse, center = Offset(endX, y))
            drawCircle(tint, radius = 9f * density * pulse, center = Offset(endX, y), style = Stroke(1.4f * density))
        }
    }
}

/** Two decimals under a tenth, three under a hundredth: enough to tell sums apart. */
private fun fmtSol(v: Double): String = when {
    abs(v) >= 10 -> String.format("%.1f", v)
    abs(v) >= 0.1 -> String.format("%.2f", v)
    else -> String.format("%.3f", v)
}

/**
 * The Seeker crowd, its own page.
 *
 * A page rather than a sheet on purpose: this is something to read for a minute,
 * not a control to poke and dismiss. Three questions in order — what are they
 * buying right now, who are the big ones, and what does the whole hundred and
 * twenty thousand actually hold.
 */
/**
 * Who is actually being watched, before any chart says anything.
 *
 * There are 120,520 Seekers on chain and they are mostly asleep: the median one
 * holds 0.032 SOL and last moved 209 days ago, around when the airdrops landed.
 * Following all of them would be following nobody, so the roster is the 10,527
 * holding at least one SOL. Every number on this page means *those*, and saying
 * so once here is cheaper than a footnote under each chart.
 */
@Composable
private fun WhoWeWatch(feed: SeekerFeed.Feed?) {
    val ctx = LocalContext.current
    val followed = remember { SeekerScan.roster(ctx).size }
    // Ticks once a second, so "next in 2m" is a clock and not a number that was
    // true when the screen opened.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    // Only for the fallback, when nobody publishes a feed and this phone sweeps.
    var scanAt by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { scanAt = SeekerScan.lastPassAt(ctx) }
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    // The clock used to fetch for itself, but only once a sweep was already
    // overdue: by then the ring had been spinning for half a minute. The page
    // polls on its own schedule now and this just reads the answer.
    val at = feed?.at ?: scanAt
    if (followed == 0) return
    Row(
        Modifier.fillMaxWidth().clip(rs(Radius.row)).background(Halo.cardSoft)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // Counts up on arrival: a number that lands says "measured", a number
            // that is just there says "typed".
            val shown = rememberCountUp(followed.toFloat(), durationMs = 900).toInt()
            Text(
                thousands(shown), fontFamily = Sora, fontWeight = FontWeight.Bold,
                fontSize = 21.sp, color = Halo.mint, style = Tabular,
            )
            Text(
                stringResource(R.string.who_active),
                fontFamily = Inter, fontSize = 11.sp, color = Halo.muted,
            )
        }
        Box(Modifier.width(1.dp).height(40.dp).background(Halo.stroke))
        Column(Modifier.weight(1.45f).padding(start = 14.dp)) {
            Text(
                stringResource(R.string.who_total, thousands(120_520)),
                fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Halo.ink,
            )
            Text(
                stringResource(R.string.who_focus),
                fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, lineHeight = 15.sp,
            )
            if (at > 0) {
                Spacer(Modifier.height(4.dp))
                SweepClock(at = at, now = now)
            }
        }
    }
}

/** The scanner's own cadence: every four minutes, and the page says so out loud. */
private const val PERIOD_MS = 4 * 60_000L

/**
 * The clock, drawn.
 *
 * It was a line of monospace saying "aggiornato 1m 12s fa · prossimo fra 2m 48s",
 * which is accurate and looks like a log file. A ring that empties says the same
 * thing without being read, and the words underneath it shrink to what the ring
 * cannot say.
 */
@Composable
private fun SweepClock(at: Long, now: Long) {
    val left = (at + PERIOD_MS - now).coerceIn(0L, PERIOD_MS)
    val frac = left.toFloat() / PERIOD_MS
    // Overdue is a state worth showing, not a zero to hide: a sweep that has not
    // landed is the one thing this clock exists to make visible.
    val overdue = now - at > PERIOD_MS + 30_000L
    val tint = if (overdue) Halo.amber else Halo.cyan
    val spin by rememberInfiniteTransition(label = "clock").animateFloat(
        0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "spin",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(15.dp)) {
            val r = size.minDimension / 2f - 1.2f * density
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(tint.copy(alpha = 0.22f), r, c, style = Stroke(1.6f * density))
            if (overdue) {
                // Still waiting: a short arc going round, because there is no share
                // of a countdown left to draw.
                drawArc(
                    color = tint, startAngle = spin, sweepAngle = 70f, useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                    style = Stroke(1.8f * density, cap = StrokeCap.Round),
                )
            } else {
                drawArc(
                    color = tint, startAngle = -90f, sweepAngle = -360f * frac, useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                    style = Stroke(1.8f * density, cap = StrokeCap.Round),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            if (overdue) stringResource(R.string.who_clock_late)
            else stringResource(R.string.who_clock2, mmss(left / 1000L)),
            fontFamily = Inter, fontSize = 10.5.sp, color = tint,
        )
    }
}

private fun mmss(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
}

private fun thousands(v: Int): String =
    v.toString().reversed().chunked(3).joinToString(".").reversed()

/**
 * The three things the page holds besides the live feed.
 *
 * The live feed is not one of them on purpose: it is the reason to open Scout,
 * so it sits above the bar and never hides behind a tap. What was three more
 * screens of scrolling is now three words.
 */
/** One line, said plainly, where a card would otherwise have disappeared. */
@Composable
internal fun NothingHere(text: String) {
    Text(text, style = HaloType.small, color = Halo.muted, lineHeight = 17.sp)
}

private enum class ScoutTab { BUYING, HOLDING, WHALES }

/**
 * The switch, in the app's own language rather than Material's.
 *
 * Each word wears the colour of the card it opens, so the lit chip and the
 * heading underneath it agree without anybody reading either. It paints its own
 * ground because it sticks to the top of the list: without it the rows would
 * show through while they slide past.
 */
@Composable
private fun ScoutTabs(selected: ScoutTab, onPick: (ScoutTab) -> Unit) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxWidth().background(Halo.ground).padding(horizontal = 18.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip(stringResource(R.string.crowd_tab_buying), selected == ScoutTab.BUYING, Halo.mint, Modifier.weight(1f)) {
                Haptics.tick(ctx); onPick(ScoutTab.BUYING)
            }
            ModeChip(stringResource(R.string.crowd_tab_holding), selected == ScoutTab.HOLDING, Halo.cyan, Modifier.weight(1f)) {
                Haptics.tick(ctx); onPick(ScoutTab.HOLDING)
            }
            ModeChip(stringResource(R.string.crowd_tab_whales), selected == ScoutTab.WHALES, Halo.amber, Modifier.weight(1f)) {
                Haptics.tick(ctx); onPick(ScoutTab.WHALES)
            }
        }
    }
}

@Composable
internal fun CrowdPage(onBuy: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    // One reader for the whole page.
    //
    // Three cards used to decide separately whether to talk to the network: the
    // live feed read only the cached file, the ranking underneath did the download
    // and kept the answer, and the clock fetched only once a sweep was already
    // late. The feed was therefore as old as the last time some other card had
    // happened to write the file, which on a phone opened in the morning was
    // hours. Now the page polls once a minute while it is on screen and hands the
    // same answer to all three, so a purchase that lands on chain appears without
    // leaving the page and coming back.
    //
    // A minute against a scanner that publishes every four is deliberate: the file
    // is six kilobytes, the poll is served from the local copy unless it has aged
    // past [SeekerFeed.FRESH_MS], and the names it warms are already cached after
    // the first pass.
    var feed by remember { mutableStateOf<SeekerFeed.Feed?>(null) }
    // Null while the first read is still running: waiting and quiet are not the
    // same fact and the card says them differently.
    var events by remember { mutableStateOf<List<CrowdBuy>?>(null) }
    // Which entrance animations have already played during this visit. Inside a
    // lazy list a card that scrolls past the top is thrown away, so without this
    // every bar and every feed row replays its arrival each time it comes back.
    val played = remember { mutableSetOf<String>() }
    var feedOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(feed) { events = runCatching { crowdEvents(ctx, feed) }.getOrDefault(emptyList()) }
    LaunchedEffect(Unit) {
        // The file first, so the page is never blank while the network answers.
        withContext(Dispatchers.IO) { SeekerFeed.cached(ctx) }?.let { feed = it }
        if (!SeekerFeed.available) return@LaunchedEffect
        while (true) {
            withContext(Dispatchers.IO) { SeekerFeed.refresh(ctx) }?.let { feed = it }
            kotlinx.coroutines.delay(150_000)
        }
    }
    // Which of the three the bar is showing. Kept across a rotation, because
    // turning the phone is not a request to go back to the start.
    var tab by rememberSaveable { mutableStateOf(ScoutTab.BUYING) }

    // The horizontal inset moved off the list and onto each row: the bar that
    // sticks to the top has to paint edge to edge, or the cards scrolling
    // underneath it appear in the margins beside it.
    val pad = Modifier.padding(horizontal = 18.dp)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Sections differ in height by hundreds of points. With the bar already
    // pinned, switching to a shorter one would drag the whole list up under the
    // thumb; this keeps the bar exactly where it was left.
    LaunchedEffect(tab) { if (listState.firstVisibleItemIndex >= 3) listState.scrollToItem(3) }
    LazyColumn(
        Modifier.fillMaxSize().background(Halo.ground).statusBarsPadding().navigationBarsPadding(),
        state = listState,
        contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "head") {
            Row(pad, verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(38.dp).clip(rs(12)).background(Halo.cardSoft)
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center,
                ) { HaloIcon(HIcon.CHEVRON_LEFT, Halo.ink, 20.dp) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.crowd_sheet), style = HaloType.screen, color = Halo.ink)
                    Text(stringResource(R.string.crowd_page_sub), style = HaloType.small, color = Halo.muted)
                }
            }
        }
        // Who we are actually looking at, said once, at the top, before any chart.
        // "What the Seekers are buying" invites you to assume all of them, and the
        // truth is a tenth of them: the rest have been still for seven months.
        item(key = "who") { Box(pad) { WhoWeWatch(feed) } }
        // The reason to open this page goes first and stays whole. It used to be
        // third, under a chart and under a card that apologises on a quiet hour.
        item(key = "live") {
            Box(pad) { CrowdFeed(events, feedOpen, { feedOpen = !feedOpen }, played, onBuy = onBuy) }
        }
        stickyHeader(key = "tabs") { ScoutTabs(tab) { tab = it } }
        // Keyed on the tab, so switching builds the new section instead of
        // pouring new data into the old one's remembered state.
        item(key = tab.name) {
            Box(pad) {
                when (tab) {
                    ScoutTab.BUYING -> SeekerCard(feed) {}
                    ScoutTab.HOLDING -> SeekerHoldingsCard(animate = remember { played.add("census") })
                    ScoutTab.WHALES -> SeekerWhalesCard()
                }
            }
        }
    }
}
