package com.clearsign.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Every coin there is, ranked, the way a person expects a market screen to work.
 *
 * The wallet's own lists answer "what can this wallet trade": Jupiter's registry,
 * Solana only, ordered by things a trader cares about. That is the wrong list for
 * the question "how is bitcoin doing", and it is the wrong list for following a
 * coin you hold somewhere else. This is the other list — CoinGecko's, free and
 * without a key, ranked by market capitalisation, with every chain in it.
 *
 * What comes back knows whether a coin also lives on Solana ([Coin.mint]). That
 * one field is the difference between a row you can buy from and a row you can
 * only watch, and the screen says which is which rather than offering a button
 * that cannot work.
 */
object Market {
    private const val TAG = "Apex-Market"
    private const val BASE = "https://api.coingecko.com/api/v3"
    private const val PAGE = 100
    private const val TTL_MS = 120_000L

    data class Coin(
        val id: String,
        val symbol: String,
        val name: String,
        val image: String?,
        val priceUsd: Double?,
        val marketCap: Double?,
        val rank: Int?,
        val change24h: Double?,
        /** The Solana mint, when this coin has one. Null means watch only. */
        val mint: String? = null,
    ) {
        /** How the watchlist and the manual amounts address it: a mint if it has one. */
        val key: String get() = mint ?: "cg:$id"
    }

    @Volatile private var top: List<Coin> = emptyList()
    @Volatile private var topAt = 0L
    private val mints = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * What is already in memory, and never a network call.
     *
     * For anything drawn on screen: [top] blocks, and a composable that blocks on
     * a cold cache freezes the frame it is drawing. The market screen fills this
     * when it opens, so by the time a coin sheet needs it, it is there.
     */
    fun cachedTop(): List<Coin> = top

    /** The first [PAGE] by market cap. Cached for two minutes: this is a free API. */
    fun top(force: Boolean = false): List<Coin> {
        val now = System.currentTimeMillis()
        if (!force && top.isNotEmpty() && now - topAt < TTL_MS) return top
        val arr = getArray("$BASE/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=$PAGE&page=1&price_change_percentage=24h")
            ?: return top
        top = parse(arr)
        topAt = now
        return top
    }

    fun toJson(c: Coin): String = org.json.JSONObject().put("id", c.id).put("symbol", c.symbol).put("name", c.name).put("image", c.image)
        .put("price", c.priceUsd).put("mcap", c.marketCap).put("rank", c.rank).put("ch", c.change24h).put("mint", c.mint).toString()

    fun fromJson(s: String): Coin? = runCatching {
        val o = org.json.JSONObject(s)
        Coin(
            o.getString("id"), o.getString("symbol"), o.getString("name"), o.optString("image").takeIf { it.isNotEmpty() },
            o.optDouble("price").takeIf { !it.isNaN() }, o.optDouble("mcap").takeIf { !it.isNaN() },
            o.optInt("rank").takeIf { it > 0 }, o.optDouble("ch").takeIf { !it.isNaN() }, o.optString("mint").takeIf { it.isNotEmpty() },
        )
    }.getOrNull()

    /** One coin with its price and market cap, whether or not the ranked page carries it. */
    fun byId(id: String): Coin? {
        top.firstOrNull { it.id == id && it.marketCap != null }?.let { return it }
        val arr = getArray("$BASE/coins/markets?vs_currency=usd&ids=" + URLEncoder.encode(id, "UTF-8") + "&price_change_percentage=24h") ?: return null
        return parse(arr).firstOrNull()
    }

