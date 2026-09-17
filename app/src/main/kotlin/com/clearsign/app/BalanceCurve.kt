package com.clearsign.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The shape of your money over the last month, faint behind the home actions.
 *
 * ## What it is, said exactly
 *
 * Nothing in this app has ever written down what the wallet was worth yesterday,
 * so a real history does not exist and cannot be invented. What does exist is the
 * price of every coin you hold, going back months. So this is **what the coins
 * you hold today were worth on each of the last thirty days** — not what you had
 * on those days. Buy something this morning and the whole month redraws as if
 * you had always held it.
 *
 * That is a different question from "how did I do", and it would be the wrong
 * number to put next to a percentage. It is the right one for this: a shape
 * under the buttons, no axis, no figures, no label. And it is the same statement
 * the header already makes — "+0.3% today" is this, over one day, on the coins
 * you hold now.
 *
 * ## Anchored to the truth at the right-hand edge
 *
 * Each coin's series is scaled so its last point equals what that coin is worth
 * right now, in your currency. Two things fall out of that: no exchange rate has
 * to be applied to a chart that came back in dollars, and the right end of the
 * curve is the total on the screen above it rather than an approximation of it.
 *
 * ## What it costs
 *
 * At most six coins are looked up, the largest ones, and together they are
 * almost always the whole picture; everything else is carried flat at today's
 * value, which is exactly right for a stablecoin and near enough for dust. The
 * prices come from the same free source the coin charts use, with its own cache,
 * and the finished curve is kept for half an hour. So opening the wallet twenty
 * times in an afternoon costs nothing after the first.
 *
 * Fewer than eight usable points and it draws nothing. A line through three
 * points is a guess wearing the clothes of a measurement.
 */
object BalanceCurve {
    private const val POINTS = 30
    private const val MIN_POINTS = 8
    private const val TRACK_MAX = 6
    private const val TTL_MS = 30 * 60_000L

    private val cache = ConcurrentHashMap<String, Pair<Long, List<Double>>>()

    suspend fun of(owner: String, view: PortfolioView): List<Double> = withContext(Dispatchers.IO) {
        val key = owner + "|" + view.currency
        val now = System.currentTimeMillis()
        cache[key]?.let { (at, v) -> if (now - at < TTL_MS) return@withContext v }

        // Everything that is yours and has a price, coins and DeFi together.
        //
        // The DeFi positions were being left out, and on a wallet where most of
        // the money is staked that is not a detail: nine tenths of the total sat
        // flat while a hundred dollars of coins drew the whole shape. A stake has
        // no mint of its own, only the name of what is staked, so it is matched
        // to a coin you also hold by that name. Staked SKR and SKR in the wallet
        // are then one line to look up instead of two, which also spends one
        // request instead of two.
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
        // Everything not looked up is carried at today's value, unchanged. A flat
        // line is what we actually know about it, and it keeps the curve anchored
        // on the real total instead of quietly shrinking the portfolio.
        val flat = view.total - tracked.sumOf { it.value }

        val parts = ArrayList<List<Double>>(tracked.size)
        for ((i, e) in tracked.withIndex()) {
            // A breath between calls. Six coins fired at once came back with one
            // series out of six, which is what a free price API does when you
            // ask it six things in the same second, and the curve ended up being
            // the shape of whichever coin happened to answer.
            if (i > 0) kotlinx.coroutines.delay(260)
            val mint = if (e.key == com.clearsign.core.NATIVE_SOL_MINT) Jupiter.SOL_MINT else e.key
            val closes = runCatching { Gecko.series(mint, Gecko.Span.DAYS) }.getOrNull()
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
        out
    }
}
