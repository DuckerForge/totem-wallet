@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
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

/** Your address as a QR + text, with copy and share. */
@Composable
internal fun ReceiveSheet(address: String, label: String?, onTap: () -> Unit = {}, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var copied by remember { mutableStateOf(false) }
    var copiedAddr by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(
            Modifier.fillMaxHeight(0.95f).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {

            // The address first, with the copy under it. It was last, under tapping, QR codes and
            // links, in the same outlined button as everything else: four ways of doing one thing, and
            // the one that always works at the bottom. Written whole: cut into blocks of four is what
            // you do to something read aloud, and nobody reads a Solana address, they copy it.
            Column(
                Modifier.fillMaxWidth().clip(rs(16)).background(Halo.card).haloBorder(rs(16)).padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    address,
                    fontFamily = Mono, fontSize = 13.sp, color = Halo.ink,
                    textAlign = TextAlign.Center, lineHeight = 20.sp,
                )
                // One breath when the sheet opens, and never again. Enough for the
                // eye to land here first; a button that keeps pulsing is a button
                // people learn to look away from.
                val breath = remember { androidx.compose.animation.core.Animatable(0f) }
                LaunchedEffect(Unit) {
                    breath.animateTo(1f, androidx.compose.animation.core.tween(520))
                    breath.animateTo(0f, androidx.compose.animation.core.tween(1100))
                }
                Box(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier.matchParentSize().padding(horizontal = 6.dp)
                            .clip(rs(16))
                            .background(Halo.mint.copy(alpha = 0.22f * breath.value)),
                    )
                    PrimaryButton(
                        if (copiedAddr) stringResource(R.string.copied) else stringResource(R.string.copy_address),
                        danger = false,
                        icon = if (copiedAddr) HIcon.CHECK else HIcon.COPY,
                    ) {
                        copyText(ctx, address); Haptics.success(ctx); copiedAddr = true
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(rs(12)).background(Halo.cyanSoft), contentAlignment = Alignment.Center) { HaloIcon(HIcon.RECEIVE, Halo.cyan, 20.dp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.receive_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.ink)
                    Text(label ?: stringResource(R.string.receive_note), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                }
                Box(
                    Modifier.size(34.dp).clip(rs(999)).background(Halo.card).haloBorder(rs(999)).clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) { HaloIcon(HIcon.CLOSE, Halo.muted, 16.dp, description = stringResource(R.string.close)) }
            }
            // Nothing is open until you ask for it. Three ways to hand over the same
            // request, and the fields only appear inside the one you pick.
            var mode by remember { mutableStateOf(RequestMode.NONE) }
            var amount by remember { mutableStateOf("") }
            var note by remember { mutableStateOf("") }
            // Two shapes of the same request. The universal link opens on any phone,
            // with or without a wallet; the classic Solana Pay URI is what another
            // wallet's scanner expects, so it stays one tap away.
            var classic by remember { mutableStateOf(false) }
            val payload = remember(address, amount, note, classic) {
                val sol = amount.replace(',', '.').toDoubleOrNull()
                if (classic) MoneyLinks.solanaPay(address, sol, null, note)
                else MoneyLinks.request(address, sol, null, note)
            }

            // 1. The tap. The whole block is the button: the picture is the instruction.
            SheetBlock(stringResource(R.string.recv_block_nfc), stringResource(R.string.recv_block_nfc_sub), HIcon.RECEIVE, Modifier.clickable { onTap() }) {
                if (TapService.isSupported(ctx)) TapAnimation(active = false, height = 126.dp)
                else Banner(stringResource(R.string.tap_unsupported), Halo.amber, HIcon.WARNING)
            }

            // 2. For a phone that cannot tap.
            SheetBlock(stringResource(R.string.recv_block_qr), stringResource(R.string.recv_block_qr_sub), HIcon.QR) {
                if (mode == RequestMode.QR) {
                    AmountAndNote(amount, note, { amount = it }, { note = it })
                    QrTile(payload)
                    LinkShape(classic) { classic = it }
                } else {
                    GhostButton(stringResource(R.string.recv_make_qr), Modifier.fillMaxWidth(), HIcon.QR, tint = Halo.cyan) { mode = RequestMode.QR }
                }
            }

            // 3. For a chat, where there is no phone to hold and no camera to point.
            SheetBlock(stringResource(R.string.recv_block_link), stringResource(R.string.recv_block_link_sub), HIcon.SHARE) {
                if (mode == RequestMode.LINK) {
                    AmountAndNote(amount, note, { amount = it }, { note = it })
                    LinkShape(classic) { classic = it }
                    Text(payload, fontFamily = Mono, fontSize = 11.sp, color = Halo.muted, textAlign = TextAlign.Center, maxLines = 3, lineHeight = 15.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton(if (copied) stringResource(R.string.copied) else stringResource(R.string.copy), Modifier.weight(1f), if (copied) HIcon.CHECK else HIcon.COPY, tint = if (copied) Halo.mint else Halo.muted) {
                            copyText(ctx, payload); copied = true; Haptics.tick(ctx)
                        }
                        GhostButton(stringResource(R.string.share), Modifier.weight(1f), HIcon.SHARE, tint = Halo.cyan) {
                            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, payload)
                            Door.hold(); runCatching { ctx.startActivity(Intent.createChooser(i, null)) }
                        }
                    }
                } else {
                    GhostButton(stringResource(R.string.recv_make_link), Modifier.fillMaxWidth(), HIcon.SHARE, tint = Halo.cyan) { mode = RequestMode.LINK }
                }
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

/** Which of the three ways is open. Only one at a time, and none at the start. */
private enum class RequestMode { NONE, QR, LINK }

/** The two things a request needs, asked only inside the block that needs them. */
@Composable
private fun AmountAndNote(amount: String, note: String, onAmount: (String) -> Unit, onNote: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = amount, onValueChange = { v -> onAmount(v.filter { c -> c.isDigit() || c == '.' || c == ',' }) },
            modifier = Modifier.weight(1f), singleLine = true,
            label = { Text(stringResource(R.string.req_amount), fontFamily = Inter, fontSize = 11.sp) },
            suffix = { Text("SOL", fontFamily = Mono, fontSize = 11.sp, color = Halo.muted) },
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 14.sp, color = Halo.ink),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Halo.cyan, unfocusedBorderColor = Halo.stroke,
                focusedContainerColor = Halo.cardSoft, unfocusedContainerColor = Halo.cardSoft, cursorColor = Halo.cyan,
            ),
            shape = rs(12),
        )
        androidx.compose.material3.OutlinedTextField(
            value = note, onValueChange = { onNote(it.take(40)) },
            modifier = Modifier.weight(1.2f), singleLine = true,
            label = { Text(stringResource(R.string.req_note), fontFamily = Inter, fontSize = 11.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Inter, fontSize = 13.sp, color = Halo.ink),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Halo.cyan, unfocusedBorderColor = Halo.stroke,
                focusedContainerColor = Halo.cardSoft, unfocusedContainerColor = Halo.cardSoft, cursorColor = Halo.cyan,
            ),
            shape = rs(12),
        )
    }
}

