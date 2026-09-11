@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import com.clearsign.core.NATIVE_SOL_MINT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode

private data class TokenDef(val mint: String, val symbol: String, val decimals: Int)

private val POPULAR = listOf(
    TokenDef("So11111111111111111111111111111111111111112", "SOL", 9),
    TokenDef("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "USDC", 6),
    TokenDef("Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", "USDT", 6),
    TokenDef("SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3", "SKR", 6),
    TokenDef("JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN", "JUP", 6),
    TokenDef("DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263", "BONK", 5),
)

private sealed interface SwapState {
    data object Form : SwapState
    data object Building : SwapState
    data class Review(val tx: ByteArray, val analyzed: ReceiptEngine.Analyzed, val outUi: String, val outSym: String) : SwapState
    data object Signing : SwapState
    data class Done(val signature: String) : SwapState
    data class Error(val message: String) : SwapState
}

@Composable
internal fun SwapSheet(signer: SeedVaultSigner, owner: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var state by remember { mutableStateOf<SwapState>(SwapState.Form) }

    // The wallet's own assets = the "from" universe (SOL + tokens with balance).
    var owned by remember { mutableStateOf<List<Pair<TokenDef, Long>>>(emptyList()) }
    var from by remember { mutableStateOf(POPULAR[0]) }              // SOL by default
    var to by remember { mutableStateOf(POPULAR[1]) }                // USDC by default
    var amount by remember { mutableStateOf("") }
    var quote by remember { mutableStateOf<Jupiter.Quote?>(null) }
    var quoting by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(owner) {
        val rpc = SolanaRpc.urlFor(null)
        val lam = withContext(Dispatchers.IO) { runCatching { SolanaRpc.getBalance(rpc, owner) }.getOrNull() ?: 0L }
        val toks = withContext(Dispatchers.IO) { runCatching { SolanaRpc.tokenAccountsOf(rpc, owner) }.getOrDefault(emptyList()).filter { it.amount > 0 && !it.isFrozen } }
        owned = listOf(POPULAR[0] to lam) + toks.map { t -> (POPULAR.firstOrNull { it.mint == t.mint } ?: TokenDef(t.mint, TokenSymbols.symbol(t.mint), t.decimals)) to t.amount }
    }

    val fromBalance = owned.firstOrNull { it.first.mint == from.mint }?.second ?: 0L
    val rawIn = parseRaw(amount, from.decimals)

    // Debounced quote whenever the inputs change.
    LaunchedEffect(from.mint, to.mint, amount) {
        quote = null; formError = null
        if (rawIn == null || rawIn <= 0L || from.mint == to.mint) return@LaunchedEffect
        delay(450)
        quoting = true
        quote = withContext(Dispatchers.IO) { runCatching { Jupiter.quote(from.mint, to.mint, rawIn) }.getOrNull() }
        quoting = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.95f).imePadding()) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(rs(12)).background(Halo.cyanSoft), contentAlignment = Alignment.Center) { HaloIcon(HIcon.SWAP, Halo.cyan, 20.dp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.swap_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.ink)
                    Text(stringResource(R.string.swap_sub), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                }
            }

            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val s = state) {
                    SwapState.Form, SwapState.Building -> {
                        // FROM
                        Column(Modifier.fillMaxWidth().clip(rs(18)).background(Halo.cardSoft).border(1.dp, Halo.stroke, rs(18)).padding(14.dp)) {
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
                                TokenChip(from) { }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.send_available, fmtUnits(fromBalance, from.decimals) + " " + from.symbol), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, style = Tabular)
                                Spacer(Modifier.width(8.dp))
                                SmallChip(stringResource(R.string.send_max), null) { amount = fmtUnits(if (from.mint == NATIVE_SOL_MINT) (fromBalance - 2_000_000L).coerceAtLeast(0) else fromBalance, from.decimals) }
                            }
                            Text(stringResource(R.string.swap_from_hint), fontFamily = Inter, fontSize = 10.5.sp, color = Halo.muted)
                            TokenRow(owned.map { it.first }.ifEmpty { POPULAR }, from) { from = it; if (to.mint == it.mint) to = POPULAR.first { p -> p.mint != it.mint } }
                        }
                        // TO
                        Column(Modifier.fillMaxWidth().clip(rs(18)).background(Halo.cardSoft).border(1.dp, Halo.stroke, rs(18)).padding(14.dp)) {
                            Text(stringResource(R.string.swap_you_get), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    quote?.let { fmtUnits(it.outAmount, to.decimals) } ?: if (quoting) "…" else "0.0",
                                    fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Halo.mint, style = Tabular, modifier = Modifier.weight(1f),
                                )
                                TokenChip(to) { }
                            }
                            TokenRow(POPULAR.filter { it.mint != from.mint }, to) { to = it }
                        }
                        // Quote details
                        quote?.let { q ->
                            Column(Modifier.fillMaxWidth().clip(rs(16)).background(Halo.ground.copy(alpha = 0.5f)).border(1.dp, Halo.stroke, rs(16)).padding(14.dp)) {
                                StatRow(stringResource(R.string.swap_rate), "1 ${from.symbol} ≈ " + rate(q, from.decimals, to.decimals) + " ${to.symbol}")
                                StatRow(stringResource(R.string.swap_route), if (q.routeLabels.isEmpty()) "Jupiter" else "Jupiter · " + q.routeLabels.joinToString(", "))
                                StatRow(stringResource(R.string.swap_fee, "%.2f".format(q.feeBps / 100.0)), fmtUnits(q.outAmount * q.feeBps / 10_000, to.decimals) + " " + to.symbol, accent = true)
                                StatRow(stringResource(R.string.swap_impact), "%.2f%%".format(q.priceImpactPct * 100))
                            }
                        }
                        formError?.let { Banner(it, Halo.red, HIcon.WARNING) }
                        if (s is SwapState.Building) Working(stringResource(R.string.swap_building))
                    }
                    is SwapState.Review -> Column { SignReceiptBody(s.analyzed.receipt, null) }
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
                    SwapState.Form -> PrimaryButton(stringResource(R.string.swap_review), danger = false, enabled = quote != null && rawIn != null && rawIn <= fromBalance) {
                        val q = quote ?: return@PrimaryButton
                        if (rawIn == null || rawIn > fromBalance) { formError = ctx.getString(R.string.send_insufficient, from.symbol); return@PrimaryButton }
                        state = SwapState.Building
                        scope.launch {
                            state = try {
                                val tx = withContext(Dispatchers.IO) {
                                    Jupiter.swapTransaction(q, owner, Jupiter.feeAccountFor(to.mint))
                                        ?: Jupiter.swapTransaction(q, owner, null)   // retry without the fee account if it can't be used
                                } ?: return@launch run { state = SwapState.Error(ctx.getString(R.string.swap_build_failed)) }
                                val analyzed = withContext(Dispatchers.IO) { ReceiptEngine.analyze(ctx, BlocklistScanner(ctx), tx, owner, null, requireSim = false) }
                                SwapState.Review(tx, analyzed, fmtUnits(q.outAmount, to.decimals), to.symbol)
                            } catch (e: Exception) { SwapState.Error(e.message ?: ctx.getString(R.string.swap_build_failed)) }
                        }
                    }
                    is SwapState.Review -> {
                        if (s.analyzed.receipt.blocksApproval) { Banner(stringResource(R.string.send_blocked), Halo.red, HIcon.BLOCK); Spacer(Modifier.height(8.dp)); GhostButton(stringResource(R.string.back)) { state = SwapState.Form } }
                        else {
                            HoldToConfirm(stringResource(R.string.swap_hold, s.outUi, s.outSym)) {
                                state = SwapState.Signing
                                scope.launch {
                                    state = when (val r = WalletActions.signAndSendRaw(ctx, signer, owner, s.tx, s.analyzed.receipt, kind = "swap")) {
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

@Composable
private fun TokenChip(t: TokenDef, onClick: () -> Unit) {
    Row(Modifier.clip(rs(999)).background(Halo.card).border(1.dp, Halo.stroke, rs(999)).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t.symbol, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Halo.ink)
    }
}

@Composable
private fun TokenRow(tokens: List<TokenDef>, selected: TokenDef, onPick: (TokenDef) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tokens.distinctBy { it.mint }.take(8).forEach { t ->
            val on = t.mint == selected.mint
            Box(Modifier.clip(rs(10)).then(if (on) Modifier.background(Halo.mint.copy(alpha = 0.14f)) else Modifier).border(1.dp, if (on) Halo.mint else Halo.stroke, rs(10)).clickable { onPick(t) }.padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text(t.symbol, fontFamily = Inter, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, fontSize = 11.5.sp, color = if (on) Halo.mint else Halo.muted)
            }
        }
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
