@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.clearsign.core.ClearSignFlow
import com.clearsign.core.Receipt
import com.clearsign.core.ReceiptBuilder
import com.clearsign.core.Severity
import com.clearsign.core.TrustLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home: the wallet's own screen (a dApp never opens this, MWA routes to
 * [MobileWalletAdapterActivity]). Accounts, ledger, contacts, and the offline demo
 * scenarios at the bottom for a pitch without a dApp.
 */
class MainActivity : ComponentActivity() {

    private lateinit var bridge: ActivityResultBridge
    private lateinit var signer: SeedVaultSigner

    /* A payment request read by tapping, or arrived as a link. */
    var incoming by mutableStateOf<com.clearsign.core.PayRequest?>(null)
    /* Stamped when a tap carried no payment request, so the screen can say so. */
    var tapMiss by mutableStateOf(0L)

    /**
     * Where a notification, a widget or the bubble asked us to land.
     *
     * State on the activity rather than a read of `intent`, because the read used to happen
     * inside `remember`: first composition only. With the app already open, the bubble's
     * buttons went through onNewIntent, updated the intent nobody read again, and the screen
     * sat where it was. Cleared once the screen has acted on it, so going back and forth does
     * not re-open the same sheet.
     */
    var openRequest by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readRequest(intent)
        openRequest = intent.getStringExtra("open")
    }

    /*
     * Reader mode: while Totem is in front, holding it against a tag or another phone reads
     * the request into the send form. Only a read; the ordinary receipt still has to be approved.
     */
    /** The sticker writer borrows the radio; this is how it gives it back. */
    internal fun resumeReader() = startReaderMode()

    private fun startReaderMode() {
        // Reading and pretending to be a tag share one radio, and reader mode wins: with it on,
        // this phone polls and emulates nothing. While the tap screen is armed we stay out of
        // the way, or the other phone finds no card, which is what "the tap does nothing" was.
        if (TapService.armed.value) return
        val nfc = android.nfc.NfcAdapter.getDefaultAdapter(this) ?: return
        nfc.enableReaderMode(
            this,
            { tag ->
                val ndef = android.nfc.tech.Ndef.get(tag) ?: return@enableReaderMode
                val message = runCatching {
                    ndef.connect()
                    ndef.ndefMessage ?: ndef.cachedNdefMessage
                }.getOrNull()
                runCatching { ndef.close() }
                val uri = message?.records?.firstNotNullOfOrNull { r ->
                    runCatching { com.clearsign.core.Ndef.uriOf(r.payload.let { byteArrayOf() } + r.toByteArray()) }.getOrNull()
                        ?: runCatching { r.toUri()?.toString() }.getOrNull()
                }
                val contact = uri?.let { com.clearsign.core.ContactTap.parse(it) }
                val parsed = if (contact == null) uri?.let { com.clearsign.core.SolanaPay.parse(it) } else null
                runOnUiThread {
                    when {
                        contact != null -> { Haptics.tick(this); ContactInbox.incoming.value = contact }
                        parsed != null -> { Haptics.tick(this); incoming = parsed }
                        else -> tapMiss = System.currentTimeMillis()
                    }
                }
            },
            android.nfc.NfcAdapter.FLAG_READER_NFC_A or android.nfc.NfcAdapter.FLAG_READER_NFC_B or
                android.nfc.NfcAdapter.FLAG_READER_NFC_F or android.nfc.NfcAdapter.FLAG_READER_NFC_V or
                android.nfc.NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null,
        )
    }

    private fun stopReaderMode() {
        runCatching { android.nfc.NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this) }
    }

    private fun readRequest(intent: android.content.Intent?) {
        val data = intent?.data ?: return
        val scheme = data.scheme ?: return
        // `solana:` from a tag or a chat, and the web form of the same request from a
        // link somebody tapped. Both end up in the send form, neither signs anything.
        // A Blink: its own scheme, or a dial.to link, or any link carrying ?action=.
        if (scheme.equals("solana-action", ignoreCase = true) || Blinks.looksLike(data.toString())) { Blinks.incoming.value = data.toString(); return }
        if (!scheme.equals("solana", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) return
        incoming = com.clearsign.core.SolanaPay.parse(data.toString()) ?: return
    }

    override fun onPause() {
        super.onPause()
        stopReaderMode()
        runCatching { getSystemService(android.hardware.SensorManager::class.java).unregisterListener(proximity) }
    }

    override fun onDestroy() {
        super.onDestroy()
        Voice.release()
    }

    // A hand over the top of the screen, where the proximity sensor is, covers
    // the numbers: the same guest mode, with the print to come back.
    private val proximity = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val near = e.values.firstOrNull()?.let { it < (e.sensor.maximumRange.coerceAtLeast(1f) / 2f) } ?: false
            if (near && Settings.coverToHide(this@MainActivity) && !Settings.guest.value && Settings.watchWallet(this@MainActivity) != null) {
                Settings.guest.value = true
                Haptics.success(this@MainActivity)
            }
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }

    override fun onResume() {
        super.onResume()
        startReaderMode()
        runCatching {
            val sm = getSystemService(android.hardware.SensorManager::class.java)
            sm.getDefaultSensor(android.hardware.Sensor.TYPE_PROXIMITY)?.let { sm.registerListener(proximity, it, android.hardware.SensorManager.SENSOR_DELAY_NORMAL) }
        }
        // The widget mirrors what the app knows; refresh it whenever we come to the front.
        lifecycleScope.launch { runCatching { HealthWidgetData.refresh(this@MainActivity) } }
        // A paired agent link should be listening whenever the phone is up.
        if (AgentLink.current(this) != null && SessionWallet.current(this) != null) AgentLinkService.start(this)
        // And so should the trader. Without this the loop came back only at the
        // next fifteen-minute keeper window, so after force-stopping the app it
        // read as switched on and doing nothing at all.
        TraderKeeper.sync(this)
        // Independent of trading: it reads other people's wallets and spends nothing.
        SeekerKeeper.sync(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Themes.load(this)
        Settings.load(this)
        Pro.load(this)
        // Hand the radio back and forth as the tap screen arms and disarms. Started here, not in
        // onResume: `lifecycleScope` lives until destroy and `repeatOnLifecycle` never returns,
        // so one per resume left a permanent collector each time, ten after ten trips home.
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                TapService.armed.collect { emitting -> if (emitting) stopReaderMode() else startReaderMode() }
            }
        }
        // Must be registered before the Activity is STARTED.
        bridge = ActivityResultBridge(this)
        signer = SeedVaultSigner(this, bridge)
        enableEdgeToEdge()
        setContent { ScaledText { HomeScreen(signer) } }
        // What this phone already learned about coins: names, decimals, icons. They never
        // change, and without this all were fetched again on every start. Read after the first
        // frame and on IO: two JSON files on the main thread before `setContent` kept the splash
        // black, longer every month. One image loader for every logo, with a memory cache.
        coil.Coil.setImageLoader {
            coil.ImageLoader.Builder(this)
                .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.08).build() }
                .crossfade(false)
                .build()
        }
        window.decorView.post {
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { JupiterTokens.warmDisk(this@MainActivity) }
                runCatching { Gecko.warmPools(this@MainActivity) }
            }
        }
    }
}

