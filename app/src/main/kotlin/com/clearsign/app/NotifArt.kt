package com.clearsign.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import java.util.Locale

/**
 * Pictures for the shade. A notification carries one image, and an image says at a glance
 * where each coin stands between stop and target: a track per coin, stop at the left in red,
 * target at the right in the accent, a faint tick for the entry, a lit dot where the market
 * put it. Drawn in the theme's palette; nothing here touches the network or a model.
 */
object NotifArt {
    data class Row(val symbol: String, val movePct: Double?, val takeProfitPct: Int, val stopLossPct: Int)

    private const val W = 1200
    private const val ROW = 150
    private const val PAD = 48

    fun tracks(ctx: Context, rows: List<Row>): Bitmap {
        val p = Halo.palette
        val n = rows.size.coerceAtLeast(1)
        val h = PAD * 2 + ROW * n
        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // The page's own ground, top to bottom, so the picture reads as a
        // piece of the app and not as a white box.
        c.drawRect(
            0f, 0f, W.toFloat(), h.toFloat(),
            Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), p.ground2.toArgb(), p.ground.toArgb(), Shader.TileMode.CLAMP) },
        )
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.ink.toArgb(); textSize = 40f; isFakeBoldText = true }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.muted.toArgb(); textSize = 28f }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        val x0 = PAD + 250f
        val x1 = W - PAD - 40f
        rows.forEachIndexed { i, r ->
            val cy = PAD + ROW * i + ROW * 0.55f
            val span = (r.takeProfitPct + r.stopLossPct).coerceAtLeast(1)
            val tint = (if ((r.movePct ?: 0.0) >= 0) p.accent else p.red).toArgb()

            // Name, and the number under it.
            c.drawText(r.symbol.take(10), PAD.toFloat(), cy + 14f, text)
            val shown = r.movePct?.let { String.format(Locale.ROOT, "%+.1f%%", it) } ?: "…"
            c.drawText(shown, PAD.toFloat(), cy + 52f, small.apply { color = tint })

            // The track: red at the stop, the accent at the target.
            line.shader = LinearGradient(x0, 0f, x1, 0f, p.red.toArgb(), p.accent.toArgb(), Shader.TileMode.CLAMP)
            line.alpha = 110
            c.drawLine(x0, cy, x1, cy, line)
            line.shader = null; line.alpha = 255

            // Entry: a faint tick where the coin started.
            val xe = x0 + (x1 - x0) * r.stopLossPct / span
            c.drawLine(xe, cy - 16f, xe, cy + 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.stroke.toArgb(); strokeWidth = 3f })

            // The two ends, labelled.
            small.color = p.red.toArgb(); small.textAlign = Paint.Align.LEFT
            c.drawText("−" + r.stopLossPct + "%", x0, cy - 26f, small)
            small.color = p.accent.toArgb(); small.textAlign = Paint.Align.RIGHT
            c.drawText("+" + r.takeProfitPct + "%", x1, cy - 26f, small)
            small.textAlign = Paint.Align.LEFT; small.color = p.muted.toArgb()

            // The coin: a lit dot with a glow, clamped to the track.
            val m = r.movePct ?: return@forEachIndexed
            val t = ((m + r.stopLossPct) / span).toFloat().coerceIn(0f, 1f)
            val x = x0 + (x1 - x0) * t
            fill.shader = RadialGradient(x, cy, 46f, intArrayOf(tint and 0x00FFFFFF or (0x90 shl 24), tint and 0x00FFFFFF), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, cy, 46f, fill)
            fill.shader = null
            fill.color = p.ground.toArgb(); c.drawCircle(x, cy, 16f, fill)
            fill.color = tint; c.drawCircle(x, cy, 11f, fill)
        }
        return bmp
    }

    /** The coin's logo for the notification's large icon: downloaded once, kept on disk. Null when it cannot be had. */
    fun logo(ctx: Context, url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        val f = java.io.File(ctx.cacheDir, "logo-" + url.hashCode().toString(16) + ".png")
        if (f.exists()) return runCatching { android.graphics.BitmapFactory.decodeFile(f.path) }.getOrNull()
        return runCatching {
            val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 4000; readTimeout = 6000 }
            val b = c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return null
            val sq = Bitmap.createScaledBitmap(b, 192, 192, true)
            f.outputStream().use { sq.compress(Bitmap.CompressFormat.PNG, 90, it) }
            sq
        }.getOrNull()
    }
}
