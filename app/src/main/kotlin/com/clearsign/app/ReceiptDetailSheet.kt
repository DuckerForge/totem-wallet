@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clearsign.core.BalanceDelta
import com.clearsign.core.Receipt
import com.clearsign.core.RecipientShare
import com.clearsign.core.Risk
import com.clearsign.core.RiskFlag
import com.clearsign.core.Severity
import com.clearsign.core.TrustLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Rebuild the receipt the user saw from what the ledger kept, so the detail renders like the live one. */
internal fun receiptOf(e: LedgerEntry): Receipt {
    fun leg(l: Leg) = BalanceDelta(e.wallet, l.mint, l.symbol, l.decimals, l.rawAmount)
    val totalOut = e.outflows.sumOf { kotlin.math.abs(it.rawAmount).toDouble() }.takeIf { it > 0 } ?: 1.0
    val primaryTrust = e.counterparties.firstOrNull { it.address == e.primaryRecipient }?.trust
    return Receipt(
        primaryRecipient = e.primaryRecipient, recipientLabel = e.recipientLabel,
        recipientTrust = runCatching { TrustLevel.valueOf(primaryTrust ?: "NEW") }.getOrDefault(TrustLevel.NEW),
        outflows = e.outflows.map(::leg), inflows = e.inflows.map(::leg), feeLamports = e.feeLamports,
        risks = e.risks.mapNotNull { r -> runCatching { Risk(RiskFlag.valueOf(r.flag), Severity.valueOf(r.severity), r.detail) }.getOrNull() },
        distributions = e.counterparties.map { c ->
            RecipientShare(c.address, c.label, runCatching { TrustLevel.valueOf(c.trust) }.getOrDefault(TrustLevel.NEW),
                BalanceDelta(c.address, c.mint, c.symbol, c.decimals, c.rawAmount, createdAccount = c.isNewAccount),
                share = kotlin.math.abs(c.rawAmount) / totalOut, isFee = c.isFee, isNewAccount = c.isNewAccount)
        },
    )
}

