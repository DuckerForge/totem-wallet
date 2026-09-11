@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.clearsign.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/** How the receipt is laid out: glass cards, a paper till receipt, or a terminal log. */
enum class ReceiptStyle { CARDS, PAPER, TERMINAL }

/** Per-theme typography. Weight comes from the call site; [tracking] is extra letter-spacing (sp). */
data class HaloFonts(val display: FontFamily, val body: FontFamily, val mono: FontFamily, val tracking: Float = 0f)

/**
 * A full colour palette for the app. Every screen paints through [Halo], which
 * forwards to the palette currently selected, so swapping a theme is one
 * assignment — no screen knows more than "accent" or "card".
 *
 * Contrast is a contract, not a hope: `PaletteContrastTest` checks every palette
 * (muted ≥ 4.5:1, accent ≥ 7:1, ink ≥ 12:1 against the ground).
 */
data class HaloPalette(
    val id: String,
    val nameRes: Int,
    val ground: Color,      // page background (bottom of the gradient)
    val ground2: Color,     // page background (top of the gradient)
    val card: Color,        // glass panel
    val cardSoft: Color,    // lighter panel / chip
    val stroke: Color,      // hairline borders
    val accent: Color,      // the brand colour: primary actions, positive
    val accent2: Color,     // secondary accent: links, info, avatars
    val ink: Color,         // body text
    val muted: Color,       // secondary text
    val amber: Color,       // warnings
    val red: Color,         // danger
    val redSoft: Color,     // danger tint
    val accentSoft: Color,  // accent2 tint
    val premium: Boolean,   // unlocked by paying in SKR
    val grainAlpha: Float,  // film-grain texture strength (0 = none)
    val scanlines: Boolean, // CRT scanlines (Phosphor)
    val fonts: HaloFonts,   // typography of the theme
    val radiusScale: Float, // 1 = rounded as designed, 0.15 = nearly square
    val iconStroke: Float,  // multiplier on the hand-drawn icon stroke
    val receiptStyle: ReceiptStyle,
) {
    val isFree: Boolean get() = !premium
}

/** The four palettes: Halo (free) + three premium ones unlockable in SKR. */
object Palettes {
    private fun p(
        id: String, nameRes: Int, ground: Long, ground2: Long, card: Long, cardSoft: Long, stroke: Long,
        accent: Long, accent2: Long, ink: Long, muted: Long, amber: Long, red: Long,
        premium: Boolean, grain: Float, scanlines: Boolean = false,
        fonts: HaloFonts, radiusScale: Float = 1f, iconStroke: Float = 1f, receiptStyle: ReceiptStyle = ReceiptStyle.CARDS,
    ) = HaloPalette(
        id = id, nameRes = nameRes,
        ground = Color(ground), ground2 = Color(ground2), card = Color(card), cardSoft = Color(cardSoft), stroke = Color(stroke),
        accent = Color(accent), accent2 = Color(accent2), ink = Color(ink), muted = Color(muted), amber = Color(amber), red = Color(red),
        redSoft = Color(red).copy(alpha = 0.133f), accentSoft = Color(accent2).copy(alpha = 0.133f),
        premium = premium, grainAlpha = grain, scanlines = scanlines,
        fonts = fonts, radiusScale = radiusScale, iconStroke = iconStroke, receiptStyle = receiptStyle,
    )

    /** Dark premium, glass panels, mint + cyan on a blue-black ground. Free. */
    val halo = p(
        "halo", R.string.theme_halo,
        ground = 0xFF070B12, ground2 = 0xFF0B1120, card = 0xE6141C2C, cardSoft = 0x99101828, stroke = 0x2E78A0C8,
        accent = 0xFF4DFFD0, accent2 = 0xFF4CC9FF, ink = 0xFFE6EEFB, muted = 0xFF8299B4, amber = 0xFFFFC24B, red = 0xFFFF5A6A,
        premium = false, grain = 0f,
        fonts = HaloFonts(SoraFamily, InterFamily, JetBrainsMonoFamily),
    )

    /** Northern lights: deep violet ground, teal + lavender accents. */
    val aurora = p(
        "aurora", R.string.theme_aurora,
        ground = 0xFF0A0714, ground2 = 0xFF120C22, card = 0xE61B1330, cardSoft = 0x99160F2A, stroke = 0x2E9A7CD8,
        accent = 0xFF62F2C6, accent2 = 0xFFB48CFF, ink = 0xFFEFE9FF, muted = 0xFF9A8FBE, amber = 0xFFFFC66B, red = 0xFFFF5E7A,
        premium = true, grain = 0.04f,
        fonts = HaloFonts(SoraLight, InterLight, JetBrainsMonoFamily, tracking = 0.4f), iconStroke = 0.9f,
    )

    /** Warm copper on charcoal — a leather-and-brass instrument. */
    val ember = p(
        "ember", R.string.theme_ember,
        ground = 0xFF100A07, ground2 = 0xFF1A100A, card = 0xE6261912, cardSoft = 0x99201510, stroke = 0x2ED8A070,
        accent = 0xFFE8A870, accent2 = 0xFFFF8C5A, ink = 0xFFFBF1E6, muted = 0xFFB39A86, amber = 0xFFFFE271, red = 0xFFFF5266,
        premium = true, grain = 0.045f,
        fonts = HaloFonts(FrauncesFamily, InterFamily, JetBrainsMonoFamily), radiusScale = 0.5f, iconStroke = 0.85f, receiptStyle = ReceiptStyle.PAPER,
    )

