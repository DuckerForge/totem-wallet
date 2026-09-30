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
    // Null until the first read returns, so "Nothing yet" never flashes over a full ledger.
    // A second visit starts from the last full read.
    val loaded by produceState(initialValue = lastRead?.takeIf { it.first == version }?.second, version, month) {
        val m = month
        value = withContext(Dispatchers.IO) { if (m == null) Ledger.all(ctx) else Ledger.month(ctx, m) }
            .also { if (m == null) lastRead = version to it }
    }
    val entries = loaded.orEmpty()
    val shown = remember(entries, kind) { if (kind == null) entries else entries.filter { it.kind == kind } }
    var selected by remember { mutableStateOf<LedgerEntry?>(null) }
    var showExport by remember { mutableStateOf(false) }
    var backfilling by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PageHeader(
                stringResource(R.string.tab_receipts),
                // An empty line while reading keeps the header at its height.
                when {
                    loaded == null -> ""
                    shown.isEmpty() -> stringResource(R.string.ledger_empty_sub)
                    else -> stringResource(R.string.ledger_count, shown.size)
                },
                HIcon.RECEIPT,
            ) {
                if (shown.isNotEmpty()) HaloChip(stringResource(R.string.export_btn), HIcon.DOWNLOAD, Halo.mint) { showExport = true }
            }
            // Two chip rows both starting with a selected "All" look identical: label each
            // row, and make each "All" name what it covers.
            FilterLabel(stringResource(R.string.ledger_period))
            ChipRow(listOf<Pair<String?, String>>(null to stringResource(R.string.ledger_all_months)) + months.map { it to monthLabel(it) }, month) { month = it }
            FilterLabel(stringResource(R.string.ledger_kind))
            ChipRow(
                listOf<Pair<String?, String>>(null to stringResource(R.string.ledger_all_kinds)) +
                    listOf("tx", "send", "swap", "agent", "order", "gift", "ore_dig", "ore_claim", "spare_store", "blink", "burn", "envelope", "theme", "revoke", "close", "message", "signin").map { it to kindLabel(ctx, it) },
                kind,
            ) { kind = it }

            // ---- period totals ----------------------------------------------
            if (shown.isNotEmpty()) {
                val totals = remember(shown, currency) { Totals.of(shown, currency) }
                SoftPanel(padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ledger_totals).uppercase(), style = HaloType.label, color = Halo.muted)
                        Spacer(Modifier.weight(1f))
                        Text(fmtFiat(totals.fiatOut, currency), style = HaloType.title, color = Halo.mint)
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
                                style = HaloType.label, color = Halo.amber,
                            )
                        }
                    }
                }
            }
        }

        if (loaded == null) {
            // Still reading: draw nothing.
        } else if (shown.isEmpty()) {
            Box(Modifier.padding(horizontal = 20.dp)) { EmptyState(HIcon.RECEIPT, stringResource(R.string.ledger_empty_sub), stringResource(R.string.ledger_empty)) }
        } else {
            val grouped = remember(shown) { shown.groupBy { dayKey(it.at) } }
            val analytics = remember(shown, currency) { AnalyticsEngine.of(shown, currency) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (analytics.hasData) item(key = "analytics") { AnalyticsCard(analytics) }
                grouped.forEach { (day, list) ->
                    stickyHeader(key = "h$day") {
                        Box(Modifier.fillMaxWidth().background(Halo.ground).padding(vertical = 6.dp)) {
                            Text(DateUtils.formatDateTime(ctx, list.first().at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_WEEKDAY), style = HaloType.label, color = Halo.muted)
                        }
                    }
                    items(list, key = { it.id }) { e -> LedgerRow(e, currency) { selected = e } }
                }
            }
        }
    }
    selected?.let { e -> ReceiptDetailSheet(e) { selected = null } }
    // Export `shown`, not `entries`: `entries` ignores the kind filter.
    if (showExport) ExportSheet(shown, month) { showExport = false }
}

