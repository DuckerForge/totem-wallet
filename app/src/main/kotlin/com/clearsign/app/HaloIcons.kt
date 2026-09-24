package com.clearsign.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * ClearSign's own icon set — hand-drawn line icons on a 24-unit grid, drawn
 * live on a Canvas so they scale crisply at any density and take any Halo tint.
 * No emoji, no Material glyphs: one consistent stroke weight everywhere.
 */
enum class HIcon {
    // risk flags
    SKULL, BAN, INFINITY, KEY, TRASH, SPARK, MASK, FLASK, DRIFT, MEGAPHONE,
    DRAIN, FLAG, HOURGLASS, GIFT, SEEDLING, UNLOCK,
    // states / actions
    BLOCK, SHIELD_LOCK, CHECK, WARNING, PEN, SEND, SIGN, LOGIN, COPY, EXTERNAL,
    CHEVRON_RIGHT, CHEVRON_DOWN, CHEVRON_LEFT, CLOSE, SEARCH, NFC, MORE, HOLD, GEM, WALLET, HISTORY, CONTACTS, PEOPLE, INFO, FINGERPRINT,
    // wallet actions / themes
    LOCK, COINS, QR, PALETTE, RECEIVE, PASTE, SCAN, SHARE,
    /** The light switch: a crescent to go dark, a sun to come back. */
    MOON, SUN,
    // brand: a shield sealed with a signature check
    SEAL,
    // brand: the carrier pigeon in a shield
    PIGEON,
    // swap: two arrows
    SWAP,
    /** Following a coin. Outline while you are not, filled once you are. */
    STAR, STAR_FILLED,
    /** The market: a line that has been going up, or one that has been going down. */
    CHART, CHART_DOWN,
    /** The agent: a small head with an antenna. Something that thinks, in a box you can see. */
    AGENT,
    /** A bridge: two piers and the arc between them. */
    BRIDGE,
    // navigation / ledger
    RECEIPT, SETTINGS, FILTER, DOWNLOAD, TAG, NOTE, CALENDAR, PDF,
    // refresh: three quarters of an arc with a tip. HISTORY is a clock and said something else.
    REFRESH,
}

@Composable
fun HaloIcon(icon: HIcon, tint: Color, size: Dp = 20.dp, modifier: Modifier = Modifier, strokeScale: Float = 1f, description: String? = null) {
    // A Canvas says nothing to TalkBack. An icon that is the whole button (close, back, the
    // star) gets a [description]; one next to its own text stays silent, the text is read.
    val m = if (description != null) modifier.semantics { contentDescription = description } else modifier
    Canvas(m.size(size)) {
        val u = this.size.minDimension / 24f
        val g = G(this, u, tint, 1.9f * u * strokeScale * Halo.palette.iconStroke)
        g.draw(icon)
    }
}

