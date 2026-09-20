package com.clearsign.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * The coin, drawn properly.
 *
 * The sheet used to carry the same eighty-pixel line the swap button carries,
 * which is the right picture for "am I about to buy into a crash" and the wrong
 * one for "what is this coin doing". The difference is not decoration: a line
 * hides the range inside each bar, and the range is where the violence is. What
 * is here instead is what a person opening a coin actually reads — candles with
 * their wicks, the volume under them, the scale written down the side, and a
 * crosshair that answers "what was it worth *there*" by touching the screen.
 *
 * Two sources, chosen by what the coin is.
 *
 *  - It lives on Solana: the busiest pool on GeckoTerminal, which is the same
 *    data DexTools and DexScreener read, with real per-candle volume and the
 *    pool's own depth underneath.
 *  - It does not: CoinGecko's OHLC, exchange-weighted. Bitcoin has no Solana
 *    pool and never will, and a blank rectangle where its chart belongs reads as
 *    a broken screen rather than as an honest absence.
 *
 * Whichever answers, the timeframes are named after the range they cover and
 * never after a candle size, because only one of the two sources lets us choose
 * the candle size. And when neither has anything, nothing is drawn: a flat line
 * would read as a price that stood still.
 */
@Composable
internal fun CoinChart(coin: Market.Coin, mint: String?) {
    val ctx = LocalContext.current
    var span by remember(coin.key) { mutableStateOf(Gecko.Span.HOURS) }
    var candles by remember(coin.key) { mutableStateOf<List<Gecko.Candle>>(emptyList()) }
    var loading by remember(coin.key) { mutableStateOf(true) }
    var pool by remember(coin.key) { mutableStateOf<Gecko.Pool?>(null) }
    // Which vendor answered, decided at every load and never remembered as a
    // decision. The mint arrives after the sheet opens — the coin page is where
    // we go and look it up — so anything settled at first composition would
    // settle on "no mint" and stay there, and every Solana coin would be drawn
    // from the market with its pool sitting right there unread.
    var fromPool by remember(coin.key) { mutableStateOf(mint != null) }
    var cursor by remember(coin.key) { mutableStateOf<Int?>(null) }

    LaunchedEffect(mint) {
        if (mint == null) return@LaunchedEffect
        pool = withContext(Dispatchers.IO) { runCatching { Gecko.pool(mint) }.getOrNull() }
    }

    LaunchedEffect(mint, coin.id, span) {
        loading = true
        cursor = null
        var viaPool = false
        var got = if (mint != null) {
            withContext(Dispatchers.IO) { runCatching { Gecko.series(mint, span) }.getOrDefault(emptyList()) }
                .also { viaPool = it.isNotEmpty() }
        } else {
            emptyList()
        }
        // No pool, or a pool too young to have filled this span. The market
        // knows the coin by name, and a chart from one level up beats no chart.
        val days = span.cgDays
        if (got.isEmpty() && days != null && coin.id != mint) {
            got = withContext(Dispatchers.IO) { runCatching { Market.ohlc(coin.id, days) }.getOrDefault(emptyList()) }
        }
        candles = got
        fromPool = viaPool
        loading = false
    }

    // Only the spans the source can honestly draw. A coin with a mint can be
    // read candle by candle; one without is whatever CoinGecko's ranges give.
    val spans = if (mint != null) Gecko.Span.entries.toList() else Gecko.Span.entries.filter { it.cgDays != null }
    LaunchedEffect(mint) { if (span !in spans) span = Gecko.Span.HOURS }

    val first = candles.firstOrNull()?.open
    val last = candles.lastOrNull()?.close
    val pct = if (first != null && last != null && first > 0) (last - first) / first * 100 else null
    val tint = if (pct == null) Halo.muted else if (pct >= 0) Halo.mint else Halo.red
    val at = cursor?.let { candles.getOrNull(it) }

    GlassCard {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // The head of the card is either the price now or, while a finger
            // is on the chart, the candle under it. One line doing two jobs,
            // because a readout that appears somewhere else makes the eye leave
            // the chart. Its height is fixed: a header that grows by a row would
            // shove the picture down at the exact moment you are reading it.
            Box(Modifier.fillMaxWidth().height(46.dp)) {
                if (at == null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (last ?: coin.priceUsd)?.let { fmtPrice(it, "USD") } ?: stringResource(R.string.market_no_price),
                                fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 21.sp, color = Halo.ink, maxLines = 1,
                            )
                            if (pct != null) {
                                Text(
                                    stringResource(R.string.chart_change, pctText(pct), stringResource(span.labelRes)),
                                    fontFamily = Mono, fontSize = 11.5.sp, color = tint, style = Tabular, maxLines = 1,
                                )
                            }
                        }
                        // Which vendor drew this. Not a detail: one of them is
                        // one pool on this chain and the other is every exchange
                        // there is, and they do not have to agree.
                        Text(
                            stringResource(if (fromPool) R.string.chart_src_pool else R.string.chart_src_market),
                            fontFamily = Inter, fontSize = 10.sp, color = Halo.muted,
                        )
                    }
                } else {
                    Column {
                        Text(stamp(at.at, span), fontFamily = Mono, fontSize = 11.sp, color = Halo.ink, style = Tabular, maxLines = 1)
                        Text(
                            stringResource(
                                R.string.chart_ohlc,
                                axisNum(at.open), axisNum(at.high), axisNum(at.low), axisNum(at.close),
                            ),
                            fontFamily = Mono, fontSize = 10.5.sp, color = Halo.muted, style = Tabular, maxLines = 1,
                        )
                        if (at.volume > 0) {
                            Text(
                                stringResource(R.string.chart_vol_at, fmtCap(at.volume)),
                                fontFamily = Mono, fontSize = 10.5.sp, color = Halo.cyan, style = Tabular, maxLines = 1,
                            )
                        }
                    }
                }
            }

            when {
                candles.size >= 2 -> Candles(candles, tint, cursor, span) { cursor = it }
                loading -> Box(Modifier.fillMaxWidth().height(196.dp))
                else -> Text(stringResource(R.string.chart_none), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                spans.forEach { s ->
                    val on = s == span
                    Box(
                        Modifier.weight(1f).clip(rs(999))
                            .background(if (on) tint.copy(alpha = 0.16f) else Halo.cardSoft)
                            .clickable { span = s }
                            .padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(s.labelRes), fontFamily = Mono, fontSize = 10.5.sp,
                            color = if (on) tint else Halo.muted, style = Tabular, maxLines = 1,
                        )
                    }
                }
            }

            // What the price is standing on. A chart with no depth under it is
            // the oldest trap on this chain: a line that looks like a market and
            // is two thousand dollars deep.
            val p = pool
            val stats = buildList {
                if (p != null) {
                    p.liquidityUsd?.let { add(Triple(stringResource(R.string.chart_liquidity), fmtCap(it), Halo.ink)) }
                    p.volume24Usd?.let { add(Triple(stringResource(R.string.chart_volume24), fmtCap(it), Halo.ink)) }
                    if (p.buys24 != null && p.sells24 != null) {
                        add(Triple(stringResource(R.string.chart_trades24), "${p.buys24} / ${p.sells24}", Halo.ink))
                    }
                    (coin.marketCap ?: p.fdvUsd)?.let {
                        add(Triple(stringResource(if (coin.marketCap != null) R.string.chart_mcap else R.string.chart_fdv), fmtCap(it), Halo.ink))
                    }
                    p.dex?.let { add(Triple(stringResource(R.string.chart_dex), it.replaceFirstChar { c -> c.uppercase() }, Halo.muted)) }
                } else {
                    coin.marketCap?.let { add(Triple(stringResource(R.string.chart_mcap), fmtCap(it), Halo.ink)) }
                    coin.volume24h?.let { add(Triple(stringResource(R.string.chart_volume24), fmtCap(it), Halo.ink)) }
                }
            }
            if (stats.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    stats.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth()) {
                            pair.forEach { (label, value, colour) ->
                                Column(Modifier.weight(1f)) {
                                    Text(label.uppercase(), fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = Halo.muted)
                                    Text(value, fontFamily = Mono, fontSize = 12.sp, color = colour, style = Tabular, maxLines = 1)
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // The whole thing, at the place that does nothing else. We draw what
            // fits in a wallet; depth, holders and every pool are a website's
            // job, and pretending otherwise would mean shipping a browser.
            val url = p?.let { "https://www.dextools.io/app/en/solana/pair-explorer/${it.id}" }
                ?: coin.id.takeIf { it != mint && it != coin.mint }?.let { "https://www.coingecko.com/en/coins/$it" }
            if (url != null) {
                Row {
                    SmallChip(
                        stringResource(R.string.chart_open_on, if (p != null) "DexTools" else "CoinGecko"),
                        HIcon.EXTERNAL, tint = Halo.cyan,
                    ) {
                        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                    }
                }
            }
        }
    }
}

