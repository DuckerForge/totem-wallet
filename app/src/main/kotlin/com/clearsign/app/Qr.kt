package com.clearsign.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR codes, in one place: the receive address, and the signed transaction an
 * agent on another machine needs to read back off the screen.
 *
 * Correction level drops to L on long payloads (a signed Jupiter swap is around
 * a kilobyte): fewer modules means a code a camera can still resolve.
 */
private const val DENSE_AT = 900

internal fun qrMatrix(text: String): BitMatrix = QRCodeWriter().encode(
    text,
    BarcodeFormat.QR_CODE,
    0,
    0,
    mapOf(
        EncodeHintType.MARGIN to 0,
        EncodeHintType.ERROR_CORRECTION to if (text.length > DENSE_AT) ErrorCorrectionLevel.L else ErrorCorrectionLevel.M,
    ),
)

/**
 * The code on a light tile, so any scanner reads it whatever the theme is doing.
 */
@Composable
internal fun QrTile(text: String, size: Dp = 232.dp, modifier: Modifier = Modifier) {
    val matrix = remember(text) { runCatching { qrMatrix(text) }.getOrNull() }
    if (matrix == null) return
    val ground = Halo.ground
    Box(
        modifier.size(size).clip(rs(20)).background(Color(0xFFF7F8FB)).border(1.dp, Halo.stroke, rs(20)).padding(16.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().size(size - 32.dp)) {
            val n = matrix.width
            val cell = this.size.minDimension / n
            for (y in 0 until n) for (x in 0 until n) if (matrix.get(x, y)) {
                drawRect(ground, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