    /** Free text across every coin, not only the ranked page. */
    fun search(query: String): List<Coin> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val o = getObject("$BASE/search?query=" + URLEncoder.encode(q, "UTF-8")) ?: return emptyList()
        val arr = o.optJSONArray("coins") ?: return emptyList()
        // The search endpoint has no prices, so the rows come back thin and the
        // screen fills them from [top] when the coin is in it. Asking for prices
        // here would be one call per keystroke on an API with no key.
        val out = ArrayList<Coin>(arr.length())
        for (i in 0 until minOf(25, arr.length())) {
            val c = arr.optJSONObject(i) ?: continue
            val id = c.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val ranked = top.firstOrNull { it.id == id }
            out += ranked ?: Coin(
                id = id,
                symbol = c.optString("symbol").uppercase(),
                name = c.optString("name").ifEmpty { id },
                image = c.optString("thumb").takeIf { it.isNotEmpty() },
                priceUsd = null, marketCap = null,
                rank = c.optInt("market_cap_rank").takeIf { it > 0 },
                change24h = null,
            )
        }
        return out
    }

    /**
     * The Solana mint for a coin, when it has one. One extra call, remembered for
     * the life of the process, and only made when somebody actually opens a coin.
     */
    fun mintOf(id: String): String? {
        mints[id]?.let { return it.takeIf { m -> m.isNotEmpty() } }
        val o = getObject("$BASE/coins/$id?localization=false&tickers=false&market_data=false&community_data=false&developer_data=false&sparkline=false")
        val mint = o?.optJSONObject("platforms")?.optString("solana").orEmpty()
        mints[id] = mint
        return mint.takeIf { it.isNotEmpty() }
    }

    /**
     * Coins that do not live on Solana but have an official bridged form there:
     * the same asset, held by a custodian or a bridge, tradable on Jupiter. Only
     * the versions Jupiter marks verified and that carry real liquidity
     * (checked on the 15th of September 2026): the search is full of copies.
     */
    data class Bridged(val label: String, val mint: String)
    val bridged: Map<String, List<Bridged>> = mapOf(
        "bitcoin" to listOf(
            Bridged("WBTC (Portal)", "3NZ9JMVBmGAqocybic2c7LQCJScmgsAZ6vQqTDzcqmJh"),
            Bridged("cbBTC (Coinbase)", "cbbtcf3aa214zXHbiAZQwf4122FBYbraNdFqgw4iMij"),
        ),
        "ethereum" to listOf(Bridged("ETH (Portal)", "7vfCXTUXx5WJV5JADk17DUJ4ksgau7utNKj4b963voxs")),
    )

    /** Prices for coins already known by id, for the followed list. */
    fun pricesFor(ids: Collection<String>): Map<String, Coin> {
        if (ids.isEmpty()) return emptyMap()
        val arr = getArray("$BASE/coins/markets?vs_currency=usd&ids=" + ids.joinToString(",") + "&price_change_percentage=24h")
            ?: return emptyMap()
        return parse(arr).associateBy { it.id }
    }

    private fun parse(arr: JSONArray): List<Coin> {
        val out = ArrayList<Coin>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: continue
            out += Coin(
                id = id,
                symbol = o.optString("symbol").uppercase(),
                name = o.optString("name").ifEmpty { id },
                image = o.optString("image").takeIf { it.isNotEmpty() },
                priceUsd = o.optDouble("current_price").takeIf { !it.isNaN() },
                marketCap = o.optDouble("market_cap").takeIf { !it.isNaN() },
                rank = o.optInt("market_cap_rank").takeIf { it > 0 },
                change24h = o.optDouble("price_change_percentage_24h").takeIf { !it.isNaN() },
                mint = mints[id]?.takeIf { it.isNotEmpty() },
            )
        }
        return out
    }

    private fun getArray(url: String): JSONArray? = fetch(url)?.let { runCatching { JSONArray(it) }.getOrNull() }
    private fun getObject(url: String): JSONObject? = fetch(url)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun fetch(url: String): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 12000
            setRequestProperty("Accept", "application/json")
        }
        val code = c.responseCode
        val body = if (code in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        if (code == 429) Log.w(TAG, "rate limited")
        c.disconnect()
        body
    } catch (e: Exception) {
        Log.w(TAG, "GET failed: ${e.message}"); null
    }
}
