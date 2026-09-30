package com.clearsign.app

import android.content.Context
import android.util.Log
import com.clearsign.core.CrowdRank
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The published ranking, read rather than computed: one scanner writes a few KB and every
 * phone reads it, since each phone sweeping 10k wallets would cost the same credits per user.
 * Local scanning stays as the labeled fallback. A stale file is still shown: a ranking twenty
 * minutes old beats an empty card on a network blip.
 */
object SeekerFeed {
    private const val TAG = "ClearSign-Seeker"
    private const val CACHE = "seeker_feed.json"
    /**
     * 2.5 min, well under the 10 min publish cycle, so a new publish is never missed for a whole
     * cycle and the page is at most a couple of minutes behind.
     */
    private const val FRESH_MS = 150_000L

    /**
     * Freshness for the background loop, which hunts every six minutes and gets the crowd signal
     * late by design anyway. 240 reads a day per phone become 96 (1.5 GB a day at 10k phones).
     */
    const val SLOW_FRESH_MS = 900_000L

    val available: Boolean get() = BuildConfig.CROWD_URL.isNotBlank() || BuildConfig.ARCHIVE_URL.isNotBlank()

    data class Feed(val at: Long, val followed: Int, val rows: List<CrowdRank>, val events: List<com.clearsign.core.CrowdBuy> = emptyList())

    /** The cached copy, without touching the network. */
    fun cached(ctx: Context): Feed? = runCatching {
        val f = File(ctx.filesDir, CACHE)
        if (!f.exists()) null else parse(JSONObject(f.readText()))
    }.getOrNull()

    /** Fetch when the cached copy has aged out; the cached copy otherwise. */
    fun refresh(ctx: Context, freshMs: Long = FRESH_MS): Feed? {
        if (!available) return null
        val f = File(ctx.filesDir, CACHE)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < freshMs) return cached(ctx)
        // The archive first, the service second. Same body: the worker writes both on every publish.
        // The difference is who pays: an archive read is not a worker invocation, and Cloudflare's
        // free plan counts invocations.
        val body = archive() ?: get(BuildConfig.CROWD_URL) ?: return cached(ctx)
        // Names before parsing: the feed publishes mints, and a mint on screen is
        // an address nobody reads. One search per fifty, then every row has a name.
        runCatching { warmNames(JSONObject(body)) }
        val parsed = runCatching { parse(JSONObject(body)) }.getOrNull() ?: return cached(ctx)
        runCatching { f.writeText(body) }
        return parsed
    }

    /** The ranking as the worker writes it, from the archive, no key. */
    private fun archive(): String? {
        val base = BuildConfig.ARCHIVE_URL.takeIf { it.isNotBlank() } ?: return null
        return get(base.trimEnd('/') + "/clearsign/crowd.json")
    }

    private fun warmNames(o: JSONObject) {
        val mints = HashSet<String>()
        o.optJSONArray("rows")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("mint")?.let { mints += it } }
        o.optJSONArray("events")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("m")?.let { mints += it } }
        JupiterTokens.warm(mints)
    }

    private fun parse(o: JSONObject): Feed {
        val a = o.optJSONArray("rows")
        val rows = ArrayList<CrowdRank>(a?.length() ?: 0)
        for (i in 0 until (a?.length() ?: 0)) {
            val r = a!!.optJSONObject(i) ?: continue
            val mint = r.optString("mint").ifEmpty { continue }
            rows += CrowdRank(
                mint = mint,
                // The scanner publishes mints, not names: a symbol is a local
                // lookup and shipping one from the server would only let it go
                // stale in a second place.
                symbol = JupiterTokens.cached(mint)?.symbol
                    ?: r.optString("sym").ifEmpty { mint.take(6) },
                wallets = r.optInt("wallets"), whales = r.optInt("whales"),
                buys = r.optInt("buys"), solSpent = r.optDouble("sol", 0.0),
                firstAt = r.optLong("last"), lastAt = r.optLong("last"),
                score = r.optDouble("score", 0.0),
            )
        }
        val ev = o.optJSONArray("events")
        val events = ArrayList<com.clearsign.core.CrowdBuy>(ev?.length() ?: 0)
        for (i in 0 until (ev?.length() ?: 0)) {
            val e = ev!!.optJSONObject(i) ?: continue
            val mint = e.optString("m").ifEmpty { continue }
            events += com.clearsign.core.CrowdBuy(
                wallet = e.optString("w"),
                tier = if (e.optString("t") == "b") com.clearsign.core.SeekerTier.WHALE else com.clearsign.core.SeekerTier.DOLPHIN,
                mint = mint,
                symbol = JupiterTokens.cached(mint)?.symbol ?: mint.take(6),
                at = e.optLong("at"), solSpent = e.optDouble("sol", 0.0),
                sell = e.optInt("s", 0) == 1,
            )
        }
        return Feed(o.optLong("at"), o.optInt("followed"), rows, events)
    }

    private fun get(url: String): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 12000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "gzip")
        }
        val ok = c.responseCode in 200..299
        val raw = if (ok) c.inputStream else c.errorStream
        val text = (if (c.contentEncoding.equals("gzip", true)) java.util.zip.GZIPInputStream(raw) else raw)
            ?.bufferedReader()?.use { it.readText() }
        c.disconnect()
        if (ok) text else null
    } catch (e: Exception) { Log.w(TAG, "feed: ${e.message}"); null }
}
