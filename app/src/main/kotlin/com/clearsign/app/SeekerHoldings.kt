package com.clearsign.app

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a hundred and twenty thousand Seekers are holding.
 *
 * The picture exists to make one fact impossible to miss: of the thirty-four
 * things this crowd holds most, **nineteen are worth nothing**. SEKR, CHAPTER2,
 * HM, PDT, GRUMPY, RETROPASS — they came with the phone, everybody has them, and
 * they are worth zero dollars. They lie along the floor here, dim, exactly where
 * they belong. The handful that hold real money float above it.
 *
 * That is why the crowd feature counts purchases and never holdings. A holdings
 * leaderboard for this crowd is a leaderboard of free things.
 *
 * Numbers from a random sample of the census, measured, not estimated.
 */
private class SeekerHolding(val symbol: String, val pct: Double, val usdPer: Double)

private fun load(ctx: Context): Triple<List<SeekerHolding>, Int, Int> = runCatching {
    val o = JSONObject(ctx.assets.open("seeker_holdings.json").bufferedReader().use { it.readText() })
    val a = o.getJSONArray("rows")
    val rows = (0 until a.length()).map { i ->
        val r = a.getJSONObject(i)
        SeekerHolding(r.getString("s"), r.getDouble("p"), r.getDouble("u"))
    }
    Triple(rows, o.optInt("total"), o.optInt("sample"))
}.getOrElse { Triple(emptyList(), 0, 0) }

@Composable
internal fun SeekerHoldingsCard() {
    val ctx = LocalContext.current
    val (rows, total, sample) = remember { load(ctx) }
    if (rows.isEmpty()) return
    val free = rows.count { it.usdPer < 0.01 }

    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HaloIcon(HIcon.GEM, Halo.mint, 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.hold_title, total),
                    fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = Halo.mint, modifier = Modifier.weight(1f),
                )
            }
            HoldingsField(rows)
            // The legend lives outside the canvas. Inside it, at nine pixels, it was
            // there and unreadable, which is the same as not being there.
            Legend(Halo.mint, stringResource(R.string.hold_axis_up, rows.size - free))
            Legend(Halo.muted, stringResource(R.string.hold_legend_free, free))
            Text(
                stringResource(R.string.hold_note, free, rows.size, sample),
                fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted, lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun Legend(dot: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(rs(999)).background(dot))
        Spacer(Modifier.width(7.dp))
        Text(text, fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, lineHeight = 15.sp)
    }
}

/**
 * Two worlds, one line between them.
 *
 * Above the line, the things worth money: a bubble each, as wide as the crowd
 * that holds it, as high as what one holder's share is worth. Below it, in the
 * dark, the things that came free with the phone — nineteen of the thirty-four
 * most widely held, worth nothing, held by everybody because nobody chose them.
 *
 * The line is the point. Everything else is labelling.
 */
@Composable
private fun HoldingsField(rows: List<SeekerHolding>) {
    val drift by rememberInfiniteTransition(label = "drift").animateFloat(
        0f, (2 * Math.PI).toFloat(),
        infiniteRepeatable(tween(13000, easing = LinearEasing)), label = "d",
    )
    val tm = rememberTextMeasurer()
    val mint = Halo.mint
    val cyan = Halo.cyan
    val muted = Halo.muted
    val ink = Halo.ink

    val valued = rows.filter { it.usdPer >= 0.01 }.sortedByDescending { it.pct }
    val gifts = rows.filter { it.usdPer < 0.01 }.sortedByDescending { it.pct }
    val maxPct = rows.maxOf { it.pct }
    val maxUsd = valued.maxOfOrNull { it.usdPer } ?: 1.0

    Canvas(Modifier.fillMaxWidth().height(230.dp)) {
        val line = size.height * 0.68f
        val top = 30f * density
        val left = 8f * density
        val right = size.width - 8f * density

        // The divider, brightest in the middle, where the eye lands.
        drawLine(
            Brush.horizontalGradient(
                listOf(mint.copy(alpha = 0f), mint.copy(alpha = 0.55f), mint.copy(alpha = 0f)),
            ),
            Offset(0f, line), Offset(size.width, line), 1.6f * density, StrokeCap.Round,
        )

        fun label(text: String, x: Float, y: Float, size2: Float, color: Color, bold: Boolean = false) {
            val lay = tm.measure(
                text,
                TextStyle(
                    fontFamily = Inter, fontSize = size2.sp, color = color,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
                ),
            )
            drawText(lay, topLeft = Offset(x - lay.size.width / 2f, y - lay.size.height / 2f))
        }

        // Above: what is worth something.
        valued.forEachIndexed { i, h ->
            val r = (9f + 15f * sqrt(h.pct / maxPct)).toFloat() * density
            val x = left + r + (right - left - 2 * r) * (i.toFloat() / (valued.size - 1).coerceAtLeast(1))
            val lift = (ln(1 + h.usdPer) / ln(1 + maxUsd)).toFloat().coerceIn(0f, 1f)
            val y = line - 18f * density - lift * (line - top - 18f * density)
            val bob = sin(drift + i * 0.8f) * 2.6f * density
            val tint = if (h.usdPer >= 50) mint else cyan

            drawCircle(tint.copy(alpha = 0.10f), r * 2.0f, Offset(x, y + bob))
            drawCircle(tint.copy(alpha = 0.20f), r, Offset(x, y + bob))
            drawCircle(tint, r, Offset(x, y + bob), style = Stroke(1.5f * density))
            // A line down to the divider, so height reads as a measurement and not
            // as a bubble that happens to be floating there.
            drawLine(
                tint.copy(alpha = 0.16f), Offset(x, y + bob + r), Offset(x, line),
                1f * density, StrokeCap.Round,
            )
            if (r > 11f * density) {
                label(h.symbol, x, y + bob - 5f * density, 9f, ink, bold = true)
                label("$" + h.usdPer.toInt(), x, y + bob + 5f * density, 8f, tint)
            } else {
                // Too small to write inside, so the name goes underneath it. A bubble
                // with no name is a dot, and a dot carries no information at all.
                label(h.symbol, x, y + bob + r + 7f * density, 8f, ink.copy(alpha = 0.75f))
            }
            // How much of the crowd holds it, above the bubble: the width of a circle
            // is not something anybody reads as a percentage.
            label(
                String.format("%.0f%%", h.pct), x, y + bob - r - 7f * density, 8f,
                tint.copy(alpha = 0.8f),
            )
        }

        // Below: the free things, small and dim, in a single row nobody has to read.
        gifts.forEachIndexed { i, h ->
            val r = (4f + 5f * sqrt(h.pct / maxPct)).toFloat() * density
            val per = ((right - left) / gifts.size)
            val x = left + per * (i + 0.5f)
            val y = line + 24f * density + sin(drift * 0.7f + i * 1.3f) * 1.4f * density
            drawCircle(muted.copy(alpha = 0.13f), r, Offset(x, y))
            drawCircle(muted.copy(alpha = 0.34f), r, Offset(x, y), style = Stroke(1f * density))
            // Every other one gets its name: all of them would collide, none of them
            // would leave the reader wondering what the dark row even is.
            if (i % 2 == 0) label(h.symbol, x, y + r + 7f * density, 7.5f, muted.copy(alpha = 0.85f))
        }


    }
}