@Composable
internal fun ReceiptDetailSheet(entry: LedgerEntry, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currency by Settings.currency
    var e by remember { mutableStateOf(entry) }
    var note by remember { mutableStateOf(entry.note) }
    val receipt = remember(e.id) { receiptOf(e) }
    val save = rememberSaveToLauncher("application/pdf")

    fun update(f: (LedgerEntry) -> LedgerEntry) { val n = f(e); e = n; scope.launch(Dispatchers.IO) { Ledger.update(ctx, n.id, n.ym) { n } } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.95f).imePadding()) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(rs(12)).background(Halo.cyanSoft), contentAlignment = Alignment.Center) { HaloIcon(HIcon.RECEIPT, Halo.cyan, 20.dp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.dApp + (e.host?.let { " · $it" } ?: ""), style = HaloType.title, color = Halo.ink, maxLines = 1)
                    Text(
                        DateUtils.formatDateTime(ctx, e.at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH) + " · " + kindLabel(ctx, e.kind) +
                            (if (e.txCount > 1) " · ${e.txIndex + 1}/${e.txCount}" else "") + (e.cluster?.let { " · $it" } ?: ""),
                        style = HaloType.small, color = Halo.muted,
                    )
                }
                if (e.attestationSig != null) { HaloIcon(HIcon.SHIELD_LOCK, Halo.mint, 20.dp); Spacer(Modifier.width(8.dp)) }
                RoundIconButton(HIcon.CLOSE, onClick = onDismiss)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (e.hasValue) Column { SignReceiptBody(receipt, e.cluster) }
                else Banner(stringResource(R.string.pdf_no_value), Halo.muted, HIcon.PEN)

                // ---- ledger facts ---------------------------------------------
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.detail_hdr).uppercase(), style = HaloType.label, color = Halo.cyan)
                        e.fiatValue(currency)?.let { StatRow(stringResource(R.string.detail_value, currency), fmtFiat(it, currency), accent = true) }
                        e.fiat.values.sortedBy { it.currency }.forEach { s -> StatRow("SOL / " + s.currency, fmtFiat(s.solPrice, s.currency) + if (s.source != "spot") " · ${s.source}" else "") }
                        StatRow(stringResource(R.string.detail_proof), if (e.attestationSig != null) stringResource(R.string.detail_attested) else stringResource(R.string.pdf_not_attested), accent = e.attestationSig != null)
                        e.signature?.let { sig ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.detail_tx), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted); Spacer(Modifier.width(8.dp))
                                Text(shorten(sig, 8), fontFamily = Mono, fontSize = 11.5.sp, color = Halo.ink, modifier = Modifier.weight(1f))
                                SmallChip(stringResource(R.string.copy), HIcon.COPY, Halo.muted) { copyText(ctx, sig); Haptics.tick(ctx) }
                                Spacer(Modifier.width(6.dp))
                                SmallChip("Solscan", HIcon.EXTERNAL, Halo.cyan) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(solscanTxUrl(sig, e.cluster)))) } }
                            }
                        }
                        e.pkg?.let { StatRow("package", it) }
                    }
                }

                // ---- note + tags ----------------------------------------------
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.detail_note_hdr), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Halo.cyan)
                        ChipRow(listOf("gift", "income", "expense", "trade").map { it to tagLabel(ctx, it) }, e.tags.firstOrNull()) { t ->
                            update { it.copy(tags = if (t == null || it.tags.firstOrNull() == t) emptyList() else listOf(t)) }; Haptics.tick(ctx)
                        }
                        OutlinedTextField(
                            value = note, onValueChange = { note = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                            placeholder = { Text(stringResource(R.string.detail_note_hint), fontFamily = Inter, fontSize = 13.sp, color = Halo.muted) },
                            textStyle = TextStyle(fontFamily = Inter, fontSize = 13.sp, color = Halo.ink), shape = rs(14),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Halo.mint, unfocusedBorderColor = Halo.stroke, focusedContainerColor = Halo.cardSoft, unfocusedContainerColor = Halo.cardSoft, cursorColor = Halo.mint, focusedTextColor = Halo.ink, unfocusedTextColor = Halo.ink),
                        )
                        if (note != e.note) GhostButton(stringResource(R.string.detail_save_note), icon = HIcon.NOTE, tint = Halo.mint) { update { it.copy(note = note) }; Haptics.tick(ctx) }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            // ---- actions --------------------------------------------------------
            Column(Modifier.fillMaxWidth().background(Halo.ground2).padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Un'azione principale piena, le altre leggere: due bottoni fantasma
                // uguali non dicevano quale fosse quella che uno cerca.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        PrimaryButton(stringResource(R.string.detail_share_pdf), danger = false, icon = HIcon.PDF) {
                            scope.launch { val f = withContext(Dispatchers.IO) { Exports.write(ctx, "clearsign-receipt-${e.id.take(8)}.pdf", ReceiptPdf(ctx).receipt(e, currency)) }; Exports.share(ctx, listOf(f), "application/pdf", "ClearSign receipt") }
                        }
                    }
                    GhostButton(stringResource(R.string.detail_save), Modifier.weight(1f), HIcon.DOWNLOAD, height = 54.dp) {
                        scope.launch { val b = withContext(Dispatchers.IO) { ReceiptPdf(ctx).receipt(e, currency) }; save("clearsign-receipt-${e.id.take(8)}.pdf", b) }
                    }
                }
                if (e.attestation != null && e.attestationSig != null) {
                    var showProof by remember { mutableStateOf(false) }
                    GhostButton(stringResource(R.string.proof_show), icon = HIcon.QR, tint = Halo.mint) { showProof = true }
                    if (showProof) ProofSheet(e) { showProof = false }
                }
                if (e.attestation != null && e.attestationSig != null) GhostButton(stringResource(R.string.detail_share_proof), icon = HIcon.SHIELD_LOCK, tint = Halo.cyan) {
                    scope.launch {
                        val f = withContext(Dispatchers.IO) { Exports.write(ctx, "clearsign-proof-${e.id.take(8)}.json", Attestation.exportBundle(e.attestation!!, e.attestationSig!!).toByteArray()) }
                        Exports.share(ctx, listOf(f), "application/json", "ClearSign attested receipt")
                    }
                }
            }
        }
    }
}

internal fun tagLabel(ctx: android.content.Context, t: String): String = when (t) {
    "gift" -> ctx.getString(R.string.tag_gift); "income" -> ctx.getString(R.string.tag_income); "expense" -> ctx.getString(R.string.tag_expense); else -> ctx.getString(R.string.tag_trade)
}
