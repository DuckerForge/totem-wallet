package com.clearsign.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The mark: a Seeker with wings. A line of light writes itself up the dark, violet to cyan;
 * the Seeker lights up under it, and the line turns out to be its right edge; behind it the two
 * panels of the old V open as wings, the thunderbird; then the scene settles on the mark itself.
 * Same geometry as `scripts/make_totem_mark.py`, which paints the bitmap, so the door lands on
 * the launcher's own image. Rules: no stars, nothing falling, one motion at a time, a breath of
 * silence at the end.
 */
@Composable
internal fun GateDemo(modifier: Modifier = Modifier, opening: Boolean = false) {
    // Once only. A story that keeps restarting stops being a story and becomes a
    // moving background: the first time you watch, the third annoys. At the end
    // the mark stays, still.
    val run = remember { Animatable(0f) }
    LaunchedEffect(Unit) { run.animateTo(1f, tween(6000, easing = LinearEasing)) }
    val t = run.value

    // The real mark, not a drawing that resembles it: the same image as the
    // launcher, so if it ever changes it changes here too.
    val mark = ImageBitmap.imageResource(R.mipmap.brand_bird)

    val open = remember { Animatable(0f) }
    LaunchedEffect(opening) { if (opening) open.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }

    Canvas(modifier.fillMaxWidth()) {
        val w = size.width
        val h = size.height
        val op = open.value

        // The mark lives in a square of its own, not in the box it gets: that one is
        // `weight(1f)` and takes all the height left over.
        val d = min(w * 0.46f, h * 0.62f)
        val cx = w / 2f
        val cy = h * 0.17f

        // The phone and the wings, measured in the bitmap: the subject fills 0.92 of the
        // square, the phone is 0.58 of that and sits a little under the middle.
        val sq = d * 0.92f
        val phoneLen = sq * 0.58f
        val phoneW = phoneLen * (69.56f / 150.86f)   // il Seeker vero: 150,86 x 69,56 mm
        val pc = Offset(cx, cy + sq * 0.12f)

        // --- the timing, which is the story -----------------------------------------------
        val spark = ease(seg(t, 0.05f, 0.30f))
        // A still beat with the stroke lit and nothing under it, then the Seeker lights up,
        // already in place: no movement, revealed under a light that was already there.
        val arrive = ease(seg(t, 0.38f, 0.55f))
        // The wings open behind it, from nearly closed to their seat.
        val wings = ease(seg(t, 0.58f, 0.84f))
        // And the scene settles on the bitmap, which is the same picture.
        val become = ease(seg(t, 0.86f, 0.97f))

        // Opening the door does not skip the story, it lets it finish and then pulls everything
        // back. `opening` used to push the scene straight to the mark, and the flag stayed up while
        // the door tried the unlock, so after a failed print the phones were never seen again and
        // the door showed a frozen logo. Now the cycle runs on its own and the opening fades it out.
        val leaving = 1f - op * 0.85f

        // --- il respiro dietro ---------------------------------------------
        drawCircle(
            Brush.radialGradient(
                listOf(NEON_MID.copy(alpha = 0.05f * (0.3f + 0.7f * max(spark, wings))), Color.Transparent),
                center = Offset(cx, cy), radius = d * 0.95f,
            ),
            radius = d * 0.95f, center = Offset(cx, cy),
        )

        // --- the wings, behind ---------------------------------------------
        val wingW = phoneW * 0.86f
        val wingL = phoneLen * 0.62f
        listOf(-1f, 1f).forEach { side ->
            wing(
                pivot = Offset(pc.x + side * phoneW * 0.30f, pc.y - phoneLen * 0.10f),
                degrees = side * Math.toDegrees((0.95f - 0.35f * wings).toDouble()).toFloat(),
                lift = wingL * (0.55f + 0.15f * wings),
                wide = wingW, len = wingL,
                alpha = wings * leaving * (1f - become * 0.6f),
            )
        }

        // --- the Seeker, from the back --------------------------------------
        phone(pc, phoneLen, phoneW, 0f, arrive * leaving * (1f - become * 0.6f), arrive * (0.4f + spark), back = true)

        // --- the line of light, which comes first ------------------------------------------
        // On the phone's right edge, one corner radius in at both ends, written bottom to top:
        // drawn where the edge will be, so when the phone arrives the line is already its edge.
        if (spark > 0f && become < 1f) {
            val x = pc.x + phoneW / 2f
            val a = Offset(x, pc.y + phoneLen / 2f - phoneLen * 0.092f)
            val b = Offset(x, pc.y - phoneLen / 2f + phoneLen * 0.092f)
            neon(a, b, spark, (0.4f + 0.6f * spark), d, (1f - become * 0.8f) * leaving)
        }

        // --- and the mark settles --------------------------------------------
        val markAlpha = become * leaving
        if (markAlpha > 0.01f) {
            drawImage(
                image = mark,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(mark.width, mark.height),
                dstOffset = IntOffset((cx - d / 2f).toInt(), (cy - d / 2f).toInt()),
                dstSize = IntSize(d.toInt(), d.toInt()),
                alpha = markAlpha,
            )
        }
    }
}

/**
 * One wing: a panel of the old V, the same dark glass as the phone, turned outward around a
 * pivot near the phone's shoulder and lifted along its own axis so most of it rises above it.
 */
