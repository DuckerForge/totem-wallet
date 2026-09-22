package com.clearsign.app

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The "vintage" surface treatments: a whisper of film grain, optional CRT
 * scanlines, and a soft flash when the theme changes. All of it is one tiled
 * bitmap draw on top of the content — no per-frame allocation, no recomposition.
 */

private val grainBrush: ShaderBrush by lazy {
    val n = 128
    val rnd = Random(0x5EED)
    val px = IntArray(n * n) {
        val v = rnd.nextInt(256)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    val bmp = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
    ShaderBrush(ImageShader(bmp, TileMode.Repeated, TileMode.Repeated))
}

/** Film grain over the content at [alpha] (0 = off). */
fun Modifier.grain(alpha: Float): Modifier =
    if (alpha <= 0f) this else drawWithContent {
        drawContent()
        drawRect(brush = grainBrush, alpha = alpha)
    }

private fun scanlineBrush(density: Float): ShaderBrush {
    // One dark row every ~3dp: fine enough to read as texture, not stripes.
    val period = (3f * density).roundToInt().coerceAtLeast(2)
    val px = IntArray(period) { if (it == period - 1) (0xFF shl 24) else 0 }
    val bmp: ImageBitmap = Bitmap.createBitmap(px, 1, period, Bitmap.Config.ARGB_8888).asImageBitmap()
    return ShaderBrush(ImageShader(bmp, TileMode.Repeated, TileMode.Repeated))
}

/** Faint CRT scanlines (Phosphor). */
@Composable
fun Modifier.scanlines(alpha: Float = 0.06f): Modifier {
    val density = LocalDensity.current.density
    val brush = remember(density) { scanlineBrush(density) }
    return drawWithContent {
        drawContent()
        drawRect(brush = brush, alpha = alpha)
    }
}

/** A 350 ms accent flash when the palette changes — the theme "lands". */
@Composable
fun Modifier.themeFlash(): Modifier {
    val id = Halo.palette.id
    val flash = remember { Animatable(0f) }
    val seen = remember { arrayOf(id) }
    LaunchedEffect(id) {
        if (seen[0] != id) {
            seen[0] = id
            flash.snapTo(1f)
            flash.animateTo(0f, tween(350))
        }
    }
    return drawWithContent {
        drawContent()
        val a = flash.value
        if (a > 0f) drawRect(Halo.mint, alpha = a * 0.18f)
    }
}

/**
 * CRT / old-TV overlay: heavy scanlines, a bright roll bar drifting down, a faint
 * green phosphor tint, a slow tear line and a subtle flicker. Pure overlay draw
 * (no content sampling), so it runs anywhere and costs one rect pass per frame.
 */
@Composable
fun Modifier.crt(tint: Color, enabled: Boolean): Modifier {
    if (!enabled) return this
    val density = LocalDensity.current.density
    val lines = remember(density) { scanlineBrush(density) }
    // One clock for the three effects. Forty-two seconds is a whole number of
    // rolls (4.2 s), tears (7 s) and flickers (1 s), so each phase is a
    // multiple of it and the loop is seamless. Read in draw, never in
    // composition: the root of the app does not recompose for a CRT.
    val clock = rememberInfiniteTransition(label = "crt").animateFloat(0f, 1f, infiniteRepeatable(tween(42_000, easing = LinearEasing)), label = "clock")
    return drawWithContent {
        drawContent()
        val t = clock.value
        val roll = (t * 10f) % 1f
        val tear = (t * 6f) % 1f
        val flick = (t * 42f) % 1f
        // Phosphor glow: a whisper of the theme colour over everything.
        drawRect(tint, alpha = 0.05f)
        // Scanlines, stronger than the static ones.
        drawRect(brush = lines, alpha = 0.12f)
        // Roll bar: a soft bright band sweeping top→bottom.
        val bandH = size.height * 0.22f
        val y = roll * (size.height + bandH) - bandH
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.045f), 1f to Color.Transparent,
                startY = y, endY = y + bandH,
            ),
            topLeft = Offset(0f, y), size = androidx.compose.ui.geometry.Size(size.width, bandH),
        )
        // Tear line: a thin bright glitch line that drifts and fades in/out.
        val ty = tear * size.height
        val ta = (kotlin.math.sin(tear * Math.PI * 6).toFloat()).coerceAtLeast(0f) * 0.18f
        if (ta > 0.01f) drawRect(tint.copy(alpha = ta), topLeft = Offset(0f, ty), size = androidx.compose.ui.geometry.Size(size.width, density * 1.5f))
        // Flicker: a barely-there brightness jitter.
        val f = (kotlin.math.sin(flick * Math.PI * 2).toFloat()) * 0.012f
        if (f > 0f) drawRect(Color.White, alpha = f) else if (f < 0f) drawRect(Color.Black, alpha = -f)
    }
}

/** Everything a screen's root needs on top of its background: grain, scanlines/CRT, flash. */
@Composable
fun Modifier.haloSurface(): Modifier {
    val p = Halo.palette
    var m = this.grain(p.grainAlpha)
    // Scanline themes (Phosphor) get the full CRT when the user leaves it on; otherwise plain scanlines.
    m = if (p.scanlines && Settings.crt.value) m.crt(Halo.mint, true)
        else if (p.scanlines) m.scanlines()
        else m
    return m.themeFlash()
}

/**
 * Wraps content in the user's chosen global text scale (Settings.textScale) on top
 * of the system font scale, so every sp in the app grows or shrinks together.
 */
@androidx.compose.runtime.Composable
internal fun ScaledText(content: @androidx.compose.runtime.Composable () -> Unit) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    val scale by Settings.textScale
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale * scale),
        content = content,
    )
}
