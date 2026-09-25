package com.clearsign.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Jupiter's token registry (Token API v2), the list the Jupiter app shows, so a swap offers
 * every coin instead of six. It answers what nothing else could: the name and logo of a
 * mint we do not hold and, the real blocker, its decimals, which [SolanaRpc.dasAssets]
 * drops. Everything learned is pushed into [TokenSymbols] too.
 */
object JupiterTokens {
    private const val TAG = "ClearSign-JupTok"
    private val HOSTS = listOf("https://lite-api.jup.ag/tokens/v2", "https://api.jup.ag/tokens/v2")
    private const val TOP_FILE = "jup_top_tokens.json"
    private const val TOP_TTL_MS = 24L * 60 * 60 * 1000

    data class Tok(
        val mint: String,
        val symbol: String,
        val name: String,
        val icon: String?,
        val decimals: Int,
        val usd: Double? = null,
        /** The 24h move in percent, when the registry has one. */
        val change24h: Double? = null,
        val verified: Boolean = false,
        val liquidity: Double = 0.0,
        /** What the registry says the coin is worth in total. Null when it does not say. */
        val mcap: Double? = null,
        // What the registry knows about the coin itself, for TokenSafety. All of it
        // rides along in the same answer, so grading a coin costs no extra call.
        val organic: String? = null,
        val canMint: Boolean = false,
        val canFreeze: Boolean = false,
        val token2022: Boolean = false,
        val topHoldersPct: Double? = null,
        val devPct: Double? = null,
        val devMints: Int = 0,
        val holders: Int = 0,
    ) {
        /**
         * [sellable] comes from an actual quote back to SOL; null when nobody asked. [ext] is what
         * the mint account can still do to you after the purchase; left out, it comes from whatever
         * was already read, so a list shows the flag without one call per row.
         */
        fun facts(sellable: Boolean? = null, ext: com.clearsign.core.MintExtensions? = null) = com.clearsign.core.TokenFacts(
            verified = verified, organic = organic, canMint = canMint, canFreeze = canFreeze,
            token2022 = token2022, topHoldersPct = topHoldersPct, devPct = devPct, devMints = devMints,
            holders = holders, liquidityUsd = liquidity, sellable = sellable,
            ext = ext ?: TokenExtensions.cached(mint) ?: com.clearsign.core.MintExtensions.NONE,
        )
    }

    /** Everything we have ever seen, so the UI can name a mint without a round trip. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Tok>()
    @Volatile private var top: List<Tok> = emptyList()
    @Volatile private var topAt = 0L

    fun cached(mint: String): Tok? = cache[mint]?.also { touch(mint) }

    /** When a coin was last used: decides who stays on disk. */
    private val touchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun touch(mint: String) { touchedAt[mint] = System.currentTimeMillis() }

    /** Quante monete tiene il disco, al massimo. Oltre, vanno via quelle usate da piu' tempo. */
    const val DISK_MAX = 2000
    /** A file bigger than this is not read: it grew before the cap existed. */
    const val DISK_MAX_BYTES = 1_000_000L

    /** The [max] most recently used coins, for the disk. Pure, for the test. */
    internal fun keepNewest(all: Collection<Tok>, touched: Map<String, Long>, max: Int): List<Tok> =
        all.sortedByDescending { touched[it.mint] ?: 0L }.take(max)

    // ---- the archive and the disk -------------------------------------------------
    //
    // Name, symbol, decimals and icon never change, and this map lived in memory only: every
    // restart asked for everything again, paid not in money but in rate limits, the same quota
    // that shows a price while someone watches. Two steps, cheapest first: the disk for what this
    // phone has seen, the shared archive for what another phone has. Only then Jupiter.
    @Volatile private var diskFile: File? = null

    fun warmDisk(ctx: Context) {
        if (diskFile != null) return
        val f = File(ctx.filesDir, "tokens.json")
        diskFile = f
        runCatching {
            if (!f.exists()) return
            // A file that grew with no cap is thrown away rather than read: the current
            // cap rewrites it small at the first chance.
            if (f.length() > DISK_MAX_BYTES) { f.delete(); return }
            // `putIfAbsent`: this runs after the first frame now, and a coin asked for in
            // the meantime, with its price, must not be overwritten by the disk.
            parse(JSONArray(f.readText())).forEach { cache.putIfAbsent(it.mint, it) }
        }
    }

