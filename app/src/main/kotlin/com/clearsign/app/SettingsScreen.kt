@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Settings tab. Sixteen cards in one flat stack were impossible to scan, so they
 * live in five named groups now — graphics with graphics, agent with agent — and
 * each group opens only when you ask for it.
 */
@Composable
internal fun SettingsScreen(signer: SeedVaultSigner, owner: String?, tools: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.tab_settings), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Halo.ink)

        // What people touch most, first. Everything is included: there is no
        // plan to buy and no card to say so.
        SettingsGroup(stringResource(R.string.set_g_wallet), stringResource(R.string.set_g_wallet_sub), HIcon.WALLET) {
            tools()
            ConnectionsCard()
            ProtectionsCard()
        }

        SettingsGroup(stringResource(R.string.set_g_agent), stringResource(R.string.set_g_agent_sub), HIcon.SPARK) {
            AgentGateCard()
            BrainCard()
            WebCheckCard()
        }

        SettingsGroup(stringResource(R.string.set_g_phone), stringResource(R.string.set_g_phone_sub), HIcon.QR) {
            CompanionLinkCard(owner)
            WatchtowerCard()
            CoverCard()
        }

        SettingsGroup(stringResource(R.string.set_g_look), stringResource(R.string.set_g_look_sub), HIcon.PALETTE) {
            ThemesCard(signer, owner) {}
            CrtCard()
        }

        SettingsGroup(stringResource(R.string.set_g_general), stringResource(R.string.set_g_general_sub), HIcon.INFO) {
            LanguageCard()
            CurrencyCard()
            AboutCard()
        }

        SettingsGroup(stringResource(R.string.set_g_adv), stringResource(R.string.set_g_adv_sub), HIcon.KEY) {
            SpeedCard()
            AttestationCard()
            SwapFeesCard(signer, owner)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * One named drawer of settings. Closed it is a single row you can read at a
 * glance; open it holds its cards, spaced like the rest of the screen.
 */
@Composable
private fun SettingsGroup(title: String, sub: String, icon: HIcon, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(rs(18)).background(Halo.card).border(1.dp, if (open) Halo.mint.copy(alpha = 0.35f) else Halo.stroke, rs(18))
                .clickable { open = !open; Haptics.tick(ctx) }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(34.dp).clip(rs(10)).background(if (open) Halo.mint.copy(alpha = 0.14f) else Halo.cyanSoft), contentAlignment = Alignment.Center) {
                HaloIcon(icon, if (open) Halo.mint else Halo.cyan, 18.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.ink)
                Text(sub, fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted)
            }
            HaloIcon(if (open) HIcon.CHEVRON_DOWN else HIcon.CHEVRON_RIGHT, Halo.muted, 18.dp)
        }
        if (open) content()
    }
}

@Composable
private fun LanguageCard() {
    val ctx = LocalContext.current
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            var current by remember { mutableStateOf(AppLocale.current(ctx)) }
            SectionTitle(stringResource(R.string.lang_label), stringResource(R.string.settings_sub), HIcon.INFO)
            ChipRow(listOf(null to stringResource(R.string.lang_system), "en" to "English", "it" to "Italiano"), current) { tag ->
                if (tag != current) { AppLocale.set(ctx, tag); current = tag; Haptics.tick(ctx) }
            }
            Text(stringResource(R.string.lang_note), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted)
        }
    }
}

@Composable
private fun CurrencyCard() {
    val ctx = LocalContext.current
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val cur by Settings.currency
            SectionTitle(stringResource(R.string.settings_currency), stringResource(R.string.settings_currency_sub), HIcon.COINS)
            ChipRow(FiatRates.SUPPORTED.map { it to it }, cur) { code -> if (code != null && code != cur) { Settings.setCurrency(ctx, code); Haptics.tick(ctx) } }
            Text(stringResource(R.string.settings_currency_note), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted)
        }
    }
}

