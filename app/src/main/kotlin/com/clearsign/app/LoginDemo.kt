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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
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
internal fun GateDemo(modifier: Modifier = Modifier, opening: Boolean = false) {
    val loop = rememberInfiniteTransition(label = "orbit")
    // The door opening: the phone's light goes to full and a ring leaves it for
    // the edge of the screen, once, while the wallet fades in underneath. It is
    // the one moment the scene answers the person instead of playing to itself.
    val open = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(opening) { if (opening) open.animateTo(1f, tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(6200, easing = LinearEasing)), label = "t")
    val slow by loop.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "slow")

    // Fixed once: a starfield that reshuffled every frame would be snow. Three
    // depths, because a sky with one depth is wallpaper: the far ones are many,
    // tiny and slow; the near ones are few, bright, and drift a little faster
    // across them. Each has its own phase and pace, so no two twinkle together.
    val stars = remember {
        val rnd = java.util.Random(7)
        List(70) { i ->
            val depth = when { i < 44 -> 0; i < 62 -> 1; else -> 2 }
            Star(
                rnd.nextFloat(), rnd.nextFloat() * 0.78f,
                r = when (depth) { 0 -> 0.5f + rnd.nextFloat() * 0.7f; 1 -> 1.0f + rnd.nextFloat() * 0.9f; else -> 1.8f + rnd.nextFloat() * 1.2f },
                phase = rnd.nextFloat() * 6.28f, depth = depth, pace = 0.6f + rnd.nextFloat() * 1.6f,
            )
        }
    }

    val mint = Halo.mint
    val cyan = Halo.cyan
    val bad = Halo.red
    val faint = Halo.muted

    // The height is the caller's. On the door it is the whole middle of the
    // screen, which used to be a 190dp strip with half a page of dark under it.
    Canvas(modifier.fillMaxWidth()) {
        val w = size.width
        val h = size.height
        val unit = min(w, h)

        // ---- the dark, and what is in it -----------------------------------
        // A breath of colour behind everything: two soft clouds, cyan and
        // violet, drifting against each other so the black is not flat.
        run {
            val c1 = Offset(w * (0.30f + 0.06f * sin(slow * 0.5f)), h * (0.22f + 0.03f * cos(slow * 0.4f)))
            val c2 = Offset(w * (0.74f + 0.05f * cos(slow * 0.45f)), h * (0.40f + 0.04f * sin(slow * 0.6f)))
            drawCircle(Brush.radialGradient(listOf(cyan.copy(alpha = 0.07f), Color.Transparent), center = c1, radius = w * 0.55f), w * 0.55f, c1)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF8B7CF6).copy(alpha = 0.06f), Color.Transparent), center = c2, radius = w * 0.5f), w * 0.5f, c2)
        }
        stars.forEach { s ->
            val tw = 0.5f + 0.5f * sin(slow * 1.7f * s.pace + s.phase)
            val twinkle = when (s.depth) { 0 -> 0.25f + 0.35f * tw; 1 -> 0.4f + 0.5f * tw; else -> 0.55f + 0.45f * tw }
            // Parallax: the near layer moves three times as much as the far one.
            val par = (s.depth + 1) * unit * 0.004f
            val p = Offset(s.x * w + sin(slow * 0.35f + s.phase) * par, s.y * h + cos(slow * 0.25f + s.phase) * par * 0.4f)
            val rr = s.r * unit / 1000f * 2.2f
            if (s.depth == 2) {
                // The bright few breathe: a soft halo that swells on the peak of
                // the twinkle. No spikes; a cross of light reads as a symbol, and
                // a symbol in a sky is a thing the eye stops on.
                val swell = ((tw - 0.6f) / 0.4f).coerceIn(0f, 1f)
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f + 0.30f * swell), Color.Transparent), center = p, radius = rr * (3f + 3f * swell)),
                    rr * (3f + 3f * swell), p,
                )
            }
            drawCircle(Color.White.copy(alpha = 0.9f * twinkle), rr, p)
        }

        // ---- one falling star, early in the loop, never in the silence -------
        // Between the first arrival and the second, when nothing else moves: a
        // streak across the top that says the dark is deep. It stays out of the
        // last beat on purpose; the refusal owns that silence.
        run {
            val local = (t - 0.14f) / 0.07f
            if (local > 0f && local < 1f) {
                val p = easeOut(local)
                val from = Offset(w * 0.12f, h * 0.06f)
                val to = Offset(w * 0.58f, h * 0.20f)
                val head = Offset(from.x + (to.x - from.x) * p, from.y + (to.y - from.y) * p)
                val fade = 1f - local
                for (k in 6 downTo 0) {
                    val u = (p - k * 0.035f).coerceAtLeast(0f)
                    val q = Offset(from.x + (to.x - from.x) * u, from.y + (to.y - from.y) * u)
                    val a = 1f - k / 7f
                    drawCircle(Color.White.copy(alpha = 0.55f * a * a * fade), unit * 0.0035f * (0.4f + a), q)
                }
                drawCircle(Color.White.copy(alpha = 0.9f * fade), unit * 0.005f, head)
            }
        }

        // ---- the planet ----------------------------------------------------
        // Only its limb crosses the frame. A circle this large reads as a world;
        // the same circle small enough to see whole reads as a ball.
        val planetR = w * 1.35f
        val planet = Offset(w / 2f, h * 0.92f + planetR)
        val limbY = planet.y - planetR

        // The atmosphere is one soft radial glow just outside the horizon, not
        // stroked arcs: arcs scaled with the scene and turned into three thick
        // bands when the scene got tall. A gradient over the same radius fades
        // out on its own, whatever the size.
        val glowOut = w * 0.09f
        drawCircle(
            Brush.radialGradient(
                colorStops = arrayOf(
                    (planetR - w * 0.01f) / (planetR + glowOut) to Color.Transparent,
                    planetR / (planetR + glowOut) to cyan.copy(alpha = 0.55f),
                    (planetR + glowOut * 0.35f) / (planetR + glowOut) to cyan.copy(alpha = 0.12f),
                    1f to Color.Transparent,
                ),
                center = planet, radius = planetR + glowOut,
            ),
            planetR + glowOut, planet,
        )
        clipRect(top = limbY) {
            drawCircle(Halo.ground, planetR, planet)
            // The lit rim, thin: it fades out well before the frame's bottom edge,
            // so the body meets the edge as plain dark and not as a cut band.
            drawCircle(
                brush = Brush.verticalGradient(
                    listOf(cyan.copy(alpha = 0.20f), cyan.copy(alpha = 0.04f), Color.Transparent),
                    startY = limbY, endY = limbY + w * 0.16f,
                ),
                radius = planetR, center = planet,
            )
            drawCircle(cyan.copy(alpha = 0.6f), planetR, planet, style = Stroke(1.dp.toPx()))
            // Cities, breathing out of phase. Six points of life, not a grid.
            for (k in 0 until 7) {
                val a = -1.2f + k * 0.4f
                val lit = 0.25f + 0.75f * (0.5f + 0.5f * sin(slow * 2.1f + k * 1.7f))
                val p = Offset(planet.x + planetR * sin(a) * 0.62f, limbY + w * (0.015f + 0.02f * ((k % 3) + 1)))
                drawCircle(cyan.copy(alpha = 0.55f * lit), w * 0.003f, p)
                drawCircle(cyan.copy(alpha = 0.12f * lit), w * 0.010f, p)
            }
        }

        // ---- the phone, holding station ------------------------------------
        // The phone sits in the upper part of the scene, whatever the scene's
        // height. On the door the fingerprint sheet rises over the bottom half
        // of the screen, and a phone at the vertical middle of a tall canvas
        // was exactly the thing it covered. Sized from the width, so a taller
        // screen gives the planet more sky, not the phone a longer fall.
        val phoneH = min(h * 0.34f, w * 0.30f)
        val phoneW = phoneH * 0.47f
        val phone = Offset(w / 2f, min(h * 0.46f, w * 0.42f) + sin(slow) * unit * 0.008f)   // a slow float, never still
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

        // The phone is not there until something comes. Between arrivals it is
        // a ghost of an outline in the dark; as a streak closes in it takes
        // shape, and by the time the streak reaches the glass it is solid. Then
        // it fades again. So the eye learns the scene's one rule by watching it:
        // the thing that decides appears when there is something to decide.
        val o = open.value
        var wake = o
        lanes.forEach { lane ->
            val local = (t - lane.start) / 0.30f
            if (local <= 0f || local > 1.35f) return@forEach
            val rise = ((local - 0.25f) / 0.45f).coerceIn(0f, 1f)
            val fall = 1f - ((local - 1.0f) / 0.35f).coerceIn(0f, 1f)
            wake = max(wake, rise * rise * (3f - 2f * rise) * fall)
        }
        val presence = 0.10f + 0.90f * wake
        drawIntoCanvas { c ->
            c.saveLayer(
                androidx.compose.ui.geometry.Rect(phone.x - phoneW, phone.y - phoneH, phone.x + phoneW, phone.y + phoneH),
                androidx.compose.ui.graphics.Paint().apply { alpha = presence },
            )
        }
        drawPhone(
            phone.x - phoneW / 2f, phone.y - phoneH / 2f, phoneW, phoneH,
            back = false, body = Halo.cardSoft, edge = Halo.stroke, ink = Halo.ink, glass = Halo.ground,
        )
        // The glass lights from the bottom as it wakes, and a thin line of light
        // near the foot says "on". No dot, no ring in the middle: a symbol on the
        // screen was a thing to read, and there is nothing to read here.
        val glassL = phone.x - phoneW * 0.40f
        val glassT = phone.y - phoneH * 0.42f
        val glassW = phoneW * 0.80f
        val glassH = phoneH * 0.84f
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, mint.copy(alpha = 0.22f + 0.35f * o)), startY = glassT, endY = glassT + glassH),
            Offset(glassL, glassT), androidx.compose.ui.geometry.Size(glassW, glassH),
        )
        drawLine(
            mint.copy(alpha = 0.85f), Offset(phone.x - phoneW * 0.18f, glassT + glassH - phoneH * 0.06f),
            Offset(phone.x + phoneW * 0.18f, glassT + glassH - phoneH * 0.06f), strokeWidth = 2f * density, cap = StrokeCap.Round,
        )
        drawIntoCanvas { it.restore() }
        // The station light, only while it is awake: a thin ring that says watching.
        drawCircle(mint.copy(alpha = 0.16f * wake), shieldR * 1.15f, phone, style = Stroke(1.dp.toPx()))
        if (o > 0f) {
            // Opening: two rings leaving the phone for the edge, thinning as they go.
            for (k in 0 until 2) {
                val q = ((o - k * 0.18f) / 0.82f).coerceIn(0f, 1f)
                if (q <= 0f) continue
                drawCircle(mint.copy(alpha = 0.5f * (1f - q)), shieldR + (w * 0.9f) * q, phone, style = Stroke((2.2f * (1f - q) + 0.4f).dp.toPx()))
            }
        }
    }
}

private class Star(val x: Float, val y: Float, val r: Float, val phase: Float, val depth: Int = 0, val pace: Float = 1f)

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
