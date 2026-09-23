package com.clearsign.app

import android.content.Context
import android.util.Log
import com.clearsign.core.CrowdBuy
import com.clearsign.core.SeekerCrowd
import com.clearsign.core.SeekerTier
import com.clearsign.core.SeekerWallet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the Seeker crowd is buying, read off the chain, on its own Helius key, never the
 * agent's: if the scan exhausts its month, trading must not notice. That is why
 * [BuildConfig.SCAN_RPC_URL] exists. The cost trick: asking who moved is one call per hundred
 * wallets, since a swap always changes the balance if only by the fee, and only the movers
 * cost a lookup each. One call per wallet would be a hundred times the price.
 */
object SeekerScan {
    private const val TAG = "ClearSign-Seeker"
    private const val SNAP = "seeker_snap.txt"
    private const val BUYS = "seeker_buys.jsonl"
    private const val KEEP_MS = 7 * 24 * 3600_000L

    /** How many of the biggest wallets the very first pass reads in full. */
    private const val SEED = 250

    val available: Boolean get() = BuildConfig.SCAN_RPC_URL.isNotBlank()

    @Volatile private var roster: List<SeekerWallet>? = null

    /** The census that ships with the app: every Seeker wallet holding at least one SOL. */
    fun roster(ctx: Context): List<SeekerWallet> = roster ?: synchronized(this) {
        roster ?: runCatching {
            ctx.assets.open("seekers.txt").bufferedReader().useLines { SeekerCrowd.parseRoster(it) }
        }.getOrElse { emptyList() }.also { roster = it }
    }

    /**
     * One pass. [tier] null means everyone; [cap] bounds how many movers are looked up, so one
     * busy hour cannot drain a quiet key. Returns how many purchases were recorded; the first
     * pass records none: with no previous balances there is nothing to compare against.
     */
    fun pass(ctx: Context, tier: SeekerTier? = null, cap: Int = 400): Int {
        val all = roster(ctx)
        val watch = if (tier == null) all else all.filter { it.tier == tier }
        Log.i(TAG, "pass: chiave=$available censimento=${all.size} da_seguire=${watch.size}")
        if (!available || watch.isEmpty()) return 0

        val snap = readSnap(ctx)
        val cold = snap.isEmpty()
        val movers = ArrayList<SeekerWallet>()
        val fresh = HashMap<String, Long>(watch.size * 2)

        for (chunk in watch.chunked(100)) {
            val lam = lamports(chunk.map { it.address }) ?: continue
            chunk.forEachIndexed { i, w ->
                val now = lam.getOrNull(i) ?: return@forEachIndexed
                fresh[w.address] = now
                val was = snap[w.address]
                if (!cold && was != null && was != now) movers.add(w)
            }
        }
        // Whoever we could not read keeps the balance we knew, so a transient failure
        // does not read as movement on the next pass.
        for ((a, v) in snap) fresh.putIfAbsent(a, v)
        writeSnap(ctx, fresh)
        if (cold) {
            // A cold pass has nothing to compare against, so on its own it would leave
            // the card empty until the next one. Instead, read the last day straight
            // from the biggest wallets: one expensive start, then deltas forever.
            val seed = watch.sortedByDescending { it.centiSol }.take(SEED)
            val since0 = System.currentTimeMillis() - 24 * 3600_000L
            val first = seed.flatMap { buysOf(it, since0) }
            if (first.isNotEmpty()) append(ctx, first)
            prefs(ctx).edit().putLong("seeker_last_pass", System.currentTimeMillis()).apply()
            Log.i(TAG, "primo giro: ${fresh.size} saldi, ${first.size} acquisti dalle ${seed.size} più grosse")
            return first.size
        }

        val since = lastPassAt(ctx)
        val found = ArrayList<CrowdBuy>()
        // Richest first: if the cap bites, it should bite the smallest wallets.
        for (w in movers.sortedByDescending { it.centiSol }.take(cap)) {
            found += buysOf(w, since)
        }
        if (found.isNotEmpty()) append(ctx, found)
        prefs(ctx).edit().putLong("seeker_last_pass", System.currentTimeMillis()).apply()
        Log.i(TAG, "giro: ${watch.size} seguite, ${movers.size} mosse, ${found.size} acquisti")
        return found.size
    }