/** Tiny drawing DSL in 24-unit coordinates. */
private class G(val s: DrawScope, val u: Float, val tint: Color, val sw: Float) {
    private val stroke get() = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = s.drawLine(tint, p(x1, y1), p(x2, y2), sw, StrokeCap.Round)
    fun circle(cx: Float, cy: Float, r: Float, fill: Boolean = false) =
        s.drawCircle(tint, r * u, p(cx, cy), style = if (fill) Fill else stroke)
    fun dot(cx: Float, cy: Float, r: Float = 1.3f) = circle(cx, cy, r, fill = true)
    fun arc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) =
        s.drawArc(tint, start, sweep, false, p(cx - r, cy - r), Size(2 * r * u, 2 * r * u), style = stroke)
    /** The same drawing in another color. Only for the very few icons that want two. */
    fun two(c: Color, body: G.() -> Unit) = G(s, u, c, sw).body()
    fun poly(vararg pts: Float, close: Boolean = false, fill: Boolean = false) = path(fill, close) {
        moveTo(pts[0], pts[1]); var i = 2; while (i < pts.size) { lineTo(pts[i], pts[i + 1]); i += 2 }
    }
    fun path(fill: Boolean = false, close: Boolean = false, build: PB.() -> Unit) {
        val path = Path(); PB(path, u).build(); if (close) path.close()
        s.drawPath(path, tint, style = if (fill) Fill else stroke)
    }
    class PB(val path: Path, val u: Float) {
        fun moveTo(x: Float, y: Float) = path.moveTo(x * u, y * u)
        fun lineTo(x: Float, y: Float) = path.lineTo(x * u, y * u)
        fun quadTo(cx: Float, cy: Float, x: Float, y: Float) = path.quadraticBezierTo(cx * u, cy * u, x * u, y * u)
        fun cubicTo(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float) = path.cubicTo(c1x * u, c1y * u, c2x * u, c2y * u, x * u, y * u)
        fun arcTo(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) =
            path.arcTo(Rect(Offset((cx - r) * u, (cy - r) * u), Size(2 * r * u, 2 * r * u)), start, sweep, false)
        fun close() = path.close()
    }

    fun draw(icon: HIcon) {
        when (icon) {
            HIcon.SKULL -> {
                path { moveTo(6f, 15f); lineTo(6f, 10.5f); arcTo(12f, 10.5f, 6f, 180f, 180f); lineTo(18f, 15f); quadTo(18f, 17f, 16f, 17f); lineTo(16f, 20f); lineTo(8f, 20f); lineTo(8f, 17f); quadTo(6f, 17f, 6f, 15f) }
                dot(9.6f, 11.3f, 1.5f); dot(14.4f, 11.3f, 1.5f)
                line(10.5f, 17.2f, 10.5f, 19.5f); line(13.5f, 17.2f, 13.5f, 19.5f)
            }
            HIcon.BAN -> { circle(12f, 12f, 8.5f); line(6f, 6f, 18f, 18f) }
            HIcon.INFINITY -> path {
                moveTo(12f, 12f); cubicTo(9.5f, 6.5f, 3.5f, 6.5f, 3.5f, 12f); cubicTo(3.5f, 17.5f, 9.5f, 17.5f, 12f, 12f)
                cubicTo(14.5f, 6.5f, 20.5f, 6.5f, 20.5f, 12f); cubicTo(20.5f, 17.5f, 14.5f, 17.5f, 12f, 12f)
            }
            HIcon.KEY -> { circle(7.5f, 12f, 3.8f); line(11.3f, 12f, 21f, 12f); line(17f, 12f, 17f, 15.5f); line(20.5f, 12f, 20.5f, 14.5f) }
            HIcon.TRASH -> {
                line(4f, 7f, 20f, 7f); poly(9f, 7f, 9f, 4.5f, 15f, 4.5f, 15f, 7f)
                poly(6f, 7f, 7f, 20.5f, 17f, 20.5f, 18f, 7f); line(10f, 10.5f, 10.3f, 17f); line(14f, 10.5f, 13.7f, 17f)
            }
            HIcon.SPARK -> {
                path(fill = true) { moveTo(12f, 3f); quadTo(12.6f, 11.4f, 21f, 12f); quadTo(12.6f, 12.6f, 12f, 21f); quadTo(11.4f, 12.6f, 3f, 12f); quadTo(11.4f, 11.4f, 12f, 3f) }
                dot(19f, 5f, 1.2f); dot(5f, 19f, 1.2f)
            }
            HIcon.MASK -> {
                path { moveTo(3f, 8.5f); quadTo(12f, 4.5f, 21f, 8.5f); lineTo(20f, 14.5f); quadTo(16.5f, 18f, 13.5f, 14.5f); quadTo(12f, 13f, 10.5f, 14.5f); quadTo(7.5f, 18f, 4f, 14.5f); close() }
                circle(8.3f, 11f, 1.6f); circle(15.7f, 11f, 1.6f)
            }
            HIcon.FLASK -> {
                line(9f, 3f, 15f, 3f); path { moveTo(10.2f, 3f); lineTo(10.2f, 9.5f); lineTo(4.8f, 18.8f); quadTo(4f, 21f, 6.5f, 21f); lineTo(17.5f, 21f); quadTo(20f, 21f, 19.2f, 18.8f); lineTo(13.8f, 9.5f); lineTo(13.8f, 3f) }
                line(7.6f, 15.5f, 16.4f, 15.5f); dot(11f, 18f, 0.9f); dot(14f, 18.5f, 0.7f)
            }
            HIcon.DRIFT -> { arc(12f, 12f, 8f, -60f, 270f); poly(20.5f, 3.5f, 20.5f, 8.5f, 15.5f, 8.5f); line(12f, 8.5f, 12f, 13f); dot(12f, 16.2f) }
            HIcon.MEGAPHONE -> {
                poly(4f, 10f, 4f, 14f, 8f, 14f, 15f, 19f, 15f, 5f, 8f, 10f, close = true); line(8f, 14f, 8.6f, 19f)
                arc(16f, 12f, 3.5f, -45f, 90f); arc(16f, 12f, 6.5f, -40f, 80f)
            }
            HIcon.DRAIN -> {
                path { moveTo(15f, 9f); lineTo(4f, 9f); quadTo(3f, 9f, 3f, 10f); lineTo(3f, 19f); quadTo(3f, 20f, 4f, 20f); lineTo(15f, 20f); quadTo(16f, 20f, 16f, 19f); lineTo(16f, 17f) }
                poly(4f, 9f, 4f, 6.5f, 13f, 5f, 13f, 9f); line(15f, 13.5f, 21.5f, 13.5f); poly(19f, 11f, 21.5f, 13.5f, 19f, 16f); dot(12.5f, 15f, 1.1f)
            }
            HIcon.FLAG -> {
                line(5.5f, 3f, 5.5f, 21f); path(fill = true) { moveTo(5.5f, 4f); lineTo(19.5f, 4f); lineTo(16.5f, 8.5f); lineTo(19.5f, 13f); lineTo(5.5f, 13f); close() }
                s.drawCircle(Halo.ground, 1.1f * u, p(9.5f, 8.3f)); s.drawCircle(Halo.ground, 1.1f * u, p(13.3f, 8.3f))
            }
            HIcon.HOURGLASS -> {
                line(6f, 3f, 18f, 3f); line(6f, 21f, 18f, 21f)
                path { moveTo(7.5f, 3f); quadTo(7.5f, 10f, 12f, 12f); quadTo(7.5f, 14f, 7.5f, 21f) }
                path { moveTo(16.5f, 3f); quadTo(16.5f, 10f, 12f, 12f); quadTo(16.5f, 14f, 16.5f, 21f) }
                path(fill = true) { moveTo(9.3f, 19.5f); lineTo(14.7f, 19.5f); lineTo(12f, 15.5f); close() }
            }
            HIcon.GIFT -> {
                poly(4f, 11f, 4f, 20.5f, 20f, 20.5f, 20f, 11f); poly(3f, 7.5f, 21f, 7.5f, 21f, 11f, 3f, 11f, close = true); line(12f, 7.5f, 12f, 20.5f)
                path { moveTo(12f, 7.5f); quadTo(6f, 8f, 8f, 4f); quadTo(10f, 2.5f, 12f, 7.5f); quadTo(14f, 2.5f, 16f, 4f); quadTo(18f, 8f, 12f, 7.5f) }
            }
            HIcon.SEEDLING -> {
                path { moveTo(12f, 21f); quadTo(12f, 15f, 12f, 12f) }
                path { moveTo(12f, 14.5f); quadTo(5f, 15f, 4.5f, 8.5f); quadTo(11.5f, 8f, 12f, 14.5f); close() }
                path { moveTo(12f, 12f); quadTo(19f, 12.5f, 19.5f, 5.5f); quadTo(12.5f, 5f, 12f, 12f); close() }
                line(5.5f, 21f, 18.5f, 21f)
            }
            HIcon.SEAL -> {
                // A wax seal with an eye: look first, then seal.
                val inner = if (tint == Halo.ground) Halo.mint else Halo.ground
                path(fill = true) { moveTo(22.50f, 12.00f); quadTo(22.50f, 12.00f, 21.86f, 13.05f); quadTo(21.21f, 14.10f, 21.34f, 15.33f); quadTo(21.46f, 16.56f, 20.42f, 17.22f); quadTo(19.39f, 17.89f, 18.97f, 19.05f); quadTo(18.55f, 20.21f, 17.32f, 20.36f); quadTo(16.10f, 20.51f, 15.22f, 21.38f); quadTo(14.34f, 22.24f, 13.17f, 21.84f); quadTo(12.00f, 21.45f, 10.83f, 21.84f); quadTo(9.66f, 22.24f, 8.78f, 21.38f); quadTo(7.90f, 20.51f, 6.68f, 20.36f); quadTo(5.45f, 20.21f, 5.03f, 19.05f); quadTo(4.61f, 17.89f, 3.58f, 17.22f); quadTo(2.54f, 16.56f, 2.66f, 15.33f); quadTo(2.79f, 14.10f, 2.14f, 13.05f); quadTo(1.50f, 12.00f, 2.14f, 10.95f); quadTo(2.79f, 9.90f, 2.66f, 8.67f); quadTo(2.54f, 7.44f, 3.58f, 6.78f); quadTo(4.61f, 6.11f, 5.03f, 4.95f); quadTo(5.45f, 3.79f, 6.68f, 3.64f); quadTo(7.90f, 3.49f, 8.78f, 2.62f); quadTo(9.66f, 1.76f, 10.83f, 2.16f); quadTo(12.00f, 2.55f, 13.17f, 2.16f); quadTo(14.34f, 1.76f, 15.22f, 2.62f); quadTo(16.10f, 3.49f, 17.32f, 3.64f); quadTo(18.55f, 3.79f, 18.97f, 4.95f); quadTo(19.39f, 6.11f, 20.42f, 6.78f); quadTo(21.46f, 7.44f, 21.34f, 8.67f); quadTo(21.21f, 9.90f, 21.86f, 10.95f); close() }
                s.drawCircle(inner, 7.6f * u, p(12f, 12f))
                val eye = Path().apply { moveTo(6.2f * u, 12f * u); cubicTo(8.6f * u, 8.2f * u, 15.4f * u, 8.2f * u, 17.8f * u, 12f * u); cubicTo(15.4f * u, 15.8f * u, 8.6f * u, 15.8f * u, 6.2f * u, 12f * u); close() }
                s.drawPath(eye, tint, style = Stroke(width = 1.1f * u, join = StrokeJoin.Round))
                s.drawCircle(tint, 2.2f * u, p(12f, 12f))
            }
            HIcon.PIGEON -> {
                // A carrier pigeon inside a shield: it delivers exactly what it
                // was handed, and the shield is what checks it first.
                path(close = true) {
                    moveTo(4f, 10.2f); quadTo(12f, 8.2f, 20f, 10.2f)
                    cubicTo(20.4f, 15.4f, 17f, 19.6f, 12f, 21.6f)
                    cubicTo(7f, 19.6f, 3.6f, 15.4f, 4f, 10.2f)
                }
                circle(13.2f, 6.3f, 3.4f)
                poly(16.4f, 5.4f, 20.6f, 6.6f, 16.4f, 7.8f, close = true, fill = true)
                dot(13.6f, 5.8f, 0.75f)
                path { moveTo(6.9f, 12.8f); quadTo(10.5f, 11.4f, 12.7f, 15.6f) }
            }
            HIcon.SWAP -> {
                path { moveTo(7f, 8f); lineTo(19f, 8f) }; poly(15.5f, 4.5f, 19f, 8f, 15.5f, 11.5f)
                path { moveTo(17f, 16f); lineTo(5f, 16f) }; poly(8.5f, 12.5f, 5f, 16f, 8.5f, 19.5f)
            }
            HIcon.RECEIPT -> {
                path { moveTo(6f, 3f); lineTo(18f, 3f); lineTo(18f, 21f); lineTo(15.5f, 19f); lineTo(13f, 21f); lineTo(10.5f, 19f); lineTo(8f, 21f); lineTo(6f, 19f); close() }
                line(9f, 8f, 15f, 8f); line(9f, 11.5f, 15f, 11.5f); line(9f, 15f, 12.5f, 15f)
            }
            HIcon.SETTINGS -> {
                circle(12f, 12f, 3f)
                for (k in 0 until 8) { val a = Math.toRadians(k * 45.0); val c = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat(); line(12f + 6.3f * c, 12f + 6.3f * sn, 12f + 9f * c, 12f + 9f * sn) }
                circle(12f, 12f, 6.3f)
            }
            HIcon.FILTER -> { poly(3.5f, 5f, 20.5f, 5f, 14f, 12.5f, 14f, 19.5f, 10f, 21f, 10f, 12.5f, close = true) }
            HIcon.DOWNLOAD -> { line(12f, 3.5f, 12f, 14.5f); poly(7.5f, 10f, 12f, 14.5f, 16.5f, 10f); path { moveTo(4f, 15.5f); lineTo(4f, 19f); quadTo(4f, 20.5f, 5.5f, 20.5f); lineTo(18.5f, 20.5f); quadTo(20f, 20.5f, 20f, 19f); lineTo(20f, 15.5f) } }
            HIcon.TAG -> { path { moveTo(3.5f, 4.5f); lineTo(11f, 4.5f); lineTo(20.5f, 14f); lineTo(13f, 21.5f); lineTo(3.5f, 12f); close() }; dot(8f, 9f, 1.5f) }
            HIcon.NOTE -> { path { moveTo(5f, 4f); lineTo(15f, 4f); lineTo(19f, 8f); lineTo(19f, 20f); lineTo(5f, 20f); close() }; path { moveTo(15f, 4f); lineTo(15f, 8f); lineTo(19f, 8f) }; line(8.5f, 12f, 15.5f, 12f); line(8.5f, 15.5f, 13.5f, 15.5f) }
            HIcon.CALENDAR -> { path { moveTo(4f, 6.5f); lineTo(20f, 6.5f); lineTo(20f, 20f); lineTo(4f, 20f); close() }; line(4f, 10.5f, 20f, 10.5f); line(8f, 3.5f, 8f, 8f); line(16f, 3.5f, 16f, 8f); dot(9f, 15f, 1.2f); dot(15f, 15f, 1.2f) }
            HIcon.PDF -> { path { moveTo(6f, 3.5f); lineTo(14f, 3.5f); lineTo(18.5f, 8f); lineTo(18.5f, 20.5f); lineTo(6f, 20.5f); close() }; path { moveTo(14f, 3.5f); lineTo(14f, 8f); lineTo(18.5f, 8f) }; path { moveTo(8.5f, 17f); lineTo(8.5f, 11.5f); lineTo(10.5f, 11.5f); quadTo(12.5f, 11.5f, 12.5f, 13.5f); quadTo(12.5f, 15.5f, 10.5f, 15.5f); lineTo(8.5f, 15.5f) } }
            HIcon.LOCK -> {
                path { moveTo(6f, 11f); lineTo(18f, 11f); quadTo(19.5f, 11f, 19.5f, 12.5f); lineTo(19.5f, 19.5f); quadTo(19.5f, 21f, 18f, 21f); lineTo(6f, 21f); quadTo(4.5f, 21f, 4.5f, 19.5f); lineTo(4.5f, 12.5f); quadTo(4.5f, 11f, 6f, 11f) }
                path { moveTo(8.5f, 11f); lineTo(8.5f, 7f); arcTo(12f, 7f, 3.5f, 180f, 180f); lineTo(15.5f, 11f) }
                dot(12f, 16f, 1.4f)
            }
            HIcon.COINS -> {
                circle(9f, 9f, 5.5f)
                path { moveTo(12.5f, 10.2f); arcTo(15f, 15f, 5.5f, 235f, 305f) }
                line(6.5f, 9f, 11.5f, 9f)
            }
            HIcon.QR -> {
                poly(4f, 4f, 10f, 4f, 10f, 10f, 4f, 10f, close = true); dot(7f, 7f, 1.2f)
                poly(14f, 4f, 20f, 4f, 20f, 10f, 14f, 10f, close = true); dot(17f, 7f, 1.2f)
                poly(4f, 14f, 10f, 14f, 10f, 20f, 4f, 20f, close = true); dot(7f, 17f, 1.2f)
                line(14f, 14f, 16.5f, 14f); line(20f, 14f, 20f, 16.5f); line(14f, 17.5f, 14f, 20f); line(17.5f, 20f, 20f, 20f); dot(17f, 17f, 1.2f)
            }
            // A crescent: one circle with a bite taken out of it, drawn as two arcs so the
            // stroke reads at eighteen dp instead of turning into a grey blob.
            HIcon.MOON -> path {
                moveTo(16.2f, 4.6f)
                arcTo(12f, 12f, 8.5f, -60f, 240f)
                arcTo(16.6f, 12f, 8.2f, 118f, -236f)
                close()
            }
            HIcon.SUN -> {
                circle(12f, 12f, 4.6f)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    val c = kotlin.math.cos(a).toFloat(); val n = kotlin.math.sin(a).toFloat()
                    line(12f + c * 7f, 12f + n * 7f, 12f + c * 9.5f, 12f + n * 9.5f)
                }
            }
            HIcon.PALETTE -> {
                path { moveTo(12f, 3f); arcTo(12f, 12f, 9f, 270f, 300f); quadTo(12.5f, 15f, 14.5f, 15.5f); quadTo(17f, 16f, 16.5f, 18.5f); quadTo(16f, 21f, 12f, 21f) }
                dot(8f, 9.5f, 1.4f); dot(12f, 7f, 1.4f); dot(16f, 9.5f, 1.4f); dot(7.5f, 14.5f, 1.4f)
            }
            // Arrow down and tray, 15 points like the rest of the row.
            HIcon.RECEIVE -> { line(12f, 4.5f, 12f, 15.5f); poly(7.5f, 11f, 12f, 15.5f, 16.5f, 11f); line(4.5f, 19.5f, 19.5f, 19.5f) }
            HIcon.PASTE -> {
                path { moveTo(9f, 5f); lineTo(6.5f, 5f); quadTo(5f, 5f, 5f, 6.5f); lineTo(5f, 19.5f); quadTo(5f, 21f, 6.5f, 21f); lineTo(17.5f, 21f); quadTo(19f, 21f, 19f, 19.5f); lineTo(19f, 6.5f); quadTo(19f, 5f, 17.5f, 5f); lineTo(15f, 5f) }
                poly(9f, 3.5f, 15f, 3.5f, 15f, 6.5f, 9f, 6.5f, close = true)
                line(8.5f, 12f, 15.5f, 12f); line(8.5f, 16f, 13.5f, 16f)
            }
            HIcon.SCAN -> {
                path { moveTo(4f, 9f); lineTo(4f, 6f); quadTo(4f, 4f, 6f, 4f); lineTo(9f, 4f) }
                path { moveTo(15f, 4f); lineTo(18f, 4f); quadTo(20f, 4f, 20f, 6f); lineTo(20f, 9f) }
                path { moveTo(20f, 15f); lineTo(20f, 18f); quadTo(20f, 20f, 18f, 20f); lineTo(15f, 20f) }
                path { moveTo(9f, 20f); lineTo(6f, 20f); quadTo(4f, 20f, 4f, 18f); lineTo(4f, 15f) }
                line(7f, 12f, 17f, 12f)
            }
            HIcon.SHARE -> { line(12f, 14f, 12f, 3.5f); poly(8f, 7.5f, 12f, 3.5f, 16f, 7.5f); path { moveTo(7f, 11f); lineTo(5.5f, 11f); lineTo(5.5f, 20f); lineTo(18.5f, 20f); lineTo(18.5f, 11f); lineTo(17f, 11f) } }
            HIcon.UNLOCK -> {
                path { moveTo(6f, 11f); lineTo(18f, 11f); quadTo(19.5f, 11f, 19.5f, 12.5f); lineTo(19.5f, 19.5f); quadTo(19.5f, 21f, 18f, 21f); lineTo(6f, 21f); quadTo(4.5f, 21f, 4.5f, 19.5f); lineTo(4.5f, 12.5f); quadTo(4.5f, 11f, 6f, 11f) }
                path { moveTo(8.5f, 11f); lineTo(8.5f, 7f); arcTo(12f, 7f, 3.5f, 180f, 180f); lineTo(15.5f, 8.2f) }
                dot(12f, 16f, 1.4f)
            }
            HIcon.BLOCK -> { path { moveTo(8f, 3f); lineTo(16f, 3f); lineTo(21f, 8f); lineTo(21f, 16f); lineTo(16f, 21f); lineTo(8f, 21f); lineTo(3f, 16f); lineTo(3f, 8f); close() }; line(7.5f, 12f, 16.5f, 12f) }
            HIcon.SHIELD_LOCK -> {
                path { moveTo(12f, 2.5f); lineTo(20f, 5.5f); lineTo(20f, 11.5f); quadTo(20f, 18f, 12f, 21.5f); quadTo(4f, 18f, 4f, 11.5f); lineTo(4f, 5.5f); close() }
                poly(9f, 11.5f, 15f, 11.5f, 15f, 16f, 9f, 16f, close = true); arc(12f, 11.5f, 2f, 180f, 180f); dot(12f, 13.8f, 0.8f)
            }
            HIcon.CHECK -> poly(5f, 12.5f, 10f, 17.5f, 19.5f, 7f)
            HIcon.WARNING -> { path { moveTo(12f, 3.5f); lineTo(21.5f, 20f); lineTo(2.5f, 20f); close() }; line(12f, 9.5f, 12f, 14f); dot(12f, 17f, 1.1f) }
            HIcon.PEN -> { path { moveTo(4f, 20f); lineTo(8f, 20f); lineTo(19.5f, 8.5f); lineTo(15.5f, 4.5f); lineTo(4f, 16f); close() }; line(13f, 7f, 17f, 11f) }
            // The paper plane was 18 points and next to Receive, which takes 15, looked
            // like another size. Now it sits in the same box as the others.
            HIcon.SEND -> { path { moveTo(4.5f, 11.5f); lineTo(19.5f, 4.5f); lineTo(14.5f, 19.5f); lineTo(11.6f, 13f); close() }; line(11.6f, 13f, 19.5f, 4.5f) }
            HIcon.SIGN -> {
                path { moveTo(14f, 3f); lineTo(7f, 3f); quadTo(5.5f, 3f, 5.5f, 4.5f); lineTo(5.5f, 19.5f); quadTo(5.5f, 21f, 7f, 21f); lineTo(17f, 21f); quadTo(18.5f, 21f, 18.5f, 19.5f); lineTo(18.5f, 7.5f); close() }
                poly(14f, 3f, 14f, 7.5f, 18.5f, 7.5f); poly(8.5f, 14.5f, 11f, 17f, 15.5f, 11.5f)
            }
            HIcon.LOGIN -> { path { moveTo(13f, 4f); lineTo(19f, 4f); quadTo(20.5f, 4f, 20.5f, 5.5f); lineTo(20.5f, 18.5f); quadTo(20.5f, 20f, 19f, 20f); lineTo(13f, 20f) }; line(3f, 12f, 14f, 12f); poly(10f, 8f, 14f, 12f, 10f, 16f) }
            HIcon.COPY -> {
                path { moveTo(10f, 9f); lineTo(19f, 9f); quadTo(20.5f, 9f, 20.5f, 10.5f); lineTo(20.5f, 19f); quadTo(20.5f, 20.5f, 19f, 20.5f); lineTo(10f, 20.5f); quadTo(8.5f, 20.5f, 8.5f, 19f); lineTo(8.5f, 10.5f); quadTo(8.5f, 9f, 10f, 9f) }
                path { moveTo(5.5f, 15f); lineTo(5f, 15f); quadTo(3.5f, 15f, 3.5f, 13.5f); lineTo(3.5f, 5f); quadTo(3.5f, 3.5f, 5f, 3.5f); lineTo(13.5f, 3.5f); quadTo(15f, 3.5f, 15f, 5f); lineTo(15f, 5.5f) }
            }
            HIcon.EXTERNAL -> { path { moveTo(18f, 13.5f); lineTo(18f, 19f); quadTo(18f, 20f, 17f, 20f); lineTo(5f, 20f); quadTo(4f, 20f, 4f, 19f); lineTo(4f, 7f); quadTo(4f, 6f, 5f, 6f); lineTo(10.5f, 6f) }; line(10.5f, 13.5f, 20f, 4f); poly(14f, 4f, 20f, 4f, 20f, 10f) }
            HIcon.CHEVRON_RIGHT -> poly(9f, 6f, 15f, 12f, 9f, 18f)
            HIcon.CHEVRON_DOWN -> poly(6f, 9f, 12f, 15f, 18f, 9f)
            HIcon.CHEVRON_LEFT -> poly(15f, 6f, 9f, 12f, 15f, 18f)
            HIcon.MORE -> { dot(6f, 12f, 1.6f); dot(12f, 12f, 1.6f); dot(18f, 12f, 1.6f) }
            HIcon.CLOSE -> { line(6.5f, 6.5f, 17.5f, 17.5f); line(17.5f, 6.5f, 6.5f, 17.5f) }
            HIcon.SEARCH -> { circle(10.5f, 10.5f, 6.2f); line(15.1f, 15.1f, 20.5f, 20.5f) }
            // Contactless: the waves everyone already reads as "hold it here".
            HIcon.NFC -> {
                dot(6.4f, 12f, 1.5f)
                arc(6.4f, 12f, 4.2f, -60f, 120f)
                arc(6.4f, 12f, 8.0f, -60f, 120f)
                arc(6.4f, 12f, 11.8f, -60f, 120f)
            }
            HIcon.HOLD -> { circle(12f, 12f, 8f); circle(12f, 12f, 3.2f, fill = true) }
            // The one gesture everybody already knows for "keep an eye on this".
            // Drawn rather than borrowed: the whole icon set is one hand.
            HIcon.STAR -> poly(12.0f, 2.8f, 14.3f, 8.8f, 20.7f, 9.2f, 15.7f, 13.2f, 17.4f, 19.4f, 12.0f, 15.9f, 6.6f, 19.4f, 8.3f, 13.2f, 3.3f, 9.2f, 9.7f, 8.8f, close = true)
            HIcon.STAR_FILLED -> poly(12.0f, 2.8f, 14.3f, 8.8f, 20.7f, 9.2f, 15.7f, 13.2f, 17.4f, 19.4f, 12.0f, 15.9f, 6.6f, 19.4f, 8.3f, 13.2f, 3.3f, 9.2f, 9.7f, 8.8f, close = true, fill = true)
            HIcon.CHART -> {
                path { moveTo(3f, 18f); lineTo(8.5f, 11.5f); lineTo(12.5f, 15f); lineTo(21f, 6f) }
                path { moveTo(16f, 6f); lineTo(21f, 6f); lineTo(21f, 11f) }
            }
            HIcon.CHART_DOWN -> {
                path { moveTo(3f, 6f); lineTo(8.5f, 12.5f); lineTo(12.5f, 9f); lineTo(21f, 18f) }
                path { moveTo(16f, 18f); lineTo(21f, 18f); lineTo(21f, 13f) }
            }
            HIcon.AGENT -> {
                path(close = true) { moveTo(7f, 8.5f); lineTo(17f, 8.5f); quadTo(19.5f, 8.5f, 19.5f, 11f); lineTo(19.5f, 17f); quadTo(19.5f, 19.5f, 17f, 19.5f); lineTo(7f, 19.5f); quadTo(4.5f, 19.5f, 4.5f, 17f); lineTo(4.5f, 11f); quadTo(4.5f, 8.5f, 7f, 8.5f) }
                path { moveTo(12f, 8.5f); lineTo(12f, 5.2f) }
                dot(12f, 4f, 1.4f)
                dot(9.2f, 13.2f, 1.5f); dot(14.8f, 13.2f, 1.5f)
                path { moveTo(9f, 16.6f); quadTo(12f, 18.2f, 15f, 16.6f) }
            }
            HIcon.BRIDGE -> {
                // It was a bridge: arch, piers and road, drawing the word, not the thing. At twenty-four
                // points it read "bridge over a river", and what happens here is money crossing to another
                // chain and something coming back: two arcs and two tips, one going, one returning. They
                // were two colors, cyan and amber, but the eight home circles are neutral on purpose: one
                // lit icon turns the accent into decoration. The two directions still differ within one
                // tint: the returning one is dimmer.
                path { moveTo(4.5f, 12f); quadTo(12f, 3.8f, 19.5f, 12f) }
                poly(16.1f, 10.8f, 19.5f, 12f, 18.6f, 8.5f)
                two(tint.copy(alpha = 0.5f)) {
                    path { moveTo(19.5f, 13.4f); quadTo(12f, 21.6f, 4.5f, 13.4f) }
                    poly(7.9f, 14.6f, 4.5f, 13.4f, 5.4f, 16.9f)
                }
            }
            HIcon.GEM -> {
                path(fill = true) { moveTo(12f, 2.5f); lineTo(21.5f, 12f); lineTo(12f, 21.5f); lineTo(2.5f, 12f); close() }
                s.drawLine(Halo.ground.copy(alpha = 0.55f), p(12f, 2.5f), p(12f, 21.5f), 0.9f * u)
                s.drawLine(Halo.ground.copy(alpha = 0.55f), p(2.5f, 12f), p(21.5f, 12f), 0.9f * u)
            }
            HIcon.WALLET -> {
                path { moveTo(4f, 7f); lineTo(18f, 7f); quadTo(20.5f, 7f, 20.5f, 9.5f); lineTo(20.5f, 17.5f); quadTo(20.5f, 20f, 18f, 20f); lineTo(6f, 20f); quadTo(3.5f, 20f, 3.5f, 17.5f); lineTo(3.5f, 7.5f); quadTo(3.5f, 5f, 6f, 5f); lineTo(17f, 5f) }
                line(14.5f, 13.5f, 20.5f, 13.5f); dot(15.5f, 13.5f, 1.2f)
            }
            HIcon.HISTORY -> { arc(12f, 12f, 8f, -150f, 300f); poly(3f, 5f, 3.8f, 10f, 8.5f, 9f); line(12f, 8f, 12f, 12.5f); line(12f, 12.5f, 15.5f, 14.5f) }
            HIcon.REFRESH -> { arc(12f, 12f, 8f, -40f, 300f); line(18.1f, 6.9f, 18.6f, 2.6f); line(18.1f, 6.9f, 22.2f, 7.6f) }
            // Two whole people, one in front: the list of them, not the exchange.
            HIcon.PEOPLE -> { circle(8.5f, 8.5f, 3.2f); path { moveTo(2.5f, 20f); quadTo(2.5f, 14.2f, 8.5f, 14.2f); quadTo(14.5f, 14.2f, 14.5f, 20f) }; circle(16.6f, 7.4f, 2.6f); path { moveTo(15.2f, 12.4f); quadTo(21.6f, 12.6f, 21.6f, 19f) } }
            HIcon.CONTACTS -> { circle(9f, 8.5f, 3.5f); path { moveTo(3f, 20f); quadTo(3f, 14f, 9f, 14f); quadTo(15f, 14f, 15f, 20f) }; arc(16f, 8.5f, 3.5f, -80f, 160f); path { moveTo(17f, 14.2f); quadTo(21f, 15f, 21f, 20f) } }
            HIcon.INFO -> { circle(12f, 12f, 8.5f); line(12f, 11f, 12f, 16.5f); dot(12f, 8f, 1.1f) }
            HIcon.FINGERPRINT -> {
                arc(12f, 13f, 8.5f, 200f, 140f); arc(12f, 13f, 5.5f, 190f, 200f); arc(12f, 13f, 2.5f, 180f, 270f)
                path { moveTo(12f, 13f); lineTo(12f, 21f) }; arc(12f, 13f, 8.5f, 20f, 50f)
            }
        }
    }
}
