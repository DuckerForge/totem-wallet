package com.clearsign.app

import android.util.Log
import com.clearsign.core.NATIVE_SOL_MINT
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Token prices for the portfolio: Jupiter Price v3 (USD, any mint with liquidity,
 * no key, no CoinGecko rate limits) plus one USD→display-currency factor.
 */
object Prices {
    private const val TAG = "ClearSign-Prices"
    private const val JUP_PRICE = "https://lite-api.jup.ag/price/v3?ids="
    private const val WSOL = "So11111111111111111111111111111111111111112"
    private const val FX_TTL_MS = 15 * 60_000L
    /** How old the worker's rates may be. The ECB publishes once a working day. */
    private const val FX_ARCHIVE_MS = 36 * 3600_000L

    @Volatile private var fxCache: Triple<String, Double, Long>? = null

    /** A spot quote: USD per whole token and the 24h move in percent (when Jupiter has it). */
    data class Px(val usd: Double, val change24h: Double?)

    /**
     * Quotes for [mints] (native SOL priced as wSOL); missing means unknown. Two sources in
     * order: the price endpoint answers for anything with real liquidity and is the cheapest call
     * there is, but not for exactly what the agent buys, young and thin. Those showed a dash,
     * which reads as "we lost it"; the token registry prices them, the same call the swap makes.
     */
    fun quotes(mints: Collection<String>): Map<String, Px> {
        val ids = mints.map { if (it == NATIVE_SOL_MINT) WSOL else it }.distinct()
        val out = HashMap<String, Px>()
        ids.chunked(50).forEach { chunk ->
            val o = get(JUP_PRICE + chunk.joinToString(",")) ?: return@forEach
            for (id in chunk) {
                val q = o.optJSONObject(id) ?: continue
                val p = q.optDouble("usdPrice").takeIf { !it.isNaN() && it > 0 } ?: continue
                out[id] = Px(p, q.optDouble("priceChange24h").takeIf { !it.isNaN() })
            }
        }
        val missing = ids.filter { it !in out }
        if (missing.isNotEmpty()) {
            runCatching { JupiterTokens.byMints(missing) }.getOrDefault(emptyMap()).forEach { (mint, t) ->
                t.usd?.let { out[mint] = Px(it, t.change24h) }
            }
        }
        out[WSOL]?.let { out[NATIVE_SOL_MINT] = it }
        return out
    }

    /** USD price per whole token for [mints]. Missing = unknown. */
    fun usd(mints: Collection<String>): Map<String, Double> = quotes(mints).mapValues { it.value.usd }

    /** How many units of [currency] one USD buys (1.0 for USD); null when no source answers. */
    fun usdTo(currency: String): Double? {
        if (currency == "USD") return 1.0
        // Counting in SOL: one dollar is 1/price SOL. No FX service knows that; the price feed does.
        if (currency == "SOL") return usd(listOf(com.clearsign.core.NATIVE_SOL_MINT))[com.clearsign.core.NATIVE_SOL_MINT]?.takeIf { it > 0 }?.let { 1.0 / it }
        fxCache?.let { (c, v, at) -> if (c == currency && System.currentTimeMillis() - at < FX_TTL_MS) return v }
        val v = frankfurter(currency) ?: viaCoinGecko(currency) ?: return null
        fxCache = Triple(currency, v, System.currentTimeMillis())
        return v
    }

    /**
     * One dollar in [currency], from the ECB via Frankfurter. Its old host (`api.frankfurter.app`)
     * now answers 301 to another host, which a plain connection does not follow.
     */
    internal fun frankfurter(currency: String): Double? {
        if (currency == "USD") return 1.0
        // The worker writes the day's rates for every phone, every ten minutes. Older than a day
        // and a half means it stopped: the ECB is asked directly.
        Archive.read("fx", 10 * 60_000L)
            ?.takeIf { System.currentTimeMillis() - it.optLong("at", 0L) < FX_ARCHIVE_MS }
            ?.optJSONObject("rates")?.optDouble(currency)?.takeIf { !it.isNaN() && it > 0 }
            ?.let { return it }
        return get("https://api.frankfurter.dev/v1/latest?base=USD&symbols=$currency")?.optJSONObject("rates")?.optDouble(currency)?.takeIf { !it.isNaN() && it > 0 }
    }

    private fun viaCoinGecko(currency: String): Double? {
        val sol = runCatching { FiatRates.spot(listOf("USD", currency)) }.getOrDefault(emptyMap())
        val usd = sol["USD"] ?: return null
        val cur = sol[currency] ?: return null
        return if (usd > 0) cur / usd else null
    }

    private fun get(url: String): JSONObject? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 6000; readTimeout = 10000; setRequestProperty("Accept", "application/json") }
        val code = c.responseCode
        if (code != 200) { Log.w(TAG, "$code for $url"); c.disconnect(); null }
        else c.inputStream.bufferedReader().use { JSONObject(it.readText()) }.also { c.disconnect() }
    } catch (e: Exception) { Log.w(TAG, "fetch failed: ${e.message}"); null }
}
