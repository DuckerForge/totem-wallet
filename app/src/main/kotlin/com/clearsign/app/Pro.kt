package com.clearsign.app

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Everything is free. This gated premium themes, background alerts, deep scans and unlimited
 * exports behind a one-off SKR payment; the app earns from the swap fee, so charging twice was
 * a second toll on the same road. The flag stays, always true, because a dozen screens ask it.
 */
object Pro {
    private const val PREFS = "clearsign_pro"
    private const val KEY = "pro"
    private const val KEY_SIG = "sig"

    val isPro = mutableStateOf(true)

    fun load(ctx: Context) { isPro.value = true }

    fun set(ctx: Context, signature: String?) {
        prefs(ctx).edit().putBoolean(KEY, true).apply { if (signature != null) putString(KEY_SIG, signature) }.apply()
        isPro.value = true
    }

    fun signature(ctx: Context): String? = prefs(ctx).getString(KEY_SIG, null)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