/**
 * The picture itself: candles, their volume, the scale, and the crosshair.
 *
 * Drawn as candles down to about two pixels a bar and as a filled line below
 * that. A year of daily bars on a phone is three hundred and sixty-five wicks
 * across three hundred points — at that width a candle is not a candle, it is a
 * dithering pattern, and the shape reads better as the line it has become.
 */
@Composable
private fun Candles(
    candles: List<Gecko.Candle>,
    tint: Color,
    cursor: Int?,
    span: Gecko.Span,
    onCursor: (Int?) -> Unit,
) {
    val tm = rememberTextMeasurer()
    val axis = TextStyle(fontFamily = Mono, fontSize = 9.sp, color = Halo.muted)
    val pill = TextStyle(fontFamily = Mono, fontSize = 9.sp, color = Halo.ground, fontWeight = FontWeight.Bold)
    val up = Halo.mint
    val down = Halo.red
    val grid = Halo.muted.copy(alpha = 0.16f)
    val ground = Halo.ground2

    Canvas(
        Modifier.fillMaxWidth().height(196.dp)
            // Touch to read a candle, slide to read the next one. The gesture
            // gives up the moment it turns vertical, or the chart would eat the
            // scroll of the sheet it is sitting in.
            .pointerInput(candles.size) {
                val n = candles.size
                awaitEachGesture {
                    val down0 = awaitFirstDown(requireUnconsumed = false)
                    val axisW = AXIS_W.toPx()
                    val plotW = (size.width.toFloat() - axisW).coerceAtLeast(1f)
                    fun idx(x: Float) = ((x / plotW) * n).toInt().coerceIn(0, n - 1)
                    onCursor(idx(down0.position.x))
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down0.id } ?: break
                        if (!ch.pressed) break
                        val dx = ch.position.x - down0.position.x
                        val dy = ch.position.y - down0.position.y
                        if (abs(dy) > abs(dx) && abs(dy) > viewConfiguration.touchSlop) break
                        onCursor(idx(ch.position.x))
                        if (abs(dx) > viewConfiguration.touchSlop) ch.consume()
                    }
                    onCursor(null)
                }
            },
    ) {
        val n = candles.size
        val axisW = AXIS_W.toPx()
        val timeH = 14.dp.toPx()
        val plotW = size.width - axisW
        val bodyH = size.height - timeH
        val hasVol = candles.any { it.volume > 0 }
        val volH = if (hasVol) bodyH * 0.18f else 0f
        val priceH = bodyH - volH - (if (hasVol) 6.dp.toPx() else 0f)

        val lo = candles.minOf { it.low }
        val hi = candles.maxOf { it.high }
        val pad = ((hi - lo) * 0.06).takeIf { it > 0 } ?: (hi * 0.02).coerceAtLeast(1e-12)
        val top = hi + pad
        val bottom = (lo - pad).coerceAtLeast(0.0)
        val range = (top - bottom).takeIf { it > 0 } ?: 1.0
        fun y(v: Double) = (priceH - ((v - bottom) / range * priceH)).toFloat().coerceIn(0f, priceH)
        fun x(i: Int) = plotW * (i + 0.5f) / n

        // Four prices down the side. Without them the picture has a shape and no
        // size, and every coin looks like every other coin.
        for (k in 0..3) {
            val v = bottom + range * k / 3.0
            val gy = y(v)
            drawLine(grid, Offset(0f, gy), Offset(plotW, gy), strokeWidth = 1f)
            val lay = tm.measure(axisNum(v), axis)
            drawText(lay, topLeft = Offset(plotW + 5.dp.toPx(), (gy - lay.size.height / 2f).coerceIn(0f, priceH - lay.size.height)))
        }

        val slot = plotW / n
        if (slot >= 2.2f) {
            val w = (slot * 0.66f).coerceAtMost(9.dp.toPx())
            candles.forEachIndexed { i, c ->
                val col = if (c.close >= c.open) up else down
                val cx = x(i)
                drawLine(col.copy(alpha = 0.75f), Offset(cx, y(c.high)), Offset(cx, y(c.low)), strokeWidth = max(1f, w * 0.18f))
                val yo = y(c.open)
                val yc = y(c.close)
                drawRect(col, Offset(cx - w / 2f, minOf(yo, yc)), Size(w, max(1.2f, abs(yc - yo))))
            }
        } else {
            val line = Path()
            candles.forEachIndexed { i, c -> if (i == 0) line.moveTo(x(i), y(c.close)) else line.lineTo(x(i), y(c.close)) }
            val area = Path().apply {
                addPath(line)
                lineTo(x(n - 1), priceH); lineTo(x(0), priceH); close()
            }
            drawPath(area, Brush.verticalGradient(listOf(tint.copy(alpha = 0.20f), Color.Transparent), endY = priceH))
            drawPath(line, tint, style = Stroke(width = 1.8f * density, cap = StrokeCap.Round))
        }

        if (hasVol) {
            val maxV = candles.maxOf { it.volume }.takeIf { it > 0 } ?: 1.0
            val w = (slot * 0.66f).coerceAtMost(9.dp.toPx()).coerceAtLeast(1f)
            candles.forEachIndexed { i, c ->
                val h = (c.volume / maxV * volH).toFloat()
                if (h <= 0f) return@forEachIndexed
                val col = if (c.close >= c.open) up else down
                drawRect(col.copy(alpha = 0.38f), Offset(x(i) - w / 2f, bodyH - h), Size(w, h))
            }
        }

        // Where it is now, written on the scale, so the last candle has a number
        // and not only a height.
        val lastY = y(candles.last().close)
        val dash = PathEffect.dashPathEffect(floatArrayOf(5f * density, 4f * density))
        drawLine(tint.copy(alpha = 0.5f), Offset(0f, lastY), Offset(plotW, lastY), strokeWidth = 1f * density, pathEffect = dash)
        run {
            val lay = tm.measure(axisNum(candles.last().close), pill)
            val px = plotW + 3.dp.toPx()
            val py = (lastY - lay.size.height / 2f - 2f * density).coerceIn(0f, priceH - lay.size.height)
            drawRoundRect(tint, Offset(px, py), Size(lay.size.width + 6f * density, lay.size.height + 4f * density), CornerRadius(3f * density))
            drawText(lay, topLeft = Offset(px + 3f * density, py + 2f * density))
        }

        // Three times along the bottom: the same shape means one thing over five
        // hours and another over a year.
        listOf(0, n / 2, n - 1).distinct().forEachIndexed { k, i ->
            val lay = tm.measure(stamp(candles[i].at, span), axis)
            val tx = when (k) {
                0 -> 0f
                1 -> (x(i) - lay.size.width / 2f)
                else -> plotW - lay.size.width
            }.coerceIn(0f, (plotW - lay.size.width).coerceAtLeast(0f))
            drawText(lay, topLeft = Offset(tx, size.height - timeH + 2f * density))
        }

        // The crosshair: the candle under the finger, said twice — once on the
        // price scale and once on the clock.
        cursor?.let { ci ->
            val c = candles.getOrNull(ci) ?: return@let
            val cx = x(ci)
            val cy = y(c.close)
            drawLine(Halo.ink.copy(alpha = 0.45f), Offset(cx, 0f), Offset(cx, bodyH), strokeWidth = 1f * density, pathEffect = dash)
            drawLine(Halo.ink.copy(alpha = 0.45f), Offset(0f, cy), Offset(plotW, cy), strokeWidth = 1f * density, pathEffect = dash)
            drawCircle(Halo.ink, 3f * density, Offset(cx, cy))

            val lay = tm.measure(axisNum(c.close), pill)
            val py = (cy - lay.size.height / 2f - 2f * density).coerceIn(0f, priceH - lay.size.height)
            drawRoundRect(Halo.ink, Offset(plotW + 3.dp.toPx(), py), Size(lay.size.width + 6f * density, lay.size.height + 4f * density), CornerRadius(3f * density))
            drawText(lay, topLeft = Offset(plotW + 3.dp.toPx() + 3f * density, py + 2f * density))

            val tlay = tm.measure(stamp(c.at, span), TextStyle(fontFamily = Mono, fontSize = 9.sp, color = Halo.ink))
            val tx = (cx - tlay.size.width / 2f).coerceIn(0f, (plotW - tlay.size.width).coerceAtLeast(0f))
            drawRoundRect(
                ground, Offset(tx - 3f * density, size.height - timeH), Size(tlay.size.width + 6f * density, tlay.size.height.toFloat()),
                CornerRadius(3f * density),
            )
            drawText(tlay, topLeft = Offset(tx, size.height - timeH))
        }
    }
}

/**
 * The gutter the prices are written in. Wide enough for eight decimals, because
 * that is what the coins people follow here actually cost.
 */
private val AXIS_W = 58.dp

/** A number for the scale: as many decimals as the price needs, and no currency on it. */
private fun axisNum(v: Double): String {
    val decimals = when {
        v >= 1000 -> 0
        v >= 1 -> 2
        v >= 0.01 -> 4
        v >= 0.0001 -> 6
        else -> 8
    }
    val s = String.format(java.util.Locale.getDefault(), "%,.${decimals}f", v)
    return if (decimals == 0) s else s.trimEnd('0').trimEnd(',', '.')
}

/** The clock under a candle, at the resolution the span is drawn in. */
private fun stamp(at: Long, span: Gecko.Span): String {
    val pattern = when (span) {
        Gecko.Span.MINUTES, Gecko.Span.HOURS -> "HH:mm"
        Gecko.Span.WEEK -> "d MMM HH:mm"
        else -> "d MMM yy"
    }
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(at))
}

private fun pctText(v: Double): String =
    (if (v >= 0) "+" else "−") + String.format(java.util.Locale.getDefault(), "%.1f%%", abs(v))
