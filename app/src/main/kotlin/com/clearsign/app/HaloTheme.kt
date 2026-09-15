@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.clearsign.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val cardSoft: Color,    // quieter panel / chip, one step below a card
    val cardHi: Color,      // the raised panel: one per page, never two
    val stroke: Color,      // hairline borders
    val accent: Color,      // the brand colour: primary actions, positive
    val accentFill: Color,  // the same accent on a large filled surface, where neon burns
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
    /** Card hairlines flow instead of sitting still — Solana's two colours, moving. */
    val livingStroke: Boolean = false,
) {
    val isFree: Boolean get() = !premium
}

/** The four palettes: Halo (free) + three premium ones unlockable in SKR. */
object Palettes {
    private fun p(
        id: String, nameRes: Int, ground: Long, ground2: Long, card: Long, cardSoft: Long, cardHi: Long, stroke: Long,
        accent: Long, accent2: Long, ink: Long, muted: Long, amber: Long, red: Long,
        premium: Boolean, grain: Float, scanlines: Boolean = false, fillSoft: Boolean = false,
        fonts: HaloFonts, radiusScale: Float = 1f, iconStroke: Float = 1f, receiptStyle: ReceiptStyle = ReceiptStyle.CARDS,
        livingStroke: Boolean = false,
    ) = HaloPalette(
        id = id, nameRes = nameRes,
        ground = Color(ground), ground2 = Color(ground2), card = Color(card), cardSoft = Color(cardSoft), cardHi = Color(cardHi), stroke = Color(stroke),
        accent = Color(accent),
        // A neon accent is right on text and hairlines and wrong on a big filled
        // block, where it glows. `fillSoft` mixes it back towards the card.
        accentFill = if (fillSoft) androidx.compose.ui.graphics.lerp(Color(accent), Color(card), 0.45f) else Color(accent),
        accent2 = Color(accent2), ink = Color(ink), muted = Color(muted), amber = Color(amber), red = Color(red),
        redSoft = Color(red).copy(alpha = 0.133f), accentSoft = Color(accent2).copy(alpha = 0.133f),
        premium = premium, grainAlpha = grain, scanlines = scanlines,
        fonts = fonts, radiusScale = radiusScale, iconStroke = iconStroke, receiptStyle = receiptStyle,
        livingStroke = livingStroke,
    )

    /** Dark premium, glass panels, mint + cyan on a blue-black ground. Free. */
    val halo = p(
        "halo", R.string.theme_halo,
        ground = 0xFF070B12, ground2 = 0xFF0E131A, card = 0xFF222B33, cardSoft = 0xFF171D25, cardHi = 0xFF313B44, stroke = 0xFF1F5E4C,
        accent = 0xFF4DFFD0, accent2 = 0xFF4CC9FF, ink = 0xFFE6EEFB, muted = 0xFF9BAEC6, amber = 0xFFFFC24B, red = 0xFFFF5A6A,
        premium = false, grain = 0f,
        fonts = HaloFonts(SoraFamily, InterFamily, JetBrainsMonoFamily),
    )

    /** Northern lights: deep violet ground, teal + lavender accents. */
    val aurora = p(
        "aurora", R.string.theme_aurora,
        ground = 0xFF0A0714, ground2 = 0xFF120E1C, card = 0xFF292535, cardSoft = 0xFF1C1827, cardHi = 0xFF393445, stroke = 0xFF443464,
        accent = 0xFF62F2C6, accent2 = 0xFFB48CFF, ink = 0xFFEFE9FF, muted = 0xFFAFA6CE, amber = 0xFFFFC66B, red = 0xFFFF5E7A,
        premium = true, grain = 0.04f,
        fonts = HaloFonts(SoraLight, InterLight, JetBrainsMonoFamily, tracking = 0.4f), iconStroke = 0.9f,
    )