/** The last full read and the ledger version it saw, so a second visit draws at once. */
private var lastRead: Pair<Int, List<LedgerEntry>>? = null

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
    val icon = when (e.kind) { "signin" -> HIcon.LOGIN; "message" -> HIcon.PEN; "theme" -> HIcon.GEM; "revoke" -> HIcon.KEY; "close" -> HIcon.TRASH; "burn" -> HIcon.TRASH; "envelope" -> HIcon.HOURGLASS; "send" -> HIcon.SEND; "swap" -> HIcon.SWAP; "blink" -> HIcon.SPARK; "agent" -> if (e.host == "refused" || e.host == "expired") HIcon.BLOCK else HIcon.AGENT; "order" -> HIcon.HOURGLASS; "gift" -> HIcon.GIFT; "spare_store" -> HIcon.COINS; else -> if (e.sent) HIcon.SEND else HIcon.SIGN }
    val danger = e.risks.any { it.severity == "DANGER" }
    // The row opens the receipt: contained, pressable, with a chevron.
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().tappable(src, rs(14), fill = Halo.card, border = if (danger) Halo.red.copy(alpha = 0.5f) else null, onClick = onTap).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(rs(10)).background(Halo.cardSoft), contentAlignment = Alignment.Center) { HaloIcon(icon, if (danger) Halo.red else Halo.cyan, 18.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(Ledger.signerName(ctx, e.dApp) + (e.host?.let { " · $it" } ?: ""), style = HaloType.small.copy(fontWeight = FontWeight.SemiBold), color = Halo.ink, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(DateUtils.formatDateTime(ctx, e.at, DateUtils.FORMAT_SHOW_TIME) + " · " + kindLabel(ctx, e.kind) + (if (e.kind == "agent") agentHow(ctx, e.host) else "") + (if (e.kind == "order" && e.note.isNotBlank()) " · " + e.note else "") + (e.recipientLabel?.let { " · $it" } ?: ""), style = HaloType.label.copy(fontWeight = FontWeight.Medium), color = Halo.muted, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (e.attestationSig != null) { Spacer(Modifier.width(5.dp)); HaloIcon(HIcon.SHIELD_LOCK, Halo.mint, 11.dp) }
                if (e.tags.contains(LedgerRecorder.FAILED)) { Spacer(Modifier.width(5.dp)); Text(stringResource(R.string.ledger_failed), style = HaloType.label, color = Halo.red) }
                e.tags.firstOrNull { it != LedgerRecorder.FAILED }?.let { Spacer(Modifier.width(5.dp)); Text(it, style = HaloType.label, color = Halo.cyan) }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            e.outflows.take(2).forEach { Text("−" + fmtUi(kotlin.math.abs(it.uiAmount)) + " " + it.symbol, style = HaloType.small.copy(fontFamily = Sora, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), color = Halo.ink) }
            e.inflows.take(1).forEach { Text("+" + fmtUi(kotlin.math.abs(it.uiAmount)) + " " + it.symbol, style = HaloType.mono, color = Halo.cyan) }
            e.fiatValue(currency)?.let { Text("≈ " + fmtFiat(it, currency), style = HaloType.label, color = Halo.muted) }
        }
        Spacer(Modifier.width(6.dp))
        HaloIcon(HIcon.CHEVRON_RIGHT, Halo.muted, 14.dp)
    }
}


/** Realized P&L per token, from the recorded receipts (FIFO). */
@Composable
internal fun AnalyticsCard(a: Analytics) {
    var open by remember { mutableStateOf(false) }
    SoftPanel(padding = 12.dp) {
        Row(Modifier.fillMaxWidth().clip(rs(8)).clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            HaloIcon(HIcon.COINS, Halo.cyan, 14.dp); Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.pnl_title).uppercase(), style = HaloType.label, color = Halo.cyan)
            Spacer(Modifier.weight(1f))
            Text((if (a.totalRealized >= 0) "+" else "") + fmtFiat(a.totalRealized, a.currency), style = HaloType.small.copy(fontFamily = Sora, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), color = if (a.totalRealized >= 0) Halo.mint else Halo.red)
            Spacer(Modifier.width(6.dp)); HaloIcon(if (open) HIcon.CHEVRON_DOWN else HIcon.CHEVRON_RIGHT, Halo.muted, 16.dp)
        }
        Spacer(Modifier.height(if (open) 8.dp else 0.dp))
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
                    if (t.disposals > 0) {
                        val ctx = LocalContext.current
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(28.dp).clip(rs(999)).clickable {
                            val pct = if (t.costOfHeld + t.realized != 0.0 && t.costOfHeld > 0) t.realized / t.costOfHeld * 100 else null
                            PnlCard.share(
                                ctx, PnlCard.Face(t.symbol, ctx.getString(R.string.pnl_card_sub, t.disposals), pct, (if (t.realized >= 0) "+" else "") + fmtFiat(t.realized, a.currency), ctx.getString(R.string.pnl_card_foot)),
                                "pnl-" + t.symbol.lowercase() + ".png",
                            )
                        }, contentAlignment = Alignment.Center) { HaloIcon(HIcon.SHARE, Halo.muted, 14.dp) }
                    }
                }
            }
            // Share the total P&L as one image.
            val ctx = LocalContext.current
            GhostButton(stringResource(R.string.pnl_card_share), Modifier.fillMaxWidth(), HIcon.SHARE, tint = Halo.cyan) {
                PnlCard.share(
                    ctx, PnlCard.Face(ctx.getString(R.string.pnl_title), ctx.getString(R.string.pnl_card_all, a.tokens.size), null, (if (a.totalRealized >= 0) "+" else "") + fmtFiat(a.totalRealized, a.currency), ctx.getString(R.string.pnl_card_foot)),
                    "pnl-total.png",
                )
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
    // Swap, Blink and setup must not fall through to "signed for a dApp".
    "swap" -> ctx.getString(R.string.kind_swap); "blink" -> ctx.getString(R.string.kind_blink); "setup" -> ctx.getString(R.string.kind_setup)
    "ore_dig" -> ctx.getString(R.string.kind_ore_dig); "ore_claim" -> ctx.getString(R.string.kind_ore_claim)
    "spare_store" -> ctx.getString(R.string.kind_spare_store)
    else -> ctx.getString(R.string.kind_tx)
}

