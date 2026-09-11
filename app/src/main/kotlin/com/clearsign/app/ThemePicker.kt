@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Themes card: four tiles, each a miniature of its palette. Halo is free;
 * the others unlock with a real SKR payment signed by the Seed Vault — the
 * token buys something you see every day.
 */
@Composable
internal fun ThemesCard(signer: SeedVaultSigner, owner: String?) {
    val ctx = LocalContext.current
    var unlockTarget by remember { mutableStateOf<HaloPalette?>(null) }
    var unlocked by remember { mutableStateOf(Themes.unlockedIds(ctx)) }
    val current = Halo.palette

    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionTitle(stringResource(R.string.home_themes_hdr), stringResource(R.string.home_themes_sub), HIcon.PALETTE)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Palettes.all.forEach { p ->
                    val isUnlocked = Themes.ALL_FREE || p.isFree || p.id in unlocked
                    ThemeTile(
                        p = p, active = p.id == current.id, locked = !isUnlocked,
                        modifier = Modifier.weight(1f),
                        onTap = {
                            if (isUnlocked) { Themes.select(ctx, p.id); Haptics.tick(ctx) } else unlockTarget = p
                        },
                        onDevUnlock = if (BuildConfig.DEBUG && !isUnlocked) ({
                            Themes.unlock(ctx, p.id, null); unlocked = Themes.unlockedIds(ctx); Themes.select(ctx, p.id); Haptics.success(ctx)
                        }) else null,
                    )
                }
            }
            Text(
                when { Themes.ALL_FREE -> stringResource(R.string.theme_note_all_free); current.isFree -> stringResource(R.string.theme_note_free); else -> stringResource(R.string.theme_note_paid, stringResource(current.nameRes)) },
                fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted,
            )
        }
    }

    unlockTarget?.let { p ->
        ThemeUnlockSheet(p, signer, owner, onDismiss = { unlockTarget = null; unlocked = Themes.unlockedIds(ctx) })
    }
}

@Composable
private fun ThemeTile(p: HaloPalette, active: Boolean, locked: Boolean, modifier: Modifier, onTap: () -> Unit, onDevUnlock: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val border by animateColorAsState(if (active) Halo.mint else Halo.stroke, tween(260), label = "border")
    val shape = rs(14)
    Column(
        modifier.pressScale(pressed).pointerInput(p.id, locked) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    val dev = onDevUnlock?.let { cb -> scope.launch { delay(1500); cb() } }
                    tryAwaitRelease()
                    pressed = false
                    dev?.cancel()
                },
                onTap = { onTap() },
            )
        },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(92.dp).clip(shape)
                .background(Brush.verticalGradient(listOf(p.ground2, p.ground)))
                .border(if (active) 1.5.dp else 1.dp, border, shape),
        ) {
            // The miniature: a glass card with an accent pill and a tabular amount.
            Column(Modifier.padding(8.dp).fillMaxWidth()) {
                Box(Modifier.width(26.dp).height(6.dp).clip(RoundedCornerShape((3 * p.radiusScale).dp)).background(p.accent))
                Spacer(Modifier.height(7.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape((8 * p.radiusScale).dp)).background(p.card).border(1.dp, p.stroke, RoundedCornerShape((8 * p.radiusScale).dp)).padding(7.dp)) {
                    Column {
                        // Real type of that theme: the tile is a preview, not a colour swatch.
                        Text("1.25", fontFamily = p.fonts.display, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = p.ink, style = Tabular)
                        Spacer(Modifier.height(2.dp))
                        when (p.receiptStyle) {
                            ReceiptStyle.CARDS -> { Box(Modifier.width(30.dp).height(4.dp).clip(RoundedCornerShape((2 * p.radiusScale).dp)).background(p.muted.copy(alpha = 0.7f))); Spacer(Modifier.height(3.dp)); Box(Modifier.width(18.dp).height(4.dp).clip(RoundedCornerShape((2 * p.radiusScale).dp)).background(p.accent2)) }
                            ReceiptStyle.PAPER -> { Text("- - - - -", fontFamily = p.fonts.body, fontSize = 8.sp, color = p.muted, maxLines = 1); Text("Aa", fontFamily = p.fonts.display, fontSize = 9.sp, color = p.accent2) }
                            ReceiptStyle.TERMINAL -> { Text("> ok_", fontFamily = p.fonts.mono, fontSize = 9.sp, color = p.accent2, maxLines = 1); Text("> 0.5", fontFamily = p.fonts.mono, fontSize = 9.sp, color = p.muted, maxLines = 1) }
                        }
                    }
                }
            }
            if (locked) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape).background(p.ground.copy(alpha = 0.85f)).border(1.dp, p.stroke, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { HaloIcon(HIcon.LOCK, p.muted, 11.dp) }
            }
            if (active) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(6.dp).size(18.dp).clip(CircleShape).background(Halo.mint),
                    contentAlignment = Alignment.Center,
                ) { HaloIcon(HIcon.CHECK, Halo.ground, 11.dp, strokeScale = 1.3f) }
            }
        }
        Text(stringResource(p.nameRes), fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = if (active) Halo.ink else Halo.muted, maxLines = 1)
        Text(
            when { p.isFree || Themes.ALL_FREE -> stringResource(R.string.theme_free); locked -> stringResource(R.string.theme_price, WalletActions.THEME_PRICE_SKR); else -> stringResource(R.string.theme_owned) },
            fontFamily = Inter, fontSize = 10.5.sp, color = if (locked) Halo.amber else Halo.muted, textAlign = TextAlign.Center, style = Tabular,
        )
    }
}

