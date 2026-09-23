package com.clearsign.app

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The mood of the market, from three free keyless sources.
 *
 * Ported from the same idea in the MEGAGEN app, and with its warning kept: **none
 * of this predicts a price.** It is sentiment and momentum, useful as a gate —
 * "do not go hunting while everything is bleeding" — and useless as a forecast.
 *
 *  * Fear & Greed (alternative.me): one 0..100 number for how the crowd feels.
 *  * CoinGecko /global: how the whole crypto market moved in 24h, and BTC dominance.
 *  * S&P and Nasdaq futures (Yahoo): whether traditional money is risk-on right now.
 *
 * The last one is the interesting one. Crypto tends to follow the futures, so the
 * cross between them is worth more than either alone: futures up while crypto is
 * still down reads differently from both falling together.
 */
object MarketMood {
    private const val TAG = "ClearSign-Mood"
    private const val TTL_MS = 10L * 60 * 1000

    enum class Cross {
        /** Futures up, crypto still down: traditional money turned before crypto did. */
        REVERSAL_UP,
        /** Both up. */
        CONFIRM_UP,
        /** Futures down, crypto still up: the rally is not being backed. */
        FADE_DOWN,
        /** Both down. */
        PAIN_DOWN,
        /** Not enough data to say anything. */
        UNKNOWN,
    }

    data class Mood(
        val fearGreed: Int?,
        val fearGreedLabel: String?,
        val cryptoChange24h: Double?,
        val btcDominance: Double?,
        val futuresPct: Double?,
        val at: Long,
    ) {
        val cross: Cross get() {
            val f = futuresPct ?: return Cross.UNKNOWN
            val c = cryptoChange24h ?: return Cross.UNKNOWN
            return when {
                f > 0.1 && c < 0 -> Cross.REVERSAL_UP
                f > 0.1 && c >= 0 -> Cross.CONFIRM_UP
                f < -0.1 && c > 0 -> Cross.FADE_DOWN
                else -> Cross.PAIN_DOWN
            }
        }

        /** Green enough to go looking for something new. A gate, not a signal to buy. */
        val riskOn: Boolean
            get() = (fearGreed ?: 50) >= 35 && (cryptoChange24h ?: 0.0) > -6.0 &&
                (cross == Cross.CONFIRM_UP || cross == Cross.REVERSAL_UP || futuresPct == null)
    }

    @Volatile private var cached: Mood? = null

    /** Blocking: call it on IO. Serves a reading up to ten minutes old. */
    fun read(): Mood {
        cached?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.let { return it }
        var fg: Int? = null
        var fgLabel: String? = null
        getJson("https://api.alternative.me/fng/?limit=1")?.optJSONArray("data")?.optJSONObject(0)?.let { o ->
            fg = o.optString("value").toIntOrNull()
            fgLabel = o.optString("value_classification").takeIf { it.isNotEmpty() }
        }
        var change: Double? = null
        var dom: Double? = null
        getJson("https://api.coingecko.com/api/v3/global")?.optJSONObject("data")?.let { d ->
            change = d.optDouble("market_cap_change_percentage_24h_usd").takeIf { !it.isNaN() }
            dom = d.optJSONObject("market_cap_percentage")?.optDouble("btc")?.takeIf { !it.isNaN() }
        }
        // Two contracts, averaged: one of them 429s often enough to matter.
        val futures = listOf("ES=F", "NQ=F").mapNotNull { futuresPct(it) }
        val mood = Mood(fg, fgLabel, change, dom, futures.average().takeIf { futures.isNotEmpty() }, System.currentTimeMillis())
        cached = mood
        return mood
    }

    private fun futuresPct(symbol: String): Double? {
        for (host in listOf("query1", "query2")) {
            val o = getJson("https://$host.finance.yahoo.com/v8/finance/chart/$symbol?interval=5m&range=1d") ?: continue
            val meta = o.optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0)?.optJSONObject("meta") ?: continue
            val now = meta.optDouble("regularMarketPrice").takeIf { !it.isNaN() && it > 0 } ?: continue
            val prev = (meta.optDouble("chartPreviousClose").takeIf { !it.isNaN() && it > 0 }
                ?: meta.optDouble("previousClose").takeIf { !it.isNaN() && it > 0 }) ?: continue
            return (now / prev - 1.0) * 100.0
        }
        return null
    }

    private fun getJson(url: String): JSONObject? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 10000
            setRequestProperty("Accept", "application/json")
            // Yahoo answers 403 to a bare client.
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Velum")
        }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect()
        if (code in 200..299) body?.let { JSONObject(it) } else null
    } catch (e: Exception) { Log.w(TAG, "mood: ${e.message}"); null }
}
