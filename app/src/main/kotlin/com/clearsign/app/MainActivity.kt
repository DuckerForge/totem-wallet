package com.clearsign.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
 * Home: the wallet's own screen (a dApp never opens this — MWA routes to
 * [MobileWalletAdapterActivity]). Shows the Seed Vault accounts, the local
 * "registro firme", the trusted contacts, and keeps the offline demo scenarios
 * at the bottom for a quick pitch without a dApp.
 */
class MainActivity : ComponentActivity() {

    private lateinit var bridge: ActivityResultBridge
    private lateinit var signer: SeedVaultSigner

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Themes.load(this)
        Settings.load(this)
        // Must be registered before the Activity is STARTED.
        bridge = ActivityResultBridge(this)
        signer = SeedVaultSigner(this, bridge)
        enableEdgeToEdge()
        setContent { HomeScreen(signer) }
    }
}

private data class HomeAccount(val account: SvAccount, val lamports: Long?, val tokens: Int)

private enum class Tab { WALLET, RECEIPTS, SETTINGS }

@Composable
fun HomeScreen(signer: SeedVaultSigner) {
    HaloRoot {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        // Wallet state lives at the root so switching tabs never drops the Seed Vault session.
        var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(Tab.WALLET) }
        var accounts by remember { mutableStateOf<List<HomeAccount>>(emptyList()) }
        var busy by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf<String?>(null) }
        var contacts by remember { mutableStateOf(Contacts.allowlist(ctx)) }
        var showDemo by remember { mutableStateOf(false) }
        var showSend by remember { mutableStateOf(false) }
        var showReceive by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { contacts = Contacts.allowlist(ctx); Exports.clean(ctx) }
        val owner = accounts.firstOrNull()?.account?.pubkeyBase58

        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Halo.ground2, Halo.ground)))
                .background(Brush.radialGradient(listOf(Halo.cyan.copy(alpha = 0.14f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(900f, -100f), radius = 900f))
                .haloSurface(),
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        Tab.WALLET -> androidx.compose.runtime.CompositionLocalProvider(LocalEntrance provides remember { java.util.concurrent.atomic.AtomicInteger() }) {
                            Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                HomeHeader(accounts.firstOrNull()?.account)

                // ---- Wallet ---------------------------------------------------
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle(stringResource(R.string.home_wallet_hdr), stringResource(R.string.home_wallet_sub), HIcon.WALLET)
                        if (accounts.isEmpty()) {
                            Text(
                                stringResource(R.string.home_wallet_note),
                                fontFamily = Inter, fontSize = 13.sp, color = Halo.muted,
                            )
                            PrimaryButton(if (busy) stringResource(R.string.connecting) else stringResource(R.string.connect_seed_vault), danger = false, enabled = !busy, icon = HIcon.FINGERPRINT) {
                                busy = true; status = null
                                scope.launch {
                                    try {
                                        val list = signer.authorizeAndListAccounts()
                                        signer.selectAccount(list.first())
                                        val rpc = SolanaRpc.urlFor(null)
                                        val bal = withContext(Dispatchers.IO) { SolanaRpc.assetsSummaryMulti(rpc, list.map { it.pubkeyBase58 }) }
                                        accounts = list.map { a -> val (l, t) = bal[a.pubkeyBase58] ?: (null to 0); HomeAccount(a, l, t) }
                                    } catch (e: Exception) {
                                        status = e.message ?: ctx.getString(R.string.connect_failed)
                                    } finally { busy = false }
                                }
                            }
                            status?.let { Text(it, color = Halo.red, fontFamily = Inter, fontSize = 12.5.sp) }
                        } else {
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
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                GhostButton(stringResource(R.string.send_btn), Modifier.weight(1f), HIcon.SEND, tint = Halo.mint) { showSend = true }
                                GhostButton(stringResource(R.string.receive_btn), Modifier.weight(1f), HIcon.RECEIVE, tint = Halo.cyan) { showReceive = true }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HaloIcon(HIcon.SHIELD_LOCK, Halo.mint, 16.dp); Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.home_keys_note), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted)
                            }
                        }
                    }
                }

                // ---- Delegations & accounts -----------------------------------
                AccountsCard(signer, owner)

                // ---- Contacts -------------------------------------------------
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionTitle(stringResource(R.string.home_contacts_hdr), if (contacts.isEmpty()) stringResource(R.string.none) else "${contacts.size}", HIcon.CONTACTS)
                        if (contacts.isEmpty()) {
                            Text(stringResource(R.string.contacts_empty_note), fontFamily = Inter, fontSize = 12.5.sp, color = Halo.muted)
                        }
                        contacts.entries.take(20).forEach { (addr, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Avatar(addr, 30.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(label, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Halo.ink)
                                    Text(shorten(addr, 6), fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, color = Halo.muted)
                                }
                                TrustChip(TrustLevel.TRUSTED)
                            }
                        }
                    }
                }

                // ---- Demo -----------------------------------------------------
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { showDemo = !showDemo }, verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle(stringResource(R.string.home_demo_hdr), stringResource(R.string.home_demo_sub), HIcon.FLASK)
                            Spacer(Modifier.weight(1f))
                            HaloIcon(if (showDemo) HIcon.CHEVRON_DOWN else HIcon.CHEVRON_RIGHT, Halo.muted, 18.dp)
                        }
                        if (showDemo) DemoSection(signer, accounts.firstOrNull()?.account?.pubkeyBase58)
                    }
                }
                Spacer(Modifier.height(8.dp))
                            }
                        }
                        Tab.RECEIPTS -> LedgerScreen()
                        Tab.SETTINGS -> SettingsScreen(signer, owner)
                    }
                }
                BottomBar(tab) { tab = it }
            }
        }
        val first = accounts.firstOrNull()?.account
        if (showSend && first != null) SendSheet(signer, first.pubkeyBase58) { showSend = false }
        if (showReceive && first != null) ReceiveSheet(first.pubkeyBase58, first.label) { showReceive = false }
    }
}

