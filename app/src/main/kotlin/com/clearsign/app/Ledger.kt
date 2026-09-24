package com.clearsign.app

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.pow

/*
 * The structured ledger: one entry per approved transaction, with raw amounts,
 * counterparties, risks, fiat snapshots and the attested proof. Everything the Receipts tab
 * and the tax exports need, as monthly JSONL files under filesDir (append-only; edits rewrite the month).
 */

/** One asset movement of the user's wallet. rawAmount < 0 = leaves the wallet. */
data class Leg(val mint: String, val symbol: String, val decimals: Int, val rawAmount: Long) {
    val uiAmount: Double get() = rawAmount / 10.0.pow(decimals)
}

data class Counterparty(
    val address: String, val label: String?, val trust: String,
    val isFee: Boolean, val isNewAccount: Boolean,
    val rawAmount: Long, val mint: String, val symbol: String, val decimals: Int,
)

data class RiskNote(val flag: String, val severity: String, val detail: String)

/** Prices at (or near) the time of the entry, one per fiat currency. */
data class FiatSnapshot(
    val currency: String,
    val solPrice: Double,
    val prices: Map<String, Double>,   // mint → price in [currency]
    val at: Long,
    val source: String,                // "spot" | "history" | "spot-approx"
) {
    fun priceOf(mint: String): Double? = if (mint == com.clearsign.core.NATIVE_SOL_MINT) solPrice else prices[mint]
}

@androidx.compose.runtime.Immutable
data class LedgerEntry(
    val id: String,
    val groupId: String,               // one approval → N transactions share it
    val at: Long,
    val kind: String,                  // tx | message | signin | theme | revoke | close | send
    val dApp: String, val host: String?, val pkg: String?, val cluster: String?,
    val wallet: String,
    val signature: String?, val sent: Boolean,
    val txIndex: Int, val txCount: Int,
    val outflows: List<Leg>, val inflows: List<Leg>,
    val feeLamports: Long, val feePaidByMe: Boolean,
    val counterparties: List<Counterparty>,
    val primaryRecipient: String?, val recipientLabel: String?,
    val risks: List<RiskNote>,
    val fiat: Map<String, FiatSnapshot> = emptyMap(),
    val attestation: String? = null, val attestationSig: String? = null,
    val note: String = "", val tags: List<String> = emptyList(),
) {
    val ym: String get() = Ledger.yearMonth(at)
    val hasValue: Boolean get() = outflows.isNotEmpty() || inflows.isNotEmpty()
    /** Value of the entry in [currency]: the sent side when there is one, else the received side. */
    fun fiatValue(currency: String): Double? {
        val snap = fiat[currency] ?: return null
        val legs = outflows.ifEmpty { inflows }
        if (legs.isEmpty()) return null
        var total = 0.0
        for (l in legs) { val p = snap.priceOf(l.mint) ?: return null; total += kotlin.math.abs(l.uiAmount) * p }
        return total
    }
}

object Ledger {
    private const val DIR = "ledger"
    private val lock = Any()
    private val cache = java.util.LinkedHashMap<String, List<LedgerEntry>>(8, 0.75f, true)

    /** Bumped after every write; screens `remember(Ledger.version.value)`. */
    val version = mutableStateOf(0)

    private val ymFmt = SimpleDateFormat("yyyy-MM", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
    fun yearMonth(at: Long): String = synchronized(ymFmt) { ymFmt.format(Date(at)) }

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR).apply { mkdirs() }
    private fun file(ctx: Context, ym: String) = File(dir(ctx), "$ym.jsonl")

    fun append(ctx: Context, e: LedgerEntry) {
        synchronized(lock) {
            file(ctx, e.ym).appendText(LedgerJson.encode(e).toString() + "\n")
            cache.remove(e.ym)
        }
        bump()
    }

    /** Rewrite one entry in place (note, tags, fiat). No-op when the id is not in that month. */
    fun update(ctx: Context, id: String, ym: String, f: (LedgerEntry) -> LedgerEntry) {
        synchronized(lock) {
            val entries = readMonth(ctx, ym)
            val idx = entries.indexOfFirst { it.id == id }
            if (idx < 0) return
            val updated = entries.toMutableList().also { it[idx] = f(it[idx]) }
            val tmp = File(dir(ctx), "$ym.tmp")
            tmp.writeText(updated.asReversed().joinToString("") { LedgerJson.encode(it).toString() + "\n" })
            tmp.renameTo(file(ctx, ym))
            cache.remove(ym)
        }
        bump()
    }