private data class HomeAccount(val account: SvAccount, val lamports: Long?, val tokens: Int)

private enum class Tab { WALLET, MARKET, AGENT, RECEIPTS, SETTINGS }

@Composable
fun HomeScreen(signer: SeedVaultSigner) {
    HaloRoot {
        val ctx = LocalContext.current
        var onboarded by remember { mutableStateOf(Settings.onboarded.value) }
        if (!onboarded) {
            Onboarding { Settings.setOnboarded(ctx); onboarded = true }
            return@HaloRoot
        }
        val scope = rememberCoroutineScope()
        // Wallet state lives at the root so switching tabs never drops the Seed Vault session.
        var tab by androidx.compose.runtime.saveable.rememberSaveable {
            mutableStateOf(if ((ctx as? android.app.Activity)?.intent?.getStringExtra("open") == "agent") Tab.AGENT else Tab.WALLET)
        }
        var accounts by remember { mutableStateOf<List<HomeAccount>>(emptyList()) }
        var busy by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf<String?>(null) }
        var contacts by remember { mutableStateOf(Contacts.allowlist(ctx)) }
        // A widget quick action asks for a specific sheet.
        val act = ctx as? MainActivity
        // Seeded from the launch intent, then kept in step by onNewIntent.
        LaunchedEffect(Unit) { if (act?.openRequest == null) act?.openRequest = act?.intent?.getStringExtra("open") }
        val requested = act?.openRequest
        var showSend by remember { mutableStateOf(false) }
        // A contact tapped in Settings: the send sheet opens already addressed.
        var sendTo by remember { mutableStateOf<String?>(null) }
        var showReceive by remember { mutableStateOf(false) }
        var showSwap by remember { mutableStateOf(false) }
        // A coin chosen in the market tab: the swap opens already pointing at it.
        var swapMint by remember { mutableStateOf<String?>(null) }
        // Asked from the card of a coin you hold: which one to sell, which one to send.
        var swapSellMint by remember { mutableStateOf<String?>(null) }
        var sendMint by remember { mutableStateOf<String?>(null) }
        var showChat by remember { mutableStateOf(false) }
        var showGift by remember { mutableStateOf(false) }
        var showTap by remember { mutableStateOf(false) }
        var showMore by remember { mutableStateOf(false) }
        var showBridge by remember { mutableStateOf(false) }
        var bridgeMemo by remember { mutableStateOf<String?>(null) }
        // The deal already struck with RocketX, traveling with the payment up to the signature.
        var bridgeDeal by remember { mutableStateOf<RocketX.Deal?>(null) }
        var showBridgeHistory by remember { mutableStateOf(false) }
        // Private send enters from Send but runs through the bridge: it carries
        // along what was already typed over there.
        var privateSend by remember { mutableStateOf<Pair<String, String>?>(null) }
        var showContactTap by remember { mutableStateOf(false) }
        var showCustomize by remember { mutableStateOf(false) }
        var showCompanion by remember { mutableStateOf(false) }
        var showChip by remember { mutableStateOf(false) }
        var guestNote by remember { mutableStateOf(0L) }
        // A notification about somebody you follow opens the feed straight on that
        // coin, with the receipt already building. The alert is the start of the
        // loop, not a link to somewhere you then have to navigate.
        var showCrowd by remember { mutableStateOf(false) }
        var crowdMint by remember { mutableStateOf((ctx as? android.app.Activity)?.intent?.getStringExtra("mint")) }
        var showHealth by remember { mutableStateOf(false) }
        var showPnl by remember { mutableStateOf(false) }
        var headline by remember { mutableStateOf<String?>(null) }
        // One scroll state for the wallet, hoisted so the header and the bottom
        // bar can both react to it. Nothing in the app did this before.
        var scanError by remember { mutableStateOf<String?>(null) }
        val scanHome = rememberAgentScan { scanError = it }
        val walletScroll = rememberScrollState()
        val density = androidx.compose.ui.platform.LocalDensity.current
        // A state, not a number: readers use it inside `graphicsLayer` or `layout`, so
        // scrolling moves layers and does not recompose the page. Read here as a Float,
        // the whole `HomeScreen` recomposed every frame.
        val collapse = remember {
            derivedStateOf { (walletScroll.value / with(density) { 180.dp.toPx() }).coerceIn(0f, 1f) }
        }
        /**
         * One place that answers "open this", wherever the ask came from: a notification, the
         * widget, or a button on the bubble. It used to be four separate reads of the intent
         * done inside `remember`, so a second ask while the app was already up changed nothing,
         * and "companion" was never listed at all: every bubble button landed on the home.
         *
         * Cleared after acting, or coming back to the app would re-open the same sheet.
         */
        LaunchedEffect(requested) {
            when (requested) {
                null -> {}
                "send" -> showSend = true
                "receive" -> showReceive = true
                "swap" -> showSwap = true
                "crowd" -> showCrowd = true
                "companion" -> showCompanion = true
                "agent" -> tab = Tab.AGENT
                else -> {}
            }
            if (requested != null) act?.openRequest = null
        }

        // A tapped or linked request opens the ordinary send form, already filled in.
        val request = (ctx as? MainActivity)?.incoming
        LaunchedEffect(request) { if (request != null) showSend = true }
        LaunchedEffect(Unit) { contacts = Contacts.allowlist(ctx); Exports.clean(ctx) }
        /**
         * Unlock: what the door does when this phone has been here before. The fingerprint
         * every time, the way Jupiter does it; the address is remembered, so the print only buys
         * that the person holding the phone is you. Signing later calls `ensureAccount`, which
         * authorizes the vault when something is actually signed.
         */
        suspend fun unlockAsking(saved: String): Presence.Result {
            val act = ctx as? android.app.Activity ?: return Presence.Result.FAILED
            val r = Presence.ask(act, ctx.getString(R.string.lock_title), ctx.getString(R.string.lock_sub))
            if (r != Presence.Result.OK) return r
            val key = Base58.decodePubkey(saved) ?: return Presence.Result.FAILED
            val shell = SvAccount(label = null, derivationUri = android.net.Uri.EMPTY, pubkeyBase58 = saved, pubkeyBytes = key)
            val bal = withContext(Dispatchers.IO) { runCatching { SolanaRpc.assetsSummaryMulti(SolanaRpc.urlFor(null), listOf(saved)) }.getOrNull() }
            val (lam, toks) = bal?.get(saved) ?: (null to 0)
            accounts = listOf(HomeAccount(shell, lam, toks))
            return Presence.Result.OK
        }

        // Leaving the app locks it. Coming back always goes through the door, so
        // "sometimes it asks and sometimes it doesn't" stops being a thing: it
        // asks, always.
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        androidx.compose.runtime.DisposableEffect(lifecycle) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                // Scan and share are ours: no leaving, no relocking.
                if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP && !Door.consumeHold()) accounts = emptyList()
            }
            lifecycle.addObserver(obs)
            onDispose { lifecycle.removeObserver(obs) }
        }
        val owner = accounts.firstOrNull()?.account?.pubkeyBase58
        // The bubble comes back on its own when asked to.
        LaunchedEffect(owner) { if (owner != null && CompanionPrefs.autoStart(ctx) && CompanionService.canRun(ctx)) CompanionService.start(ctx) }

        fun connect() {
            busy = true; status = null
            scope.launch {
                try {
                    val list = signer.authorizeAndListAccounts()
                    signer.selectAccount(list.first())
                    Settings.setWatchWallet(ctx, list.first().pubkeyBase58)
                    val rpc = SolanaRpc.urlFor(null)
                    val bal = withContext(Dispatchers.IO) { SolanaRpc.assetsSummaryMulti(rpc, list.map { it.pubkeyBase58 }) }
                    accounts = list.map { a -> val (l, t) = bal[a.pubkeyBase58] ?: (null to 0); HomeAccount(a, l, t) }
                } catch (e: Exception) {
                    status = e.message ?: ctx.getString(R.string.connect_failed)
                } finally { busy = false }
            }
        }

        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Halo.ground2, Halo.ground)))
                .background(Brush.radialGradient(listOf(Halo.cyan.copy(alpha = 0.14f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(900f, -100f), radius = 900f))
                .haloSurface(),
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // With no wallet there is nothing on any tab, and the old screen said so with a small
                    // card over two thirds of empty page. One door, the whole screen, no tab bar until there
                    // is something behind it. It opens rather than cuts: the scene fades and grows a touch.
                    androidx.compose.animation.AnimatedContent(
                        targetState = accounts.isEmpty(),
                        transitionSpec = {
                            (androidx.compose.animation.fadeIn(tween(520)) + androidx.compose.animation.scaleIn(tween(520), initialScale = 0.97f))
                                .togetherWith(androidx.compose.animation.fadeOut(tween(420)) + androidx.compose.animation.scaleOut(tween(420), targetScale = 1.04f))
                        },
                        label = "door",
                    ) { noWallet ->
                    if (noWallet) {
                        val saved = remember { Settings.watchWallet(ctx) }
                        fun ask() {
                            busy = true; status = null
                            scope.launch {
                                val r = runCatching { unlockAsking(saved!!) }.getOrDefault(Presence.Result.FAILED)
                                busy = false
                                // Closing the sheet is not a failed print. It used to answer
                                // "not recognized" to someone who had simply tapped away, and
                                // the door then sat there accusing them. The button is the retry.
                                if (r == Presence.Result.FAILED) status = ctx.getString(R.string.lock_failed)
                            }
                        }
                        // A phone that has been here before asks for the print on its own, once. The
                        // button is the retry, for a failed print or a dismissed prompt.
                        //
                        // Not on the first frame, though. The system's fingerprint sheet is a secure
                        // window: it covers the door and blacks out anything that tries to record the
                        // screen. Asked immediately, the mark writing itself was seen by nobody, not
                        // the person holding the phone and not a camera. A second and a quarter is a
                        // beat, not a wait, and the sheet still arrives before a thumb can reach it.
                        LaunchedEffect(saved) {
                            if (saved != null && !busy) { kotlinx.coroutines.delay(1250); if (!busy) ask() }
                        }
                        ConnectDoor(busy, status, returning = saved != null) {
                            if (saved == null) connect() else ask()
                        }
                    }
                    else Box(Modifier.fillMaxSize()) { when (tab) {
                        Tab.WALLET -> androidx.compose.runtime.CompositionLocalProvider(LocalEntrance provides remember { java.util.concurrent.atomic.AtomicInteger() }) {
                            // Pull down: everything on the page is asked again.
                            var pulling by remember { mutableStateOf(false) }
                            var reload by remember { mutableStateOf(0) }
                            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                                isRefreshing = pulling,
                                onRefresh = {
                                    pulling = true; reload++
                                    scope.launch {
                                        val list = accounts
                                        val bal = withContext(Dispatchers.IO) {
                                            runCatching { SolanaRpc.assetsSummaryMulti(SolanaRpc.urlFor(null), list.map { it.account.pubkeyBase58 }) }.getOrNull()
                                        }
                                        if (bal != null) accounts = list.map { a -> val (l, t) = bal[a.account.pubkeyBase58] ?: (null to 0); HomeAccount(a.account, l, t) }
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            ) {
                            Column(
                                Modifier.fillMaxSize().verticalScroll(walletScroll).padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                HomeHeader(accounts.firstOrNull()?.account, headline, collapse, onScan = { scanHome() }, onChip = { showChip = true })

                                // ---- the balance, the eight actions, the holdings --------------
                                if (accounts.isNotEmpty()) {
                                    WalletHero(
                                        owner, signer, collapse,
                                        reload = reload, onLoaded = { pulling = false },
                                        onSendCoin = { mint -> sendMint = mint; showSend = true },
                                        onSwapCoin = { mint -> swapSellMint = mint; showSwap = true },
                                        onAction = { a ->
                                            // A guest can receive and look; nothing that spends or sets.
                                            if (Settings.guest.value && a !in setOf(HomeAction.RECEIVE, HomeAction.SCAN, HomeAction.CROWD)) {
                                                guestNote = System.currentTimeMillis(); return@WalletHero
                                            }
                                            when (a) {
                                                HomeAction.SEND -> showSend = true
                                                HomeAction.RECEIVE -> showReceive = true
                                                HomeAction.SWAP -> showSwap = true
                                                HomeAction.SCAN -> scanHome()
                                                HomeAction.CROWD -> showCrowd = true
                                                HomeAction.TAP -> showTap = true
                                                HomeAction.LINK -> showGift = true
                                                HomeAction.WIDGET -> showCompanion = true
                                                HomeAction.BRIDGE -> showBridge = true
                                                HomeAction.MORE -> showMore = true
                                            }
                                        },
                                        onPnl = { showPnl = true },
                                        onTotal = { headline = it },
                                    )
                                    if (guestNote > 0L && Settings.guest.value) Banner(stringResource(R.string.guest_blocked), Halo.amber, HIcon.LOCK)
                                    RecentReceiptsCard { tab = Tab.RECEIPTS }
                                    AgentGlanceCard { tab = Tab.AGENT }
                                }

                // ---- Wallet ---------------------------------------------------
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle(stringResource(R.string.home_wallet_hdr), stringResource(R.string.home_wallet_sub), HIcon.WALLET)
                        run {
                            accounts.forEach { h ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(h.account.pubkeyBase58, 38.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(h.account.label ?: shorten(h.account.pubkeyBase58), fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Halo.ink)
                                        Text(shorten(h.account.pubkeyBase58, 6), fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, color = Halo.muted)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(h.lamports?.let { fmtSol(it, 4) + " SOL" } ?: "n/d", fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Halo.mint, style = Tabular)
                                        if (h.tokens > 0) Text(stringResource(R.string.tokens_n, h.tokens), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted)
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HaloIcon(HIcon.SHIELD_LOCK, Halo.mint, 16.dp); Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.home_keys_note), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                            }
                            }
                        }
                        Tab.MARKET -> MarketScreen(owner = owner, signer = signer, onBuy = { mint -> swapMint = mint; tab = Tab.WALLET })
                        Tab.AGENT -> AgentScreen(owner, signer, onChat = { showChat = true })
                        Tab.RECEIPTS -> LedgerScreen()
                        Tab.SETTINGS -> SettingsScreen(signer, owner) { SecurityTools(signer, owner, contacts) { addr -> sendTo = addr; showSend = true } }
                    } }
                    }
                }
                if (accounts.isNotEmpty()) BottomBar(tab, collapse) { t ->
                    // Agent and settings stay behind the print for a guest.
                    if (Settings.guest.value && (t == Tab.AGENT || t == Tab.SETTINGS)) guestNote = System.currentTimeMillis() else tab = t
                }
            }
            // A page, like Scout: it sits over everything, tab bar included. Inside
            // this Box so it fills the same height the tabs do.
            if (showChat) ChatScreen { showChat = false }
        }
        Proof.incoming.value?.let { text -> ProofCheckSheet(text, owner) { Proof.incoming.value = null } }
        Blinks.incoming.value?.let { link -> if (owner != null) BlinkSheet(link, signer, owner) { Blinks.incoming.value = null } }
        if (ContactInbox.incoming.value != null && owner != null && !showContactTap) showContactTap = true
        if (showContactTap && owner != null) ContactTapSheet(owner, onSaved = { contacts = Contacts.allowlist(ctx) }) { showContactTap = false; ContactInbox.incoming.value = null }
        val first = accounts.firstOrNull()?.account
        if (showSend && first != null) {
            SendSheet(
                signer, first.pubkeyBase58,
                prefillTo = request?.recipient ?: sendTo, prefillAmount = request?.amount?.let { fmtUi(it) },
                prefillMint = request?.let { it.mint ?: com.clearsign.core.NATIVE_SOL_MINT } ?: sendMint,
                prefillMemo = bridgeMemo,
                deal = bridgeDeal,
                onGift = { showSend = false; showGift = true },
                onPrivate = { to, amt -> showSend = false; privateSend = to to amt; showBridge = true },
            ) { showSend = false; sendTo = null; sendMint = null; bridgeMemo = null; bridgeDeal = null; (ctx as? MainActivity)?.incoming = null }
        }
        if (showTap && owner != null) TapSheet(owner) { showTap = false }
        // A page, not a sheet: it sits over everything, tab bar included, because
        // it is somewhere you go rather than something you peek at.
        if (showCrowd) {
            // The feed is the terminal now: `onBuy` is only the way out for the
            // cases the panel cannot serve, and the panel serves nearly all of them.
            CrowdPage(
                owner = owner, signer = signer, openMint = crowdMint,
                onBuy = { mint -> showCrowd = false; swapMint = mint; showSwap = true },
            ) { showCrowd = false; crowdMint = null }
        }

        if (showMore) {
            val tick by Settings.homeActionsTick
            val onHome = remember(tick) { Settings.homeActions(ctx).toSet() }
            MoreSheet(
                hidden = CHOOSABLE_ACTIONS.filter { it.name !in onHome && it !in setOf(HomeAction.TAP, HomeAction.LINK, HomeAction.BRIDGE) },
                onAction = { a ->
                    showMore = false
                    when (a) {
                        HomeAction.SEND -> showSend = true
                        HomeAction.RECEIVE -> showReceive = true
                        HomeAction.SWAP -> showSwap = true
                        HomeAction.SCAN -> scanHome()
                        HomeAction.CROWD -> showCrowd = true
                        HomeAction.WIDGET -> showCompanion = true
                        else -> {}
                    }
                },
                onTap = { showMore = false; showTap = true },
                onHealth = { showMore = false; showHealth = true },
                onContacts = { showMore = false; tab = Tab.SETTINGS },
                onSettings = { showMore = false; tab = Tab.SETTINGS },
                onBridge = { showMore = false; showBridge = true },
                onGift = { showMore = false; showGift = true },
                onContactTap = { showMore = false; showContactTap = true },
                onCompanion = { showMore = false; showCompanion = true },
            ) { showMore = false }
        }
        // These three used to live inside the block above, which meant closing
        // the More sheet to open one of them took the new sheet out of the tree
        // with it: nothing appeared until More was opened a second time.
        if (showCustomize) HomeActionsSheet { showCustomize = false }
        if (showCompanion) CompanionPage(owner) { showCompanion = false }
        if (showChip && owner != null) WalletChipSheet(owner, onSettings = { tab = Tab.SETTINGS }) { showChip = false }
        if (showBridge && owner != null) {
            BridgeSheet(
                owner,
                onSend = { req, memo, deal ->
                    showBridge = false; bridgeMemo = memo; bridgeDeal = deal
                    (ctx as? MainActivity)?.incoming = req; showSend = true
                },
                onHistory = { showBridge = false; showBridgeHistory = true },
                startPrivate = privateSend != null,
                startDest = privateSend?.first.orEmpty(),
                startAmount = privateSend?.second.orEmpty(),
            ) { showBridge = false; privateSend = null }
        }
        if (showBridgeHistory) {
            BridgeHistorySheet { showBridgeHistory = false }
        }
        if (showHealth) HealthSheet(owner) { showHealth = false }
        if (showPnl) PnlSheet { showPnl = false }
        if (showGift && owner != null) GiftSheet(signer, owner) { showGift = false }
        if (showReceive && first != null) ReceiveSheet(first.pubkeyBase58, first.label, onTap = { showReceive = false; showTap = true }) { showReceive = false }
        if ((showSwap || swapMint != null) && first != null) {
            SwapSheet(signer, first.pubkeyBase58, buyMint = swapMint, sellMint = swapSellMint) { showSwap = false; swapMint = null; swapSellMint = null }
        }
    }
}


