package com.clearsign.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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

/**
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
@Composable
internal fun CrowdFeed(feed: SeekerFeed.Feed?, compactRows: Int = 5, onBuy: (String) -> Unit) {
    val ctx = LocalContext.current
    var events by remember { mutableStateOf<List<CrowdBuy>>(emptyList()) }

    LaunchedEffect(feed) {
        withContext(Dispatchers.IO) {
            // Both sources, not one or the other. The published feed is the long
            // memory; anything this phone caught itself while the feed was still
            // filling up is just as real. Same purchase seen twice is one line.
            val got = (feed?.events.orEmpty() + SeekerScan.events(ctx))
                .distinctBy { Triple(it.wallet, it.mint, it.at) }
                .sortedByDescending { it.at }
            // Same reason as the feed: an address is not a name.
            runCatching { JupiterTokens.warm(got.map { it.mint }) }
            events = got.map { e ->
                e.copy(symbol = JupiterTokens.cached(e.mint)?.symbol ?: e.symbol)
            }
        }
    }
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

    // Five rows, not twelve. This card sits at the top of Scout and the switch
    // for the other three sections sits under it: at twelve rows the switch
    // starts off the bottom of the screen, which is the problem this layout
    // exists to solve. The rest is one tap away and nothing is lost.
    var all by remember { mutableStateOf(false) }
    val fresh = events.firstOrNull()?.let { now - it.at < 5 * 60_000L } == true

    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HaloIcon(HIcon.MEGAPHONE, Halo.cyan, 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.feed_title),
                    fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = Halo.cyan, modifier = Modifier.weight(1f),
                )
                // A purchase in the last five minutes: the dot breathes. It says
                // "this is happening now" without spending a word on it.
                if (fresh) LiveDot()
            }
            if (events.isEmpty()) {
                // The card stays even with nothing in it. Vanishing read as broken,
                // and "nobody has bought anything for a while" is itself a fact.
                Text(stringResource(R.string.feed_empty), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted, lineHeight = 16.sp)
                return@Column
            }
            // Each row slides in once, keyed on the purchase itself, so a new
            // buy landing at the top arrives rather than being suddenly there.
            events.take(if (all) 12 else compactRows).forEachIndexed { i, e ->
                Box(Modifier.staggeredEntrance(i, key = e.wallet + e.at)) { FeedRow(e, now, onBuy) }
            }
            if (events.size > compactRows) {
                SmallChip(
                    stringResource(if (all) R.string.feed_show_less else R.string.feed_show_all),
                    if (all) null else HIcon.CHEVRON_DOWN,
                    tint = Halo.muted,
                ) { all = !all; Haptics.tick(ctx) }
            }
            Text(
                stringResource(R.string.feed_note) + " " + stringResource(R.string.feed_follow_note),
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
private fun FeedRow(e: CrowdBuy, now: Long, onBuy: (String) -> Unit) {
    val whale = e.tier == SeekerTier.WHALE
    val tint = if (whale) Halo.amber else Halo.cyan
    val ctx = LocalContext.current
    val name = remember(e.wallet) { SeekerCrowd.nickname(e.wallet) }
    // The colour alone never said which of the two this was. The size does, and it
    // is the only whale score worth having: how much this wallet is actually holding.
    val size = remember(e.wallet) { SeekerScan.sizeOf(ctx, e.wallet) }
    Row(
        Modifier.fillMaxWidth().clip(rs(Radius.row)).background(Halo.cardSoft)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The level, on the left, where a reading belongs. It used to sit next to
        // the coin name, where "balena · 97 SOL" read as if they had bought 97 of
        // the coin — the number was right and in exactly the wrong place.
        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
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
                stringResource(R.string.feed_line, name, e.symbol),
                fontFamily = Inter, fontSize = 12.5.sp, color = Halo.ink, lineHeight = 17.sp,
            )
            Text(
                stringResource(
                    R.string.feed_meta2,
                    stringResource(if (whale) R.string.feed_whale else R.string.feed_dolphin),
                    sol(e.solSpent), ago(e.at, now),
                ),
                fontFamily = Mono, fontSize = 10.5.sp,
                color = if (whale) Halo.amber.copy(alpha = 0.85f) else Halo.muted, style = Tabular,
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
                .clickable { followed = Follows.toggle(ctx, e.wallet); Haptics.tick(ctx) },
            contentAlignment = Alignment.Center,
        ) { HaloIcon(if (followed) HIcon.STAR_FILLED else HIcon.STAR, if (followed) Halo.amber else Halo.muted, 15.dp) }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.clip(rs(999)).background(Halo.mint.copy(alpha = 0.14f))
                .border(1.dp, Halo.mint.copy(alpha = 0.45f), rs(999))
                .clickable { onBuy(e.mint) }
                .padding(horizontal = 13.dp, vertical = 6.dp),
        ) {
            Text(
                stringResource(R.string.feed_buy), fontFamily = Sora,
                fontWeight = FontWeight.Bold, fontSize = 11.5.sp, color = Halo.mint,
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
