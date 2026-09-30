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
 * What 120k Seekers hold, from a random sample of the census. Of the top 34 holdings seven are
 * worthless (SEKR, CHAPTER2, PDT, NAMI came with the phone), which is why the crowd feature counts
 * purchases, not holdings. [usdPer] is the median (USDC: $10, mean $487); [avg] is the mean, used
 * only to spot worthless coins (SI, PLANK, MPLX: median zero, mean above it).
 */
private class SeekerHolding(val symbol: String, val pct: Double, val usdPer: Double, val avg: Double)

/** What the staking program holds for this crowd, which no wallet balance shows. */
private class SkrStaked(val pct: Double, val avg: Double, val usdPer: Double?)

private class Census(val rows: List<SeekerHolding>, val total: Int, val sample: Int, val staked: SkrStaked?)

private fun load(ctx: Context): Census = runCatching {
    val o = JSONObject(ctx.assets.open("seeker_holdings.json").bufferedReader().use { it.readText() })
    val a = o.getJSONArray("rows")
    val rows = (0 until a.length()).map { i ->
        val r = a.getJSONObject(i)
        SeekerHolding(
            r.getString("s"), r.getDouble("p"), r.getDouble("u"),
            // Older censuses put the mean in `u` and carried no `avg` at all.
            r.optDouble("avg", r.getDouble("u")),
        )
    }
    val st = o.optJSONObject("skrStaked")?.let {
        SkrStaked(it.optDouble("pct"), it.optDouble("avg"), it.optDouble("usdPer").takeIf { u -> !u.isNaN() && u > 0 })
    }
    Census(rows, o.optInt("total"), o.optInt("sample"), st)
}.getOrElse { Census(emptyList(), 0, 0, null) }

@Composable
internal fun SeekerHoldingsCard(animate: Boolean = true) {
    val ctx = LocalContext.current
    val census = remember { load(ctx) }
    val rows = census.rows
    val total = census.total
    val sample = census.sample
    if (rows.isEmpty()) { GlassCard { NothingHere(stringResource(R.string.crowd_no_data)) }; return }
    val free = rows.count { it.avg < 0.01 }

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
        HoldingsField(rows, animate)
        Legend(Halo.mint, stringResource(R.string.hold_bars_note, rows.size - free))
        Legend(Halo.muted, stringResource(R.string.hold_legend_free, free))
        // Spell out median vs mean, or $10 of USDC across 120k people looks like a rounding error
        // (the mean is $487). Read from the file, so it holds after the next census.
        rows.firstOrNull { it.avg >= 1 && it.avg > it.usdPer * 3 }?.let { h ->
            Text(
                stringResource(R.string.hold_typical, h.symbol, dollars(h.usdPer), dollars(h.avg)),
                fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted, lineHeight = 16.sp,
            )
        }
        Text(
            stringResource(R.string.hold_note, free, rows.size, sample),
            fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted, lineHeight = 16.sp,
        )
        // SKR in the staking program is invisible to a token-account census, so these holders look empty.
        census.staked?.let { st ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(Halo.stroke))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HaloIcon(HIcon.SHIELD_LOCK, Halo.cyan, 14.dp)
                Spacer(Modifier.width(7.dp))
                Text(
                    stringResource(R.string.hold_staked_title, fmtPct(st.pct)),
                    fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = Halo.cyan,
                )
            }
            Text(
                st.usdPer?.let { u -> stringResource(R.string.hold_staked_body_usd, fmtInt(st.avg), fmtUsd(u)) }
                    ?: stringResource(R.string.hold_staked_body, fmtInt(st.avg)),
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
 * Holdings as bars: length is the share of holders, the number on the right is the money (bubbles
 * overlapped at fifteen labels). Free coins get a dim row underneath. Bars grow in once on arrival;
 * one still moving would suggest a changing number.
 */
@Composable
private fun HoldingsField(rows: List<SeekerHolding>, animate: Boolean = true) {
    val valued = rows.filter { it.avg >= 0.01 }.sortedByDescending { it.pct }
    val gifts = rows.filter { it.avg < 0.01 }.sortedByDescending { it.pct }
    val maxPct = (valued.maxOfOrNull { it.pct } ?: 1.0).coerceAtLeast(1.0)

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        // Column headers: without them "$144" next to "32%" read as a price, not the amount per holder.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(66.dp))
            Text(stringResource(R.string.hold_col_who), style = HaloType.label, color = Halo.muted, modifier = Modifier.weight(1f))
            // One label spanning both number columns, right-aligned with the dollars.
            Text(
                stringResource(R.string.hold_col_each), style = HaloType.label, color = Halo.muted,
                modifier = Modifier.width(8.dp + 36.dp + 6.dp + 44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
        valued.take(9).forEachIndexed { i, h ->
            // Keyed reveal: in Scout's lazy list this card is disposed when scrolled off, and the
            // bars would regrow from zero on the way back.
            val grow = if (animate) rememberReveal(key = h.symbol, durationMs = 600 + i * 70) else 1f
            // $5: the old $50 was tuned on the mean, and on the median no bar reached it.
                val tint = if (h.usdPer >= 5) Halo.mint else Halo.cyan
            Row(
                Modifier.fillMaxWidth().then(if (animate) Modifier.staggeredEntrance(i, key = h.symbol) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
            // Free coins as dim, named chips on one wrapping line.
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

/** Cents below $100, whole dollars above. */
private fun dollars(v: Double): String =
    if (v < 100) "$" + java.text.DecimalFormat("0.00").format(v)
    else "$" + java.text.DecimalFormat("#,##0").format(v)

private fun fmtPct(v: Double) = java.text.DecimalFormat("#.#").format(v)
private fun fmtInt(v: Double) = java.text.NumberFormat.getIntegerInstance().format(v)
private fun fmtUsd(v: Double) = "$" + java.text.DecimalFormat("#,##0").format(v)
