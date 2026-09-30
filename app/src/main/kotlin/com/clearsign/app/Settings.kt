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
    private const val KEY_WALLET_OPEN = "wallet_open"
    private const val KEY_RPC = "rpc_url"

    val currency = mutableStateOf("USD")
    val onboarded = mutableStateOf(true)
    val watchtower = mutableStateOf(false)
    /** CRT / old-TV overlay on scanline themes (Phosphor). Reading mode, toggleable. */
    val crt = mutableStateOf(true)
    /** Global text-size multiplier (0.85–1.30). Applied via LocalDensity.fontScale. */
    val textScale = mutableStateOf(1f)

    /** The user's own swap percentage, next to 25/50/75. Zero: not set. */
    val swapCustomPct = mutableStateOf(0)

    /**
     * Let the model read the web about a coin before the agent buys it. Off by default: about a
     * cent per search on your own Anthropic key. See [CoinCheck].
     */
    val webCheck = mutableStateOf(false)

    /**
     * Full Agent tab. Off: status, holdings, history. On: also the model, the collar's limits,
     * lane and targets, shadow book, live trace and the computer bridge.
     */
    val agentPro = mutableStateOf(false)

    /** Whether Home's holdings card is expanded. Persisted, so a closed list stays closed. */
    val walletOpen = mutableStateOf(true)

    /**
     * The user's own RPC node; empty means the compiled one. The compiled node is shared by every
     * install, and with the agent on one phone makes thousands of calls a day: a thousand phones
     * would exhaust it for everyone.
     */
    val rpcUrl = mutableStateOf("")

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        Rpc.init(ctx)
        rpcUrl.value = p.getString(KEY_RPC, null).orEmpty()
        applyRpc()
        currency.value = p.getString(KEY_CURRENCY, null) ?: defaultCurrency()
        onboarded.value = p.getBoolean(KEY_ONBOARDED, false)
        watchtower.value = p.getBoolean(KEY_WATCH, false)
        crt.value = p.getBoolean(KEY_CRT, true)
        textScale.value = p.getFloat(KEY_TEXT_SCALE, 1f)
        swapCustomPct.value = p.getInt(KEY_SWAP_PCT, 0)
        webCheck.value = p.getBoolean(KEY_WEB_CHECK, false)
        agentPro.value = p.getBoolean(KEY_AGENT_PRO, false)
        walletOpen.value = p.getBoolean(KEY_WALLET_OPEN, true)
    }

    /** https only, with a length check: a mistyped node would silently cut off the chain. */
    fun setRpcUrl(ctx: Context, url: String) {
        val v = url.trim().takeIf { it.startsWith("https://") && it.length > 12 }.orEmpty()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_RPC, v.ifEmpty { null }).apply()
        rpcUrl.value = v
        applyRpc()
    }

    /** Own node if set, else the compiled one. The pool puts the own node first, compiled as fallback. */
    private fun applyRpc() {
        val own = rpcUrl.value.takeIf { it.isNotBlank() }
        Rpc.ownNode = own
        SolanaRpc.customRpc = own ?: BuildConfig.HELIUS_RPC_URL.takeIf { it.isNotBlank() }
    }

    fun setWalletOpen(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WALLET_OPEN, on).apply()
        walletOpen.value = on
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

    /** "Hold to hide" is said until it has been done once. */
    fun hideHintSeen(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("hide_hint_seen", false)
    fun setHideHintSeen(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("hide_hint_seen", true).apply()

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

    /** The eyes' voice: the phone reads the loop's lines aloud while that screen is open. Off by default. */
    fun eyesVoice(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("eyes_voice", false)
    fun setEyesVoice(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("eyes_voice", on).apply()
    }

    /**
     * Guest mode: amounts hidden, spending off. Memory only, so a restart ends it; the lock
     * asks for the fingerprint on every return anyway.
     */
    val guest = mutableStateOf(false)

    /** The action circles on the home, in the order chosen. "More" is always last and never in this list. */
    fun homeActions(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("home_actions", null)
        return raw?.split(',')?.filter { it.isNotBlank() } ?: DEFAULT_HOME_ACTIONS
    }
    fun setHomeActions(ctx: Context, names: List<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("home_actions", names.joinToString(",")).apply()
        homeActionsTick.value++
    }
    val homeActionsTick = mutableStateOf(0)
    // BRIDGE instead of the payment link: the link needs someone on the other side, the bridge
    // is done alone and is the only way money leaves Solana here. Customize brings the link back.
    // WIDGET instead of AGENT: the agent already has a tab, and the bubble and widget live
    // outside the app, so the home screen is where people look for them.
    val DEFAULT_HOME_ACTIONS = listOf("SEND", "RECEIVE", "SWAP", "SCAN", "CROWD", "BRIDGE", "WIDGET")

    /** Priority fee for the transactions we build ourselves, in micro‑lamports per compute unit. 0 = none. */
    fun speed(ctx: Context): Long = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("speed", 0L)
    fun setSpeed(ctx: Context, microLamports: Long) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("speed", microLamports).apply()
}
