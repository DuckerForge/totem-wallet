package com.clearsign.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * One DeFi position, opened: how much, since when, what it yields, where it sits. No signing.
 * Read from the same portfolio snapshot the tile comes from, so it opens at once and agrees
 * with the row above: stake is a `getProgramAccounts`, the most expensive call there is, and
 * the tile draws from cache on first launch. Pull to refresh reloads everything, sheet included.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun DefiSheet(d: DefiPosition, currency: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val detail = d.detail
    val title = when {
        d.kind == DefiPosition.Kind.LEND -> stringResource(R.string.defi_sheet_lend_title, d.symbol)
        d.symbol == "SKR" -> stringResource(R.string.defi_sheet_skr_title)
        else -> stringResource(R.string.defi_sheet_stake_title)
    }
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.lg), verticalAlignment = Alignment.CenterVertically) {
                TokenLogo(if (d.kind == DefiPosition.Kind.ORE) com.clearsign.core.Ore.MINT else d.symbol, d.symbol, d.image, 46.dp)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(title, style = HaloType.title, color = Halo.ink)
                    Text(d.sub, style = HaloType.small, color = Halo.muted)
                }
                RoundIconButton(HIcon.CLOSE, description = stringResource(R.string.close), onClick = onDismiss)
            }
            Column(Modifier.padding(horizontal = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                SoftPanel {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatRow(stringResource(R.string.defi_amount), fmtUi(d.ui) + " " + d.symbol, accent = true)
                        d.fiat?.let { StatRow(stringResource(R.string.defi_value), fmtFiat(it, currency)) }
                        d.state?.let { st ->
                            StatRow(
                                stringResource(R.string.defi_state),
                                stringResource(when (st) { "active" -> R.string.hero_stake_active; "activating" -> R.string.hero_stake_activating; "deactivating" -> R.string.hero_stake_deactivating; else -> R.string.hero_stake_inactive }),
                            )
                        }
                        when (detail) {
                            is DefiPosition.Detail.Stake -> {
                                StatRow(stringResource(R.string.defi_validator), Portfolio.validatorName(detail.voter).let { n -> if (n.contains("…")) n else n })
                                if (detail.activationEpoch < Long.MAX_VALUE && detail.epoch >= detail.activationEpoch) {
                                    val (epochs, days) = Portfolio.stakeSince(detail.epoch, detail.activationEpoch)
                                    StatRow(stringResource(R.string.defi_since), stringResource(R.string.defi_since_value, detail.activationEpoch, epochs, days.toInt()))
                                }
                            }
                            is DefiPosition.Detail.Guardians -> {
                                StatRow(stringResource(R.string.defi_guardian), shorten(detail.guardian))
                                StatRow(stringResource(R.string.defi_shares), detail.shares)
                                StatRow(stringResource(R.string.defi_share_price), String.format(java.util.Locale.getDefault(), "%.6f", detail.sharePrice))
                                StatRow(stringResource(R.string.defi_pool_total), fmtUi(detail.poolUi) + " SKR")
                            }
                            is DefiPosition.Detail.Lend -> StatRow(stringResource(R.string.defi_asset), shorten(detail.asset))
                            null -> {}
                        }
                        val apr = d.aprPct
                        StatRow(
                            stringResource(R.string.defi_yield),
                            if (apr != null) stringResource(R.string.defi_yield_value, String.format(java.util.Locale.getDefault(), "%.1f", apr)) else stringResource(R.string.defi_yield_measuring),
                        )
                        d.perDayFiat?.let { day ->
                            StatRow(stringResource(R.string.defi_per_day), fmtFiat(day, currency))
                            StatRow(stringResource(R.string.defi_per_month), fmtFiat(day * 30, currency))
                        }
                    }
                }
                Text(stringResource(R.string.defi_no_sign_note), style = HaloType.small, color = Halo.muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val account = when (detail) {
                        is DefiPosition.Detail.Stake -> detail.account
                        is DefiPosition.Detail.Guardians -> detail.account
                        else -> null
                    }
                    if (!account.isNullOrBlank()) {
                        GhostButton(stringResource(R.string.defi_open_solscan), Modifier.weight(1f), HIcon.EXTERNAL, tint = Halo.cyan) { open(solscanUrl(account, null)) }
                    }
                    if (d.kind == DefiPosition.Kind.LEND) {
                        GhostButton(stringResource(R.string.defi_open_jupiter), Modifier.weight(1f), HIcon.EXTERNAL, tint = Halo.cyan) { open("https://jup.ag/lend") }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
