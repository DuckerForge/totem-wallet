package com.clearsign.app

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * ClearSign Pro: unlocked once with an SKR payment on mainnet. Gates the power
 * features (premium themes, background Watchtower alerts, deep address scans,
 * unlimited exports) — never anything about safety, which is always free.
 */
object Pro {
    private const val PREFS = "clearsign_pro"
    private const val KEY = "pro"
    private const val KEY_SIG = "sig"

    val isPro = mutableStateOf(false)

    fun load(ctx: Context) { isPro.value = prefs(ctx).getBoolean(KEY, false) }

    fun set(ctx: Context, signature: String?) {
        prefs(ctx).edit().putBoolean(KEY, true).apply { if (signature != null) putString(KEY_SIG, signature) }.apply()
        isPro.value = true
    }

    fun signature(ctx: Context): String? = prefs(ctx).getString(KEY_SIG, null)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
