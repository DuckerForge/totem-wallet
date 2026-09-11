package com.clearsign.app

/**
 * Simple realized P&L from the ledger, FIFO. Every priced inflow of a token is a
 * "lot" bought at that fiat value; every priced outflow disposes of the oldest
 * lots first, and the difference is the realized gain/loss. Entries without a
 * fiat snapshot are skipped, so the numbers are honest about what is known.
 */
data class TokenPnl(
    val mint: String, val symbol: String,
    val realized: Double,       // fiat, in the chosen currency
    val heldUnits: Double,      // units still held from recorded lots
    val costOfHeld: Double,     // fiat cost basis of what is still held
    val firstAt: Long?,         // when the position was first acquired
    val disposals: Int,
)

data class Analytics(val currency: String, val tokens: List<TokenPnl>, val totalRealized: Double) {
    val hasData: Boolean get() = tokens.isNotEmpty()
}

object AnalyticsEngine {
    private class Lot(var units: Double, val costPerUnit: Double, val at: Long)

    fun of(entries: List<LedgerEntry>, currency: String): Analytics {
        // Oldest first, so FIFO matching is correct.
        val ordered = entries.sortedBy { it.at }
        val lots = HashMap<String, ArrayDeque<Lot>>()
        val realized = HashMap<String, Double>()
        val symbol = HashMap<String, String>()
        val firstAt = HashMap<String, Long>()
        val disposals = HashMap<String, Int>()

        for (e in ordered) {
            val snap = e.fiat[currency] ?: continue
            // acquisitions
            for (leg in e.inflows) {
                val price = snap.priceOf(leg.mint) ?: continue
                val units = kotlin.math.abs(leg.uiAmount); if (units <= 0) continue
                lots.getOrPut(leg.mint) { ArrayDeque() }.addLast(Lot(units, price, e.at))
                symbol.putIfAbsent(leg.mint, leg.symbol); firstAt.putIfAbsent(leg.mint, e.at)
            }
            // disposals
            for (leg in e.outflows) {
                val price = snap.priceOf(leg.mint) ?: continue
                var units = kotlin.math.abs(leg.uiAmount); if (units <= 0) continue
                symbol.putIfAbsent(leg.mint, leg.symbol)
                val q = lots.getOrPut(leg.mint) { ArrayDeque() }
                var cost = 0.0
                while (units > 1e-12 && q.isNotEmpty()) {
                    val lot = q.first()
                    val take = minOf(units, lot.units)
                    cost += take * lot.costPerUnit; lot.units -= take; units -= take
                    if (lot.units <= 1e-12) q.removeFirst()
                }
                val proceeds = kotlin.math.abs(leg.uiAmount) * price
                realized.merge(leg.mint, proceeds - cost, Double::plus)   // unmatched units count as zero-cost (airdrops)
                disposals.merge(leg.mint, 1, Int::plus)
            }
        }

        val tokens = symbol.keys.map { mint ->
            val held = lots[mint]?.sumOf { it.units } ?: 0.0
            val costHeld = lots[mint]?.sumOf { it.units * it.costPerUnit } ?: 0.0
            TokenPnl(mint, symbol[mint] ?: TokenSymbols.symbol(mint), realized[mint] ?: 0.0, held, costHeld, firstAt[mint], disposals[mint] ?: 0)
        }.filter { it.disposals > 0 || it.heldUnits > 0 }
            .sortedByDescending { kotlin.math.abs(it.realized) }
        return Analytics(currency, tokens, tokens.sumOf { it.realized })
    }
}
