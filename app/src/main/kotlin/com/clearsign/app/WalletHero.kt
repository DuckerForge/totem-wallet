@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The Jupiter-style top of the wallet: total value + a valued portfolio list. */
@Composable
internal fun WalletHero(
    owner: String?,
    signer: SeedVaultSigner,
    /** 0 = the balance is at full size, 1 = it has gone up into the header. */
    collapse: Float,
    onAction: (HomeAction) -> Unit,
    onPnl: () -> Unit,
    /** The header takes the balance over once the big one has scrolled away. */
    onTotal: (String?) -> Unit = {},
    /** Bumped by the page when the person pulls down: reload everything. */
    reload: Int = 0,
    /** Called when a reload has finished, whatever it found. */
    onLoaded: () -> Unit = {},
) {
    val currency by Settings.currency
    var refreshKey by remember { mutableStateOf(0) }
    // Starts from what we already knew, not from nothing: see Portfolio.cached.
    val pv by produceState<PortfolioView?>(Portfolio.cached(owner, currency), owner, currency, refreshKey, reload) {
        val fresh = owner?.let { runCatching { Portfolio.load(it, currency) }.getOrNull() }
        // A failed refresh keeps the last good view rather than blanking the page.
        if (fresh != null) value = fresh
        onLoaded()
    }
    LaunchedEffect(pv) { onTotal(pv?.let { fmtFiat(it.total, it.currency) }) }
    var picked by remember { mutableStateOf<Holding?>(null) }
    picked?.let { h -> if (owner != null) TokenSheet(h, owner, signer, currency, onDismiss = { changed -> picked = null; if (changed) refreshKey++ }) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xl)) {
        // The one hero of the page, and it sits on the page rather than in a card:
        // a box around the number would make it one panel among the others.
        Column(
            Modifier.fillMaxWidth().graphicsLayer {
                alpha = 1f - collapse
                scaleX = 1f - collapse * 0.35f
                scaleY = 1f - collapse * 0.35f
                translationY = -collapse * 40.dp.toPx()
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Text(stringResource(R.string.hero_total), style = HaloType.label, color = Halo.muted)
            pv?.let { BigTotal(it.total, it.currency) } ?: Text("…", style = HaloType.amount, color = Halo.ink)
            pv?.let { v ->
                val d = v.change24hValue
                val p = v.change24hPct
                if (d != null && p != null) ChangePill(d, p, v.currency, Modifier.clickable(onClick = onPnl))
            }
        }

        HomeActions(enabled = owner != null, onAction = onAction)

        pv?.let { view ->
            val main = view.main.filter { it.raw > 0 }
            val others = view.others.filter { it.raw > 0 }
            if (main.isNotEmpty() || others.isNotEmpty()) {
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                        var showAll by remember(view) { mutableStateOf(false) }
                        var showOthers by remember(view) { mutableStateOf(false) }
                        Text(stringResource(R.string.hero_portfolio), style = HaloType.label, color = Halo.muted)
                        (if (showAll || main.size <= MAX_COLLAPSED) main else main.take(MAX_COLLAPSED)).forEach { h -> HoldingRow(h, currency) { picked = h } }
                        if (main.size > MAX_COLLAPSED) {
                            LinkRow(if (showAll) stringResource(R.string.hero_show_less) else stringResource(R.string.hero_show_all, main.size)) { showAll = !showAll }
                        }
                        if (view.unpriced > 0) Text(stringResource(R.string.hero_some_unpriced), style = HaloType.label, color = Halo.muted)
                        if (others.isNotEmpty()) {
                            LinkRow(if (showOthers) stringResource(R.string.hero_others_hide) else stringResource(R.string.hero_others, others.size)) { showOthers = !showOthers }
                            if (showOthers) others.forEach { h -> HoldingRow(h, currency) { picked = h } }
                        }
                        if (view.defi.isNotEmpty()) {
                            Spacer(Modifier.height(Space.xs))
                            Text(stringResource(R.string.hero_defi).uppercase(), style = HaloType.label, color = Halo.muted)
                            view.defi.forEach { d -> DefiRow(d, currency) }
                        }
                    }
                }
            }
        }
    }
}

