@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** The Pro card in Settings: status when active, or a call-to-unlock. */
@Composable
internal fun ProCard(onUnlock: () -> Unit) {
    val ctx = LocalContext.current
    val pro by Pro.isPro
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(rs(11)).background(Brush.linearGradient(listOf(Halo.mint, Halo.cyan))), contentAlignment = Alignment.Center) { HaloIcon(HIcon.GEM, Halo.ground, 20.dp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pro_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.ink)
                    Text(if (pro) stringResource(R.string.pro_active) else stringResource(R.string.pro_price, WalletActions.PRO_PRICE_SKR), fontFamily = Inter, fontSize = 12.sp, color = if (pro) Halo.mint else Halo.muted)
                }
                if (pro) HaloIcon(HIcon.CHECK, Halo.mint, 20.dp)
            }
            listOf(R.string.pro_f1, R.string.pro_f2, R.string.pro_f3, R.string.pro_f4).forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HaloIcon(if (pro) HIcon.CHECK else HIcon.GEM, if (pro) Halo.mint else Halo.cyan, 13.dp); Spacer(Modifier.width(8.dp))
                    Text(stringResource(f), fontFamily = Inter, fontSize = 12.5.sp, color = Halo.ink)
                }
            }
            if (!pro) PrimaryButton(stringResource(R.string.pro_unlock, WalletActions.PRO_PRICE_SKR), danger = false, icon = HIcon.GEM) { onUnlock() }
            else Pro.signature(ctx)?.let { Text(it, fontFamily = Mono, fontSize = 10.5.sp, color = Halo.muted, maxLines = 1) }
        }
    }
}

private sealed interface ProState {
    data object Checking : ProState
    data class Ready(val quote: WalletActions.SkrQuote) : ProState
    data class Blocked(val message: String) : ProState
    data object Signing : ProState
    data class Done(val signature: String) : ProState
    data class Error(val message: String) : ProState
}

/** Unlock ClearSign Pro with a real SKR payment on mainnet, signed by the Seed Vault. */
@Composable
internal fun ProSheet(signer: SeedVaultSigner, owner: String?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var state by remember { mutableStateOf<ProState>(ProState.Checking) }

    LaunchedEffect(owner) {
        state = when {
            owner == null -> ProState.Blocked(ctx.getString(R.string.theme_unlock_connect))
            !WalletActions.treasuryConfigured -> ProState.Blocked(ctx.getString(R.string.theme_unlock_no_treasury))
            else -> {
                val q = runCatching { WalletActions.skrQuote(owner) }.getOrNull()
                when {
                    q == null -> ProState.Error(ctx.getString(R.string.wa_no_blockhash))
                    q.account == null -> ProState.Blocked(ctx.getString(R.string.theme_unlock_no_skr))
                    !q.enoughForPro -> ProState.Blocked(ctx.getString(R.string.theme_unlock_insufficient, q.uiBalance(), WalletActions.PRO_PRICE_SKR))
                    else -> ProState.Ready(q)
                }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(rs(12)).background(Brush.linearGradient(listOf(Halo.mint, Halo.cyan))), contentAlignment = Alignment.Center) { HaloIcon(HIcon.GEM, Halo.ground, 22.dp) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.pro_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.ink)
                    Text(stringResource(R.string.pro_sheet_note), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                }
            }
            when (val s = state) {
                ProState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.theme_unlock_checking), fontFamily = Mono, fontSize = 12.5.sp, color = Halo.muted); Spacer(Modifier.width(6.dp)); BlinkCaret(Halo.mint, 13.dp) }
                is ProState.Ready -> {
                    Column(Modifier.fillMaxWidth().clip(rs(16)).background(Halo.cardSoft).border(cardBorder(), rs(16)).padding(14.dp)) {
                        StatRow(stringResource(R.string.theme_unlock_price), "${WalletActions.PRO_PRICE_SKR} SKR", accent = true)
                        StatRow(stringResource(R.string.theme_unlock_balance), s.quote.uiBalance() + " SKR")
                        StatRow(stringResource(R.string.theme_unlock_fee), "≈ 0.00001 SOL")
                        StatRow(stringResource(R.string.theme_unlock_signer), stringResource(R.string.seed_vault))
                    }
                    HoldToConfirm(stringResource(R.string.pro_hold, WalletActions.PRO_PRICE_SKR)) {
                        state = ProState.Signing
                        scope.launch {
                            state = when (val r = WalletActions.unlockPro(ctx, signer, owner!!, s.quote)) {
                                is WalletActions.Result.Sent -> ProState.Done(r.signature)
                                is WalletActions.Result.Failed -> ProState.Error(r.message)
                            }
                        }
                    }
                }
                is ProState.Blocked -> { Banner(s.message, Halo.amber, HIcon.INFO); GhostButton(stringResource(R.string.close)) { onDismiss() } }
                ProState.Signing -> Working(stringResource(R.string.theme_unlock_signing))
                is ProState.Done -> { Banner(stringResource(R.string.pro_done), Halo.mint, HIcon.CHECK); PrimaryButton(stringResource(R.string.done), danger = false) { onDismiss() } }
                is ProState.Error -> { Banner(s.message, Halo.red, HIcon.WARNING); GhostButton(stringResource(R.string.close)) { onDismiss() } }
            }
        }
    }
}
