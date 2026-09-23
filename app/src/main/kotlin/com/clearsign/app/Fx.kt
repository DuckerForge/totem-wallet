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
 * Dollars in the viewer's currency. The market is read in dollars because CoinGecko, Jupiter
 * and GeckoTerminal all answer in USD; the portfolio already counted in the currency chosen
 * in Settings and the Market did not, two units on one screen. The rate is the ratio between
 * SOL's price in the two currencies, a number the app already asks for, kept half an hour.
 * Until it arrives amounts are written in dollars with the dollar sign: the number stays true, only the unit changes later.
 */
@Immutable
internal data class Fx(val cur: String, val rate: Double) {
    /** The price of one coin. */
    fun price(usd: Double): String = fmtPrice(usd * rate, cur)

    /** A total: what you hold is worth. */
    fun fiat(usd: Double): String = fmtFiat(usd * rate, cur)

    /** Cifre grandi accorciate: liquidita', volume, capitalizzazione. */
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

        /** Bloccante: chiamare su IO. Se nessuno risponde restano i dollari. */
        fun fetch(cur: String): Fx {
            cached(cur)?.let { return it }
            val r = runCatching { FiatRates.spot(listOf("USD", cur)) }.getOrNull()
                ?.let { m -> m[cur]?.div(m["USD"] ?: return@let null) }
                ?.takeIf { it > 0 && it.isFinite() } ?: return usd
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
