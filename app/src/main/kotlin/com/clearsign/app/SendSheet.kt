@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Send SOL or a token from the wallet itself — through the very same receipt a
 * dApp request gets (simulation, split map, risks, look-alike detection), then
 * hold-to-sign. Recipient from the clipboard, the address book, or a QR scan.
 */

/** What the user picked to send. */
private sealed interface Asset {
    val symbol: String
    val decimals: Int
    val available: Long
    data class Sol(override val available: Long) : Asset { override val symbol = "SOL"; override val decimals = 9 }
    data class Token(val acct: SolanaRpc.TokenAccountInfo) : Asset {
        override val symbol = TokenSymbols.symbol(acct.mint); override val decimals = acct.decimals; override val available = acct.amount
    }
}

private sealed interface SendState {
    data object Form : SendState
    data object Analyzing : SendState
    data class Review(val analyzed: ReceiptEngine.Analyzed, val ixs: List<WalletTx.Instruction>, val dest: String, val amountText: String) : SendState
    data object Signing : SendState
    data class Done(val signature: String) : SendState
    data class Error(val message: String) : SendState
}

@Composable
internal fun SendSheet(signer: SeedVaultSigner, owner: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var state by remember { mutableStateOf<SendState>(SendState.Form) }

    var to by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var assets by remember { mutableStateOf<List<Asset>>(emptyList()) }
    var asset by remember { mutableStateOf<Asset?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    val contacts = remember { Contacts.allowlist(ctx) }

    LaunchedEffect(owner) {
        val rpc = SolanaRpc.urlFor(null)
        val (lam, toks) = withContext(Dispatchers.IO) {
            val l = runCatching { SolanaRpc.getBalance(rpc, owner) }.getOrNull() ?: 0L
            val t = runCatching { SolanaRpc.tokenAccountsOf(rpc, owner) }.getOrDefault(emptyList()).filter { it.amount > 0 && !it.isFrozen }
            l to t
        }
        assets = listOf(Asset.Sol(lam)) + toks.sortedByDescending { it.amount }.map { Asset.Token(it) }
        if (asset == null) asset = assets.first()
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { to = parseScanned(it) } }
    val destValid = Base58.decodePubkey(to.trim()) != null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding()) {
            // header
            Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(rs(12)).background(Halo.cyanSoft), contentAlignment = Alignment.Center) { HaloIcon(HIcon.SEND, Halo.cyan, 20.dp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.send_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.ink)
                    Text(
                        when (state) { is SendState.Review -> stringResource(R.string.send_review_sub); else -> stringResource(R.string.send_sub) },
                        fontFamily = Inter, fontSize = 12.sp, color = Halo.muted,
                    )
                }
            }

            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (val s = state) {
                    SendState.Form, SendState.Analyzing -> {
                        // ---- recipient ------------------------------------------
                        FieldLabel(stringResource(R.string.send_to))
                        OutlinedTextField(
                            value = to, onValueChange = { to = it; formError = null },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text(stringResource(R.string.send_to_hint), fontFamily = Mono, fontSize = 13.sp, color = Halo.muted) },
                            textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = Halo.ink),
                            colors = fieldColors(if (to.isBlank()) Halo.stroke else if (destValid) Halo.mint else Halo.red),
                            shape = rs(14),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallChip(stringResource(R.string.send_paste), HIcon.PASTE) { to = clipboardText(ctx)?.trim().orEmpty() }
                            SmallChip(stringResource(R.string.send_scan), HIcon.SCAN) {
                                scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(false).setPrompt(""))
                            }
                        }
                        if (contacts.isNotEmpty()) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                contacts.entries.take(12).forEach { (addr, label) ->
                                    Row(
                                        Modifier.clip(rs(999)).background(if (to.trim() == addr) Halo.mint.copy(alpha = 0.14f) else Halo.cardSoft)
                                            .border(1.dp, if (to.trim() == addr) Halo.mint else Halo.stroke, rs(999))
                                            .clickable { to = addr }.padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) { Avatar(addr, 20.dp); Spacer(Modifier.width(6.dp)); Text(label, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = Halo.ink) }
                                }
                            }
                        }

                        // ---- asset ----------------------------------------------
                        FieldLabel(stringResource(R.string.send_asset))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            assets.forEach { a ->
                                val active = a == asset
                                Box(
                                    Modifier.clip(rs(12)).background(if (active) Halo.mint.copy(alpha = 0.14f) else Color.Transparent)
                                        .border(1.dp, if (active) Halo.mint else Halo.stroke, rs(12))
                                        .clickable { asset = a; amount = ""; Haptics.tick(ctx) }.padding(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    Column {
                                        Text(a.symbol, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (active) Halo.mint else Halo.ink)
                                        Text(fmtSol(a.available, minOf(a.decimals, 4)).let { if (a.decimals == 9) it else fmtUnits(a.available, a.decimals) }, fontFamily = Inter, fontSize = 10.5.sp, color = Halo.muted, style = Tabular)
                                    }
                                }
                            }
                        }

                        // ---- amount ---------------------------------------------
                        FieldLabel(stringResource(R.string.send_amount))
                        OutlinedTextField(
                            value = amount, onValueChange = { v -> if (v.isEmpty() || v.matches(Regex("^\\d*[.,]?\\d*$"))) { amount = v; formError = null } },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text("0.00", fontFamily = Sora, fontSize = 24.sp, color = Halo.muted) },
                            textStyle = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Halo.ink, fontFeatureSettings = "tnum"),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(asset?.symbol ?: "", fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.muted)
                                    Spacer(Modifier.width(8.dp))
                                    SmallChip(stringResource(R.string.send_max), null) {
                                        asset?.let { a ->
                                            val raw = if (a is Asset.Sol) (a.available - SOL_RESERVE).coerceAtLeast(0L) else a.available
                                            amount = fmtUnits(raw, a.decimals)
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                }
                            },
                            colors = fieldColors(Halo.stroke), shape = rs(14),
                        )
                        asset?.let { a -> Text(stringResource(R.string.send_available, fmtUnits(a.available, a.decimals) + " " + a.symbol), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted, style = Tabular) }
                        formError?.let { Banner(it, Halo.red, HIcon.WARNING) }
                        if (s is SendState.Analyzing) Working(stringResource(R.string.send_analyzing))
                    }
                    is SendState.Review -> SignReceiptBody(s.analyzed.receipt, null)
                    SendState.Signing -> Working(stringResource(R.string.theme_unlock_signing))
                    is SendState.Done -> {
                        Banner(stringResource(R.string.send_done), Halo.mint, HIcon.CHECK)
                        Text(s.signature, fontFamily = Mono, fontSize = 11.sp, color = Halo.muted, maxLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GhostButton(stringResource(R.string.copy), Modifier.weight(1f), HIcon.COPY) { copyText(ctx, s.signature) }
                            GhostButton("Solscan", Modifier.weight(1f), HIcon.EXTERNAL, tint = Halo.cyan) {
                                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(solscanTxUrl(s.signature, null)))) }
                            }
                        }
                    }
                    is SendState.Error -> Banner(s.message, Halo.red, HIcon.WARNING)
                }
                Spacer(Modifier.height(8.dp))
            }

            // ---- fixed action bar --------------------------------------------
            Column(Modifier.fillMaxWidth().background(Halo.ground2).padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding()) {
                when (val s = state) {
                    SendState.Form -> PrimaryButton(stringResource(R.string.send_continue), danger = false, enabled = destValid && amount.isNotBlank() && asset != null) {
                        val a = asset ?: return@PrimaryButton
                        val raw = parseAmount(amount, a.decimals)
                        val dest = to.trim()
                        when {
                            raw == null || raw <= 0L -> formError = ctx.getString(R.string.send_invalid_amount)
                            dest == owner -> formError = ctx.getString(R.string.send_self)
                            raw > a.available -> formError = ctx.getString(R.string.send_insufficient, a.symbol)
                            else -> {
                                state = SendState.Analyzing
                                scope.launch {
                                    state = try {
                                        val (ixs, analyzed) = buildAndAnalyze(ctx, owner, dest, a, raw)
                                        SendState.Review(analyzed, ixs, dest, fmtUnits(raw, a.decimals) + " " + a.symbol)
                                    } catch (e: Exception) { SendState.Error(e.message ?: ctx.getString(R.string.wa_no_blockhash)) }
                                }
                            }
                        }
                    }
                    is SendState.Review -> {
                        if (s.analyzed.receipt.blocksApproval) {
                            Banner(stringResource(R.string.send_blocked), Halo.red, HIcon.BLOCK)
                            Spacer(Modifier.height(8.dp))
                            GhostButton(stringResource(R.string.back)) { state = SendState.Form }
                        } else {
                            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.pay), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
                                    Text("−" + s.amountText, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.mint, style = Tabular)
                                }
                                Text(contacts[s.dest] ?: shorten(s.dest), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.ink)
                            }
                            HoldToConfirm(stringResource(R.string.send_hold)) {
                                state = SendState.Signing
                                scope.launch {
                                    val r = s.analyzed.receipt
                                    val log = WalletActions.LogInfo(
                                        kind = "send", outflows = r.outflows.map { "−" + fmtAmt(it) }, recipient = s.dest, receipt = r,
                                        recipientLabel = contacts[s.dest] ?: ctx.getString(R.string.wa_log_send, shorten(s.dest)),
                                    )
                                    state = when (val res = WalletActions.signAndSend(ctx, signer, owner, s.ixs, log)) {
                                        is WalletActions.Result.Sent -> SendState.Done(res.signature)
                                        is WalletActions.Result.Failed -> SendState.Error(res.message)
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            GhostButton(stringResource(R.string.back)) { state = SendState.Form }
                        }
                    }
                    is SendState.Done -> PrimaryButton(stringResource(R.string.done), danger = false) { onDismiss() }
                    is SendState.Error -> GhostButton(stringResource(R.string.back)) { state = SendState.Form }
                    else -> {}
                }
            }
        }
    }
}

