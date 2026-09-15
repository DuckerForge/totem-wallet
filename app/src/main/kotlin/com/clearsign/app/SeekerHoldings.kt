@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import androidx.compose.foundation.layout.padding
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
            Legend(Halo.mint, stringResource(R.string.hold_bars_note, rows.size - free))
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
 * What they hold, as bars.
 *
 * It was a field of bubbles, floating higher the more one holder's share was
 * worth, with the free things in a dim row along the floor. Pretty, and not
 * readable: at fifteen bubbles the labels sat on each other (USDC over SKR,
 * PUMP over JitoSOL over PENGU) and "higher means worth more" needed a legend
 * to explain the legend. A bar is a thing a person reads without being taught:
 * longer is more people, the number on the right is the money. The free things
 * keep their own row underneath, dim, so the point survives: most of what this
 * crowd holds came with the phone and is worth nothing.
 *
 * Each bar grows into place, one after another, the first time the card is
 * seen. Once, on arrival: motion here says "this is being drawn for you", and
 * a bar that kept growing would say the number was changing.
 */
@Composable
private fun HoldingsField(rows: List<SeekerHolding>) {
    val valued = rows.filter { it.usdPer >= 0.01 }.sortedByDescending { it.pct }
    val gifts = rows.filter { it.usdPer < 0.01 }.sortedByDescending { it.pct }
    val maxPct = (valued.maxOfOrNull { it.pct } ?: 1.0).coerceAtLeast(1.0)

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        // What the columns are, said once above them. "$144" next to "32%" read
        // as a price, and it is not: it is what one holder has, on average.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(66.dp))
            Text(stringResource(R.string.hold_col_who), style = HaloType.label, color = Halo.muted, modifier = Modifier.weight(1f))
            // One label over the two number columns together, ending where the
            // dollars end. Over the last column alone it floated on its own.
            Text(
                stringResource(R.string.hold_col_each), style = HaloType.label, color = Halo.muted,
                modifier = Modifier.width(8.dp + 36.dp + 6.dp + 44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        valued.take(9).forEachIndexed { i, h ->
            val grow = rememberReveal(key = h.symbol, durationMs = 600 + i * 70)
            val tint = if (h.usdPer >= 50) Halo.mint else Halo.cyan
            Row(Modifier.fillMaxWidth().staggeredEntrance(i, key = h.symbol), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    h.symbol, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Halo.ink,
                    modifier = Modifier.width(66.dp), maxLines = 1,
                )
                // The bar: a track the full width, the fill as long as the share
                // of the crowd, drawn rather than laid out so the growth is smooth.
                Canvas(Modifier.weight(1f).height(10.dp)) {
                    val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
                    drawRoundRect(Halo.stroke.copy(alpha = 0.5f), cornerRadius = r)
                    val w = (size.width * (h.pct / maxPct) * grow).toFloat().coerceAtLeast(size.height)
                    drawRoundRect(
                        Brush.horizontalGradient(listOf(tint.copy(alpha = 0.55f), tint), endX = w),
                        size = androidx.compose.ui.geometry.Size(w, size.height), cornerRadius = r,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    String.format("%.0f%%", h.pct), fontFamily = Mono, fontSize = 11.sp, color = tint, style = Tabular,
                    modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "$" + h.usdPer.toInt(), fontFamily = Mono, fontSize = 11.sp, color = Halo.muted, style = Tabular,
                    modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
        if (gifts.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            // The free things, in one dim line that wraps. Named, because a row of
            // dots carries no information at all, and dim, because that is the
            // whole message about them.
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().staggeredEntrance(valued.size.coerceAtMost(9), key = "gifts"),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                gifts.forEach { h ->
                    Row(
                        Modifier.clip(rs(999)).background(Halo.muted.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(h.symbol, fontFamily = Inter, fontSize = 10.5.sp, color = Halo.muted.copy(alpha = 0.9f))
                        Spacer(Modifier.width(4.dp))
                        Text(String.format("%.0f%%", h.pct), fontFamily = Mono, fontSize = 10.sp, color = Halo.muted.copy(alpha = 0.7f), style = Tabular)
                    }
                }
            }
        }
    }
}
