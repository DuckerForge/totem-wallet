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

    /** Whether the holdings card on Home is unrolled. Remembered, because a list
     *  somebody has deliberately closed should stay closed tomorrow morning. */
    val walletOpen = mutableStateOf(true)

    /**
     * Il nodo con cui questo telefono parla con Solana, quando la persona ne
     * porta uno suo.
     *
     * L'app nasce con un nodo compilato dentro, che e' di chi pubblica l'app e
     * che tutte le installazioni si dividono. Va benissimo finche' sono poche.
     * Con l'agente acceso un telefono fa qualche migliaio di chiamate al giorno,
     * e mille telefoni sono milioni: a quel punto il nodo di chi pubblica finisce,
     * e finisce **per tutti insieme**, compreso chi non ha l'agente acceso e sta
     * solo guardando il saldo.
     *
     * Quindi si puo' portare il proprio, come si porta la propria chiave del
     * modello. Vuoto vuol dire quello compilato, cioe' come prima.
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
     * Solo https, e solo un indirizzo che sta in piedi. Un nodo scritto male
     * spegnerebbe la catena per chi l'ha scritto, senza dire perche'.
     */
    fun setRpcUrl(ctx: Context, url: String) {
        val v = url.trim().takeIf { it.startsWith("https://") && it.length > 12 }.orEmpty()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_RPC, v.ifEmpty { null }).apply()
        rpcUrl.value = v
        applyRpc()
    }

    /**
     * Il proprio se c'e', quello compilato se no. Il pool sa qual e' il
     * proprio: lo mette per primo e resta dietro come ripiego.
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
     * Guest: the phone in somebody else's hands. Amounts are covered, the
     * actions that spend are off. In memory only: the door asks the print on
     * every return anyway, so a restart ends it.
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
    // Il ponte al posto del link.
    //
    // Il link e' il modo di **chiedere** soldi a qualcuno, e si usa quando c'e'
    // qualcuno dall'altra parte: e' una cosa che capita, non una cosa che fai.
    // Il ponte lo fai da solo, ed e' l'unico modo che questo portafoglio ha di
    // portare i soldi fuori da Solana. Chi lo vuole indietro lo rimette da
    // Personalizza: sono le stesse nove, cambia solo quale sta sulla prima
    // pagina.
    val DEFAULT_HOME_ACTIONS = listOf("SEND", "RECEIVE", "SWAP", "SCAN", "CROWD", "BRIDGE", "AGENT")

    /**
     * A hand over the screen (the proximity sensor) covers the numbers. **Off by default.**
     *
     * It was on, and it fired on the wrong thing: a thumb travelling up the
     * screen passes the sensor, so scrolling the wallet locked the numbers and
     * asked for a fingerprint. A privacy trick that goes off while you are
     * reading is not protecting anything, it is taking the page away. It stays
     * in settings for the people who want it, and for the demo, where a hand
     * deliberately laid over the top is exactly the gesture being shown.
     */
    fun coverToHide(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("cover_hide", false)
    fun setCoverToHide(ctx: Context, on: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("cover_hide", on).apply()

    /** Priority fee for the transactions we build ourselves, in micro‑lamports per compute unit. 0 = none. */
    fun speed(ctx: Context): Long = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("speed", 0L)
    fun setSpeed(ctx: Context, microLamports: Long) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("speed", microLamports).apply()
}
