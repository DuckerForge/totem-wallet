package com.clearsign.app

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * All coins by market cap, every chain, from CoinGecko (free, no key). Jupiter's lists only
 * cover what this wallet can trade. [Coin.mint] is set when a coin also lives on Solana,
 * i.e. it can be bought here, not just watched.
 */
object Market {
    private const val TAG = "Apex-Market"
    private const val BASE = "https://api.coingecko.com/api/v3"
    private const val PAGE = 100
    private const val TTL_MS = 120_000L

    @androidx.compose.runtime.Immutable
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
        /** What changed hands in a day, in dollars. The number a chart is read against. */
        val volume24h: Double? = null,
    ) {
        /** How the watchlist and the manual amounts address it: a mint if it has one. */
        val key: String get() = mint ?: "cg:$id"
    }

    @Volatile private var top: List<Coin> = emptyList()
    @Volatile private var topAt = 0L
    private val mints = java.util.concurrent.ConcurrentHashMap<String, String>(
        // SOL has no entry in CoinGecko's platform map, so the lookup comes back empty.
        // Wrapped SOL is the mint pools and quotes use.
        mapOf("solana" to "So11111111111111111111111111111111111111112"),
    )

    /** Memory only, no network: [top] blocks and would freeze a composable. The screen fills it on open. */
    fun cachedTop(): List<Coin> = top

    /**
     * Last list saved to disk, shown on a cold start until the fresh one lands.
     * Kept six hours at most: stale prices shown as current are worse than a short wait.
     * `topAt` gets the file's age, so the two minute TTL still forces a refresh.
     */
    private const val DISK_MS = 6 * 3600_000L
    private const val FILE = "market_top.json"
    @Volatile private var file: java.io.File? = null

    /** Where the list on screen came from, null for CoinGecko. CoinGecko 403s whole networks. */
    enum class Source { SAVED, JUPITER }
    // Snapshot state: a failed refresh changes only these, and the screen must still redraw.
    var source: Source? by mutableStateOf<Source?>(null)
        private set
    /** When the list on screen was priced: for [Source.SAVED], when it was saved. */
    var savedAt: Long by mutableStateOf(0L)
        private set
    @Volatile private var app: android.content.Context? = null

    fun warm(ctx: android.content.Context) {
        app = ctx.applicationContext
        val f = java.io.File(ctx.filesDir, FILE).also { file = it }
        if (top.isNotEmpty() || !f.exists()) return
        val age = System.currentTimeMillis() - f.lastModified()
        if (age > DISK_MS) return
        runCatching {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Coin>(arr.length())
            for (i in 0 until arr.length()) fromJson(arr.optString(i))?.let { out += it }
            if (out.isNotEmpty()) { top = out; topAt = f.lastModified() }
        }
    }

    private fun save() {
        // Only CoinGecko's own list: a saved one written back would look new at the next start,
        // and Jupiter's is not the ranked list.
        if (source != null) return
        val f = file ?: return
        runCatching { f.writeText(JSONArray().apply { top.forEach { put(toJson(it)) } }.toString()) }
    }

    /**
     * Appends the next hundred by market cap, de-duplicated by id. A refresh of page one
     * replaces the head and keeps what was loaded below it.
     */
    fun more(): List<Coin> {
        // Under a saved or Jupiter list, page two would be glued to a head of another age.
        if (source != null) return top
        val page = top.size / PAGE + 1
        if (page > MAX_PAGES) return top
        val arr = getArray("$BASE/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=$PAGE&page=$page&price_change_percentage=24h")
            ?: return top
        val next = parse(arr)
        if (next.isEmpty()) return top
        val seen = top.mapTo(HashSet()) { it.id }
        top = top + next.filter { seen.add(it.id) }
        save()
        return top
    }

    /** Ten pages is a thousand coins, which is further down than anyone scrolls on a phone. */
    private const val MAX_PAGES = 10

    /** When the prices on screen were last fetched, whoever gave them. */
    @Volatile private var pricedAt = 0L

    /** The first [PAGE] by market cap. Cached for two minutes: this is a free API. */
    fun top(force: Boolean = false): List<Coin> {
        val now = System.currentTimeMillis()
        if (!force && top.isNotEmpty() && now - topAt < TTL_MS) return top
        // Archive first: the worker polls CoinGecko every ten minutes for all phones.
        // Ask CoinGecko directly only when the archive is missing or stale.
        val head = archived()?.let { parse(it) }?.takeIf { it.isNotEmpty() }
            ?: getArray("$BASE/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=$PAGE&page=1&price_change_percentage=24h")?.let { parse(it) }?.takeIf { it.isNotEmpty() }
            ?: paprika()?.let { parse(it) }.orEmpty()
        if (head.isEmpty()) {
            // No ranked list from anyone. Jupiter rows on screen: reprice them, else mark saved.
            // Any other list stays, marked saved. Nothing on screen: the saved list at any age,
            // else Jupiter's. Jupiter rows (id == mint) are retried even once marked saved.
            val jupiterRows = top.firstOrNull()?.let { it.id == it.mint } == true
            when {
                source == Source.JUPITER || jupiterRows -> {
                    val again = fromJupiter()
                    if (again.isNotEmpty()) { top = again; pricedAt = now }
                    else if (source != Source.SAVED) { source = Source.SAVED; savedAt = pricedAt.takeIf { it > 0 } ?: topAt }
                }
                top.isNotEmpty() -> if (source == null) { source = Source.SAVED; savedAt = pricedAt.takeIf { it > 0 } ?: topAt }
                else -> top = saved().ifEmpty { fromJupiter().also { if (it.isNotEmpty()) pricedAt = now } }
            }
            topAt = now
            return top
        }
        // Refresh page one in place and keep the tail below it, except under a saved or Jupiter
        // list, where the tail would be stale with nothing marking it.
        val keepTail = source == null
        source = null
        pricedAt = now
        val fresh = head.mapTo(HashSet()) { it.id }
        top = if (keepTail) head + top.drop(head.size).filterNot { it.id in fresh } else head
        topAt = now
        save()
        return top
    }

    /** The same CoinGecko page, fetched by the worker for everyone and kept in the archive. */
    private fun archived(): JSONArray? {
        val o = Archive.read("market", 2 * 60_000L) ?: return null
        if (System.currentTimeMillis() - o.optLong("at", 0L) > 25 * 60_000L) return null
        return o.optJSONArray("coins")?.takeIf { it.length() > 0 }
    }

    /**
     * CoinPaprika's top hundred in CoinGecko's shape, for networks CoinGecko blocks. Called from the
     * phone: the shared quota from Cloudflare is spent (29 Sep 2026). Ids are mapped to CoinGecko's
     * so the mint map works. Its images aren't served to apps, so [parse] reuses a known logo.
     */
    private fun paprika(): JSONArray? {
        val arr = fetch("https://api.coinpaprika.com/v1/tickers?limit=$PAGE")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return null
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val slug = c.optString("id").substringAfter('-')
            val q = c.optJSONObject("quotes")?.optJSONObject("USD")
            if (slug.isEmpty() || q == null) continue
            out.put(JSONObject()
                .put("id", PAPRIKA_TO_GECKO[slug] ?: slug)
                .put("symbol", c.optString("symbol").lowercase())
                .put("name", c.optString("name").ifEmpty { slug })
                .put("current_price", q.opt("price"))
                .put("market_cap", q.opt("market_cap"))
                .put("market_cap_rank", c.optInt("rank"))
                .put("price_change_percentage_24h", q.opt("percent_change_24h"))
                .put("total_volume", q.opt("volume_24h")))
        }
        return out.takeIf { it.length() > 0 }
    }

    /** CoinPaprika's slug to CoinGecko's id, for the top coins whose two names differ. */
    private val PAPRIKA_TO_GECKO = mapOf(
        "binance-coin" to "binancecoin", "xrp" to "ripple", "lido-staked-ether" to "staked-ether",
        "wrapped-liquid-staked-ether-20" to "wrapped-steth", "avalanche" to "avalanche-2", "near-protocol" to "near",
        "cryptocom-chain" to "crypto-com-chain", "polygon" to "matic-network",
    )

    /** The list saved on disk, however old. */
    private fun saved(): List<Coin> {
        val f = file?.takeIf { it.exists() } ?: return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { fromJson(arr.optString(it)) }
        }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) { source = Source.SAVED; savedAt = f.lastModified() } }
    }

    /**
     * Jupiter's popular list as coins, by its market caps. Keyed by mint so charts and buying work;
     * no global rank, no BTC or ETH. Not saved, it isn't the ranked list. The cached list can be a
     * day old and has no caps on disk, so every price is fetched again and unpriced coins dropped.
     */
    private fun fromJupiter(): List<Coin> {
        val ctx = app ?: return emptyList()
        val list = runCatching { JupiterTokens.top(ctx) }.getOrDefault(emptyList())
        if (list.isEmpty()) return emptyList()
        // No cap anywhere means the disk copy: the rows are read again, one call for the hundred.
        val rows = if (list.any { (it.mcap ?: 0.0) > 0 }) list
            else runCatching { JupiterTokens.byMints(list.map { it.mint }) }.getOrDefault(emptyMap()).let { m -> list.map { m[it.mint] ?: it } }
        val px = runCatching { Prices.quotes(rows.map { it.mint }) }.getOrDefault(emptyMap())
        return rows.mapNotNull { t ->
            val q = px[t.mint] ?: return@mapNotNull null
            val m = t.mcap?.takeIf { it > 0 } ?: return@mapNotNull null
            // The cap follows the price it was counted at: same supply, today's price.
            val cap = t.usd?.takeIf { it > 0 }?.let { m * q.usd / it } ?: m
            Coin(t.mint, t.symbol.uppercase(), t.name.ifEmpty { t.symbol }, t.icon, q.usd, cap, null, q.change24h, t.mint)
        }
            .sortedByDescending { it.marketCap }
            .also { if (it.isNotEmpty()) { source = Source.JUPITER; savedAt = System.currentTimeMillis() } }
    }

    fun toJson(c: Coin): String = org.json.JSONObject().put("id", c.id).put("symbol", c.symbol).put("name", c.name).put("image", c.image)
        .put("price", c.priceUsd).put("mcap", c.marketCap).put("rank", c.rank).put("ch", c.change24h).put("mint", c.mint)
        .put("vol", c.volume24h).toString()

    fun fromJson(s: String): Coin? = runCatching {
        val o = org.json.JSONObject(s)
        Coin(
            o.getString("id"), o.getString("symbol"), o.getString("name"), o.optString("image").takeIf { it.isNotEmpty() },
            o.optDouble("price").takeIf { !it.isNaN() }, o.optDouble("mcap").takeIf { !it.isNaN() },
            o.optInt("rank").takeIf { it > 0 }, o.optDouble("ch").takeIf { !it.isNaN() }, o.optString("mint").takeIf { it.isNotEmpty() },
            o.optDouble("vol").takeIf { !it.isNaN() && it > 0 },
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
     * The Solana mint for a coin, when it has one: one extra call, remembered for the process,
     * made only when somebody opens the coin.
     */
    fun mintOf(id: String): String? {
        mints[id]?.let { return it.takeIf { m -> m.isNotEmpty() } }
        val o = getObject("$BASE/coins/$id?localization=false&tickers=false&market_data=false&community_data=false&developer_data=false&sparkline=false")
        val mint = o?.optJSONObject("platforms")?.optString("solana").orEmpty()
        mints[id] = mint
        return mint.takeIf { it.isNotEmpty() }
    }

    /**
     * Coins that do not live on Solana but have an official bridged form there: the same asset
     * held by a custodian or a bridge, tradable on Jupiter. Only versions Jupiter marks verified
     * with real liquidity (checked 15 Sep 2026): the search is full of copies.
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

    /**
     * Candles for coins with no Solana pool (BTC, ETH), since GeckoTerminal only knows pools.
     * CoinGecko picks the candle size from the days asked (1 day: 30 min, a month: 4 h, a year:
     * 4 days), so spans are named by range covered. No volume.
     */
    private val ohlcCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Gecko.Candle>>>()
    private const val OHLC_TTL_MS = 5 * 60_000L

    fun ohlc(id: String, days: Int): List<Gecko.Candle> {
        val k = "$id|$days"
        val now = System.currentTimeMillis()
        ohlcCache[k]?.let { (at, v) -> if (now - at < OHLC_TTL_MS) return v }
        val arr = getArray("$BASE/coins/" + URLEncoder.encode(id, "UTF-8") + "/ohlc?vs_currency=usd&days=$days") ?: return emptyList()
        val out = ArrayList<Gecko.Candle>(arr.length())
        for (i in 0 until arr.length()) {
            // [timestamp, open, high, low, close] — already oldest first.
            val row = arr.optJSONArray(i) ?: continue
            val t = row.optLong(0)
            val c = row.optDouble(4)
            if (t <= 0 || c.isNaN() || c <= 0) continue
            out += Gecko.Candle(t, row.optDouble(1), row.optDouble(2), row.optDouble(3), c, 0.0)
        }
        if (out.isNotEmpty()) ohlcCache[k] = now to out
        return out
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
                // The worker's CoinPaprika list has no images: the logo seen last for the same coin.
                image = o.optString("image").takeIf { it.isNotEmpty() && it != "null" } ?: knownImage(id),
                priceUsd = o.optDouble("current_price").takeIf { !it.isNaN() },
                marketCap = o.optDouble("market_cap").takeIf { !it.isNaN() },
                rank = o.optInt("market_cap_rank").takeIf { it > 0 },
                change24h = o.optDouble("price_change_percentage_24h").takeIf { !it.isNaN() },
                mint = mints[id]?.takeIf { it.isNotEmpty() },
                volume24h = o.optDouble("total_volume").takeIf { !it.isNaN() && it > 0 },
            )
        }
        return out
    }

    /** A logo already seen for [id]: the list on screen, else the list saved on disk. */
    private fun knownImage(id: String): String? {
        top.firstOrNull { it.id == id }?.image?.let { return it }
        val saved = imagesOnDisk ?: runCatching {
            val arr = JSONArray(file?.readText() ?: return null)
            (0 until arr.length()).mapNotNull { fromJson(arr.optString(it)) }.mapNotNull { c -> c.image?.let { c.id to it } }.toMap()
        }.getOrDefault(emptyMap()).also { imagesOnDisk = it }
        return saved[id]
    }
    @Volatile private var imagesOnDisk: Map<String, String>? = null

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
