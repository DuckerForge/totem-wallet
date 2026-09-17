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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * A Blink, opened as a card: what it is, its buttons, and then the receipt.
 * The transaction the server hands back is treated like one from any dApp:
 * simulated, read, held to confirm, signed with the print.
 */
@Composable
internal fun BlinkSheet(link: String, signer: SeedVaultSigner, owner: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var action by remember { mutableStateOf<Blinks.Action?>(null) }
    var standing by remember { mutableStateOf(Blinks.Standing.UNKNOWN) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf<Pair<ByteArray, ReceiptEngine.Analyzed>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val values = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(link) {
        loading = true
        val api = Blinks.actionUrl(link)
        if (api == null) { error = ctx.getString(R.string.blink_not_a_link); loading = false; return@LaunchedEffect }
        val resolved = withContext(Dispatchers.IO) {
            runCatching {
                val u = URL(api)
                // A plain site link: its actions.json may say where the API is.
                val rules = runCatching { URL(u, "/actions.json").readText() }.getOrNull()
                val mapped = rules?.let { Blinks.applyRules(it, u.path) }
                if (mapped != null) URL(u, mapped).toString() else api
            }.getOrDefault(api)
        }
        standing = withContext(Dispatchers.IO) { runCatching { Blinks.standing(resolved) }.getOrDefault(Blinks.Standing.UNKNOWN) }
        action = if (standing == Blinks.Standing.BLOCKED) null else withContext(Dispatchers.IO) { runCatching { Blinks.fetch(resolved) }.getOrNull() }
        if (action == null && standing != Blinks.Standing.BLOCKED) error = ctx.getString(R.string.blink_no_answer)
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetHeader(stringResource(R.string.blink_title), runCatching { URL(Blinks.actionUrl(link) ?: link).host }.getOrDefault(""), HIcon.SPARK, onClose = onDismiss)
            if (loading) Working(stringResource(R.string.w_analyzing))
            when (standing) {
                Blinks.Standing.BLOCKED -> Banner(stringResource(R.string.blink_blocked), Halo.red, HIcon.BLOCK)
                Blinks.Standing.UNKNOWN -> if (!loading) Banner(stringResource(R.string.blink_unknown_host), Halo.amber, HIcon.WARNING)
                Blinks.Standing.TRUSTED -> if (!loading) Text(stringResource(R.string.blink_trusted), style = HaloType.small, color = Halo.mint)
            }
            error?.let { Banner(it, Halo.red, HIcon.WARNING) }

            action?.let { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    a.icon?.let { TokenLogo(a.apiUrl, a.title, it, 46.dp); Spacer(Modifier.width(12.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(a.title, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Halo.ink)
                        if (a.description.isNotBlank()) Text(a.description, style = HaloType.small, color = Halo.muted, lineHeight = 16.sp)
                    }
                }
                if (a.disabled) Banner(stringResource(R.string.blink_disabled), Halo.amber, HIcon.WARNING)

                if (review == null && done == null) {
                    a.buttons.forEach { b ->
                        b.params.forEach { p ->
                            if (p.options.isNotEmpty()) {
                                Text(p.label, style = HaloType.label, color = Halo.muted)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    p.options.take(4).forEach { (l, v) -> ModeChip(l, values[p.name] == v, Halo.cyan, Modifier.weight(1f)) { values[p.name] = v } }
                                }
                            } else {
                                OutlinedTextField(
                                    value = values[p.name] ?: "", onValueChange = { values[p.name] = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                    label = { Text(p.label, fontFamily = Inter, fontSize = 11.sp) },
                                    textStyle = TextStyle(fontFamily = Mono, fontSize = 14.sp, color = Halo.ink), colors = pickerField(), shape = rs(12),
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (p.type == "number") androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Text),
                                )
                            }
                        }
                        val ready = b.params.all { !it.required || !values[it.name].isNullOrBlank() } && !a.disabled && busy == null
                        GhostButton(b.label, Modifier.fillMaxWidth(), HIcon.SPARK, tint = Halo.mint) {
                            if (!ready) return@GhostButton
                            busy = ctx.getString(R.string.blink_building)
                            scope.launch {
                                val built = withContext(Dispatchers.IO) { runCatching { Blinks.build(Blinks.fill(b.href, values.toMap()), owner) }.getOrNull() }
                                if (built == null) { error = ctx.getString(R.string.blink_build_failed); busy = null; return@launch }
                                val analyzed = withContext(Dispatchers.IO) { runCatching { ReceiptEngine.analyze(ctx, BlocklistScanner(ctx), built.tx, owner, null, requireSim = true) }.getOrNull() }
                                busy = null
                                if (analyzed == null) { error = ctx.getString(R.string.blink_build_failed); return@launch }
                                message = built.message; review = built.tx to analyzed
                            }
                        }
                    }
                }
                busy?.let { Working(it) }

                done?.let { sig ->
                    Banner(message ?: stringResource(R.string.blink_done), Halo.mint, HIcon.CHECK)
                    Text(sig, fontFamily = Mono, fontSize = 11.sp, color = Halo.muted, maxLines = 2)
                    PrimaryButton(stringResource(R.string.done), danger = false) { onDismiss() }
                }
            }
            Text(stringResource(R.string.blink_truth), style = HaloType.small, color = Halo.muted, lineHeight = 15.sp)
            Spacer(Modifier.height(4.dp))
        }
    }

    // What you are about to sign, over everything. Not under the buttons of the
    // card that asked for it: see PayOverlay.
    review?.let { (tx, analyzed) ->
        PayOverlay(
            title = stringResource(R.string.blink_title),
            onBack = { if (busy == null) review = null },
        ) {
            SignReceiptBody(analyzed.receipt, null)
            error?.let { Banner(it, Halo.red, HIcon.WARNING) }
            busy?.let { Working(it) }
            if (analyzed.receipt.blocksApproval) {
                Banner(stringResource(R.string.send_blocked), Halo.red, HIcon.BLOCK)
            } else if (busy == null) {
                HoldToConfirm(stringResource(R.string.blink_hold)) {
                    busy = ctx.getString(R.string.w_signing)
                    scope.launch {
                        val r = WalletActions.signAndSendRaw(ctx, signer, owner, tx, analyzed.receipt, kind = "blink")
                        busy = null
                        when (r) {
                            is WalletActions.Result.Sent -> { done = r.signature; review = null }
                            is WalletActions.Result.Failed -> error = r.message
                        }
                    }
                }
            }
        }
    }
}
