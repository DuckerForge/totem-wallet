@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ---- colour <-> HSV helpers (alpha always opaque) ------------------------- */

private fun Long.toHsv(): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toInt(), hsv)
    return hsv
}

private fun hsvToLong(h: Float, s: Float, v: Float): Long {
    val argb = android.graphics.Color.HSVToColor(floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
    return 0xFF000000L or (argb.toLong() and 0xFFFFFF)
}

/**
 * The custom-theme editor: pick the accent, the secondary and the background,
 * the corner roundness and text size, the surface effects and the receipt
 * layout. Everything previews live on the app behind the sheet; Save persists it
 * and switches to the Custom theme. Cancel restores whatever was on before.
 */
@Composable
internal fun ThemeEditorSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Remember what was on when we opened, to restore on cancel.
    val original = remember { Halo.palette }
    val originalId = remember { original.id }

    val aHsv = remember { CustomTheme.accent(ctx).toHsv() }
    val a2Hsv = remember { CustomTheme.accent2(ctx).toHsv() }
    val gHsv = remember { CustomTheme.ground(ctx).toHsv() }

    var aH by remember { mutableFloatStateOf(aHsv[0]) }; var aS by remember { mutableFloatStateOf(aHsv[1]) }; var aV by remember { mutableFloatStateOf(aHsv[2]) }
    var bH by remember { mutableFloatStateOf(a2Hsv[0]) }; var bS by remember { mutableFloatStateOf(a2Hsv[1]) }; var bV by remember { mutableFloatStateOf(a2Hsv[2]) }
    var gH by remember { mutableFloatStateOf(gHsv[0]) }; var gS by remember { mutableFloatStateOf(gHsv[1]) }; var gV by remember { mutableFloatStateOf(gHsv[2]) }
    var radius by remember { mutableFloatStateOf(CustomTheme.radius(ctx)) }
    var grain by remember { mutableStateOf(CustomTheme.grain(ctx) > 0f) }
    var crt by remember { mutableStateOf(CustomTheme.scanlines(ctx)) }
    var receipt by remember { mutableStateOf(CustomTheme.receiptStyle(ctx)) }
    val textScale by Settings.textScale

    // Rebuild the live palette from the current sliders and apply it behind the sheet.
    fun apply() {
        Halo.palette = CustomTheme.build(
            accent = hsvToLong(aH, aS, aV), accent2 = hsvToLong(bH, bS, bV), ground = hsvToLong(gH, gS, gV),
            radius = radius, grain = if (grain) 0.05f else 0f, scanlines = crt, receipt = receipt,
        )
    }
    // First composition: show the in-progress theme immediately.
    remember { apply(); true }

    ModalBottomSheet(
        onDismissRequest = { Halo.palette = original; onDismiss() },
        sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.editor_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Halo.ink)
            Text(stringResource(R.string.editor_sub), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
            Spacer(Modifier.height(16.dp))

            ColorBlock(stringResource(R.string.editor_accent), Color(hsvToLong(aH, aS, aV)), aH, aS, aV,
                { aH = it; apply() }, { aS = it; apply() }, { aV = it; apply() })
            ColorBlock(stringResource(R.string.editor_accent2), Color(hsvToLong(bH, bS, bV)), bH, bS, bV,
                { bH = it; apply() }, { bS = it; apply() }, { bV = it; apply() })
            ColorBlock(stringResource(R.string.editor_ground), Color(hsvToLong(gH, gS, gV)), gH, gS, gV,
                { gH = it; apply() }, { gS = it; apply() }, { gV = it; apply() })

            SliderRow(stringResource(R.string.editor_text_size), textScale, 0.85f, 1.30f) { Settings.setTextScale(ctx, it) }
            SliderRow(stringResource(R.string.editor_corners), radius, 0.1f, 1f) { radius = it; apply() }

            Spacer(Modifier.height(6.dp))
            ToggleRow(stringResource(R.string.editor_grain), grain) { grain = it; apply() }
            ToggleRow(stringResource(R.string.editor_crt), crt) { crt = it; apply() }

            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.editor_receipt), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Halo.muted)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReceiptStyle.entries.forEach { style ->
                    val on = style == receipt
                    Box(
                        Modifier.weight(1f).clip(rs(12)).background(if (on) Halo.mint.copy(alpha = 0.16f) else Halo.cardSoft)
                            .border(1.dp, if (on) Halo.mint else Halo.stroke, rs(12)).clickable { receipt = style; apply() }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(receiptStyleName(style)), fontFamily = Inter, fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp, color = if (on) Halo.mint else Halo.muted,
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            PrimaryButton(stringResource(R.string.editor_save), danger = false, icon = HIcon.CHECK) {
                CustomTheme.save(ctx, hsvToLong(aH, aS, aV), hsvToLong(bH, bS, bV), hsvToLong(gH, gS, gV), radius, if (grain) 0.05f else 0f, crt, receipt)
                if (Pro.isPro.value) Themes.select(ctx, CustomTheme.ID) else Halo.palette = CustomTheme.palette(ctx)
                Haptics.success(ctx)
                onDismiss()
            }
            Spacer(Modifier.height(8.dp))
            GhostButton(stringResource(R.string.cancel)) { Halo.palette = original; if (originalId != CustomTheme.ID) Themes.select(ctx, originalId); onDismiss() }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun receiptStyleName(s: ReceiptStyle) = when (s) {
    ReceiptStyle.CARDS -> R.string.editor_style_cards
    ReceiptStyle.PAPER -> R.string.editor_style_paper
    ReceiptStyle.TERMINAL -> R.string.editor_style_terminal
}

@Composable
private fun ColorBlock(
    label: String, preview: Color, h: Float, s: Float, v: Float,
    onH: (Float) -> Unit, onS: (Float) -> Unit, onV: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(rs(8)).background(preview).border(cardBorder(), rs(8)))
            Spacer(Modifier.width(10.dp))
            Text(label, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.ink)
        }
        MiniSlider(h, 0f, 360f, Halo.mint, onH)
        MiniSlider(s, 0f, 1f, Halo.cyan, onS)
        MiniSlider(v, 0f, 1f, Halo.ink, onV)
    }
}

@Composable
private fun MiniSlider(value: Float, from: Float, to: Float, tint: Color, onChange: (Float) -> Unit) {
    Slider(
        value = value, onValueChange = onChange, valueRange = from..to,
        colors = SliderDefaults.colors(thumbColor = tint, activeTrackColor = tint.copy(alpha = 0.7f), inactiveTrackColor = Halo.stroke),
        modifier = Modifier.height(28.dp),
    )
}

@Composable
private fun SliderRow(label: String, value: Float, from: Float, to: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Halo.muted)
            Text("%.2f×".format(value), fontFamily = Mono, fontSize = 12.sp, color = Halo.ink, style = Tabular)
        }
        MiniSlider(value, from, to, Halo.mint, onChange)
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(rs(12)).clickable { onChange(!on) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.ink, modifier = Modifier.weight(1f))
        Box(
            Modifier.width(44.dp).height(26.dp).clip(rs(999)).background(if (on) Halo.mint.copy(alpha = 0.9f) else Halo.cardSoft).border(1.dp, if (on) Halo.mint else Halo.stroke, rs(999)),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.padding(3.dp).size(20.dp).clip(rs(999)).background(if (on) Halo.ground else Halo.muted))
        }
    }
}
