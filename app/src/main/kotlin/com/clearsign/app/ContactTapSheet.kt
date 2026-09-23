@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clearsign.core.ContactTap

/** A contact card that came in by touch, waiting for its sheet. */
object ContactInbox { val incoming = mutableStateOf<ContactTap.Card?>(null) }

/**
 * Trading contacts by touching two phones. Two tocks, one each way: "Show mine" makes this
 * phone the tag with its signed card, "Read theirs" listens. What comes in is shown already
 * checked and saved as a contact verified by touch, the one kind address poisoning cannot imitate.
 */
@Composable
internal fun ContactTapSheet(owner: String, onSaved: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(Settings.watchWallet(ctx)?.let { "" } ?: "") }
    var showing by remember { mutableStateOf(false) }
    val supported = remember { TapService.isSupported(ctx) }
    val card by ContactInbox.incoming
    var saved by remember { mutableStateOf(false) }

    val mine = remember(name, owner) {
        val at = System.currentTimeMillis()
        val key = Attestation.publicKeyBase64()
        val sig = Attestation.sign(ContactTap.payload(owner, name.ifBlank { shorten(owner, 4) }, at))
        if (key != null && sig != null) ContactTap.encode(owner, name.ifBlank { shorten(owner, 4) }, at, java.util.Base64.getDecoder().decode(key), java.util.Base64.getDecoder().decode(sig)) else null
    }
    DisposableEffect(showing, mine) {
        if (showing && mine != null) { TapService.arm(mine); TapService.preferWhileVisible(ctx, true) }
        onDispose { TapService.disarm(); TapService.preferWhileVisible(ctx, false) }
    }

    ModalBottomSheet(onDismissRequest = { TapService.disarm(); onDismiss() }, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SheetHeader(stringResource(R.string.ctap_title), stringResource(R.string.ctap_sub), HIcon.CONTACTS, onClose = { TapService.disarm(); onDismiss() })
            Text(stringResource(R.string.ctap_how), style = HaloType.small, color = Halo.muted, lineHeight = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)

            card?.let { c ->
                val ok = remember(c) { ContactTap.verify(c) }
                if (!ok) Banner(stringResource(R.string.ctap_bad), Halo.red, HIcon.BLOCK)
                else {
                    Banner(stringResource(R.string.ctap_got, c.name), Halo.mint, HIcon.CHECK)
                    Text(c.address, fontFamily = Mono, fontSize = 11.sp, color = Halo.muted)
                    if (!saved) PrimaryButton(stringResource(R.string.ctap_save), danger = false, icon = HIcon.CHECK) {
                        Contacts.saveContact(ctx, c.address, c.name); Contacts.markVerified(ctx, c.address); Haptics.success(ctx); saved = true; onSaved()
                    } else Text(stringResource(R.string.ctap_saved), style = HaloType.small, color = Halo.mint)
                }
            }

            if (!showing) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(24) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.ctap_name), fontFamily = Inter, fontSize = 11.sp) },
                    textStyle = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = Halo.ink), colors = pickerField(), shape = rs(12),
                )
                TapAnimation(active = false)
                PrimaryButton(stringResource(R.string.ctap_show), danger = false, enabled = supported && mine != null, icon = HIcon.NFC) { showing = true }
                Text(stringResource(R.string.ctap_read_hint), style = HaloType.small, color = Halo.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            } else {
                TapAnimation(active = true)
                Text(stringResource(R.string.ctap_showing, name.ifBlank { shorten(owner, 4) }), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.cyan)
                GhostButton(stringResource(R.string.ctap_stop), Modifier.fillMaxWidth(), HIcon.BLOCK, tint = Halo.amber) { showing = false }
            }
            Text(stringResource(R.string.ctap_truth), style = HaloType.small, color = Halo.muted, lineHeight = 15.sp)
            Spacer(Modifier.height(4.dp))
        }
    }
}