/** Universal link or classic Solana Pay, with the one line that says why. */
@Composable
private fun LinkShape(classic: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinkMode(stringResource(R.string.recv_link_web), !classic) { onChange(false) }
        LinkMode(stringResource(R.string.recv_link_pay), classic) { onChange(true) }
    }
    Text(
        stringResource(if (classic) R.string.recv_link_pay_note else R.string.recv_link_web_note),
        fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, textAlign = TextAlign.Center, lineHeight = 16.sp,
    )
}

/** Which shape of the request the QR carries. */
@Composable
private fun LinkMode(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label, fontFamily = Inter, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, fontSize = 11.5.sp,
        color = if (on) Halo.mint else Halo.muted,
        modifier = Modifier.clip(rs(999))
            .background((if (on) Halo.mint else Halo.muted).copy(alpha = 0.12f))
            .border(1.dp, if (on) Halo.mint.copy(alpha = 0.5f) else Halo.stroke, rs(999))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * One labeled block inside a sheet. Receive was one column where the QR, the link switch and
 * the tap button ran together; two named blocks make them two ways of handing over the same request.
 */
@Composable
internal fun SheetBlock(title: String, sub: String, icon: HIcon, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(rs(18)).background(Halo.cardSoft).haloBorder(rs(18)).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(rs(9)).background(Halo.cyanSoft), contentAlignment = Alignment.Center) { HaloIcon(icon, Halo.cyan, 14.dp) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Halo.ink, maxLines = 1)
                Text(sub, fontFamily = Inter, fontSize = 10.sp, color = Halo.muted, lineHeight = 13.sp)
            }
        }
        content()
    }
}
