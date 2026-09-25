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

    /**
     * Your own slice of the balance, kept between trades. A quarter, a half and three quarters
     * are somebody else's idea; this is the one you set once and press. Zero: not set.
     */
    val swapCustomPct = mutableStateOf(0)

    /**
     * Let the model read the web about a coin before the agent buys it. Off by default: about a
     * cent per search on your own Anthropic key. See [CoinCheck].
     */
    val webCheck = mutableStateOf(false)

    /**
     * The Agent tab with everything on it. Off, three questions: is it working, what does it
     * hold, what did it do. On, also the model, the collar's numbers, lane and targets, the
     * shadow book, the live trace, the bridge to a computer. One switch, remembered.
     */
    val agentPro = mutableStateOf(false)

    /** Whether the holdings card on Home is unrolled. Remembered, because a list
     *  somebody has deliberately closed should stay closed tomorrow morning. */
    val walletOpen = mutableStateOf(true)

    /**
     * The node this phone talks to Solana with, when the person brings their own. The app ships
     * with one compiled in, the publisher's, shared by every install: fine while they are few.
     * With the agent on a phone makes thousands of calls a day and a thousand phones make
     * millions, and the publisher's node runs out for everyone at once, including whoever is
     * only looking at a balance. Empty means the compiled one.
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

    /**
     * Only https, and only a URL that stands up: a misspelled node would switch the chain off
     * for whoever typed it, without saying why.
     */
    fun setRpcUrl(ctx: Context, url: String) {
        val v = url.trim().takeIf { it.startsWith("https://") && it.length > 12 }.orEmpty()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_RPC, v.ifEmpty { null }).apply()
        rpcUrl.value = v
        applyRpc()
    }

    /**
     * The own node if there is one, else the compiled one. The pool knows which is own: first
     * in line, with the compiled one behind as fallback.
     */
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
     * Guest: the phone in somebody else's hands. Amounts covered, spending actions off. In
     * memory only: the door asks the print on every return, so a restart ends it.
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
    // The bridge in place of the link. The link asks somebody for money and is used when there
    // is somebody on the other side: a thing that happens, not a thing you do. The bridge you do
    // alone, and it is the only way this wallet takes money out of Solana. Customize brings the
    // link back: same nine actions, only the front page changes.
    // WIDGET and not AGENT: the agent already has one of the five tabs along the bottom, and a
    // shortcut that only switches tab spends a slot to say what the tab bar already says. The
    // bubble and the widget were two taps down inside More, and they are the parts of Totem that
    // live outside Totem, so the front page is where someone would look for them.
    val DEFAULT_HOME_ACTIONS = listOf("SEND", "RECEIVE", "SWAP", "SCAN", "CROWD", "BRIDGE", "WIDGET")

    /**
     * A hand over the screen (the proximity sensor) covers the numbers. Off by default: it fired
     * on the wrong thing, a thumb traveling up the screen passes the sensor, so scrolling locked
     * the numbers and asked for a fingerprint. It stays for those who want it, and for the demo,
     * where a hand laid over the top is exactly the gesture being shown.
     */
    fun coverToHide(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("cover_hide", false)
    fun setCoverToHide(ctx: Context, on: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("cover_hide", on).apply()

    /** Priority fee for the transactions we build ourselves, in micro‑lamports per compute unit. 0 = none. */
    fun speed(ctx: Context): Long = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("speed", 0L)
    fun setSpeed(ctx: Context, microLamports: Long) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("speed", microLamports).apply()
}
