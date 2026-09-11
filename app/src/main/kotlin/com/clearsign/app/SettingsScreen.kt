@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

/** Settings tab: themes, language, display currency, what the app does, attestation key, about. */
@Composable
internal fun SettingsScreen(signer: SeedVaultSigner, owner: String?) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.tab_settings), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Halo.ink)

        var showPro by remember { mutableStateOf(false) }
        ProCard { showPro = true }
        ThemesCard(signer, owner) { showPro = true }
        if (showPro) ProSheet(signer, owner) { showPro = false }

        // ---- Language -------------------------------------------------------
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

        // ---- Display currency -----------------------------------------------
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val cur by Settings.currency
                SectionTitle(stringResource(R.string.settings_currency), stringResource(R.string.settings_currency_sub), HIcon.COINS)
                ChipRow(FiatRates.SUPPORTED.map { it to it }, cur) { code -> if (code != null && code != cur) { Settings.setCurrency(ctx, code); Haptics.tick(ctx) } }
                Text(stringResource(R.string.settings_currency_note), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted)
            }
        }

        // ---- What ClearSign does --------------------------------------------
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

        // ---- Watchtower (Pro) -------------------------------------------------
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

        // ---- Attestation key ------------------------------------------------
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle(stringResource(R.string.proof_key), stringResource(R.string.settings_key_sub), HIcon.KEY)
                Text(stringResource(R.string.settings_key_note), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                AttestationKeyRow()
            }
        }

        // ---- Swap fees setup --------------------------------------------------
        if (WalletActions.treasuryConfigured && owner != null) {
            var busy by remember { mutableStateOf(false) }
            var msg by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle(stringResource(R.string.swapfees_title), stringResource(R.string.swapfees_sub), HIcon.COINS)
                    Text(stringResource(R.string.swapfees_note), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
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

        // ---- About ------------------------------------------------------------
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(rs(12)).background(Brush.linearGradient(listOf(Halo.mint, Halo.cyan))), contentAlignment = Alignment.Center) { HaloIcon(HIcon.SEAL, Halo.ground, 24.dp) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("ClearSign " + BuildConfig.VERSION_NAME, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.ink)
                    Text(stringResource(R.string.tagline), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                    Text(stringResource(R.string.about_note), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
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
                Box(Modifier.size(40.dp).clip(rs(12)).background(Brush.linearGradient(listOf(Halo.mint, Halo.cyan))), contentAlignment = Alignment.Center) { HaloIcon(HIcon.SEAL, Halo.ground, 24.dp) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.prot_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.ink)
                    Text(stringResource(R.string.prot_row_sub, Protections.items.size, Protections.riskTypes), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.prot_intro), fontFamily = Inter, fontSize = 12.5.sp, color = Halo.muted)
                Protections.items.forEachIndexed { i, it ->
                    Row(Modifier.fillMaxWidth().clip(rs(14)).background(Halo.cardSoft).border(1.dp, Halo.stroke, rs(14)).padding(12.dp), verticalAlignment = Alignment.Top) {
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
        Modifier.fillMaxWidth().clip(rs(12)).background(Halo.cardSoft).border(1.dp, Halo.stroke, rs(12)).clickable { copyText(ctx, key); copied = true; Haptics.tick(ctx) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HaloIcon(HIcon.SHIELD_LOCK, Halo.muted, 13.dp); Spacer(Modifier.width(6.dp))
        Text(key.take(14) + "…" + key.takeLast(8), fontFamily = Mono, fontSize = 11.sp, color = Halo.ink, modifier = Modifier.weight(1f), maxLines = 1)
        Text(if (copied) stringResource(R.string.copied) else stringResource(R.string.copy), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = if (copied) Halo.mint else Halo.cyan)
    }
}
