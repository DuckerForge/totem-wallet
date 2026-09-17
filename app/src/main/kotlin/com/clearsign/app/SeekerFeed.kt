package com.clearsign.app

import android.content.Context
import android.util.Log
import com.clearsign.core.CrowdRank
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The published ranking, read rather than computed.
 *
 * One scanner runs somewhere and writes a few kilobytes; every phone reads it.
 * The alternative — each phone sweeping ten thousand wallets on its own — costs
 * the same credits once per user, which is affordable for one person and
 * impossible for a thousand. Scanning locally stays as the fallback, and is
 * honestly labelled as such.
 *
 * A stale file is still worth showing. A ranking twenty minutes old is a fact;
 * an empty card because the network blinked is not.
 */
object SeekerFeed {
    private const val TAG = "ClearSign-Seeker"
    private const val CACHE = "seeker_feed.json"
    /**
     * Two and a half minutes, which is shorter than the scan it follows.
     *
     * The service publishes every ten minutes. Holding the cached file for
     * longer than that would mean a new publish sits unseen for a whole cycle,
     * and the live feed would look frozen to somebody watching it. Shorter than
     * the publish interval costs one request and keeps the page honest: what it
     * shows is never more than a couple of minutes behind what exists.
     */
    private const val FRESH_MS = 150_000L

    val available: Boolean get() = BuildConfig.CROWD_URL.isNotBlank()

    data class Feed(val at: Long, val followed: Int, val rows: List<CrowdRank>, val events: List<com.clearsign.core.CrowdBuy> = emptyList())

    /** The cached copy, without touching the network. */
    fun cached(ctx: Context): Feed? = runCatching {
        val f = File(ctx.filesDir, CACHE)
        if (!f.exists()) null else parse(JSONObject(f.readText()))
    }.getOrNull()

    /** Fetch when the cached copy has aged out; the cached copy otherwise. */
    fun refresh(ctx: Context): Feed? {
        if (!available) return null
        val f = File(ctx.filesDir, CACHE)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < FRESH_MS) return cached(ctx)
        val body = get(BuildConfig.CROWD_URL) ?: return cached(ctx)
        // Names before parsing: the feed publishes mints, and a mint on screen is
        // an address nobody reads. One search per fifty, then every row has a name.
        runCatching { warmNames(JSONObject(body)) }
        val parsed = runCatching { parse(JSONObject(body)) }.getOrNull() ?: return cached(ctx)
        runCatching { f.writeText(body) }
        return parsed
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
