package com.clearsign.app

import android.content.Context

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clearsign.core.CrowdBuy
import com.clearsign.core.SeekerCrowd
import com.clearsign.core.SeekerTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The buys as they happen, one line each, like a room people are talking in.
 *
 * The ranking above answers "what is the crowd doing"; this answers "who just did
 * what", and it is the half people actually watch. It also fills the silence:
 * a coin needs three separate buyers before the ranking will name it, which is
 * the right bar for a claim about a crowd and a miserable bar for a live feed.
 *
 * Each line carries the same button the coin would get anywhere else in the app,
 * and it opens the ordinary swap sheet — the receipt, the collar and the hold to
 * sign all still happen. Nothing here buys anything by itself.
 *
 * [feed] arrives from the page rather than being fetched here. It used to read the
 * cached file and nothing else, while the ranking card underneath did the actual
 * download and kept it to itself: the ranking was current and the live feed showed
 * whatever was last written to disk, which on a phone opened in the morning meant
 * a "live" feed seven hours behind. One reader at the top, everybody reads it.
 */
/**
 * The two sources merged, read off the main thread.
 *
 * This used to live inside the card. Inside a lazy list that was a trap: the
 * card is thrown away when it scrolls past the top, so scrolling back re-ran
 * the whole thing, network warm included. The page reads it once now and hands
 * the answer down.
 */
internal suspend fun crowdEvents(ctx: Context, feed: SeekerFeed.Feed?): List<CrowdBuy> =
    withContext(Dispatchers.IO) {
        // Both sources, not one or the other. The published feed is the long
        // memory; anything this phone caught itself while the feed was still
        // filling up is just as real. Same purchase seen twice is one line.
        val got = (feed?.events.orEmpty() + SeekerScan.events(ctx))
            .distinctBy { Triple(it.wallet, it.mint, it.at) }
            .sortedByDescending { it.at }
        // Same reason as the feed: an address is not a name.
        runCatching { JupiterTokens.warm(got.map { it.mint }) }
        got.map { e -> e.copy(symbol = JupiterTokens.cached(e.mint)?.symbol ?: e.symbol) }
    }

@Composable
internal fun CrowdFeed(
    events: List<CrowdBuy>?,
    open: Boolean,
    onToggle: () -> Unit,
    played: MutableSet<String>,
    /** Tapping the name opens the person, not the coin. */
    onWallet: (String) -> Unit = {},
    owner: String? = null,
    signer: SeedVaultSigner? = null,
    /** A coin to open on arrival, when a notification brought us here. */
    openMint: String? = null,
    compactRows: Int = 4,
    onBuy: (String) -> Unit,
) {
    val ctx = LocalContext.current
    // The ages tick on their own rather than waiting for new data. The page hands
    // this a fresh feed every minute, but an identical feed is an equal one and
    // recomposes nothing, so on a quiet stretch "1m" would sit there saying 1m.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    // The same wallet round-tripping the same coin is one line, not four.
    //
    // A bot that buys and sells USDe every few minutes filled the whole screen
    // with itself: four rows, one wallet, one coin, the same five SOL going back
    // and forth. What a reader wants from that is "this wallet is churning this
    // coin", once. So the newest move per wallet and coin survives and the rest
    // fold into it, with a count when there were several.
    val rows = remember(events) {
        events.orEmpty()
            .groupBy { it.wallet to it.mint }
            .values
            .map { group -> group.sortedBy { it.at } }
            .sortedByDescending { it.last().at }
    }
    val fresh = rows.firstOrNull()?.let { now - it.last().at < 5 * 60_000L } == true
    // One row open at a time. Two open rows is an accordion, and it also means two
    // quotes running against Jupiter for coins nobody is looking at any more.
    var openRow by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openMint) { openMint?.let { openRow = it } }

    // No card around it and no title above it.
    //
    // Both were right when this was one panel among four on a scrolling page.
    // As its own tab the frame is a box drawn around the whole screen and the
    // title repeats the word already lit in the bar above it, while the rows
    // themselves were squeezed into a third of the height.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        run {
            // The list says something whatever happens. Vanishing read as broken, and it
            // moved the switch bar under the reader's thumb. Waiting and quiet
            // are different facts, so they get different words.
            if (events == null) {
                ScouterWait()
                return@Column
            }
            if (rows.isEmpty()) {
                Text(stringResource(R.string.feed_empty), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted, lineHeight = 16.sp)
                return@Column
            }
            // Each row slides in once and once only. The entrance is keyed on the
            // purchase, and the page remembers which ones have already played, so
            // scrolling the card away and back does not replay the cascade. A buy
            // that lands while you are looking still slides in on its own.
            rows.forEachIndexed { i, moves ->
                val e = moves.last()
                val id = "feed:" + e.wallet + e.at
                val first = remember(id) { played.add(id) }
                val mine = openRow == e.mint
                Box(if (first) Modifier.staggeredEntrance(i, key = id) else Modifier) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FeedRow(e, moves.size, now, mine, onWallet = { onWallet(e.wallet) }) { openRow = if (mine) null else e.mint }
                        // The terminal, under the row that made you want it. Nobody
                        // leaves the feed to trade any more.
                        if (mine) FeedBuyPanel(e.mint, e.symbol, moves, owner, signer) { openRow = null }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.feed_note) + " " + stringResource(R.string.feed_star_note),
                fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, lineHeight = 15.sp,
            )
        }
    }
}

