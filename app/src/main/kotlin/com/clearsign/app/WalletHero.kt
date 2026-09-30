@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.clearsign.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.draw.drawWithContent
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
    collapse: androidx.compose.runtime.State<Float>,
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
    // Seeded from Portfolio.cached so the page never starts empty.
    val pv by produceState<PortfolioView?>(Portfolio.cached(ctx, owner, currency), owner, currency, refreshKey, reload) {
        val fresh = owner?.let { runCatching { Portfolio.load(ctx, it, currency) }.getOrNull() }
        // A failed refresh keeps the last good view rather than blanking the page.
        if (fresh != null) value = fresh
        onLoaded()
    }
    LaunchedEffect(pv) { onTotal(pv?.let { fmtFiat(it.total, it.currency) }) }
    var curve by remember { mutableStateOf<List<Double>>(emptyList()) }
    // Coins the curve is built from, biggest first; the line drops one of each as it passes.
    // Six at most, as many as the curve looks up.
    val curveCoins = remember(pv) {
        pv?.let { v ->
            val byMint = HashMap<String, Double>()
            v.holdings.filter { !it.isNft && it.raw > 0 && (it.fiat ?: 0.0) > 0.0 }
                .forEach { byMint[it.mint] = (byMint[it.mint] ?: 0.0) + (it.fiat ?: 0.0) }
            byMint.entries.sortedByDescending { it.value }.take(6).map { it.key }
        }.orEmpty()
    }
    LaunchedEffect(owner, pv?.total) {
        val o = owner
        val v = pv
        curve = if (o == null || v == null) emptyList()
        else runCatching { BalanceCurve.of(ctx, o, v) }.getOrDefault(emptyList())
    }
    var oreOpen by remember { mutableStateOf(false) }
    var spareOpen by remember { mutableStateOf(false) }
    if (oreOpen && owner != null) OreSheet(owner, signer, onSpare = { oreOpen = false; spareOpen = true }) { changed -> oreOpen = false; if (changed) refreshKey++ }
    if (spareOpen && owner != null) SwapSheet(signer, owner, spare = true) { spareOpen = false; refreshKey++ }
    var defiOpen by remember { mutableStateOf<DefiPosition?>(null) }
    defiOpen?.let { d -> DefiSheet(d, currency) { defiOpen = null } }
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
        // The total sits on the page, not in a card, so it doesn't read as one panel among many.
        Column(
            Modifier.fillMaxWidth().graphicsLayer {
                val c = collapse.value
                alpha = 1f - c
                scaleX = 1f - c * 0.35f
                scaleY = 1f - c * 0.35f
                translationY = -c * 40.dp.toPx()
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            // Long press hides balances (guest mode); a tap on the hidden number asks for the fingerprint.
            val guest by Settings.guest
            val ctx = LocalContext.current
            // Show the long-press hint until it has been used once.
            var hintSeen by remember { mutableStateOf(Settings.hideHintSeen(ctx)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                HaloIcon(if (guest) HIcon.LOCK else HIcon.UNLOCK, Halo.muted, 12.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.hero_total) + (if (!guest && !hintSeen) " · " + stringResource(R.string.hero_hide_hint) else ""),
                    style = HaloType.label, color = Halo.muted,
                )
            }
            val scope = rememberCoroutineScope()
            Box(
                Modifier.combinedClickable(
                    onClick = {
                        if (guest) scope.launch {
                            val act = ctx as? android.app.Activity ?: return@launch
                            if (Presence.confirm(act, ctx.getString(R.string.guest_exit_title), ctx.getString(R.string.guest_exit_sub))) { Settings.guest.value = false; Haptics.success(ctx) }
                        }
                    },
                    onLongClick = { if (!guest) { Settings.guest.value = true; Haptics.success(ctx); Settings.setHideHintSeen(ctx); hintSeen = true } },
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

        // Month curve behind the action buttons: a card of its own would push the holdings down
        // for a glance-only chart. See BalanceCurve for what it plots, which is not the obvious thing.
        Box(Modifier.fillMaxWidth()) {
            // A tap opens the same P&L as the pill above. HomeActions is declared after it,
            // so the buttons get their own taps first.
            if (curve.size >= 8) BalanceSpark(curve, curveCoins, Modifier.matchParentSize().clickable(onClick = onPnl))
            HomeActions(enabled = owner != null, onAction = onAction)
        }

        pv?.let { view ->
            val main = view.main.filter { it.raw > 0 }
            val others = view.others.filter { it.raw > 0 }
            // Empty wallet: say so and offer Receive instead of hiding the card.
            if (main.isEmpty() && others.isEmpty() && view.defi.isEmpty()) {
                GlassCard {
                    EmptyState(HIcon.WALLET, stringResource(R.string.hero_empty_title), stringResource(R.string.hero_empty_body), stringResource(R.string.receive_btn) to { onAction(HomeAction.RECEIVE) })
                }
            }
            if (main.isNotEmpty() || others.isNotEmpty()) {
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                        var showAll by remember(view) { mutableStateOf(false) }
                        var showOthers by remember(view) { mutableStateOf(false) }
                        val hctx = LocalContext.current
                        // The card collapses to one line with the count. Open, it is the tallest thing
                        // on screen and people scroll past it. The state is persisted.
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
                        // DeFi stays visible under the main coins, not behind "show all": few rows, real money.
                        if (view.defi.isNotEmpty()) {
                            Spacer(Modifier.height(Space.xs))
                            Text(stringResource(R.string.hero_defi).uppercase(), style = HaloType.label, color = Halo.muted)
                            // Tiles in a row, one per position: compact, scrolling when there are many.
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                view.defi.forEach { d -> DefiTile(d, currency) { if (d.kind == DefiPosition.Kind.ORE) oreOpen = true else defiOpen = d } }
                            }
                        }
                        if (view.defi.none { it.kind == DefiPosition.Kind.ORE }) LinkRow(stringResource(R.string.hero_ore_dig)) { oreOpen = true }
                        // Everything else, the other coins, the dust and the NFTs, sits behind
                        // "show all" at the bottom of the card.
                        if (main.size > MAX_COLLAPSED) {
                            LinkRow(if (showAll) stringResource(R.string.hero_show_less) else stringResource(R.string.hero_show_all, main.size)) { showAll = !showAll }
                        }
                        if (showAll || main.size <= MAX_COLLAPSED) {
                            if (view.unpriced > 0) Text(stringResource(R.string.hero_some_unpriced), style = HaloType.label, color = Halo.muted)
                            if (others.isNotEmpty()) {
                                LinkRow(if (showOthers) stringResource(R.string.hero_others_hide) else stringResource(R.string.hero_others, others.size)) { showOthers = !showOthers }
                                // Pages of twenty: a lazy list inside `verticalScroll` is not allowed, and
                                // thirty-three dust rows composed at once are felt while scrolling.
                                var shownOthers by remember(view) { mutableStateOf(20) }
                                if (showOthers) {
                                    others.take(shownOthers).forEach { h -> androidx.compose.runtime.key(h.mint) { HoldingRow(h, currency) { picked = h } } }
                                    if (others.size > shownOthers) LinkRow(stringResource(R.string.hero_others_more, others.size - shownOthers)) { shownOthers += 20 }
                                }
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
internal fun LinkRow(label: String, onClick: () -> Unit) {
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
    // ORE's official logo is a black and white wordmark that vanishes among colored discs,
    // so it gets an amber gem in the theme's gold.
    if (mint == com.clearsign.core.Ore.MINT) { OreMark(size); return }
    val tint = tokenColor(mint)
    val initials: @Composable () -> Unit = {
        Box(Modifier.size(size).clip(rs(999)).background(tint.copy(alpha = 0.22f)).border(1.dp, tint.copy(alpha = 0.5f), rs(999)), contentAlignment = Alignment.Center) {
            Text(symbol.take(2).uppercase(), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp, color = tint)
        }
    }
    // Initials always drawn, the image on top once loaded: one composition, nothing to swap.
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        initials()
        // Fixed logo for SOL and USDC: CoinGecko and Jupiter ("Wrapped SOL") serve different
        // images, so it changed with the source.
        val src = TokenSymbols.canonicalImage(mint) ?: image
        if (src != null) coil.compose.AsyncImage(
            model = src, contentDescription = null,
            modifier = Modifier.matchParentSize().clip(rs(999)),
        )
    }
}

/** The ORE mark: an amber disc with the gem inside, at the size of the logo it replaces. */
@Composable
internal fun OreMark(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(rs(999))
            .background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Halo.amber.copy(alpha = 0.34f), Halo.amber.copy(alpha = 0.12f))))
            .border(1.dp, Halo.amber.copy(alpha = 0.6f), rs(999)),
        contentAlignment = Alignment.Center,
    ) { HaloIcon(HIcon.GEM, Halo.amber, size * 0.52f) }
}

/**
 * One held coin. No follow star here: following lives next to the list it feeds, and for
 * native SOL the star mixed up "SOL" (wallet) and the mint (market).
 */
@Composable
private fun HoldingRow(h: Holding, currency: String, onClick: () -> Unit) {
    // Amount held and unit price; the price is colored by the day's move.
    val unit = h.fiat?.takeIf { h.ui > 0 }?.let { fmtPrice(it / h.ui, currency) }
    val move = h.change24h
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().tappable(src, rs(Radius.row), onClick = onClick).padding(horizontal = Space.sm + 2.dp, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TokenLogo(h.mint, h.symbol, h.image, 38.dp)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(h.name?.takeIf { TokenSymbols.isKnown(h.mint) } ?: h.symbol, style = HaloType.body, color = Halo.ink, maxLines = 1)
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
            // Fiat value at body size.
            Text(
                h.fiat?.let { fmtFiat(it, currency) } ?: "—",
                style = HaloType.body.copy(fontFamily = Sora, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                color = if (h.fiat != null) Halo.ink else Halo.muted,
            )
            h.change24h?.let { c -> Text(pct(c), style = HaloType.label, color = if (c >= 0) Halo.mint else Halo.red) }
        }
        Spacer(Modifier.width(Space.sm))
        HaloIcon(HIcon.CHEVRON_RIGHT, Halo.muted, 14.dp)
    }
}

private fun pct(c: Double): String = (if (c >= 0) "+" else "−") + "%.1f%%".format(kotlin.math.abs(c))

/** "+1,23 € · +2,0 % oggi": the day's move, green up / red down. */
@Composable
private fun ChangePill(delta: Double, p: Double, currency: String, modifier: Modifier = Modifier) {
    val up = delta >= 0
    // Chevron: the pill opens the P&L.
    DeltaPill(
        (if (up) "+" else "−") + fmtFiat(kotlin.math.abs(delta), currency) + "  ·  " + pct(p) + "  " + stringResource(R.string.hero_today),
        up, modifier, chevron = true,
    )
}

/**
 * One token, tapped from the portfolio: what it is, what it is worth and, for
 * anything but SOL, burn and reclaim the rent. Simulated before the biometric
 * prompt; the Seed Vault signs; the burn lands in the ledger.
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
            Column(Modifier.fillMaxWidth().clip(rs(16)).background(Halo.cardSoft).haloBorder(rs(16), living = false).padding(14.dp)) {
                StatRow(stringResource(R.string.token_amount), fmtUi(h.ui) + " " + h.symbol)
                StatRow(stringResource(R.string.token_value), h.fiat?.let { fmtFiat(it, currency) } ?: stringResource(R.string.burn_value_none), accent = h.fiat != null)
                if (!isSol) {
                    StatRow(stringResource(R.string.token_mint), shorten(h.mint, 6))
                    accounts?.let { StatRow(stringResource(R.string.token_rent), "+" + fmtSol(it.sumOf { a -> a.lamports }, 5) + " SOL") }
                }
            }
            // Send and swap, with this coin preselected.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(stringResource(R.string.send_btn), Modifier.weight(1f), HIcon.SEND, tint = Halo.mint) { onSend() }
                GhostButton(stringResource(R.string.swap_btn), Modifier.weight(1f), HIcon.SWAP, tint = Halo.cyan) { onSwap() }
            }
            // Other actions, paired two per row from whichever apply. Fixed rows left SOL with
            // single stretched buttons; pairing stays balanced even when a price button appears late.
            var priceUsd by remember(h.mint) { mutableStateOf<Double?>(null) }
            LaunchedEffect(h.mint) { priceUsd = withContext(Dispatchers.IO) { runCatching { Prices.usd(listOf(h.mint))[h.mint] }.getOrNull() } }
            var tp by remember { mutableStateOf(false) }
            var alert by remember { mutableStateOf(false) }
            val coin = OrderCoin(h.mint, h.symbol, h.decimals, h.image, priceUsd, h.raw)
            val orderable = !h.isNft && h.raw > 0 && priceUsd != null

            val actions = buildList<TokenAction> {
                // Native SOL's mint is the placeholder "SOL": nothing to copy, and Solscan says
                // "invalid address", so it opens the wallet instead.
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
                // Take profit sells into SOL, so none for SOL itself. Price alerts work for SOL too.
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

/** The total: largest text on the page, animates to new values, sheen when it changes. */
@Composable
private fun BigTotal(total: Double, currency: String) {
    val shown by androidx.compose.animation.core.animateFloatAsState(
        total.toFloat(), androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "total",
    )
    // Sheen runs twice per change, then stops. Looping, with the `RenderEffect` blur below, it
    // redid the blur every frame: 828 frames while scrolling, median 29 ms, p90 38, against
    // 8.3 ms per frame at 120 Hz. At 2 nothing is drawn.
    val sweepAnim = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(2f) }
    androidx.compose.runtime.LaunchedEffect(total) {
        repeat(2) {
            sweepAnim.snapTo(-1f)
            sweepAnim.animateTo(2f, androidx.compose.animation.core.tween(3600, delayMillis = 1400, easing = androidx.compose.animation.core.LinearEasing))
        }
    }
    // Digits in ink; in the accent they looked like a terminal. The accent goes in the glow and sheen.
    val ink = Halo.ink
    val lit = Halo.mint
    // The blur is created once: inside `graphicsLayer` it was rebuilt on every
    // layer invalidation.
    val blur = androidx.compose.runtime.remember {
        android.graphics.RenderEffect.createBlurEffect(18f, 18f, android.graphics.Shader.TileMode.DECAL).asComposeRenderEffect()
    }
    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
        // Blurred accent glow behind the number. Faint is enough on dark themes; light themes need
        // it stronger to show at all under black text.
        Text(
            fmtFiat(shown.toDouble(), currency),
            style = HaloType.amount.copy(fontSize = 44.sp, lineHeight = 50.sp, color = lit.copy(alpha = if (Halo.isLight) 0.30f else 0.18f)),
            modifier = Modifier.graphicsLayer { renderEffect = blur },
        )
        // Sheen drawn over the text, not as a style brush: read in composition, `sweepAnim.value`
        // relaid out the text every frame. Read in draw it only repaints.
        Text(
            fmtFiat(shown.toDouble(), currency),
            style = HaloType.amount.copy(fontSize = 44.sp, lineHeight = 50.sp, color = ink),
            modifier = Modifier
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val s = sweepAnim.value
                    if (s > -0.99f && s < 1.99f) {
                        drawRect(
                            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                listOf(androidx.compose.ui.graphics.Color.Transparent, lit, androidx.compose.ui.graphics.Color.Transparent),
                                startX = (s - 0.25f) * size.width, endX = (s + 0.25f) * size.width,
                            ),
                            blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop,
                        )
                    }
                }
        )
    }
}