private sealed interface UnlockState {
    data object Checking : UnlockState
    data class Ready(val quote: WalletActions.SkrQuote) : UnlockState
    data class Blocked(val message: String) : UnlockState   // no wallet / no SKR / not enough / not configured
    data object Signing : UnlockState
    data class Done(val signature: String) : UnlockState
    data class Error(val message: String) : UnlockState
}

@Composable
private fun ThemeUnlockSheet(p: HaloPalette, signer: SeedVaultSigner, owner: String?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var state by remember { mutableStateOf<UnlockState>(UnlockState.Checking) }
    val name = stringResource(p.nameRes)

    LaunchedEffect(p.id, owner) {
        state = when {
            owner == null -> UnlockState.Blocked(ctx.getString(R.string.theme_unlock_connect))
            !WalletActions.treasuryConfigured -> UnlockState.Blocked(ctx.getString(R.string.theme_unlock_no_treasury))
            else -> {
                val q = runCatching { WalletActions.skrQuote(owner) }.getOrNull()
                when {
                    q == null -> UnlockState.Error(ctx.getString(R.string.wa_no_blockhash))
                    q.account == null -> UnlockState.Blocked(ctx.getString(R.string.theme_unlock_no_skr))
                    !q.enough -> UnlockState.Blocked(ctx.getString(R.string.theme_unlock_insufficient, q.uiBalance(), WalletActions.THEME_PRICE_SKR))
                    else -> UnlockState.Ready(q)
                }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(rs(12)).background(Brush.linearGradient(listOf(p.accent, p.accent2))), contentAlignment = Alignment.Center) { HaloIcon(HIcon.PALETTE, p.ground, 22.dp) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.theme_unlock_title, name), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.ink)
                    Text(stringResource(R.string.theme_unlock_note), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                }
            }
            when (val s = state) {
                UnlockState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.theme_unlock_checking), fontFamily = Mono, fontSize = 12.5.sp, color = Halo.muted); Spacer(Modifier.width(6.dp)); BlinkCaret(Halo.mint, 13.dp)
                }
                is UnlockState.Ready -> {
                    Column(Modifier.fillMaxWidth().clip(rs(16)).background(Halo.cardSoft).border(1.dp, Halo.stroke, rs(16)).padding(14.dp)) {
                        StatRow(stringResource(R.string.theme_unlock_price), "${WalletActions.THEME_PRICE_SKR} SKR", accent = true)
                        StatRow(stringResource(R.string.theme_unlock_balance), s.quote.uiBalance() + " SKR")
                        StatRow(stringResource(R.string.theme_unlock_fee), "≈ 0.00001 SOL")
                        StatRow(stringResource(R.string.theme_unlock_signer), stringResource(R.string.seed_vault))
                    }
                    HoldToConfirm(stringResource(R.string.theme_unlock_hold, WalletActions.THEME_PRICE_SKR)) {
                        state = UnlockState.Signing
                        scope.launch {
                            state = when (val r = WalletActions.payTheme(ctx, signer, owner!!, p, s.quote)) {
                                is WalletActions.Result.Sent -> UnlockState.Done(r.signature)
                                is WalletActions.Result.Failed -> UnlockState.Error(r.message)
                            }
                        }
                    }
                }
                is UnlockState.Blocked -> {
                    Banner(s.message, Halo.amber, HIcon.INFO)
                    GhostButton(stringResource(R.string.close)) { onDismiss() }
                }
                UnlockState.Signing -> Working(stringResource(R.string.theme_unlock_signing))
                is UnlockState.Done -> {
                    Banner(stringResource(R.string.theme_unlock_done, name), Halo.mint, HIcon.CHECK)
                    Text(s.signature, fontFamily = Mono, fontSize = 11.sp, color = Halo.muted, maxLines = 1)
                    PrimaryButton(stringResource(R.string.done), danger = false) { onDismiss() }
                }
                is UnlockState.Error -> {
                    Banner(s.message, Halo.red, HIcon.WARNING)
                    GhostButton(stringResource(R.string.close)) { onDismiss() }
                }
            }
        }
    }
}