/** A dot that breathes while the crowd is moving. */
@Composable
private fun LiveDot() {
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(Modifier.size(7.dp).clip(rs(999)).background(Halo.cyan.copy(alpha = a)))
}

@Composable
private fun FeedRow(e: CrowdBuy, times: Int, now: Long, open: Boolean, onWallet: () -> Unit, onOpen: () -> Unit) {
    val whale = e.tier == SeekerTier.WHALE
    // A sale reads red whoever made it: the size of the wallet matters less than
    // the direction when somebody is on the way out.
    val tint = if (e.sell) Halo.red else if (whale) Halo.amber else Halo.cyan
    val ctx = LocalContext.current
    val name = remember(e.wallet) { SeekerCrowd.nickname(e.wallet) }
    // The colour alone never said which of the two this was. The size does, and it
    // is the only whale score worth having: how much this wallet is actually holding.
    val size = remember(e.wallet) { SeekerScan.sizeOf(ctx, e.wallet) }
    Row(
        Modifier.fillMaxWidth().clip(rs(Radius.row))
            .background(if (open) Halo.cardHi else Halo.cardSoft)
            .clickable { onOpen(); Haptics.tick(ctx) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The level, on the left, where a reading belongs. It used to sit next to
        // the coin name, where "balena · 97 SOL" read as if they had bought 97 of
        // the coin — the number was right and in exactly the wrong place.
        Box(
            Modifier.size(42.dp).clickable { onWallet(); Haptics.tick(ctx) },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(36.dp).align(Alignment.TopStart).clip(rs(999)).background(tint.copy(alpha = 0.13f))
                    .border(1.2.dp, tint.copy(alpha = 0.55f), rs(999)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    size?.let { compact(it) } ?: name.take(1),
                    fontFamily = Mono, fontWeight = FontWeight.Bold,
                    fontSize = if (size != null) 10.5.sp else 13.sp, color = tint, style = Tabular,
                )
            }
            // The coin as a badge on the buyer: who, and what, in one object. A third
            // column for the logo would have squeezed the sentence that matters.
            Box(
                Modifier.align(Alignment.BottomEnd).size(20.dp).clip(rs(999)).background(Halo.card)
                    .border(1.dp, Halo.cardSoft, rs(999)),
                contentAlignment = Alignment.Center,
            ) {
                TokenLogo(e.mint, e.symbol, JupiterTokens.cached(e.mint)?.icon, 18.dp)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (e.sell) R.string.feed_line_sell else R.string.feed_line, name, e.symbol),
                fontFamily = Inter, fontSize = 12.5.sp, color = Halo.ink, lineHeight = 17.sp,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    // "spent" on a sale said the opposite of what happened: money
                    // came out of the coin, it did not go into it.
                    if (e.sell) R.string.feed_meta_sell else R.string.feed_meta2,
                    stringResource(if (whale) R.string.feed_whale else R.string.feed_dolphin),
                    sol(e.solSpent), ago(e.at, now),
                ),
                fontFamily = Mono, fontSize = 10.5.sp,
                color = if (e.sell) Halo.red.copy(alpha = 0.8f) else if (whale) Halo.amber.copy(alpha = 0.85f) else Halo.muted,
                style = Tabular, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            // Said once, quietly: this wallet has been in and out of this coin
            // several times in the window. That is a fact about the wallet, and
            // usually the most useful one on the row.
            if (times > 1) Text(
                stringResource(R.string.feed_churn, times),
                fontFamily = Inter, fontSize = 10.5.sp, color = Halo.amber, maxLines = 1,
            )
        }
        Spacer(Modifier.width(6.dp))
        // Follow this wallet: from now on what it buys goes to the agent as a
        // candidate, through the gates. A star, because that is what following
        // looks like everywhere else, and it stays lit so the list says who.
        var followed by remember(e.wallet) { mutableStateOf(Follows.has(ctx, e.wallet)) }
        Box(
            Modifier.size(30.dp).clip(rs(999))
                .background(if (followed) Halo.amber.copy(alpha = 0.16f) else Halo.cardSoft)
                .clickable {
                    followed = Follows.toggle(ctx, e.wallet)
                    // Following now means two things, and the second one is the
                    // one the star looks like it promises: the phone tells you.
                    FollowWatch.sync(ctx)
                    Haptics.tick(ctx)
                },
            contentAlignment = Alignment.Center,
        ) { HaloIcon(if (followed) HIcon.STAR_FILLED else HIcon.STAR, if (followed) Halo.amber else Halo.muted, 15.dp) }
        Spacer(Modifier.width(6.dp))
        // The word and the colour have to agree, or the list reads as broken.
        //
        // Both buttons used to be mint, and mint in this app means go. So a row
        // saying "sold" carried a green button saying "Look" right next to a row
        // saying "bought" with a green button saying "Buy", and two identical
        // pills with different words on them look like a bug rather than a
        // choice. It is a choice: buying what somebody has just sold is not a
        // thing to offer in one tap. Now only a buy is green. A sale gets a
        // quiet button, and the eye sorts the two apart before reading either.
        val go = !open && !e.sell
        val pill = if (go) Halo.mint else Halo.muted
        Box(
            Modifier.clip(rs(999)).background(pill.copy(alpha = if (go) 0.14f else 0.10f))
                .border(1.dp, pill.copy(alpha = if (go) 0.45f else 0.30f), rs(999))
                .clickable { onOpen(); Haptics.tick(ctx) }
                .padding(horizontal = 13.dp, vertical = 6.dp),
        ) {
            Text(
                stringResource(if (open) R.string.feed_close else if (e.sell) R.string.feed_look else R.string.feed_buy),
                fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, color = pill,
            )
        }
    }
}

