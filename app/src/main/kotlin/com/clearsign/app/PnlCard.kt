package com.clearsign.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * The card people post: how a trade or a budget went, one picture in the theme's colors. Big
 * number, a line above and below, the app's name small in the corner. Nothing that identifies the wallet.
 */
object PnlCard {
    private const val W = 1200
    private const val H = 675

    data class Face(val title: String, val sub: String, val pct: Double?, val amountText: String, val foot: String)

    fun draw(face: Face, brandName: String): Bitmap {
        val p = Halo.palette
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), p.ground2.toArgb(), p.ground.toArgb(), Shader.TileMode.CLAMP) })
        val up = (face.pct ?: 0.0) >= 0
        val tint = (if (up) p.accent else p.red).toArgb()
        // A soft light in the corner the number leans on.
        c.drawCircle(W * 0.82f, H * 0.2f, 360f, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = android.graphics.RadialGradient(W * 0.82f, H * 0.2f, 360f, intArrayOf(tint and 0x00FFFFFF or (0x55 shl 24), tint and 0x00FFFFFF), null, Shader.TileMode.CLAMP) })
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.muted.toArgb(); textSize = 34f }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.ink.toArgb(); textSize = 52f; isFakeBoldText = true }
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint; textSize = 190f; isFakeBoldText = true }
        val mid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.ink.toArgb(); textSize = 56f; isFakeBoldText = true }
        c.drawText(face.title, 72f, 120f, title)
        c.drawText(face.sub, 72f, 172f, small)
        val pctText = face.pct?.let { String.format(Locale.ROOT, "%+.1f%%", it) } ?: "—"
        c.drawText(pctText, 66f, 400f, big)
        c.drawText(face.amountText, 72f, 480f, mid)
        c.drawText(face.foot, 72f, H - 64f, small)
        val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.accent.toArgb(); textSize = 30f; isFakeBoldText = true; textAlign = Paint.Align.RIGHT }
        c.drawText(brandName.uppercase(), W - 72f, H - 64f, brand)
        return bmp
    }

    /** Draw, save in the export folder, open the share sheet. */
    fun share(ctx: Context, face: Face, name: String) {
        val bmp = draw(face, ctx.getString(R.string.app_name))
        val out = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val f = Exports.write(ctx, name, out.toByteArray())
        Exports.share(ctx, listOf(f), "image/png", face.title)
    }
}
