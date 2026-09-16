package com.clearsign.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import java.util.Locale

/**
 * What the bubble shows, chosen by the person.
 *
 * The face (what the small circle says at a glance), the rows of the open
 * panel, the coin to watch, the size. Kept in the app's prefs so the service
 * and the page read the same thing.
 */
object CompanionPrefs {
    private const val P = "apex_companion"
    enum class Face { AGENT, HEALTH, TOTAL, COIN }

    private fun p(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)

    fun face(ctx: Context): Face = runCatching { Face.valueOf(p(ctx).getString("face", "AGENT")!!) }.getOrDefault(Face.AGENT)
    fun setFace(ctx: Context, f: Face) = p(ctx).edit().putString("face", f.name).apply()
    fun show(ctx: Context, what: String): Boolean = p(ctx).getBoolean("show_$what", what != "coin")
    fun setShow(ctx: Context, what: String, on: Boolean) = p(ctx).edit().putBoolean("show_$what", on).apply()
    fun coin(ctx: Context): String? = p(ctx).getString("coin", null)
    fun setCoin(ctx: Context, mint: String?) = p(ctx).edit().putString("coin", mint).apply()
    fun size(ctx: Context): Int = p(ctx).getInt("size", 54)
    fun setSize(ctx: Context, dp: Int) = p(ctx).edit().putInt("size", dp).apply()
    /** Come back on its own when the app opens. */
    fun autoStart(ctx: Context): Boolean = p(ctx).getBoolean("auto", false)
    fun setAutoStart(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("auto", on).apply()

    /** What the face needs to draw itself, gathered by whoever has the data. */
    data class FaceData(val score: Int?, val openPositions: Int?, val totalText: String?, val coinSymbol: String?, val coinChange: Double?, val trading: Boolean)

    /**
     * The face, as a bitmap: the same drawing for the bubble on screen and the
     * preview on the page. A ring in the theme's colours with one thing inside.
     */
    fun faceBitmap(px: Int, face: Face, d: FaceData): Bitmap {
        val p = Halo.palette
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.ground.toArgb() }
        c.drawCircle(px / 2f, px / 2f, px / 2f, fill)
        val w = px * 0.1f
        val rect = RectF(w, w, px - w, px - w)
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND }
        arc.color = p.stroke.toArgb(); c.drawArc(rect, 0f, 360f, false, arc)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.ink.toArgb(); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.muted.toArgb(); textAlign = Paint.Align.CENTER }
        val cx = px / 2f
        when (face) {
            Face.AGENT -> {
                val tint = (if (d.trading) p.accent else p.muted).toArgb()
                arc.color = tint; c.drawArc(rect, -90f, if (d.trading) 360f else 0f, false, arc)
                text.textSize = px * 0.34f
                c.drawText(d.openPositions?.toString() ?: "–", cx, cx + text.textSize * 0.35f, text)
                small.textSize = px * 0.13f; small.color = tint
                c.drawText(if (d.trading) "ON" else "OFF", cx, px - w * 1.6f, small)
            }
            Face.HEALTH -> {
                val s = d.score
                val tint = when { s == null -> p.muted; s >= 80 -> p.accent; s >= 50 -> p.amber; else -> p.red }.toArgb()
                arc.color = tint; c.drawArc(rect, -90f, if (s != null) 360f * s.coerceIn(0, 100) / 100f else 0f, false, arc)
                text.textSize = px * 0.34f
                c.drawText(s?.toString() ?: "–", cx, cx + text.textSize * 0.35f, text)
            }
            Face.TOTAL -> {
                arc.color = p.accent2.toArgb(); c.drawArc(rect, -90f, 360f, false, arc)
                val t = d.totalText ?: "…"
                text.textSize = if (t.length > 6) px * 0.20f else px * 0.26f
                c.drawText(t, cx, cx + text.textSize * 0.35f, text)
            }
            Face.COIN -> {
                val ch = d.coinChange
                val tint = (if (ch == null) p.muted else if (ch >= 0) p.accent else p.red).toArgb()
                arc.color = tint; c.drawArc(rect, -90f, 360f, false, arc)
                text.textSize = px * 0.22f
                c.drawText(d.coinSymbol?.take(5) ?: "?", cx, cx - px * 0.02f, text)
                small.textSize = px * 0.16f; small.color = tint
                c.drawText(ch?.let { String.format(Locale.ROOT, "%+.1f%%", it) } ?: "", cx, cx + px * 0.2f, small)
            }
        }
        return bmp
    }
}
