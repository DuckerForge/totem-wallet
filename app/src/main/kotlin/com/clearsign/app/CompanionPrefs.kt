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
        val ground = p.ground.toArgb()

        // --- il corpo della sfera -------------------------------------------
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                px * 0.34f, px * 0.30f, px * 0.82f,
                intArrayOf(lift(ground, 0.38f), ground, sink(ground, 0.45f)),
                floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(cx, cx, cx, body)

        // La specchiatura: dove la luce batte per prima.
        val spec = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                px * 0.33f, px * 0.25f, px * 0.32f,
                intArrayOf(AColor.argb(70, 255, 255, 255), AColor.argb(0, 255, 255, 255)),
                null, Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(px * 0.33f, px * 0.25f, px * 0.32f, spec)

        // Il bordo: chiaro in alto dove la luce lo prende, scuro in basso.
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = px * 0.028f
            shader = LinearGradient(
                0f, 0f, 0f, px.toFloat(),
                intArrayOf(AColor.argb(110, 255, 255, 255), AColor.argb(18, 255, 255, 255), AColor.argb(90, 0, 0, 0)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(cx, cx, cx - px * 0.014f, rim)

        // --- l'anello coi numeri --------------------------------------------
        //
        // Sottile. Era spesso un decimo del diametro, cioe' su una bolla da 54
        // un anello da 5: a quel punto il colore non e' piu' un bordo, e' meta'
        // della bolla, e il numero dentro sta in un buco.
        val w = px * 0.058f
        val inset = px * 0.085f
        val rect = RectF(inset, inset, px - inset, px - inset)
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND }
        arc.color = AColor.argb(120, AColor.red(p.stroke.toArgb()), AColor.green(p.stroke.toArgb()), AColor.blue(p.stroke.toArgb()))
        c.drawArc(rect, 0f, 360f, false, arc)

        /**
         * Il gradiente del marchio lungo l'anello, dal viola al ciano alla menta.
         *
         * Una tinta unica non e' questa app: la V sul lanciatore e' un tratto che
         * corre dal viola in basso al ciano in alto, ed e' la prima cosa che si
         * vede di Velum. L'anello e' un cerchio, quindi il gradiente gira
         * (`SweepGradient`) e parte dall'alto, da dove parte anche l'arco.
         *
         * Si usa **solo dove il colore non e' gia' un messaggio**: quando dice
         * salute bassa o moneta in perdita, il rosso e l'ambra valgono piu' di
         * qualsiasi bel gradiente, e restano tinta unica.
         */
        fun brand() {
            arc.shader = android.graphics.SweepGradient(
                cx, cx,
                intArrayOf(0xFF9524F3.toInt(), 0xFF4CC9FF.toInt(), 0xFF4DFFD0.toInt(), 0xFF9524F3.toInt()),
                floatArrayOf(0f, 0.34f, 0.67f, 1f),
            ).apply { setLocalMatrix(android.graphics.Matrix().apply { postRotate(-90f, cx, cx) }) }
        }
        fun flat(color: Int) { arc.shader = null; arc.color = color }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.ink.toArgb(); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.muted.toArgb(); textAlign = Paint.Align.CENTER }
        // Una sfera ha un'ombra sotto le scritte, se no il testo galleggia.
        text.setShadowLayer(px * 0.04f, 0f, px * 0.015f, AColor.argb(150, 0, 0, 0))

        when (face) {
            Face.AGENT, Face.ROTATE -> {
                val tint = (if (d.trading) p.accent else p.muted).toArgb()
                if (d.trading) brand() else flat(tint)
                c.drawArc(rect, -90f, if (d.trading) 360f else 0f, false, arc)
                // Il numero e' la cosa piu' grande della bolla, e non e' bianco
                // piatto: si accende dall'alto verso il basso, dal bianco al
                // colore del marchio, come il tratto sull'icona.
                text.textSize = px * 0.40f
                val top = cx - text.textSize * 0.55f
                text.shader = LinearGradient(
                    0f, top, 0f, top + text.textSize,
                    intArrayOf(0xFFFFFFFF.toInt(), if (d.trading) 0xFF4DFFD0.toInt() else p.muted.toArgb()),
                    null, Shader.TileMode.CLAMP,
                )
                c.drawText(d.openPositions?.toString() ?: "–", cx, cx + text.textSize * 0.28f, text)
                text.shader = null
                // "ON" era scritto piccolo in menta su fondo scuro, cioe' due
                // colori vicini a corpo dieci: si intuiva, non si leggeva.
                // Adesso e' una pastiglia piena col testo scavato dentro, che a
                // quella misura e' l'unico modo di farsi leggere.
                val label = if (d.trading) "ON" else "OFF"
                small.textSize = px * 0.135f
                val tw = small.measureText(label)
                val ph = px * 0.19f
                val pw = tw + px * 0.14f
                val py = px - inset * 1.55f - ph * 0.5f
                val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
                c.drawRoundRect(RectF(cx - pw / 2f, py, cx + pw / 2f, py + ph), ph / 2f, ph / 2f, pill)
                small.color = p.ground.toArgb()
                small.isFakeBoldText = true
                c.drawText(label, cx, py + ph * 0.72f, small)
                small.isFakeBoldText = false
            }
            Face.HEALTH -> {
                val s = d.score
                val tint = when { s == null -> p.muted; s >= 80 -> p.accent; s >= 50 -> p.amber; else -> p.red }.toArgb()
                if (s != null && s >= 80) brand() else flat(tint)
                c.drawArc(rect, -90f, if (s != null) 360f * s.coerceIn(0, 100) / 100f else 0f, false, arc)
                text.textSize = px * 0.34f
                c.drawText(s?.toString() ?: "–", cx, cx + text.textSize * 0.35f, text)
            }
            Face.TOTAL -> {
                brand(); c.drawArc(rect, -90f, 360f, false, arc)
                val t = d.totalText ?: "…"
                text.textSize = if (t.length > 6) px * 0.20f else px * 0.26f
                c.drawText(t, cx, cx + text.textSize * 0.35f, text)
            }
            Face.COIN -> {
                val ch = d.coinChange
                val tint = (if (ch == null) p.muted else if (ch >= 0) p.accent else p.red).toArgb()
                if (ch != null && ch >= 0) brand() else flat(tint)
                c.drawArc(rect, -90f, 360f, false, arc)
                text.textSize = px * 0.22f
                c.drawText(d.coinSymbol?.take(5) ?: "?", cx, cx - px * 0.02f, text)
                small.textSize = px * 0.16f; small.color = tint
                c.drawText(ch?.let { String.format(Locale.ROOT, "%+.1f%%", it) } ?: "", cx, cx + px * 0.2f, small)
            }
            // La paghetta: quanto vale adesso, e di quanto si e' mossa da quando
            // l'hai messa. Non il portafoglio intero, che e' un altro discorso e
            // un altro numero.
            Face.BUDGET -> {
                val ch = d.budgetChange
                val tint = (if (ch == null) p.accent2 else if (ch >= 0) p.accent else p.red).toArgb()
                if (ch == null || ch >= 0) brand() else flat(tint)
                c.drawArc(rect, -90f, 360f, false, arc)
                val t = d.budgetText ?: "…"
                text.textSize = if (t.length > 6) px * 0.19f else px * 0.24f
                c.drawText(t, cx, cx - px * 0.01f, text)
                small.textSize = px * 0.155f; small.color = tint
                c.drawText(ch?.let { String.format(Locale.ROOT, "%+.1f%%", it) } ?: "", cx, cx + px * 0.21f, small)
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