@Composable
private fun ProtectionsCard() {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            var showProtections by remember { mutableStateOf(false) }
            SectionTitle(stringResource(R.string.home_ready_title), stringResource(R.string.prot_row_title), HIcon.SEAL)
            Text(stringResource(R.string.home_ready_note), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
            Row(
                Modifier.fillMaxWidth().clip(rs(14)).background(Halo.mint.copy(alpha = 0.08f)).border(1.dp, Halo.mint.copy(alpha = 0.3f), rs(14))
                    .clickable { showProtections = true }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HaloIcon(HIcon.SHIELD_LOCK, Halo.mint, 22.dp); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.prot_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Halo.ink)
                    Text(stringResource(R.string.prot_row_sub, Protections.items.size, Protections.riskTypes), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted)
                }
                HaloIcon(HIcon.CHEVRON_RIGHT, Halo.muted, 18.dp)
            }
            if (showProtections) ProtectionsSheet { showProtections = false }
        }
    }
}

@Composable
private fun AgentGateCard() {
    run {
        var gateError by remember { mutableStateOf<String?>(null) }
        val scanGate = rememberAgentScan { gateError = it }
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(rs(10)).background(Halo.cyanSoft), contentAlignment = Alignment.Center) { HaloIcon(HIcon.SCAN, Halo.cyan, 18.dp) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.gate_card_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.ink)
                        Text(stringResource(R.string.gate_card_sub), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                    }
                }
                GhostButton(stringResource(R.string.gate_scan), Modifier.fillMaxWidth(), HIcon.QR, tint = Halo.cyan) { scanGate() }
                gateError?.let { Banner(it, Halo.amber, HIcon.WARNING) }
                Text(stringResource(R.string.gate_card_note), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted)
            }
        }
    }
}

/**
 * The way in to the list of who can ask this wallet for things.
 *
 * Lives under the wallet group rather than the agent one: an agent is one of the
 * things that can ask, not the only one, and the person looking for "which sites
 * am I logged into" is thinking about the wallet.
 */
@Composable
private fun ConnectionsCard() {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val count = remember(open) { runCatching { Connections.all(ctx).count { !it.revoked } }.getOrDefault(0) }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(rs(10)).background(Halo.cyanSoft), contentAlignment = Alignment.Center) { HaloIcon(HIcon.LOGIN, Halo.cyan, 18.dp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.conn_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.ink)
                    Text(stringResource(R.string.conn_card_sub, count), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                }
            }
            GhostButton(stringResource(R.string.conn_open), Modifier.fillMaxWidth(), HIcon.CHEVRON_RIGHT, tint = Halo.cyan) { open = true }
        }
    }
    if (open) ConnectionsSheet { open = false }
}

@Composable
private fun CompanionCard() {
    val ctx = LocalContext.current
    run {
        var canDraw by remember { mutableStateOf(CompanionService.canRun(ctx)) }
        var running by remember { mutableStateOf(false) }
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(rs(10)).background(Halo.mint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { HaloIcon(HIcon.SPARK, Halo.mint, 18.dp) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.companion_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.ink)
                        Text(
                            if (!canDraw) stringResource(R.string.companion_needs_perm) else stringResource(R.string.companion_sub),
                            fontFamily = Inter, fontSize = 12.sp, color = if (!canDraw) Halo.amber else Halo.muted,
                        )
                    }
                }
                if (!canDraw) {
                    GhostButton(stringResource(R.string.companion_grant), Modifier.fillMaxWidth(), HIcon.UNLOCK, tint = Halo.amber) {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:" + ctx.packageName),
                                ),
                            )
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton(stringResource(R.string.companion_on), Modifier.weight(1f), HIcon.CHECK, tint = Halo.mint) {
                            CompanionService.start(ctx); running = true; Haptics.tick(ctx)
                        }
                        GhostButton(stringResource(R.string.companion_off), Modifier.weight(1f), HIcon.BLOCK) {
                            CompanionService.stop(ctx); running = false; Haptics.tick(ctx)
                        }
                    }
                }
                Text(stringResource(R.string.companion_note), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted)
            }
        }
    }
}

