@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * "Delegations & accounts": the wallet hygiene most wallets hide. Lists every
 * token account a third party can still spend from (an approval left behind by
 * a dApp) with a one-tap revoke, and every empty token account whose rent can
 * be reclaimed by closing it. Each action is a real transaction: mini receipt,
 * hold-to-sign, Seed Vault.
 */
@Composable
internal fun AccountsCard(signer: SeedVaultSigner, owner: String?) {
    var loading by remember { mutableStateOf(false) }
    var accounts by remember { mutableStateOf<List<SolanaRpc.TokenAccountInfo>?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var action by remember { mutableStateOf<HygieneAction?>(null) }

    LaunchedEffect(owner, refreshKey) {
        if (owner == null) { accounts = null; return@LaunchedEffect }
        loading = true
        accounts = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SolanaRpc.tokenAccountsOf(SolanaRpc.urlFor(null), owner, force = refreshKey > 0) }
        }.getOrDefault(emptyList())
        loading = false
    }

    val delegated = accounts?.filter { it.hasActiveDelegate } ?: emptyList()
    val empties = owner?.let { o -> accounts?.filter { it.isClosableBy(o) } } ?: emptyList()

    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(
                stringResource(R.string.home_accounts_hdr),
                when {
                    owner == null -> stringResource(R.string.home_accounts_sub)
                    loading && accounts == null -> stringResource(R.string.deleg_loading)
                    delegated.isEmpty() && empties.isEmpty() -> stringResource(R.string.deleg_clean)
                    else -> stringResource(R.string.deleg_count, delegated.size, empties.size)
                },
                HIcon.KEY,
            )
            when {
                owner == null -> Text(stringResource(R.string.deleg_connect), fontFamily = Inter, fontSize = 12.5.sp, color = Halo.muted)
                loading && accounts == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.deleg_scanning), fontFamily = Mono, fontSize = 12.sp, color = Halo.muted); Spacer(Modifier.width(6.dp)); BlinkCaret(Halo.mint, 12.dp)
                }
                delegated.isEmpty() && empties.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                    HaloIcon(HIcon.SHIELD_LOCK, Halo.mint, 16.dp); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.deleg_none), fontFamily = Inter, fontSize = 12.5.sp, color = Halo.muted)
                }
                else -> {
                    delegated.forEachIndexed { i, a ->
                        Box(Modifier.staggeredEntrance(i, a.pubkey)) { DelegateRow(a) { action = HygieneAction.Revoke(a) } }
                    }
                    empties.forEachIndexed { i, a ->
                        Box(Modifier.staggeredEntrance(delegated.size + i, a.pubkey)) { EmptyRow(a) { action = HygieneAction.Close(listOf(a)) } }
                    }
                    if (empties.size > 1) {
                        val batch = empties.take(10)
                        GhostButton(stringResource(R.string.deleg_close_all, batch.size, fmtSol(batch.sumOf { it.lamports }, 4)), icon = HIcon.TRASH, tint = Halo.cyan) {
                            action = HygieneAction.Close(batch)
                        }
                    }
                }
            }
        }
    }

    action?.let { a ->
        HygieneSheet(a, signer, owner ?: return@let, onDismiss = { done -> action = null; if (done) refreshKey++ })
    }
}

private sealed interface HygieneAction {
    data class Revoke(val account: SolanaRpc.TokenAccountInfo) : HygieneAction
    data class Close(val accounts: List<SolanaRpc.TokenAccountInfo>) : HygieneAction
}

@Composable
private fun ActionChip(label: String, tint: Color, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.pressScale(src).clip(rs(10)).background(tint.copy(alpha = 0.12f)).border(1.dp, tint.copy(alpha = 0.5f), rs(10))
            .clickable(interactionSource = src, indication = null) { onClick() }.padding(horizontal = 12.dp, vertical = 8.dp),
    ) { Text(label, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = tint) }
}

@Composable
private fun DelegateRow(a: SolanaRpc.TokenAccountInfo, onRevoke: () -> Unit) {
    val symbol = TokenSymbols.symbol(a.mint)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(rs(10)).background(Halo.amber.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { HaloIcon(HIcon.UNLOCK, Halo.amber, 18.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.deleg_row_title, symbol), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = Halo.ink, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(shorten(a.delegate ?: "", 5), fontFamily = Mono, fontSize = 11.sp, color = Halo.muted)
                Spacer(Modifier.width(6.dp))
                if (a.isUnlimitedDelegation) {
                    Row(Modifier.clip(rs(6)).background(Halo.redSoft).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        HaloIcon(HIcon.INFINITY, Halo.red, 11.dp); Spacer(Modifier.width(3.dp))
                        Text(stringResource(R.string.deleg_unlimited), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, color = Halo.red)
                    }
                } else {
                    Text(stringResource(R.string.deleg_amount, fmtSol(a.delegatedAmount, minOf(a.decimals, 4)) + " " + symbol), fontFamily = Inter, fontSize = 11.sp, color = Halo.amber, style = Tabular)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        ActionChip(stringResource(R.string.deleg_revoke), Halo.amber, onRevoke)
    }
}

@Composable
private fun EmptyRow(a: SolanaRpc.TokenAccountInfo, onClose: () -> Unit) {
    val symbol = TokenSymbols.symbol(a.mint)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(rs(10)).background(Halo.cardSoft), contentAlignment = Alignment.Center) { HaloIcon(HIcon.TRASH, Halo.cyan, 18.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.empty_row_title, symbol), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = Halo.ink, maxLines = 1)
            Text(stringResource(R.string.empty_row_sub, fmtSol(a.lamports, 5)), fontFamily = Inter, fontSize = 11.sp, color = Halo.cyan, style = Tabular)
        }
        Spacer(Modifier.width(8.dp))
        ActionChip(stringResource(R.string.deleg_close), Halo.cyan, onClose)
    }
}

