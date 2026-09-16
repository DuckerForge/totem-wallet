package com.clearsign.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A second opinion about which coins are worth looking at.
 *
 * Everything the agent considered came from Jupiter: two of its lists, ranked by
 * its own organic score and its own traded volume. One vendor deciding both what
 * exists and what is interesting is a narrow way to see a market, and it is also
 * the easiest thing in the world to game — you only have to game one list.
 *
 * GeckoTerminal ranks **pools** by what is actually moving through them, on a
 * different index with a different definition of trending, and it is free with no
 * key. What comes back here is only a list of mints: they are handed straight to
 * [JupiterTokens.byMints] so they arrive at the gates in exactly the same shape,
 * with the same trading windows, as everything else. A new source widens the
 * field; it never walks around the checks.
 *
 * Blocking: call on IO. Any failure is an empty list, never an exception: a
 * second opinion that is not available is not a reason to stop.
 */
object Gecko {
    private const val TAG = "Apex-Gecko"
    private const val BASE = "https://api.geckoterminal.com/api/v2/networks/solana"

    /** Trending and newly opened pools, as mint addresses. At most [limit] of each. */
    fun mints(limit: Int = 20): List<String> =
        (poolMints("/trending_pools", limit) + poolMints("/new_pools", limit)).distinct()

    private fun poolMints(path: String, limit: Int): List<String> {
        val data = get(BASE + path)?.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<String>(minOf(limit, data.length()))
        for (i in 0 until minOf(limit, data.length())) {
            val pool = data.optJSONObject(i) ?: continue
            // "solana_<mint>" is how this API names a token inside a pool.
            val id = pool.optJSONObject("relationships")?.optJSONObject("base_token")
                ?.optJSONObject("data")?.optString("id").orEmpty()
            val mint = id.substringAfter('_', "").takeIf { it.length in 32..48 } ?: continue
            out += mint
        }
        return out
    }

    /**
     * How far back a chart looks. Each one is a real GeckoTerminal bucket, not a
     * label over the same data: asking for a day of one-minute candles and
     * drawing every tenth is a lie about the resolution you are seeing.
     */
    enum class Span(val path: String, val aggregate: Int, val limit: Int, val labelRes: Int) {
        MINUTES("minute", 5, 60, R.string.span_minutes),   // five hours, five-minute candles
        HOURS("hour", 1, 48, R.string.span_hours),         // two days, hourly
        DAYS("day", 1, 90, R.string.span_days),            // three months, daily
    }

    /**
     * The series, kept for a few minutes per coin.
     *
     * There was no cache at all, and it did not show while one chart was open in
     * one sheet. A feed where every row can open a chart is a different animal:
     * without this, scrolling a list would fire two uncached calls per row at a
     * public API with no key, and get everybody rate limited. Five minutes is
     * shorter than the candle itself on every span we draw.
     */
    private const val FRESH_MS = 5 * 60_000L
    private val seriesCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Double>>>()
    private val poolCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    /** Closes for [mint] on [span], from memory when they are fresh enough. */
    fun series(mint: String, span: Span): List<Double> {
        val key = "$mint|${span.name}"
        val now = System.currentTimeMillis()
        seriesCache[key]?.let { (at, v) -> if (now - at < FRESH_MS) return v }
        val pool = poolCache[mint]?.takeIf { now - it.first < 30 * 60_000L }?.second
            ?: topPool(mint)?.also { poolCache[mint] = now to it }
            ?: return emptyList()
        val v = closes(pool, span)
        // An empty answer is not cached: the pool may simply be a minute too young,
        // and a coin bought right now is exactly the one somebody wants to see.
        if (v.isNotEmpty()) seriesCache[key] = now to v
        return v
    }

    /** The busiest pool for [mint], which is the one a price should come from. */
    fun topPool(mint: String): String? {
        val data = get("$BASE/tokens/$mint/pools?page=1")?.optJSONArray("data") ?: return null
        var best: String? = null
        var bestLiq = -1.0
        for (i in 0 until data.length()) {
            val p = data.optJSONObject(i) ?: continue
            val liq = p.optJSONObject("attributes")?.optString("reserve_in_usd")?.toDoubleOrNull() ?: 0.0
            val id = p.optString("id").substringAfter('_', "")
            if (id.isNotEmpty() && liq > bestLiq) { bestLiq = liq; best = id }
        }
        return best
    }

    /**
     * Closing prices for [pool], oldest first. Empty when the pool is too young or
     * the API is unreachable — and an empty chart is drawn as nothing at all,
     * never as a flat line, which would read as a price that did not move.
     */
    fun closes(pool: String, span: Span): List<Double> {
        val url = "$BASE/pools/$pool/ohlcv/${span.path}?aggregate=${span.aggregate}&limit=${span.limit}"
        val list = get(url)?.optJSONObject("data")?.optJSONObject("attributes")
            ?.optJSONArray("ohlcv_list") ?: return emptyList()
        val out = ArrayList<Double>(list.length())
        for (i in 0 until list.length()) {
            // [timestamp, open, high, low, close, volume]
            val c = list.optJSONArray(i)?.optDouble(4) ?: continue
            if (!c.isNaN() && c > 0) out += c
        }
        // The API answers newest first; a chart reads left to right.
        return out.asReversed()
    }

    private fun get(url: String): JSONObject? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 12000
            setRequestProperty("Accept", "application/json")
        }
        val code = c.responseCode
        val body = if (code in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        c.disconnect()
        body?.let { JSONObject(it) }
    } catch (e: Exception) {
        Log.w(TAG, "GET failed: ${e.message}"); null
    }
}