/** Priority fee for what we send ourselves: none, some, a lot. Said in what it costs. */
@Composable
private fun SpeedCard() {
    val ctx = LocalContext.current
    var v by remember { mutableStateOf(Settings.speed(ctx)) }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(stringResource(R.string.speed_title), stringResource(R.string.speed_sub), HIcon.SPARK)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(stringResource(R.string.speed_normal), v == 0L, Halo.muted, Modifier.weight(1f)) { v = 0L; Settings.setSpeed(ctx, 0L) }
                ModeChip(stringResource(R.string.speed_fast), v == 10_000L, Halo.cyan, Modifier.weight(1f)) { v = 10_000L; Settings.setSpeed(ctx, 10_000L) }
                ModeChip(stringResource(R.string.speed_turbo), v == 100_000L, Halo.mint, Modifier.weight(1f)) { v = 100_000L; Settings.setSpeed(ctx, 100_000L) }
            }
            Text(stringResource(R.string.speed_note), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted)
        }
    }
}

/** One row that opens the page of the bubble and the widget. */
@Composable
private fun CompanionLinkCard(owner: String?) {
    var open by remember { mutableStateOf(false) }
    GlassCard {
        Row(Modifier.fillMaxWidth().clickable { open = true }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { SectionTitle(stringResource(R.string.comp_page_title), stringResource(R.string.comp_page_sub), HIcon.SPARK) }
            HaloIcon(HIcon.CHEVRON_RIGHT, Halo.muted, 18.dp)
        }
    }
    if (open) CompanionPage(owner) { open = false }
}

/** A hand over the screen hides the numbers. Off for people who hold the phone near the face a lot. */
@Composable
private fun CoverCard() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Settings.coverToHide(ctx)) }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle(stringResource(R.string.cover_title), stringResource(R.string.cover_sub), HIcon.LOCK)
            }
            androidx.compose.material3.Switch(checked = on, onCheckedChange = { on = it; Settings.setCoverToHide(ctx, it) })
        }
    }
}

@Composable
private fun WidgetCard() {
    val ctx = LocalContext.current
    run {
        val scope = rememberCoroutineScope()
        var note by remember { mutableStateOf<String?>(null) }
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(rs(10)).background(Halo.cyanSoft), contentAlignment = Alignment.Center) { HaloIcon(HIcon.WALLET, Halo.cyan, 18.dp) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.widget_card_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.ink)
                        Text(note ?: stringResource(R.string.widget_card_sub), fontFamily = Inter, fontSize = 12.sp, color = if (note != null) Halo.mint else Halo.muted)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(stringResource(R.string.widget_add), Modifier.weight(1f), HIcon.QR, tint = Halo.cyan) {
                        val awm = ctx.getSystemService(android.appwidget.AppWidgetManager::class.java)
                        val provider = android.content.ComponentName(ctx, HealthWidgetReceiver::class.java)
                        note = if (awm != null && awm.isRequestPinAppWidgetSupported && awm.requestPinAppWidget(provider, null, null)) {
                            ctx.getString(R.string.widget_added)
                        } else ctx.getString(R.string.widget_unsupported)
                        Haptics.tick(ctx)
                    }
                    GhostButton(stringResource(R.string.widget_refresh), Modifier.weight(1f), HIcon.HISTORY) {
                        scope.launch { runCatching { HealthWidgetData.refresh(ctx) }; note = ctx.getString(R.string.widget_refreshed); Haptics.tick(ctx) }
                    }
                }
                Text(stringResource(R.string.widget_card_note), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted)
            }
        }
    }
}

/**
 * The one setting in the app that spends money on something other than a trade,
 * so it says so in the same breath as the switch, and starts off.
 */
