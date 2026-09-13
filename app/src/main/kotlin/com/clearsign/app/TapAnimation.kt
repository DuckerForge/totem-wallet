package com.clearsign.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * The gesture, performed instead of described.
 *
 * A still picture cannot say "back to back" — you have to watch the screens turn
 * away. So the loop does the whole move: two phones face you, both turn over,
 * they come together, and only then do they talk. Both turn the same way and
 * stay identical, because drawing one with the camera left and one with it right
 * made them read as two different phones.
 */
@Composable
internal fun TapAnimation(active: Boolean, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 146.dp) {
    val t = rememberInfiniteTransition(label = "tap-art")
    val p by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val wave by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "wave",
    )

    val ink = Halo.ink
    val stroke = Halo.stroke
    val card = Halo.card
    val glass = Halo.ground
    val accent = if (active) Halo.cyan else Halo.muted

    Canvas(modifier.fillMaxWidth().height(height)) {
        val h = size.height
        // Fit whichever way is tighter: half a sheet is narrow, so the pair has to
        // shrink to the width rather than run off the side of its block.
        val phoneH = minOf(h * 0.78f, size.width / 1.52f)
        // The real outline of a Seeker: 150.86 x 69.56 mm.
        val phoneW = phoneH * (69.56f / 150.86f)
        val cy = h / 2f
        val mid = size.width / 2f

        // ── the four beats of the move ──────────────────────────────────────
        val hold = 0.16f           // facing you
        val turn = 0.26f           // turning over
        val close = 0.16f          // coming together
        val flip = ease(((p - hold) / turn).coerceIn(0f, 1f))
        val join = ease(((p - hold - turn) / close).coerceIn(0f, 1f))
        val talk = ((p - hold - turn - close) / (1f - hold - turn - close)).coerceIn(0f, 1f)

        // Half a turn. Past 90 degrees you are looking at the back.
        val angle = flip * PI.toFloat()
        val squash = abs(cos(angle)).coerceAtLeast(0.05f)
        val showingBack = flip > 0.5f

        // Apart while they turn, touching once they have.
        val apart = phoneW * 1.55f
        val near = phoneW * 0.62f
        val half = apart + (near - apart) * join

        drawPhone(mid - half - phoneW * squash / 2f, cy - phoneH / 2f, phoneW * squash, phoneH, showingBack, card, stroke, ink, glass)
        drawPhone(mid + half - phoneW * squash / 2f, cy - phoneH / 2f, phoneW * squash, phoneH, showingBack, card, stroke, ink, glass)

        // They only talk once the backs are together.
        if (talk > 0.02f) {
            val meet = Offset(mid, cy)
            for (i in 0..2) {
                val ph = (wave + i / 3f) % 1f
                val r = phoneW * 0.20f + ph * h * 0.22f
                val alpha = (1f - ph) * talk * (if (active) 0.95f else 0.4f)
                if (alpha <= 0.01f) continue
                val box = Rect(meet - Offset(r, r), meet + Offset(r, r))
                drawArc(accent.copy(alpha = alpha), -46f, 92f, false, box.topLeft, box.size, style = Stroke(1.7.dp.toPx()))
                drawArc(accent.copy(alpha = alpha), 134f, 92f, false, box.topLeft, box.size, style = Stroke(1.7.dp.toPx()))
            }
            drawCircle(accent.copy(alpha = talk * 0.9f), 2.6.dp.toPx(), meet)
        }
    }
}

private fun ease(t: Float): Float = t * t * (3f - 2f * t)

internal fun DrawScope.drawPhone(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    back: Boolean,
    body: Color,
    edge: Color,
    ink: Color,
    glass: Color,
) {
    // The corner radius belongs to the real body, so it must not squash with the turn.
    val r = CornerRadius((h * 0.092f).coerceAtMost(w / 2f), h * 0.092f)
    val shell = Path().apply { addRoundRect(RoundRect(Rect(Offset(x, y), Size(w, h)), r)) }
    drawPath(shell, body)
    drawPath(shell, edge, style = Stroke(1.6.dp.toPx()))
    if (w < h * 0.10f) return   // nearly edge-on: no face to draw

    if (back) {
        // Camera island: a tall pill in the top corner with three lenses, and the
        // flash beside it — straight off the Seeker's manufacturing drawing.
        val iw = w * 0.22f
        val ih = h * 0.29f
        val ix = x + w * 0.12f
        val iy = y + h * 0.05f
        if (iw > 1f) {
            val island = Path().apply { addRoundRect(RoundRect(Rect(Offset(ix, iy), Size(iw, ih)), CornerRadius(iw / 2f, iw / 2f))) }
            drawPath(island, edge.copy(alpha = 0.7f), style = Stroke(1.2.dp.toPx()))
            for (k in 0..2) {
                val c = Offset(ix + iw / 2f, iy + ih * (0.19f + 0.31f * k))
                drawCircle(ink.copy(alpha = 0.5f), iw * 0.30f, c)
                drawCircle(edge.copy(alpha = 0.8f), iw * 0.30f, c, style = Stroke(1.1.dp.toPx()))
            }
            drawCircle(edge.copy(alpha = 0.75f), iw * 0.14f, Offset(ix + iw * 1.45f, iy + ih * 0.26f))
        }
    } else {
        // The screen, which is almost the whole front: 147.38 of 150.86 mm.
        val inset = w * 0.06f
        val sy = y + h * 0.022f
        val sh = h * 0.956f
        if (w - inset * 2 > 0) {
            val screen = Path().apply {
                addRoundRect(RoundRect(Rect(Offset(x + inset, sy), Size(w - inset * 2, sh)), CornerRadius((h * 0.07f).coerceAtMost((w - inset * 2) / 2f), h * 0.07f)))
            }
            drawPath(screen, glass)
            drawPath(screen, edge.copy(alpha = 0.6f), style = Stroke(1.dp.toPx()))
            drawCircle(edge.copy(alpha = 0.8f), (w * 0.055f).coerceAtLeast(0.8f), Offset(x + w / 2f, sy + h * 0.055f))
        }
    }

    // Side buttons, from the right-view of the same drawing.
    drawLine(edge, Offset(x + w, y + h * 0.22f), Offset(x + w, y + h * 0.29f), 2.2.dp.toPx())
    drawLine(edge, Offset(x + w, y + h * 0.33f), Offset(x + w, y + h * 0.46f), 2.2.dp.toPx())
}
