package com.clearsign.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A second opinion on which coins are worth a look. Jupiter alone decided what exists
 * and what is interesting: one vendor, one list to game. GeckoTerminal ranks pools by
 * what moves through them, free, no key. Only mints come back, and they go through
 * [JupiterTokens.byMints] so they reach the gates in the same shape as everything else:
 * a new source widens the field, never walks around the checks. Blocking, IO, failure is
 * an empty list.
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
     * How far back a chart looks. Each span is a real GeckoTerminal bucket, not a label
     * over the same data: drawing every tenth one-minute candle lies about resolution.
     * [cgDays] is what to ask CoinGecko when the coin has no pool; null where no honest
     * range matches.
     */
    enum class Span(val path: String, val aggregate: Int, val limit: Int, val labelRes: Int, val cgDays: Int?) {
        MINUTES("minute", 5, 60, R.string.span_minutes, null),  // five hours, five-minute candles
        HOURS("hour", 1, 48, R.string.span_hours, 2),           // two days, hourly
        WEEK("hour", 1, 168, R.string.span_week, 7),            // a week, hourly
        MONTH("hour", 4, 180, R.string.span_month, 30),         // a month, four-hourly
        DAYS("day", 1, 90, R.string.span_days, 90),             // three months, daily
        YEAR("day", 1, 365, R.string.span_year, 365);           // a year, daily

        companion object {
            /**
             * The three that fit under a button. A swap or order sheet has room for a glance, not
             * a timeframe picker; the coin page, where people go to look at the chart, gets them all.
             */
            val small = listOf(MINUTES, HOURS, DAYS)
        }
    }

    /**
     * The series, kept per coin. There was no cache, and it did not show while one chart
     * was open; a feed where every row opens a chart would fire two uncached calls per row
     * at a keyless public API and get everybody rate limited.
     */
    private const val FRESH_MS = 30 * 60_000L
    private val seriesCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Candle>>>()
    /**
     * Which pool a coin is read from, written down for good. The busiest pool does not
     * change minute to minute, and finding it costs a request to a source that refuses
     * after five. In memory only, it was looked up again after every restart, and the
     * second request was the refused one: a coin just called unchartable had twenty pools.
     */
    private val poolCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    private const val POOL_MS = 7 * 24 * 3600_000L
    @Volatile private var poolsLoaded = false

    /** Give it a context once and the pool map survives the app being closed. */
    fun warmPools(ctx: android.content.Context) {
        if (poolsLoaded) return
        poolsLoaded = true
        // The name carries a number because the pick rule changed: the old file holds
        // pools chosen by depth, some dead, good for another week. Thrown away, start over.
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
        // An empty answer is not cached: the pool may simply be a minute too young,
        // and a coin bought right now is exactly the one somebody wants to see.
        if (v.isNotEmpty()) seriesCache[key] = now to v
        return v
    }

    /**
     * The busiest pool for a coin and what goes through it. The call that finds the pool
     * already says how deep it is, today's volume, buys against sells; it was thrown away
     * and the coin page showed a price standing on nothing. Figures are minutes-fresh and
     * the id is not, so they cache apart: the id a week on disk, the figures three minutes.
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
     * One question per coin, even when two ask. Opening a sheet starts the figures and the
     * chart together, and the chart asks here again for its pool; the cache fills at the
     * end, so two identical requests went out a second apart. The second now waits at the
     * door and finds the answer written.
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
     * Of all a coin's pools, the one where it actually trades. The deepest was taken, and
     * depth says nothing about buying: measured on EDEL, 20 Sep 2026, the deepest pool had
     * 179k $ sitting still, zero trades, last candle from May at 0.0062 $; the traded one
     * 127 $ deep, 14k volume, coin at 0.0230 $. From outside it looked like another coin.
     * So: by volume, with a floor of a fiftieth of the deepest pool's depth, or two dollars
     * and much volume is two wallets passing a ball. Nothing traded: the deepest stays.
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

    /**
     * One candle with its hour. The timestamp used to be thrown away, and it is what turns
     * a price line into a story: knowing when somebody bought puts a mark on the line, and
     * the price under it is read off the chart, never off a claim nobody published.
     */
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
     * [token] is the mint whose price you want; always pass it when known. A pool has two
     * coins and without it the API answers with the first: measured 20 Sep 2026 on SOL,
     * the deepest pool is "WOTF / SOL" and the Solana chart was WOTF's. Not a visible
     * error, just the wrong coin. With the mint the same pool says 111 $, a SOL's price.
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
            // The close was all anybody kept, because all anybody drew was a
            // line. A candle needs the other three, and the bar under it needs
            // the volume: the same request already carried them.
            fun at(k: Int, fallback: Double) = row.optDouble(k).takeIf { !it.isNaN() && it > 0 } ?: fallback
            out += Candle(t * 1000L, at(1, c), at(2, c), at(3, c), c, row.optDouble(5).takeIf { !it.isNaN() && it > 0 } ?: 0.0)
        }
        // The API answers newest first; a chart reads left to right.
        return out.asReversed()
    }

    /**
     * One request at a time, not too close together. Measured: five calls in a row and it
     * answers 429 and stays refused for a while. Two callers ask at once, a chart someone
     * opened and the balance curve behind the home buttons, so without a gate the background
     * spends the allowance and the person's chart gets the refusal. A lock and a gap, on IO.
     */
    private val gate = Any()
    @Volatile private var lastCall = 0L
    @Volatile private var lastFront = 0L
    /** A chart somebody opened waits about a second. */
    private const val GAP_FRONT = 900L
    /** The curve filling itself in behind the buttons waits much longer, and gets out of the way. */
    private const val GAP_BACK = 3600L

    /**
     * Two lanes, because one caller has somebody watching it. One gap for all made the curve
     * and an opened chart equally slow, and the curve starts first: tap a row and you queue
     * behind six of its requests. Background keeps a wider gap and stands down three seconds
     * after anything in front asked.
     */
    private fun pace(bg: Boolean) {
        // The slot is booked inside the lock, the wait happens outside. Sleeping with the lock
        // held, the background curve kept it three seconds and six: whoever opened a coin waited
        // to learn when they could start waiting.
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
            // Six seconds, not one and a half. Measured: a refusal lasts tens of seconds,
            // so retrying at once only got a second no.
            if (attempt == 0) runCatching { Thread.sleep(6000) }
        }
        return null
    }
}
