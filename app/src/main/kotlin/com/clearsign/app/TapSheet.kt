@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Being paid by holding two phones together.
 *
 * The merchant types an amount; the phone starts emitting that request over NFC
 * and shows the same request as a QR for anyone whose phone cannot tap. The
 * honest line stays on screen the whole time: this asks, it does not take.
 */
@Composable
internal fun TapSheet(address: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var armed by remember { mutableStateOf(false) }
    val supported = remember { TapService.isSupported(ctx) }
    val tapped by TapService.tapped.collectAsState()
    var seenAt by remember { mutableStateOf(0L) }
    val request = remember(address, amount, note) {
        MoneyLinks.request(address, amount.replace(',', '.').toDoubleOrNull(), null, note)
    }

    // The radio is on only while this screen is: an idle phone emits nothing.
    // While it is on we also claim the AID, because another installed app claims
    // the same one and Android would otherwise have no reason to route to us.
    DisposableEffect(armed, request) {
        val activity = ctx as? android.app.Activity
        if (armed) {
            TapService.arm(request)
            activity?.let { TapService.preferWhileVisible(it, true) }
        }
        onDispose {
            TapService.disarm()
            activity?.let { TapService.preferWhileVisible(it, false) }
        }
    }
    if (armed && tapped > seenAt) seenAt = tapped

    ModalBottomSheet(onDismissRequest = { TapService.disarm(); onDismiss() }, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.95f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(rs(12)).background(Halo.cyanSoft), contentAlignment = Alignment.Center) {
                    HaloIcon(HIcon.RECEIVE, Halo.cyan, 20.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.tap_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.ink)
                    Text(stringResource(R.string.tap_sub), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                }
            }

            if (!supported) {
                Banner(stringResource(R.string.tap_unsupported), Halo.amber, HIcon.WARNING)
            }

            if (!armed) TapAnimation(active = false)

            // Phone-to-phone NFC is finicky and nobody guesses the geometry: the two
            // antennas have to overlap, which means back against back.
            Text(
                stringResource(R.string.tap_how),
                fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted, lineHeight = 16.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            if (!armed) {
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.tap_amount), fontFamily = Inter, fontSize = 11.sp) },
                    suffix = { Text("SOL", fontFamily = Mono, fontSize = 12.sp, color = Halo.muted) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 18.sp, color = Halo.ink),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Halo.cyan, unfocusedBorderColor = Halo.stroke,
                        focusedContainerColor = Halo.cardSoft, unfocusedContainerColor = Halo.cardSoft, cursorColor = Halo.cyan,
                    ),
                    shape = rs(12),
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it.take(40) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.tap_note), fontFamily = Inter, fontSize = 11.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Inter, fontSize = 14.sp, color = Halo.ink),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Halo.cyan, unfocusedBorderColor = Halo.stroke,
                        focusedContainerColor = Halo.cardSoft, unfocusedContainerColor = Halo.cardSoft, cursorColor = Halo.cyan,
                    ),
                    shape = rs(12),
                )
                PrimaryButton(stringResource(R.string.tap_arm), danger = false, enabled = supported, icon = HIcon.RECEIVE) { armed = true }
            } else {
                val pulse = rememberInfiniteTransition(label = "tap")
                val glow by pulse.animateFloat(
                    initialValue = 0.35f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "glow",
                )
                Box(Modifier.alpha(0.55f + glow * 0.45f)) { TapAnimation(active = true) }

                amount.replace(',', '.').toDoubleOrNull()?.let { a ->
                    Text(fmtUi(a) + " SOL", fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Halo.ink, style = Tabular)
                }
                note.takeIf { it.isNotBlank() }?.let { Text(it, fontFamily = Inter, fontSize = 13.sp, color = Halo.muted) }

                if (seenAt > 0) {
                    Banner(stringResource(R.string.tap_done), Halo.mint, HIcon.CHECK)
                } else {
                    Text(stringResource(R.string.tap_waiting), fontFamily = Inter, fontSize = 12.5.sp, color = Halo.cyan)
                }

                Text(stringResource(R.string.tap_qr_fallback), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted)
                QrTile(request, size = 200.dp)
                GhostButton(stringResource(R.string.tap_stop), Modifier.fillMaxWidth(), HIcon.BLOCK, tint = Halo.amber) { armed = false; seenAt = 0 }
            }

            Text(stringResource(R.string.tap_truth), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, lineHeight = 16.sp)
            Spacer(Modifier.height(2.dp))
            GhostButton(stringResource(R.string.close), Modifier.fillMaxWidth()) { TapService.disarm(); onDismiss() }
        }
    }
}
