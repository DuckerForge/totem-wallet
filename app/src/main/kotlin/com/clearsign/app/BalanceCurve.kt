package com.clearsign.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Last month's balance shape, drawn faintly behind the home actions. There is no balance history,
 * so it is today's holdings priced over the last thirty days: fine for a shape with no axis.
 * Each series is scaled so its last point is today's value; only the largest coins are looked
 * up, the rest stay flat. Cached half an hour; under eight points, nothing is drawn.
 */
object BalanceCurve {
    private const val POINTS = 30
    private const val MIN_POINTS = 8
    /**
     * Each coin costs two requests to a source that refuses after five in a row, and the charts
     * the user opens come first. The top three are almost the whole curve; the rest stays flat.
     */
    private const val TRACK_MAX = 3
    private const val TTL_MS = 30 * 60_000L
    /** Max age of the curve on disk before it is recomputed (thirteen requests). */
    private const val DISK_MS = 6 * 3600_000L

    private val cache = ConcurrentHashMap<String, Pair<Long, List<Double>>>()

    /**
     * A fresh computation costs six requests to a source that refuses after five in a burst and
     * stays blocked for tens of seconds. At startup that starved the charts the user opens, so it
     * waits out the first minute. A curve already on disk shows at once.
     */
    private val bornAt = System.currentTimeMillis()
    private const val QUIET_MS = 60_000L

    suspend fun of(ctx: Context, owner: String, view: PortfolioView): List<Double> = withContext(Dispatchers.IO) {
        val key = owner + "|" + view.currency
        val now = System.currentTimeMillis()
        cache[key]?.let { (at, v) -> if (now - at < TTL_MS) return@withContext v }
        // Kept on disk so it shows instantly; memory only cost thirteen requests and about four
        // seconds per cold start. Six hours is fresh enough for a thirty-day shape, and it is
        // scaled onto today's total before drawing.
        read(ctx, key, now)?.let { stored ->
            val anchored = anchor(stored, view.total)
            cache[key] = now to anchored
            return@withContext anchored
        }

        // Coins and DeFi together, or a mostly staked wallet draws its shape from a small slice.
        // A stake has no mint, only the staked symbol, so it is matched to a held coin by name
        // and shares its lookup.
        val waited = System.currentTimeMillis() - bornAt
        if (waited < QUIET_MS) kotlinx.coroutines.delay(QUIET_MS - waited)

        val byMint = HashMap<String, Double>()
        val symbolToMint = HashMap<String, String>()
        for (h in view.holdings) {
            if (h.isNft || h.raw <= 0) continue
            val f = h.fiat ?: continue
            if (f <= 0.0) continue
            byMint[h.mint] = (byMint[h.mint] ?: 0.0) + f
            symbolToMint.putIfAbsent(h.symbol.uppercase(), h.mint)
        }
        symbolToMint.putIfAbsent("SOL", com.clearsign.core.NATIVE_SOL_MINT)
        for (d in view.defi) {
            val f = d.fiat ?: continue
            if (f <= 0.0) continue
            val mint = symbolToMint[d.symbol.uppercase()] ?: continue
            byMint[mint] = (byMint[mint] ?: 0.0) + f
        }
        if (byMint.isEmpty()) return@withContext emptyList()

        val tracked = byMint.entries.sortedByDescending { it.value }.take(TRACK_MAX)
        // Untracked coins are carried flat at today's value, keeping the curve on the real total.
        val flat = view.total - tracked.sumOf { it.value }

        val parts = ArrayList<List<Double>>(tracked.size)
        for (e in tracked) {
            val mint = if (e.key == com.clearsign.core.NATIVE_SOL_MINT) Jupiter.SOL_MINT else e.key
            val closes = runCatching { Gecko.series(mint, Gecko.Span.DAYS, bg = true) }.getOrNull()
                ?.map { it.close }?.takeLast(POINTS)?.filter { it > 0.0 }.orEmpty()
            // Scaled by its own last close, so the series lands on what this coin
            // is worth right now whatever currency the screen is counting in.
            parts += if (closes.size >= MIN_POINTS) {
                val last = closes.last()
                closes.map { e.value * it / last }
            } else {
                emptyList()
            }
        }

        val usable = parts.filter { it.isNotEmpty() }
        if (usable.isEmpty()) return@withContext emptyList()
        // Shorter series are held at their own oldest value for the days they do
        // not reach: a coin listed a week ago did not exist before that, and
        // stretching it backwards would invent a price it never had.
        val n = usable.maxOf { it.size }
        if (n < MIN_POINTS) return@withContext emptyList()
        val untracked = flat + tracked.filterIndexed { i, _ -> parts[i].isEmpty() }.sumOf { it.value }

        val out = (0 until n).map { i ->
            untracked + usable.sumOf { s ->
                val from = i - (n - s.size)
                s[if (from < 0) 0 else from]
            }
        }
        cache[key] = now to out
        runCatching { write(ctx, key, now, out) }
        out
    }

    /** The same shape, lifted so its last point is what the wallet is worth now. */
    private fun anchor(v: List<Double>, total: Double): List<Double> {
        val last = v.lastOrNull() ?: return v
        if (last <= 0.0 || total <= 0.0) return v
        val k = total / last
        return v.map { it * k }
    }

    private fun file(ctx: Context) = java.io.File(ctx.filesDir, "balance_curve.json")

    private fun write(ctx: Context, key: String, at: Long, v: List<Double>) {
        val a = org.json.JSONArray()
        v.forEach { a.put(it) }
        file(ctx).writeText(org.json.JSONObject().put("k", key).put("at", at).put("v", a).toString())
    }

    private fun read(ctx: Context, key: String, now: Long): List<Double>? {
        val f = file(ctx)
        if (!f.exists()) return null
        val o = runCatching { org.json.JSONObject(f.readText()) }.getOrNull() ?: return null
        if (o.optString("k") != key) return null
        if (now - o.optLong("at") > DISK_MS) return null
        val a = o.optJSONArray("v") ?: return null
        val out = (0 until a.length()).map { a.optDouble(it) }.filter { !it.isNaN() }
        return out.takeIf { it.size >= MIN_POINTS }
    }
}
