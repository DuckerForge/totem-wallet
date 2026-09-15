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
    private const val KEY_CRT = "crt_effect"
    private const val KEY_TEXT_SCALE = "text_scale"
    private const val KEY_SWAP_PCT = "swap_custom_pct"
    private const val KEY_WEB_CHECK = "web_check"
    private const val KEY_AGENT_PRO = "agent_pro"

    val currency = mutableStateOf("USD")
    val onboarded = mutableStateOf(true)
    val watchtower = mutableStateOf(false)
    /** CRT / old-TV overlay on scanline themes (Phosphor). Reading mode, toggleable. */
    val crt = mutableStateOf(true)
    /** Global text-size multiplier (0.85–1.30). Applied via LocalDensity.fontScale. */
    val textScale = mutableStateOf(1f)

    /**
     * Your own slice of the balance, kept between trades.
     *
     * A quarter, a half and three quarters are somebody else's idea of how you
     * trade. This is the one you set once and then press, and it is worth storing
     * because the whole point of it is not typing the number again.
     * Zero means you have not set one.
     */
    val swapCustomPct = mutableStateOf(0)

    /**
     * Let the model read the web about a coin before the agent buys it.
     *
     * Off by default because it spends money that is not the trade: about a cent
     * per search on your own Anthropic key, on the one coin per purchase. See
     * [CoinCheck].
     */
    val webCheck = mutableStateOf(false)

    /**
     * The Agent tab, with everything on it.
     *
     * Off, the tab answers three questions and stops: is it working, what does it
     * hold, what did it do. On, it also shows the model, the collar's numbers,
     * the lane and targets, the shadow book, the live trace, and the bridge to an
     * agent on a computer. Same page, one switch, remembered.
     */
    val agentPro = mutableStateOf(false)

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        currency.value = p.getString(KEY_CURRENCY, null) ?: defaultCurrency()
        onboarded.value = p.getBoolean(KEY_ONBOARDED, false)
        watchtower.value = p.getBoolean(KEY_WATCH, false)
        crt.value = p.getBoolean(KEY_CRT, true)
        textScale.value = p.getFloat(KEY_TEXT_SCALE, 1f)
        swapCustomPct.value = p.getInt(KEY_SWAP_PCT, 0)
        webCheck.value = p.getBoolean(KEY_WEB_CHECK, false)
        agentPro.value = p.getBoolean(KEY_AGENT_PRO, false)
    }

    fun setAgentPro(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AGENT_PRO, on).apply()
        agentPro.value = on
    }

    fun setWebCheck(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WEB_CHECK, on).apply()
        webCheck.value = on
    }

    fun setSwapCustomPct(ctx: Context, pct: Int) {
        val v = pct.coerceIn(0, 100)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SWAP_PCT, v).apply()
        swapCustomPct.value = v
    }

    fun watchWallet(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_WATCH_WALLET, null)
    fun setWatchWallet(ctx: Context, owner: String) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_WATCH_WALLET, owner).apply() }
    fun setWatchtower(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WATCH, on).apply()
        watchtower.value = on
        if (on) Watchtower.enable(ctx) else Watchtower.disable(ctx)
    }

    fun setCrt(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CRT, on).apply()
        crt.value = on
    }

    fun setTextScale(ctx: Context, v: Float) {
        val c = v.coerceIn(0.85f, 1.30f)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_TEXT_SCALE, c).apply()
        textScale.value = c
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
