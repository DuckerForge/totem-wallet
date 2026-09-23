package com.clearsign.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * The house motion vocabulary. Every helper animates in `graphicsLayer` or a
 * draw lambda, so a running animation never recomposes the tree around it.
 */

/** Shrinks slightly while pressed — buttons feel physical. */
@Composable
fun Modifier.pressScale(pressed: Boolean, down: Float = 0.97f): Modifier {
    val s by animateFloatAsState(if (pressed) down else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "press")
    return graphicsLayer { scaleX = s; scaleY = s }
}

@Composable
fun Modifier.pressScale(src: InteractionSource, down: Float = 0.97f): Modifier {
    val pressed by src.collectIsPressedAsState()
    return pressScale(pressed, down)
}

/** Fade + 16dp rise, staggered 60 ms per [index]: content arrives, it doesn't pop. */
@Composable
fun Modifier.staggeredEntrance(index: Int, key: Any? = Unit): Modifier {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        if (a.value < 1f) {
            delay(60L * index)
            a.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        }
    }
    val dy = with(LocalDensity.current) { 16.dp.toPx() }
    return graphicsLayer { alpha = a.value; translationY = (1f - a.value) * dy }
}

/** A number that counts up to [target] in 700 ms (re-runs when the target changes). */
@Composable
fun rememberCountUp(target: Float, durationMs: Int = 700): Float {
    val v = remember { Animatable(0f) }
    LaunchedEffect(target) { v.animateTo(target, tween(durationMs, easing = FastOutSlowInEasing)) }
    return v.value
}

/** Terminal caret: a 2×[height] block blinking at 1 Hz. */
@Composable
fun BlinkCaret(color: Color, height: Dp = 14.dp, width: Dp = 7.dp) {
    val tr = rememberInfiniteTransition(label = "caret")
    val a by tr.animateFloat(
        1f, 1f,
        infiniteRepeatable(keyframes { durationMillis = 1000; 1f at 0; 1f at 480; 0f at 500; 0f at 980 }, RepeatMode.Restart),
        label = "a",
    )
    Box(Modifier.width(width).height(height).graphicsLayer { alpha = a }.background(color))
}

/** A slow breathing value in 0..1 as State — read it inside a draw lambda, never in composition. */
@Composable
fun rememberBreathState(periodMs: Int = 1400): androidx.compose.runtime.State<Float> {
    val tr = rememberInfiniteTransition(label = "breath")
    return tr.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Reverse), label = "v")
}

/** 0 → 1 reveal progress over [durationMs], restarted when [key] changes (for count-ups that must end exact). */
@Composable
fun rememberReveal(key: Any?, durationMs: Int = 700): Float {
    val v = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { v.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing)) }
    return v.value
}

/** Home cards fade in one after another: each `GlassCard` takes the next slot. */
val LocalEntrance = androidx.compose.runtime.compositionLocalOf<java.util.concurrent.atomic.AtomicInteger?> { null }

/**
 * One slow ring of light around a badge, once, then gone: a 270 degree arc with a conic
 * gradient, bright head, tail fading to nothing, one traveling point of light, not a spinner.
 * Two turns over two and a half seconds, then it fades and stops drawing: a permanent spinner
 * on a screen not loading anything is noise. The glow is two arcs, not a blur (a render node per
 * frame). [key] restarts it; pass something that changes once per app launch, or the greeting becomes a tic.
 */
@Composable
fun SweepHalo(
    color: Color,
    modifier: Modifier = Modifier,
    key: Any? = Unit,
    turns: Float = 2f,
    durationMs: Int = 2500,
    stroke: Dp = 1.6.dp,
) {
    val spin = remember(key) { Animatable(0f) }
    val fade = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        fade.snapTo(1f)
        spin.snapTo(0f)
        spin.animateTo(turns * 360f, tween(durationMs, easing = FastOutSlowInEasing))
        fade.animateTo(0f, tween(450, easing = LinearEasing))
    }
    if (fade.value <= 0.01f) return
    val px = with(LocalDensity.current) { stroke.toPx() }
    androidx.compose.foundation.Canvas(modifier) {
        val inset = px * 1.5f
        val rect = androidx.compose.ui.geometry.Rect(
            inset, inset, size.width - inset, size.height - inset,
        )
        // The tail is transparent and the head is not, all the way round, so the
        // brightness travels with the rotation instead of the whole ring pulsing.
        val brush = androidx.compose.ui.graphics.Brush.sweepGradient(
            0f to color.copy(alpha = 0f),
            0.55f to color.copy(alpha = 0.10f * fade.value),
            0.92f to color.copy(alpha = 0.85f * fade.value),
            1f to color.copy(alpha = 0f),
            center = rect.center,
        )
        rotate(spin.value, rect.center) {
            drawArc(
                brush = brush, startAngle = 0f, sweepAngle = 270f, useCenter = false,
                topLeft = rect.topLeft, size = rect.size,
                style = androidx.compose.ui.graphics.drawscope.Stroke(px * 2.6f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                alpha = 0.28f,
            )
            drawArc(
                brush = brush, startAngle = 0f, sweepAngle = 270f, useCenter = false,
                topLeft = rect.topLeft, size = rect.size,
                style = androidx.compose.ui.graphics.drawscope.Stroke(px, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
    }
}

/**
 * Something that changes once per app launch. A greeting keyed on a composable's lifetime
 * replays every time you come back to the tab, how a nice touch turns nervous.
 */
object FirstRun {
    val at: Long = System.currentTimeMillis()
}
