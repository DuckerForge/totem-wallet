package com.clearsign.app

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * What a coin *is*, and how it has moved over more than a day.
 *
 * Jupiter's registry answers "is this tradeable and is it a trap": price,
 * liquidity, holders, authorities. It does not answer "what am I buying", and
 * an agent proposing a purchase with no idea what the thing does is guessing
 * with a straight face. This fills that gap: a sentence about the project, its
 * market cap rank, and the week, month and year behind the last 24 hours.
 *
 * Keyless. The public tier needs no account and no card, which is the whole
 * reason it is worth using here. It also rate-limits by IP and answers 429 when
 * pushed, so every result is cached for an hour and every failure is silent: a
 * missing description is a description we do not have, never a reason to stop.
 *
 * No key at all today. A free demo key (no card) would lift the limit to ten
 * thousand calls a month at a hundred a minute, and the only change needed is
 * an `x-cg-demo-api-key` header in [get].
 */
object CoinGecko {
    private const val TAG = "Apex-Gecko"
    private const val BASE = "https://api.coingecko.com/api/v3"
    private const val TTL_MS = 60L * 60 * 1000

    /**
     * [changePct] is keyed by window: "24h", "7d", "30d", "1y". Anything the API
     * did not return is simply absent, so a caller can say "this is new" instead
     * of printing a zero that looks like a flat week.
     */
    data class Coin(
        val name: String,
        val rank: Int?,
        val marketCapUsd: Double?,
        val categories: List<String>,
        val about: String?,
        val changePct: Map<String, Double>,
    )

    private class Entry(val coin: Coin?, val at: Long)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    /** By Solana mint address. Null when unknown, unreachable or rate-limited. Blocking: call on IO. */
    fun byMint(mint: String): Coin? {
        cache[mint]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.let { return it.coin }
        val o = get("/coins/solana/contract/$mint?localization=false&tickers=false&community_data=false&developer_data=false")
        val coin = o?.let(::parse)
        // A miss is cached too: without that, a coin CoinGecko has never listed
        // costs a round trip on every single scan.
        cache[mint] = Entry(coin, System.currentTimeMillis())
        return coin
    }

    private fun parse(o: JSONObject): Coin? {
        if (o.has("error") || o.has("status")) return null
        val name = o.optString("name").takeIf { it.isNotEmpty() } ?: return null
        val m = o.optJSONObject("market_data")
        fun pct(k: String) = m?.optDouble(k)?.takeIf { !it.isNaN() }
        val changes = buildMap {
            pct("price_change_percentage_24h")?.let { put("24h", it) }
            pct("price_change_percentage_7d")?.let { put("7d", it) }
            pct("price_change_percentage_30d")?.let { put("30d", it) }
            pct("price_change_percentage_1y")?.let { put("1y", it) }
        }
        val cats = o.optJSONArray("categories")?.let { a ->
            (0 until a.length()).mapNotNull { a.optString(it).takeIf { c -> c.isNotEmpty() && c != "null" } }
        } ?: emptyList()
        // The listing blurb runs to pages of marketing. Two sentences is what a
        // person is told out loud, and all the model needs to say what it is.
        val about = o.optJSONObject("description")?.optString("en")?.takeIf { it.isNotBlank() }
            ?.replace(Regex("<[^>]*>"), "")
            ?.split(Regex("(?<=\\.)\\s+"))?.take(2)?.joinToString(" ")?.trim()?.take(280)
        return Coin(
            name = name,
            rank = o.optInt("market_cap_rank").takeIf { it > 0 },
            marketCapUsd = m?.optJSONObject("market_cap")?.optDouble("usd")?.takeIf { !it.isNaN() },
            categories = cats.take(4),
            about = about,
            changePct = changes,
        )
    }

    private fun get(path: String): JSONObject? = try {
        val c = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 12000
            setRequestProperty("Accept", "application/json")
        }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect()
        if (code == 429) Log.w(TAG, "rate limited")
        body?.takeIf { code in 200..299 }?.let { JSONObject(it) }
    } catch (e: Exception) {
        Log.w(TAG, "GET failed: ${e.message}"); null
    }
}
