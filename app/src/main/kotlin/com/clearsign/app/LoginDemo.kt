package com.clearsign.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * What Apex is, shown instead of said.
 *
 * Orbit. The curve of a planet across the bottom with its atmosphere lit from
 * behind, one phone holding station above it, and requests arriving out of the
 * dark as streaks of light that slow as they come. Two are ordinary and are
 * taken in. The third turns red on approach and breaks against a ring of light
 * that leaves the phone to meet it.
 *
 * That is the product in six seconds: everything out there can only arrive and
 * ask, the phone is the thing that decides, and what lies does not get in.
 *
 * It replaced a version with three straight spokes into a wireframe globe, which
 * read as a spider rather than a scene. The rules that keep it a scene: no
 * straight lines, no grid, no text, nothing moving at constant speed, and a beat
 * of silence at the end of each loop so the refusal is a moment instead of a
 * stream.
 *
 * It plays on the door, where there is nothing to do but wait for a fingerprint,
 * and it is the only animation in the app that runs without being asked for.
 */
@Composable
internal fun GateDemo(modifier: Modifier = Modifier) {
    val loop = rememberInfiniteTransition(label = "orbit")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(6200, easing = LinearEasing)), label = "t")
    val slow by loop.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "slow")

    // Fixed once: a starfield that reshuffled every frame would be snow.
    val stars = remember {
        val rnd = java.util.Random(7)
        List(26) { Star(rnd.nextFloat(), rnd.nextFloat() * 0.62f, 0.6f + rnd.nextFloat() * 1.5f, rnd.nextFloat() * 6.28f) }
    }

    val mint = Halo.mint
    val cyan = Halo.cyan
    val bad = Halo.red
    val faint = Halo.muted

    Canvas(modifier.fillMaxWidth().height(190.dp)) {
        val w = size.width
        val h = size.height
        val unit = min(w, h)

        // ---- the dark, and what is in it -----------------------------------
        stars.forEach { s ->
            val twinkle = 0.35f + 0.4f * (0.5f + 0.5f * sin(slow * 1.7f + s.phase))
            val drift = sin(slow * 0.35f + s.phase) * unit * 0.004f
            drawCircle(Color.White.copy(alpha = 0.55f * twinkle), s.r, Offset(s.x * w + drift, s.y * h))
        }

        // ---- the planet ----------------------------------------------------
        // Only its limb crosses the frame. A circle this large reads as a world;
        // the same circle small enough to see whole reads as a ball.
        val planetR = w * 1.35f
        val planet = Offset(w / 2f, h * 0.92f + planetR)
        val limbY = planet.y - planetR

        // The atmosphere first, so the body draws over its inner edge and the glow
        // survives only outside the horizon, which is where it is in a photograph.
        for (k in 3 downTo 1) {
            drawCircle(cyan.copy(alpha = 0.05f * k), planetR + unit * 0.012f * k * k, planet, style = Stroke(unit * 0.02f * k))
        }
        clipRect(top = limbY) {
            drawCircle(Halo.ground, planetR, planet)
            drawCircle(
                brush = Brush.verticalGradient(
                    listOf(cyan.copy(alpha = 0.16f), Color.Transparent),
                    startY = limbY, endY = limbY + h * 0.45f,
                ),
                radius = planetR, center = planet,
            )
            drawCircle(cyan.copy(alpha = 0.45f), planetR, planet, style = Stroke(1.2.dp.toPx()))
            // Cities, breathing out of phase. Six points of life, not a grid.
            for (k in 0 until 6) {
                val a = -1.15f + k * 0.46f
                val lit = 0.25f + 0.75f * (0.5f + 0.5f * sin(slow * 2.1f + k * 1.7f))
                val p = Offset(planet.x + planetR * sin(a) * 0.62f, limbY + h * (0.04f + 0.05f * ((k % 3) + 1)))
                drawCircle(cyan.copy(alpha = 0.5f * lit), unit * 0.006f, p)
                drawCircle(cyan.copy(alpha = 0.14f * lit), unit * 0.018f, p)
            }
        }

        // ---- the phone, holding station ------------------------------------
        val phoneH = h * 0.34f
        val phoneW = phoneH * 0.47f
        val phone = Offset(w / 2f, h * 0.46f + sin(slow) * unit * 0.008f)   // a slow float, never still
        val shieldR = phoneW * 0.95f

        // ---- three arrivals -------------------------------------------------
        // Two are ordinary, the third lies. Always the third, always from the same
        // side: the eye learns where to look before it knows why.
        val lanes = listOf(
            Lane(Offset(-w * 0.25f, h * 0.16f), Offset(w * 0.22f, h * 0.02f), 0.02f, mint, true),
            Lane(Offset(w * 1.25f, h * 0.30f), Offset(w * 0.80f, h * 0.04f), 0.28f, cyan, true),
            Lane(Offset(w * 1.28f, -h * 0.10f), Offset(w * 0.86f, h * 0.30f), 0.56f, bad, false),
        )

        lanes.forEach { lane ->
            val local = (t - lane.start) / 0.30f
            if (local <= 0f || local > 1.35f) return@forEach
            val p = easeOut(clamp(local))
            // The liar never reaches the glass: it stops where the shield is.
            val stopAt = if (lane.honest) 1f else 0.72f
            val travel = min(p, stopAt)
            val fade = 1f - clamp((local - 1f) / 0.35f)

            fun at(u: Float): Offset {
                val m = 1 - u
                return Offset(
                    m * m * lane.from.x + 2 * m * u * lane.ctrl.x + u * u * phone.x,
                    m * m * lane.from.y + 2 * m * u * lane.ctrl.y + u * u * phone.y,
                )
            }

            // A tail of eight, thinning behind the head. Cheaper than a gradient
            // stroke and it bends with the curve for free.
            for (k in 7 downTo 0) {
                val u = (travel - k * 0.026f).coerceAtLeast(0f)
                if (u <= 0f) continue
                val a = (1f - k / 8f)
                drawCircle(lane.color.copy(alpha = 0.5f * a * a * fade), unit * (0.004f + 0.010f * a), at(u))
            }
            val head = at(travel)
            drawCircle(lane.color.copy(alpha = 0.18f * fade), unit * 0.030f, head)
            drawCircle(lane.color.copy(alpha = 0.95f * fade), unit * 0.011f, head)

            if (lane.honest) {
                // Taken in: a ring closing on the phone, quick and quiet.
                val land = clamp((p - 0.92f) / 0.08f)
                if (land > 0f) {
                    drawCircle(lane.color.copy(alpha = 0.45f * (1f - land) * fade), shieldR * (1.4f - 0.5f * land), phone, style = Stroke(1.6.dp.toPx()))
                }
            } else if (p >= stopAt) {
                // Refused: the shield goes out to meet it and the thing breaks up.
                val hit = clamp((p - stopAt) / (1f - stopAt))
                drawCircle(bad.copy(alpha = 0.55f * (1f - hit) * fade), shieldR * (1f + 1.6f * hit), phone, style = Stroke((2.4f * (1f - hit)).coerceAtLeast(0.4f).dp.toPx()))
                drawCircle(bad.copy(alpha = 0.10f * (1f - hit) * fade), shieldR * (1f + 1.6f * hit), phone)
                for (k in 0 until 3) {
                    val ang = -0.9f + k * 0.9f
                    val d = unit * 0.09f * hit
                    val frag = Offset(head.x + cos(ang) * d, head.y + sin(ang) * d + d * 0.5f)
                    drawCircle(bad.copy(alpha = 0.7f * (1f - hit) * fade), unit * 0.006f * (1f - hit), frag)
                }
            }
        }

        // The phone last: every streak passes behind it, nothing crosses the glass.
        drawPhone(
            phone.x - phoneW / 2f, phone.y - phoneH / 2f, phoneW, phoneH,
            back = false, body = Halo.cardSoft, edge = Halo.stroke, ink = Halo.ink, glass = Halo.ground,
        )
        val breath = 0.5f + 0.5f * sin(slow * 1.9f)
        drawCircle(mint.copy(alpha = 0.08f + 0.08f * breath), phoneW * 0.5f, phone)
        drawCircle(mint.copy(alpha = 0.6f + 0.3f * breath), phoneW * 0.12f, phone)
        // The station light: a thin ring that says the thing is awake and watching.
        drawCircle(mint.copy(alpha = 0.10f + 0.06f * breath), shieldR * 1.15f, phone, style = Stroke(1.dp.toPx()))
    }
}

private class Star(val x: Float, val y: Float, val r: Float, val phase: Float)

private class Lane(
    val from: Offset,
    val ctrl: Offset,
    val start: Float,
    val color: Color,
    val honest: Boolean,
)

/** Fast in, slow on arrival. Nothing in a scene moves at a constant speed. */
private fun easeOut(p: Float): Float {
    val m = 1f - p
    return 1f - m * m * m
}

private fun clamp(v: Float) = max(0f, min(1f, v))
