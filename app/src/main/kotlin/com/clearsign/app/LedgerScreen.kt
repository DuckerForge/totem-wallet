@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.clearsign.app

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** "Scontrini": the ledger — filters, period totals, one row per transaction, export. */
@Composable
internal fun LedgerScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val currency by Settings.currency
    val version = Ledger.version.value
    val months by produceState(initialValue = emptyList<String>(), version) { value = withContext(Dispatchers.IO) { Ledger.months(ctx) } }
    var month by remember { mutableStateOf<String?>(null) }   // null = all
    var kind by remember { mutableStateOf<String?>(null) }
    val entries by produceState(initialValue = emptyList<LedgerEntry>(), version, month) {
        value = withContext(Dispatchers.IO) { if (month == null) Ledger.all(ctx) else Ledger.month(ctx, month!!) }
    }
    val shown = remember(entries, kind) { if (kind == null) entries else entries.filter { it.kind == kind } }
    var selected by remember { mutableStateOf<LedgerEntry?>(null) }
    var showExport by remember { mutableStateOf(false) }
    var backfilling by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.tab_receipts), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Halo.ink)
                    Text(if (shown.isEmpty()) stringResource(R.string.ledger_empty_sub) else stringResource(R.string.ledger_count, shown.size), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                }
                if (shown.isNotEmpty()) SmallChip(stringResource(R.string.export_btn), HIcon.DOWNLOAD, Halo.mint) { showExport = true }
            }
            // Two rows of chips that both began with "All", one under the other and
            // both selected at the start, are impossible to tell apart. Each says
            // what it filters, and each "All" names its own thing.
            FilterLabel(stringResource(R.string.ledger_period))
            ChipRow(listOf<Pair<String?, String>>(null to stringResource(R.string.ledger_all_months)) + months.map { it to monthLabel(it) }, month) { month = it }
            FilterLabel(stringResource(R.string.ledger_kind))
            ChipRow(
                listOf<Pair<String?, String>>(null to stringResource(R.string.ledger_all_kinds)) +
                    listOf("tx", "send", "agent", "order", "gift", "burn", "envelope", "theme", "revoke", "close", "message", "signin").map { it to kindLabel(ctx, it) },
                kind,
            ) { kind = it }

            // ---- period totals ----------------------------------------------
            if (shown.isNotEmpty()) {
                val totals = remember(shown, currency) { Totals.of(shown, currency) }
                Column(Modifier.fillMaxWidth().clip(rs(16)).background(Halo.cardSoft).border(cardBorder(), rs(16)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ledger_totals), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
                        Spacer(Modifier.weight(1f))
                        Text(fmtFiat(totals.fiatOut, currency), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Halo.mint, style = Tabular)
                    }
                    totals.out.entries.sortedByDescending { it.value }.take(4).forEach { (sym, v) -> StatRow("− $sym", fmtUi(v)) }
                    totals.inn.entries.sortedByDescending { it.value }.take(3).forEach { (sym, v) -> StatRow("+ $sym", fmtUi(v)) }
                    StatRow(stringResource(R.string.ledger_fees), fmtSol(totals.fee, 6) + " SOL")
                    if (totals.unpriced > 0) {
                        val bf = backfilling
                        Row(Modifier.fillMaxWidth().clickable(enabled = bf == null) {
                            scope.launch { backfilling = 0 to totals.unpriced; FiatRates.backfill(ctx, shown, currency) { d, t -> backfilling = d to t }; backfilling = null }
                        }, verticalAlignment = Alignment.CenterVertically) {
                            HaloIcon(HIcon.COINS, Halo.amber, 13.dp); Spacer(Modifier.width(6.dp))
                            Text(
                                if (bf == null) stringResource(R.string.ledger_unpriced, totals.unpriced, currency) else stringResource(R.string.ledger_backfilling, bf.first, bf.second),
                                fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, color = Halo.amber,
                            )
                        }
                    }
                }
            }
        }

        if (shown.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                HaloIcon(HIcon.RECEIPT, Halo.muted, 40.dp)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.ledger_empty), fontFamily = Inter, fontSize = 13.sp, color = Halo.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            val grouped = remember(shown) { shown.groupBy { dayKey(it.at) } }
            val analytics = remember(shown, currency) { AnalyticsEngine.of(shown, currency) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (analytics.hasData) item(key = "analytics") { AnalyticsCard(analytics) }
                grouped.forEach { (day, list) ->
                    stickyHeader(key = "h$day") {
                        Box(Modifier.fillMaxWidth().background(Halo.ground).padding(vertical = 6.dp)) {
                            Text(DateUtils.formatDateTime(ctx, list.first().at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_WEEKDAY), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
                        }
                    }
                    items(list, key = { it.id }) { e -> LedgerRow(e, currency) { selected = e } }
                }
            }
        }
    }
    selected?.let { e -> ReceiptDetailSheet(e) { selected = null } }
    // What you see is what you export: passing `entries` here ignored the kind
    // filter, so "Sep 2026 + Gift" said three rows and wrote out all of September.
    if (showExport) ExportSheet(shown, month) { showExport = false }
}

