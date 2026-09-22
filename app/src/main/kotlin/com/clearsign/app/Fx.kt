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
 * I dollari nella valuta di chi guarda.
 *
 * Il mercato lo si legge in dollari perche' in dollari lo scrivono: CoinGecko,
 * Jupiter e GeckoTerminal rispondono tutti in USD, e una moneta non ha un
 * prezzo in euro, ha un prezzo in dollari e un cambio. Il portafoglio pero'
 * gia' contava nella valuta scelta in Impostazioni, e il Mercato no: la stessa
 * app diceva il totale in euro e sotto, riga per riga, i prezzi in dollari.
 * Due unita' nella stessa schermata senza dirlo.
 *
 * Qui sta la conversione, in un posto solo. Il cambio e' il rapporto fra il
 * prezzo di un SOL nelle due valute, che e' un numero che l'app chiede gia'
 * per il portafoglio e che vale per qualsiasi cifra in dollari. Tenuto mezz'ora,
 * perche' un cambio fra monete vere non si muove come una moneta.
 *
 * Finche' non e' arrivato si scrive in dollari **col simbolo del dollaro**: il
 * numero resta vero, cambia solo l'unita' quando la risposta arriva. Un euro
 * stampato su una cifra in dollari sarebbe l'unico errore vero possibile qui.
 */
@Immutable
internal data class Fx(val cur: String, val rate: Double) {
    /** Il prezzo di una moneta. */
    fun price(usd: Double): String = fmtPrice(usd * rate, cur)

    /** Un totale: quanto vale quello che hai. */
    fun fiat(usd: Double): String = fmtFiat(usd * rate, cur)

    /** Cifre grandi accorciate: liquidita', volume, capitalizzazione. */
    fun cap(usd: Double): String = fmtCap(usd * rate, cur)

    /** Il numero nudo della scala del grafico, senza simbolo. */
    fun num(usd: Double): String = axisNum(usd * rate)

    companion object {
        val usd = Fx("USD", 1.0)

        private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Double>>()
        private const val TTL_MS = 30 * 60_000L

        /** Quello che si sa gia', senza chiedere niente a nessuno. */
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

/** Il cambio di adesso, gia' pronto se qualcun altro l'ha appena chiesto. */
@Composable
internal fun rememberFx(): Fx {
    val cur by Settings.currency
    var fx by remember(cur) { mutableStateOf(Fx.cached(cur) ?: Fx.usd) }
    LaunchedEffect(cur) { if (fx.cur != cur) fx = withContext(Dispatchers.IO) { Fx.fetch(cur) } }
    return fx
}
