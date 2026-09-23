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
 * The coin, drawn properly. The sheet carried the swap button's eighty-pixel line, right for
 * "am I buying into a crash" and wrong for "what is this coin doing": a line hides the range
 * inside each bar. Here candles with wicks, volume, the scale, a crosshair under the finger.
 * Two sources: on Solana the busiest GeckoTerminal pool (what DexTools and DexScreener read),
 * elsewhere CoinGecko OHLC. Timeframes are named after the range covered, never a candle size. Nothing to draw, nothing drawn.
 */
@Composable
internal fun CoinChart(coin: Market.Coin, mint: String?) {
    val ctx = LocalContext.current
    // The chart is in dollars because the source answers in dollars. What is written is
    // converted here, scale included: a coin read in euros cannot have a dollar axis, or the
    // line under the finger says a number that exists nowhere.
    val fx = rememberFx()
    var span by remember(coin.key) { mutableStateOf(Gecko.Span.HOURS) }
    var candles by remember(coin.key) { mutableStateOf<List<Gecko.Candle>>(emptyList()) }
    var loading by remember(coin.key) { mutableStateOf(true) }
    var pool by remember(coin.key) { mutableStateOf<Gecko.Pool?>(null) }
    // Which vendor answered, decided at every load, never remembered. The mint arrives after
    // the sheet opens, so anything settled at first composition would settle on "no mint" and
    // every Solana coin would be drawn from the market with its pool unread.
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
            // The head of the card is the price now or, with a finger on the chart, the candle under
            // it: one line doing two jobs, so the eye never leaves the chart. Fixed height: a header
            // that grows by a row shoves the picture down while you read it.
            Box(Modifier.fillMaxWidth().height(46.dp)) {
                if (at == null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                // The market price before the candle: the candle is one pool's and may be an hour old, the
                                // price on top is what the row behind this sheet just showed. Two numbers for the same coin
                                // on one screen are an error, even when both are true.
                                (coin.priceUsd ?: last)?.let { fx.price(it) } ?: stringResource(R.string.market_no_price),
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
                                fx.num(at.open), fx.num(at.high), fx.num(at.low), fx.num(at.close),
                            ),
                            fontFamily = Mono, fontSize = 10.5.sp, color = Halo.muted, style = Tabular, maxLines = 1,
                        )
                        if (at.volume > 0) {
                            Text(
                                stringResource(R.string.chart_vol_at, fx.cap(at.volume)),
                                fontFamily = Mono, fontSize = 10.5.sp, color = Halo.cyan, style = Tabular, maxLines = 1,
                            )
                        }
                    }
                }
            }

            when {
                candles.size >= 2 -> Candles(candles, tint, cursor, span, fx) { cursor = it }
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
                    p.liquidityUsd?.let { add(Triple(stringResource(R.string.chart_liquidity), fx.cap(it), Halo.ink)) }
                    p.volume24Usd?.let { add(Triple(stringResource(R.string.chart_volume24), fx.cap(it), Halo.ink)) }
                    if (p.buys24 != null && p.sells24 != null) {
                        add(Triple(stringResource(R.string.chart_trades24), "${p.buys24} / ${p.sells24}", Halo.ink))
                    }
                    (coin.marketCap ?: p.fdvUsd)?.let {
                        add(Triple(stringResource(if (coin.marketCap != null) R.string.chart_mcap else R.string.chart_fdv), fx.cap(it), Halo.ink))
                    }
                    p.dex?.let { add(Triple(stringResource(R.string.chart_dex), it.replaceFirstChar { c -> c.uppercase() }, Halo.muted)) }
                } else {
                    coin.marketCap?.let { add(Triple(stringResource(R.string.chart_mcap), fx.cap(it), Halo.ink)) }
                    coin.volume24h?.let { add(Triple(stringResource(R.string.chart_volume24), fx.cap(it), Halo.ink)) }
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

            // The whole thing, where nothing else is drawn: depth, holders and every pool are a
            // website's job. The site is the one the number came from, not the famous one: the pool id
            // is GeckoTerminal's, and DexTools given a foreign code opens something rather than saying
            // "not found". So the link opens exactly the pool drawn above.
            val url = p?.let { "https://www.geckoterminal.com/solana/pools/${it.id}" }
                ?: coin.id.takeIf { it != mint && it != coin.mint }?.let { "https://www.coingecko.com/en/coins/$it" }
            if (url != null) {
                Row {
                    SmallChip(
                        stringResource(R.string.chart_open_on, if (p != null) "GeckoTerminal" else "CoinGecko"),
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
 * The picture: candles, their volume, the scale, the crosshair. Candles down to about two
 * pixels a bar, a filled line below that: a year of daily bars across three hundred points
 * is a dithering pattern, and reads better as the line it has become.
 */
@Composable
private fun Candles(
    candles: List<Gecko.Candle>,
    tint: Color,
    cursor: Int?,
    span: Gecko.Span,
    fx: Fx,
    onCursor: (Int?) -> Unit,
) {
    val tm = rememberTextMeasurer()
    val up = Halo.mint
    val down = Halo.red
    val mutedCol = Halo.muted
    val groundCol = Halo.ground
    val grid = mutedCol.copy(alpha = 0.16f)
    val ground = Halo.ground2
    val axis = remember(mutedCol) { TextStyle(fontFamily = Mono, fontSize = 9.sp, color = mutedCol) }
    val pill = remember(groundCol) { TextStyle(fontFamily = Mono, fontSize = 9.sp, color = groundCol, fontWeight = FontWeight.Bold) }
    // The scale and the four axis labels change with the candles, not with
    // the finger: computed once per list, not once per draw.
    val scale = remember(candles) {
        val lo = candles.minOf { it.low }
        val hi = candles.maxOf { it.high }
        val pad = ((hi - lo) * 0.06).takeIf { it > 0 } ?: (hi * 0.02).coerceAtLeast(1e-12)
        val top = hi + pad
        val bottom = (lo - pad).coerceAtLeast(0.0)
        Triple(bottom, (top - bottom).takeIf { it > 0 } ?: 1.0, candles.maxOf { it.volume }.takeIf { it > 0 } ?: 1.0)
    }
    val axisLabels = remember(scale, fx, axis, tm) { (0..3).map { k -> tm.measure(fx.num(scale.first + scale.second * k / 3.0), axis) } }
    val lastLabel = remember(candles, fx, pill, tm) { tm.measure(fx.num(candles.last().close), pill) }
    val densityNow = androidx.compose.ui.platform.LocalDensity.current.density
    val dash = remember(densityNow) { PathEffect.dashPathEffect(floatArrayOf(5f * densityNow, 4f * densityNow)) }

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

        val bottom = scale.first
        val range = scale.second
        fun y(v: Double) = (priceH - ((v - bottom) / range * priceH)).toFloat().coerceIn(0f, priceH)
        fun x(i: Int) = plotW * (i + 0.5f) / n

        // Four prices down the side. Without them the picture has a shape and no
        // size, and every coin looks like every other coin.
        for (k in 0..3) {
            val v = bottom + range * k / 3.0
            val gy = y(v)
            drawLine(grid, Offset(0f, gy), Offset(plotW, gy), strokeWidth = 1f)
            val lay = axisLabels[k]
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
            val maxV = scale.third
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
        drawLine(tint.copy(alpha = 0.5f), Offset(0f, lastY), Offset(plotW, lastY), strokeWidth = 1f * density, pathEffect = dash)
        run {
            val lay = lastLabel
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

            val lay = tm.measure(fx.num(c.close), pill)
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

/** The gutter the prices are written in. Wide enough for eight decimals, which is what the coins people follow here cost. */
private val AXIS_W = 58.dp

/** A number for the scale: as many decimals as the price needs, and no currency on it. */
internal fun axisNum(v: Double): String {
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
