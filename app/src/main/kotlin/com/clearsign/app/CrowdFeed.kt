package com.clearsign.app

import android.content.Context

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
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
 * The buys as they happen, one line each, like a room people talk in. The ranking says
 * what the crowd is doing; this says who just did what, and it fills the silence, since
 * a coin needs three buyers before the ranking names it. Each line carries the ordinary
 * swap sheet: receipt, collar, hold to sign. Nothing here buys by itself. [feed] comes
 * from the page: reading the cached file here once showed a "live" feed seven hours old.
 */
/**
 * The two sources merged, off the main thread. It lived inside the card, and inside a
 * lazy list that re-ran the whole thing, network warm included, on every scroll back.
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

    // The same wallet round-tripping the same coin is one line, not four: a bot churning
    // USDe every few minutes filled the screen with itself. The newest move per wallet and
    // coin survives, the rest fold into it with a count.
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

    // No card around it and no title above it: as its own tab the frame boxed the whole
    // screen, the title repeated the lit word in the bar, and the rows had a third of the height.
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
            // Each row slides in once. The entrance is keyed on the purchase and the page remembers
            // which have played, so scrolling away and back does not replay the cascade.
            rows.forEachIndexed { i, moves ->
                val e = moves.last()
                val id = "feed:" + e.wallet + e.at
                val first = remember(id) { played.add(id) }
                val mine = openRow == e.mint
                Box(if (first) Modifier.staggeredEntrance(i, key = id) else Modifier) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FeedRow(e, moves, now, mine, onWallet = { onWallet(e.wallet) }) { openRow = if (mine) null else e.mint }
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
private fun FeedRow(e: CrowdBuy, moves: List<CrowdBuy>, now: Long, open: Boolean, onWallet: () -> Unit, onOpen: () -> Unit) {
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
            // The round trip, when we watched both ends. "Took out 10.28 SOL" is half a sentence; if
            // the same wallet's buy is in this window the two numbers together are what anyone wants
            // to know. Written only with both sides: guessing at a position opened before the window
            // would be worse than staying quiet.
            val paid = moves.filter { !it.sell }.sumOf { it.solSpent }
            val took = moves.filter { it.sell }.sumOf { it.solSpent }
            val times = moves.size
            if (paid > 0.0 && took > 0.0) {
                val pct = (took - paid) / paid * 100.0
                Text(
                    stringResource(R.string.feed_round, sol(paid), sol(took), (if (pct >= 0) "+" else "") + String.format(java.util.Locale.ROOT, "%.0f%%", pct)),
                    fontFamily = Mono, fontSize = 10.5.sp, style = Tabular, maxLines = 1,
                    color = if (pct >= 0) Halo.mint else Halo.red,
                )
            } else if (times > 1) {
                // Said once, quietly: this wallet has been in and out of this coin
                // several times in the window. That is a fact about the wallet, and
                // usually the most useful one on the row.
                Text(
                    stringResource(R.string.feed_churn, times),
                    fontFamily = Inter, fontSize = 10.5.sp, color = Halo.amber, maxLines = 1,
                )
            }
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
        // The word and the color must agree. Both buttons were mint, and mint here means go: a
        // "sold" row with a green "Look" next to a "bought" row with a green "Buy" looked like a
        // bug. Buying what somebody just sold is not a one-tap offer, so only a buy is green.
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

/** The wallet's size inside a 38dp circle: thousands become "8k", the order of magnitude is the whole message. */
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
 * The second before the crowd arrives. Grey text saying "looking" was truthful and
 * forgettable; a radar was the wrong instrument, this page fills from the top down. So a
 * tube: scanlines and a bar rolling down like an old set before the picture locks, the
 * look the terminal receipt and the CRT switch already own. Drawn, not loaded, and
 * nothing in it shaped like a number, because there is no data yet.
 */
@Composable
private fun ScouterWait() {
    val t = rememberInfiniteTransition(label = "tube")
    val roll by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "roll",
    )
    Box(
        Modifier.fillMaxWidth().height(118.dp).clip(rs(14))
            .background(Halo.ground)
            .border(1.dp, Halo.cyan.copy(alpha = 0.22f), rs(14)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // The lines of the raster. Three pixels apart is where it stops
            // looking like a texture and starts looking like a screen.
            var y = 0f
            while (y < size.height) {
                drawLine(
                    Halo.cyan.copy(alpha = 0.055f),
                    androidx.compose.ui.geometry.Offset(0f, y),
                    androidx.compose.ui.geometry.Offset(size.width, y),
                    1f,
                )
                y += 3f
            }
            // The bar, and it does not wrap: it leaves the bottom and comes back
            // at the top, which is what an unlocked picture actually does.
            val band = size.height * 0.34f
            val edge = -band + roll * (size.height + band)
            drawRect(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Halo.cyan.copy(alpha = 0f), Halo.cyan.copy(alpha = 0.16f)),
                    startY = edge - band, endY = edge,
                ),
                topLeft = androidx.compose.ui.geometry.Offset(0f, edge - band),
                size = androidx.compose.ui.geometry.Size(size.width, band),
            )
            // The beam itself: a hot line with a softer one under it, because a
            // single hairline reads as a divider and not as light.
            drawLine(
                Halo.cyan.copy(alpha = 0.30f),
                androidx.compose.ui.geometry.Offset(0f, edge + 2f),
                androidx.compose.ui.geometry.Offset(size.width, edge + 2f),
                3f,
            )
            drawLine(
                Halo.cyan.copy(alpha = 0.95f),
                androidx.compose.ui.geometry.Offset(0f, edge),
                androidx.compose.ui.geometry.Offset(size.width, edge),
                1.4f,
            )
        }
        Text(
            stringResource(R.string.feed_waiting),
            fontFamily = Mono, fontSize = 11.5.sp, color = Halo.cyan.copy(alpha = 0.85f), lineHeight = 16.sp,
        )
    }
}