    /** Warm copper on charcoal — a leather-and-brass instrument. */
    val ember = p(
        "ember", R.string.theme_ember,
        ground = 0xFF100A07, ground2 = 0xFF18110E, card = 0xFF312723, cardSoft = 0xFF231B17, cardHi = 0xFF423731, stroke = 0xFF613623,
        accent = 0xFFE8A870, accent2 = 0xFFFF8C5A, ink = 0xFFFBF1E6, muted = 0xFFC5B09E, amber = 0xFFFFE271, red = 0xFFFF5266,
        premium = true, grain = 0.045f,
        fonts = HaloFonts(FrauncesFamily, InterFamily, JetBrainsMonoFamily), radiusScale = 0.5f, iconStroke = 0.85f, receiptStyle = ReceiptStyle.PAPER,
    )

    /** Green phosphor terminal, with faint CRT scanlines. */
    val phosphor = p(
        "phosphor", R.string.theme_phosphor,
        ground = 0xFF050807, ground2 = 0xFF0C100F, card = 0xFF242A27, cardSoft = 0xFF171C19, cardHi = 0xFF343C37, stroke = 0xFF385C43,
        accent = 0xFF5CFF8A, accent2 = 0xFF9CFFB8, ink = 0xFFD8FFE0, muted = 0xFF89BE9A, amber = 0xFFFFD75C, red = 0xFFFF4F5E,
        premium = true, grain = 0.05f, scanlines = true,
        fonts = HaloFonts(JetBrainsMonoFamily, JetBrainsMonoFamily, JetBrainsMonoFamily), radiusScale = 0.15f, iconStroke = 1.25f, receiptStyle = ReceiptStyle.TERMINAL,
    )

    /** Solana brand: signature purple + mint green on a violet-black ground. */
    /**
     * Solana as a whole palette: violet ground, its green and its purple.
     *
     * Free, and it was not: a theme nobody can select is a theme nobody can judge,
     * and this one turned out to be the one that suits the link and mint screens.
     * [flow] is the other half of the same idea — Halo's colours with only the
     * hairline moving — and both exist because they answer different moods.
     */
    val solana = p(
        "solana", R.string.theme_solana,
        ground = 0xFF0B0518, ground2 = 0xFF130C20, card = 0xFF2A2338, cardSoft = 0xFF1D162A, cardHi = 0xFF3A3349, stroke = 0xFF463367,
        accent = 0xFF14F195, accent2 = 0xFFB98CFF, ink = 0xFFF3EEFF, muted = 0xFFB3A7D4, amber = 0xFFFFC24B, red = 0xFFFF5A6A,
        premium = false, grain = 0.03f,
        fonts = HaloFonts(SoraFamily, InterFamily, JetBrainsMonoFamily),
    )

    /**
     * Home, with a living edge.
     *
     * Every colour is Halo's, unchanged — the point was never a new palette. The
     * only difference is the hairline around each card, where Solana's purple and
     * green slide past each other instead of sitting still. A theme is allowed to
     * change one thing.
     */
    val flow = p(
        "flow", R.string.theme_flow,
        ground = 0xFF070B12, ground2 = 0xFF0E131A, card = 0xFF222B33, cardSoft = 0xFF171D25, cardHi = 0xFF313B44, stroke = 0xFF1F5E4C,
        accent = 0xFF4DFFD0, accent2 = 0xFF4CC9FF, ink = 0xFFE6EEFB, muted = 0xFF9BAEC6, amber = 0xFFFFC24B, red = 0xFFFF5A6A,
        premium = false, grain = 0f,
        fonts = HaloFonts(SoraFamily, InterFamily, JetBrainsMonoFamily), livingStroke = true,
    )

    /** Seeker (SKR): black, near-mono, one green accent — the device's own look. */
    val skr = p(
        "skr", R.string.theme_skr,
        ground = 0xFF060606, ground2 = 0xFF0E0E0E, card = 0xFF282828, cardSoft = 0xFF191919, cardHi = 0xFF393939, stroke = 0xFF555555,
        accent = 0xFF14F195, accent2 = 0xFFEDEDED, ink = 0xFFF6F6F6, muted = 0xFFA6A6A6, amber = 0xFFFFC24B, red = 0xFFFF5A6A,
        premium = true, grain = 0f, radiusScale = 0.6f,
        fonts = HaloFonts(SoraFamily, InterFamily, JetBrainsMonoFamily),
    )

