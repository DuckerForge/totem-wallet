@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One person in the crowd, opened by tapping their name. The feed says a name bought a coin,
 * a headline with no story. This page tells the story we can honestly tell and stops where
 * our knowledge stops: what they hold now, live from the chain, and every move inside the
 * published window drawn on the price line. Not whether they are any good: the scanner
 * publishes the last forty events across ten thousand wallets, so a win rate would be invented.
 */
@Composable
internal fun WalletPage(address: String, moves: List<CrowdBuy>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val name = remember(address) { SeekerCrowd.nickname(address) }
    val size = remember(address) { SeekerScan.sizeOf(ctx, address) }
    val whale = moves.firstOrNull()?.tier == com.clearsign.core.SeekerTier.WHALE
    var holds by remember(address) { mutableStateOf<Holdings?>(null) }
    var failed by remember(address) { mutableStateOf(false) }
    var followed by remember(address) { mutableStateOf(Follows.has(ctx, address)) }

    LaunchedEffect(address) {
        val h = withContext(Dispatchers.IO) { runCatching { holdingsOf(address, take = 8) }.getOrNull() }
        if (h == null) failed = true else holds = h
    }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null,
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxSize(0.95f).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Who, with the one number the census can vouch for.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(address, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = HaloType.title, color = Halo.ink)
                    Text(
                        stringResource(if (whale) R.string.feed_whale else R.string.feed_dolphin) +
                            (size?.let { " · " + fmtUi(it) + " SOL" } ?: ""),
                        style = HaloType.small, color = if (whale) Halo.amber else Halo.muted,
                    )
                }
                Box(
                    Modifier.size(38.dp).clip(rs(999))
                        .background(if (followed) Halo.amber.copy(alpha = 0.16f) else Halo.cardSoft)
                        .clickable {
                            followed = Follows.toggle(ctx, address)
                            FollowWatch.sync(ctx)
                            Haptics.tick(ctx)
                        },
                    contentAlignment = Alignment.Center,
                ) { HaloIcon(if (followed) HIcon.STAR_FILLED else HIcon.STAR, if (followed) Halo.amber else Halo.muted, 19.dp) }
            }
            Text(address, fontFamily = Mono, fontSize = 10.5.sp, color = Halo.muted, lineHeight = 15.sp)

            // What they hold now, from the chain, not from the census snapshot.
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.wp_holds).uppercase(), style = HaloType.label, color = Halo.muted)
                    val h = holds
                    when {
                        failed -> Text(stringResource(R.string.wp_holds_failed), style = HaloType.small, color = Halo.muted, lineHeight = 16.sp)
                        h == null -> Text(stringResource(R.string.analyzing), style = HaloType.small, color = Halo.muted)
                        h.top.isEmpty() -> Text(stringResource(R.string.wp_holds_none), style = HaloType.small, color = Halo.muted, lineHeight = 16.sp)
                        else -> {
                            h.top.forEach { held ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    TokenLogo(held.mint, held.symbol, JupiterTokens.cached(held.mint)?.icon, 22.dp)
                                    Spacer(Modifier.width(9.dp))
                                    Text(held.symbol, style = HaloType.body, color = Halo.ink, modifier = Modifier.weight(1f), maxLines = 1)
                                    Text("$" + fmtUi(held.usd), fontFamily = Mono, fontSize = 12.sp, color = Halo.mint, style = Tabular)
                                }
                            }
                            if (h.unpriced > 0) {
                                Text(stringResource(R.string.wp_unpriced, h.unpriced), style = HaloType.small, color = Halo.muted)
                            }
                        }
                    }
                }
            }

            // Every move on this coin, drawn where it happened.
            val byMint = remember(moves) { moves.groupBy { it.mint }.toList().sortedByDescending { it.second.maxOf { m -> m.at } } }
            byMint.forEach { (mint, list) -> WalletMoves(mint, list) }

            // The edge of what we know, said out loud.
            Text(stringResource(R.string.wp_truth), style = HaloType.small, color = Halo.muted, lineHeight = 17.sp)
            Text(
                stringResource(if (followed) R.string.wp_star_on else R.string.wp_star_off),
                style = HaloType.small, color = if (followed) Halo.amber else Halo.muted, lineHeight = 17.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** One coin this wallet touched, with their moves marked on the price line. */
@Composable
private fun WalletMoves(mint: String, list: List<CrowdBuy>) {
    val symbol = list.first().symbol
    var series by remember(mint) { mutableStateOf<List<Gecko.Candle>>(emptyList()) }
    LaunchedEffect(mint) {
        series = withContext(Dispatchers.IO) { runCatching { Gecko.series(mint, spanFor(list)) }.getOrDefault(emptyList()) }
    }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TokenLogo(mint, symbol, JupiterTokens.cached(mint)?.icon, 22.dp)
                Spacer(Modifier.width(9.dp))
                Text(symbol, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Halo.ink, modifier = Modifier.weight(1f))
                val buys = list.count { !it.sell }
                val sells = list.size - buys
                Text(
                    stringResource(R.string.wp_moves, buys, sells),
                    fontFamily = Mono, fontSize = 11.sp, color = Halo.muted, style = Tabular,
                )
            }
            if (series.size > 2) {
                val from = series.first().at
                val to = series.last().at
                val span = (to - from).coerceAtLeast(1L).toFloat()
                // A move newer than the last candle belongs at the right edge, not nowhere: the last candle
                // starts at the top of the hour, so anything bought in the last hour fell outside the range
                // and the card claimed the move was two days old.
                val marks = list.filter { it.at >= from }
                    .map { SparkMark(((it.at - from) / span).coerceIn(0f, 1f), it.sell) }
                Box(Modifier.fillMaxWidth().height(72.dp)) {
                    Spark(series.map { it.close }, Halo.cyan, marks = marks)
                }
                if (marks.isEmpty()) {
                    // Their move is older than the chart we can draw. Better said
                    // than left as a line with nothing on it.
                    Text(stringResource(R.string.wp_older), style = HaloType.small, color = Halo.muted, lineHeight = 16.sp)
                }
            }
        }
    }
}

/**
 * Which chart makes the moves readable. Two days of hourly candles is right for "where is
 * this coin going" and useless for a wallet that bought and sold inside twenty minutes: both
 * rings land on one pixel. When everything happened recently and close together, five-minute
 * candles over five hours pull the two apart.
 */
internal fun spanFor(moves: List<com.clearsign.core.CrowdBuy>): Gecko.Span {
    if (moves.isEmpty()) return Gecko.Span.HOURS
    val now = System.currentTimeMillis()
    val oldest = moves.minOf { it.at }
    val tight = moves.maxOf { it.at } - oldest
    // Inside the window the short chart can actually cover, and tight enough
    // that the long one would stack them.
    return if (now - oldest < 4 * 3_600_000L && tight < 3 * 3_600_000L) Gecko.Span.MINUTES else Gecko.Span.HOURS
}
