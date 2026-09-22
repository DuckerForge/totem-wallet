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
internal fun ThemesCard(signer: SeedVaultSigner, owner: String?, onNeedPro: () -> Unit) {
    val ctx = LocalContext.current
    val pro by Pro.isPro
    val current = Halo.palette

    var showEditor by remember { mutableStateOf(false) }
    // Built-ins first, then a "Custom" tile that opens the editor.
    // Built once, and again when the editor closes: reading the saved custom
    // palette is a preferences read, and it must not happen on every recomposition.
    val tiles = remember(showEditor) { Palettes.all + CustomTheme.palette(ctx) }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionTitle(stringResource(R.string.home_themes_hdr), stringResource(R.string.home_themes_sub), HIcon.PALETTE)
            tiles.chunked(3).forEach { rowTiles ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowTiles.forEach { p ->
                        val custom = p.id == CustomTheme.ID
                        val isUnlocked = p.isFree || pro
                        ThemeTile(
                            p = p, active = p.id == current.id, locked = !isUnlocked, custom = custom,
                            modifier = Modifier.weight(1f),
                            onTap = {
                                if (!isUnlocked) onNeedPro()
                                else if (custom) showEditor = true
                                else { Themes.select(ctx, p.id); Haptics.tick(ctx) }
                            },
                            onDevUnlock = if (BuildConfig.DEBUG && !isUnlocked) ({ Pro.set(ctx, null); if (custom) showEditor = true else Themes.select(ctx, p.id); Haptics.success(ctx) }) else null,
                        )
                    }
                    repeat(3 - rowTiles.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Text(
                if (pro || current.premium) stringResource(R.string.theme_note_pro) else stringResource(R.string.theme_note_free),
                fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted,
            )
        }
    }
    if (showEditor) ThemeEditorSheet { showEditor = false }
}

@Composable
private fun ThemeTile(p: HaloPalette, active: Boolean, locked: Boolean, modifier: Modifier, onTap: () -> Unit, onDevUnlock: (() -> Unit)?, custom: Boolean = false) {
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
            if (custom && !locked) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape).background(p.accent.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center,
                ) { HaloIcon(HIcon.PALETTE, p.ground, 11.dp) }
            }
        }
        Text(stringResource(p.nameRes), fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = if (active) Halo.ink else Halo.muted, maxLines = 1)
        Text(
            when { p.isFree -> stringResource(R.string.theme_free); locked -> stringResource(R.string.theme_pro_tag); else -> stringResource(R.string.theme_owned) },
            fontFamily = Inter, fontSize = 10.5.sp, color = if (locked) Halo.amber else Halo.muted, textAlign = TextAlign.Center, style = Tabular,
        )
    }
}
