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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * What Apex is for, shown instead of said.
 *
 * A payment leaves one phone for another, and on the way a slice peels off to
 * somewhere nobody mentioned. That is the thing this whole app exists to catch,
 * and it lands in two seconds of watching where a paragraph of text would have
 * been skimmed and forgotten.
 *
 * It plays on the door, where there is nothing else to do but wait for a
 * fingerprint, and it is the only animation in the app that runs without being
 * asked for. That is the exception the door earns: everywhere else, motion here
 * answers something a person did.
 */
@Composable
internal fun HiddenCutDemo(modifier: Modifier = Modifier) {
    // The coin that crosses is the real SOL logo, the same one the receipt sends
    // along its line. Null until it loads, and the plain disc stands in.
    val sol = rememberCoinBitmap(com.clearsign.core.NATIVE_SOL_MINT)
    val loop = rememberInfiniteTransition(label = "cut")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "t")

    val body = Halo.cardSoft
    val edge = Halo.stroke
    val ink = Halo.ink
    val glass = Halo.ground
    val good = Halo.mint
    val bad = Halo.red
    val faint = Halo.muted

    Canvas(modifier.fillMaxWidth().height(150.dp)) {
        val w = size.width
        val h = size.height
        val phoneH = h * 0.68f
        val phoneW = phoneH * 0.47f
        val cy = h * 0.38f

        val from = Offset(w * 0.14f, cy)
        val to = Offset(w * 0.86f, cy)

        // Fronts, not backs. Somebody paying is looking at a screen, and two
        // phones showing their camera islands read as two phones lying face down.
        drawPhone(from.x - phoneW / 2f, cy - phoneH / 2f, phoneW, phoneH, back = false, body = body, edge = edge, ink = ink, glass = glass)
        drawPhone(to.x - phoneW / 2f, cy - phoneH / 2f, phoneW, phoneH, back = false, body = body, edge = edge, ink = ink, glass = glass)

        // The honest road: a gentle arc between the two phones.
        // From edge to edge, so the line leaves one phone and reaches the other
        // instead of appearing out of the middle of both.
        val a0 = Offset(from.x + phoneW * 0.62f, cy)
        val a1 = Offset(to.x - phoneW * 0.62f, cy)
        val ctrl = Offset((a0.x + a1.x) / 2f, cy - h * 0.22f)
        val road = Path().apply { moveTo(a0.x, a0.y); quadraticBezierTo(ctrl.x, ctrl.y, a1.x, a1.y) }
        drawPath(road, faint.copy(alpha = 0.40f), style = Stroke(1.6.dp.toPx()))

        fun on(p: Float): Offset {
            val m = 1 - p
            return Offset(
                m * m * a0.x + 2 * m * p * ctrl.x + p * p * a1.x,
                m * m * a0.y + 2 * m * p * ctrl.y + p * p * a1.y,
            )
        }

        // Which way the money is going, said once at the end of each line.
        arrow(on(0.96f), on(1f), faint.copy(alpha = 0.75f), min(w, h) * 0.048f)

        // Where the slice leaves. Fixed, so the eye learns the spot across loops.
        val forkAt = 0.46f
        val fork = on(forkAt)
        val skim = Offset(w * 0.66f, h * 0.86f)
        val skimCtrl = Offset(fork.x + (skim.x - fork.x) * 0.2f, skim.y - h * 0.12f)
        val skimR = min(w, h) * 0.052f

        // Timeline of one loop: the coin crosses, the cut leaves halfway, both
        // settle, and a beat of nothing before it starts again. The pause matters:
        // without it the loop reads as a stream and you never see the moment.
        val move = clamp((t - 0.04f) / 0.56f)
        val cut = clamp((t - 0.30f) / 0.34f)
        val fade = 1f - clamp((t - 0.86f) / 0.14f)

        val coinR = min(w, h) * 0.030f

        if (cut > 0f) {
            fun onSkim(p: Float): Offset {
                val m = 1 - p
                return Offset(
                    m * m * fork.x + 2 * m * p * skimCtrl.x + p * p * skim.x,
                    m * m * fork.y + 2 * m * p * skimCtrl.y + p * p * skim.y,
                )
            }
            // Thin and quiet on purpose. A thick red arrow shouts, and the point
            // is that this is the part nobody points at.
            val skimPath = Path().apply {
                moveTo(fork.x, fork.y)
                quadraticBezierTo(skimCtrl.x, skimCtrl.y, skim.x, skim.y)
            }
            drawPath(
                skimPath,
                bad.copy(alpha = 0.55f * fade),
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
            )
            // Where it ends up: a wallet nobody named.
            val landed = clamp((cut - 0.75f) / 0.25f)
            if (landed > 0f) {
                drawCircle(bad.copy(alpha = 0.14f * fade * landed), skimR * 1.5f, skim)
                drawCircle(bad.copy(alpha = 0.9f * fade * landed), skimR, skim, style = Stroke(1.8.dp.toPx()))
            }
            coin(onSkim(cut), coinR * 0.8f, bad, fade)
        }

        if (move > 0f) {
            // The coin shrinks the instant it passes the fork: less arrives than
            // left, which is the whole story in one gesture.
            val took = if (move < forkAt) 1f else 0.72f
            val at = on(move)
            val r = coinR * took * 2.0f
            if (sol != null) {
                drawCircle(good.copy(alpha = 0.28f * fade), r * 1.15f, at)
                clipPath(Path().apply { addOval(Rect(at.x - r, at.y - r, at.x + r, at.y + r)) }) {
                    drawImage(
                        image = sol,
                        dstOffset = IntOffset((at.x - r).toInt(), (at.y - r).toInt()),
                        dstSize = IntSize((r * 2).toInt(), (r * 2).toInt()),
                        alpha = fade,
                    )
                }
                drawCircle(good.copy(alpha = 0.9f * fade), r, at, style = Stroke(1.4.dp.toPx()))
            } else {
                coin(at, coinR * took, good, fade)
            }
        }
    }
}

/** A small head at [tip], pointing the way the line was already going. */
private fun DrawScope.arrow(before: Offset, tip: Offset, color: Color, size: Float) {
    val dx = tip.x - before.x
    val dy = tip.y - before.y
    val len = kotlin.math.hypot(dx, dy).takeIf { it > 0.0001f } ?: return
    val ux = dx / len
    val uy = dy / len
    // Perpendicular, for the two barbs.
    val px = -uy
    val py = ux
    val back = Offset(tip.x - ux * size, tip.y - uy * size)
    val head = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(back.x + px * size * 0.5f, back.y + py * size * 0.5f)
        lineTo(back.x - px * size * 0.5f, back.y - py * size * 0.5f)
        close()
    }
    drawPath(head, color)
}

private fun DrawScope.coin(at: Offset, r: Float, color: Color, alpha: Float) {
    drawCircle(color.copy(alpha = 0.22f * alpha), r * 2.1f, at)
    drawCircle(color.copy(alpha = 0.95f * alpha), r, at)
    drawCircle(Halo.ground.copy(alpha = 0.8f * alpha), r * 0.34f, at)
}

private fun clamp(v: Float) = max(0f, min(1f, v))