/** Three rows is a glance; everything else hides behind "show all". */
private const val MAX_COLLAPSED = 3

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(rs(10)).clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Halo.cyan)
        Spacer(Modifier.width(4.dp))
        HaloIcon(HIcon.CHEVRON_RIGHT, Halo.cyan, 14.dp)
    }
}

/** A stable, saturated hue per mint so the list reads as a set of distinct assets. */
internal fun tokenColor(mint: String): androidx.compose.ui.graphics.Color {
    val hue = ((mint.hashCode() % 360) + 360) % 360
    return androidx.compose.ui.graphics.Color.hsl(hue.toFloat(), 0.62f, 0.60f)
}

/** Logo from the token metadata, or coloured initials while it loads / when there is none. */
@Composable
internal fun TokenLogo(mint: String, symbol: String, image: String?, size: androidx.compose.ui.unit.Dp) {
    val tint = tokenColor(mint)
    val initials: @Composable () -> Unit = {
        Box(Modifier.size(size).clip(rs(999)).background(tint.copy(alpha = 0.22f)).border(1.dp, tint.copy(alpha = 0.5f), rs(999)), contentAlignment = Alignment.Center) {
            Text(symbol.take(2).uppercase(), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp, color = tint)
        }
    }
    if (image == null) initials()
    else coil.compose.SubcomposeAsyncImage(
        model = image, contentDescription = null,
        modifier = Modifier.size(size).clip(rs(999)),
        loading = { initials() }, error = { initials() },
    )
}