/**
 * Wallet health, delegations, contacts and the offline demo. They stacked under the
 * portfolio; they live in Settings now so the home stays a wallet, not a dashboard.
 */
@Composable
private fun SecurityTools(signer: SeedVaultSigner, owner: String?, contacts: Map<String, String>, onSend: (String) -> Unit) {
    var showDemo by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // ---- Try an attack: the receipt on a drainer, no dApp needed ----
                // It opens over everything, like every other receipt in the app. Grown inside
                // this page it sat under a screenful of settings, and the thing it exists to
                // show, the risk and the button that refuses it, was below the fold.
                GlassCard {
                    Row(Modifier.fillMaxWidth().clickable { showDemo = true }, verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle(stringResource(R.string.home_demo_hdr), stringResource(R.string.home_demo_sub), HIcon.FLASK)
                        Spacer(Modifier.weight(1f))
                        HaloIcon(HIcon.CHEVRON_RIGHT, Halo.muted, 18.dp)
                    }
                }
                if (showDemo) {
                    PayOverlay(
                        title = stringResource(R.string.home_demo_hdr),
                        hint = stringResource(R.string.demo_offline_note),
                        onBack = { showDemo = false },
                    ) { DemoSection(signer, owner) }
                }

                // ---- Wallet health (the score) --------------------------------
                WalletHealthCard(owner)
                ForgottenMoneyCard(owner)

                // ---- Delegations & accounts (the one-tap fixes) ---------------
                AccountsCard(signer, owner)

                // ---- Contacts -------------------------------------------------
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle(stringResource(R.string.home_contacts_hdr), if (contacts.isEmpty()) stringResource(R.string.none) else "${contacts.size}", HIcon.CONTACTS)
                        if (contacts.isEmpty()) EmptyLine(HIcon.PEOPLE, stringResource(R.string.contacts_empty_note))
                        val vctx = LocalContext.current
                        val verified = remember(contacts) { Contacts.verified(vctx) }
                        // A contact is somewhere money goes: the row opens the
                        // send sheet with the address already in it.
                        contacts.entries.take(20).forEach { (addr, label) ->
                            HaloRow(
                                label, shorten(addr, 6) + (if (addr in verified) " · " + stringResource(R.string.ctap_verified) else ""),
                                leading = { Avatar(addr, 30.dp) },
                                trailing = { TrustChip(TrustLevel.TRUSTED) },
                            ) { onSend(addr) }
                        }
                        if (owner != null) {
                            var showTapX by remember { mutableStateOf(false) }
                            GhostButton(stringResource(R.string.ctap_open), Modifier.fillMaxWidth(), HIcon.NFC, tint = Halo.cyan) { showTapX = true }
                            if (showTapX) ContactTapSheet(owner, onSaved = {}) { showTapX = false; ContactInbox.incoming.value = null }
                        }
                    }
                }

    }
}

