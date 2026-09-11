package com.clearsign.app

import android.content.Context
import com.clearsign.core.NATIVE_SOL_MINT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow

/** One holding, priced in the display currency when a price is known. */
data class Holding(val mint: String, val symbol: String, val decimals: Int, val raw: Long, val fiat: Double?) {
    val ui: Double get() = raw / 10.0.pow(decimals)
}

/** The wallet's portfolio: total value in [currency] and the holdings behind it. */
data class PortfolioView(val currency: String, val total: Double, val holdings: List<Holding>, val priced: Int, val unpriced: Int)

object Portfolio {
    private val STABLES = setOf(
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", // USDC
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", // USDT
        "2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo", // PYUSD
    )

    suspend fun load(owner: String, currency: String): PortfolioView = withContext(Dispatchers.IO) {
        val rpc = SolanaRpc.urlFor(null)
        val lam = runCatching { SolanaRpc.getBalance(rpc, owner) }.getOrNull() ?: 0L
        val toks = runCatching { SolanaRpc.tokenAccountsOf(rpc, owner) }.getOrDefault(emptyList()).filter { it.amount > 0 && !it.isFrozen }
        val solPrice = runCatching { FiatRates.spot(listOf(currency))[currency] }.getOrNull()
        // Stablecoins ≈ 1 unit of USD; convert to the display currency via the SOL cross-rate is wrong,
        // so price non-USD stables at the USD→currency factor when we can, else treat 1:1 for USD.
        val tokenPrices = runCatching { FiatRates.tokenSpot(toks.map { it.mint }, currency) }.getOrDefault(emptyMap())

        val holdings = ArrayList<Holding>()
        val solUi = lam / 1e9
        holdings.add(Holding(NATIVE_SOL_MINT, "SOL", 9, lam, solPrice?.let { it * solUi }))
        for (t in toks) {
            val ui = t.amount / 10.0.pow(t.decimals)
            val price = tokenPrices[t.mint] ?: if (t.mint in STABLES && currency == "USD") 1.0 else null
            holdings.add(Holding(t.mint, TokenSymbols.symbol(t.mint), t.decimals, t.amount, price?.let { it * ui }))
        }
        holdings.sortByDescending { it.fiat ?: -1.0 }
        val total = holdings.sumOf { it.fiat ?: 0.0 }
        PortfolioView(currency, total, holdings, holdings.count { it.fiat != null }, holdings.count { it.fiat == null })
    }
}
