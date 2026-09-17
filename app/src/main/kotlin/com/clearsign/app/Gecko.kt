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
    private const val FRESH_MS = 30 * 60_000L
    private val seriesCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Candle>>>()
    /**
     * Which pool to read a coin from, written down for good.
     *
     * The busiest pool for a coin does not change from one minute to the next,
     * and finding it costs a request to a source that starts refusing after five
     * of them. Held only in memory it was looked up again after every restart and
     * every half hour, so opening a chart cost two requests where it needed one,
     * and the second one was the one that got refused. Measured: a coin the app
     * had just called unchartable turned out to have twenty pools.
     */
    private val poolCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    private const val POOL_MS = 7 * 24 * 3600_000L
    @Volatile private var poolsLoaded = false

    /** Give it a context once and the pool map survives the app being closed. */
    fun warmPools(ctx: android.content.Context) {
        if (poolsLoaded) return
        poolsLoaded = true
        poolFile = java.io.File(ctx.filesDir, "gecko_pools.json")
        runCatching {
            val f = poolFile ?: return
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            val now = System.currentTimeMillis()
            o.keys().forEach { k ->
                val a = o.optJSONArray(k) ?: return@forEach
                val at = a.optLong(0)
                if (now - at < POOL_MS) poolCache[k] = at to a.optString(1)
            }
        }
    }

    @Volatile private var poolFile: java.io.File? = null

    private fun savePools() {
        val f = poolFile ?: return
        runCatching {
            val o = JSONObject()
            poolCache.forEach { (k, v) -> o.put(k, org.json.JSONArray().put(v.first).put(v.second)) }
            f.writeText(o.toString())
        }
    }

    /** Candles for [mint] on [span], from memory when they are fresh enough. */
    fun series(mint: String, span: Span, bg: Boolean = false): List<Candle> {
        val key = "$mint|${span.name}"
        val now = System.currentTimeMillis()
        seriesCache[key]?.let { (at, v) -> if (now - at < FRESH_MS) return v }
        val pool = poolCache[mint]?.takeIf { now - it.first < POOL_MS }?.second
            ?: topPool(mint, bg)?.also { poolCache[mint] = now to it; savePools() }
            ?: return emptyList()
        val v = candles(pool, span, bg)
        // An empty answer is not cached: the pool may simply be a minute too young,
        // and a coin bought right now is exactly the one somebody wants to see.
        if (v.isNotEmpty()) seriesCache[key] = now to v
        return v
    }

    /** The busiest pool for [mint], which is the one a price should come from. */
    fun topPool(mint: String, bg: Boolean = false): String? {
        val data = get("$BASE/tokens/$mint/pools?page=1", bg)?.optJSONArray("data") ?: return null
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
    fun closes(pool: String, span: Span): List<Double> = candles(pool, span).map { it.close }

    /**
     * One candle, with the hour it belongs to.
     *
     * The timestamp used to be thrown away, and it is the thing that turns a
     * price line into a story: knowing *when* somebody bought puts a mark on the
     * line at the moment they did it. The price under that mark is read off the
     * chart, never off a claim about what they paid, which nobody published.
     */
    data class Candle(val at: Long, val close: Double)

    fun candles(pool: String, span: Span, bg: Boolean = false): List<Candle> {
        val url = "$BASE/pools/$pool/ohlcv/${span.path}?aggregate=${span.aggregate}&limit=${span.limit}"
        val list = get(url, bg)?.optJSONObject("data")?.optJSONObject("attributes")
            ?.optJSONArray("ohlcv_list") ?: return emptyList()
        val out = ArrayList<Candle>(list.length())
        for (i in 0 until list.length()) {
            // [timestamp, open, high, low, close, volume]
            val row = list.optJSONArray(i) ?: continue
            val c = row.optDouble(4)
            val t = row.optLong(0)
            if (!c.isNaN() && c > 0 && t > 0) out += Candle(t * 1000L, c)
        }
        // The API answers newest first; a chart reads left to right.
        return out.asReversed()
    }

    /**
     * One request at a time, and not too close together.
     *
     * Measured against the real thing: five calls in a row and it answers 429,
     * and it stays refused for a while after. Nothing here needs to be fast, and
     * two things ask at once — a coin chart somebody opened, and the balance
     * curve filling itself in behind the home buttons — so without a gate the
     * background work spends the whole allowance and the chart a person is
     * waiting for gets the refusal.
     *
     * A lock and a gap. Everything here already runs on IO threads, so a blocked
     * one costs nothing but itself.
     */
    private val gate = Any()
    @Volatile private var lastCall = 0L
    @Volatile private var lastFront = 0L
    /** A chart somebody opened waits about a second. */
    private const val GAP_FRONT = 900L
    /** The curve filling itself in behind the buttons waits much longer, and gets out of the way. */
    private const val GAP_BACK = 3600L

    /**
     * Two lanes, because one of the two callers has somebody watching it.
     *
     * A single gap for everybody made the balance curve and an opened chart
     * equally slow, and the curve goes first because it starts with the screen:
     * tap a row and you queue behind six of its requests. So background work
     * keeps a wider gap and also stands down for three seconds after anything in
     * front asked, which is exactly the window in which a person is waiting.
     */
    private fun pace(bg: Boolean) {
        synchronized(gate) {
            val now = System.currentTimeMillis()
            var wait = (if (bg) GAP_BACK else GAP_FRONT) - (now - lastCall)
            if (bg) wait = maxOf(wait, 3000L - (now - lastFront))
            if (wait > 0) runCatching { Thread.sleep(wait) }
            lastCall = System.currentTimeMillis()
            if (!bg) lastFront = lastCall
        }
    }

    private fun get(url: String, bg: Boolean = false): JSONObject? {
        // Refused once is not refused. The limiter answers 429 and forgets about
        // it a moment later, and an empty answer here is a chart that simply
        // does not appear, which reads as broken rather than as busy.
        repeat(2) { attempt ->
            pace(bg)
            val body = runCatching {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000; readTimeout = 12000
                    setRequestProperty("Accept", "application/json")
                }
                val code = c.responseCode
                val out = if (code in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
                c.disconnect()
                if (out == null) Log.w(TAG, "GET $code")
                out
            }.getOrElse { Log.w(TAG, "GET failed: ${it.message}"); null }
            if (body != null) return runCatching { JSONObject(body) }.getOrNull()
            // Sei secondi, non uno e mezzo. Misurato: quando rifiuta resta
            // rifiutata per decine di secondi, quindi riprovare subito voleva
            // dire solo farsi dire di no due volte.
            if (attempt == 0) runCatching { Thread.sleep(6000) }
        }
        return null
    }
}