/** Sums for a period, in tokens and in fiat. */
internal class Totals(val out: Map<String, Double>, val inn: Map<String, Double>, val fee: Long, val fiatOut: Double, val unpriced: Int) {
    companion object {
        fun of(entries: List<LedgerEntry>, currency: String): Totals {
            val out = HashMap<String, Double>(); val inn = HashMap<String, Double>(); var fee = 0L; var fiat = 0.0; var unpriced = 0
            for (e in entries) {
                e.outflows.forEach { out.merge(it.symbol, kotlin.math.abs(it.uiAmount), Double::plus) }
                e.inflows.forEach { inn.merge(it.symbol, kotlin.math.abs(it.uiAmount), Double::plus) }
                if (e.feePaidByMe) fee += e.feeLamports
                if (e.hasValue) { val v = e.fiatValue(currency); if (v == null) unpriced++ else if (e.outflows.isNotEmpty()) fiat += v }
            }
            return Totals(out, inn, fee, fiat, unpriced)
        }
    }
}

@Composable
private fun LedgerRow(e: LedgerEntry, currency: String, onTap: () -> Unit) {
    val ctx = LocalContext.current
    val icon = when (e.kind) { "signin" -> HIcon.LOGIN; "message" -> HIcon.PEN; "theme" -> HIcon.GEM; "revoke" -> HIcon.KEY; "close" -> HIcon.TRASH; "burn" -> HIcon.TRASH; "envelope" -> HIcon.HOURGLASS; "send" -> HIcon.SEND; "agent" -> if (e.host == "refused" || e.host == "expired") HIcon.BLOCK else HIcon.PIGEON; "order" -> HIcon.HOURGLASS; "gift" -> HIcon.GIFT; else -> if (e.sent) HIcon.SEND else HIcon.SIGN }
    val danger = e.risks.any { it.severity == "DANGER" }
    Row(
        Modifier.fillMaxWidth().clip(rs(14)).background(Halo.card).border(1.dp, if (danger) Halo.red.copy(alpha = 0.5f) else Halo.stroke, rs(14)).clickable { onTap() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(rs(10)).background(Halo.cardSoft), contentAlignment = Alignment.Center) { HaloIcon(icon, if (danger) Halo.red else Halo.cyan, 18.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.dApp + (e.host?.let { " · $it" } ?: ""), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = Halo.ink, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(DateUtils.formatDateTime(ctx, e.at, DateUtils.FORMAT_SHOW_TIME) + " · " + kindLabel(ctx, e.kind) + (if (e.kind == "agent") agentHow(ctx, e.host) else "") + (if (e.kind == "order" && e.note.isNotBlank()) " · " + e.note else "") + (e.recipientLabel?.let { " · $it" } ?: ""), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (e.attestationSig != null) { Spacer(Modifier.width(5.dp)); HaloIcon(HIcon.SHIELD_LOCK, Halo.mint, 11.dp) }
                if (e.tags.isNotEmpty()) { Spacer(Modifier.width(5.dp)); Text(e.tags.first(), fontFamily = Inter, fontSize = 10.sp, color = Halo.cyan) }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            e.outflows.take(2).forEach { Text("−" + fmtUi(kotlin.math.abs(it.uiAmount)) + " " + it.symbol, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Halo.ink, style = Tabular) }
            e.inflows.take(1).forEach { Text("+" + fmtUi(kotlin.math.abs(it.uiAmount)) + " " + it.symbol, fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Halo.cyan, style = Tabular) }
            e.fiatValue(currency)?.let { Text("≈ " + fmtFiat(it, currency), fontFamily = Inter, fontSize = 10.5.sp, color = Halo.muted, style = Tabular) }
        }
    }
}


/** Realized P&L per token, from the recorded receipts (FIFO). */
@Composable
internal fun AnalyticsCard(a: Analytics) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(rs(16)).background(Halo.cardSoft).border(cardBorder(), rs(16)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            HaloIcon(HIcon.COINS, Halo.cyan, 14.dp); Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.pnl_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Halo.cyan)
            Spacer(Modifier.weight(1f))
            Text((if (a.totalRealized >= 0) "+" else "") + fmtFiat(a.totalRealized, a.currency), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (a.totalRealized >= 0) Halo.mint else Halo.red, style = Tabular)
            Spacer(Modifier.width(6.dp)); HaloIcon(if (open) HIcon.CHEVRON_DOWN else HIcon.CHEVRON_RIGHT, Halo.muted, 16.dp)
        }
        if (open) {
            Text(stringResource(R.string.pnl_note), fontFamily = Inter, fontSize = 10.5.sp, color = Halo.muted)
            a.tokens.take(8).forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.symbol, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.ink)
                        val since = t.firstAt?.let { android.text.format.DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), android.text.format.DateUtils.DAY_IN_MILLIS).toString() }
                        Text(
                            (if (t.heldUnits > 0) stringResource(R.string.pnl_held, fmtUi(t.heldUnits)) else "") + (since?.let { (if (t.heldUnits > 0) " · " else "") + stringResource(R.string.pnl_since, it) } ?: ""),
                            fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, style = Tabular,
                        )
                    }
                    if (t.disposals > 0) Text((if (t.realized >= 0) "+" else "") + fmtFiat(t.realized, a.currency), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (t.realized >= 0) Halo.mint else Halo.red, style = Tabular)
                }
            }
        }
    }
}

