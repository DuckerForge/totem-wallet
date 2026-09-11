package com.clearsign.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Fiat prices for the ledger (CoinGecko, free tier: ~10 calls/min). A spot
 * snapshot in EUR+USD is taken when an entry is recorded; other currencies and
 * missed entries are backfilled from daily history on demand.
 */
object FiatRates {
    private const val TAG = "ClearSign-Fiat"
    private const val BASE = "https://api.coingecko.com/api/v3"
    val SUPPORTED = listOf("EUR", "USD", "GBP", "CHF", "JPY")
    private const val PREFS = "clearsign_fiat"

    private val dayFmt = SimpleDateFormat("dd-MM-yyyy", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun day(at: Long) = synchronized(dayFmt) { dayFmt.format(Date(at)) }

    private fun get(url: String): JSONObject? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 6000; readTimeout = 10000; setRequestProperty("Accept", "application/json") }
        val code = c.responseCode
        if (code != 200) { Log.w(TAG, "$code for $url"); c.disconnect(); null }
        else c.inputStream.bufferedReader().use { JSONObject(it.readText()) }.also { c.disconnect() }
    } catch (e: Exception) { Log.w(TAG, "fetch failed: ${e.message}"); null }

    /** SOL spot in several currencies: currency → price. */
    fun spot(currencies: List<String>): Map<String, Double> {
        val o = get("$BASE/simple/price?ids=solana&vs_currencies=${currencies.joinToString(",") { it.lowercase() }}")?.optJSONObject("solana") ?: return emptyMap()
        return currencies.mapNotNull { c -> o.optDouble(c.lowercase()).takeIf { !it.isNaN() }?.let { c to it } }.toMap()
    }

    /** Token spot prices by mint (best effort; unknown tokens are simply absent). */
    fun tokenSpot(mints: List<String>, currency: String): Map<String, Double> {
        val todo = mints.filter { it != com.clearsign.core.NATIVE_SOL_MINT }.distinct()
        if (todo.isEmpty()) return emptyMap()
        val o = get("$BASE/simple/token_price/solana?contract_addresses=${todo.joinToString(",")}&vs_currencies=${currency.lowercase()}") ?: return emptyMap()
        val out = HashMap<String, Double>()
        for (m in todo) o.optJSONObject(m)?.optDouble(currency.lowercase())?.takeIf { !it.isNaN() }?.let { out[m] = it }
        return out
    }

    /** SOL price on a given UTC day, cached forever (history doesn't change). */
    fun history(ctx: Context, at: Long, currency: String): Double? {
        val key = "sol:$currency:${day(at)}"
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.contains(key)) return p.getString(key, null)?.toDoubleOrNull()
        val v = get("$BASE/coins/solana/history?date=${day(at)}&localization=false")
            ?.optJSONObject("market_data")?.optJSONObject("current_price")?.optDouble(currency.lowercase())?.takeIf { !it.isNaN() }
        if (v != null) p.edit().putString(key, v.toString()).apply()
        return v
    }

    /** Price a freshly recorded entry in EUR + USD (spot), off the caller's thread. */
    fun fillAsync(ctx: Context, id: String, ym: String, legs: List<Leg>) {
        AppScope.launch {
            val base = listOf("EUR", "USD")
            val sol = spot(base); if (sol.isEmpty()) return@launch
            val mints = legs.map { it.mint }.distinct()
            val now = System.currentTimeMillis()
            val snaps = base.mapNotNull { cur ->
                val s = sol[cur] ?: return@mapNotNull null
                cur to FiatSnapshot(cur, s, runCatching { tokenSpot(mints, cur) }.getOrDefault(emptyMap()), now, "spot")
            }.toMap()
            Ledger.update(ctx, id, ym) { it.copy(fiat = it.fiat + snaps) }
        }
    }

    /**
     * Fill missing [currency] snapshots from daily history, one call per distinct
     * day (2.5 s apart for the free tier). Tokens get today's spot, flagged approx.
     */
    suspend fun backfill(ctx: Context, entries: List<LedgerEntry>, currency: String, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val todo = entries.filter { it.hasValue && it.fiat[currency] == null }
        if (todo.isEmpty()) return
        val mints = todo.flatMap { (it.outflows + it.inflows).map { l -> l.mint } }.distinct()
        val tokenPrices = runCatching { tokenSpot(mints, currency) }.getOrDefault(emptyMap())
        var done = 0
        for ((d, group) in todo.groupBy { day(it.at) }) {
            val price = history(ctx, group.first().at, currency)
            if (price != null) {
                for (e in group) Ledger.update(ctx, e.id, e.ym) { it.copy(fiat = it.fiat + (currency to FiatSnapshot(currency, price, tokenPrices, it.at, if (tokenPrices.isEmpty()) "history" else "spot-approx"))) }
            }
            done += group.size; onProgress(done, todo.size)
            delay(2500)
        }
    }
}
