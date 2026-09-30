package com.clearsign.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import java.util.Locale

/**
 * Bubble settings: face, panel rows, coin to watch, size, position. In the app's prefs so
 * the service and the settings page read the same values.
 */
object CompanionPrefs {
    private const val P = "apex_companion"

    /** The bubble's face. `ROTATE` alternates the open coin and the budget, four seconds each. */
    enum class Face { ROTATE, AGENT, HEALTH, TOTAL, COIN, BUDGET }

    private fun p(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun face(ctx: Context): Face = runCatching { Face.valueOf(p(ctx).getString("face", "ROTATE")!!) }.getOrDefault(Face.ROTATE)
    fun setFace(ctx: Context, f: Face) = p(ctx).edit().putString("face", f.name).apply()
    fun show(ctx: Context, what: String): Boolean = p(ctx).getBoolean("show_$what", what != "coin")
    fun setShow(ctx: Context, what: String, on: Boolean) = p(ctx).edit().putBoolean("show_$what", on).apply()
    fun coin(ctx: Context): String? = p(ctx).getString("coin", null)
    fun setCoin(ctx: Context, mint: String?) = p(ctx).edit().putString("coin", mint).apply()
    fun size(ctx: Context): Int = p(ctx).getInt("size", 54)
    fun setSize(ctx: Context, dp: Int) = p(ctx).edit().putInt("size", dp).apply()

    /**
     * Last drag position, saved so a service restart (any settings change) keeps it.
     * −1 means never dragged; the service picks.
     */
    fun spotX(ctx: Context): Int = p(ctx).getInt("x", -1)
    fun spotY(ctx: Context): Int = p(ctx).getInt("y", -1)
    fun setSpot(ctx: Context, x: Int, y: Int) = p(ctx).edit().putInt("x", x).putInt("y", y).apply()

    /**
     * Numbers in the bubble's notification. Android needs the notification to keep the
     * service alive; this only adds the numbers. Off by default.
     */
    fun notif(ctx: Context): Boolean = p(ctx).getBoolean("notif", false)
    fun setNotif(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("notif", on).apply()

    /** Come back on its own when the app opens. */
    fun autoStart(ctx: Context): Boolean = p(ctx).getBoolean("auto", false)
    fun setAutoStart(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("auto", on).apply()

    /** What the face needs to draw itself, gathered by whoever has the data. */
    data class FaceData(
        val score: Int?,
        val openPositions: Int?,
        val totalText: String?,
        val coinSymbol: String?,
        val coinChange: Double?,
        val trading: Boolean,
        /** The budget now, already shortened ("17.4 $"), and how much it moved since you put it in. */
        val budgetText: String? = null,
        val budgetChange: Double? = null,
    )

    /**
     * The face as a bitmap, shared by the bubble and the preview. Depth is painted (radial
     * gradient, highlight top left, shadow below, rim lit on the light side) instead of real 3D,
     * which would keep an OpenGL surface and the GPU awake over every app. Redrawn only when the
     * numbers change.
     */
    fun faceBitmap(px: Int, face: Face, d: FaceData): Bitmap {
        val p = Halo.palette
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = px / 2f
        // Slightly above center, leaving room below for the shadow.
        val cy = px * 0.47f
        val r = px * 0.44f
        val body0 = p.card.toArgb()

        // --- the shadow below -----------------------------------------------
        // Fainter on light themes and tucked under the ball: a uniform dark wash read as a
        // dirty ring around a pale ball.
        val light = p.ground.relativeLuminance() > 0.5
        val shadowY = cy + r * 0.34f
        val shadowR = r * 0.92f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, shadowY, shadowR,
                intArrayOf(AColor.argb(if (light) 55 else 110, 0, 0, 0), AColor.argb(0, 0, 0, 0)),
                floatArrayOf(0.66f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(cx, shadowY, shadowR, shadow)

        // --- the glow, while it works -----------------------------------------
        if (d.trading) {
            val a = p.accent.toArgb()
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx, cy, r * 1.14f,
                    intArrayOf(AColor.argb(0, AColor.red(a), AColor.green(a), AColor.blue(a)), AColor.argb(70, AColor.red(a), AColor.green(a), AColor.blue(a)), AColor.argb(0, AColor.red(a), AColor.green(a), AColor.blue(a))),
                    floatArrayOf(0.80f, 0.92f, 1f), Shader.TileMode.CLAMP,
                )
            }
            c.drawCircle(cx, cy, r * 1.14f, glow)
        }

        // --- the sphere's body --------------------------------------------------------------
        // On the card color, not the ground's: black on black leaves the painted light nothing to show.
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx - r * 0.35f, cy - r * 0.42f, r * 1.75f,
                intArrayOf(lift(body0, 0.22f), body0, sink(body0, 0.55f)),
                floatArrayOf(0f, 0.50f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(cx, cy, r, body)

        // The highlight. Any larger and it washes out the mark.
        val spec = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx - r * 0.38f, cy - r * 0.54f, r * 0.50f,
                intArrayOf(AColor.argb(40, 255, 255, 255), AColor.argb(0, 255, 255, 255)),
                null, Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(cx - r * 0.38f, cy - r * 0.54f, r * 0.50f, spec)

        // The rim: light at the top where the light catches it, dark at the bottom.
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = r * 0.05f
            shader = LinearGradient(
                0f, cy - r, 0f, cy + r,
                intArrayOf(AColor.argb(120, 255, 255, 255), AColor.argb(14, 255, 255, 255), AColor.argb(110, 0, 0, 0)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(cx, cy, r - r * 0.025f, rim)

        // --- the ring with the numbers ----------------------------------------
        val w = r * 0.11f
        val inset = r * 0.17f
        val rect = RectF(cx - r + inset, cy - r + inset, cx + r - inset, cy + r - inset)
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND }
        val st = p.stroke.toArgb()
        arc.color = AColor.argb(150, AColor.red(st), AColor.green(st), AColor.blue(st))
        c.drawArc(rect, 0f, 360f, false, arc)

        /**
         * The brand gradient along the ring, violet to blue to green. Only where the color is not
         * already a message: low health and a losing coin stay red and amber, flat.
         */
        fun brand() {
            arc.shader = android.graphics.SweepGradient(
                cx, cy,
                intArrayOf(0xFF9945FF.toInt(), 0xFF4CC9FF.toInt(), 0xFF14F195.toInt(), 0xFF9945FF.toInt()),
                floatArrayOf(0f, 0.34f, 0.67f, 1f),
            ).apply { setLocalMatrix(android.graphics.Matrix().apply { postRotate(-90f, cx, cy) }) }
        }
        fun flat(color: Int) { arc.shader = null; arc.color = color }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.ink.toArgb(); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.muted.toArgb(); textAlign = Paint.Align.CENTER }
        text.setShadowLayer(r * 0.08f, 0f, r * 0.03f, AColor.argb(150, 0, 0, 0))

        /** The status dot, at four o'clock on the ring: lit while it works. */
        fun statusDot(on: Boolean) {
            val ang = Math.toRadians(45.0)
            val rr = r - inset
            val dx = cx + (rr * kotlin.math.cos(ang)).toFloat()
            val dy = cy + (rr * kotlin.math.sin(ang)).toFloat()
            val dot = Paint(Paint.ANTI_ALIAS_FLAG)
            dot.color = sink(body0, 0.5f)
            c.drawCircle(dx, dy, r * 0.16f, dot)
            dot.color = (if (on) p.accent else p.muted).toArgb()
            c.drawCircle(dx, dy, r * 0.10f, dot)
            if (on) {
                dot.color = AColor.argb(90, 255, 255, 255)
                c.drawCircle(dx - r * 0.03f, dy - r * 0.03f, r * 0.04f, dot)
            }
        }

        /**
         * Shown when there is no number: the Agent tab's robot, on the same 24-unit grid.
         * The bubble is the agent, so not the app's mark.
         */
        fun mark(alpha: Int) {
            val s = r * 1.55f
            val u = s / 24f
            val ox = cx - s / 2f
            val oy = cy - s / 2f
            fun x(v: Float) = ox + v * u
            fun y(v: Float) = oy + v * u

            val head = android.graphics.RectF(x(4.5f), y(8.5f), x(19.5f), y(19.5f))
            val smile = android.graphics.Path().apply {
                moveTo(x(9f), y(16.6f)); quadTo(x(12f), y(18.2f), x(15f), y(16.6f))
            }
            // A dark blur under everything, so the robot still reads on a light theme.
            val under = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = u * 3.4f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                color = AColor.argb(if (alpha < 255) 90 else 120, 0, 0, 0)
                maskFilter = android.graphics.BlurMaskFilter(r * 0.10f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            c.drawRoundRect(head, u * 2.5f, u * 2.5f, under)
            c.drawLine(x(12f), y(8.5f), x(12f), y(5.2f), under)

            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = u * 1.7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                shader = LinearGradient(
                    cx, y(19.5f), cx, y(4f),
                    intArrayOf(p.accent.toArgb(), p.accent2.toArgb()), null, Shader.TileMode.CLAMP,
                )
            }
            c.drawRoundRect(head, u * 2.5f, u * 2.5f, ink)
            c.drawLine(x(12f), y(8.5f), x(12f), y(5.2f), ink)
            c.drawPath(smile, ink)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    cx, y(19.5f), cx, y(4f),
                    intArrayOf(p.accent.toArgb(), p.accent2.toArgb()), null, Shader.TileMode.CLAMP,
                )
            }
            c.drawCircle(x(12f), y(4f), u * 1.4f, fill)
            c.drawCircle(x(9.2f), y(13.2f), u * 1.5f, fill)
            c.drawCircle(x(14.8f), y(13.2f), u * 1.5f, fill)
        }

        when (face) {
            Face.AGENT, Face.ROTATE -> {
                if (d.trading) brand() else flat(AColor.argb(0, 0, 0, 0))
                c.drawArc(rect, -90f, 360f, false, arc)
                val n = d.openPositions
                if (d.trading && n != null) {
                    // The number is the biggest thing in the bubble, and it lights from top to
                    // bottom, white to brand color.
                    text.textSize = r * 0.92f
                    val top = cy - text.textSize * 0.55f
                    text.shader = LinearGradient(0f, top, 0f, top + text.textSize, intArrayOf(0xFFFFFFFF.toInt(), 0xFF4DFFD0.toInt()), null, Shader.TileMode.CLAMP)
                    c.drawText(n.toString(), cx, cy + text.textSize * 0.34f, text)
                    text.shader = null
                } else {
                    // Idle: the robot, not a dash.
                    mark(if (d.trading) 255 else 190)
                }
                statusDot(d.trading)
            }
            Face.HEALTH -> {
                val s = d.score
                val tint = when { s == null -> p.muted; s >= 80 -> p.accent; s >= 50 -> p.amber; else -> p.red }.toArgb()
                if (s != null && s >= 80) brand() else flat(tint)
                c.drawArc(rect, -90f, if (s != null) 360f * s.coerceIn(0, 100) / 100f else 0f, false, arc)
                text.textSize = r * 0.78f
                c.drawText(s?.toString() ?: "–", cx, cy + text.textSize * 0.35f, text)
            }
            Face.TOTAL -> {
                brand(); c.drawArc(rect, -90f, 360f, false, arc)
                val t = d.totalText ?: "…"
                text.textSize = if (t.length > 6) r * 0.44f else r * 0.58f
                c.drawText(t, cx, cy + text.textSize * 0.35f, text)
            }
            Face.COIN -> {
                val ch = d.coinChange
                val tint = (if (ch == null) p.muted else if (ch >= 0) p.accent else p.red).toArgb()
                if (ch != null && ch >= 0) brand() else flat(tint)
                c.drawArc(rect, -90f, 360f, false, arc)
                text.textSize = r * 0.50f
                c.drawText(d.coinSymbol?.take(5) ?: "?", cx, cy - r * 0.04f, text)
                small.textSize = r * 0.36f; small.color = tint; small.isFakeBoldText = true
                c.drawText(ch?.let { String.format(Locale.ROOT, "%+.1f%%", it) } ?: "", cx, cy + r * 0.44f, small)
            }
            Face.BUDGET -> {
                val ch = d.budgetChange
                val tint = (if (ch == null) p.accent2 else if (ch >= 0) p.accent else p.red).toArgb()
                if (ch == null || ch >= 0) brand() else flat(tint)
                c.drawArc(rect, -90f, 360f, false, arc)
                val t = d.budgetText ?: "…"
                text.textSize = if (t.length > 6) r * 0.42f else r * 0.54f
                c.drawText(t, cx, cy - r * 0.02f, text)
                small.textSize = r * 0.34f; small.color = tint; small.isFakeBoldText = true
                c.drawText(ch?.let { String.format(Locale.ROOT, "%+.1f%%", it) } ?: "", cx, cy + r * 0.46f, small)
            }
        }
        return bmp
    }

    /** Blend toward white by [k]. */
    private fun lift(c: Int, k: Float) = AColor.rgb(
        (AColor.red(c) + (255 - AColor.red(c)) * k).toInt().coerceIn(0, 255),
        (AColor.green(c) + (255 - AColor.green(c)) * k).toInt().coerceIn(0, 255),
        (AColor.blue(c) + (255 - AColor.blue(c)) * k).toInt().coerceIn(0, 255),
    )

    /** Darken toward black by [k]. */
    private fun sink(c: Int, k: Float) = AColor.rgb(
        (AColor.red(c) * (1 - k)).toInt().coerceIn(0, 255),
        (AColor.green(c) * (1 - k)).toInt().coerceIn(0, 255),
        (AColor.blue(c) * (1 - k)).toInt().coerceIn(0, 255),
    )
}
