@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode

/** The two ends of the trade, so one picker can serve both cards. */
private enum class Side { FROM, TO }

/** Enough to start with before Jupiter's registry answers; everything else comes from the picker. */
private val POPULAR = listOf(
    // Jupiter quotes in wrapped SOL; `NATIVE_SOL_MINT` is our own placeholder ("SOL")
    // and means nothing to the API. The swap wraps and unwraps around the trade.
    PickToken(Jupiter.SOL_MINT, "SOL", "Solana", TokenSymbols.image(Jupiter.SOL_MINT), 9, verified = true),
    PickToken("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "USDC", "USD Coin", TokenSymbols.image("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"), 6, verified = true),
)

private sealed interface SwapState {
    data object Form : SwapState
    data object Building : SwapState
    data class Review(
        val tx: ByteArray, val analyzed: ReceiptEngine.Analyzed, val outUi: String, val outSym: String,
        val quote: Jupiter.Quote,
        /** The trade as the quote describes it, so the picture survives a failed simulation. */
        val pair: SwapPair,
        /** When this price was fetched, so the screen can say how fresh it is. */
        val at: Long = 0L,
        /** Set when Jupiter Ultra built it: MEV protection, its slippage, and Jupiter lands it. */
        val ultraRequestId: String? = null,
        val ultraSlippageBps: Int = 0,
        val gasless: Boolean = false,
    ) : SwapState
    data object Signing : SwapState
    data class Done(val signature: String) : SwapState
    data class Error(val message: String) : SwapState
}

@Composable
internal fun SwapSheet(signer: SeedVaultSigner, owner: String, buyMint: String? = null, sellMint: String? = null, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var state by remember { mutableStateOf<SwapState>(SwapState.Form) }

    // The wallet's own assets, named and priced exactly like the home screen.
    var owned by remember { mutableStateOf<List<PickToken>>(emptyList()) }
    var picking by remember { mutableStateOf<Side?>(null) }
    var from by remember { mutableStateOf(POPULAR[0]) }              // SOL by default
    var to by remember { mutableStateOf(POPULAR[1]) }                // USDC by default

    // Opened from the market tab with a coin in mind: that coin is what we are
    // buying, and SOL is what we are buying it with. The registry has to answer
    // first, because a token with no decimals cannot be swapped into.
    LaunchedEffect(buyMint) {
        val mint = buyMint ?: return@LaunchedEffect
        val t = withContext(Dispatchers.IO) { runCatching { JupiterTokens.byMints(listOf(mint)) }.getOrNull()?.get(mint) } ?: return@LaunchedEffect
        to = PickToken.of(t)
        from = POPULAR[0]
    }
    // Opened from a coin you already hold: that coin is what you are selling.
    // The other side goes to dollars, or to SOL when the coin *is* dollars,
    // because a swap with the same thing on both sides is not a swap.
    LaunchedEffect(sellMint) {
        val mint = sellMint ?: return@LaunchedEffect
        val real = if (mint == com.clearsign.core.NATIVE_SOL_MINT) Jupiter.SOL_MINT else mint
        val t = withContext(Dispatchers.IO) { runCatching { JupiterTokens.byMints(listOf(real)) }.getOrNull()?.get(real) } ?: return@LaunchedEffect
        from = PickToken.of(t)
        to = if (real == POPULAR[1].mint) POPULAR[0] else POPULAR[1]
    }
    var amount by remember { mutableStateOf("") }
    var quote by remember { mutableStateOf<Jupiter.Quote?>(null) }
    var safety by remember { mutableStateOf<com.clearsign.core.TokenSafety?>(null) }
    var checking by remember { mutableStateOf(false) }
    var quoting by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var editingPct by remember { mutableStateOf(false) }
    // A fresher quote that goes somewhere else. Offered, never applied behind your back.
    var newRoute by remember { mutableStateOf<SwapState.Review?>(null) }

    val currency = Settings.currency.value
    LaunchedEffect(owner, currency) {
        val view = runCatching { Portfolio.load(ctx, owner, currency) }.getOrNull() ?: return@LaunchedEffect
        val holdings = view.holdings.filter { !it.isNft && it.raw > 0 }
        // Jupiter names and prices the ones DAS could not describe, and gives us
        // their decimals — which is what a "to" token needs to be swappable at all.
        withContext(Dispatchers.IO) { runCatching { JupiterTokens.byMints(holdings.map { it.mint }) } }
        owned = holdings.map { h ->
            // Native SOL is held under our placeholder mint; trade it as wrapped SOL.
            if (h.mint == com.clearsign.core.NATIVE_SOL_MINT) PickToken.of(h).copy(mint = Jupiter.SOL_MINT) else PickToken.of(h)
        }
        owned.firstOrNull { it.mint == from.mint }?.let { from = it }
    }

    // What you are buying, graded before you buy it. The receipt can only describe
    // the transaction; it cannot tell you the coin is one you will never sell.
    LaunchedEffect(to.mint) {
        safety = null
        checking = true
        safety = withContext(Dispatchers.IO) {
            val tok = JupiterTokens.cached(to.mint) ?: JupiterTokens.byMints(listOf(to.mint))[to.mint]
            val out = runCatching { Jupiter.sellableBack(to.mint, tok?.decimals ?: to.decimals, tok?.usd ?: to.usd) }.getOrNull()
            // Read the mint itself before the money moves. The registry cannot see
            // a permanent delegate, and a permanent delegate is somebody who can
            // take this coin back out of your wallet afterwards.
            val ext = tok?.let { TokenExtensions.of(to.mint, it.token2022) }
            tok?.facts(out, ext)?.let { com.clearsign.core.assessToken(it) }
        }
        checking = false
    }

    val fromBalance = owned.firstOrNull { it.mint == from.mint }?.balance ?: 0L
    val rawIn = parseRaw(amount, from.decimals)

    /**
     * Quote, transaction, receipt, in one place. Written once because it happens twice: on
     * Review, and every fifteen seconds after while you look. A quote goes stale, and so does
     * the blockhash in the transaction, so a review left open a minute showed a price that no
     * longer existed on a transaction that would no longer land.
     */
    suspend fun buildReview(q: Jupiter.Quote, raw: Long): SwapState = try {
        // The building itself lives in SwapBuild, shared with the feed, so the
        // same trade cannot produce two different receipts on two screens.
        val b = SwapBuild.build(
            ctx, owner,
            SwapBuild.Side(from.mint, from.symbol, from.decimals),
            SwapBuild.Side(to.mint, to.symbol, to.decimals),
            raw, known = q,
        )
        if (b == null) SwapState.Error(ctx.getString(R.string.swap_build_failed))
        else SwapState.Review(
            b.tx, b.analyzed, b.outUi, b.outSymbol, b.quote, b.pair,
            at = b.at, ultraRequestId = b.ultraRequestId, ultraSlippageBps = b.ultraSlippageBps, gasless = b.gasless,
        )
    } catch (e: Exception) {
        SwapState.Error(e.message ?: ctx.getString(R.string.swap_build_failed))
    }

    /**
     * While the review is open the price keeps moving, so the review keeps up, with one hard
     * limit: a new quote can come back with a different route (other pools, other accounts).
     * Swapping that in silently turns the picture you were reading into another transaction,
     * and on this screen that is not allowed. Prices move; the shape of what you sign does not
     * change without you saying so.
     */
    LaunchedEffect(state) {
        val s = state as? SwapState.Review ?: return@LaunchedEffect
        val raw = rawIn ?: return@LaunchedEffect
        delay(15_000)
        val fresh = withContext(Dispatchers.IO) { runCatching { Jupiter.quote(from.mint, to.mint, raw) }.getOrNull() }
        if (fresh == null || state !== s) return@LaunchedEffect
        val next = buildReview(fresh, raw) as? SwapState.Review ?: return@LaunchedEffect
        val sameShape = next.quote.routeLabels == s.quote.routeLabels &&
            next.analyzed.receipt.distributions.size == s.analyzed.receipt.distributions.size
        if (sameShape) state = next else newRoute = next
    }

    // Debounced quote whenever the inputs change.
    LaunchedEffect(from.mint, to.mint, amount) {
        quote = null; formError = null
        if (rawIn == null || rawIn <= 0L || from.mint == to.mint) return@LaunchedEffect
        delay(450)
        quoting = true
        quote = withContext(Dispatchers.IO) { runCatching { Jupiter.quote(from.mint, to.mint, rawIn) }.getOrNull() }
        quoting = false
        if (quote == null) formError = ctx.getString(R.string.swap_quote_failed)
    }

    if (editingPct) {
        CustomPercentSheet(
            current = Settings.swapCustomPct.value,
            onSave = { Settings.setSwapCustomPct(ctx, it); editingPct = false },
        ) { editingPct = false }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.95f).imePadding()) {
            // The coin list takes over the whole sheet rather than stacking a second
            // modal on top of this one, which Compose handles badly.
            val side = picking
            if (side != null) {
                TokenPicker(
                    title = stringResource(if (side == Side.FROM) R.string.swap_pick_from else R.string.swap_pick_to),
                    owned = owned,
                    currency = currency,
                    onPick = { t ->
                        if (side == Side.FROM) from = t else to = t
                        if (from.mint == to.mint) {
                            val other = POPULAR.first { it.mint != t.mint }
                            if (side == Side.FROM) to = other else from = other
                        }
                        picking = null
                    },
                    onClose = { picking = null },
                )
                return@Column
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(rs(12)).background(Halo.cyanSoft), contentAlignment = Alignment.Center) { HaloIcon(HIcon.SWAP, Halo.cyan, 20.dp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.swap_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.ink)
                    Text(stringResource(R.string.swap_sub), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                }
                // A full-height sheet has to say how to leave it: swiping it down is
                // not something a person should have to discover.
                Box(
                    Modifier.size(34.dp).clip(rs(999)).background(Halo.card).haloBorder(rs(999))
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) { HaloIcon(HIcon.CLOSE, Halo.muted, 16.dp, description = stringResource(R.string.close)) }
            }

            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val s = state) {
                    SwapState.Form, SwapState.Building -> {
                        // FROM
                        Column(Modifier.fillMaxWidth().clip(rs(18)).background(Halo.cardSoft).haloBorder(rs(18)).padding(14.dp)) {
                            Text(stringResource(R.string.swap_you_pay), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = amount, onValueChange = { v -> if (v.isEmpty() || v.matches(Regex("^\\d*[.,]?\\d*$"))) amount = v },
                                    modifier = Modifier.weight(1f), singleLine = true,
                                    placeholder = { Text("0.0", fontFamily = Sora, fontSize = 26.sp, color = Halo.muted) },
                                    textStyle = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Halo.ink, fontFeatureSettings = "tnum"),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    colors = swapField(), shape = rs(12),
                                )
                                Spacer(Modifier.width(8.dp))
                                TokenChip(from) { picking = Side.FROM }
                            }
                            Text(stringResource(R.string.send_available, fmtUnits(fromBalance, from.decimals) + " " + from.symbol), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, style = Tabular)
                            // Their own row: five of these next to the balance ran
                            // off the side of the phone.
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Leave SOL behind for the transaction and for the wrapped-SOL account Jupiter opens and
                                // closes inside it: 0.00204 SOL of rent for the duration. A reserve of 0.002 was 0.00004
                                // short, so "MAX" from SOL built a swap that failed in simulation.
                                val spendable = if (from.mint == Jupiter.SOL_MINT) (fromBalance - 3_000_000L).coerceAtLeast(0) else fromBalance
                                fun slice(pct: Int) { amount = fmtUnits(spendable / 100L * pct, from.decimals) }
                                SmallChip("25%", null) { slice(25) }
                                SmallChip("50%", null) { slice(50) }
                                SmallChip("75%", null) { slice(75) }
                                SmallChip(stringResource(R.string.send_max), null) { amount = fmtUnits(spendable, from.decimals) }
                                // Yours. Tap to use it, hold to change it, and it is
                                // still here tomorrow.
                                val custom by Settings.swapCustomPct
                                if (custom in 1..100) {
                                    SmallChip("$custom%", null, tint = Halo.mint, onLongClick = { editingPct = true }, pulse = true) { slice(custom) }
                                } else {
                                    SmallChip(stringResource(R.string.swap_pct_custom), HIcon.PEN, tint = Halo.muted, pulse = true) { editingPct = true }
                                }
                            }
                            Text(stringResource(R.string.swap_from_hint), fontFamily = Inter, fontSize = 10.5.sp, color = Halo.muted)
                        }
                        // Turn the trade around. The amount you were quoted becomes the
                        // amount you are paying, which is what you meant if you picked
                        // the two coins in the wrong order.
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier.size(38.dp).clip(rs(999)).background(Halo.card).haloBorder(rs(999))
                                    .clickable {
                                        val q = quote
                                        val next = if (q != null) fmtUnits(q.outAmount, to.decimals) else ""
                                        val old = from
                                        from = to
                                        to = old
                                        amount = next
                                        Haptics.tick(ctx)
                                    },
                                contentAlignment = Alignment.Center,
                            ) { HaloIcon(HIcon.SWAP, Halo.mint, 18.dp) }
                        }
                        // TO
                        Column(Modifier.fillMaxWidth().clip(rs(18)).background(Halo.cardSoft).haloBorder(rs(18)).padding(14.dp)) {
                            Text(stringResource(R.string.swap_you_get), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    quote?.let { fmtUnits(it.outAmount, to.decimals) } ?: if (quoting) "…" else "0.0",
                                    fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Halo.mint, style = Tabular, modifier = Modifier.weight(1f),
                                )
                                TokenChip(to) { picking = Side.TO }
                            }
                        }
                        if (checking) Text(stringResource(R.string.safe_checking), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted)
                        // What the thing you are buying has been doing. A name and a
                        // number with no shape behind them is the part of a swap that
                        // feels like a coin toss, and a line fixes it for nothing.
                        Box(
                            Modifier.fillMaxWidth().clip(rs(16)).background(Halo.ground.copy(alpha = 0.5f))
                                .haloBorder(rs(16)).padding(14.dp),
                        ) { PriceChart(to.mint, to.symbol) }
                        safety?.let { SafetyCard(it, to.symbol) }
                        ShieldCard(to.mint, to.symbol)
                        // Quote details
                        quote?.let { q ->
                            Column(Modifier.fillMaxWidth().clip(rs(16)).background(Halo.ground.copy(alpha = 0.5f)).haloBorder(rs(16)).padding(14.dp)) {
                                StatRow(stringResource(R.string.swap_rate), "1 ${from.symbol} ≈ " + rate(q, from.decimals, to.decimals) + " ${to.symbol}")
                                StatRow(stringResource(R.string.swap_route), if (q.routeLabels.isEmpty()) "Jupiter" else "Jupiter · " + q.routeLabels.joinToString(", "))
                                StatRow(stringResource(R.string.swap_impact), "%.2f%%".format(q.priceImpactPct * 100))
                            }
                        }
                        formError?.let { Banner(it, Halo.red, HIcon.WARNING) }
                        if (s is SwapState.Building) Working(stringResource(R.string.swap_building))
                    }
                    // The coin was graded on the form, where you picked it. Saying
                    // it again here is the same sentence twice on one screen.
                    is SwapState.Review -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        newRoute?.let { n ->
                            Column(
                                Modifier.fillMaxWidth().clip(rs(14)).background(Halo.amber.copy(alpha = 0.10f))
                                    .border(1.dp, Halo.amber.copy(alpha = 0.45f), rs(14)).padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    stringResource(R.string.swap_route_changed, fmtUnits(n.quote.outAmount, to.decimals), to.symbol),
                                    fontFamily = Inter, fontSize = 12.5.sp, color = Halo.amber, lineHeight = 17.sp,
                                )
                                GhostButton(stringResource(R.string.swap_route_take), tint = Halo.amber) { state = n; newRoute = null }
                            }
                        }
                        SwapSummary(from, to, s.quote)
                        if (s.ultraRequestId != null) {
                            Banner(
                                stringResource(R.string.swap_ultra, "%.2f%%".format(s.ultraSlippageBps / 100.0)) + (if (s.gasless) " " + stringResource(R.string.swap_gasless) else ""),
                                Halo.mint, HIcon.SHIELD_LOCK,
                            )
                        }
                        Text(stringResource(R.string.swap_live), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted)
                        SignReceiptBody(s.analyzed.receipt, null, s.pair, plain = true)
                    }
                    SwapState.Signing -> Working(stringResource(R.string.theme_unlock_signing))
                    is SwapState.Done -> {
                        Banner(stringResource(R.string.swap_done), Halo.mint, HIcon.CHECK)
                        Text(s.signature, fontFamily = Mono, fontSize = 11.sp, color = Halo.muted, maxLines = 2)
                    }
                    is SwapState.Error -> Banner(s.message, Halo.red, HIcon.WARNING)
                }
                Spacer(Modifier.height(8.dp))
            }

            Column(Modifier.fillMaxWidth().background(Halo.ground2).padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding()) {
                when (val s = state) {
                    SwapState.Form -> {
                        val trapped = safety?.flags?.contains(com.clearsign.core.SafetyFlag.NO_WAY_OUT) == true
                        if (trapped) Banner(stringResource(R.string.safe_blocked), Halo.red, HIcon.BLOCK)
                        PrimaryButton(stringResource(R.string.swap_review), danger = false, enabled = !trapped && quote != null && rawIn != null && rawIn <= fromBalance) {
                        val q = quote ?: return@PrimaryButton
                        if (rawIn == null || rawIn > fromBalance) { formError = ctx.getString(R.string.send_insufficient, from.symbol); return@PrimaryButton }
                        state = SwapState.Building
                        scope.launch { state = buildReview(q, rawIn) }
                        }
                    }
                    is SwapState.Review -> {
                        // A route the node says will fail is nearly always a stale price, and the fix is a new
                        // quote. The hold is gone in that case: signing pays a fee for a transaction that does nothing.
                        val willFail = s.analyzed.receipt.risks.any {
                            it.flag == com.clearsign.core.RiskFlag.SIMULATION_FAILED && it.severity == com.clearsign.core.Severity.DANGER
                        }
                        if (willFail) {
                            Banner(stringResource(R.string.swap_would_fail), Halo.red, HIcon.BLOCK)
                            Spacer(Modifier.height(8.dp))
                            PrimaryButton(stringResource(R.string.swap_retry), danger = false) { state = SwapState.Form }
                            Spacer(Modifier.height(8.dp))
                            // Kept, small and last: a simulation can be wrong about
                            // state that is about to change, and it is your money.
                            GhostButton(stringResource(R.string.swap_anyway), tint = Halo.muted) {
                                state = SwapState.Signing
                                scope.launch {
                                    state = when (val r = WalletActions.signAndSendRaw(ctx, signer, owner, s.tx, s.analyzed.receipt, kind = "swap", ultraRequestId = s.ultraRequestId)) {
                                        is WalletActions.Result.Sent -> SwapState.Done(r.signature)
                                        is WalletActions.Result.Failed -> SwapState.Error(r.message)
                                    }
                                }
                            }
                        } else if (s.analyzed.receipt.blocksApproval) { Banner(stringResource(R.string.send_blocked), Halo.red, HIcon.BLOCK); Spacer(Modifier.height(8.dp)); GhostButton(stringResource(R.string.back)) { state = SwapState.Form } }
                        else {
                            HoldToConfirm(stringResource(R.string.swap_hold, s.outUi, s.outSym)) {
                                state = SwapState.Signing
                                scope.launch {
                                    state = when (val r = WalletActions.signAndSendRaw(ctx, signer, owner, s.tx, s.analyzed.receipt, kind = "swap", ultraRequestId = s.ultraRequestId)) {
                                        is WalletActions.Result.Sent -> SwapState.Done(r.signature)
                                        is WalletActions.Result.Failed -> SwapState.Error(r.message)
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp)); GhostButton(stringResource(R.string.back)) { state = SwapState.Form }
                        }
                    }
                    is SwapState.Done -> PrimaryButton(stringResource(R.string.done), danger = false) { onDismiss() }
                    is SwapState.Error -> GhostButton(stringResource(R.string.back)) { state = SwapState.Form }
                    else -> {}
                }
            }
        }
    }
}