/**
 * The wallet's size, small enough to live inside a 38dp circle. Thousands become
 * "8k": inside a badge the exact figure is unreadable and the order of magnitude
 * is the whole message.
 */
private fun compact(v: Double): String = when {
    v >= 1000 -> String.format("%.0fk", v / 1000)
    v >= 100 -> String.format("%.0f", v)
    v >= 10 -> String.format("%.0f", v)
    else -> String.format("%.1f", v)
}

private fun sol(v: Double): String = if (v >= 1) String.format("%.2f", v) else String.format("%.3f", v)

/** Minutes, then hours, then days. Nobody needs "2h 14m". */
private fun ago(at: Long, now: Long): String {
    val m = ((now - at) / 60_000L).coerceAtLeast(0L)
    return when {
        m < 1 -> "<1m"
        m < 60 -> "${m}m"
        m < 1440 -> "${m / 60}h"
        else -> "${m / 1440}g"
    }
}


/**
 * The second before the crowd arrives.
 *
 * It was a line of grey text saying it was looking, which is the truthful and
 * completely forgettable version. This page is a scanner pointed at a hundred
 * and twenty thousand phones, and for one second a year it gets to look like
 * one. The sweep is drawn, not loaded: four rings, a cross, a beam with a
 * trail, and contacts that light as the beam crosses them and fade behind it.
 *
 * The contacts sit at fixed angles chosen once, so the thing reads as an
 * instrument finding something rather than as noise. Nothing here means
 * anything — there is no data yet, that is the point — and nothing here is
 * shaped like a number, so it cannot be mistaken for one.
 */
