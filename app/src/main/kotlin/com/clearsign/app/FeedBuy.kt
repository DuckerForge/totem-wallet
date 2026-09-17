@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The terminal, inside the row.
 *
 * The loop used to be: see that somebody bought, press Buy, watch Scout be
 * destroyed, land in a sheet with an empty amount field, type, wait, review,
 * sign, come back to a feed rebuilt from scratch and moved on. Four taps and a
 * different screen, for a decision that took one second to make.
 *
 * Now the row opens downward and everything happens in it: what the coin costs,
 * what it has done, what that person put in, how much you want to put in, the
 * receipt built from the real bytes, and the hold. Nothing is hidden to make it
 * fast. The speed comes from not going anywhere, which is the whole point.
 */
@Composable
internal fun FeedBuyPanel(
    mint: String,
    symbol: String,
    /** Every move this wallet made on this coin inside the window, oldest first. */
    moves: List<com.clearsign.core.CrowdBuy>,
    owner: String?,
    signer: SeedVaultSigner?,
    onDone: () -> Unit,
) {
    val last = moves.lastOrNull() ?: return
    val sell = last.sell
    val solSpent = last.solSpent
    val ctx = LocalContext.current
    var px by remember(mint) { mutableStateOf<Prices.Px?>(null) }
    var series by remember(mint) { mutableStateOf<List<Gecko.Candle>>(emptyList()) }
    var decimals by remember(mint) { mutableStateOf<Int?>(null) }
    var balance by remember(owner) { mutableStateOf<Long?>(null) }

    LaunchedEffect(mint) {
        withContext(Dispatchers.IO) {
            px = runCatching { Prices.quotes(listOf(mint))[mint] }.getOrNull()
            decimals = runCatching { JupiterTokens.byMints(listOf(mint))[mint]?.decimals }.getOrNull()
            series = runCatching { Gecko.series(mint, spanFor(moves)) }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(owner) {
        owner ?: return@LaunchedEffect
        balance = withContext(Dispatchers.IO) { runCatching { SolanaRpc.getBalance(SolanaRpc.urlFor(null), owner) }.getOrNull() }
    }

    Column(
        Modifier.fillMaxWidth().clip(rs(Radius.row)).background(Halo.ground).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // The coin, priced. The row above says who and what; this says how much.
        Row(verticalAlignment = Alignment.CenterVertically) {
            TokenLogo(mint, symbol, JupiterTokens.cached(mint)?.icon, 26.dp)
            Spacer(Modifier.width(9.dp))
            Text(symbol, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Halo.ink)
            Spacer(Modifier.width(9.dp))
            px?.let { p ->
                Text(fmtPriceUsd(p.usd), fontFamily = Mono, fontSize = 12.5.sp, color = Halo.ink, style = Tabular)
                p.change24h?.let { c ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        String.format(java.util.Locale.ROOT, "%+.1f%%", c),
                        fontFamily = Mono, fontSize = 12.sp, style = Tabular,
                        color = if (c >= 0) Halo.mint else Halo.red,
                    )
                }
            }
        }

        // Two days of hourly closes, with this wallet's moves marked on them.
        //
        // This is the whole point of the panel. A row saying "somebody bought
        // BONK" is a name and a coin; the same row with a ring on the line where
        // they went in, and the line's shape since, is a thing you can have an
        // opinion about. Drawn as nothing when the pool is too young, never as a
        // flat line, which would read as a price that did not move.
        if (series.size > 2) {
            val from = series.first().at
            val to = series.last().at
            val span = (to - from).coerceAtLeast(1L).toFloat()
            // Anything after the last candle sits at the right edge: the last
            // candle opens at the top of the hour, so a purchase from ten minutes
            // ago is newer than it and would otherwise vanish from the line.
            val marks = moves
                .filter { it.at >= from }
                .map { SparkMark(((it.at - from) / span).coerceIn(0f, 1f), it.sell) }
            Box(Modifier.fillMaxWidth().height(if (marks.isEmpty()) 58.dp else 72.dp)) {
                Spark(series.map { it.close }, if ((px?.change24h ?: 0.0) >= 0) Halo.mint else Halo.red, marks = marks)
            }
            // How far back this is. Without it the line has no width: the same
            // shape means one thing over five hours and another over three
            // months, and the rings sitting on it mean nothing at all until you
            // know which. Three marks, because two look like a caption and four
            // start to crowd a line this small.
            Row(Modifier.fillMaxWidth()) {
                listOf(from, (from + to) / 2, to).forEachIndexed { i, t ->
                    Text(
                        if (i == 2) stringResource(R.string.chart_now) else stringResource(R.string.chart_ago, ago(t)),
                        fontFamily = Mono, fontSize = 9.5.sp, color = Halo.muted, style = Tabular,
                        modifier = Modifier.weight(1f),
                        textAlign = if (i == 0) TextAlign.Start else if (i == 1) TextAlign.Center else TextAlign.End,
                    )
                }
            }
            // What the price has done since they went in. Read off the chart, so
            // it is the coin's move over that stretch and not a claim about the
            // money they made, which nobody can see from here.
            moves.firstOrNull { !it.sell && it.at >= from }?.let { entry ->
                val i = (((entry.at - from) / span) * (series.size - 1)).toInt().coerceIn(0, series.lastIndex)
                val then = series[i].close
                val nowPx = series.last().close
                if (then > 0) {
                    val move = (nowPx - then) / then * 100
                    Text(
                        stringResource(R.string.feed_since, String.format(java.util.Locale.ROOT, "%+.1f%%", move), ago(entry.at)),
                        style = HaloType.small, lineHeight = 16.sp,
                        color = if (move >= 0) Halo.mint else Halo.red,
                    )
                }
            }
        }

        // No chart at all, said rather than left blank.
        //
        // A coin nobody has made a pool for, or one made an hour ago, has no
        // price history anywhere, and the panel simply had a hole where the
        // picture goes. A hole reads as a thing that failed to load, and people
        // tap it again.
        if (series.size <= 2) {
            Text(stringResource(R.string.feed_no_chart), style = HaloType.small, color = Halo.muted, lineHeight = 16.sp)
        }

        if (sell) {
            // The fact, and nothing after it.
            //
            // It used to carry a sentence about somebody walking away and looking
            // before you follow them in. True once. Printed under every single
            // sale in the list it became wallpaper, and advice that repeats
            // itself word for word stops being advice. What this panel says about
            // a sale is already in what it does not offer: there is no buy button
            // under it.
            Text(
                stringResource(R.string.feed_they_sold, fmtSol((solSpent * 1e9).toLong(), 3)),
                style = HaloType.small, color = Halo.amber, lineHeight = 16.sp,
            )
            return@Column
        }
        if (owner == null || signer == null || decimals == null) {
            Text(stringResource(R.string.feed_cannot_quote), style = HaloType.small, color = Halo.muted, lineHeight = 16.sp)
            return@Column
        }
        BuyBody(mint, symbol, decimals!!, owner, signer, balance, onDone)
    }
}

/** Three slices and a receipt. Kept apart so the panel above stays readable. */
@Composable
private fun BuyBody(
    mint: String,
    symbol: String,
    decimals: Int,
    owner: String,
    signer: SeedVaultSigner,
    balance: Long?,
    onDone: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The reserve the swap sheet keeps for the temporary wSOL account plus the
    // fee. Offering a slice that cannot be signed is worse than offering fewer.
    val reserve = 3_000_000L
    val slices = listOf(50_000_000L, 100_000_000L, 250_000_000L)
    var chosen by remember(mint) { mutableStateOf<Long?>(null) }
    var built by remember(mint) { mutableStateOf<SwapBuilt?>(null) }
    var working by remember(mint) { mutableStateOf(false) }
    var error by remember(mint) { mutableStateOf<String?>(null) }
    var done by remember(mint) { mutableStateOf<String?>(null) }

    suspend fun build(lamports: Long) {
        working = true; error = null
        val b = runCatching {
            SwapBuild.build(
                ctx, owner,
                SwapBuild.Side(Jupiter.SOL_MINT, "SOL", 9),
                SwapBuild.Side(mint, symbol, decimals),
                lamports,
            )
        }.getOrNull()
        built = b
        if (b == null) error = ctx.getString(R.string.swap_build_failed)
        working = false
    }

    // The price keeps moving while you look at it, and so does the blockhash
    // inside the transaction. Same fifteen seconds as the swap sheet, and the
    // same rule: a fresher price on the same route is swapped in, a different
    // route is not, because that would change the thing you are reading.
    LaunchedEffect(built) {
        val b = built ?: return@LaunchedEffect
        val lamports = chosen ?: return@LaunchedEffect
        delay(15_000)
        val next = runCatching {
            SwapBuild.build(ctx, owner, SwapBuild.Side(Jupiter.SOL_MINT, "SOL", 9), SwapBuild.Side(mint, symbol, decimals), lamports)
        }.getOrNull() ?: return@LaunchedEffect
        if (built === b && next.sameShape(b)) built = next
    }

    if (done != null) {
        Text(stringResource(R.string.feed_bought, symbol), style = HaloType.small, color = Halo.mint, lineHeight = 16.sp)
        return
    }

    Text(
        stringResource(R.string.feed_pick, fmtSol(balance ?: 0L, 3)),
        style = HaloType.small, color = Halo.muted,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        slices.forEach { lam ->
            val afford = balance == null || balance >= lam + reserve
            ModeChip(
                fmtSol(lam, 2) + " SOL", chosen == lam,
                if (afford) Halo.mint else Halo.stroke, Modifier.weight(1f),
            ) {
                if (!afford) return@ModeChip
                Haptics.tick(ctx); chosen = lam; scope.launch { build(lam) }
            }
        }
    }
    var review by remember { mutableStateOf(false) }
    if (working) Text(stringResource(R.string.analyzing), style = HaloType.small, color = Halo.muted)
    error?.let { Text(it, style = HaloType.small, color = Halo.red, lineHeight = 16.sp) }

    built?.let { b ->
        // Three lines, not the whole signing sheet.
        //
        // The full receipt is right where it is right: on a screen that exists
        // only to be read before a signature. Dropped into a feed row it buried
        // the one thing the row is for under distributions, addresses and risk
        // cards. What is out, what is in, what it costs, and anything the engine
        // actually flagged. Everything else is one tap away and stays there.
        Column(
            Modifier.fillMaxWidth().clip(rs(12)).background(Halo.cardSoft).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            ReceiptLine(stringResource(R.string.feed_out), "−" + b.pair.outUi + " " + b.pair.outSymbol, Halo.ink)
            ReceiptLine(stringResource(R.string.feed_in), "+" + b.outUi + " " + b.outSymbol, Halo.mint)
            b.analyzed.receipt.feeLamports.takeIf { it > 0 }?.let {
                ReceiptLine(stringResource(R.string.feed_fee), fmtSol(it, 5) + " SOL", Halo.muted)
            }
            val risks = b.analyzed.receipt.risks.filter { it.severity != com.clearsign.core.Severity.INFO }
            if (risks.isEmpty()) {
                ReceiptLine(stringResource(R.string.feed_risk), stringResource(R.string.feed_risk_none), Halo.mint)
            } else {
                risks.take(2).forEach { r ->
                    Text(
                        r.detail ?: r.flag.name,
                        style = HaloType.small, lineHeight = 16.sp,
                        color = if (r.severity == com.clearsign.core.Severity.DANGER) Halo.red else Halo.amber,
                    )
                }
            }
        }
        // The cut, said before the signature and not after. Jupiter Ultra adds it
        // to the route when a referral account exists, so it is already inside the
        // numbers above; this line is so nobody has to work that out.
        if (BuildConfig.JUP_REFERRAL.isNotBlank()) {
            Text(
                stringResource(R.string.feed_fee_note, String.format(java.util.Locale.ROOT, "%.2f", JupiterUltra.REFERRAL_FEE_BPS / 100.0)),
                style = HaloType.small, color = Halo.muted, lineHeight = 16.sp,
            )
        }
        if (b.analyzed.receipt.blocksApproval) {
            Text(stringResource(R.string.feed_blocked), style = HaloType.small, color = Halo.red, lineHeight = 16.sp)
        } else {
            // Nothing is signed from inside a row any more.
            //
            // The three lines above are the menu: the dish and the price, enough
            // to decide whether to go on. What you sign is a different thing, and
            // in this app it has a screen of its own, every time. It used to be
            // signed straight from here off a four-line summary, which is the one
            // place in the whole product where the receipt was optional.
            PrimaryButton(stringResource(R.string.gift_see_receipt), danger = false, enabled = !working, icon = HIcon.RECEIPT) {
                review = true
            }
        }
    }

    // The receipt, on its own, over everything: same window as everywhere else.
    val ready = built
    if (review && ready != null) {
        PayOverlay(
            title = symbol,
            hint = stringResource(R.string.env_review_hint_swap),
            onBack = { if (!working) review = false },
        ) {
            Column { SignReceiptBody(ready.analyzed.receipt, null, ready.pair, plain = true) }
            error?.let { Banner(it, Halo.red, HIcon.WARNING) }
            if (working) {
                Working(stringResource(R.string.theme_unlock_signing))
            } else {
                HoldToConfirm(stringResource(R.string.hold_sign_send)) {
                    scope.launch {
                        working = true
                        val r = WalletActions.signAndSendRaw(
                            ctx, signer, owner, ready.tx, ready.analyzed.receipt, kind = "swap",
                            ultraRequestId = ready.ultraRequestId,
                        )
                        working = false
                        when (r) {
                            is WalletActions.Result.Sent -> { done = r.signature; review = false; onDone() }
                            is WalletActions.Result.Failed -> error = r.message
                        }
                    }
                }
            }
        }
    }
}

private fun fmtPriceUsd(v: Double): String = when {
    v >= 1 -> "$" + java.text.DecimalFormat("#,##0.00").format(v)
    v >= 0.01 -> "$" + java.text.DecimalFormat("0.0000").format(v)
    else -> "$" + java.text.DecimalFormat("0.00000000").format(v)
}

/** One line of the short receipt: what it is on the left, the number on the right. */
@Composable
private fun ReceiptLine(label: String, value: String, tint: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = HaloType.small, color = Halo.muted, modifier = Modifier.weight(1f))
        Text(value, fontFamily = Mono, fontSize = 12.sp, color = tint, style = Tabular)
    }
}

/** "3h ago", short, for the line under the chart. */
private fun ago(at: Long): String {
    val m = ((System.currentTimeMillis() - at) / 60_000L).coerceAtLeast(0)
    return when {
        m < 60 -> m.toString() + "m"
        m < 1440 -> (m / 60).toString() + "h"
        else -> (m / 1440).toString() + "d"
    }
}