@Composable
private fun WebCheckCard() {
    val ctx = LocalContext.current
    val on by Settings.webCheck
    val ready = remember { Secrets.model(ctx).let { it.ready && it.anthropic } }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(rs(10)).background(Halo.cyanSoft), contentAlignment = Alignment.Center) { HaloIcon(HIcon.SEARCH, Halo.cyan, 18.dp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.webcheck_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.ink)
                    Text(
                        if (!ready) stringResource(R.string.webcheck_needs_key)
                        else if (on) stringResource(R.string.webcheck_on) else stringResource(R.string.webcheck_off),
                        fontFamily = Inter, fontSize = 12.sp, color = if (on && ready) Halo.mint else Halo.muted,
                    )
                }
                if (ready) {
                    Text(
                        if (on) stringResource(R.string.watch_disable) else stringResource(R.string.watch_enable),
                        fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = if (on) Halo.muted else Halo.mint,
                        modifier = Modifier.clip(rs(10)).background((if (on) Halo.muted else Halo.mint).copy(alpha = 0.12f)).clickable {
                            Settings.setWebCheck(ctx, !on); Haptics.tick(ctx)
                        }.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
            Text(stringResource(R.string.webcheck_note), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun CrtCard() {
    val ctx = LocalContext.current
    run {
        val on by Settings.crt
        val phosphor = Halo.palette.scanlines
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(rs(10)).background(Halo.mint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { HaloIcon(HIcon.FLASK, Halo.mint, 18.dp) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.crt_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.ink)
                        Text(if (on) stringResource(R.string.crt_on) else stringResource(R.string.crt_off), fontFamily = Inter, fontSize = 12.sp, color = if (on) Halo.mint else Halo.muted)
                    }
                    Text(
                        if (on) stringResource(R.string.watch_disable) else stringResource(R.string.watch_enable),
                        fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = if (on) Halo.muted else Halo.mint,
                        modifier = Modifier.clip(rs(10)).background((if (on) Halo.muted else Halo.mint).copy(alpha = 0.12f)).clickable {
                            Settings.setCrt(ctx, !on); Haptics.tick(ctx)
                        }.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
                Text(stringResource(if (phosphor) R.string.crt_note else R.string.crt_note_needs_phosphor), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted)
            }
        }
    }
}

@Composable
private fun WatchtowerCard() {
    val ctx = LocalContext.current
    run {
        val pro by Pro.isPro
        val on by Settings.watchtower
        val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) { Watchtower.ensureChannel(ctx); Settings.setWatchtower(ctx, true); Haptics.tick(ctx) }
        }
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(rs(10)).background(Halo.cyanSoft), contentAlignment = Alignment.Center) { HaloIcon(HIcon.MEGAPHONE, Halo.cyan, 18.dp) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.watch_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.ink)
                        Text(if (!pro) stringResource(R.string.watch_pro) else if (on) stringResource(R.string.watch_on) else stringResource(R.string.watch_off), fontFamily = Inter, fontSize = 12.sp, color = if (on) Halo.mint else Halo.muted)
                    }
                    if (pro) Text(
                        if (on) stringResource(R.string.watch_disable) else stringResource(R.string.watch_enable),
                        fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = if (on) Halo.muted else Halo.mint,
                        modifier = Modifier.clip(rs(10)).background((if (on) Halo.muted else Halo.mint).copy(alpha = 0.12f)).clickable {
                            if (on) Settings.setWatchtower(ctx, false)
                            else if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            else { Watchtower.ensureChannel(ctx); Settings.setWatchtower(ctx, true); Haptics.tick(ctx) }
                        }.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
                Text(stringResource(R.string.watch_note), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted)
            }
        }
    }
}

@Composable
private fun AttestationCard() {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(stringResource(R.string.proof_key), stringResource(R.string.settings_key_sub), HIcon.KEY)
            Text(stringResource(R.string.settings_key_note), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
            AttestationKeyRow()
        }
    }
}

