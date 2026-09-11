package com.clearsign.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.res.ResourcesCompat
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Printable receipts, no third-party library: A4 pages drawn line by line in the
 * bundled monospace, with the attestation (public key + signature) at the bottom
 * so the document can be verified independently of the app.
 */
class ReceiptPdf(private val ctx: Context) {
    private val W = 595; private val H = 842; private val M = 40f
    private val lineH = 13f
    private val mono: Typeface = runCatching { ResourcesCompat.getFont(ctx, R.font.jetbrains_mono) }.getOrNull() ?: Typeface.MONOSPACE
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono; textSize = 9.5f; color = Color.BLACK }
    private val bold = Paint(body).apply { typeface = Typeface.create(mono, Typeface.BOLD); textSize = 12.5f }
    private val dim = Paint(body).apply { color = Color.rgb(110, 110, 110) }
    private val utc = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val rule = "-".repeat(72)

    private inner class Page(val doc: PdfDocument, val no: Int) {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, no).create())
        val c: Canvas = page.canvas
        var y = M + 12f
        fun line(s: String, p: Paint = body) { c.drawText(s, M, y, p); y += lineH }
        fun right(label: String, value: String, p: Paint = body) { c.drawText(label, M, y, p); c.drawText(value, W - M - p.measureText(value), y, p); y += lineH }
        fun blank() { y += lineH * 0.6f }
        fun wrapped(prefix: String, text: String, width: Int = 64) { wrap(text, width).forEachIndexed { i, l -> line((if (i == 0) prefix else " ".repeat(prefix.length)) + l) } }
        fun full(): Boolean = y > H - M - lineH * 3
        fun finish() { c.drawText(ctx.getString(R.string.pdf_footer, no), W - M - dim.measureText(ctx.getString(R.string.pdf_footer, no)), H - M + 8f, dim); doc.finishPage(page) }
    }

    private fun wrap(s: String, n: Int): List<String> = if (s.length <= n) listOf(s) else s.chunked(n)
    private fun fmt(raw: Long, dec: Int) = LedgerCsv.num(raw, dec)
    private fun fiat(e: LedgerEntry, l: Leg, cur: String): String =
        e.fiat[cur]?.priceOf(l.mint)?.let { p -> "≈ %.2f %s".format(Locale.ROOT, kotlin.math.abs(l.uiAmount) * p, cur) } ?: ""

    /** One receipt on one page. */
    fun receipt(e: LedgerEntry, currency: String): ByteArray {
        val doc = PdfDocument()
        val p = Page(doc, 1); drawReceipt(p, e, currency); p.finish()
        return bytes(doc)
    }

    private fun drawReceipt(p: Page, e: LedgerEntry, cur: String) {
        p.right("CLEARSIGN · " + ctx.getString(R.string.pdf_receipt).uppercase(), "#" + e.id.take(8), bold)
        p.line(utc.format(Date(e.at)) + "   " + (e.cluster ?: "mainnet-beta"), dim)
        p.line("${ctx.getString(R.string.pdf_dapp)}  ${e.dApp}" + (e.host?.let { " ($it)" } ?: "") + "   ${ctx.getString(R.string.pdf_kind)}  ${e.kind}" + (if (e.txCount > 1) "  ${e.txIndex + 1}/${e.txCount}" else ""))
        p.line(rule, dim)
        for (l in e.outflows) p.right("OUT  -" + fmt(l.rawAmount, l.decimals) + " " + l.symbol, fiat(e, l, cur))
        for (l in e.inflows) p.right("IN   +" + fmt(l.rawAmount, l.decimals) + " " + l.symbol, fiat(e, l, cur))
        if (e.outflows.isEmpty() && e.inflows.isEmpty()) p.line(ctx.getString(R.string.pdf_no_value), dim)
        if (e.feeLamports > 0) p.line("FEE   " + fmt(e.feeLamports, 9) + " SOL" + (if (e.feePaidByMe) "  (" + ctx.getString(R.string.pdf_fee_mine) + ")" else "  (" + ctx.getString(R.string.pdf_fee_other) + ")"))
        p.line(rule, dim)
        if (e.counterparties.isNotEmpty()) {
            p.line(ctx.getString(R.string.pdf_to).uppercase(), dim)
            for (c in e.counterparties) p.right("  " + (c.label ?: c.address.take(4) + "…" + c.address.takeLast(4)).take(22).padEnd(22) + " [" + c.trust + "]" + (if (c.isFee) " fee" else "") + (if (c.isNewAccount) " new" else ""), fmt(c.rawAmount, c.decimals) + " " + c.symbol)
        } else if (e.primaryRecipient != null) p.line("${ctx.getString(R.string.pdf_to)}  " + (e.recipientLabel ?: "") + "  " + e.primaryRecipient)
        if (e.risks.isNotEmpty()) { p.line(ctx.getString(R.string.pdf_risks).uppercase(), dim); for (r in e.risks) p.wrapped("  ${r.severity.padEnd(6)} ${r.flag}  ", r.detail, 40) }
        p.line(rule, dim)
        e.signature?.let { p.wrapped("TX     ", it) }
        if (e.note.isNotBlank()) p.wrapped(ctx.getString(R.string.pdf_note).uppercase().padEnd(7), e.note)
        if (e.tags.isNotEmpty()) p.line(ctx.getString(R.string.pdf_tags).uppercase().padEnd(7) + e.tags.joinToString(", "))
        p.line(rule, dim)
        if (e.attestation != null && e.attestationSig != null) {
            p.line(ctx.getString(R.string.pdf_attested), bold)
            p.wrapped("PUBKEY ", Attestation.publicKeyBase64() ?: "-")
            p.wrapped("SIG    ", e.attestationSig)
            p.wrapped("SHA256 ", Attestation.sha256Hex(e.attestation.toByteArray()))
        } else p.line(ctx.getString(R.string.pdf_not_attested), dim)
    }

    /** A period statement: totals, then one table row per entry, then the keys. */
    fun statement(entries: List<LedgerEntry>, currency: String, title: String): ByteArray {
        val doc = PdfDocument(); var no = 1
        var p = Page(doc, no)
        p.line("CLEARSIGN · " + ctx.getString(R.string.pdf_statement).uppercase() + "  " + title, bold)
        p.line(utc.format(Date()), dim); p.line(rule, dim)
        val sorted = entries.filter { it.kind != "message" && it.kind != "signin" }.sortedBy { it.at }
        // totals per symbol
        val out = HashMap<String, Double>(); val inn = HashMap<String, Double>(); var fee = 0L; var fiatTotal = 0.0; var priced = 0
        for (e in sorted) {
            e.outflows.forEach { out.merge(it.symbol, kotlin.math.abs(it.uiAmount), Double::plus) }
            e.inflows.forEach { inn.merge(it.symbol, kotlin.math.abs(it.uiAmount), Double::plus) }
            if (e.feePaidByMe) fee += e.feeLamports
            e.fiatValue(currency)?.let { fiatTotal += it; priced++ }
        }
        p.line(ctx.getString(R.string.pdf_totals).uppercase(), dim)
        out.entries.sortedBy { it.key }.forEach { p.right("  OUT " + it.key, "%.6f".format(Locale.ROOT, it.value).trimEnd('0').trimEnd('.')) }
        inn.entries.sortedBy { it.key }.forEach { p.right("  IN  " + it.key, "%.6f".format(Locale.ROOT, it.value).trimEnd('0').trimEnd('.')) }
        p.right("  FEE SOL", fmt(fee, 9))
        p.right("  " + ctx.getString(R.string.pdf_fiat_total, currency), "%.2f %s  (%d/%d)".format(Locale.ROOT, fiatTotal, currency, priced, sorted.size))
        p.line(rule, dim); p.blank()
        p.line("DATE                 DAPP            OUT                IN                 FIAT", dim)
        for (e in sorted) {
            if (p.full()) { p.finish(); no++; p = Page(doc, no) }
            val o = e.outflows.firstOrNull()?.let { "-" + fmt(it.rawAmount, it.decimals) + " " + it.symbol } ?: ""
            val i = e.inflows.firstOrNull()?.let { "+" + fmt(it.rawAmount, it.decimals) + " " + it.symbol } ?: ""
            val f = e.fiatValue(currency)?.let { "%.2f".format(Locale.ROOT, it) } ?: ""
            p.line(utc.format(Date(e.at)).take(16).padEnd(21) + e.dApp.take(15).padEnd(16) + o.take(18).padEnd(19) + i.take(18).padEnd(19) + f)
            if (e.outflows.size + e.inflows.size > 2) p.line("  " + ctx.getString(R.string.pdf_more_legs, e.outflows.size + e.inflows.size - 2), dim)
        }
        if (p.full()) { p.finish(); no++; p = Page(doc, no) }
        p.blank(); p.line(rule, dim); p.line(ctx.getString(R.string.pdf_attested_list), bold)
        p.wrapped("PUBKEY ", Attestation.publicKeyBase64() ?: "-")
        for (e in sorted) {
            if (p.full()) { p.finish(); no++; p = Page(doc, no) }
            e.attestationSig?.let { p.line("  #" + e.id.take(8) + "  " + it.take(56) + if (it.length > 56) "…" else "", dim) }
        }
        p.finish()
        return bytes(doc)
    }

    private fun bytes(doc: PdfDocument): ByteArray = ByteArrayOutputStream().use { doc.writeTo(it); doc.close(); it.toByteArray() }
}