/** How an agent row went: " · da solo", " · confermato", " · rifiutato". */
internal fun agentHow(ctx: android.content.Context, host: String?): String = when (host) {
    "auto" -> " · " + ctx.getString(R.string.agent_how_auto)
    "asked" -> " · " + ctx.getString(R.string.agent_how_asked)
    "refused" -> " · " + ctx.getString(R.string.agent_how_refused)
    "expired" -> " · " + ctx.getString(R.string.agent_how_expired)
    else -> ""
}

internal fun kindLabel(ctx: android.content.Context, k: String): String = when (k) {
    "signin" -> ctx.getString(R.string.kind_signin); "message" -> ctx.getString(R.string.kind_message); "theme" -> ctx.getString(R.string.kind_theme)
    "revoke" -> ctx.getString(R.string.kind_revoke); "close" -> ctx.getString(R.string.kind_close); "burn" -> ctx.getString(R.string.kind_burn); "envelope" -> ctx.getString(R.string.kind_envelope); "send" -> ctx.getString(R.string.kind_send)
    "agent" -> ctx.getString(R.string.kind_agent); "gift" -> ctx.getString(R.string.kind_gift); "order" -> ctx.getString(R.string.kind_order)
    else -> ctx.getString(R.string.kind_tx)
}

/**
 * "Set 2026". The app can be set to a different language from the phone, so the
 * month has to follow the app's choice, not `Locale.getDefault()`.
 */
internal fun monthLabel(ym: String): String = runCatching {
    val (y, m) = ym.split("-").map { it.toInt() }
    val locale = androidx.core.os.LocaleListCompat.getAdjustedDefault()[0] ?: Locale.getDefault()
    java.text.DateFormatSymbols.getInstance(locale).shortMonths[m - 1].replaceFirstChar { it.uppercase() } + " " + y
}.getOrDefault(ym)

private fun dayKey(at: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(java.util.Date(at))

internal fun fmtUi(v: Double): String = String.format(Locale.ROOT, "%,.6f", v).replace(',', ' ').trimEnd('0').trimEnd('.')
internal fun fmtFiat(v: Double, cur: String): String =
    if (cur == "SOL") String.format(Locale.getDefault(), if (kotlin.math.abs(v) < 1) "%,.4f" else "%,.3f", v) + " SOL"
    else String.format(Locale.getDefault(), "%,.2f", v) + " " + (runCatching { java.util.Currency.getInstance(cur).symbol }.getOrDefault(cur))

/**
 * The price of one coin, which is not the same kind of number as a total.
 *
 * Two decimals is right for what a holding is worth and useless for what one
 * unit of it costs: most of what the agent buys trades at four zeros after the
 * point, and "0,00 €" is not a price. So the decimals follow the size of the
 * number, down to eight, and the trailing zeros go.
 */
internal fun fmtPrice(v: Double, cur: String): String {
    val symbol = runCatching { java.util.Currency.getInstance(cur).symbol }.getOrDefault(cur)
    val decimals = when {
        v >= 100 -> 2
        v >= 1 -> 3
        v >= 0.01 -> 4
        v >= 0.0001 -> 6
        else -> 8
    }
    val s = String.format(Locale.getDefault(), "%,.${decimals}f", v)
    return (if (s.contains(',') || s.contains('.')) s.trimEnd('0').trimEnd(',', '.') else s) + " " + symbol
}

/** The caption that says what a row of chips filters. */
@Composable
private fun FilterLabel(text: String) {
    Text(
        text.uppercase(), fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 10.sp,
        color = Halo.muted, modifier = Modifier.padding(top = 2.dp),
    )
}
