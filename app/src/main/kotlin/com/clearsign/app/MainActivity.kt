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
     * Where a notification, the widget or the bubble asked us to land. Activity state, not a
     * read of `intent` inside `remember`, which runs on first composition only and misses
     * onNewIntent. Cleared once acted on, so coming back does not re-open the same sheet.
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
        // Reader mode and tag emulation share the radio, and reader mode wins. Stay off while
        // the tap screen is armed, or the other phone finds no card.
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
    }

    override fun onDestroy() {
        super.onDestroy()
        Voice.release()
    }

    override fun onResume() {
        super.onResume()
        startReaderMode()
        // The widget mirrors what the app knows; refresh it whenever we come to the front.
        lifecycleScope.launch { runCatching { HealthWidgetData.refresh(this@MainActivity) } }
        // A paired agent link should be listening whenever the phone is up.
        if (AgentLink.current(this) != null && SessionWallet.current(this) != null) AgentLinkService.start(this)
        // So should the trader: after a force-stop it would otherwise wait for the next
        // 15-minute keeper window while showing as on.
        TraderKeeper.sync(this)
        // Independent of trading: it reads other people's wallets and spends nothing.
        SeekerKeeper.sync(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Themes.load(this)
        Settings.load(this); SpareJar.load(this)
        Pro.load(this)
        // Hand the radio back and forth as the tap screen arms and disarms. Here, not in
        // onResume: `repeatOnLifecycle` never returns, so one per resume leaks a collector.
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
        // Coin names, decimals and icons cached on disk, read after the first frame on IO:
        // on the main thread before `setContent` they kept the splash black. One image
        // loader for every logo, with a memory cache.
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
                runCatching { Market.warm(this@MainActivity) }
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
        // Bumped to reload the home. Up here so a swap or a send can ask for it on closing.
        var reload by remember { mutableStateOf(0) }
        // Reload now and again after 4 s, once the chain has landed it, so a swapped coin
        // leaves the home without a pull.
        fun reloadAfterSend(who: String?) {
            reload++
            scope.launch {
                kotlinx.coroutines.delay(4_000)
                who?.let { SolanaRpc.forgetTokens(it) }
                reload++
            }
        }
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
        // Private send starts in Send but runs through the bridge, carrying what was typed.
        var privateSend by remember { mutableStateOf<Pair<String, String>?>(null) }
        var showContactTap by remember { mutableStateOf(false) }
        var showCustomize by remember { mutableStateOf(false) }
        var showCompanion by remember { mutableStateOf(false) }
        var showChip by remember { mutableStateOf(false) }
        var guestNote by remember { mutableStateOf(0L) }
        // A notification about a followed wallet opens the feed on that coin, with the
        // receipt already building.
        var showCrowd by remember { mutableStateOf(false) }
        var crowdMint by remember { mutableStateOf((ctx as? android.app.Activity)?.intent?.getStringExtra("mint")) }
        var showHealth by remember { mutableStateOf(false) }
        var showPnl by remember { mutableStateOf(false) }
        var headline by remember { mutableStateOf<String?>(null) }
        // One scroll state for the wallet, hoisted so the header and the bottom
        // bar can both react to it.
        var scanError by remember { mutableStateOf<String?>(null) }
        val scanHome = rememberAgentScan { scanError = it }
        val walletScroll = rememberScrollState()
        val density = androidx.compose.ui.platform.LocalDensity.current
        // A State, not a Float: readers use it inside `graphicsLayer` or `layout`, so scrolling
        // moves layers without recomposing. Read as a Float here, `HomeScreen` recomposes every frame.
        val collapse = remember {
            derivedStateOf { (walletScroll.value / with(density) { 180.dp.toPx() }).coerceIn(0f, 1f) }
        }
        /**
         * Handles "open this" from a notification, the widget or a bubble button, also while
         * the app is already up. Cleared after acting, or coming back would re-open the sheet.
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
         * Unlock on a returning phone: fingerprint every time, as Jupiter does. The address is
         * remembered, so the print only proves who holds the phone. Signing later calls
         * `ensureAccount`, which authorizes the vault when something is actually signed.
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

        // Leaving the app locks it; coming back always asks for the print.
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
                    // No wallet: the lock screen fills the screen, no tab bar. Fade and slight scale
                    // between the two.
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
                                // A dismissed prompt is not a failed print; only FAILED gets the
                                // error. The button is the retry.
                                if (r == Presence.Result.FAILED) status = ctx.getString(R.string.lock_failed)
                            }
                        }
                        // A returning phone asks for the print on its own, once; the button retries.
                        // Delayed 1.25 s: the fingerprint sheet is a secure window that covers the
                        // screen and blanks screen recording, so the intro animation would go unseen.
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
            ) { showSend = false; sendTo = null; sendMint = null; bridgeMemo = null; bridgeDeal = null; (ctx as? MainActivity)?.incoming = null; reloadAfterSend(owner) }
        }
        if (showTap && owner != null) TapSheet(owner) { showTap = false }
        // A full page, not a sheet: it covers the tab bar too.
        if (showCrowd) {
            // The feed's own panel handles buys; `onBuy` is the fallback for the few it cannot.
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
        // Outside the block above: nested there, closing More to open one of these
        // removed it from the tree too.
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
        if (showHealth) HealthSheet(owner, signer) { showHealth = false }
        if (showPnl) PnlSheet { showPnl = false }
        if (showGift && owner != null) GiftSheet(signer, owner) { showGift = false }
        if (showReceive && first != null) ReceiveSheet(first.pubkeyBase58, first.label, onTap = { showReceive = false; showTap = true }) { showReceive = false }
        if ((showSwap || swapMint != null) && first != null) {
            SwapSheet(signer, first.pubkeyBase58, buyMint = swapMint, sellMint = swapSellMint) { showSwap = false; swapMint = null; swapSellMint = null; reloadAfterSend(first.pubkeyBase58) }
        }
    }
}


/** Wallet health, delegations, contacts and the offline demo, in Settings so the home stays a wallet. */
@Composable
private fun SecurityTools(signer: SeedVaultSigner, owner: String?, contacts: Map<String, String>, onSend: (String) -> Unit) {
    var showDemo by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // ---- Try an attack: the receipt on a drainer, no dApp needed ----
                // Opens over everything like other receipts; inline, the risk and the refusing
                // button fell below the fold.
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
                WalletHealthCard(owner, signer)
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
                        // Tapping a contact opens send with its address filled in.
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

/** Lock screen when there is no wallet yet: nothing works without a key, so one screen, one action. */
@Composable
private fun ConnectDoor(busy: Boolean, status: String?, returning: Boolean, onConnect: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp).padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // High on purpose: the fingerprint sheet covers the bottom half and the name must stay
        // readable. Name only, no launcher art (a light square that drew the eye).
        Spacer(Modifier.weight(0.08f))
        // The app's one staged animation: the name lights up once on arrival. Elsewhere
        // motion only answers input.
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
                // Theme color, not ink: a black headline on a light theme clashed with the
                // mark and the button.
                color = Halo.mint.copy(alpha = 0.22f + 0.78f * lit.value),
                letterSpacing = 3.sp,
            )
        }
        // No subtitle: the scene below explains itself.
        Spacer(Modifier.height(8.dp))
        // GateDemo fills the middle: requests reach the phone and the malicious one is
        // stopped. Planet's limb above the button, phone in the center.
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
        // Top border only; on four sides it looks like a card.
        Modifier.fillMaxWidth().background(Halo.card)
            .drawBehind { drawLine(Halo.stroke, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        // The market icon follows the big coins' day: up in the accent, down in red.
        // Weighted by size, so one small coin cannot flip it.
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
                // Scrolling down fades the labels and shrinks the bar. Always composed: squeezed
                // in layout and faded in graphicsLayer, so nothing recomposes.
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
    // Same header as the other tabs; once the big number scrolls away, the subtitle shows it.
    // The corner holds the light/dark switch, not the mark (a dark blob at 38 dp).
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
        // The dApp receipt in short form: amount, risks, button. The full one (flow map,
        // decoded calls) pushed the risk and the refusal below the fold here.
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

