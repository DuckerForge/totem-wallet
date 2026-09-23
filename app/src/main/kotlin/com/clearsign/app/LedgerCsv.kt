package com.clearsign.app

import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Tax-tool exports, one row per movement; a swap (sent leg plus received leg) is one row. The
 * network fee appears once per transaction and only when this wallet paid it. Pure Kotlin, unit-tested.
 */
object LedgerCsv {
    val KOINLY_HEADER = "Date,Sent Amount,Sent Currency,Received Amount,Received Currency,Fee Amount,Fee Currency,Net Worth Amount,Net Worth Currency,Label,Description,TxHash"
    val COINTRACKER_HEADER = "Date,Received Quantity,Received Currency,Sent Quantity,Sent Currency,Fee Amount,Fee Currency,Tag"

    private fun utc(pattern: String) = SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val koinlyDate = utc("yyyy-MM-dd HH:mm:ss")
    private val ctDate = utc("MM/dd/yyyy HH:mm:ss")

    fun num(raw: Long, decimals: Int): String = BigDecimal.valueOf(raw).abs().movePointLeft(decimals).stripTrailingZeros().toPlainString()
    fun cell(s: String?): String = when {
        s == null -> ""
        s.any { it == ',' || it == '"' || it == '\n' } -> "\"" + s.replace("\"", "\"\"") + "\""
        else -> s
    }

    /** Skip signatures that moved nothing. */
    private fun exportable(entries: List<LedgerEntry>) = entries.filter { it.kind != "message" && it.kind != "signin" }.sortedBy { it.at }

    /** (sent leg?, received leg?) pairs of one entry: swap rows first, then leftovers. */
    internal fun rows(e: LedgerEntry): List<Pair<Leg?, Leg?>> {
        val out = e.outflows.toMutableList(); val inn = e.inflows.toMutableList()
        // A burn is not a trade: the token is destroyed (lost) and the rent refund is your own deposit back.
        if (e.kind == "burn") return out.map { it to null } + inn.map { null to it }
        val rows = ArrayList<Pair<Leg?, Leg?>>()
        while (out.isNotEmpty() && inn.isNotEmpty()) rows.add(out.removeAt(0) to inn.removeAt(0))
        out.forEach { rows.add(it to null) }; inn.forEach { rows.add(null to it) }
        if (rows.isEmpty() && e.feeLamports > 0 && e.feePaidByMe) rows.add(null to null)   // fee-only tx (revoke, close)
        return rows
    }

    fun koinlyLabel(e: LedgerEntry): String = when {
        "gift" in e.tags -> "gift"; "income" in e.tags -> "income"; "expense" in e.tags -> "cost"
        e.kind == "theme" -> "cost"
        e.kind == "burn" -> "lost"
        else -> ""
    }

    private fun description(e: LedgerEntry): String =
        listOfNotNull(e.dApp, e.kind, e.recipientLabel ?: e.primaryRecipient?.let { shorten(it) }).joinToString(" · ")

    fun koinly(entries: List<LedgerEntry>, currency: String): String = buildString {
        appendLine(KOINLY_HEADER)
        for (e in exportable(entries)) {
            val snap = e.fiat[currency]
            rows(e).forEachIndexed { i, (sent, recv) ->
                val worthLeg = sent ?: recv
                val worth = worthLeg?.let { l -> snap?.priceOf(l.mint)?.let { p -> BigDecimal.valueOf(kotlin.math.abs(l.uiAmount) * p).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() } }
                val fee = if (i == 0 && e.feePaidByMe && e.feeLamports > 0) num(e.feeLamports, 9) else null
                appendLine(listOf(
                    synchronized(koinlyDate) { koinlyDate.format(Date(e.at)) },
                    sent?.let { num(it.rawAmount, it.decimals) }, sent?.symbol,
                    recv?.let { num(it.rawAmount, it.decimals) }, recv?.symbol,
                    fee, if (fee != null) "SOL" else null,
                    worth, if (worth != null) currency else null,
                    koinlyLabel(e), description(e), e.signature,
                ).joinToString(",") { cell(it) })
            }
        }
    }

    fun coinTrackerTag(e: LedgerEntry): String = when {
        "gift" in e.tags -> "gift"; "expense" in e.tags || e.kind == "theme" -> "payment"
        e.kind == "burn" -> "lost"
        else -> ""
    }

    fun coinTracker(entries: List<LedgerEntry>): String = buildString {
        appendLine(COINTRACKER_HEADER)
        for (e in exportable(entries)) rows(e).forEachIndexed { i, (sent, recv) ->
            val fee = if (i == 0 && e.feePaidByMe && e.feeLamports > 0) num(e.feeLamports, 9) else null
            appendLine(listOf(
                synchronized(ctDate) { ctDate.format(Date(e.at)) },
                recv?.let { num(it.rawAmount, it.decimals) }, recv?.symbol,
                sent?.let { num(it.rawAmount, it.decimals) }, sent?.symbol,
                fee, if (fee != null) "SOL" else null, coinTrackerTag(e),
            ).joinToString(",") { cell(it) })
        }
    }

    private fun shorten(a: String) = if (a.length > 10) a.take(4) + "…" + a.takeLast(4) else a
}
