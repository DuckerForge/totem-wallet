@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.clearsign.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Bridges opened from this phone. The leg that matters is off Solana: you sign a deposit to RocketX
 * and wait for funds on Base or Bitcoin while Solscan already says "confirmed". So each row links
 * both transactions (`originTransactionUrl`, `destinationTransactionUrl`), the amount is
 * `actualAmount`, and RocketX support opens with the order number copied. Status refreshes on open.
 */
@Composable
internal fun BridgeHistorySheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var all by remember { mutableStateOf(RocketX.bridges(ctx)) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SheetHeader(stringResource(R.string.bridge_history_title), stringResource(R.string.bridge_history_sub), HIcon.HISTORY, onClose = onDismiss)

            if (all.isEmpty()) {
                Text(stringResource(R.string.bridge_history_empty), style = HaloType.small, color = Halo.muted, lineHeight = 17.sp)
            }

            all.forEach { b ->
                var st by remember(b.requestId) { mutableStateOf<RocketX.Status?>(null) }
                var asked by remember(b.requestId) { mutableStateOf(false) }
                LaunchedEffect(b.requestId) {
                    if (b.signature.isBlank()) { asked = true; return@LaunchedEffect }
                    st = withContext(Dispatchers.IO) { runCatching { RocketX.status(b.signature, b.requestId) }.getOrNull() }
                    asked = true
                }

                val landed = st?.actualAmount?.takeIf { it > 0 } ?: b.toAmount
                Column(
                    Modifier.fillMaxWidth().clip(rs(14)).background(Halo.cardSoft).border(1.dp, Halo.stroke, rs(14)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            b.from + "  →  " + (if (st?.good == true) "" else "~") + fmtAmt(landed) + " " + b.to,
                            fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Halo.ink,
                            modifier = Modifier.weight(1f),
                        )
                        Text(fmtDay(ctx, b.at), style = HaloType.small, color = Halo.muted)
                    }
                    Text(
                        b.toNetwork + (if (b.exchange.isNotBlank()) "  ·  " + b.exchange else ""),
                        style = HaloType.small, color = Halo.muted,
                    )
                    // Destination address. The signing receipt only has the deposit address, so this is the only copy.
                    (st?.destAddress?.takeIf { it.isNotBlank() } ?: b.toAddress).takeIf { it.isNotBlank() }?.let {
                        Text(shorten(it, 6), fontFamily = Mono, fontSize = 11.sp, color = Halo.muted)
                    }
                    Text(
                        when {
                            // Order opened, deposit never signed: nothing left the phone, nothing to ask.
                            b.signature.isBlank() -> stringResource(R.string.bridge_unpaid)
                            !asked -> stringResource(R.string.bridge_asking)
                            st == null -> stringResource(R.string.bridge_status_none)
                            st!!.good -> stringResource(R.string.bridge_landed)
                            else -> (st!!.subState.ifBlank { st!!.state }).replace('_', ' ')
                        },
                        style = HaloType.small,
                        color = if (st?.good == true) Halo.mint else if (asked && st != null) Halo.amber else Halo.muted,
                    )

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (st?.originUrl ?: b.signature.takeIf { it.isNotBlank() }?.let { solscanTxUrl(it, null) })?.let { url ->
                            SmallChip(stringResource(R.string.bridge_h_sent), HIcon.EXTERNAL, tint = Halo.cyan) { open(ctx, url) }
                        }
                        // The destination tx is the only proof of arrival. Until it exists, link the
                        // address on the other chain.
                        val dest = st?.destUrl
                        if (dest != null) {
                            SmallChip(stringResource(R.string.bridge_h_got), HIcon.EXTERNAL, tint = Halo.mint) { open(ctx, dest) }
                        } else {
                            RocketX.explorerAddress(b.explorer, st?.destAddress?.takeIf { it.isNotBlank() } ?: b.toAddress)?.let { url ->
                                SmallChip(stringResource(R.string.bridge_h_where), HIcon.EXTERNAL, tint = Halo.muted) { open(ctx, url) }
                            }
                        }
                        SmallChip("RocketX", HIcon.EXTERNAL, tint = Halo.amber) {
                            copyText(ctx, b.requestId)
                            open(ctx, RocketX.ORDERS_URL)
                        }
                        if (b.signature.isBlank()) {
                            SmallChip(stringResource(R.string.bridge_h_remove), HIcon.TRASH, tint = Halo.muted) {
                                RocketX.forget(ctx, b.requestId); all = RocketX.bridges(ctx); Haptics.tick(ctx)
                            }
                        }
                    }
                }
            }
            if (all.isNotEmpty()) {
                Text(stringResource(R.string.bridge_history_note), style = HaloType.small, color = Halo.muted, lineHeight = 16.sp)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

private fun open(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** Eight decimals are enough for a bridge, and nobody wants the trailing zeros. */
private fun fmtAmt(v: Double): String =
    if (v <= 0.0) "?" else String.format(Locale.ROOT, "%.8f", v).trimEnd('0').trimEnd('.')

/** Today, yesterday, or how many days ago. A bridge lives hours, not seconds. */
private fun fmtDay(ctx: android.content.Context, at: Long): String {
    val days = ((System.currentTimeMillis() - at) / 86_400_000L).toInt()
    return when {
        days <= 0 -> ctx.getString(R.string.conn_today)
        days == 1 -> ctx.getString(R.string.conn_yesterday)
        else -> ctx.getString(R.string.conn_days_ago, days)
    }
}

/**
 * The bridge terms, shown above the signing receipt. The receipt only covers Solana (0.1 SOL to a
 * RocketX deposit address); what arrives and where is not simulated. So this shows both legs, the
 * full destination address (no fixing it afterwards) and the provider.
 */
@Composable
internal fun BridgeDealCard(deal: RocketX.Deal) {
    Column(
        Modifier.fillMaxWidth().clip(rs(14)).background(Halo.mint.copy(alpha = 0.07f))
            .border(1.dp, Halo.mint.copy(alpha = 0.35f), rs(14)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(stringResource(R.string.bridge_deal_title).uppercase(), style = HaloType.label, color = Halo.mint)
        DealRow(stringResource(R.string.bridge_deal_send), deal.fromText, Halo.ink)
        DealRow(stringResource(R.string.bridge_deal_get), "~" + fmtAmt(deal.toAmount) + " " + deal.toSymbol + "  ·  " + deal.network, Halo.mint)
        Text(deal.toAddress, fontFamily = Mono, fontSize = 11.sp, color = Halo.muted, lineHeight = 15.sp)
        Text(
            stringResource(R.string.bridge_deal_by, deal.exchange) +
                (deal.minutes?.let { "  ·  " + stringResource(R.string.bridge_deal_mins, it) } ?: ""),
            style = HaloType.small, color = Halo.muted,
        )
        Text(stringResource(R.string.bridge_deal_locked), style = HaloType.small, color = Halo.amber, lineHeight = 16.sp)
    }
}

@Composable
private fun DealRow(label: String, value: String, tint: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = HaloType.small, color = Halo.muted, modifier = Modifier.width(62.dp))
        Text(value, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = tint)
    }
}