    /**
     * The disk remembers what a coin is called, never what it is worth. A name does not age; a
     * price reread tomorrow from a file is an old number with the air of a current one, worse
     * than none. So `usd` and the change leave zeroed, and whoever needs a price fetches it.
     */
    private fun saveDisk() {
        val f = diskFile ?: return
        runCatching { f.writeText(rawOf(keepNewest(cache.values, touchedAt, DISK_MAX).map { it.copy(usd = null, change24h = null) })) }
    }

    /**
     * What the archive knows: name and decimals, nothing else. No price, no liquidity, no
     * judgment: a token from here has zero liquidity and is unverified, exactly how an unknown
     * coin looks, so whoever judges it treats it with the suspicion it deserves.
     */
    private fun fromArchive(mints: List<String>): List<Tok> {
        val base = BuildConfig.CROWD_URL.takeIf { it.isNotBlank() } ?: return emptyList()
        val body = runCatching {
            val c = (java.net.URL(base.trimEnd('/') + "/?t=" + mints.take(20).joinToString(",")).openConnection() as java.net.HttpURLConnection)
                .apply { connectTimeout = 5_000; readTimeout = 10_000; setRequestProperty("Accept", "application/json") }
            if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyList()
        return runCatching {
            val o = org.json.JSONObject(body)
            o.keys().asSequence().mapNotNull { k ->
                val j = o.optJSONObject(k) ?: return@mapNotNull null
                val sym = j.optString("s").ifEmpty { return@mapNotNull null }
                Tok(k, sym, j.optString("n"), j.optString("i").ifEmpty { null }, j.optInt("d"))
            }.toList()
        }.getOrDefault(emptyList())
    }

    /** The popular list by Jupiter's 24h organic score: memory, then disk (a day old at most), then the network. Blocking, IO. */
    fun top(ctx: Context): List<Tok> {
        top.takeIf { it.isNotEmpty() && System.currentTimeMillis() - topAt < TOP_TTL_MS }?.let { return it }
        val file = File(ctx.filesDir, TOP_FILE)
        if (top.isEmpty() && file.exists()) {
            runCatching { parse(JSONArray(file.readText())) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                top = it
                topAt = file.lastModified()
                if (System.currentTimeMillis() - topAt < TOP_TTL_MS) return it
            }
        }
        val fresh = fetch("/toporganicscore/24h?limit=100")
        if (fresh.isEmpty()) return top
        top = fresh
        topAt = System.currentTimeMillis()
        runCatching { file.writeText(rawOf(fresh)) }
        return fresh
    }

    /** Free-text search: a symbol, a name, or one or more mints. Blocking: call on IO. */
    fun search(query: String): List<Tok> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return fetch("/search?query=" + URLEncoder.encode(q, "UTF-8"))
    }

    /** Metadata for specific mints (up to 100 per call). Blocking: call on IO. */
    fun byMints(mints: List<String>): Map<String, Tok> {
        val todo = mints.distinct().filter { it != com.clearsign.core.NATIVE_SOL_MINT }
        if (todo.isEmpty()) return emptyMap()
        // This one asks Jupiter, always, and skips disk and archive. Callers want a price, and a
        // price has an age; disk and archive know name, decimals and face, and for a while today they
        // answered anyway with `usd` empty, so the Market wrote "no price" on coins that have one.
        // The real saving is in `warm`, the volume road for names.
        val out = HashMap<String, Tok>()
        todo.chunked(100)
            .flatMap { chunk -> fetch("/search?query=" + chunk.joinToString(",")) }
            .forEach { out[it.mint] = it; cache[it.mint] = it; touch(it.mint) }
        saveDisk()
        return out
    }