    /** How much SOL a followed wallet holds, from the census. Null if not followed. */
    fun sizeOf(ctx: Context, address: String): Double? {
        val m = sizes ?: synchronized(this) {
            sizes ?: roster(ctx).associate { it.address to it.centiSol / 100.0 }.also { sizes = it }
        }
        return m[address]
    }

    @Volatile private var sizes: Map<String, Double>? = null

    /** The purchases themselves, newest first: the feed, before it is a ranking. */
    fun events(ctx: Context, limit: Int = 40): List<CrowdBuy> =
        readBuys(ctx).sortedByDescending { it.at }.take(limit)

    /** The ranking the card shows. Empty is a real answer, not a failure. */
    fun rank(ctx: Context, windowMs: Long = 24 * 3600_000L) =
        SeekerCrowd.rank(readBuys(ctx), System.currentTimeMillis(), windowMs)

    fun lastPassAt(ctx: Context): Long = prefs(ctx).getLong("seeker_last_pass", 0L)

    /** True when the card should run a pass itself rather than wait for the worker. */
    fun stale(ctx: Context, maxAgeMs: Long = 45 * 60_000L): Boolean =
        available && System.currentTimeMillis() - lastPassAt(ctx) > maxAgeMs

    // ---- chain ----------------------------------------------------------------

    /** Balances for up to a hundred addresses in one call. Null when the call failed. */
    private fun lamports(addrs: List<String>): List<Long?>? {
        val params = JSONArray()
            .put(JSONArray(addrs))
            .put(JSONObject().put("encoding", "base64").put("dataSlice", JSONObject().put("offset", 0).put("length", 0)))
        val v = call("getMultipleAccounts", params)?.optJSONObject("result")?.optJSONArray("value") ?: return null
        return (0 until v.length()).map { v.optJSONObject(it)?.optLong("lamports") }
    }

    /** The purchases [w] made after [since]. A sale or a plain transfer yields nothing. */
    private fun buysOf(w: SeekerWallet, since: Long): List<CrowdBuy> {
        val sigs = call("getSignaturesForAddress", JSONArray().put(w.address).put(JSONObject().put("limit", 10)))
            ?.optJSONArray("result") ?: return emptyList()
        val out = ArrayList<CrowdBuy>()
        for (i in 0 until sigs.length()) {
            val s = sigs.optJSONObject(i) ?: continue
            if (!s.isNull("err")) continue
            val at = s.optLong("blockTime") * 1000L
            if (at <= since || at == 0L) continue
            val tx = call(
                "getTransaction",
                JSONArray().put(s.optString("signature")).put(
                    JSONObject().put("encoding", "jsonParsed").put("maxSupportedTransactionVersion", 0),
                ),
            )?.optJSONObject("result") ?: continue
            out += swapOf(tx, w, at) ?: continue
        }
        return out
    }

    /**
     * A purchase, or null. "Bought" means money left and a coin arrived in the same transaction,
     * both halves required: a coin arriving alone is an airdrop, and on this crowd airdrops
     * outnumber purchases (SEKR, CHAPTER2, HM shipped with the phone and are worth nothing).
     */
    private fun swapOf(tx: JSONObject, w: SeekerWallet, at: Long): CrowdBuy? {
        val meta = tx.optJSONObject("meta") ?: return null
        if (!meta.isNull("err")) return null

        fun balances(key: String): Map<String, Pair<Double, String>> {
            val a = meta.optJSONArray(key) ?: return emptyMap()
            val m = HashMap<String, Pair<Double, String>>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (o.optString("owner") != w.address) continue
                val amt = o.optJSONObject("uiTokenAmount")?.optString("uiAmountString")?.toDoubleOrNull() ?: 0.0
                m[o.optString("mint")] = amt to o.optString("programId")
            }
            return m
        }
        val pre = balances("preTokenBalances")
        val post = balances("postTokenBalances")

        // The SOL side, from the wallet's own row in the account list.
        val keys = tx.optJSONObject("transaction")?.optJSONObject("message")?.optJSONArray("accountKeys")
        var solSpent = 0.0
        if (keys != null) {
            for (i in 0 until keys.length()) {
                val pk = keys.optJSONObject(i)?.optString("pubkey") ?: keys.optString(i)
                if (pk != w.address) continue
                val preL = meta.optJSONArray("preBalances")?.optLong(i) ?: 0L
                val postL = meta.optJSONArray("postBalances")?.optLong(i) ?: 0L
                solSpent = (preL - postL) / 1e9
                break
            }
        }
        // Paying in USDC is still paying. Counted at one dollar, which is what it is.
        val usdcOut = (pre[com.clearsign.core.AgentPolicy.USDC]?.first ?: 0.0) -
            (post[com.clearsign.core.AgentPolicy.USDC]?.first ?: 0.0)
        val spent = if (solSpent > 0.001) solSpent else if (usdcOut > 1.0) usdcOut / 200.0 else 0.0
        if (spent < SeekerCrowd.MIN_SPEND_SOL) return null

