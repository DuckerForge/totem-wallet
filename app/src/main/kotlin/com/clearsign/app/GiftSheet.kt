@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Giving money to someone who has no wallet yet, or whose address you do not
 * know: put it behind a link.
 *
 * The honest part is on the screen, not in a footnote — the link carries the
 * key, so the first person to open it takes the money. In exchange it works with
 * anybody, over any messenger, with no contract and no address.
 */
@Composable
internal fun GiftSheet(signer: SeedVaultSigner, owner: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var amount by remember { mutableStateOf("0.005") }
    var note by remember { mutableStateOf("") }
    var link by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The gift hands real money to a key in a link. You see it first, like everywhere else.
    var review by remember { mutableStateOf<ReceiptEngine.Analyzed?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val gifts = remember(refresh) { MoneyLinks.all(ctx) }
    val lamports = (amount.replace(',', '.').toDoubleOrNull() ?: 0.0).let { (it * 1e9).toLong() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.95f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(rs(12)).background(Halo.mint.copy(alpha = 0.15f)).padding(9.dp)) { HaloIcon(HIcon.SHARE, Halo.mint, 20.dp) }
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.gift_title), fontFamily = Sora, fontWeight = FontWeight.Bold,
                    fontSize = 19.sp, color = Halo.ink, modifier = Modifier.weight(1f),
                )
                // A way out that does not depend on knowing you can swipe a sheet down.
                Box(
                    Modifier.size(34.dp).clip(rs(999)).background(Halo.card).haloBorder(rs(999)).clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) { HaloIcon(HIcon.CLOSE, Halo.muted, 16.dp) }
            }

            if (link != null) {
                Text(stringResource(R.string.gift_ready), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.mint)
                QrTile(link!!, size = 220.dp)
                Text(link!!, fontFamily = Mono, fontSize = 10.sp, color = Halo.muted, lineHeight = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(stringResource(R.string.copy), Modifier.weight(1f), HIcon.COPY, tint = Halo.cyan) { copyText(ctx, link!!) }
                    GhostButton(stringResource(R.string.share), Modifier.weight(1f), HIcon.SHARE, tint = Halo.mint) {
                        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                        Door.hold(); runCatching { ctx.startActivity(Intent.createChooser(i, null)) }
                    }
                }
                Banner(stringResource(R.string.gift_warn), Halo.amber, HIcon.WARNING)
                GhostButton(stringResource(R.string.close), Modifier.fillMaxWidth()) { onDismiss() }
            } else {
                GiftAnimation()
                Text(stringResource(R.string.gift_body), fontFamily = Inter, fontSize = 12.5.sp, color = Halo.muted, lineHeight = 18.sp)
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.gift_amount), fontFamily = Inter, fontSize = 11.sp) },
                    suffix = { Text("SOL", fontFamily = Mono, fontSize = 12.sp, color = Halo.muted) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 16.sp, color = Halo.ink),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Halo.mint, unfocusedBorderColor = Halo.stroke,
                        focusedContainerColor = Halo.cardSoft, unfocusedContainerColor = Halo.cardSoft, cursorColor = Halo.mint,
                    ),
                    shape = rs(12),
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it.take(60) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.gift_note), fontFamily = Inter, fontSize = 11.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Inter, fontSize = 14.sp, color = Halo.ink),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Halo.mint, unfocusedBorderColor = Halo.stroke,
                        focusedContainerColor = Halo.cardSoft, unfocusedContainerColor = Halo.cardSoft, cursorColor = Halo.mint,
                    ),
                    shape = rs(12),
                )
                Banner(stringResource(R.string.gift_warn), Halo.amber, HIcon.WARNING)
                error?.let { Banner(it, Halo.red, HIcon.WARNING) }
                if (!busy && review == null && lamports > 0L) {
                    PrimaryButton(stringResource(R.string.env_review), danger = false, icon = HIcon.RECEIPT) {
                        busy = true
                        scope.launch {
                            val from = Base58.decodePubkey(owner)
                            val a = if (from == null) null else MoneyLinks.previewGift(ctx, owner, lamports)
                            busy = false
                            if (a == null) error = ctx.getString(R.string.wa_sim_generic) else review = a
                        }
                    }
                }

                if (busy && review == null) Working(stringResource(R.string.gift_creating))

                // Gifts nobody has taken yet are still yours: this is the way back.
                val open = gifts.filter { it.open }
                if (open.isNotEmpty()) {
                    Text(stringResource(R.string.gift_open), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
                    open.forEach { g ->
                        Row(
                            Modifier.fillMaxWidth().clip(rs(12)).background(Halo.cardSoft).haloBorder(rs(12)).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(fmtSol(g.lamports, 5) + " SOL", fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Halo.ink, style = Tabular)
                                Text(g.note.ifBlank { shorten(g.pubkey, 4) }, fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, maxLines = 1)
                            }
                            GhostButton(stringResource(R.string.gift_reclaim), Modifier, HIcon.RECEIVE, tint = Halo.cyan) {
                                scope.launch {
                                    error = MoneyLinks.reclaim(ctx, g, owner)
                                    refresh++
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                GhostButton(stringResource(R.string.cancel)) { onDismiss() }
            }
        }
    }

    // What you are about to sign, over everything, on its own. Not appended to
    // the bottom of the form the way it used to be: see PayOverlay.
    review?.let { r ->
        PayOverlay(
            title = stringResource(R.string.gift_title),
            hint = stringResource(R.string.gift_review_hint),
            onBack = { if (!busy) review = null },
        ) {
            Column { SignReceiptBody(r.receipt, null, hero = false) }
            error?.let { Banner(it, Halo.red, HIcon.WARNING) }
            if (busy) {
                Working(stringResource(R.string.gift_creating))
            } else {
                HoldToConfirm(stringResource(R.string.gift_create, fmtSol(lamports, 5))) {
                    busy = true; error = null
                    scope.launch {
                        val (url, err) = MoneyLinks.createGift(ctx, signer, owner, lamports, note)
                        busy = false
                        if (url != null) { link = url; review = null; refresh++ } else error = err
                    }
                }
            }
        }
    }
}
