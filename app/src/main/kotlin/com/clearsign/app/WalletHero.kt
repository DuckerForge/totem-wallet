@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.clearsign.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.drawscope.clipRect
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
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
    /** Send this coin, or swap out of it: the sheet for one holding asks for both. */
    onSendCoin: (String) -> Unit = {},
    onSwapCoin: (String) -> Unit = {},
) {
    val currency by Settings.currency
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var refreshKey by remember { mutableStateOf(0) }
    // Starts from what we already knew, not from nothing: see Portfolio.cached.
    val pv by produceState<PortfolioView?>(Portfolio.cached(ctx, owner, currency), owner, currency, refreshKey, reload) {
        val fresh = owner?.let { runCatching { Portfolio.load(ctx, it, currency) }.getOrNull() }
        // A failed refresh keeps the last good view rather than blanking the page.
        if (fresh != null) value = fresh
        onLoaded()
    }
    LaunchedEffect(pv) { onTotal(pv?.let { fmtFiat(it.total, it.currency) }) }
    var curve by remember { mutableStateOf<List<Double>>(emptyList()) }
    LaunchedEffect(owner, pv?.total) {
        val o = owner
        val v = pv
        curve = if (o == null || v == null) emptyList()
        else runCatching { BalanceCurve.of(ctx, o, v) }.getOrDefault(emptyList())
    }
    var picked by remember { mutableStateOf<Holding?>(null) }
    picked?.let { h ->
        if (owner != null) TokenSheet(
            h, owner, signer, currency,
            onSend = { picked = null; onSendCoin(h.mint) },
            onSwap = { picked = null; onSwapCoin(h.mint) },
            onDismiss = { changed -> picked = null; if (changed) refreshKey++ },
        )
    }

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
            // A long press covers everything for a guest; a tap on the covered
            // number asks the print to bring it back.
            val guest by Settings.guest
            val ctx = LocalContext.current
            val scope = rememberCoroutineScope()
            Box(
                Modifier.combinedClickable(
                    onClick = {
                        if (guest) scope.launch {
                            val act = ctx as? android.app.Activity ?: return@launch
                            if (Presence.confirm(act, ctx.getString(R.string.guest_exit_title), ctx.getString(R.string.guest_exit_sub))) { Settings.guest.value = false; Haptics.success(ctx) }
                        }
                    },
                    onLongClick = { if (!guest) { Settings.guest.value = true; Haptics.success(ctx) } },
                ),
            ) {
                if (guest) Text("••••", style = HaloType.amount.copy(fontSize = 44.sp, lineHeight = 50.sp), color = Halo.muted)
                else pv?.let { BigTotal(it.total, it.currency) } ?: Text("…", style = HaloType.amount, color = Halo.ink)
            }
            if (guest) Text(stringResource(R.string.guest_on), style = HaloType.small, color = Halo.amber)
            pv?.let { v ->
                val d = v.change24hValue
                val p = v.change24hPct
                if (d != null && p != null) ChangePill(d, p, v.currency, Modifier.clickable(onClick = onPnl))
            }
        }

        // The shape of the month, under the buttons rather than beside them.
        //
        // A chart of your own money is the thing people open a wallet to look at,
        // and it had nowhere to go: the balance says today and the pill says the
        // last day, and between them there was no picture at all. Put in a card
        // of its own it would have pushed the holdings further down the page for
        // something nobody needs to read closely. Here it costs no height, and
        // what it is for is a glance: up or down, steady or jagged. See
        // BalanceCurve for exactly what it is a chart of, because it is not the
        // obvious thing.
        Box(Modifier.fillMaxWidth()) {
            if (curve.size >= 8) BalanceSpark(curve, Modifier.matchParentSize())
            HomeActions(enabled = owner != null, onAction = onAction)
        }

        pv?.let { view ->
            val main = view.main.filter { it.raw > 0 }
            val others = view.others.filter { it.raw > 0 }
            if (main.isNotEmpty() || others.isNotEmpty()) {
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                        var showAll by remember(view) { mutableStateOf(false) }
                        var showOthers by remember(view) { mutableStateOf(false) }
                        val hctx = LocalContext.current
                        // The whole card rolls up.
                        //
                        // Open, this is the tallest thing on the screen: twelve
                        // coins, the ones nobody prices, thirty-three odds and
                        // ends, and the DeFi under all of it. Somebody who knows
                        // what they hold scrolls past the lot every time to reach
                        // the rest of the page. Closed, it is one line that still
                        // says how much is in there, and it stays closed tomorrow,
                        // because a drawer that reopens itself is not a drawer.
                        val open by Settings.walletOpen
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                Settings.setWalletOpen(hctx, !open); Haptics.tick(hctx)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.hero_portfolio), style = HaloType.label, color = Halo.muted)
                            Spacer(Modifier.weight(1f))
                            if (!open) {
                                val n = main.size + others.size
                                Text(
                                    if (view.defi.isEmpty()) stringResource(R.string.hero_closed, n)
                                    else stringResource(R.string.hero_closed_defi, n, view.defi.size),
                                    style = HaloType.label, color = Halo.muted,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            HaloIcon(if (open) HIcon.CHEVRON_DOWN else HIcon.CHEVRON_RIGHT, Halo.muted, 14.dp)
                        }
                        if (!open) return@Column
                        (if (showAll || main.size <= MAX_COLLAPSED) main else main.take(MAX_COLLAPSED)).forEach { h -> HoldingRow(h, currency) { picked = h } }
                        if (main.size > MAX_COLLAPSED) {
                            LinkRow(if (showAll) stringResource(R.string.hero_show_less) else stringResource(R.string.hero_show_all, main.size)) { showAll = !showAll }
                        }
                        // Everything past the first few coins lives behind "show all".
                        //
                        // The odds and ends and the DeFi used to sit below the fold
                        // whatever you did, so the card was tall even when it was
                        // "collapsed" and the interesting part, the coins, was the
                        // smallest thing in it. One link now decides the whole
                        // depth of the card: the coins you actually watch, or all
                        // of it.
                        if (showAll || main.size <= MAX_COLLAPSED) {
                            if (view.unpriced > 0) Text(stringResource(R.string.hero_some_unpriced), style = HaloType.label, color = Halo.muted)
                            if (others.isNotEmpty()) {
                                LinkRow(if (showOthers) stringResource(R.string.hero_others_hide) else stringResource(R.string.hero_others, others.size)) { showOthers = !showOthers }
                                if (showOthers) others.forEach { h -> HoldingRow(h, currency) { picked = h } }
                            }
                            if (view.defi.isNotEmpty()) {
                                Spacer(Modifier.height(Space.xs))
                                Text(stringResource(R.string.hero_defi).uppercase(), style = HaloType.label, color = Halo.muted)
                                view.defi.forEach { d -> DefiRow(d) }
                            }
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
                    append((if (Settings.guest.value) "••••" else fmtUi(h.ui)) + " " + h.symbol)
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
private fun TokenSheet(
    h: Holding,
    owner: String,
    signer: SeedVaultSigner,
    currency: String,
    onSend: () -> Unit,
    onSwap: () -> Unit,
    onDismiss: (Boolean) -> Unit,
) {
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
            // The two things you actually want to do with a coin you hold, and
            // for a long time the one place they were missing. Tapping a holding
            // opened a card that could copy its address, show it on an explorer
            // and destroy it, and to send or sell it you had to back out and
            // start again from the home actions, picking the same coin a second
            // time from a list. Both open with this coin already chosen.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(stringResource(R.string.send_btn), Modifier.weight(1f), HIcon.SEND, tint = Halo.mint) { onSend() }
                GhostButton(stringResource(R.string.swap_btn), Modifier.weight(1f), HIcon.SWAP, tint = Halo.cyan) { onSwap() }
            }
            // The rest of what this card can do, two to a row, whatever survives.
            //
            // They used to be written as fixed rows, and each row hid its own
            // buttons: no mint to copy when the coin is SOL, no take profit on
            // SOL either, nothing at all for a coin nobody prices. On SOL that
            // left one button on one row and one button on the next, each
            // stretched the full width, stacked, which looks like a mistake
            // rather than a card with fewer things to offer. A list that lays
            // itself out in pairs cannot get that wrong: it is balanced for
            // every coin, and it stays balanced when a button appears late,
            // which the two price-driven ones do.
            var priceUsd by remember(h.mint) { mutableStateOf<Double?>(null) }
            LaunchedEffect(h.mint) { priceUsd = withContext(Dispatchers.IO) { runCatching { Prices.usd(listOf(h.mint))[h.mint] }.getOrNull() } }
            var tp by remember { mutableStateOf(false) }
            var alert by remember { mutableStateOf(false) }
            val coin = OrderCoin(h.mint, h.symbol, h.decimals, h.image, priceUsd, h.raw)
            val orderable = !h.isNft && h.raw > 0 && priceUsd != null

            val actions = buildList<TokenAction> {
                // Native SOL's mint field is the string "SOL", a placeholder this
                // app uses internally. There is nothing to copy, and handing it to
                // an explorer produced a page saying the address is invalid, so the
                // explorer goes to the wallet instead: the page that exists.
                if (!isSol) add(
                    TokenAction(stringResource(R.string.copy), HIcon.COPY, Halo.muted) {
                        (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("mint", h.mint))
                    },
                )
                add(
                    TokenAction("Solscan", HIcon.EXTERNAL, Halo.cyan) {
                        val target = if (isSol) owner else h.mint
                        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(solscanUrl(target, null)))) }
                    },
                )
                // The take profit puts an order on the chain to sell the coin, and
                // SOL is what everything else is sold *into*, so it has none. Being
                // told when something moves has no such problem, and "tell me when
                // SOL moves" is probably the most wanted alert in the app.
                if (orderable && !isSol) add(TokenAction(stringResource(R.string.order_tp_short), HIcon.HOURGLASS, Halo.mint) { tp = true })
                if (orderable) add(TokenAction(stringResource(R.string.order_alert), HIcon.WARNING, Halo.amber) { alert = true })
            }
            actions.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { a -> GhostButton(a.label, Modifier.weight(1f), a.icon, tint = a.tint, onClick = a.onClick) }
                }
            }
            if (tp) TakeProfitSheet(coin, signer, owner, onDone = { tp = false; onDismiss(true) }) { tp = false }
            if (alert) AlertSheet(coin, onDone = { alert = false }) { alert = false }
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