private fun DrawScope.wing(pivot: Offset, degrees: Float, lift: Float, wide: Float, len: Float, alpha: Float) {
    if (alpha <= 0.01f) return
    rotate(degrees, pivot) {
        val top = Offset(pivot.x - wide / 2f, pivot.y - lift)
        val brush = Brush.linearGradient(
            listOf(Color(0xFF262042), Color(0xFF101424), Color(0xFF090C16)),
            start = top, end = Offset(top.x + wide, top.y + len),
        )
        drawRoundRect(brush, top, Size(wide, len), CornerRadius(wide * 0.28f), alpha = alpha * 0.95f)
        drawRoundRect(Color(0xFF96AAFF).copy(alpha = 0.22f * alpha), top, Size(wide, len), CornerRadius(wide * 0.28f), style = Stroke(1.dp.toPx()))
    }
}

/** Violet at the bottom and cyan at the top: the two ends of the brand. */
private val NEON_LOW = Color(0xFF9524F3)
private val NEON_MID = Color(0xFF666CF4)
private val NEON_HIGH = Color(0xFF0BF5EC)

/**
 * The stroke that draws itself, color flowing along its length. In pieces, not one go, because
 * the color changes along the way and that is how only the arrived part shows. Three passes:
 * a wide faint halo, a medium one, and the real thread, which is the one read.
 */
private fun DrawScope.neon(a: Offset, b: Offset, reveal: Float, glow: Float, d: Float, fade: Float = 1f) {
    if (reveal <= 0f) return
    // As thick as in the logo, not a neon tube: measured on the mark, the one
    // thing the stroke is part of.
    val core = d * 0.017f
    val steps = 44
    for (i in 0 until steps) {
        val u0 = i / steps.toFloat()
        if (u0 >= reveal) break
        val u1 = min((i + 1) / steps.toFloat(), reveal)
        val p0 = Offset(a.x + (b.x - a.x) * u0, a.y + (b.y - a.y) * u0)
        val p1 = Offset(a.x + (b.x - a.x) * u1, a.y + (b.y - a.y) * u1)
        val u = (u0 + u1) / 2f
        val c = if (u < 0.5f) lerp(NEON_LOW, NEON_MID, u * 2f) else lerp(NEON_MID, NEON_HIGH, (u - 0.5f) * 2f)
        drawLine(c.copy(alpha = (0.09f + 0.13f * glow) * fade), p0, p1, core * 4.4f, StrokeCap.Round)
        drawLine(c.copy(alpha = (0.24f + 0.22f * glow) * fade), p0, p1, core * 2.1f, StrokeCap.Round)
        drawLine(c.copy(alpha = fade), p0, p1, core, StrokeCap.Round)
    }
    // The tip lit while traveling, nothing once arrived: a thread still glowing
    // at the top would say something is still happening.
    if (reveal < 1f) {
        val head = Offset(a.x + (b.x - a.x) * reveal, a.y + (b.y - a.y) * reveal)
        val c = if (reveal < 0.5f) lerp(NEON_LOW, NEON_MID, reveal * 2f) else lerp(NEON_MID, NEON_HIGH, (reveal - 0.5f) * 2f)
        drawCircle(c.copy(alpha = 0.22f * fade), core * 3.4f, head)
        drawCircle(c.copy(alpha = fade), core * 1.15f, head)
    }
}

/**
 * A Seeker. The phone is already drawn elsewhere, not by eye: `drawPhone` in TapAnimation
 * comes from the Seeker's factory drawing (three-lens island with the flash beside it, side
 * keys, 150.86 x 69.56 mm) and the tap between two phones uses it; a second drawing here
 * would diverge at the first fix. One from the back, because at this size a phone seen from
 * the front is a black rectangle that could be anyone's: the island says Seeker.
 */
private fun DrawScope.phone(
    center: Offset,
    len: Float,
    wide: Float,
    angle: Float,
    alpha: Float,
    glow: Float,
    back: Boolean,
) {
    if (alpha <= 0.01f) return
    // The body warms as the stroke passes: the one thing that ties the two
    // objects instead of leaving them side by side.
    val body = lerp(Color(0xFF24243A), NEON_MID, 0.18f * glow)
    rotate(angle, center) {
        drawPhone(
            x = center.x - wide / 2f,
            y = center.y - len / 2f,
            w = wide,
            h = len,
            back = back,
            body = body.copy(alpha = alpha),
            edge = Color(0xFFB2B2E4).copy(alpha = alpha),
            ink = Color(0xFF12121C).copy(alpha = alpha),
            glass = Color(0xFF0B0B14).copy(alpha = alpha),
        )
    }
}

/** A point halfway between two, needed only here. */
private fun mix(a: Offset, b: Offset, k: Float) = Offset(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k)

/** The slice of time between two instants of the cycle, zero to one. */
private fun seg(t: Float, from: Float, to: Float): Float =
    if (t <= from) 0f else if (t >= to) 1f else (t - from) / (to - from)

/** Niente si muove a velocita' costante: rallenta arrivando. */
private fun ease(p: Float): Float {
    val c = min(1f, max(0f, p))
    return 1f - (1f - c) * (1f - c) * (1f - c)
}