@Composable
private fun SwapFeesCard(signer: SeedVaultSigner, owner: String?) {
    val ctx = LocalContext.current
    if (WalletActions.treasuryConfigured && owner != null) {
        var busy by remember { mutableStateOf(false) }
        var msg by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(stringResource(R.string.swapfees_title), stringResource(R.string.swapfees_sub), HIcon.COINS)
                Text(stringResource(R.string.swapfees_note), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                // Counted before you press it: how many accounts are missing and
                // what opening them costs in rent. Both come back to you, since
                // the treasury is your own address.
                val pending by androidx.compose.runtime.produceState<List<String>?>(initialValue = null, owner) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { WalletActions.feeMintsToOpen(ctx, owner) }.getOrNull() }
                }
                pending?.let { list ->
                    Text(
                        if (list.isEmpty()) stringResource(R.string.swapfees_all_set)
                        else stringResource(R.string.swapfees_pending, list.size, fmtSol(list.size * 2_040_000L, 4)),
                        fontFamily = Inter, fontSize = 11.5.sp, color = if (list.isEmpty()) Halo.mint else Halo.amber,
                    )
                }
                if (busy) Working(stringResource(R.string.theme_unlock_signing))
                else GhostButton(stringResource(R.string.swapfees_btn), icon = HIcon.COINS, tint = Halo.mint) {
                    busy = true; msg = null
                    scope.launch {
                        msg = when (val r = WalletActions.activateSwapFees(ctx, signer, owner)) {
                            is WalletActions.Result.Sent -> ctx.getString(R.string.swapfees_done)
                            is WalletActions.Result.Failed -> r.message
                        }
                        busy = false
                    }
                }
                msg?.let { Text(it, fontFamily = Inter, fontSize = 11.5.sp, color = Halo.mint) }
            }
        }
    }
}

@Composable
private fun AboutCard() {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(rs(12)).background(Brush.linearGradient(listOf(Halo.mint, Halo.cyan))), contentAlignment = Alignment.Center) { HaloIcon(HIcon.AGENT, Halo.ground, 24.dp) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Apex " + BuildConfig.VERSION_NAME, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.ink)
                Text(stringResource(R.string.tagline), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                Text(stringResource(R.string.about_note), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted)
            }
        }
    }
}


/** A row of selectable chips: (value, label). */
@Composable
internal fun <T> ChipRow(items: List<Pair<T?, String>>, selected: T?, onSelect: (T?) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (value, label) ->
            val active = value == selected
            Box(
                Modifier.clip(rs(12))
                    .then(if (active) Modifier.background(Halo.mint.copy(alpha = 0.14f)) else Modifier)
                    .border(1.dp, if (active) Halo.mint else Halo.stroke, rs(12))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) { Text(label, fontFamily = Sora, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, fontSize = 12.sp, color = if (active) Halo.mint else Halo.muted) }
        }
    }
}

@Composable
internal fun ProtectionsSheet(onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(rs(12)).background(Brush.linearGradient(listOf(Halo.mint, Halo.cyan))), contentAlignment = Alignment.Center) { HaloIcon(HIcon.AGENT, Halo.ground, 24.dp) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.prot_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.ink)
                    Text(stringResource(R.string.prot_row_sub, Protections.items.size, Protections.riskTypes), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.prot_intro), fontFamily = Inter, fontSize = 12.5.sp, color = Halo.muted)
                Protections.items.forEachIndexed { i, it ->
                    Row(Modifier.fillMaxWidth().clip(rs(14)).background(Halo.cardSoft).border(cardBorder(), rs(14)).padding(12.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(32.dp).clip(rs(10)).background(Halo.cyanSoft), contentAlignment = Alignment.Center) { HaloIcon(it.icon, Halo.cyan, 17.dp) }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("${i + 1}. " + stringResource(it.titleRes), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = Halo.ink)
                            Text(stringResource(it.bodyRes), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** The app's attestation public key: what a verifier needs to check an exported proof. */
@Composable
internal fun AttestationKeyRow() {
    val ctx = LocalContext.current
    val key = remember { Attestation.publicKeyBase64() } ?: return
    var copied by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(rs(12)).background(Halo.cardSoft).border(cardBorder(), rs(12)).clickable { copyText(ctx, key); copied = true; Haptics.tick(ctx) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HaloIcon(HIcon.SHIELD_LOCK, Halo.muted, 13.dp); Spacer(Modifier.width(6.dp))
        Text(key.take(14) + "…" + key.takeLast(8), fontFamily = Mono, fontSize = 11.sp, color = Halo.ink, modifier = Modifier.weight(1f), maxLines = 1)
        Text(if (copied) stringResource(R.string.copied) else stringResource(R.string.copy), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = if (copied) Halo.mint else Halo.cyan)
    }
}


