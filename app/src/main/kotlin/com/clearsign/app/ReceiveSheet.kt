@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Your address as a QR + text, with copy and share. */
@Composable
internal fun ReceiveSheet(address: String, label: String?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var copied by remember { mutableStateOf(false) }
    val matrix = remember(address) {
        QRCodeWriter().encode(address, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(rs(12)).background(Halo.cyanSoft), contentAlignment = Alignment.Center) { HaloIcon(HIcon.RECEIVE, Halo.cyan, 20.dp) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.receive_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.ink)
                    Text(label ?: stringResource(R.string.receive_note), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                }
            }
            // The QR lives on a light tile so every scanner reads it, whatever the theme.
            Box(Modifier.size(232.dp).clip(rs(20)).background(Color(0xFFF7F8FB)).border(1.dp, Halo.stroke, rs(20)).padding(16.dp)) {
                val ground = Halo.ground
                Canvas(Modifier.fillMaxWidth().height(200.dp)) {
                    val n = matrix.width
                    val cell = size.minDimension / n
                    for (y in 0 until n) for (x in 0 until n) if (matrix.get(x, y)) {
                        drawRect(ground, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                    }
                }
            }
            Text(
                address.chunked(4).joinToString(" "),
                fontFamily = Mono, fontSize = 12.5.sp, color = Halo.ink, textAlign = TextAlign.Center, lineHeight = 19.sp,
            )
            Text(stringResource(R.string.receive_hint), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(if (copied) stringResource(R.string.copied) else stringResource(R.string.copy), Modifier.weight(1f), if (copied) HIcon.CHECK else HIcon.COPY, tint = if (copied) Halo.mint else Halo.muted) {
                    copyText(ctx, address); copied = true; Haptics.tick(ctx)
                }
                GhostButton(stringResource(R.string.share), Modifier.weight(1f), HIcon.SHARE, tint = Halo.cyan) {
                    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, address)
                    runCatching { ctx.startActivity(Intent.createChooser(i, null)) }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
