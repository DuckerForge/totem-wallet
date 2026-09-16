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
    solSpent: Double,
    sell: Boolean,
    owner: String?,
    signer: SeedVaultSigner?,
    onDone: () -> Unit,
) {
    val ctx = LocalContext.current
    var px by remember(mint) { mutableStateOf<Prices.Px?>(null) }
    var series by remember(mint) { mutableStateOf<List<Double>>(emptyList()) }
    var decimals by remember(mint) { mutableStateOf<Int?>(null) }
    var balance by remember(owner) { mutableStateOf<Long?>(null) }

    LaunchedEffect(mint) {
        withContext(Dispatchers.IO) {
            px = runCatching { Prices.quotes(listOf(mint))[mint] }.getOrNull()
            decimals = runCatching { JupiterTokens.byMints(listOf(mint))[mint]?.decimals }.getOrNull()
            series = runCatching { Gecko.series(mint, Gecko.Span.HOURS) }.getOrDefault(emptyList())
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

        // Two days of hourly closes. Drawn as nothing when the pool is too young,
        // never as a flat line, which would read as a price that did not move.
        if (series.size > 2) {
            Box(Modifier.fillMaxWidth().height(58.dp)) {
                Spark(series, if ((px?.change24h ?: 0.0) >= 0) Halo.mint else Halo.red)
            }
        }

        if (sell) {
            // Offering to buy what somebody is walking away from would be the
            // opposite of what this feed is for.
            Text(
                stringResource(R.string.feed_they_sold, fmtSol((solSpent * 1e9).toLong(), 3)) + " " +
                    stringResource(R.string.feed_sell_note),
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
            HoldToConfirm(stringResource(R.string.hold_sign_send), enabled = !working) {
                scope.launch {
                    working = true
                    val r = WalletActions.signAndSendRaw(
                        ctx, signer, owner, b.tx, b.analyzed.receipt, kind = "swap",
                        ultraRequestId = b.ultraRequestId,
                    )
                    working = false
                    when (r) {
                        is WalletActions.Result.Sent -> { done = r.signature; onDone() }
                        is WalletActions.Result.Failed -> error = r.message
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
