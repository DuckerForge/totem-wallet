package com.clearsign.app

import android.content.Context
import org.json.JSONArray

/**
 * The coins you follow without owning them. The portfolio shows what the wallet holds, the
 * wrong list for deciding what to buy: the coin you watch is the one not bought yet. Mints
 * only, kept on the phone; names, logos and prices come from the registry, so a coin renamed
 * tomorrow is still labeled right. Newest first: the last thing looked up is the one on your mind.
 */
object Watchlist {
    private const val PREFS = "apex_watchlist"
    private const val KEY = "mints"
    private const val MAX = 100

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * How much of a coin you hold where this wallet cannot see: an exchange, another wallet,
     * another chain. One number per followed coin, typed once, so the market screen says what
     * your position is worth instead of only what the coin costs.
     */
    fun amount(ctx: Context, key: String): Double =
        prefs(ctx).getString("qty_" + key, null)?.toDoubleOrNull() ?: 0.0

    fun setAmount(ctx: Context, key: String, qty: Double) {
        val p = prefs(ctx).edit()
        if (qty > 0) p.putString("qty_" + key, qty.toString()) else p.remove("qty_" + key)
        p.apply()
        // An amount is a coin you hold: it belongs in the list, whatever the star says.
        if (qty > 0 && !has(ctx, key)) add(ctx, key)
    }

    /**
     * Amounts that lost their coin: a quantity saved, the coin gone from the list, the total
     * short and nobody knowing why. Every amount above zero brings its coin back.
     */
    fun reconcile(ctx: Context) {
        // "SOL" is what the wallet calls the coin with no token account, not a mint. Starred from
        // the portfolio it landed here as a key nobody could price, chart or buy, next to the Solana
        // the market already followed by mint. Everything under it moves across.
        rename(ctx, com.clearsign.core.NATIVE_SOL_MINT, Jupiter.SOL_MINT)

        val all = prefs(ctx).all
        val listed = all(ctx)
        all.keys.filter { it.startsWith("qty_") }.forEach { k ->
            val key = k.removePrefix("qty_")
            val qty = (all[k] as? String)?.toDoubleOrNull() ?: 0.0
            if (qty > 0 && key !in listed) add(ctx, key)
        }
    }

    /**
     * The same coin under a better name. Renaming carries the amount, the bell and the last
     * known price, or the coin comes back stripped of everything typed into it. The destination
     * wins when both exist: it is the one that works.
     */
    fun rename(ctx: Context, from: String, to: String) {
        if (from == to || !has(ctx, from)) return
        val qty = amount(ctx, from)
        if (qty > 0 && amount(ctx, to) <= 0) setAmount(ctx, to, qty)
        if (moves(ctx, from) && !moves(ctx, to)) setMoves(ctx, to, true)
        recallCoin(ctx, from)?.let { if (recallCoin(ctx, to) == null) rememberCoin(ctx, to, it) }
        add(ctx, to)
        remove(ctx, from)
        prefs(ctx).edit().remove("mv_" + from).remove("mvp_" + from).remove("seen_" + from).apply()
    }

    // ---- "tell me when it moves", per coin, like CoinGecko's bell -------------

    fun moves(ctx: Context, key: String): Boolean = prefs(ctx).getBoolean("mv_" + key, false)

    fun setMoves(ctx: Context, key: String, on: Boolean) {
        prefs(ctx).edit().putBoolean("mv_" + key, on).remove("mvp_" + key).apply()
        if (on && !has(ctx, key)) add(ctx, key)
    }

    fun movesKeys(ctx: Context): List<String> =
        prefs(ctx).all.keys.filter { it.startsWith("mv_") && prefs(ctx).getBoolean(it, false) }.map { it.removePrefix("mv_") }

    /** The price the last notification was measured from; null until the first look. */
    fun moveBase(ctx: Context, key: String): Double? = prefs(ctx).getString("mvp_" + key, null)?.toDoubleOrNull()
    fun setMoveBase(ctx: Context, key: String, price: Double) = prefs(ctx).edit().putString("mvp_" + key, price.toString()).apply()

    // ---- what a followed coin looked like the last time anybody priced it ------

    fun rememberCoin(ctx: Context, key: String, json: String) = prefs(ctx).edit().putString("seen_" + key, json).apply()
    fun recallCoin(ctx: Context, key: String): String? = prefs(ctx).getString("seen_" + key, null)

    fun all(ctx: Context): List<String> = runCatching {
        val a = JSONArray(prefs(ctx).getString(KEY, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }
    }.getOrDefault(emptyList())

    fun has(ctx: Context, mint: String) = mint in all(ctx)

    fun add(ctx: Context, mint: String) {
        if (mint.isEmpty()) return
        save(ctx, (listOf(mint) + all(ctx).filter { it != mint }).take(MAX))
    }

    fun remove(ctx: Context, mint: String) {
        save(ctx, all(ctx).filter { it != mint })
        // Unfollowed on purpose: the amount goes with it, or it would come straight back.
        prefs(ctx).edit().remove("qty_" + mint).apply()
    }

    /** Returns whether it is followed now. */
    fun toggle(ctx: Context, mint: String): Boolean {
        val on = !has(ctx, mint)
        if (on) add(ctx, mint) else remove(ctx, mint)
        return on
    }

    private fun save(ctx: Context, list: List<String>) {
        prefs(ctx).edit().putString(KEY, JSONArray(list).toString()).apply()
    }
}