/**
 * Pick your own slice, once. A slider, not a number field: this is a phone, the useful
 * values are round, and nobody wants a keyboard for "a third of it".
 */
@Composable
private fun CustomPercentSheet(current: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var pct by remember { mutableStateOf((if (current in 1..100) current else 10).toFloat()) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.swap_pct_title), style = HaloType.title, color = Halo.ink)
            Text(stringResource(R.string.swap_pct_sub), style = HaloType.small, color = Halo.muted)
            Text(
                pct.toInt().toString() + "%",
                fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = Halo.mint, style = Tabular,
            )
            Slider(
                value = pct, onValueChange = { pct = it }, valueRange = 1f..100f,
                colors = SliderDefaults.colors(thumbColor = Halo.mint, activeTrackColor = Halo.mint, inactiveTrackColor = Halo.stroke),
            )
            PrimaryButton(stringResource(R.string.swap_pct_save), danger = false) { onSave(pct.toInt()) }
            if (current in 1..100) {
                GhostButton(stringResource(R.string.swap_pct_clear), tint = Halo.muted) { onSave(0) }
            }
        }
    }
}

/** What the swap does, in trade terms: logos, rate, route, our fee, price impact. */
@Composable
private fun SwapSummary(from: PickToken, to: PickToken, q: Jupiter.Quote) {
    Column(Modifier.fillMaxWidth().clip(rs(18)).background(Halo.cardSoft).haloBorder(rs(18)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TokenLogo(from.mint, from.symbol, from.icon, 34.dp)
            Column(Modifier.weight(1f)) {
                Text(fmtUnits(q.inAmount, from.decimals) + " " + from.symbol, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.ink, style = Tabular)
                Text(stringResource(R.string.swap_you_pay), fontFamily = Inter, fontSize = 10.5.sp, color = Halo.muted)
            }
            HaloIcon(HIcon.CHEVRON_RIGHT, Halo.mint, 18.dp)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(fmtUnits(q.outAmount, to.decimals) + " " + to.symbol, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.mint, style = Tabular)
                Text(stringResource(R.string.swap_you_get), fontFamily = Inter, fontSize = 10.5.sp, color = Halo.muted)
            }
            TokenLogo(to.mint, to.symbol, to.icon, 34.dp)
        }
        StatRow(stringResource(R.string.swap_rate), "1 ${from.symbol} ≈ " + rate(q, from.decimals, to.decimals) + " ${to.symbol}")
        StatRow(stringResource(R.string.swap_route), if (q.routeLabels.isEmpty()) "Jupiter" else "Jupiter · " + q.routeLabels.distinct().joinToString(", "))
        StatRow(stringResource(R.string.swap_impact), "%.2f%%".format(q.priceImpactPct * 100))
        Text(stringResource(R.string.swap_review_note), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted)
    }
}