/**
 * "Set 2026". The app can be set to another language than the phone, so the month follows
 * the app's choice, not `Locale.getDefault()`.
 */
internal fun monthLabel(ym: String): String = runCatching {
    val (y, m) = ym.split("-").map { it.toInt() }
    val locale = androidx.core.os.LocaleListCompat.getAdjustedDefault()[0] ?: Locale.getDefault()
    java.text.DateFormatSymbols.getInstance(locale).shortMonths[m - 1].replaceFirstChar { it.uppercase() } + " " + y
}.getOrDefault(ym)

private fun dayKey(at: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(java.util.Date(at))

/**
 * A token amount, decimals scaled to its size, grouped per the reader's locale. A flat six
 * decimals showed "80 779.072631" for 80k SKR: four digits of noise.
 */
internal fun fmtUi(v: Double): String {
    val a = kotlin.math.abs(v)
    val pattern = when {
        a >= 1_000 -> "%,.0f"
        a >= 1 -> "%,.2f"
        a >= 0.01 -> "%,.4f"
        else -> "%,.6f"
    }
    return String.format(Locale.getDefault(), pattern, v).let {
        if (pattern == "%,.0f") it else it.trimEnd('0').trimEnd { c -> !c.isDigit() }
    }
}
internal fun fmtFiat(v: Double, cur: String): String =
    if (Settings.guest.value) "••••"
    else if (cur == "SOL") String.format(Locale.getDefault(), if (kotlin.math.abs(v) < 1) "%,.4f" else "%,.3f", v) + " SOL"
    else String.format(Locale.getDefault(), "%,.2f", v) + " " + (runCatching { java.util.Currency.getInstance(cur).symbol }.getOrDefault(cur))

/**
 * Unit price of a coin. Two decimals suit totals, not unit prices: most coins the agent buys
 * trade at four zeros after the point and would show "0,00 €". Decimals follow the size, up
 * to eight.
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
    Text(text.uppercase(), style = HaloType.label, color = Halo.muted, modifier = Modifier.padding(top = 2.dp))
}
