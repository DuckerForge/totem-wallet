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
 * The mark, made of two phones. First a line of light writes itself on the dark, violet at
 * the bottom to cyan at the top; then two phones light up under it, already posed, and the
 * line turns out to be the fold between them, the V of the mark; then the phones leave into
 * the light and the mark stays. Stroke first, because with the phones first it read as a V
 * of phones with a line added. Not decoration: the V is two phones touching, the thing this
 * app does and others do not. It runs on the one screen where there is nothing to do but
 * wait for a fingerprint. Rules: no stars, nothing falling, one motion at a time, a breath
 * of silence at the end so the V stays in the eye.
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

        // The phones' V is the mark's V, taken by laying the two phone silhouettes over the icon
        // until they fell into it: vertex low at 0.82 of the side, arms 38 degrees off vertical.
        // Reading it off a crop of lines gave them much narrower, because the bottom of the image is something else.
        val vertex = Offset(cx, cy + d * 0.320f)
        val armX = d * 0.480f
        val armY = d * 0.615f
        val leftTip = Offset(cx - armX, vertex.y - armY)
        val rightTip = Offset(cx + armX, vertex.y - armY)
        val armLen = hypot(armX, armY)

        // --- the timing, which is the story -----------------------------------------------
        // Stroke first, then what it is made of. A line of light draws itself on the dark and for a
        // moment is the only thing on screen: the mark writing itself. Lit and still, the two Seekers
        // light up under it, already in place, and the line turns out to be the fold between two
        // phones touching. Then the phones leave into the light and the mark stays.
        val spark = ease(seg(t, 0.05f, 0.30f))
        // A still beat with the stroke lit and nothing under it. Without it the phones
        // would enter while it is still being drawn, and neither the line writing
        // itself nor their arrival would show.
        val arrive = ease(seg(t, 0.38f, 0.55f))
        // And another beat at the full V, before the dissolve. `become` used to start
        // too early and the stroke began sliding while still being drawn, so it came
        // off the phone mid-run.
        val become = ease(seg(t, 0.68f, 0.84f))

        // Opening the door does not skip the story, it lets it finish and then pulls everything
        // back. `opening` used to push the scene straight to the mark, and the flag stayed up while
        // the door tried the unlock, so after a failed print the phones were never seen again and
        // the door showed a frozen logo. Now the cycle runs on its own and the opening fades it out.
        val leaving = 1f - op * 0.85f
        // The phones leave fast once started: with a linear fade the body vanishes
        // but the camera islands stay legible, two blots floating on the mark.
        val gone = (1f - become).let { it * it * it }
        // They light up, they do not arrive: no movement, revealed under a light that
        // was already there.
        val phoneAlpha = arrive * gone * leaving
        // No damping at the tail: it hid the cut when the cycle restarted, and the
        // cycle no longer restarts. Left in, the mark stayed at forty-five percent forever.
        val markAlpha = become * leaving

        // --- il respiro dietro ---------------------------------------------
        drawCircle(
            Brush.radialGradient(
                listOf(NEON_MID.copy(alpha = 0.05f * (0.3f + 0.7f * max(spark, markAlpha))), Color.Transparent),
                center = Offset(cx, cy), radius = d * 0.95f,
            ),
            radius = d * 0.95f, center = Offset(cx, cy),
        )

        // --- the two phones, while they last --------------------------------
        val phoneLen = armLen * 0.98f
        val phoneW = phoneLen * (69.56f / 150.86f)   // il Seeker vero: 150,86 x 69,56 mm
        // In the light they do not vanish in place: they draw a breath closer, as if
        // absorbed. The only movement left.
        val pull = become * d * 0.06f
        // Where a phone is now. Used twice, by the phones and by the stroke running on
        // them, and as long as it is one computation they cannot come apart.
        fun seat(tip: Offset, side: Float): Offset {
            val mid = Offset((tip.x + vertex.x) / 2f, (tip.y + vertex.y) / 2f)
            return Offset(
                mid.x - side * pull,
                mid.y + pull * 0.4f,
            )
        }
        if (phoneAlpha > 0.01f) {
            listOf(leftTip to -1f, rightTip to 1f).forEach { (tip, side) ->
                val ang = Math.toDegrees(atan2((tip.x - vertex.x).toDouble(), (vertex.y - tip.y).toDouble())).toFloat()
                // One facing, one from the back. Two identical backs said the same thing twice and the left
                // one had to be mirrored, a patch. This way both sides of the phone show, and the stroke
                // runs on the back, where it sits in the mark. They arrive already warm: the light above is on.
                phone(seat(tip, side), phoneLen, phoneW, ang, phoneAlpha, arrive * (0.4f + spark), back = side > 0f)
            }
        }

        // --- the line of light, which comes first ------------------------------------------
        // Drawn on the dark and kept lit until the phones are gone; the mark carries its own stroke
        // painted in, so here it vanishes. It runs on the glass, not beside it: same center and length
        // as the right phone, inset by a corner radius so the round tip stops inside the edge. Drawn
        // where the phone will be, so when it arrives it lands on it. Before, it ran vertex to tip,
        // overshot a tenth each side, and stayed put when the phones were pulled in: two objects, not one.
        if (spark > 0f && become < 1f) {
            val ux = (rightTip.x - vertex.x) / armLen
            val uy = (rightTip.y - vertex.y) / armLen
            // Not in the middle of the phone: on its left edge, the one facing the
            // vertex. In the mark the stroke is the fold between the two panels, not a
            // line across one.
            val edge = Offset(uy, -ux)
            val c = seat(rightTip, 1f) + edge * (phoneW * 0.46f)
            val half = phoneLen / 2f - phoneW * 0.15f
            // In the mark the stroke is not the arm's axis: it starts below the vertex, cuts the V and
            // leaves at the right tip, steeper. Measured in the image. As the phones dissolve the stroke
            // slides onto it, so the mark does not arrive, it settles on something already in place.
            val a = mix(Offset(c.x - ux * half, c.y - uy * half), Offset(cx - d * 0.310f, cy + d * 0.280f), become)
            val b = mix(Offset(c.x + ux * half, c.y + uy * half), Offset(cx + d * 0.360f, cy - d * 0.330f), become)
            neon(a, b, spark, (1f - become) * (0.4f + 0.6f * spark), d, (1f - become * 0.8f) * leaving)
        }

        // --- and the mark arrives --------------------------------------------
        if (markAlpha > 0.01f) {
            // It enters a breath larger and settles: arriving without moving is not
            // arriving.
            val k = d * (1.06f - 0.06f * become)
            drawImage(
                image = mark,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(mark.width, mark.height),
                dstOffset = IntOffset((cx - k / 2f).toInt(), (cy - k / 2f).toInt()),
                dstSize = IntSize(k.toInt(), k.toInt()),
                alpha = markAlpha,
            )
        }
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