/** Three tabs on a hairline-topped bar; the active one sits on a soft pill. */
@Composable
private fun BottomBar(tab: Tab, onSelect: (Tab) -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().background(Halo.card).border(androidx.compose.foundation.BorderStroke(1.dp, Halo.stroke)).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        listOf(Tab.WALLET to (HIcon.WALLET to R.string.tab_wallet), Tab.RECEIPTS to (HIcon.RECEIPT to R.string.tab_receipts), Tab.SETTINGS to (HIcon.SETTINGS to R.string.tab_settings)).forEach { (t, v) ->
            val (icon, label) = v
            val active = t == tab
            val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Column(
                Modifier.pressScale(src).clip(rs(12)).then(if (active) Modifier.background(Halo.mint.copy(alpha = 0.12f)) else Modifier)
                    .clickable(interactionSource = src, indication = null) { if (!active) { onSelect(t); Haptics.tick(ctx) } }
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HaloIcon(icon, if (active) Halo.mint else Halo.muted, 22.dp)
                Text(stringResource(label), fontFamily = Inter, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, fontSize = 10.5.sp, color = if (active) Halo.mint else Halo.muted)
            }
        }
    }
}

@Composable
private fun HomeHeader(account: SvAccount?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).clip(rs(10)).background(Brush.linearGradient(listOf(Halo.mint, Halo.cyan))),
            contentAlignment = Alignment.Center,
        ) { HaloIcon(HIcon.SEAL, Halo.ground, 26.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("ClearSign", fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 21.sp, color = Halo.ink)
            Text(stringResource(R.string.tagline), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted)
        }
        if (account != null) {
            Row(
                Modifier.clip(rs(999)).background(Halo.cardSoft).border(1.dp, Halo.stroke, rs(999)).padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(account.pubkeyBase58, 20.dp)
                Spacer(Modifier.width(6.dp))
                Text(account.label ?: shorten(account.pubkeyBase58), fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, color = Halo.ink, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun SectionTitle(title: String, sub: String, icon: HIcon? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(Modifier.size(36.dp).clip(rs(11)).background(Halo.cyanSoft), contentAlignment = Alignment.Center) { HaloIcon(icon, Halo.cyan, 20.dp) }
            Spacer(Modifier.width(12.dp))
        }
        Column {
            Text(title, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
            Text(sub, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.ink)
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
        ClearSignFlow(case.decoder, case.simulator, case.scanner, if (connectedWallet != null) signer else MockSigner(myWallet), case.trust, com.clearsign.core.RiskEngine(deviceLocaleTag()))
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
        Text(stringResource(scenario.blurbRes), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            HaloIcon(HIcon.INFO, Halo.cyan, 13.dp); Spacer(Modifier.width(5.dp))
            Text(stringResource(R.string.demo_offline_note), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted)
        }
        // The very same receipt a dApp request gets: hero, risks, node map, details.
        androidx.compose.runtime.key(scenario) { Column { SignReceiptBody(receipt, null) } }
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