/**
 * One thing that is yours outside the token list: what, where, and whether it is live.
 *
 * It used to carry the money too: what the position is worth, how many coins are
 * in it, what it pays a day and at what rate. Those numbers came out of an API
 * that only sees its own platforms and an APR that is an estimate, and four
 * estimates stacked in one corner read as a statement. A number that precise
 * about somebody's money has to be right or absent, and this one could not be
 * made right from here. So the row says what is true and stops: this is yours,
 * it is over there, and it is working.
 */
@Composable
private fun DefiRow(d: DefiPosition) {
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
    }
}

/** One of the smaller things a holding's card can do. */
private class TokenAction(val label: String, val icon: HIcon, val tint: androidx.compose.ui.graphics.Color, val onClick: () -> Unit)

/**
 * The curve itself: an area under a line, faint enough to read the buttons through.
 *
 * Green when the month ends higher than it started, red when it does not, and
 * that is the only thing it says. No axis, no grid, no numbers: a figure printed
 * here would be a figure nobody asked for, floating behind something they did
 * ask for, and the two would fight.
 */
@Composable
private fun BalanceSpark(values: List<Double>, modifier: Modifier) {
    val tint = if (values.last() >= values.first()) Halo.mint else Halo.red
    // It draws itself, left to right, the way the month happened.
    //
    // Appearing all at once it read as a background texture that had always been
    // there, and a month of your own money is worth one second of attention. A
    // line that arrives from the left is also the only hint on this screen that
    // the left edge is the past: there is no axis to say so, and there should
    // not be one. Once per curve, not on every recomposition — a shape that
    // keeps redrawing itself would say the numbers were still changing.
    //
    // Two seconds and a steady speed, not the app's usual reveal. That one eases
    // out, so the head bolts across and then crawls the last tenth, which on a
    // line reads as a stutter rather than a trace. A pen moves at the speed it
    // moves.
    val growth = remember(values) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(values) {
        growth.animateTo(1f, androidx.compose.animation.core.tween(2100, easing = androidx.compose.animation.core.LinearEasing))
    }
    val grow = growth.value
    androidx.compose.foundation.Canvas(modifier) {
        val lo = values.min()
        val hi = values.max()
        val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
        // Kept off the floor and the ceiling: a line that touches either edge
        // reads as clipped, as if the real shape carried on outside the box.
        val top = size.height * 0.16f
        val usable = size.height * 0.66f
        fun px(i: Int) = size.width * i / (values.size - 1).toFloat()
        fun py(v: Double) = top + usable - ((v - lo) / span).toFloat() * usable
        val line = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, py(values[0]))
            for (i in 1 until values.size) lineTo(px(i), py(values[i]))
        }
        val area = androidx.compose.ui.graphics.Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        clipRect(right = size.width * grow) {
            drawPath(
                area,
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(tint.copy(alpha = 0.11f), tint.copy(alpha = 0f)),
                    startY = top, endY = size.height,
                ),
            )
            drawPath(line, tint.copy(alpha = 0.24f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6f))
        }
        // The head of the line while it travels, so the eye has something to
        // follow. It stops existing the moment the curve is whole.
        if (grow < 1f) {
            val i = ((values.size - 1) * grow).toInt().coerceIn(0, values.size - 1)
            val head = androidx.compose.ui.geometry.Offset(size.width * grow, py(values[i]))
            drawCircle(tint.copy(alpha = 0.16f), 7f, head)
            drawCircle(tint.copy(alpha = 0.55f), 2.2f, head)
        }
    }
}