@Composable
private fun ScouterWait() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val t = rememberInfiniteTransition(label = "scouter")
        val beam by t.animateFloat(
            0f, 360f,
            infiniteRepeatable(tween(1700, easing = LinearEasing)),
            label = "beam",
        )
        // Chosen once and kept: a radar whose contacts jump every frame is
        // static, and static is what a broken instrument looks like.
        val blips = remember {
            val r = kotlin.random.Random(11)
            List(6) { Triple(r.nextFloat() * 360f, 0.30f + r.nextFloat() * 0.58f, 1.6f + r.nextFloat() * 1.9f) }
        }
        Canvas(Modifier.size(132.dp)) {
            val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            val rad = size.minDimension / 2f * 0.84f
            val box = androidx.compose.ui.geometry.Size(rad * 2, rad * 2)
            val at = androidx.compose.ui.geometry.Offset(c.x - rad, c.y - rad)

            for (k in 1..4) {
                drawCircle(Halo.cyan.copy(alpha = if (k == 4) 0.34f else 0.13f), rad * k / 4f, c, style = Stroke(1f))
            }
            drawLine(Halo.cyan.copy(alpha = 0.12f), androidx.compose.ui.geometry.Offset(c.x - rad, c.y), androidx.compose.ui.geometry.Offset(c.x + rad, c.y), 1f)
            drawLine(Halo.cyan.copy(alpha = 0.12f), androidx.compose.ui.geometry.Offset(c.x, c.y - rad), androidx.compose.ui.geometry.Offset(c.x, c.y + rad), 1f)
            // Ticks on the outer ring, so the circle reads as a dial.
            for (k in 0 until 24) {
                val a = k * 15.0 * Math.PI / 180.0
                val long = k % 6 == 0
                val r0 = rad * (if (long) 0.90f else 0.95f)
                drawLine(
                    Halo.cyan.copy(alpha = if (long) 0.40f else 0.18f),
                    androidx.compose.ui.geometry.Offset(c.x + (r0 * kotlin.math.cos(a)).toFloat(), c.y + (r0 * kotlin.math.sin(a)).toFloat()),
                    androidx.compose.ui.geometry.Offset(c.x + (rad * kotlin.math.cos(a)).toFloat(), c.y + (rad * kotlin.math.sin(a)).toFloat()),
                    1f,
                )
            }
            // The beam, as a stack of thin wedges fading behind the leading edge.
            // A sweep gradient was the obvious way and it came out flat: the trail
            // has to die over about seventy degrees, not over the whole circle.
            val steps = 26
            for (i in 0 until steps) {
                drawArc(
                    Halo.cyan.copy(alpha = 0.30f * (1f - i / steps.toFloat())),
                    startAngle = beam - i * 2.7f, sweepAngle = 2.9f, useCenter = true,
                    topLeft = at, size = box,
                )
            }
            val br = beam * Math.PI / 180.0
            drawLine(
                Halo.cyan.copy(alpha = 0.85f), c,
                androidx.compose.ui.geometry.Offset(c.x + (rad * kotlin.math.cos(br)).toFloat(), c.y + (rad * kotlin.math.sin(br)).toFloat()),
                1.6f,
            )
            blips.forEach { (angle, dist, dot) ->
                // How far behind the beam this contact is, as a fraction of the
                // seventy degrees the trail lives for.
                val behind = ((beam - angle) % 360f + 360f) % 360f
                val lit = if (behind <= 70f) 1f - behind / 70f else 0f
                if (lit <= 0.02f) return@forEach
                val a = angle * Math.PI / 180.0
                val p = androidx.compose.ui.geometry.Offset(
                    c.x + (rad * dist * kotlin.math.cos(a)).toFloat(),
                    c.y + (rad * dist * kotlin.math.sin(a)).toFloat(),
                )
                drawCircle(Halo.mint.copy(alpha = 0.22f * lit), dot * 3.2f, p)
                drawCircle(Halo.mint.copy(alpha = lit), dot, p)
            }
        }
        Text(
            stringResource(R.string.feed_waiting),
            fontFamily = Mono, fontSize = 11.sp, color = Halo.cyan.copy(alpha = 0.75f), lineHeight = 16.sp,
        )
    }
}