    // ---- candidates -----------------------------------------------------------
    // Four treatments of the same idea, free so they can actually be worn for a
    // while on every real screen. They share one ground and one neutral ladder
    // (a cool-white veil, no accent tint) and differ only in which colour means
    // "this is the action". The loser ones get deleted once a winner is picked.
    private const val G0 = 0xFF070B12
    private const val G2 = 0xFF12161E
    private const val CS = 0xFF181D25
    private const val CD = 0xFF22272F
    private const val CH = 0xFF2D333C
    private const val ST = 0xFF434953
    private const val INK0 = 0xFFE6EEFB
    private const val MU0 = 0xFF9BAEC6

    /** Mint acts, cyan links, and nothing neon on a large fill. */
    val mintSoft = p(
        "mintsoft", R.string.theme_mint_soft,
        ground = G0, ground2 = G2, card = CD, cardSoft = CS, cardHi = CH, stroke = ST,
        accent = 0xFF4DFFD0, accent2 = 0xFF4CC9FF, ink = INK0, muted = MU0, amber = 0xFFFFC24B, red = 0xFFFF5A6A,
        premium = false, grain = 0f, fillSoft = true,
        fonts = HaloFonts(SoraFamily, InterFamily, JetBrainsMonoFamily),
    )

    /** The same rule, but the accent stays at full strength everywhere. */
    val mintNeon = p(
        "mintneon", R.string.theme_mint_neon,
        ground = G0, ground2 = G2, card = CD, cardSoft = CS, cardHi = CH, stroke = ST,
        accent = 0xFF4DFFD0, accent2 = 0xFF4CC9FF, ink = INK0, muted = MU0, amber = 0xFFFFC24B, red = 0xFFFF5A6A,
        premium = false, grain = 0f,
        fonts = HaloFonts(SoraFamily, InterFamily, JetBrainsMonoFamily),
    )

    /** Cyan acts; mint is kept for money and for anything that went up. */
    val cyanAct = p(
        "cyanact", R.string.theme_cyan_act,
        ground = G0, ground2 = G2, card = CD, cardSoft = CS, cardHi = CH, stroke = ST,
        accent = 0xFF4CC9FF, accent2 = 0xFF7FD8FF, ink = INK0, muted = MU0, amber = 0xFFFFC24B, red = 0xFFFF5A6A,
        premium = false, grain = 0f, fillSoft = true,
        fonts = HaloFonts(SoraFamily, InterFamily, JetBrainsMonoFamily),
    )

    /** The warm road the banks take: gold acts, mint stays positive. */
    val gold = p(
        "gold", R.string.theme_gold,
        ground = G0, ground2 = G2, card = CD, cardSoft = CS, cardHi = CH, stroke = ST,
        accent = 0xFFFFC24B, accent2 = 0xFF4CC9FF, ink = INK0, muted = MU0, amber = 0xFFFFD98A, red = 0xFFFF5A6A,
        premium = false, grain = 0f, fillSoft = true,
        fonts = HaloFonts(SoraFamily, InterFamily, JetBrainsMonoFamily),
    )

    val all: List<HaloPalette> = listOf(halo, mintSoft, mintNeon, cyanAct, gold, flow, solana, skr, aurora, ember, phosphor)

    /** The built-ins plus the user's custom palette (always last). */
    fun withCustom(ctx: android.content.Context): List<HaloPalette> = all + CustomTheme.palette(ctx)

    /**
     * The theme a phone starts with, and what an unknown id falls back to.
     *
     * Not [halo] any more. Menta is the same palette with the accent mixed back
     * towards the card on large filled surfaces, and on a big button that is the
     * difference between a colour and a glare.
     */
    val default: HaloPalette get() = mintSoft

    fun byId(id: String?): HaloPalette =
        if (id == CustomTheme.ID) CustomThemeHolder else all.firstOrNull { it.id == id } ?: default

    /** A process-lifetime cache of the custom palette so `byId` stays cheap and non-Context. */
    internal var CustomThemeHolder: HaloPalette = halo
}

/**
 * The user's own palette, edited live in the theme editor: accent, secondary,
 * background, corner roundness, grain, CRT and receipt layout. Colours the user
 * doesn't set (card, stroke, ink, muted) are derived from the ones they do, so
 * the result always reads as one coherent theme.
 */
