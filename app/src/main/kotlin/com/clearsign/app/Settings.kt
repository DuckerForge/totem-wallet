package com.clearsign.app

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import java.util.Currency
import java.util.Locale

/** Small user preferences (display currency); loaded once per process. */
object Settings {
    private const val PREFS = "clearsign_settings"
    private const val KEY_CURRENCY = "currency"

    val currency = mutableStateOf("USD")

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        currency.value = p.getString(KEY_CURRENCY, null) ?: defaultCurrency()
    }

    fun setCurrency(ctx: Context, code: String) {
        if (code !in FiatRates.SUPPORTED) return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CURRENCY, code).apply()
        currency.value = code
    }

    private fun defaultCurrency(): String =
        runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull()?.takeIf { it in FiatRates.SUPPORTED } ?: "USD"
}