@Composable
private fun HoldingRow(h: Holding, currency: String, onClick: () -> Unit) {
    val ctx = LocalContext.current
    // Follow a coin straight from the thing you already own. The star is the
    // gesture everyone knows, and it is the same list the Market tab shows.
    var starred by remember(h.mint) { mutableStateOf(Watchlist.has(ctx, h.mint)) }
    Row(
        Modifier.fillMaxWidth().clip(rs(Radius.row)).clickable(onClick = onClick).padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TokenLogo(h.mint, h.symbol, h.image, 38.dp)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(h.name?.takeIf { TokenSymbols.isKnown(h.mint) } ?: h.symbol, style = HaloType.body, color = Halo.ink, maxLines = 1)
            // How much you hold, and what one of them costs. The second half is
            // the number you go to another app to look up, and it carries the
            // day's direction in its colour the way a price always does.
            val unit = h.fiat?.takeIf { h.ui > 0 }?.let { fmtPrice(it / h.ui, currency) }
            val move = h.change24h
            Text(
                buildAnnotatedString {
                    append(fmtUi(h.ui) + " " + h.symbol)
                    if (unit != null) {
                        append(" · ")
                        withStyle(SpanStyle(color = if (move == null) Halo.muted else if (move >= 0) Halo.mint else Halo.red)) {
                            append(unit)
                        }
                    }
                },
                style = HaloType.label, color = Halo.muted, maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            // The number you came to see: it reads at body size, not as a footnote.
            Text(
                h.fiat?.let { fmtFiat(it, currency) } ?: "—",
                style = HaloType.body.copy(fontFamily = Sora, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                color = if (h.fiat != null) Halo.ink else Halo.muted,
            )
            h.change24h?.let { c -> Text(pct(c), style = HaloType.label, color = if (c >= 0) Halo.mint else Halo.red) }
        }
        Spacer(Modifier.width(2.dp))
        Box(
            Modifier.size(30.dp).clip(rs(999)).clickable {
                starred = Watchlist.toggle(ctx, h.mint)
                Haptics.tick(ctx)
            },
            contentAlignment = Alignment.Center,
        ) {
            HaloIcon(if (starred) HIcon.STAR_FILLED else HIcon.STAR, if (starred) Halo.amber else Halo.muted.copy(alpha = 0.5f), 15.dp)
        }
    }
}

private fun pct(c: Double): String = (if (c >= 0) "+" else "−") + "%.1f%%".format(kotlin.math.abs(c))

/** "+1,23 € · +2,0 % oggi": the day's move, green up / red down. */
@Composable
private fun ChangePill(delta: Double, p: Double, currency: String, modifier: Modifier = Modifier) {
    val up = delta >= 0
    val tint = if (up) Halo.mint else Halo.red
    Row(
        modifier.clip(rs(Radius.pill)).background(tint.copy(alpha = 0.12f)).padding(start = 12.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (if (up) "+" else "−") + fmtFiat(kotlin.math.abs(delta), currency) + "  ·  " + pct(p) + "  " + stringResource(R.string.hero_today),
            style = HaloType.small, color = tint,
        )
        // The chevron is the promise that there is something behind the number.
        HaloIcon(HIcon.CHEVRON_RIGHT, tint, 15.dp)
    }
}

/**
 * One token, tapped from the portfolio: what it is, what it's worth, and — for
 * anything that isn't SOL — "burn and reclaim the rent": every unit is destroyed
 * and the token account closed, so its ~0.002 SOL deposit comes back. Simulated
 * before the biometric prompt; the Seed Vault signs; the burn lands in the ledger.
 */
@Composable
private fun TokenSheet(h: Holding, owner: String, signer: SeedVaultSigner, currency: String, onDismiss: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var accounts by remember { mutableStateOf<List<SolanaRpc.TokenAccountInfo>?>(null) }
    var burn by remember { mutableStateOf<HygieneAction.Burn?>(null) }
    val isSol = h.mint == com.clearsign.core.NATIVE_SOL_MINT
    LaunchedEffect(h.mint) {
        if (isSol) return@LaunchedEffect
        accounts = withContext(Dispatchers.IO) {
            runCatching { SolanaRpc.tokenAccountsOf(SolanaRpc.urlFor(null), owner).filter { it.mint == h.mint } }.getOrDefault(emptyList())
        }
    }
    burn?.let { b -> HygieneSheet(b, signer, owner) { done -> burn = null; if (done) onDismiss(true) } }
    ModalBottomSheet(onDismissRequest = { onDismiss(false) }, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TokenLogo(h.mint, h.symbol, h.image, 46.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(h.name?.takeIf { TokenSymbols.isKnown(h.mint) } ?: h.symbol, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.ink, maxLines = 1)
                    Text(h.symbol + (if (h.isNft) "  ·  NFT" else ""), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                }
            }
            Column(Modifier.fillMaxWidth().clip(rs(16)).background(Halo.cardSoft).border(cardBorder(), rs(16)).padding(14.dp)) {
                StatRow(stringResource(R.string.token_amount), fmtUi(h.ui) + " " + h.symbol)
                StatRow(stringResource(R.string.token_value), h.fiat?.let { fmtFiat(it, currency) } ?: stringResource(R.string.burn_value_none), accent = h.fiat != null)
                if (!isSol) {
                    StatRow(stringResource(R.string.token_mint), shorten(h.mint, 6))
                    accounts?.let { StatRow(stringResource(R.string.token_rent), "+" + fmtSol(it.sumOf { a -> a.lamports }, 5) + " SOL") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(stringResource(R.string.copy), Modifier.weight(1f), HIcon.COPY) {
                    (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("mint", h.mint))
                }
                GhostButton("Solscan", Modifier.weight(1f), HIcon.EXTERNAL, tint = Halo.cyan) {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(solscanUrl(h.mint, null)))) }
                }
            }
            // Sell it in profit without watching it, or be told when it moves.
            // Both need a price; a coin nobody quotes gets neither button.
            if (!isSol && !h.isNft && h.raw > 0) {
                var priceUsd by remember(h.mint) { mutableStateOf<Double?>(null) }
                LaunchedEffect(h.mint) { priceUsd = withContext(Dispatchers.IO) { runCatching { Prices.usd(listOf(h.mint))[h.mint] }.getOrNull() } }
                var tp by remember { mutableStateOf(false) }
                var alert by remember { mutableStateOf(false) }
                val coin = OrderCoin(h.mint, h.symbol, h.decimals, h.image, priceUsd, h.raw)
                if (priceUsd != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton(stringResource(R.string.order_tp_short), Modifier.weight(1f), HIcon.HOURGLASS, tint = Halo.mint) { tp = true }
                        GhostButton(stringResource(R.string.order_alert), Modifier.weight(1f), HIcon.WARNING, tint = Halo.amber) { alert = true }
                    }
                }
                if (tp) TakeProfitSheet(coin, signer, owner, onDone = { tp = false; onDismiss(true) }) { tp = false }
                if (alert) AlertSheet(coin, onDone = { alert = false }) { alert = false }
            }
            if (!isSol) {
                Text(stringResource(R.string.token_burn_note), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                PrimaryButton(stringResource(R.string.token_burn_btn), danger = true, enabled = !accounts.isNullOrEmpty(), icon = HIcon.TRASH) {
                    accounts?.takeIf { it.isNotEmpty() }?.let { burn = HygieneAction.Burn(h, it) }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * The number the page is about. Bigger than any other text, it counts to
 * its new value instead of jumping, and a soft light sweeps across the
 * digits now and then, the way light moves on a card held in the hand.
 */
@Composable
private fun BigTotal(total: Double, currency: String) {
    val shown by androidx.compose.animation.core.animateFloatAsState(
        total.toFloat(), androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "total",
    )
    val sweep by androidx.compose.animation.core.rememberInfiniteTransition(label = "sweep").animateFloat(
        -1f, 2f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(3600, delayMillis = 1400, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "sweepX",
    )
    val ink = Halo.ink
    val lit = Halo.mint
    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
        // The glow behind: the accent, very faint, so the number sits in light.
        Text(
            fmtFiat(shown.toDouble(), currency),
            style = HaloType.amount.copy(fontSize = 44.sp, lineHeight = 50.sp, color = lit.copy(alpha = 0.18f)),
            modifier = Modifier.graphicsLayer { renderEffect = android.graphics.RenderEffect.createBlurEffect(18f, 18f, android.graphics.Shader.TileMode.DECAL).asComposeRenderEffect() },
        )
        Text(
            fmtFiat(shown.toDouble(), currency),
            style = HaloType.amount.copy(
                fontSize = 44.sp, lineHeight = 50.sp,
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    0f to ink, (sweep - 0.25f).coerceIn(0f, 1f) to ink, sweep.coerceIn(0f, 1f) to lit, (sweep + 0.25f).coerceIn(0f, 1f) to ink, 1f to ink,
                ),
            ),
        )
    }
}

/** One thing that is yours outside the token list: what, where, how much, and whether it is live. */
@Composable
private fun DefiRow(d: DefiPosition, currency: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TokenLogo(d.symbol, d.symbol, d.image, 34.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (d.kind == DefiPosition.Kind.STAKE) R.string.hero_defi_stake else R.string.hero_defi_lend, d.symbol),
                fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.ink, maxLines = 1,
            )
            Text(
                d.sub + (d.state?.let { st ->
                    " · " + stringResource(
                        when (st) { "active" -> R.string.hero_stake_active; "activating" -> R.string.hero_stake_activating; "deactivating" -> R.string.hero_stake_deactivating; else -> R.string.hero_stake_inactive },
                    )
                } ?: ""),
                fontFamily = Inter, fontSize = 11.sp, color = if (d.state == "active" || d.state == null) Halo.mint else Halo.amber, maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(d.fiat?.let { fmtFiat(it, currency) } ?: "…", fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = Halo.ink)
            Text(fmtUi(d.ui) + " " + d.symbol, fontFamily = Mono, fontSize = 11.sp, color = Halo.muted)
        }
    }
}
