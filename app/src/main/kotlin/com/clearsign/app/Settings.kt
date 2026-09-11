package com.clearsign.app

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import java.util.Currency
import java.util.Locale

/** Small user preferences (display currency); loaded once per process. */
object Settings {
    private const val PREFS = "clearsign_settings"
    private const val KEY_CURRENCY = "currency"
    private const val KEY_ONBOARDED = "onboarded"
    private const val KEY_WATCH = "watchtower"
    private const val KEY_WATCH_WALLET = "watch_wallet"

    val currency = mutableStateOf("USD")
    val onboarded = mutableStateOf(true)
    val watchtower = mutableStateOf(false)

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        currency.value = p.getString(KEY_CURRENCY, null) ?: defaultCurrency()
        onboarded.value = p.getBoolean(KEY_ONBOARDED, false)
        watchtower.value = p.getBoolean(KEY_WATCH, false)
    }

    fun watchWallet(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_WATCH_WALLET, null)
    fun setWatchWallet(ctx: Context, owner: String) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_WATCH_WALLET, owner).apply() }
    fun setWatchtower(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WATCH, on).apply()
        watchtower.value = on
        if (on) Watchtower.enable(ctx) else Watchtower.disable(ctx)
    }

    fun setOnboarded(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ONBOARDED, true).apply()
        onboarded.value = true
    }

    fun setCurrency(ctx: Context, code: String) {
        if (code !in FiatRates.SUPPORTED) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CURRENCY, code).apply()
        currency.value = code
    }

    private fun defaultCurrency(): String =
        runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull()?.takeIf { it in FiatRates.SUPPORTED } ?: "USD"
}