    /**
     * The pool the agent picks from: what is traded most and what real wallets are buying,
     * merged and de-duplicated. Two lists because they disagree usefully: traded is where the
     * money is, organic where the people are. `/recent` is left out: two or three holders, which
     * no gate passes. Returns [com.clearsign.core.Candidate], not [Tok]: the trading windows go
     * stale in minutes and must never be cached. Blocking, IO.
     */
    fun pool(): List<com.clearsign.core.Candidate> {
        val seen = LinkedHashMap<String, com.clearsign.core.Candidate>()
        fun take(arr: JSONArray?) {
            if (arr == null) return
            parse(arr) // seeds the name and icon cache for everything we are about to rank
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i)?.let(::candidate) ?: continue
                seen.getOrPut(c.mint) { c }
            }
        }
        // Four of Jupiter's lists, because they disagree usefully: traded is where the money is,
        // organic where the people are, trending what is talked about, and an hour and a day return
        // different coins.
        listOf(
            "/toptraded/24h?limit=100",
            "/toporganicscore/1h?limit=100",
            "/toporganicscore/24h?limit=100",
            "/toptrending/24h?limit=100",
        ).forEach { path -> take(HOSTS.firstNotNullOfOrNull { getArray(it + path) }) }

        // And one source that is not Jupiter at all. See [Gecko]: its mints come
        // back through the same lookup, so they reach the gates in the same shape
        // as everything above, with the same trading windows attached.
        val outside = runCatching { Gecko.mints() }.getOrDefault(emptyList()).filter { it !in seen }
        if (outside.isNotEmpty()) {
            outside.chunked(100).forEach { chunk ->
                take(HOSTS.firstNotNullOfOrNull { getArray(it + "/search?query=" + chunk.joinToString(",")) })
            }
        }
        return seen.values.toList()
    }

    /**
     * Names on a pile of mints in as few calls as possible: "Vesper bought 25zrhp…pump" says
     * nothing, "Vesper bought KITTY" says it all. This is the volume road (feed, census, charts),
     * where a name written yesterday is as good as today's, so it may go through disk and shared
     * archive and bothers Jupiter only for what nobody knows; the registry's unknowns keep their
     * address. Blocking, IO.
     */
    fun warm(mints: Collection<String>) {
        val missing = mints.filter { it.isNotEmpty() && cache[it] == null }.distinct()
        if (missing.isEmpty()) return
        val still = missing.filter { m -> fromArchiveInto(m) == null }
        for (chunk in still.chunked(50)) {
            val q = URLEncoder.encode(chunk.joinToString(","), "UTF-8")
            HOSTS.firstNotNullOfOrNull { getArray("$it/search?query=$q") }?.let { parse(it) }
        }
        saveDisk()
    }

    /** A coin from the archive into the cache, if there. Null when it was not. */
    private fun fromArchiveInto(mint: String): Tok? =
        fromArchive(listOf(mint)).firstOrNull()?.also { cache[it.mint] = it; touch(it.mint) }

    /**
     * One coin with its trading windows, so a receipt can judge the thing it is about to
     * receive and not only the transaction that brings it. Blocking.
     */
    fun candidateOf(mint: String): com.clearsign.core.Candidate? {
        val arr = HOSTS.firstNotNullOfOrNull { getArray(it + "/search?query=" + URLEncoder.encode(mint, "UTF-8")) } ?: return null
        parse(arr)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == mint) return candidate(o)
        }
        return null
    }

    /** One registry row as the scan sees it. Null when it is not a usable token. */
    private fun candidate(o: JSONObject): com.clearsign.core.Candidate? {
        val mint = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val sym = o.optString("symbol").takeIf { it.isNotEmpty() } ?: return null
        if (!o.has("decimals")) return null
        val audit = o.optJSONObject("audit")
        fun num(k: String) = o.optDouble(k).takeIf { !it.isNaN() }
        val age = o.optJSONObject("firstPool")?.optString("createdAt")?.takeIf { it.isNotEmpty() }?.let { iso ->
            runCatching {
                val ms = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(iso)!!.time
                ((System.currentTimeMillis() - ms) / 60_000.0).coerceAtLeast(0.0)
            }.getOrNull()
        }
        return com.clearsign.core.Candidate(
            mint = mint, symbol = sym,
            name = o.optString("name").takeIf { it.isNotEmpty() } ?: sym,
            decimals = o.optInt("decimals"),
            usd = num("usdPrice")?.takeIf { it > 0 },
            liquidity = num("liquidity") ?: 0.0,
            mcap = num("mcap"), fdv = num("fdv"),
            holders = o.optInt("holderCount").takeIf { it > 0 },
            organicScore = num("organicScore"),
            verified = o.optBoolean("isVerified"),
            canMint = !o.isNull("mintAuthority") || audit?.optBoolean("mintAuthorityDisabled", true) == false,
            canFreeze = !o.isNull("freezeAuthority") || audit?.optBoolean("freezeAuthorityDisabled", true) == false,
            token2022 = o.optString("tokenProgram").let { it.isNotEmpty() && it != SolanaTx.TOKEN_PROGRAM },
            topHoldersPct = audit?.optDouble("topHoldersPercentage")?.takeIf { !it.isNaN() },
            devMints = audit?.optInt("devMints") ?: 0,
            ageMinutes = age,
            s5m = window(o.optJSONObject("stats5m")), s1h = window(o.optJSONObject("stats1h")),
            s6h = window(o.optJSONObject("stats6h")), s24h = window(o.optJSONObject("stats24h")),
        )
    }

    private fun window(o: JSONObject?): com.clearsign.core.ScanWindow? {
        if (o == null) return null
        fun num(k: String) = o.optDouble(k).takeIf { !it.isNaN() }
        fun int(k: String) = if (o.has(k)) o.optInt(k) else null
        return com.clearsign.core.ScanWindow(
            priceChange = num("priceChange"), liquidityChange = num("liquidityChange"), volume = num("volume"),
            buyVolume = num("buyVolume"), sellVolume = num("sellVolume"),
            buyOrganicVolume = num("buyOrganicVolume"), sellOrganicVolume = num("sellOrganicVolume"),
            numBuys = int("numBuys"), numTraders = int("numTraders"),
            numOrganicBuyers = int("numOrganicBuyers"), numNetBuyers = int("numNetBuyers"),
            holderChange = num("holderChange"),
        )
    }

    private fun fetch(path: String): List<Tok> {
        val arr = HOSTS.firstNotNullOfOrNull { getArray(it + path) } ?: return emptyList()
        return parse(arr)
    }

    internal fun parse(arr: JSONArray): List<Tok> {
        val out = ArrayList<Tok>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val mint = o.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val sym = o.optString("symbol").takeIf { it.isNotEmpty() } ?: continue
            if (!o.has("decimals")) continue
            val audit = o.optJSONObject("audit")
            val t = Tok(
                mint = mint,
                symbol = sym,
                name = o.optString("name").takeIf { it.isNotEmpty() } ?: sym,
                icon = o.optString("icon").takeIf { it.isNotEmpty() },
                decimals = o.optInt("decimals"),
                usd = o.optDouble("usdPrice").takeIf { !it.isNaN() && it > 0 },
                change24h = o.optJSONObject("stats24h")?.optDouble("priceChange")?.takeIf { !it.isNaN() },
                verified = o.optBoolean("isVerified"),
                liquidity = o.optDouble("liquidity").takeIf { !it.isNaN() } ?: 0.0,
                mcap = o.optDouble("mcap").takeIf { !it.isNaN() && it > 0 },
                organic = o.optString("organicScoreLabel").takeIf { it.isNotEmpty() },
                // The raw authority fields are the truth; `audit` restates them as
                // "disabled" booleans and adds what it knows about the holders.
                canMint = !o.isNull("mintAuthority") || audit?.optBoolean("mintAuthorityDisabled", true) == false,
                canFreeze = !o.isNull("freezeAuthority") || audit?.optBoolean("freezeAuthorityDisabled", true) == false,
                token2022 = o.optString("tokenProgram").let { it.isNotEmpty() && it != SolanaTx.TOKEN_PROGRAM },
                topHoldersPct = audit?.optDouble("topHoldersPercentage")?.takeIf { !it.isNaN() },
                devPct = audit?.optDouble("devBalancePercentage")?.takeIf { !it.isNaN() },
                devMints = audit?.optInt("devMints") ?: 0,
                holders = o.optInt("holderCount"),
            )
            cache[mint] = t
            touch(mint)
            TokenSymbols.seed(mint, t.symbol, t.name, t.icon)
            out.add(t)
        }
        return out
    }

    /** Re-serialise what we keep, so the disk copy stays small instead of storing Jupiter's full rows. */
    private fun rawOf(list: List<Tok>): String {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.mint).put("symbol", t.symbol).put("name", t.name)
                    .put("icon", t.icon ?: JSONObject.NULL).put("decimals", t.decimals)
                    .put("usdPrice", t.usd ?: JSONObject.NULL)
                    .put("isVerified", t.verified).put("liquidity", t.liquidity)
                    .put("organicScoreLabel", t.organic ?: JSONObject.NULL)
                    .put("mintAuthority", if (t.canMint) "live" else JSONObject.NULL)
                    .put("freezeAuthority", if (t.canFreeze) "live" else JSONObject.NULL)
                    .put("tokenProgram", if (t.token2022) "token2022" else SolanaTx.TOKEN_PROGRAM)
                    .put("holderCount", t.holders)
                    .put(
                        "audit",
                        JSONObject()
                            .put("topHoldersPercentage", t.topHoldersPct ?: JSONObject.NULL)
                            .put("devBalancePercentage", t.devPct ?: JSONObject.NULL)
                            .put("devMints", t.devMints),
                    ),
            )
        }
        return arr.toString()
    }

    private fun getArray(url: String): JSONArray? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 12000; setRequestProperty("Accept", "application/json")
        }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect()
        if (code !in 200..299) { Log.w(TAG, "GET $url -> $code"); null } else body?.let { JSONArray(it) }
    } catch (e: Exception) { Log.w(TAG, "GET failed: ${e.message}"); null }
}
