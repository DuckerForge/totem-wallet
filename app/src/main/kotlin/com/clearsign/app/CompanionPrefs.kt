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
 * What the bubble shows, chosen by the person.
 *
 * The face (what the small circle says at a glance), the rows of the open
 * panel, the coin to watch, the size, and where it was left on the screen.
 * Kept in the app's prefs so the service and the page read the same thing.
 */
object CompanionPrefs {
    private const val P = "apex_companion"

    /**
     * Cosa dice il cerchietto.
     *
     * `ROTATE` non e' una faccia, e' due facce a turno: la moneta aperta e la
     * paghetta, quattro secondi ciascuna. E' il modo in cui una bolla grande
     * come un'unghia riesce a dire le due cose che si vogliono sapere davvero,
     * invece di farne scegliere una e nascondere l'altra.
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
     * Dove l'hai lasciata.
     *
     * Prima non si salvava: la trascinavi dove ti serviva, e alla prima
     * modifica di una qualsiasi impostazione (che fa ripartire il servizio) la
     * ritrovavi in alto a sinistra. Una cosa che sta sopra tutte le altre app
     * deve stare dove l'hai messa, o sei tu a doverti adattare a lei.
     *
     * −1 vuol dire "mai trascinata", e allora decide il servizio.
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
        /** La paghetta adesso, gia' accorciata ("17,4 $"), e di quanto si e' mossa da quando l'hai messa. */
        val budgetText: String? = null,
        val budgetChange: Double? = null,
    )

    /**
     * The face, as a bitmap: the same drawing for the bubble on screen and the
     * preview on the page.
     *
     * **Sembra una sfera, e non costa niente.** Un vero 3D qui vorrebbe dire una
     * superficie OpenGL accesa sopra ogni altra app, cioe' la GPU sveglia per
     * sempre per un cerchietto: l'esatto contrario di quello che serve a una
     * cosa che sta sullo schermo tutto il giorno. La profondita' qui e' dipinta
     * — gradiente radiale spostato verso la luce, una specchiatura in alto a
     * sinistra, l'ombra che si raccoglie in basso, il bordo acceso solo dove la
     * luce lo prende — e si ridisegna **solo quando cambiano i numeri**, non a
     * ogni fotogramma. A riposo e' un bitmap fermo.
     */
    fun faceBitmap(px: Int, face: Face, d: FaceData): Bitmap {
        val p = Halo.palette
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = px / 2f
        // La sfera sta un pelo sopra il centro e lascia sotto lo spazio per la
        // sua ombra: senza ombra una palla sopra un'altra app non e' appoggiata
        // da nessuna parte.
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

        // --- il bagliore, quando lavora --------------------------------------
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

        // --- il corpo della sfera ---------------------------------------------
        //
        // Sul colore delle schede, non su quello del fondo: era una palla nera
        // su fondi neri, e la luce dipinta sopra non aveva niente da illuminare.
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx - r * 0.35f, cy - r * 0.42f, r * 1.75f,
                intArrayOf(lift(body0, 0.34f), body0, sink(body0, 0.55f)),
                floatArrayOf(0f, 0.50f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(cx, cy, r, body)

        // La specchiatura: dove la luce batte per prima.
        val spec = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx - r * 0.34f, cy - r * 0.48f, r * 0.62f,
                intArrayOf(AColor.argb(84, 255, 255, 255), AColor.argb(0, 255, 255, 255)),
                null, Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(cx - r * 0.34f, cy - r * 0.48f, r * 0.62f, spec)

        // Il bordo: chiaro in alto dove la luce lo prende, scuro in basso.
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
         * Il gradiente del marchio lungo l'anello, dal viola al ciano alla menta.
         * Si usa solo dove il colore non e' gia' un messaggio: salute bassa e
         * moneta in perdita restano rosso e ambra, tinta unica.
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

        /** Il puntino di stato, a ore quattro sull'anello: acceso quando lavora. */
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

        /** Il segno dell'app: una V dal viola al ciano, per quando non c'e' un numero da dire. */
        fun mark(alpha: Int) {
            val v = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = r * 0.14f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                shader = LinearGradient(cx - r * 0.3f, cy + r * 0.3f, cx + r * 0.3f, cy - r * 0.3f, intArrayOf(0xFF9524F3.toInt(), 0xFF4CC9FF.toInt()), null, Shader.TileMode.CLAMP)
                this.alpha = alpha
            }
            val path = android.graphics.Path().apply {
                moveTo(cx - r * 0.34f, cy - r * 0.26f); lineTo(cx, cy + r * 0.30f); lineTo(cx + r * 0.34f, cy - r * 0.26f)
            }
            c.drawPath(path, v)
        }

        when (face) {
            Face.AGENT, Face.ROTATE -> {
                if (d.trading) brand() else flat(AColor.argb(0, 0, 0, 0))
                c.drawArc(rect, -90f, 360f, false, arc)
                val n = d.openPositions
                if (d.trading && n != null) {
                    // Il numero e' la cosa piu' grande della bolla, e si accende
                    // dall'alto verso il basso, dal bianco al colore del marchio.
                    text.textSize = r * 0.92f
                    val top = cy - text.textSize * 0.55f
                    text.shader = LinearGradient(0f, top, 0f, top + text.textSize, intArrayOf(0xFFFFFFFF.toInt(), 0xFF4DFFD0.toInt()), null, Shader.TileMode.CLAMP)
                    c.drawText(n.toString(), cx, cy + text.textSize * 0.34f, text)
                    text.shader = null
                } else {
                    // Fermo: il segno dell'app, non un trattino.
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
