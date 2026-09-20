package com.clearsign.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/*
 * What the coin has been doing, under the thing that is about to buy it.
 *
 * Buying a name and a number with no idea of the shape behind them is the part
 * of a swap that feels like a coin toss, and the fix is small: a line, three
 * spans, and the change across the one you are looking at.
 *
 * Deliberately a line and not candles. At this size candles are decoration —
 * nobody reads a wick two pixels wide — and the question here is only "which way
 * has this been going, and how violently".
 *
 * The prices come from the busiest pool on GeckoTerminal: free, no key, and a
 * different vendor from the one quoting the swap, which is the point. Nothing
 * here blocks or delays the swap, and no history means no chart, never a flat
 * line — a flat line would read as a price that did not move.
 */
/** A price worth a line on the chart: an order, or an alert. [key] restarts the entrance when it changes. */
internal data class ChartTarget(val priceUsd: Double, val label: String, val tint: Color, val key: Any)

@Composable
internal fun PriceChart(mint: String, symbol: String, targets: List<ChartTarget> = emptyList()) {
    var span by remember(mint) { mutableStateOf(Gecko.Span.HOURS) }
    var pool by remember(mint) { mutableStateOf<String?>(null) }
    var series by remember(mint) { mutableStateOf<List<Double>>(emptyList()) }
    var loading by remember(mint) { mutableStateOf(true) }

    LaunchedEffect(mint, span) {
        loading = true
        withContext(Dispatchers.IO) {
            val p = pool ?: runCatching { Gecko.topPool(mint) }.getOrNull()
            pool = p
            series = if (p == null) emptyList() else runCatching { Gecko.closes(p, span) }.getOrElse { emptyList() }
        }
        loading = false
    }

    val first = series.firstOrNull()
    val last = series.lastOrNull()
    val pct = if (first != null && last != null && first > 0) (last - first) / first * 100 else null
    val up = (pct ?: 0.0) >= 0
    val tint = if (pct == null) Halo.muted else if (up) Halo.mint else Halo.red

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(symbol, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Halo.ink)
            Spacer(Modifier.width(8.dp))
            if (pct != null) {
                Text(
                    stringResource(R.string.chart_change, pctText(pct), stringResource(span.labelRes)),
                    fontFamily = Mono, fontSize = 11.5.sp, color = tint, style = Tabular,
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                Gecko.Span.small.forEach { s ->
                    val on = s == span
                    Box(
                        Modifier.padding(start = 5.dp).clip(rs(999))
                            .background(if (on) tint.copy(alpha = 0.16f) else Halo.cardSoft)
                            .clickable { span = s }
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    ) {
                        Text(
                            stringResource(s.labelRes), fontFamily = Mono, fontSize = 10.5.sp,
                            color = if (on) tint else Halo.muted, style = Tabular,
                        )
                    }
                }
            }
        }

        if (series.size >= 2) {
            Spark(series, tint, targets)
        } else if (!loading) {
            Text(
                stringResource(R.string.chart_none),
                fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted,
            )
        }
    }
}

/**
 * A moment on the line: somebody was in or out of this coin right here.
 *
 * [at] is a fraction of the chart's width, zero at the oldest candle and one at
 * the newest, so the mark lands at the time it happened. The price under it is
 * whatever the line is doing there. Nobody publishes the price a wallet
 * actually paid, so nothing here claims to know it.
 */
internal class SparkMark(val at: Float, val sell: Boolean)

/** The line, with the ground under it shaded so the direction reads at a glance. */
@Composable
internal fun Spark(
    values: List<Double>,
    tint: Color,
    targets: List<ChartTarget> = emptyList(),
    marks: List<SparkMark> = emptyList(),
) {
    // The scale stretches to fit the targets, so a line at +30% is on the
    // picture and not off the top of it. Capped at four times the range of the
    // prices themselves: a target at +300% would flatten the whole story.
    val range = (values.max() - values.min()).takeIf { it > 0 } ?: (values.max() * 0.02).coerceAtLeast(1e-12)
    val lo = minOf(values.min(), targets.minOfOrNull { it.priceUsd }?.coerceAtLeast(values.min() - 4 * range) ?: values.min())
    val hi = maxOf(values.max(), targets.maxOfOrNull { it.priceUsd }?.coerceAtMost(values.max() + 4 * range) ?: values.max())
    val span = (hi - lo).takeIf { it > 0 } ?: 1.0
    // Each target slides in from the top when it first appears, or when its key
    // changes: an order being placed is a line arriving, not a line that was
    // always there.
    val reveals = targets.map { rememberReveal(it.key, durationMs = 700) }
    val tm = androidx.compose.ui.text.rememberTextMeasurer()

    Canvas(Modifier.fillMaxWidth().height(if (targets.isEmpty()) 86.dp else 110.dp)) {
        val n = values.size
        val stepX = size.width / (n - 1).coerceAtLeast(1)
        fun y(v: Double) = (size.height - 6f) - ((v - lo) / span).toFloat() * (size.height - 14f)

        val line = Path().apply {
            moveTo(0f, y(values[0]))
            for (i in 1 until n) lineTo(stepX * i, y(values[i]))
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            area,
            Brush.verticalGradient(listOf(tint.copy(alpha = 0.22f), Color.Transparent)),
        )
        drawPath(line, tint, style = Stroke(width = 1.8f * density, cap = StrokeCap.Round))
        // Where it is now, so the eye lands on the end of the story.
        drawCircle(tint, 3.2f * density, Offset(size.width - 1f, y(values[n - 1])))
        drawCircle(tint.copy(alpha = 0.22f), 7f * density, Offset(size.width - 1f, y(values[n - 1])))

        targets.forEachIndexed { i, t ->
            val yEnd = y(t.priceUsd)
            val yNow = yEnd * reveals[i]
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f * density, 5f * density))
            drawLine(
                t.tint.copy(alpha = 0.35f + 0.55f * reveals[i]), Offset(0f, yNow), Offset(size.width, yNow),
                strokeWidth = 1.4f * density, pathEffect = dash,
            )
            val lay = tm.measure(
                t.label,
                androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 9.5.sp, color = t.tint, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            )
            // The label sits above the line, on the left, where nothing else lives.
            val ty = (yNow - lay.size.height - 2f * density).coerceAtLeast(0f)
            drawRoundRect(
                Halo.ground2.copy(alpha = 0.85f), Offset(2f * density, ty), androidx.compose.ui.geometry.Size(lay.size.width + 8f * density, lay.size.height.toFloat()),
                androidx.compose.ui.geometry.CornerRadius(4f * density),
            )
            drawText(lay, topLeft = Offset(6f * density, ty))
        }

        // Where somebody was in or out. A ring on the line rather than a dot,
        // so it reads as a moment on the price and not as a data point of its
        // own, with a faint stem down to the floor to say "at this time".
        marks.forEach { m ->
            val x = (size.width * m.at).coerceIn(0f, size.width)
            val idx = ((n - 1) * m.at).toInt().coerceIn(0, n - 1)
            val my = y(values[idx])
            val c = if (m.sell) Halo.red else Halo.mint
            drawLine(c.copy(alpha = 0.28f), Offset(x, my), Offset(x, size.height), strokeWidth = 1.2f * density)
            drawCircle(Halo.ground, 5.5f * density, Offset(x, my))
            drawCircle(c, 5.5f * density, Offset(x, my), style = Stroke(width = 2f * density))
        }
    }
}

private fun pctText(v: Double): String =
    (if (v >= 0) "+" else "−") + String.format("%.1f%%", abs(v))
