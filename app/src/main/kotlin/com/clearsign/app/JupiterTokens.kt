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
 * Jupiter's token registry (Token API v2): the same list the Jupiter app itself
 * shows, so a swap can offer every coin instead of six hardcoded ones.
 *
 * It answers the three things a picker needs and that nothing else in the app
 * could provide: the **name and logo** of a mint we do not hold, and — the real
 * blocker — its **decimals**, which the DAS path in [SolanaRpc.dasAssets] drops.
 *
 * Everything learned here is pushed into [TokenSymbols] too, so receipts and the
 * portfolio get better names and logos for free.
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
        val verified: Boolean = false,
        val liquidity: Double = 0.0,
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
        /** [sellable] comes from an actual quote back to SOL; null when nobody asked. */
        fun facts(sellable: Boolean? = null) = com.clearsign.core.TokenFacts(
            verified = verified, organic = organic, canMint = canMint, canFreeze = canFreeze,
            token2022 = token2022, topHoldersPct = topHoldersPct, devPct = devPct, devMints = devMints,
            holders = holders, liquidityUsd = liquidity, sellable = sellable,
        )
    }

    /** Everything we have ever seen, so the UI can name a mint without a round trip. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Tok>()
    @Volatile private var top: List<Tok> = emptyList()
    @Volatile private var topAt = 0L

    fun cached(mint: String): Tok? = cache[mint]

    /**
     * The popular list, by Jupiter's 24h organic score. Served from memory, then
     * from disk (a day old at most), then from the network. Blocking: call on IO.
     */
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
        return todo.chunked(100).flatMap { chunk -> fetch("/search?query=" + chunk.joinToString(",")) }.associateBy { it.mint }
    }

    /**
     * The pool the agent picks from: what is traded most and what real wallets
     * are buying right now, merged and de-duplicated.
     *
     * Two lists rather than one because they disagree in a useful way: the
     * traded list is where the money is, the organic list is where the people
     * are. The freshly-launched list (`/recent`) is deliberately left out: those
     * rows come back with two or three holders, which no gate will pass and no
     * budget should buy.
     *
     * Returns [com.clearsign.core.Candidate], not [Tok], because the trading
     * windows in the answer go stale in minutes and must never be cached.
     * Blocking: call on IO.
     */
    fun pool(): List<com.clearsign.core.Candidate> {
        val seen = LinkedHashMap<String, com.clearsign.core.Candidate>()
        listOf("/toptraded/24h?limit=100", "/toporganicscore/1h?limit=100").forEach { path ->
            val arr = HOSTS.firstNotNullOfOrNull { getArray(it + path) } ?: return@forEach
            parse(arr) // seeds the name and icon cache for everything we are about to rank
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val c = candidate(o) ?: continue
                seen.getOrPut(c.mint) { c }
            }
        }
        return seen.values.toList()
    }

    /**
     * One coin, with its trading windows, so a receipt can judge the thing it is
     * about to receive and not only the transaction that brings it. Blocking.
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
                verified = o.optBoolean("isVerified"),
                liquidity = o.optDouble("liquidity").takeIf { !it.isNaN() } ?: 0.0,
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