object CustomTheme {
    const val ID = "custom"
    private const val PREFS = "clearsign_custom_theme"

    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    // Defaults mirror Halo so a fresh custom theme is a sane starting point.
    fun accent(ctx: android.content.Context) = prefs(ctx).getLong("accent", 0xFF4DFFD0)
    fun accent2(ctx: android.content.Context) = prefs(ctx).getLong("accent2", 0xFF4CC9FF)
    fun ground(ctx: android.content.Context) = prefs(ctx).getLong("ground", 0xFF070B12)
    fun radius(ctx: android.content.Context) = prefs(ctx).getFloat("radius", 1f)
    fun grain(ctx: android.content.Context) = prefs(ctx).getFloat("grain", 0f)
    fun scanlines(ctx: android.content.Context) = prefs(ctx).getBoolean("scanlines", false)
    fun receiptStyle(ctx: android.content.Context) = ReceiptStyle.entries[prefs(ctx).getInt("receipt", 0).coerceIn(0, ReceiptStyle.entries.size - 1)]

    fun save(
        ctx: android.content.Context, accent: Long, accent2: Long, ground: Long,
        radius: Float, grain: Float, scanlines: Boolean, receipt: ReceiptStyle,
    ) {
        prefs(ctx).edit()
            .putLong("accent", accent).putLong("accent2", accent2).putLong("ground", ground)
            .putFloat("radius", radius).putFloat("grain", grain).putBoolean("scanlines", scanlines)
            .putInt("receipt", receipt.ordinal).apply()
        Palettes.CustomThemeHolder = palette(ctx)
        if (Halo.palette.id == ID) Halo.palette = Palettes.CustomThemeHolder
    }

    /** Build (and cache) the palette from saved values. */
    fun palette(ctx: android.content.Context): HaloPalette {
        val pal = build(accent(ctx), accent2(ctx), ground(ctx), radius(ctx), grain(ctx), scanlines(ctx), receiptStyle(ctx))
        Palettes.CustomThemeHolder = pal
        return pal
    }

    /** Pure builder: derive a coherent palette from the few colours the user picks. */
    fun build(accent: Long, accent2: Long, ground: Long, radius: Float, grain: Float, scanlines: Boolean, receipt: ReceiptStyle): HaloPalette {
        val g = ground; val a = accent; val a2 = accent2
        val dark = luminance(g) < 0.5f
        val ink = if (dark) 0xFFECEFF4.toInt() else 0xFF10151F.toInt()
        return HaloPalette(
            id = ID, nameRes = R.string.theme_custom,
            // The same ladder the built-in palettes climb, measured from the user's
            // own ground and tinted with their own accent2, so a custom theme has
            // the same depth instead of staying flat while the others gained it.
            ground = Color(g), ground2 = Color(step(g, a2, 0.034f)),
            card = Color(step(g, a2, 0.139f)), cardSoft = Color(step(g, a2, 0.080f)),
            cardHi = Color(step(g, a2, 0.210f)),
            stroke = Color(mix(g.toInt(), a2.toInt(), 0.34f)),
            accent = Color(a), accent2 = Color(a2), ink = Color(ink),
            // Lifted a quarter of the way to ink: the surfaces moved up under it.
            accentFill = androidx.compose.ui.graphics.lerp(Color(a), Color(step(g, a2, 0.139f)), 0.45f),
            muted = Color(mix(mix(ink, g.toInt(), 0.42f), ink, 0.25f)), amber = Color(0xFFFFC24B), red = Color(0xFFFF5A6A),
            redSoft = Color(0xFFFF5A6A).copy(alpha = 0.133f), accentSoft = Color(a2).copy(alpha = 0.133f),
            premium = true, grainAlpha = grain, scanlines = scanlines,
            fonts = HaloFonts(SoraFamily, InterFamily, JetBrainsMonoFamily),
            radiusScale = radius, iconStroke = 1f, receiptStyle = receipt,
        )
    }

