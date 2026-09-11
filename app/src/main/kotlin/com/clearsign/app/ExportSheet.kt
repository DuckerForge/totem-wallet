@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Fmt(val mime: String, val ext: String) { KOINLY("text/csv", "csv"), COINTRACKER("text/csv", "csv"), PDF_STATEMENT("application/pdf", "pdf"), PDF_EACH("application/pdf", "pdf"), JSON("application/json", "json") }

/** Export a period: tax CSVs, PDF statement / receipts, or the attested JSON bundle. */
@Composable
internal fun ExportSheet(entries: List<LedgerEntry>, month: String?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currency by Settings.currency
    var fmt by remember { mutableStateOf(Fmt.KOINLY) }
    var busy by remember { mutableStateOf(false) }
    val period = month?.let { monthLabel(it) } ?: stringResource(R.string.ledger_all)
    val stem = "clearsign-" + fmt.name.lowercase().replace('_', '-') + "-" + (month ?: "all")
    val unpriced = remember(entries, currency) { entries.count { it.hasValue && it.fiat[currency] == null } }
    val save = rememberSaveToLauncher(fmt.mime)

    suspend fun produce(): List<Pair<String, ByteArray>> = withContext(Dispatchers.IO) {
        val key = Attestation.publicKeyBase64()
        when (fmt) {
            Fmt.KOINLY -> listOf("$stem.csv" to LedgerCsv.koinly(entries, currency).toByteArray())
            Fmt.COINTRACKER -> listOf("$stem.csv" to LedgerCsv.coinTracker(entries).toByteArray())
            Fmt.PDF_STATEMENT -> listOf("$stem.pdf" to ReceiptPdf(ctx).statement(entries, currency, period))
            Fmt.PDF_EACH -> entries.filter { it.hasValue }.take(50).map { "clearsign-receipt-${it.id.take(8)}.pdf" to ReceiptPdf(ctx).receipt(it, currency) }
            Fmt.JSON -> listOf("$stem.json" to LedgerBundle.build(entries, key).toByteArray())
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.export_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.ink)
            Text(stringResource(R.string.export_sub, entries.size, period), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
            ChipRow(
                listOf(Fmt.KOINLY to "Koinly CSV", Fmt.COINTRACKER to "CoinTracker CSV", Fmt.PDF_STATEMENT to stringResource(R.string.export_pdf_statement), Fmt.PDF_EACH to stringResource(R.string.export_pdf_each), Fmt.JSON to stringResource(R.string.export_json)),
                fmt,
            ) { it?.let { f -> fmt = f } }
            Text(
                when (fmt) {
                    Fmt.KOINLY -> stringResource(R.string.export_koinly_note, currency); Fmt.COINTRACKER -> stringResource(R.string.export_cointracker_note)
                    Fmt.PDF_STATEMENT -> stringResource(R.string.export_statement_note, currency); Fmt.PDF_EACH -> stringResource(R.string.export_each_note)
                    Fmt.JSON -> stringResource(R.string.export_json_note)
                },
                fontFamily = Inter, fontSize = 12.sp, color = Halo.muted,
            )
            if (unpriced > 0 && fmt != Fmt.COINTRACKER && fmt != Fmt.JSON) Banner(stringResource(R.string.export_unpriced, unpriced, currency), Halo.amber, HIcon.COINS)
            if (busy) Working(stringResource(R.string.export_working))
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(stringResource(R.string.export_share), danger = false, icon = HIcon.SHARE) {
                    busy = true
                    scope.launch {
                        val files = produce().map { (n, b) -> Exports.write(ctx, n, b) }
                        Exports.share(ctx, files, fmt.mime, "ClearSign export $period"); busy = false
                    }
                }
            }
            if (!busy && fmt != Fmt.PDF_EACH) GhostButton(stringResource(R.string.detail_save), icon = HIcon.DOWNLOAD) {
                scope.launch { val (n, b) = produce().first(); save(n, b) }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
