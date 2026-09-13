package com.clearsign.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

/** Seeker, verified on chain. The second coin, so the flight is not all one token. */
private const val SKR_MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"

/**
 * What a gift link does, in one picture: money leaves this phone and lands on
 * somebody else's, with nothing in between but a message.
 *
 * The coins are the real token marks rather than drawn circles — the app already
 * has them, and a real SOL mark says "money" faster than any glyph we could
 * invent. The phones are the same Seeker outline the tap screen draws, lit from
 * the giving side so the direction is readable before the first coin moves.
 */
@Composable
internal fun GiftAnimation(modifier: Modifier = Modifier, height: Dp = 156.dp) {
    val mints = remember { listOf(Jupiter.SOL_MINT, SKR_MINT) }

    // TokenSymbols keeps its logos in a plain map, so a logo that arrives later
    // never redraws anything. Hold them in state instead, or SKR stays two letters.
    var logos by remember { mutableStateOf(mints.associateWith { TokenSymbols.image(it) }) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { JupiterTokens.byMints(mints) } }
        logos = mints.associateWith { TokenSymbols.image(it) }
    }

    val t = rememberInfiniteTransition(label = "gift")
    val flight by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart),
        label = "flight",
    )

    val accent = Halo.mint
    val edge = Halo.stroke
    val ink = Halo.ink
    val glass = Halo.ground
    val shellTop = Halo.cardSoft
    val shellBottom = Halo.card

    BoxWithConstraints(modifier.fillMaxWidth().height(height)) {
        val w = maxWidth
        val h = maxHeight
        val phoneH = minOf(h * 0.76f, w / 2.8f)
        val phoneW = phoneH * (69.56f / 150.86f)
        val margin = 14.dp
        val leftX = margin
        val rightX = w - margin - phoneW
        val topY = (h - phoneH) / 2f
        val coin = 26.dp

        Canvas(Modifier.fillMaxWidth().height(h)) {
            val pw = phoneW.toPx()
            val ph = phoneH.toPx()
            val ty = topY.toPx()
            val cy = ty + ph / 2f

            // The giving side is lit. One glow, on one phone: the direction of the
            // whole picture has to be legible before anything moves.
            drawCircle(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0f)),
                    center = Offset(leftX.toPx() + pw / 2f, cy),
                    radius = ph * 0.75f,
                ),
                radius = ph * 0.75f,
                center = Offset(leftX.toPx() + pw / 2f, cy),
            )

            // Yours, back turned, brighter than the ground so it reads as an object.
            drawPhone(leftX.toPx(), ty, pw, ph, back = true, body = shellTop, edge = accent.copy(alpha = 0.45f), ink = ink, glass = glass)
            // Theirs, screen towards you: a gift arrives as something you read.
            drawPhone(rightX.toPx(), ty, pw, ph, back = false, body = shellBottom, edge = edge, ink = ink, glass = glass)
        }

        // Three coins on one arc, each a third of a cycle behind the one in front.
        val from = leftX + phoneW * 0.55f
        val to = rightX + phoneW * 0.15f
        for (i in 0..2) {
            val phase = (flight + i / 3f) % 1f
            val x = from + (to - from) * phase
            val lift = (h * 0.22f) * sin(PI.toFloat() * phase)
            val y = topY + phoneH / 2f - coin / 2f - lift
            // Fade in as it leaves and out as it lands, so nothing pops in mid-air.
            val fade = (sin(PI.toFloat() * phase) * 1.7f).coerceIn(0f, 1f)
            val mint = mints[i % mints.size]
            Box(
                Modifier.offset(x = x, y = y).size(coin).alpha(fade).scale(0.7f + 0.3f * fade),
            ) {
                TokenLogo(mint, if (mint == Jupiter.SOL_MINT) "SOL" else "SKR", logos[mint], coin)
            }
        }
    }
}