        // The biggest thing that arrived and is not money.
        val gained = post.entries
            .filter { it.key !in SeekerCrowd.MONEY }
            .map { it.key to (it.value.first - (pre[it.key]?.first ?: 0.0)) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second } ?: return null

        return CrowdBuy(
            wallet = w.address, tier = w.tier, mint = gained.first,
            symbol = JupiterTokens.cached(gained.first)?.symbol ?: gained.first.take(6),
            at = at, solSpent = spent,
        )
    }

    private fun call(method: String, params: JSONArray): JSONObject? = try {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params).toString()
        val c = (URL(BuildConfig.SCAN_RPC_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept-Encoding", "gzip")
            connectTimeout = 8000; readTimeout = 20000
        }
        OutputStreamWriter(c.outputStream).use { it.write(body) }
        val ok = c.responseCode in 200..299
        val raw = if (ok) c.inputStream else c.errorStream
        val text = (if (c.contentEncoding.equals("gzip", true)) java.util.zip.GZIPInputStream(raw) else raw)
            ?.bufferedReader()?.use { it.readText() }
        c.disconnect()
        if (ok && text != null) JSONObject(text) else null
    } catch (e: Exception) { Log.w(TAG, "${method}: ${e.message}"); null }

    // ---- storage ---------------------------------------------------------------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("clearsign", Context.MODE_PRIVATE)

    private fun readSnap(ctx: Context): Map<String, Long> {
        val f = File(ctx.filesDir, SNAP)
        if (!f.exists()) return emptyMap()
        return runCatching {
            f.bufferedReader().useLines { seq ->
                seq.mapNotNull { l ->
                    val i = l.indexOf(' ')
                    if (i <= 0) null else l.substring(0, i) to (l.substring(i + 1).toLongOrNull() ?: return@mapNotNull null)
                }.toMap()
            }
        }.getOrElse { emptyMap() }
    }

    private fun writeSnap(ctx: Context, m: Map<String, Long>) = runCatching {
        File(ctx.filesDir, SNAP).bufferedWriter().use { w -> m.forEach { (a, v) -> w.write("$a $v\n") } }
    }

    private fun append(ctx: Context, buys: List<CrowdBuy>) = runCatching {
        File(ctx.filesDir, BUYS).appendText(
            buys.joinToString("") { b ->
                JSONObject()
                    .put("w", b.wallet).put("t", if (b.tier == SeekerTier.WHALE) "b" else "d")
                    .put("m", b.mint).put("s", b.symbol).put("at", b.at).put("sol", b.solSpent)
                    .toString() + "\n"
            },
        )
    }

    /** Everything still inside the window, rewriting the file when it has aged out. */
    private fun readBuys(ctx: Context): List<CrowdBuy> {
        val f = File(ctx.filesDir, BUYS)
        if (!f.exists()) return emptyList()
        val cut = System.currentTimeMillis() - KEEP_MS
        val kept = ArrayList<CrowdBuy>()
        var dropped = false
        runCatching {
            f.forEachLine { l ->
                val o = runCatching { JSONObject(l) }.getOrNull() ?: return@forEachLine
                val at = o.optLong("at")
                if (at < cut) { dropped = true; return@forEachLine }
                kept += CrowdBuy(
                    wallet = o.optString("w"),
                    tier = if (o.optString("t") == "b") SeekerTier.WHALE else SeekerTier.DOLPHIN,
                    mint = o.optString("m"), symbol = o.optString("s"),
                    at = at, solSpent = o.optDouble("sol", 0.0),
                )
            }
        }
        if (dropped) runCatching {
            f.bufferedWriter().use { w ->
                kept.forEach { b ->
                    w.write(
                        JSONObject().put("w", b.wallet).put("t", if (b.tier == SeekerTier.WHALE) "b" else "d")
                            .put("m", b.mint).put("s", b.symbol).put("at", b.at).put("sol", b.solSpent).toString() + "\n",
                    )
                }
            }
        }
        return kept
    }
}