    /** Months with entries, newest first. */
    fun months(ctx: Context): List<String> = synchronized(lock) {
        dir(ctx).listFiles { f -> f.name.endsWith(".jsonl") }?.map { it.name.removeSuffix(".jsonl") }?.sortedDescending() ?: emptyList()
    }

    /** Entries of one month, newest first. */
    fun month(ctx: Context, ym: String): List<LedgerEntry> = synchronized(lock) { readMonth(ctx, ym) }

    /**
     * The names this wallet has carried. A row stores the app's name as it was when it signed,
     * which is right for a dApp and wrong for us: those rows were signed by this same app under
     * an older name, and a ledger that says "Velum" next to an entry made by Totem reads like a
     * third party was involved. Rendering maps them to the name on the screen today; what is on
     * disk is left alone, because the file is the record.
     */
    private val OURS = setOf("Totem", "Velum", "Apex", "ClearSign", "Seeker Wallet")

    /** The name to show for a row's signer. */
    fun signerName(ctx: Context, dApp: String): String =
        if (dApp in OURS) ctx.getString(R.string.app_name) else dApp

    fun all(ctx: Context): List<LedgerEntry> = months(ctx).flatMap { month(ctx, it) }

    /**
     * The newest [n] entries, of one [kind] or any, for a card. Months are read newest first and
     * only until the list is full, so five rows never parse a year of files.
     */
    fun recent(ctx: Context, n: Int, kind: String? = null): List<LedgerEntry> = synchronized(lock) {
        takeAcross(months(ctx).map { ym -> { readMonth(ctx, ym) } }, n) { kind == null || it.kind == kind }
    }

    /** The entry a signature belongs to, newest months first, or null. */
    fun bySignature(ctx: Context, signature: String): LedgerEntry? = synchronized(lock) {
        months(ctx).firstNotNullOfOrNull { ym -> readMonth(ctx, ym).firstOrNull { it.signature == signature } }
    }

    /** Pure: the first [n] items that pass [keep], pulling each source only when the ones before did not fill the list. */
    internal fun <T> takeAcross(sources: List<() -> List<T>>, n: Int, keep: (T) -> Boolean): List<T> {
        val out = ArrayList<T>(n)
        for (src in sources) {
            if (out.size >= n) break
            for (e in src()) { if (keep(e)) { out += e; if (out.size >= n) break } }
        }
        return out
    }

    fun range(ctx: Context, fromMs: Long, toMs: Long): List<LedgerEntry> =
        months(ctx).filter { ym -> val (y, m) = ym.split("-").map { it.toInt() }; monthOverlaps(y, m, fromMs, toMs) }
            .flatMap { month(ctx, it) }.filter { it.at in fromMs..toMs }

    fun byId(ctx: Context, id: String, ym: String): LedgerEntry? = month(ctx, ym).firstOrNull { it.id == id }

    /** For "3rd signature · since …": ledger entries + the legacy sign log. */
    fun statsFor(ctx: Context, host: String?, name: String): SignLog.DappStats {
        val mine = all(ctx).filter { e -> if (host != null) e.host == host else e.host == null && e.dApp == name }
        val legacy = SignLog.statsFor(ctx, host, name)
        return SignLog.DappStats(mine.size + legacy.count, listOfNotNull(mine.minOfOrNull { it.at }, legacy.firstAt).minOrNull())
    }

    private fun readMonth(ctx: Context, ym: String): List<LedgerEntry> {
        cache[ym]?.let { return it }
        val f = file(ctx, ym)
        val list = if (!f.exists()) emptyList() else f.readLines().asReversed().mapNotNull { line ->
            if (line.isBlank()) null else runCatching { LedgerJson.decode(org.json.JSONObject(line)) }.getOrNull()
        }
        cache[ym] = list
        while (cache.size > 6) cache.remove(cache.keys.first())
        return list
    }

    private fun monthOverlaps(y: Int, m: Int, fromMs: Long, toMs: Long): Boolean {
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m - 1, 1) }
        val start = cal.timeInMillis; cal.add(java.util.Calendar.MONTH, 1); val end = cal.timeInMillis
        return start <= toMs && end > fromMs
    }

    private fun bump() { android.os.Handler(android.os.Looper.getMainLooper()).post { version.value++ } }
}
