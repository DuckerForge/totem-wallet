package com.clearsign.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A second candidate source, so Jupiter's list is not the only one to game. GeckoTerminal
 * ranks pools by volume, free, no key. Only mints come back; they go through
 * [JupiterTokens.byMints] and the same gates as everything else. Blocking, IO; failure is an
 * empty list.
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
     * Chart spans, each a real GeckoTerminal bucket: thinning one-minute candles would fake the
     * resolution. [cgDays] is the CoinGecko range for coins with no pool; null where none matches.
     */
    enum class Span(val path: String, val aggregate: Int, val limit: Int, val labelRes: Int, val cgDays: Int?) {
        MINUTES("minute", 5, 60, R.string.span_minutes, null),  // five hours, five-minute candles
        HOURS("hour", 1, 48, R.string.span_hours, 2),           // two days, hourly
        WEEK("hour", 1, 168, R.string.span_week, 7),            // a week, hourly
        MONTH("hour", 4, 180, R.string.span_month, 30),         // a month, four-hourly
        DAYS("day", 1, 90, R.string.span_days, 90),             // three months, daily
        YEAR("day", 1, 365, R.string.span_year, 365);           // a year, daily

        companion object {
            /** The three spans for swap and order sheets; the coin page gets them all. */
            val small = listOf(MINUTES, HOURS, DAYS)
        }
    }

    /**
     * Series cache, per coin. Without it a feed where every row opens a chart fires two calls
     * per row at a keyless public API and gets rate limited.
     */
    private const val FRESH_MS = 30 * 60_000L
    private val seriesCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Candle>>>()
    /**
     * The pool each coin is read from, persisted for a week. The busiest pool rarely changes,
     * and finding it costs a request to an API that refuses after five; memory-only, every
     * restart spent that budget again and charts came back empty.
     */
    private val poolCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    private const val POOL_MS = 7 * 24 * 3600_000L
    @Volatile private var poolsLoaded = false

    /** Give it a context once and the pool map survives the app being closed. */
    fun warmPools(ctx: android.content.Context) {
        if (poolsLoaded) return
        poolsLoaded = true
        // Versioned name: the old file holds pools picked by depth, some dead. Drop it.
        runCatching { java.io.File(ctx.filesDir, "gecko_pools.json").delete() }
        poolFile = java.io.File(ctx.filesDir, "gecko_pools2.json")
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
        val v = candles(pool, span, bg, token = mint)
        // Empty is not cached: the pool may be a minute too young, and a coin just bought
        // is the one people want to see.
        if (v.isNotEmpty()) seriesCache[key] = now to v
        return v
    }

    /**
     * A coin's busiest pool and its figures (depth, day volume, buys vs sells), which come free
     * with the pool lookup. They cache apart: the id a week on disk, the figures three minutes.
     */
    data class Pool(
        val id: String,
        /** Raydium, Orca, Meteora… whoever holds it. */
        val dex: String?,
        val liquidityUsd: Double?,
        val volume24Usd: Double?,
        val buys24: Int?,
        val sells24: Int?,
        val fdvUsd: Double?,
    )

    private val statsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Pool>>()
    private const val STATS_MS = 3 * 60_000L

    /**
     * One in-flight lookup per coin. Opening a sheet starts the figures and the chart together,
     * both asking for the pool before the cache fills; the second caller waits for the first.
     */
    private val asking = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun pool(mint: String, bg: Boolean = false): Pool? = synchronized(asking.computeIfAbsent(mint) { Any() }) {
        val now = System.currentTimeMillis()
        statsCache[mint]?.let { (at, p) -> if (now - at < STATS_MS) return p }
        val data = get("$BASE/tokens/$mint/pools?page=1", bg)?.optJSONArray("data") ?: return null
        val pools = ArrayList<Pool>(data.length())
        for (i in 0 until data.length()) {
            val p = data.optJSONObject(i) ?: continue
            val a = p.optJSONObject("attributes")
            val id = p.optString("id").substringAfter('_', "")
            if (id.isEmpty()) continue
            val tx = a?.optJSONObject("transactions")?.optJSONObject("h24")
            pools += Pool(
                id = id,
                dex = p.optJSONObject("relationships")?.optJSONObject("dex")?.optJSONObject("data")
                    ?.optString("id")?.takeIf { it.isNotEmpty() },
                liquidityUsd = a?.optString("reserve_in_usd")?.toDoubleOrNull()?.takeIf { it > 0 },
                volume24Usd = a?.optJSONObject("volume_usd")?.optString("h24")?.toDoubleOrNull()?.takeIf { it > 0 },
                buys24 = tx?.optInt("buys", -1)?.takeIf { it >= 0 },
                sells24 = tx?.optInt("sells", -1)?.takeIf { it >= 0 },
                fdvUsd = a?.optString("fdv_usd")?.toDoubleOrNull()?.takeIf { it > 0 },
            )
        }
        val best = busiest(pools)
        best?.let {
            statsCache[mint] = now to it
            poolCache[mint] = now to it.id
            savePools()
        }
        return best
    }

    /**
     * The pool where the coin actually trades: top volume, with a depth floor of 1/50 of the
     * deepest pool (tiny depth with big volume is wash trading). Nothing traded: the deepest.
     * Depth alone misleads: EDEL on 20 Sep 2026, deepest pool $179k, zero trades, stale at
     * $0.0062; the traded one $127 deep, $14k volume, at $0.0230.
     */
    internal fun busiest(pools: List<Pool>): Pool? {
        val deepest = pools.maxOfOrNull { it.liquidityUsd ?: 0.0 } ?: return null
        val traded = pools.filter { (it.volume24Usd ?: 0.0) > 0 && (it.liquidityUsd ?: 0.0) >= deepest / 50 }
        return traded.maxByOrNull { it.volume24Usd ?: 0.0 } ?: pools.maxByOrNull { it.liquidityUsd ?: 0.0 }
    }

    /** The busiest pool for [mint], which is the one a price should come from. */
    fun topPool(mint: String, bg: Boolean = false): String? = pool(mint, bg)?.id

    /**
     * Closing prices for [pool], oldest first. Empty when the pool is too young or the API
     * unreachable, and an empty chart is drawn as nothing, never as a flat line.
     */
    fun closes(pool: String, span: Span, token: String? = null): List<Double> = candles(pool, span, token = token).map { it.close }

    /** One candle with its timestamp, so a buy can be marked on the chart at the chart's own price. */
    data class Candle(
        val at: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        /** Dollars through the pool in this candle. Zero when the source has no volume to give. */
        val volume: Double,
    )

    /**
     * [token] is the mint whose price you want; always pass it when known. Without it the API
     * prices the pool's first coin: measured 20 Sep 2026, SOL's deepest pool is "WOTF / SOL"
     * and the SOL chart showed WOTF. With the mint the same pool gives $111, SOL's price.
     */
    fun candles(pool: String, span: Span, bg: Boolean = false, token: String? = null): List<Candle> {
        val url = "$BASE/pools/$pool/ohlcv/${span.path}?aggregate=${span.aggregate}&limit=${span.limit}" +
            (token?.let { "&token=$it" } ?: "")
        val list = get(url, bg)?.optJSONObject("data")?.optJSONObject("attributes")
            ?.optJSONArray("ohlcv_list") ?: return emptyList()
        val out = ArrayList<Candle>(list.length())
        for (i in 0 until list.length()) {
            // [timestamp, open, high, low, close, volume]
            val row = list.optJSONArray(i) ?: continue
            val c = row.optDouble(4)
            val t = row.optLong(0)
            if (c.isNaN() || c <= 0 || t <= 0) continue
            // Keep OHLC and volume, not just the close: candles and volume bars need them.
            fun at(k: Int, fallback: Double) = row.optDouble(k).takeIf { !it.isNaN() && it > 0 } ?: fallback
            out += Candle(t * 1000L, at(1, c), at(2, c), at(3, c), c, row.optDouble(5).takeIf { !it.isNaN() && it > 0 } ?: 0.0)
        }
        // The API answers newest first; a chart reads left to right.
        return out.asReversed()
    }

    /**
     * One request at a time, spaced out. Measured: five calls in a row get 429 for a while.
     * An opened chart and the home balance curve share that budget; without this gate the
     * background spends it and the visible chart gets refused. Lock plus gap, on IO.
     */
    private val gate = Any()
    @Volatile private var lastCall = 0L
    @Volatile private var lastFront = 0L
    /** A chart somebody opened waits about a second. */
    private const val GAP_FRONT = 900L
    /** The background balance curve waits much longer and yields to the foreground. */
    private const val GAP_BACK = 3600L

    /**
     * Two lanes: foreground (an opened chart) and background (the balance curve). With one gap
     * an opened chart queued behind the curve's six requests. Background keeps a wider gap and
     * stands down for 3 s after any foreground request.
     */
    private fun pace(bg: Boolean) {
        // Book the slot inside the lock, sleep outside it: sleeping with the lock held made the
        // foreground wait out the background's 3-6 s sleeps.
        val wait: Long
        synchronized(gate) {
            val now = System.currentTimeMillis()
            var w = (if (bg) GAP_BACK else GAP_FRONT) - (now - lastCall)
            if (bg) w = maxOf(w, 3000L - (now - lastFront))
            wait = maxOf(w, 0L)
            lastCall = now + wait
            if (!bg) lastFront = lastCall
        }
        if (wait > 0) runCatching { Thread.sleep(wait) }
    }

    private fun get(url: String, bg: Boolean = false): JSONObject? {
        // Retry once on 429: the limit clears, and an empty answer here is a missing chart,
        // which looks broken.
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
            // 6 s, not 1.5 s: measured, a 429 lasts tens of seconds and a quick retry got another.
            if (attempt == 0) runCatching { Thread.sleep(6000) }
        }
        return null
    }
}
