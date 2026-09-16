@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

/** The proof of one receipt, as a QR to hold up. */
@Composable
internal fun ProofSheet(entry: LedgerEntry, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val line = remember(entry.id) {
        val key = Attestation.publicKeyBase64()
        if (entry.attestation != null && entry.attestationSig != null && key != null) Proof.encode(entry.attestation, entry.attestationSig, key) else null
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SheetHeader(stringResource(R.string.proof_title), stringResource(R.string.proof_sub), HIcon.SHIELD_LOCK, onClose = onDismiss)
            if (line == null) {
                Banner(stringResource(R.string.proof_none), Halo.amber, HIcon.WARNING)
            } else {
                QrTile(line, size = 280.dp)
                Text(
                    stringResource(R.string.proof_signed_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.at)), entry.signature?.take(8) ?: "…"),
                    style = HaloType.small, color = Halo.muted,
                )
                GhostButton(stringResource(R.string.share), Modifier.fillMaxWidth(), HIcon.SHARE, tint = Halo.cyan) {
                    val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, line)
                    runCatching { ctx.startActivity(android.content.Intent.createChooser(i, null)) }
                }
            }
            Text(stringResource(R.string.proof_truth), style = HaloType.small, color = Halo.muted, lineHeight = 15.sp)
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** A proof that was scanned: valid or not, and what it says. */
@Composable
internal fun ProofCheckSheet(text: String, owner: String?, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val v = remember(text) { Proof.verify(text) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetHeader(stringResource(R.string.proof_check_title), stringResource(R.string.proof_check_sub), HIcon.SHIELD_LOCK, onClose = onDismiss)
            if (v == null) {
                Banner(stringResource(R.string.proof_bad), Halo.red, HIcon.BLOCK)
            } else {
                Banner(stringResource(R.string.proof_good), Halo.mint, HIcon.CHECK)
                v.outflows.forEach { StatRow(stringResource(R.string.proof_paid), it, accent = true) }
                v.inflows.forEach { StatRow(stringResource(R.string.proof_got), it) }
                StatRow(stringResource(R.string.proof_from), shorten(v.signer, 6))
                StatRow(stringResource(R.string.proof_when), DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(v.at)))
                v.txSignature?.let { sig ->
                    StatRow(stringResource(R.string.proof_tx), sig.take(12) + "…")
                    val ctx = LocalContext.current
                    GhostButton(stringResource(R.string.proof_chain), Modifier.fillMaxWidth(), HIcon.EXTERNAL, tint = Halo.cyan) {
                        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(solscanTxUrl(sig, null)))) }
                    }
                }
                if (owner != null && v.inflows.isEmpty() && v.outflows.isNotEmpty()) {
                    Text(stringResource(R.string.proof_to_note), style = HaloType.small, color = Halo.muted, lineHeight = 15.sp)
                }
            }
            Text(stringResource(R.string.proof_check_truth), style = HaloType.small, color = Halo.muted, lineHeight = 15.sp)
            Spacer(Modifier.height(4.dp))
        }
    }
}
