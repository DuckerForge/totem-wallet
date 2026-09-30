package com.clearsign.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * USD amounts in the Settings currency, since CoinGecko, Jupiter and GeckoTerminal all answer
 * in USD. ECB rate for fiat, SOL's price when counting in SOL, cached half an hour. Until the
 * rate arrives, amounts show in dollars with the dollar sign.
 */
@Immutable
internal data class Fx(val cur: String, val rate: Double) {
    /** The price of one coin. */
    fun price(usd: Double): String = fmtPrice(usd * rate, cur)

    /** A total: what you hold is worth. */
    fun fiat(usd: Double): String = fmtFiat(usd * rate, cur)

    /** Large figures, shortened: liquidity, volume, market cap. */
    fun cap(usd: Double): String = fmtCap(usd * rate, cur)

    /** The bare number for the chart scale, no symbol. */
    fun num(usd: Double): String = axisNum(usd * rate)

    companion object {
        val usd = Fx("USD", 1.0)

        private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Double>>()
        private const val TTL_MS = 30 * 60_000L

        /** What is already known, without asking anyone. */
        fun cached(cur: String): Fx? =
            if (cur == "USD") usd
            else cache[cur]?.takeIf { System.currentTimeMillis() - it.first < TTL_MS }?.let { Fx(cur, it.second) }

        /** Blocking: call on IO. Falls back to USD if nobody answers. */
        fun fetch(cur: String): Fx {
            cached(cur)?.let { return it }
            val r = when (cur) {
                // Counting in SOL is a price, not a currency rate: the price feed knows it, the ECB does not.
                "SOL" -> runCatching { Prices.usdTo("SOL") }.getOrNull()
                // The ECB rate first: a currency rate is not a crypto question, and CoinGecko can refuse.
                else -> runCatching { Prices.frankfurter(cur) }.getOrNull()
                    ?: runCatching { FiatRates.spot(listOf("USD", cur)) }.getOrNull()
                        ?.let { m -> m[cur]?.div(m["USD"] ?: return@let null) }
            }?.takeIf { it > 0 && it.isFinite() } ?: return usd
            cache[cur] = System.currentTimeMillis() to r
            return Fx(cur, r)
        }
    }
}

/** The current rate, ready at once if somebody else just asked. */
@Composable
internal fun rememberFx(): Fx {
    val cur by Settings.currency
    var fx by remember(cur) { mutableStateOf(Fx.cached(cur) ?: Fx.usd) }
    LaunchedEffect(cur) { if (fx.cur != cur) fx = withContext(Dispatchers.IO) { Fx.fetch(cur) } }
    return fx
}