    /**
     * One rung of the surface ladder: the ground mixed towards a pale version of
     * the theme's own second accent. Keeping the hue is what makes a raised panel
     * read as the same material rather than as grey paint.
     */
    private fun step(ground: Long, accent2: Long, f: Float): Int {
        val tint = mix(accent2.toInt(), 0xFFFFFFFF.toInt(), 0.72f)
        return mix(ground.toInt(), tint, f)
    }

    // ---- colour math (ARGB Long/Int) ----
    private fun withAlpha(rgb: Int, alpha: Int) = ((alpha and 0xFF) shl 24) or (rgb and 0x00FFFFFF)
    private fun lighten(c: Long, amt: Float): Int = mix(c.toInt(), 0xFFFFFFFF.toInt(), amt)
    private fun mix(a: Int, b: Int, t: Float): Int {
        val ia = 1f - t
        val ar = (a ushr 16) and 0xFF; val ag = (a ushr 8) and 0xFF; val ab = a and 0xFF
        val br = (b ushr 16) and 0xFF; val bg = (b ushr 8) and 0xFF; val bb = b and 0xFF
        val r = (ar * ia + br * t).toInt().coerceIn(0, 255)
        val gg = (ag * ia + bg * t).toInt().coerceIn(0, 255)
        val bl = (ab * ia + bb * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (gg shl 8) or bl
    }
    private fun luminance(c: Long): Float {
        val r = ((c ushr 16) and 0xFF) / 255f; val g = ((c ushr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }
}

/**
 * The colours every screen reads. Backed by Compose state, so composition and
 * draw lambdas that read a slot re-run when the palette changes; `Themes.select`
 * is the only writer (main thread).
 */
object Halo {
    var palette: HaloPalette by mutableStateOf(Palettes.default)

    val ground: Color get() = palette.ground
    val ground2: Color get() = palette.ground2
    val card: Color get() = palette.card
    val cardSoft: Color get() = palette.cardSoft
    val cardHi: Color get() = palette.cardHi
    /** The accent on a big filled surface: the same colour, quieter, so it does not glow. */
    val mintFill: Color get() = palette.accentFill
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

/**
 * The type scale: six roles, no invented sizes.
 *
 * Before this there were 21 different font sizes in the app, six of them
 * fractional, each chosen by eye on one screen. That is what makes an interface
 * look assembled rather than designed — the sizes carry no meaning, so nothing
 * on a page is visibly more important than anything else.
 *
 * Sizes stay in `sp` so the text-size slider (`ScaledText`) still moves them all
 * together, and the families are palette getters so a theme can change the face.
 * Colour is deliberately not here: colour is information, decided at the call
 * site, not a property of a size.
 */
object HaloType {
    /** Captions, units, the small print inside a chip. The legibility floor. */
    val label: TextStyle get() = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 15.sp)

    /** Secondary text: notes under a title, the second line of a row. */
    val small: TextStyle get() = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)

    /** Body copy and the title of a list row. */
    val body: TextStyle get() = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 21.sp)

    /** The title of a card or a block. */
    val title: TextStyle get() = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 23.sp)

    /** The title of a page. */
    val screen: TextStyle get() = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 27.sp)

    /** A figure that is the point of the screen. */
    val amount: TextStyle get() = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 38.sp, fontFeatureSettings = "tnum")

    /** Addresses, signatures, anything meant to be compared character by character. */
    val code: TextStyle get() = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp)
}

/**
 * The radius scale. Four values, and each one means a level: a pill is a control,
 * 10 is something inside a panel, 16 is a panel, 22 is a card or a sheet. Pass
 * these to [rs] instead of a number picked by eye — there were fifteen of those.
 */
object Radius {
    const val pill = 999
    const val row = 10
    const val panel = 16
    const val card = 22
}

/** The spacing scale. Four, eight, twelve, sixteen, twenty-four. Nothing between. */
object Space {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
}

/** WCAG relative luminance / contrast, used by the palette test and the picker. */
fun Color.relativeLuminance(): Double {
    fun lin(c: Float): Double { val v = c.toDouble(); return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
    return 0.2126 * lin(red) + 0.7152 * lin(green) + 0.0722 * lin(blue)
}

fun contrastRatio(a: Color, b: Color): Double {
    val la = a.relativeLuminance(); val lb = b.relativeLuminance()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}
