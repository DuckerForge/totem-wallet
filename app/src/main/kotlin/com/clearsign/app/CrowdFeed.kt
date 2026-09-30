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
 * Live buys, one line each. The ranking needs three buyers before it names a coin; this shows
 * every buy as it lands. Each line opens the normal swap sheet (receipt, collar, hold to sign);
 * nothing buys on its own. [feed] comes from the page: the cached file can be hours old.
 */
/** Both sources merged, off the main thread. Kept out of the card so the lazy list doesn't rerun it on scroll. */
internal suspend fun crowdEvents(ctx: Context, feed: SeekerFeed.Feed?): List<CrowdBuy> =
    withContext(Dispatchers.IO) {
        // Published feed plus what this phone caught while the feed was still filling.
        // A purchase seen by both is one line.
        val got = (feed?.events.orEmpty() + SeekerScan.events(ctx))
            .distinctBy { Triple(it.wallet, it.mint, it.at) }
            .sortedByDescending { it.at }
        // Resolve symbols from the token list, as the feed does.
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
    // Tick the ages every 30 s: an unchanged feed recomposes nothing, so "1m" would never move.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    // One line per wallet and coin, newest move shown, the rest folded in with a count.
    // Otherwise a bot churning USDe every few minutes fills the screen.
    val rows = remember(events) {
        events.orEmpty()
            .groupBy { it.wallet to it.mint }
            .values
            .map { group -> group.sortedBy { it.at } }
            .sortedByDescending { it.last().at }
    }
    val fresh = rows.firstOrNull()?.let { now - it.last().at < 5 * 60_000L } == true
    // One row open at a time, so only one Jupiter quote runs.
    var openRow by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openMint) { openMint?.let { openRow = it } }

    // No card or title: as its own tab, the frame boxed the screen and the title repeated the tab bar.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        run {
            // Always show something: an empty space looks broken and shifts the tab bar.
            // Waiting and empty get different text.
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
                        // The buy panel opens under its row, so trading doesn't leave the feed.
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

/** Pulsing dot for a live feed. */
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
    // Sales are red whatever the tier: direction matters more than wallet size.
    val tint = if (e.sell) Halo.red else if (whale) Halo.amber else Halo.cyan
    val ctx = LocalContext.current
    val name = remember(e.wallet) { SeekerCrowd.nickname(e.wallet) }
    // Show how much the wallet holds: color alone doesn't say whale or dolphin.
    val size = remember(e.wallet) { SeekerScan.sizeOf(ctx, e.wallet) }
    Row(
        Modifier.fillMaxWidth().clip(rs(Radius.row))
            .background(if (open) Halo.cardHi else Halo.cardSoft)
            .clickable { onOpen(); Haptics.tick(ctx) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Wallet size on the left: next to the coin name, "balena · 97 SOL" read as if
        // 97 of the coin had been bought.
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
            // Coin logo as a badge on the buyer; a separate column would squeeze the text.
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
                    // Sales get their own wording: "spent" would say the opposite.
                    if (e.sell) R.string.feed_meta_sell else R.string.feed_meta2,
                    stringResource(if (whale) R.string.feed_whale else R.string.feed_dolphin),
                    sol(e.solSpent), ago(e.at, now),
                ),
                fontFamily = Mono, fontSize = 10.5.sp,
                color = if (e.sell) Halo.red.copy(alpha = 0.8f) else if (whale) Halo.amber.copy(alpha = 0.85f) else Halo.muted,
                style = Tabular, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            // Round trip, only when both the buy and the sale are in the window. No guessing at
            // a position opened before it.
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
                // In and out of this coin several times in the window: often the most useful fact here.
                Text(
                    stringResource(R.string.feed_churn, times),
                    fontFamily = Inter, fontSize = 10.5.sp, color = Halo.amber, maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        // Follow: this wallet's buys become agent candidates, still through the gates.
        // The star stays lit on followed wallets.
        var followed by remember(e.wallet) { mutableStateOf(Follows.has(ctx, e.wallet)) }
        Box(
            Modifier.size(30.dp).clip(rs(999))
                .background(if (followed) Halo.amber.copy(alpha = 0.16f) else Halo.cardSoft)
                .clickable {
                    followed = Follows.toggle(ctx, e.wallet)
                    // Following also turns on notifications for this wallet.
                    FollowWatch.sync(ctx)
                    Haptics.tick(ctx)
                },
            contentAlignment = Alignment.Center,
        ) { HaloIcon(if (followed) HIcon.STAR_FILLED else HIcon.STAR, if (followed) Halo.amber else Halo.muted, 15.dp) }
        Spacer(Modifier.width(6.dp))
        // Word and color must agree: mint means go, so only a buy is green. Buying what
        // someone just sold isn't a one-tap offer.
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

/** Wallet size for the small circle: thousands become "8k". */
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
 * Loading state: scanlines and a bar rolling down like an old CRT before the picture locks,
 * matching the terminal receipt and the CRT switch. Drawn, not loaded, and nothing number-like
 * in it since there is no data yet.
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
            // Raster lines 3 px apart; tighter reads as texture, not a screen.
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
            // The bar leaves the bottom and re-enters at the top, like an unlocked picture.
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
            // Bright line over a softer one: a single hairline reads as a divider.
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