/**
 * The door, when there is no wallet yet. It was a card at the top of the Wallet tab with
 * an empty page under it and four tabs leading nowhere. Nothing works without a key, so
 * until there is one there is one screen and one thing to do.
 */
@Composable
private fun ConnectDoor(busy: Boolean, status: String?, returning: Boolean, onConnect: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp).padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // High on the page on purpose: the fingerprint sheet covers the bottom half, and the
        // mark and the sentence must stay readable behind it. The name alone: the launcher art
        // was a light square on a dark screen, pulling the eye from what is worth watching.
        Spacer(Modifier.weight(0.08f))
        // The one orchestrated moment in the app: the name comes up out of the
        // dark like a light being switched on above it. Once, on arrival, and
        // never again — everywhere else motion answers something you did.
        val lit = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(Unit) { lit.animateTo(1f, tween(1200, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
        Box(contentAlignment = Alignment.Center) {
            // The beam itself: a soft pool of light that opens above the letters.
            Box(
                Modifier.matchParentSize().scale(1f + 2.4f * lit.value, 1f + 1.2f * lit.value)
                    .background(
                        Brush.radialGradient(
                            listOf(Halo.cyan.copy(alpha = 0.22f * lit.value), Color.Transparent),
                        ),
                    ),
            )
            Text(
                stringResource(R.string.door_title).uppercase(),
                style = HaloType.screen,
                // The name in the theme's own colour, not in ink: on a light theme a black
                // headline over a white page had nothing to do with the mark under it or the
                // button below, and the one page that is only the brand read as a form.
                color = Halo.mint.copy(alpha = 0.22f + 0.78f * lit.value),
                letterSpacing = 3.sp,
            )
        }
        // The name and nothing else. The sentence under it explained the scene, and a scene that
        // needs explaining is a worse scene. What is out there arrives, asks, and the liar breaks
        // on the glass: the whole pitch, drawn.
        Spacer(Modifier.height(8.dp))
        // Shown, not told: everything comes to this one phone and asks, and the request that
        // lies stops at the glass. The scene takes the whole middle: planet's limb above the
        // button, the phone holding station in the center.
        GateDemo(Modifier.weight(1f).heightIn(min = 220.dp), opening = busy)
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            when {
                busy -> stringResource(R.string.connecting)
                returning -> stringResource(R.string.lock_open)
                else -> stringResource(R.string.connect_seed_vault)
            },
            danger = false, enabled = !busy, icon = HIcon.FINGERPRINT,
        ) { onConnect() }
        status?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Halo.red, fontFamily = Inter, fontSize = 12.5.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** Five tabs on a hairline-topped bar; the active one sits on a soft pill. */
@Composable
private fun BottomBar(tab: Tab, collapse: androidx.compose.runtime.State<Float>, onSelect: (Tab) -> Unit) {
    val ctx = LocalContext.current
    Row(
        // Only the top line: a border on four sides made the bar a card, and a bar is
        // not a card.
        Modifier.fillMaxWidth().background(Halo.card)
            .drawBehind { drawLine(Halo.stroke, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        // The market icon says how the market is: a line going up in the accent
        // when the big coins are up over the day, going down in red when they
        // are down. Weighted by size, so one small coin cannot turn it.
        val marketUp = remember(tab) {
            val top = Market.cachedTop().take(20).filter { it.marketCap != null && it.change24h != null }
            if (top.isEmpty()) true else top.sumOf { it.marketCap!! * it.change24h!! } >= 0
        }
        listOf(
            Tab.WALLET to (HIcon.WALLET to R.string.tab_wallet),
            Tab.MARKET to ((if (marketUp) HIcon.CHART else HIcon.CHART_DOWN) to R.string.tab_market),
            Tab.AGENT to (HIcon.AGENT to R.string.tab_agent),
            Tab.RECEIPTS to (HIcon.RECEIPT to R.string.tab_receipts),
            Tab.SETTINGS to (HIcon.SETTINGS to R.string.tab_settings),
        ).forEach { (t, v) ->
            val (icon, label) = v
            val active = t == tab
            val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Column(
                Modifier.pressScale(src).clip(rs(12)).then(if (active) Modifier.background(Halo.mint.copy(alpha = 0.12f)) else Modifier)
                    .clickable(interactionSource = src, indication = null) { if (!active) { onSelect(t); Haptics.tick(ctx) } }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val tint = when {
                    t == Tab.MARKET && !marketUp -> if (active) Halo.red else Halo.red.copy(alpha = 0.75f)
                    t == Tab.MARKET -> if (active) Halo.mint else Halo.mint.copy(alpha = 0.7f)
                    active -> Halo.mint
                    else -> Halo.muted
                }
                HaloIcon(icon, tint, 22.dp)
                // Scrolling down hands the screen to the content: labels fade and the bar closes up.
                // Always composed: squeezed in layout and faded in graphicsLayer, so the bar closes
                // without recomposing anything.
                Text(
                    stringResource(label), style = HaloType.label,
                    color = if (active) Halo.mint else Halo.muted,
                    modifier = Modifier
                        // Collapses only on Wallet, the one tab that scrolls the bar: on the
                        // others the labels stay, and back on Wallet the scroll is where it was.
                        .layout { measurable, constraints ->
                            val p = measurable.measure(constraints)
                            val c = if (tab == Tab.WALLET) collapse.value else 0f
                            val h = (p.height * (1f - c)).toInt().coerceAtLeast(0)
                            layout(p.width, h) { p.placeRelative(0, 0) }
                        }
                        .graphicsLayer {
                            val c = if (tab == Tab.WALLET) collapse.value else 0f
                            alpha = 1f - c
                            scaleY = 1f - c
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                        },
                )
            }
        }
    }
}

@Composable
private fun HomeHeader(account: SvAccount?, headline: String? = null, collapse: androidx.compose.runtime.State<Float>? = null, onScan: (() -> Unit)? = null, onChip: () -> Unit = {}) {
    // Scanning an Agent Gate request lives here, where wallets put the scanner.
    var scanError by remember { mutableStateOf<String?>(null) }
    val ownScan = rememberAgentScan { scanError = it }
    val scan = onScan ?: ownScan
    // The same header as the other tabs, and when the big number has scrolled away the line
    // under the title picks it up, so the figure that counts never leaves. Where the mark used
    // to sit there is the light switch: the mark is on the launcher and the door already, and
    // at thirty-eight dp it was a dark blob. A switch earns that corner, a third logo does not.
    val ctxH = LocalContext.current
    var lightNow by remember { mutableStateOf(Themes.isLight(ctxH)) }
    PageHeader(
        title = stringResource(R.string.app_name),
        sub = headline,
        subModifier = Modifier.graphicsLayer { alpha = collapse?.value ?: 0f },
        leading = {
            val srcT = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Box(
                Modifier.size(38.dp).tappable(srcT, rs(999), fill = Halo.cardSoft) {
                    Themes.toggleLight(ctxH); lightNow = Themes.isLight(ctxH); Haptics.tick(ctxH)
                },
                contentAlignment = Alignment.Center,
            ) {
                HaloIcon(if (lightNow) HIcon.SUN else HIcon.MOON, Halo.mint, 19.dp)
            }
        },
    ) {
        if (account != null) {
            val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Row(
                Modifier.tappable(src, rs(999), onClick = onChip).padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(account.pubkeyBase58, 20.dp)
                Spacer(Modifier.width(6.dp))
                Text(account.label ?: shorten(account.pubkeyBase58), style = HaloType.label.copy(fontWeight = FontWeight.Medium), color = Halo.ink, maxLines = 1)
            }
        }
    }
    scanError?.let { Spacer(Modifier.height(8.dp)); Banner(it, Halo.amber, HIcon.WARNING) }
}

@Composable
internal fun SectionTitle(eyebrow: String, headline: String, icon: HIcon? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(Modifier.size(36.dp).clip(rs(11)).background(Halo.cyanSoft), contentAlignment = Alignment.Center) { HaloIcon(icon, Halo.cyan, 20.dp) }
            Spacer(Modifier.width(12.dp))
        }
        Column {
            Text(eyebrow, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
            Text(headline, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.ink)
        }
    }
}

// ---- offline demo (the :core pipeline with in-memory ports) ---------------------

@Composable
private fun DemoSection(signer: SeedVaultSigner, connectedWallet: String?) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val myWallet = connectedWallet ?: DEMO_WALLET
    var scenario by remember { mutableStateOf(Scenario.SAFE_PAYMENT) }
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<String?>(null) }
    var outcomeOk by remember { mutableStateOf(false) }

    val drainerReason = stringResource(R.string.demo_drainer_reason)
    val case = remember(scenario, myWallet) { DemoCase(scenario, myWallet, drainerReason) }
    val flow = remember(scenario, myWallet) {
        ClearSignFlow(case.decoder, case.simulator, case.scanner, if (connectedWallet != null) DemoSigner(connectedWallet, signer) else MockSigner(myWallet), case.trust, com.clearsign.core.RiskEngine(deviceLocaleTag()))
    }
    val preview = remember(scenario, myWallet) { flow.preview(DEMO_TX, DEMO_FEE_LAMPORTS) }
    val receipt = preview.receipt

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Scenario.entries.forEach { s ->
                val active = s == scenario
                Box(
                    Modifier.clip(rs(12))
                        .then(if (active) Modifier.background(Halo.mint.copy(alpha = 0.14f)) else Modifier)
                        .border(1.dp, if (active) Halo.mint else Halo.stroke, rs(12))
                        .clickable { scenario = s; outcome = null }.padding(horizontal = 12.dp, vertical = 7.dp),
                ) { Text(stringResource(s.titleRes), fontFamily = Sora, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, fontSize = 12.sp, color = if (active) Halo.mint else Halo.muted) }
            }
        }
        // The same receipt a dApp request gets, in its short form: the amount, the risks and
        // the button. The full one carries the flow map and the decoded calls, and on this
        // screen, under the chips, that pushed the risk and the refusal below the fold: the two
        // things the whole demo exists to show were the two you had to go looking for.
        androidx.compose.runtime.key(scenario) { Column { SignReceiptBody(receipt, null, plain = true, map = false) } }
        if (receipt.blocksApproval) {
            Banner(stringResource(R.string.demo_blocked), Halo.red, HIcon.BLOCK)
        } else {
            HoldToConfirm(if (busy) stringResource(R.string.signing) else stringResource(R.string.hold_sign), enabled = !busy) {
                busy = true; outcome = null
                scope.launch {
                    try {
                        val r = withContext(Dispatchers.Default) { flow.approveAndSign(DEMO_TX, preview) }
                        when (r) {
                            is ClearSignFlow.SignOutcome.Signed -> {
                                outcomeOk = true
                                outcome = ctx.getString(if (connectedWallet != null) R.string.demo_signed_hw else R.string.demo_signed_sim) + "\n" +
                                    r.signature.take(8).joinToString("") { b -> "%02x".format(b) } + "…"
                            }
                            is ClearSignFlow.SignOutcome.Refused -> { outcomeOk = false; outcome = ctx.getString(R.string.demo_refused, r.risk.detail) }
                        }
                    } catch (e: Exception) {
                        outcomeOk = false; outcome = e.message ?: ctx.getString(R.string.sign_error)
                    } finally { busy = false }
                }
            }
        }
        outcome?.let { Banner(it, if (outcomeOk) Halo.mint else Halo.red, if (outcomeOk) HIcon.CHECK else HIcon.WARNING) }
        GhostButton(stringResource(R.string.demo_next), icon = HIcon.CHEVRON_RIGHT, tint = Halo.cyan) {
            val all = Scenario.entries; scenario = all[(all.indexOf(scenario) + 1) % all.size]; outcome = null; Haptics.tick(ctx)
        }
    }
}