/**
 * A position outside the token list: what, where, whether it is live. No value shown: the API
 * only sees its own platforms and estimates APR, and a money figure must be right or absent.
 */
@Composable
private fun DefiRow(d: DefiPosition, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(rs(10)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TokenLogo(if (d.kind == DefiPosition.Kind.ORE) com.clearsign.core.Ore.MINT else d.symbol, d.symbol, d.image, 34.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    when (d.kind) { DefiPosition.Kind.STAKE -> R.string.hero_defi_stake; DefiPosition.Kind.LEND -> R.string.hero_defi_lend; DefiPosition.Kind.ORE -> R.string.hero_defi_ore },
                    d.symbol,
                ),
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

/** A DeFi position as a tile: logo, kind, value. Three fit per row; more scroll. */
@Composable
private fun DefiTile(d: DefiPosition, currency: String, onClick: () -> Unit) {
    val what = when (d.kind) {
        DefiPosition.Kind.STAKE -> stringResource(R.string.hero_tile_stake)
        DefiPosition.Kind.LEND -> stringResource(R.string.hero_tile_lend)
        DefiPosition.Kind.ORE -> stringResource(R.string.hero_tile_ore)
    }
    val line = when (d.kind) {
        DefiPosition.Kind.ORE -> d.sub.substringBefore(" · ")
        else -> d.fiat?.let { fmtFiat(it, currency) } ?: d.sub
    }
    // 106 dp: three tiles plus two gaps fit the card's 339 dp on a 411 dp phone; a fourth scrolls.
    // Every tile opens a sheet.
    HaloTile(106.dp, onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TokenLogo(if (d.kind == DefiPosition.Kind.ORE) com.clearsign.core.Ore.MINT else d.symbol, d.symbol, d.image, 24.dp)
            Spacer(Modifier.width(6.dp))
            Text(d.symbol, style = HaloType.small.copy(fontFamily = Sora, fontWeight = FontWeight.Bold), color = Halo.ink, maxLines = 1)
        }
        Text(what, style = HaloType.label.copy(fontWeight = FontWeight.Medium), color = Halo.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            line, style = HaloType.mono, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (d.state == "active" || d.state == null) Halo.mint else Halo.amber,
        )
    }
}

/** One of the smaller things a holding's card can do. */
private class TokenAction(val label: String, val icon: HIcon, val tint: androidx.compose.ui.graphics.Color, val onClick: () -> Unit)

/**
 * The curve: a faint area under a line, green if the month ends higher, red if not; no axis or
 * numbers. One clock drives it all: the line ends at [LINE_END], the rest is left for the last
 * coins to finish falling so none freezes mid-air.
 */
private const val LINE_END = 0.66f

/** How long one coin's flight lasts, on that same clock. */
private const val FALL = 0.30f

@Composable
private fun BalanceSpark(values: List<Double>, coins: List<String>, modifier: Modifier) {
    val tint = if (values.last() >= values.first()) Halo.mint else Halo.red
    // Draws left to right once per curve, so the left edge reads as the past. Linear, not the
    // app's ease-out: a head that bolts then crawls looks like a stutter.
    val growth = remember(values) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(values) {
        growth.animateTo(1f, androidx.compose.animation.core.tween(5200, easing = androidx.compose.animation.core.LinearEasing))
    }
    // `growth` is read in draw; read here, the spark recomposed every frame. Bitmaps are keyed
    // on the mint: remembered state in a loop is positional, and when the list changed length
    // every image went null.
    val marks = coins.map { m -> androidx.compose.runtime.key(m) { rememberCoinBitmap(m) } }
    val lo = androidx.compose.runtime.remember(values) { values.min() }
    val hi = androidx.compose.runtime.remember(values) { values.max() }
    androidx.compose.foundation.Canvas(modifier) {
        val p = growth.value
        val grow = (p / LINE_END).coerceAtMost(1f)
        val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
        // Margins top and bottom: a line touching the edge looks clipped.
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
        // The curve's coins, biggest first, each dropped as the head passes. None stays on
        // screen: the action buttons are under this.
        marks.forEachIndexed { i, bmp ->
            if (bmp == null) return@forEachIndexed
            // Where on the line it sits, and when on the clock it is let go.
            val at = (i + 1f) / (marks.size + 1f)
            val release = LINE_END * at
            if (p < release) return@forEachIndexed
            val fall = ((p - release) / FALL).coerceIn(0f, 1f)
            if (fall >= 1f) return@forEachIndexed
            val vi = ((values.size - 1) * at).toInt().coerceIn(0, values.size - 1)
            val r = 11.dp.toPx()
            // Arc up and forward with the head's motion, then down; a straight drop looked limp.
            val y0 = py(values[vi])
            val dist = size.height - y0 + r * 2
            val rise = 0.34f
            val drop = -rise * dist * fall + (1f + rise) * dist * fall * fall
            val c = androidx.compose.ui.geometry.Offset(
                size.width * at + dist * 0.13f * fall,
                y0 + drop,
            )
            // Spins and shrinks, gone before it lands. Fading in place looked like switching off.
            val fade = (1f - fall * fall).coerceIn(0f, 1f)
            val spin = (if (i % 2 == 0) 1f else -1f) * 46f * fall
            val d = (r * 1.7f * (1f - 0.28f * fall)).toInt()
            rotate(spin, c) {
                drawImage(
                    bmp,
                    dstOffset = androidx.compose.ui.unit.IntOffset((c.x - d / 2f).toInt(), (c.y - d / 2f).toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(d, d),
                    alpha = 0.78f * fade,
                )
            }
        }
        // A dot at the line's head while it draws.
        if (grow < 1f) {
            val i = ((values.size - 1) * grow).toInt().coerceIn(0, values.size - 1)
            val head = androidx.compose.ui.geometry.Offset(size.width * grow, py(values[i]))
            drawCircle(tint.copy(alpha = 0.16f), 7f, head)
            drawCircle(tint.copy(alpha = 0.55f), 2.2f, head)
        }
    }
}
