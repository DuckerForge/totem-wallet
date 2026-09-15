package com.clearsign.app

import com.clearsign.core.NATIVE_SOL_MINT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow

/** One holding, priced in the display currency when a price is known. */
data class Holding(
    val mint: String, val symbol: String, val decimals: Int, val raw: Long, val fiat: Double?,
    val name: String? = null, val image: String? = null, val isNft: Boolean = false,
    val change24h: Double? = null,     // percent move of the price over the last 24h
) {
    val ui: Double get() = raw / 10.0.pow(decimals)
    /** Priced, or at least a token we can name: shown in the main list. The rest is "other assets". */
    val isMain: Boolean get() = fiat != null || (!isNft && TokenSymbols.isKnown(mint))
}

/** The wallet's portfolio: total value in [currency] and the holdings behind it. */
data class PortfolioView(val currency: String, val total: Double, val holdings: List<Holding>, val priced: Int, val unpriced: Int) {
    val main: List<Holding> get() = holdings.filter { it.isMain }
    val others: List<Holding> get() = holdings.filter { !it.isMain }
    /** How much the priced part of the portfolio moved over 24h, in [currency] (null when nothing has a change). */
    val change24hValue: Double? get() {
        val parts = holdings.filter { it.fiat != null && it.change24h != null }
        if (parts.isEmpty()) return null
        return parts.sumOf { h -> h.fiat!! - h.fiat / (1 + h.change24h!! / 100.0) }
    }
    val change24hPct: Double? get() = change24hValue?.let { d -> (total - d).takeIf { it > 0 }?.let { d / it * 100.0 } }
}

object Portfolio {
    private val STABLES = setOf(
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", // USDC
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", // USDT
        "2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo", // PYUSD
    )

    /**
     * The last view loaded, kept for the life of the process.
     *
     * Switching tabs throws the wallet page's composition away, so coming back
     * started from null: the hero drew itself empty, the cards under it sat high,
     * and half a second later the numbers arrived and shoved everything down. The
     * data had not changed, only our memory of it had. A total half a second stale
     * is not a lie; a page that jumps is simply broken.
     */
    @Volatile private var last: Pair<String, PortfolioView>? = null

    /** What we knew a moment ago, so the page can be drawn at its real size at once. */
    fun cached(owner: String?, currency: String): PortfolioView? =
        last?.takeIf { it.first == "$owner|$currency" }?.second

    suspend fun load(owner: String, currency: String): PortfolioView = withContext(Dispatchers.IO) {
        val rpc = SolanaRpc.urlFor(null)
        val lam = runCatching { SolanaRpc.getBalance(rpc, owner) }.getOrNull() ?: 0L
        val toks = runCatching { SolanaRpc.tokenAccountsOf(rpc, owner) }.getOrDefault(emptyList()).filter { it.amount > 0 && !it.isFrozen }
        // Names, logos and NFT-ness from DAS; prices from Jupiter (USD) converted once.
        runCatching { TokenSymbols.resolve(toks.map { it.mint }) }
        val fungible = toks.filter { !TokenSymbols.isNft(it.mint) }.map { it.mint }
        val quotes = runCatching { Prices.quotes(listOf(NATIVE_SOL_MINT) + fungible) }.getOrDefault(emptyMap())
        val fx = runCatching { Prices.usdTo(currency) }.getOrNull()
        fun price(mint: String): Double? = (quotes[mint]?.usd ?: if (mint in STABLES) 1.0 else null)?.let { p -> fx?.let { p * it } }
        fun change(mint: String): Double? = quotes[mint]?.change24h

        val holdings = ArrayList<Holding>()
        val solUi = lam / 1e9
        holdings.add(Holding(NATIVE_SOL_MINT, "SOL", 9, lam, price(NATIVE_SOL_MINT)?.let { it * solUi }, name = "Solana", image = TokenSymbols.image(NATIVE_SOL_MINT), change24h = change(NATIVE_SOL_MINT)))
        for (t in toks) {
            val ui = t.amount / 10.0.pow(t.decimals)
            holdings.add(
                Holding(
                    t.mint, TokenSymbols.symbol(t.mint), t.decimals, t.amount, price(t.mint)?.let { it * ui },
                    name = TokenSymbols.name(t.mint), image = TokenSymbols.image(t.mint), isNft = TokenSymbols.isNft(t.mint),
                    change24h = change(t.mint),
                ),
            )
        }
        holdings.sortWith(compareByDescending<Holding> { it.fiat ?: -1.0 }.thenByDescending { it.ui })
        val total = holdings.sumOf { it.fiat ?: 0.0 }
        PortfolioView(currency, total, holdings, holdings.count { it.fiat != null }, holdings.count { it.fiat == null && it.isMain })
            .also { last = "$owner|$currency" to it }
    }
}