/** Keep a little SOL back for fees + rent-exempt minimum when sending "MAX". */
private const val SOL_RESERVE = 1_500_000L

private suspend fun buildAndAnalyze(ctx: Context, owner: String, dest: String, asset: Asset, raw: Long): Pair<List<WalletTx.Instruction>, ReceiptEngine.Analyzed> {
    val rpc = SolanaRpc.urlFor(null)
    val ownerKey = Base58.decode(owner); val destKey = Base58.decode(dest)
    val ixs: List<WalletTx.Instruction> = when (asset) {
        is Asset.Sol -> listOf(WalletTx.systemTransfer(ownerKey, destKey, raw))
        is Asset.Token -> withContext(Dispatchers.IO) {
            val mint = Base58.decode(asset.acct.mint)
            val program = WalletTx.tokenProgramFor(asset.acct.program)
            val ata = Pda.associatedTokenAddress(destKey, mint, program)
            val exists = SolanaRpc.getAccountInfoRaw(rpc, Base58.encode(ata)) != null
            buildList {
                if (!exists) add(WalletTx.createAtaIdempotent(ownerKey, ata, destKey, mint, program))
                add(WalletTx.tokenTransferChecked(Base58.decode(asset.acct.pubkey), mint, ata, ownerKey, raw, asset.decimals, program))
            }
        }
    }
    val bh = withContext(Dispatchers.IO) { SolanaRpc.latestBlockhash(rpc) } ?: throw IllegalStateException(ctx.getString(R.string.wa_no_blockhash))
    val payload = WalletTx.build(ownerKey, Base58.decode(bh.hash), ixs)
    val analyzed = ReceiptEngine.analyze(ctx, BlocklistScanner(ctx), payload, owner, null, requireSim = true)
    return ixs to analyzed
}