private sealed interface SheetState {
    data object Ready : SheetState
    data object Signing : SheetState
    data class Done(val signature: String) : SheetState
    data class Error(val message: String) : SheetState
}

@Composable
private fun HygieneSheet(action: HygieneAction, signer: SeedVaultSigner, owner: String, onDismiss: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var state by remember { mutableStateOf<SheetState>(SheetState.Ready) }
    val done = state is SheetState.Done

    val title: String; val what: String; val hold: String; val icon: HIcon; val tint: Color; val refund: Long?
    when (action) {
        is HygieneAction.Revoke -> {
            val sym = TokenSymbols.symbol(action.account.mint)
            title = stringResource(R.string.sheet_revoke_title); what = stringResource(R.string.sheet_revoke_what, sym)
            hold = stringResource(R.string.hold_revoke); icon = HIcon.KEY; tint = Halo.amber; refund = null
        }
        is HygieneAction.Close -> {
            title = stringResource(R.string.sheet_close_title); what = stringResource(R.string.sheet_close_what, action.accounts.size)
            hold = stringResource(R.string.hold_close); icon = HIcon.TRASH; tint = Halo.cyan; refund = action.accounts.sumOf { it.lamports }
        }
    }

    ModalBottomSheet(onDismissRequest = { onDismiss(done) }, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(rs(12)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { HaloIcon(icon, tint, 22.dp) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.ink)
                    Text(what, fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                }
            }
            when (val s = state) {
                SheetState.Ready -> {
                    Column(Modifier.fillMaxWidth().clip(rs(16)).background(Halo.cardSoft).border(1.dp, Halo.stroke, rs(16)).padding(14.dp)) {
                        if (action is HygieneAction.Revoke) StatRow(stringResource(R.string.deleg_delegate), shorten(action.account.delegate ?: "", 6))
                        if (refund != null) StatRow(stringResource(R.string.sheet_refund), "+" + fmtSol(refund, 5) + " SOL", accent = true)
                        StatRow(stringResource(R.string.theme_unlock_fee), "≈ 0.00001 SOL")
                        StatRow(stringResource(R.string.theme_unlock_signer), stringResource(R.string.seed_vault))
                    }
                    HoldToConfirm(hold) {
                        state = SheetState.Signing
                        scope.launch {
                            val ownerKey = Base58.decode(owner)
                            val (ixs, log) = when (action) {
                                is HygieneAction.Revoke -> {
                                    val a = action.account
                                    listOf(WalletTx.tokenRevoke(Base58.decode(a.pubkey), ownerKey, WalletTx.tokenProgramFor(a.program))) to
                                        WalletActions.LogInfo(kind = "revoke", recipient = a.delegate, recipientLabel = ctx.getString(R.string.wa_log_revoke, TokenSymbols.symbol(a.mint)))
                                }
                                is HygieneAction.Close -> {
                                    action.accounts.map { a -> WalletTx.tokenCloseAccount(Base58.decode(a.pubkey), ownerKey, ownerKey, WalletTx.tokenProgramFor(a.program)) } to
                                        WalletActions.LogInfo(kind = "close", inflows = listOf("+" + fmtSol(refund ?: 0L, 5) + " SOL"), recipientLabel = ctx.getString(R.string.wa_log_close, action.accounts.size))
                                }
                            }
                            state = when (val r = WalletActions.signAndSend(ctx, signer, owner, ixs, log)) {
                                is WalletActions.Result.Sent -> SheetState.Done(r.signature)
                                is WalletActions.Result.Failed -> SheetState.Error(r.message)
                            }
                        }
                    }
                }
                SheetState.Signing -> Working(stringResource(R.string.theme_unlock_signing))
                is SheetState.Done -> {
                    Banner(if (action is HygieneAction.Revoke) stringResource(R.string.sheet_done_revoke) else stringResource(R.string.sheet_done_close), Halo.mint, HIcon.CHECK)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton(stringResource(R.string.copy), Modifier.weight(1f), HIcon.COPY) {
                            (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("signature", s.signature))
                        }
                        GhostButton("Solscan", Modifier.weight(1f), HIcon.EXTERNAL, tint = Halo.cyan) {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(solscanTxUrl(s.signature, null)))) }
                        }
                    }
                    PrimaryButton(stringResource(R.string.done), danger = false) { onDismiss(true) }
                }
                is SheetState.Error -> {
                    Banner(s.message, Halo.red, HIcon.WARNING)
                    GhostButton(stringResource(R.string.close)) { onDismiss(false) }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
