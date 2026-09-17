package com.clearsign.app

import android.content.Context

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

/**
 * Money that is yours and is not a token in the wallet: SOL in a stake
 * account, a deposit in Jupiter Lend. Jupiter's wallet lists these under
 * DeFi; the token list alone would say you have less than you do.
 */
data class DefiPosition(
    val kind: Kind, val label: String, val sub: String, val symbol: String, val ui: Double, val fiat: Double?,
    val image: String? = null, val state: String? = null,
    /** What it pays, per year, as a percentage; null when nobody can say. */
    val aprPct: Double? = null,
) {
    enum class Kind { STAKE, LEND }
    /** Coins earned in a day at that rate. */
    val perDayUi: Double? get() = aprPct?.let { ui * it / 100.0 / 365.0 }
    val perDayFiat: Double? get() = if (aprPct != null && fiat != null) fiat * aprPct / 100.0 / 365.0 else null
}

/** The wallet's portfolio: total value in [currency] and the holdings behind it. */
data class PortfolioView(
    val currency: String, val total: Double, val holdings: List<Holding>, val priced: Int, val unpriced: Int,
    val defi: List<DefiPosition> = emptyList(),
) {
    val main: List<Holding> get() = holdings.filter { it.isMain }
    val others: List<Holding> get() = holdings.filter { !it.isMain }
    /** How much the priced part of the portfolio moved over 24h, in [currency] (null when nothing has a change). */
    val change24hValue: Double? get() {
        // A coin down a hundred percent divides by zero and poisons the whole
        // sum with an infinity, so the header reads NaN in exactly the case this
        // app exists for. Yesterday's price of something now worth nothing is not
        // knowable from a percentage, so that coin is left out of the sum.
        val parts = holdings.filter { h ->
            h.fiat != null && h.change24h != null && (1 + h.change24h / 100.0) > 0.0
        }
        if (parts.isEmpty()) return null
        return parts.sumOf { h -> h.fiat!! - h.fiat / (1 + h.change24h!! / 100.0) }
            .takeIf { it.isFinite() }
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

    /** The validators a Seeker owner is likely to meet, by vote account. Anyone else is shown by address. */
    private val VALIDATORS = mapOf(
        "SKRuTecmFDZHjs2DxRTJNEK7m7hunKGTWJiaZ3tMVVA" to "Seeker",
    )
    fun validatorName(voter: String?): String = when {
        voter == null -> "…"
        VALIDATORS[voter] != null -> VALIDATORS[voter]!!
        else -> voter.take(4) + "…" + voter.takeLast(4)
    }

    /** What we knew a moment ago, so the page can be drawn at its real size at once. */
    fun cached(owner: String?, currency: String): PortfolioView? =
        last?.takeIf { it.first == "$owner|$currency" }?.second

    /**
     * The same, but it also survives the app being closed.
     *
     * Memory alone fixed the jump between tabs and did nothing for the one that
     * matters more: open the app cold and the page drew itself empty, the cards
     * sat high, and a second later the numbers arrived and shoved everything
     * down. It is the first thing anybody sees, every morning, and it looked
     * like the app was rebuilding itself.
     *
     * Same lesson as the crowd archive: a cache dies with the process, a written
     * file does not. The page opens at its real size with yesterday's truth, and
     * the network corrects it in place a moment later.
     */
    fun cached(ctx: Context, owner: String?, currency: String): PortfolioView? {
        val key = "$owner|$currency"
        cached(owner, currency)?.let { return it }
        val v = runCatching { read(ctx, key) }.getOrNull() ?: return null
        last = key to v
        return v
    }

    private fun file(ctx: Context) = java.io.File(ctx.filesDir, "portfolio.json")

    private fun save(ctx: Context, key: String, v: PortfolioView) {
        val o = org.json.JSONObject()
        o.put("k", key)
        o.put("cur", v.currency)
        o.put("total", v.total)
        o.put("priced", v.priced)
        o.put("unpriced", v.unpriced)
        val hs = org.json.JSONArray()
        v.holdings.forEach { h ->
            hs.put(
                org.json.JSONObject()
                    .put("m", h.mint).put("s", h.symbol).put("d", h.decimals).put("r", h.raw)
                    .put("f", h.fiat ?: org.json.JSONObject.NULL)
                    .put("n", h.name ?: org.json.JSONObject.NULL)
                    .put("i", h.image ?: org.json.JSONObject.NULL)
                    .put("nft", h.isNft)
                    .put("c", h.change24h ?: org.json.JSONObject.NULL),
            )
        }
        o.put("h", hs)
        val ds = org.json.JSONArray()
        v.defi.forEach { d ->
            ds.put(
                org.json.JSONObject()
                    .put("k", d.kind.name).put("l", d.label).put("sub", d.sub).put("s", d.symbol)
                    .put("ui", d.ui).put("f", d.fiat ?: org.json.JSONObject.NULL)
                    .put("i", d.image ?: org.json.JSONObject.NULL)
                    .put("st", d.state ?: org.json.JSONObject.NULL)
                    .put("apr", d.aprPct ?: org.json.JSONObject.NULL),
            )
        }
        o.put("d", ds)
        file(ctx).writeText(o.toString())
    }

    private fun read(ctx: Context, key: String): PortfolioView? {
        val f = file(ctx)
        if (!f.exists()) return null
        val o = org.json.JSONObject(f.readText())
        if (o.optString("k") != key) return null
        fun d(j: org.json.JSONObject, n: String): Double? = if (j.isNull(n)) null else j.optDouble(n).takeIf { !it.isNaN() }
        fun t(j: org.json.JSONObject, n: String): String? = if (j.isNull(n)) null else j.optString(n).ifEmpty { null }
        val hs = o.optJSONArray("h") ?: org.json.JSONArray()
        val holdings = (0 until hs.length()).mapNotNull { i ->
            val j = hs.optJSONObject(i) ?: return@mapNotNull null
            Holding(j.optString("m"), j.optString("s"), j.optInt("d"), j.optLong("r"), d(j, "f"), t(j, "n"), t(j, "i"), j.optBoolean("nft"), d(j, "c"))
        }
        val ds = o.optJSONArray("d") ?: org.json.JSONArray()
        val defi = (0 until ds.length()).mapNotNull { i ->
            val j = ds.optJSONObject(i) ?: return@mapNotNull null
            val kind = runCatching { DefiPosition.Kind.valueOf(j.optString("k")) }.getOrNull() ?: return@mapNotNull null
            DefiPosition(kind, j.optString("l"), j.optString("sub"), j.optString("s"), j.optDouble("ui"), d(j, "f"), t(j, "i"), t(j, "st"), d(j, "apr"))
        }
        return PortfolioView(o.optString("cur"), o.optDouble("total"), holdings, o.optInt("priced"), o.optInt("unpriced"), defi)
    }

    suspend fun load(ctx: Context?, owner: String, currency: String): PortfolioView = withContext(Dispatchers.IO) {
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

        // Outside the token list: stake accounts and Jupiter Lend deposits.
        val defi = ArrayList<DefiPosition>()
        runCatching {
            val stakes = SolanaRpc.stakeAccounts(rpc, owner).filter { it.lamports > 0 }
            if (stakes.isNotEmpty()) {
                val epoch = SolanaRpc.epoch(rpc) ?: Long.MAX_VALUE
                // Inflation goes to stakers: the rate on the whole supply, spread
                // over the two thirds of it that are staked. An estimate, said so.
                val apr = runCatching { SolanaRpc.inflationRate(rpc) }.getOrNull()?.let { it / 0.65 * 100.0 }
                for (st in stakes) {
                    val ui = st.lamports / 1e9
                    val live = st.state(epoch) == "active"
                    defi += DefiPosition(
                        DefiPosition.Kind.STAKE, "SOL", validatorName(st.voter), "SOL", ui, price(NATIVE_SOL_MINT)?.let { it * ui },
                        image = TokenSymbols.image(NATIVE_SOL_MINT), state = st.state(epoch), aprPct = if (live) apr else null,
                    )
                }
            }
        }
        runCatching {
            SolanaRpc.skrStake(rpc, owner)?.let { st ->
                val skrUsd = quotes[SkrStake.SKR_MINT]?.usd ?: runCatching { Prices.quotes(listOf(SkrStake.SKR_MINT))[SkrStake.SKR_MINT]?.usd }.getOrNull()
                defi += DefiPosition(
                    DefiPosition.Kind.STAKE, "SKR", ctx?.getString(R.string.defi_guardians) ?: "Seeker Guardians", "SKR", st.ui,
                    skrUsd?.let { p -> fx?.let { p * it * st.ui } }, image = TokenSymbols.image(SkrStake.SKR_MINT), state = "active",
                    aprPct = ctx?.let { c -> runCatching { SkrStake.observedAprPct(c, st.sharePrice) }.getOrNull() },
                )
            }
        }
        runCatching {
            for (d in JupiterLend.deposits(owner)) {
                val ui = d.raw / 10.0.pow(d.decimals)
                val usd = d.priceUsd ?: quotes[d.asset]?.usd ?: if (d.asset in STABLES) 1.0 else null
                defi += DefiPosition(DefiPosition.Kind.LEND, d.symbol, "Jupiter Lend", d.symbol, ui, usd?.let { p -> fx?.let { p * it * ui } }, image = d.logo, aprPct = d.aprPct)
            }
        }

        runCatching {
            for (j in JupiterPortfolio.positions(owner)) {
                if (j.platformId() == "native-stake") continue   // already read from the chain above
                defi += DefiPosition(DefiPosition.Kind.LEND, j.name ?: j.label, "Jupiter · " + j.label, "$", j.valueUsd, fx?.let { j.valueUsd * it }, aprPct = j.apy)
            }
        }

        val total = holdings.sumOf { it.fiat ?: 0.0 } + defi.sumOf { it.fiat ?: 0.0 }
        PortfolioView(currency, total, holdings, holdings.count { it.fiat != null }, holdings.count { it.fiat == null && it.isMain }, defi)
            .also { v -> last = "$owner|$currency" to v; ctx?.let { runCatching { save(it, "$owner|$currency", v) } } }
    }
}