/** The coin you are trading, as a button that opens the whole registry. */
@Composable
private fun TokenChip(t: PickToken, onClick: () -> Unit) {
    Row(
        Modifier.clip(rs(999)).background(Halo.card).haloBorder(rs(999)).clickable { onClick() }.padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TokenLogo(t.mint, t.symbol, t.icon, 24.dp)
        Spacer(Modifier.width(7.dp))
        Text(t.symbol, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Halo.ink)
        Spacer(Modifier.width(2.dp))
        HaloIcon(HIcon.CHEVRON_DOWN, Halo.muted, 14.dp)
    }
}

@Composable
private fun swapField() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
    cursorColor = Halo.mint, focusedTextColor = Halo.ink, unfocusedTextColor = Halo.ink,
)

private fun parseRaw(text: String, decimals: Int): Long? = runCatching {
    BigDecimal(text.trim().replace(',', '.')).movePointRight(decimals).setScale(0, RoundingMode.DOWN).longValueExact()
}.getOrNull()

private fun rate(q: Jupiter.Quote, inDec: Int, outDec: Int): String {
    val inUi = q.inAmount / Math.pow(10.0, inDec.toDouble())
    val outUi = q.outAmount / Math.pow(10.0, outDec.toDouble())
    if (inUi == 0.0) return "0"
    val r = outUi / inUi
    return if (r >= 1) "%.4f".format(r).trimEnd('0').trimEnd('.') else "%.6f".format(r).trimEnd('0').trimEnd('.')
}