    /** Green phosphor terminal, with faint CRT scanlines. */
    val phosphor = p(
        "phosphor", R.string.theme_phosphor,
        ground = 0xFF050807, ground2 = 0xFF0A100C, card = 0xE60E1A12, cardSoft = 0x990B140E, stroke = 0x2E4CFF7A,
        accent = 0xFF5CFF8A, accent2 = 0xFF9CFFB8, ink = 0xFFD8FFE0, muted = 0xFF6FA882, amber = 0xFFFFD75C, red = 0xFFFF4F5E,
        premium = true, grain = 0.05f, scanlines = true,
        fonts = HaloFonts(JetBrainsMonoFamily, JetBrainsMonoFamily, JetBrainsMonoFamily), radiusScale = 0.15f, iconStroke = 1.25f, receiptStyle = ReceiptStyle.TERMINAL,
    )

    val all: List<HaloPalette> = listOf(halo, aurora, ember, phosphor)

    fun byId(id: String?): HaloPalette = all.firstOrNull { it.id == id } ?: halo
}

/**
 * The colours every screen reads. Backed by Compose state, so composition and
 * draw lambdas that read a slot re-run when the palette changes; `Themes.select`
 * is the only writer (main thread).
 */
object Halo {
    var palette: HaloPalette by mutableStateOf(Palettes.halo)

    val ground: Color get() = palette.ground
    val ground2: Color get() = palette.ground2
    val card: Color get() = palette.card
    val cardSoft: Color get() = palette.cardSoft
    val stroke: Color get() = palette.stroke
    val mint: Color get() = palette.accent
    val cyan: Color get() = palette.accent2
    val ink: Color get() = palette.ink
    val muted: Color get() = palette.muted
    val amber: Color get() = palette.amber
    val red: Color get() = palette.red
    val redSoft: Color get() = palette.redSoft
    val cyanSoft: Color get() = palette.accentSoft

    // ---- shapes: every corner in the app goes through here, scaled per theme ----
    val radiusScale: Float get() = palette.radiusScale
    /** 999 = "pill" at the call site: squarish themes turn it into a 4dp chamfer. */
    fun radius(dp: Int): Dp = if (dp >= 999) (if (radiusScale < 0.3f) 4.dp else 999.dp) else (dp * radiusScale).dp
    fun shape(dp: Int): RoundedCornerShape = RoundedCornerShape(radius(dp))
    fun shapeTop(dp: Int): RoundedCornerShape = RoundedCornerShape(topStart = radius(dp), topEnd = radius(dp))
}

/** Theme-scaled rounded corners — the one-token replacement for `RoundedCornerShape(N.dp)`. */
internal fun rs(dp: Int): RoundedCornerShape = Halo.shape(dp)

private fun vf(resId: Int, w: Int, axisWeight: Int = w) = Font(
    resId = resId,
    weight = FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(axisWeight)),
)

// Families are built once; which one a theme uses is decided by its palette.
private val SoraFamily = FontFamily(vf(R.font.sora, 500), vf(R.font.sora, 600), vf(R.font.sora, 700))
private val InterFamily = FontFamily(vf(R.font.inter, 400), vf(R.font.inter, 500), vf(R.font.inter, 600))
/** Aurora: the call site asks for Bold, the variable axis renders 100 lighter — airy without touching screens. */
private val SoraLight = FontFamily(vf(R.font.sora, 500, 400), vf(R.font.sora, 600, 500), vf(R.font.sora, 700, 600))
private val InterLight = FontFamily(vf(R.font.inter, 400, 300), vf(R.font.inter, 500, 400), vf(R.font.inter, 600, 500))
/** Ember: a soft serif for the paper receipt. */
private val FrauncesFamily = FontFamily(vf(R.font.fraunces, 400), vf(R.font.fraunces, 500), vf(R.font.fraunces, 600), vf(R.font.fraunces, 700))
/** Phosphor + every address/signature/ticker: a proper mono. */
private val JetBrainsMonoFamily = FontFamily(vf(R.font.jetbrains_mono, 400), vf(R.font.jetbrains_mono, 500), vf(R.font.jetbrains_mono, 600), vf(R.font.jetbrains_mono, 700))

/** Display face — headings, amounts, the wordmark. Follows the theme. */
val Sora: FontFamily get() = Halo.palette.fonts.display

/** Body face — labels, running text. Follows the theme. */
val Inter: FontFamily get() = Halo.palette.fonts.body

/** Addresses, signatures, the stats ticker. Follows the theme. */
val Mono: FontFamily get() = Halo.palette.fonts.mono

/** Tabular figures so amounts line up; all bundled faces ship `tnum`. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

/** WCAG relative luminance / contrast, used by the palette test and the picker. */
fun Color.relativeLuminance(): Double {
    fun lin(c: Float): Double { val v = c.toDouble(); return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
    return 0.2126 * lin(red) + 0.7152 * lin(green) + 0.0722 * lin(blue)
}

fun contrastRatio(a: Color, b: Color): Double {
    val la = a.relativeLuminance(); val lb = b.relativeLuminance()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}
