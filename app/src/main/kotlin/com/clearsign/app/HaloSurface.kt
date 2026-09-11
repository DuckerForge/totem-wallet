package com.clearsign.app

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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

/** Everything a screen's root needs on top of its background: grain, scanlines, flash. */
@Composable
fun Modifier.haloSurface(): Modifier {
    val p = Halo.palette
    var m = this.grain(p.grainAlpha)
    if (p.scanlines) m = m.scanlines()
    return m.themeFlash()
}
