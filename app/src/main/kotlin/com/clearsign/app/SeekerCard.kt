@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.clearsign.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.launch
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
 * What the Seeker crowd is buying: one phone on the left and a braid of coins leaving
 * it. Ribbon thickness is how many different wallets bought, never how many purchases:
 * one trader buying ten times is still one person. Gold means whales were in it.
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

    // The published ranking comes down from the page, the only thing on screen that talks
    // to the network; this card used to fetch its own while the feed above read the cached
    // file, and the feed looked broken. Without a published feed the card still sweeps:
    // WorkManager can defer a job for hours, and "empty because nobody ran the scan" must
    // not look like "empty because nothing is happening".
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
                    if (looking) stringResource(R.string.crowd_looking) else stringResource(R.string.crowd_window),
                    fontFamily = Mono, fontSize = 11.sp,
                    color = if (looking) Halo.cyan else Halo.muted, style = Tabular,
                )
            }
            // What the rows are counted over, before the rows. "10,527 wallets" right above "14
            // different wallets" put the roster next to what the scanner actually watched that day,
            // a hundred or so, and every row looked broken.
            Text(
                stringResource(R.string.crowd_basis, followed),
                fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, lineHeight = 15.sp,
            )

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
                if (ranks.size > LANES) Text(
                    stringResource(R.string.crowd_more, ranks.size - LANES),
                    fontFamily = Inter, fontSize = 11.sp, color = Halo.muted,
                )
                Text(
                    stringResource(R.string.crowd_note),
                    fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, lineHeight = 15.sp,
                )
                // The bias, admitted on the card that carries it: whales are re-read every ten minutes
                // and the rest every hour and twenty, so a whale's purchase is far likelier to be caught.
                Text(
                    stringResource(R.string.crowd_bias),
                    fontFamily = Inter, fontSize = 11.sp, color = Halo.amber, lineHeight = 15.sp,
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
 * The braid: one phone, a ribbon per coin as wide as its distinct buyers. The flow
 * moves along the ribbon so it reads as movement, not as a finished diagram.
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

/* Two decimals under a tenth, three under a hundredth: enough to tell sums apart. */
private fun fmtSol(v: Double): String = when {
    abs(v) >= 10 -> String.format("%.1f", v)
    abs(v) >= 0.1 -> String.format("%.2f", v)
    else -> String.format("%.3f", v)
}

/*
 * The Seeker crowd, its own page, not a sheet: something to read for a minute. Three
 * questions in order: what are they buying now, who are the big ones, what does the
 * whole hundred and twenty thousand hold.
 */
/**
 * Who we watch and when we last looked, one line under the title. The full card said
 * it over four lines and pushed the switch bar below the fold. The count still counts
 * up on arrival: a number that lands says measured, one simply there says typed.
 */
@Composable
private fun WhoLine(feed: SeekerFeed.Feed?) {
    val ctx = LocalContext.current
    val followed = remember { SeekerScan.roster(ctx).size }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var scanAt by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { scanAt = SeekerScan.lastPassAt(ctx) }
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val at = feed?.at ?: scanAt
    if (followed == 0) {
        Text(stringResource(R.string.crowd_page_sub), style = HaloType.small, color = Halo.muted)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val shown = rememberCountUp(followed.toFloat(), durationMs = 900).toInt()
        // Un punto acceso, la cifra, e le parole spente: la riga era tutta
        // dello stesso colore e si leggeva come un avviso.
        Box(Modifier.size(6.dp).clip(rs(3)).background(Halo.mint))
        Spacer(Modifier.width(7.dp))
        Text(
            stringResource(R.string.who_line, thousands(shown)),
            style = HaloType.small, color = Halo.muted,
        )
        if (at > 0) {
            Spacer(Modifier.width(10.dp))
            SweepClock(at = at, now = now)
        }
    }
}


/** The scanner's own cadence: every four minutes, and the page says so out loud. */
private const val PERIOD_MS = 4 * 60_000L

/**
 * The clock, drawn. A monospace line saying "updated 1m 12s ago · next in 2m 48s" is
 * accurate and looks like a log file. A ring that empties says it without being read.
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
        // The figures alone. "Next look in" and, when late, "looking": two sentences
        // for what the ring already shows.
        if (!overdue) {
            Spacer(Modifier.width(5.dp))
            Text(mmss(left / 1000L), style = HaloType.mono.copy(fontSize = 10.5.sp), color = tint)
        }
    }
}

private fun mmss(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

private fun thousands(v: Int): String =
    v.toString().reversed().chunked(3).joinToString(".").reversed()

/*
 * The three sections besides the live feed. The feed is not one of them on purpose: it
 * is the reason to open Scout, so it sits above the bar and never hides behind a tap.
 */
/** One line, said plainly, where a card would otherwise have disappeared. */
@Composable
internal fun NothingHere(text: String) {
    Text(text, style = HaloType.small, color = Halo.muted, lineHeight = 17.sp)
}

private enum class ScoutTab { LIVE, BUYING, HOLDING, WHALES, FOLLOWED }

/**
 * The switch, in the app's own language rather than Material's. Each word wears the
 * color of the card it opens, so the lit chip and the heading agree. It paints its own
 * ground because it sticks to the top of the list.
 */
@Composable
private fun ScoutTabs(selected: ScoutTab, follows: Int, onPick: (ScoutTab) -> Unit) {
    val ctx = LocalContext.current
    // One track, and the chosen word is a raised pill sliding over it: five bordered
    // chips were five buttons, and which one was lit did not show at a glance.
    val tabs = buildList {
        add(Triple(ScoutTab.LIVE, R.string.crowd_tab_live, Halo.cyan))
        add(Triple(ScoutTab.BUYING, R.string.crowd_tab_buying, Halo.mint))
        add(Triple(ScoutTab.HOLDING, R.string.crowd_tab_holding, Halo.mint))
        add(Triple(ScoutTab.WHALES, R.string.crowd_tab_whales, Halo.amber))
        if (follows > 0) add(Triple(ScoutTab.FOLLOWED, R.string.crowd_tab_followed_short, Halo.amber))
    }
    Box(Modifier.fillMaxWidth().background(Halo.ground).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(rs(14)).background(Halo.cardSoft).haloBorder(rs(14), living = false).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for ((tab, label, tint) in tabs) {
                val on = tab == selected
                val bg by androidx.compose.animation.animateColorAsState(if (on) Halo.card else androidx.compose.ui.graphics.Color.Transparent, tween(220), label = "tabBg")
                val fg by androidx.compose.animation.animateColorAsState(if (on) tint else Halo.muted, tween(220), label = "tabFg")
                Box(
                    Modifier.weight(1f).height(34.dp).clip(rs(11)).background(bg)
                        .then(if (on) Modifier.haloBorder(rs(11), living = false) else Modifier)
                        .clickable { Haptics.tick(ctx); onPick(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(label), style = HaloType.label.copy(fontSize = 11.5.sp), color = fg, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun CrowdPage(owner: String?, signer: SeedVaultSigner?, openMint: String? = null, onBuy: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    // One reader for the whole page. Three cards used to decide separately whether to talk
    // to the network, and the feed was as old as the last time another card happened to
    // write the file, hours on a phone opened in the morning. The page polls once a minute
    // while on screen and hands one answer to all three. A minute against a scanner that
    // publishes every four is fine: six kilobytes, served from the local copy until it ages
    // past [SeekerFeed.FRESH_MS].
    var feed by remember { mutableStateOf<SeekerFeed.Feed?>(null) }
    // Null while the first read is still running: waiting and quiet are not the
    // same fact and the card says them differently.
    var events by remember { mutableStateOf<List<CrowdBuy>?>(null) }
    // Which entrance animations have already played during this visit. Inside a
    // lazy list a card that scrolls past the top is thrown away, so without this
    // every bar and every feed row replays its arrival each time it comes back.
    val played = remember { mutableSetOf<String>() }
    var feedOpen by rememberSaveable { mutableStateOf(false) }
    // Nothing is drawn until the service has spoken. Drawing the phone's own three rows
    // first and then rebuilding from the service replayed every entrance: the page threw
    // away what it had just shown. Waiting one beat for the cached file, a disk read, means
    // the first thing drawn is the whole picture.
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(feed, asked) {
        if (!asked) return@LaunchedEffect
        // A failure keeps what is on screen. Replacing a good list with an empty
        // one says "nobody is buying anything", which is a different claim from
        // "we could not look".
        events = runCatching { crowdEvents(ctx, feed) }.getOrNull() ?: events
    }
    LaunchedEffect(Unit) {
        // The file first, so the page is never blank while the network answers.
        val cached = withContext(Dispatchers.IO) { SeekerFeed.cached(ctx) }
        if (cached != null) feed = cached
        if (!SeekerFeed.available) { asked = true; return@LaunchedEffect }
        // With a file in hand we can draw now; without one there is nothing to
        // draw yet, so the wait stays on screen until the service answers.
        if (cached != null) asked = true
        while (true) {
            withContext(Dispatchers.IO) { SeekerFeed.refresh(ctx) }?.let { feed = it }
            asked = true
            kotlinx.coroutines.delay(150_000)
        }
    }
    // Which of the five the bar is showing. Kept across a rotation, because
    // turning the phone is not a request to go back to the start.
    var tab by rememberSaveable { mutableStateOf(ScoutTab.LIVE) }
    // Read once per visit: following somebody is not something that changes
    // under you while you look at the bar.
    val follows = remember { Follows.all(ctx).toList() }
    var person by remember { mutableStateOf<String?>(null) }
    person?.let { addr ->
        WalletPage(addr, events.orEmpty().filter { it.wallet == addr }.sortedBy { it.at }) { person = null }
    }

    // No card and no title on any tab: as its own tab the frame is a box around the whole
    // screen and the title repeats the word already lit in the bar. The census, the ranking
    // and the whales each sat three frames deep with the picture squeezed into what was left.
    val pad = Modifier.padding(horizontal = 14.dp)

    // Nothing scrolls to make the bar work. Pinned inside the list under the feed, it sat
    // near the bottom with the section it names off screen: you pressed a word and only the
    // word changed. So the bar lives at the top and the section under it gets its own scroll.
    Column(
        Modifier.fillMaxSize().background(Halo.ground).statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(pad.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(rs(18)).background(Halo.cardSoft).haloBorder(rs(18), living = false).clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) { HaloIcon(HIcon.CHEVRON_LEFT, Halo.ink, 18.dp, description = stringResource(R.string.back)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.crowd_sheet), style = HaloType.screen, color = Halo.ink)
                // Who we are actually looking at, said once, at the top, before any
                // chart. "What the Seekers are buying" invites you to assume all of
                // them, and the truth is a tenth: the rest have been still for months.
                WhoLine(feed)
            }
        }
        Spacer(Modifier.height(10.dp))
        ScoutTabs(tab, follows.size) { tab = it }
        Column(
            Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(pad) {
                when (tab) {
                    // The ranking rides on top of the stream rather than in a tab of its own: same question
                    // at two speeds, and on separate tabs each looked thin with half the screen empty.
                    ScoutTab.LIVE -> CrowdFeed(
                        events, feedOpen, { feedOpen = !feedOpen }, played,
                        onWallet = { person = it }, owner = owner, signer = signer, openMint = openMint, onBuy = onBuy,
                    )
                    ScoutTab.BUYING -> SeekerCard(feed) {}
                    ScoutTab.HOLDING -> SeekerHoldingsCard(animate = remember { played.add("census") })
                    ScoutTab.WHALES -> SeekerWhalesCard()
                    ScoutTab.FOLLOWED -> FollowedList(follows, events) { person = it }
                }
            }
        }
    }
}

/**
 * The wallets you follow, where somebody can find them. The list lived inside the agent
 * page behind an open budget, so with no budget the star did something invisible. It
 * belongs next to the feed where the star lives, and it says what the star does.
 */
@Composable
private fun FollowedList(follows: List<String>, events: List<CrowdBuy>?, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val now = System.currentTimeMillis()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        follows.forEach { addr ->
            val last = events.orEmpty().filter { it.wallet == addr }.maxByOrNull { it.at }
            GlassCard {
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(addr); Haptics.tick(ctx) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(addr, 34.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(com.clearsign.core.SeekerCrowd.nickname(addr), style = HaloType.body, color = Halo.ink, maxLines = 1)
                        Text(
                            last?.let {
                                stringResource(
                                    if (it.sell) R.string.folw_last_sell else R.string.folw_last_buy,
                                    it.symbol, mmss((now - it.at) / 1000),
                                )
                            } ?: stringResource(R.string.folw_quiet),
                            style = HaloType.small, color = if (last == null) Halo.muted else Halo.mint, maxLines = 1,
                        )
                    }
                    HaloIcon(HIcon.CHEVRON_RIGHT, Halo.muted, 18.dp)
                }
            }
        }
        // What the star actually does, in three lines, once.
        Text(stringResource(R.string.folw_what_1), style = HaloType.small, color = Halo.muted, lineHeight = 17.sp)
        Text(stringResource(R.string.folw_what_2), style = HaloType.small, color = Halo.muted, lineHeight = 17.sp)
        Text(stringResource(R.string.folw_what_3), style = HaloType.small, color = Halo.muted, lineHeight = 17.sp)
    }
}
