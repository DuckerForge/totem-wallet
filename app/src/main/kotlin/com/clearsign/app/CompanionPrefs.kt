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
 * What the bubble shows, chosen by the person: the face, the rows of the open panel, the
 * coin to watch, the size, where it was left. In the app's prefs so the service and the
 * page read the same thing.
 */
object CompanionPrefs {
    private const val P = "apex_companion"

    /**
     * What the small circle says. `ROTATE` is not a face but two in turn, the open coin and the
     * budget, four seconds each: how a bubble the size of a fingernail says both things worth
     * knowing instead of hiding one.
     */
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
     * Where you left it. It was not saved: any settings change restarted the service and the
     * bubble was back top left. Something that sits over every app must stay where you put it.
     * −1 means never dragged, and the service decides.
     */
    fun spotX(ctx: Context): Int = p(ctx).getInt("x", -1)
    fun spotY(ctx: Context): Int = p(ctx).getInt("y", -1)
    fun setSpot(ctx: Context, x: Int, y: Int) = p(ctx).edit().putInt("x", x).putInt("y", y).apply()

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
     * The face as a bitmap, the same drawing for the bubble and the preview. It looks like a
     * sphere and costs nothing: real 3D would mean an OpenGL surface over every app, the GPU
     * awake forever for a small circle. Depth is painted (radial gradient toward the light, a
     * highlight top left, shadow gathering below, the rim lit only where the light hits) and
     * redrawn only when the numbers change. At rest it is a still bitmap.
     */
    fun faceBitmap(px: Int, face: Face, d: FaceData): Bitmap {
        val p = Halo.palette
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = px / 2f
        // The sphere sits a hair above center and leaves room below for its shadow:
        // without one, a ball over another app rests on nothing.
        val cy = px * 0.47f
        val r = px * 0.44f
        val body0 = p.card.toArgb()

        // --- l'ombra sotto --------------------------------------------------
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy + r * 0.32f, r * 1.05f,
                intArrayOf(AColor.argb(120, 0, 0, 0), AColor.argb(0, 0, 0, 0)),
                floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(cx, cy + r * 0.32f, r * 1.05f, shadow)

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
        // On the card color, not the ground's: it was a black ball on black grounds, and the painted
        // light had nothing to light.
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx - r * 0.35f, cy - r * 0.42f, r * 1.75f,
                intArrayOf(lift(body0, 0.22f), body0, sink(body0, 0.55f)),
                floatArrayOf(0f, 0.50f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(cx, cy, r, body)

        // The highlight, where the light hits first. It was twice this and fell right
        // on the V's left arm, which washed out.
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

        // --- l'anello coi numeri ----------------------------------------------
        val w = r * 0.11f
        val inset = r * 0.17f
        val rect = RectF(cx - r + inset, cy - r + inset, cx + r - inset, cy + r - inset)
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND }
        val st = p.stroke.toArgb()
        arc.color = AColor.argb(150, AColor.red(st), AColor.green(st), AColor.blue(st))
        c.drawArc(rect, 0f, 360f, false, arc)

        /**
         * The brand gradient along the ring, violet to cyan to mint. Only where the color is not
         * already a message: low health and a losing coin stay red and amber, flat.
         */
        fun brand() {
            arc.shader = android.graphics.SweepGradient(
                cx, cy,
                intArrayOf(0xFF9524F3.toInt(), 0xFF4CC9FF.toInt(), 0xFF4DFFD0.toInt(), 0xFF9524F3.toInt()),
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
         * The app's mark, a V from violet to cyan, for when there is no number to say. Always full,
         * not half when still: it was the mark vanishing under the light, not the light being too
         * much. A dark shadow under it, so it stands out on light themes too.
         */
        fun mark(alpha: Int) {
            val path = android.graphics.Path().apply {
                moveTo(cx - r * 0.36f, cy - r * 0.28f); lineTo(cx, cy + r * 0.32f); lineTo(cx + r * 0.36f, cy - r * 0.28f)
            }
            val under = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = r * 0.26f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                color = AColor.argb(if (alpha < 255) 90 else 120, 0, 0, 0)
                maskFilter = android.graphics.BlurMaskFilter(r * 0.10f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            c.drawPath(path, under)
            val v = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = r * 0.15f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                shader = LinearGradient(cx - r * 0.3f, cy + r * 0.3f, cx + r * 0.3f, cy - r * 0.3f, intArrayOf(0xFF9524F3.toInt(), 0xFF4CC9FF.toInt()), null, Shader.TileMode.CLAMP)
                this.alpha = 255
            }
            c.drawPath(path, v)
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
                    // Idle: the app's mark, not a dash.
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

    /** Verso la luce. */
    private fun lift(c: Int, k: Float) = AColor.rgb(
        (AColor.red(c) + (255 - AColor.red(c)) * k).toInt().coerceIn(0, 255),
        (AColor.green(c) + (255 - AColor.green(c)) * k).toInt().coerceIn(0, 255),
        (AColor.blue(c) + (255 - AColor.blue(c)) * k).toInt().coerceIn(0, 255),
    )

    /** Verso l'ombra. */
    private fun sink(c: Int, k: Float) = AColor.rgb(
        (AColor.red(c) * (1 - k)).toInt().coerceIn(0, 255),
        (AColor.green(c) * (1 - k)).toInt().coerceIn(0, 255),
        (AColor.blue(c) * (1 - k)).toInt().coerceIn(0, 255),
    )
}