private fun parseAmount(text: String, decimals: Int): Long? = runCatching {
    BigDecimal(text.trim().replace(',', '.')).movePointRight(decimals).setScale(0, RoundingMode.DOWN).longValueExact()
}.getOrNull()

/** Raw units → "1.5" (trailing zeros trimmed). */
internal fun fmtUnits(raw: Long, decimals: Int): String {
    val s = BigDecimal.valueOf(raw).movePointLeft(decimals).toPlainString()
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
}

/** A scanned QR may be a bare address or a Solana Pay URI (`solana:<addr>?…`). */
private fun parseScanned(text: String): String {
    val t = text.trim()
    val body = if (t.startsWith("solana:", ignoreCase = true)) t.substringAfter(':').substringBefore('?') else t
    return body.trim()
}

internal fun clipboardText(ctx: Context): String? =
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()

internal fun copyText(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ClearSign", text))
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
}

@Composable
internal fun SmallChip(label: String, icon: HIcon?, tint: Color = Halo.cyan, onClick: () -> Unit) {
    Row(
        Modifier.clip(rs(10)).background(tint.copy(alpha = 0.10f)).border(1.dp, tint.copy(alpha = 0.45f), rs(10))
            .clickable { onClick() }.padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { HaloIcon(icon, tint, 14.dp); Spacer(Modifier.width(6.dp)) }
        Text(label, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = tint)
    }
}

@Composable
private fun fieldColors(border: Color) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = border, unfocusedBorderColor = border.copy(alpha = 0.7f),
    focusedContainerColor = Halo.cardSoft, unfocusedContainerColor = Halo.cardSoft,
    cursorColor = Halo.mint, focusedTextColor = Halo.ink, unfocusedTextColor = Halo.ink,
)
