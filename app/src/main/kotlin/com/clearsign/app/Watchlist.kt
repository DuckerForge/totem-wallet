package com.clearsign.app

import android.content.Context
import org.json.JSONArray

/**
 * The coins you follow without owning them.
 *
 * The portfolio can only ever show what the wallet holds, which is the wrong
 * list for deciding what to buy: the coin you are watching is by definition the
 * one you have not bought yet. This is that other list, kept on the phone and
 * nowhere else. It stores mints and nothing more. Names, logos and prices all
 * come from the registry we already ask, so a coin followed today is still
 * correctly labelled after it renames itself tomorrow.
 *
 * Order is the order you added them, newest first, because the last thing you
 * looked up is the thing you are thinking about.
 */
object Watchlist {
    private const val PREFS = "apex_watchlist"
    private const val KEY = "mints"
    private const val MAX = 100

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * How much of a coin you hold somewhere this wallet cannot see.
     *
     * The portfolio can only count what is in the Seed Vault account. A coin held
     * on an exchange, in another wallet, or on another chain is invisible to it
     * and yet it is yours. One number per followed coin, typed once, so the
     * market screen can say what your position is worth instead of only what the
     * coin costs.
     */
    fun amount(ctx: Context, key: String): Double =
        prefs(ctx).getString("qty_" + key, null)?.toDoubleOrNull() ?: 0.0

    fun setAmount(ctx: Context, key: String, qty: Double) {
        val p = prefs(ctx).edit()
        if (qty > 0) p.putString("qty_" + key, qty.toString()) else p.remove("qty_" + key)
        p.apply()
    }

    fun all(ctx: Context): List<String> = runCatching {
        val a = JSONArray(prefs(ctx).getString(KEY, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }
    }.getOrDefault(emptyList())

    fun has(ctx: Context, mint: String) = mint in all(ctx)

    fun add(ctx: Context, mint: String) {
        if (mint.isEmpty()) return
        save(ctx, (listOf(mint) + all(ctx).filter { it != mint }).take(MAX))
    }

    fun remove(ctx: Context, mint: String) = save(ctx, all(ctx).filter { it != mint })

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
