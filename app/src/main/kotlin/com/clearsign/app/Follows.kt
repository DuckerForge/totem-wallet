package com.clearsign.app

import android.content.Context
import org.json.JSONArray

/**
 * The Seeker wallets this person follows.
 *
 * Following a wallet means one thing: when it buys, the coin it bought goes to
 * the agent as a candidate. Not "buy what they buy": a candidate, which then
 * meets the same gates, the same collar and the same slice as anything the
 * scan turns up on its own. Somebody with a bigger wallet is somebody who can
 * afford to be wrong; the gates are what keep their mistakes from becoming
 * yours at four minutes' delay.
 *
 * Addresses only, kept on the phone. Nothing about who anybody is.
 */
object Follows {
    private const val PREFS = "apex_follows"
    private const val KEY = "wallets"
    private const val MAX = 30

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): Set<String> = runCatching {
        val a = JSONArray(prefs(ctx).getString(KEY, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }.toSet()
    }.getOrDefault(emptySet())

    fun has(ctx: Context, wallet: String) = wallet in all(ctx)

    /** Returns whether it is followed now. */
    fun toggle(ctx: Context, wallet: String): Boolean {
        val now = all(ctx)
        val next = if (wallet in now) now - wallet else (now + wallet).toList().takeLast(MAX).toSet()
        prefs(ctx).edit().putString(KEY, JSONArray(next.toList()).toString()).apply()
        return wallet in next
    }
}
