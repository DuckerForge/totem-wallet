package com.clearsign.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clearsign.core.Ore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ORE, dal portafoglio. Il giro in corso con il conto alla rovescia, la
 * griglia con il SOL per casella, i tre numeri, Riscuoti e Scava.
 *
 * Il conto alla rovescia si ridisegna ogni secondo dallo slot letto una
 * volta e non chiede niente alla catena: si rilegge quando il giro e' finito
 * e la pausa e' passata, non prima. Riscuoti e Scava passano da anteprima,
 * scontrino e impronta come ogni cosa che questo portafoglio spedisce.
 */
private sealed interface OreState {
    object Idle : OreState
    object Analyzing : OreState
    data class Review(val analyzed: ReceiptEngine.Analyzed, val ixs: List<WalletTx.Instruction>, val label: String, val kind: String) : OreState
    object Signing : OreState
    data class Done(val signature: String) : OreState
    data class Error(val message: String) : OreState
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun OreSheet(owner: String, signer: SeedVaultSigner, onDismiss: (changed: Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<OreMiner.View?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var changed by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<OreState>(OreState.Idle) }
    var digging by remember { mutableStateOf(false) }
    var perSquare by remember { mutableStateOf("0.001") }
    var picked by remember { mutableStateOf(setOf<Int>()) }

    LaunchedEffect(refresh) {
        loading = true
        view = withContext(Dispatchers.IO) { runCatching { OreMiner.read(SolanaRpc.urlFor(null), owner) }.getOrNull() }
        loading = false
    }
    // Un secondo alla volta. A giro finito si aspetta la pausa e si rilegge.
    LaunchedEffect(view) {
        val v = view ?: return@LaunchedEffect
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
            if (v.secondsLeft(now) <= 0.0 && now - v.at > 20_000L) { refresh++; break }
        }
    }

    val ownerKey = remember(owner) { Base58.decodePubkey(owner) }

    fun review(kind: String, ixs: List<WalletTx.Instruction>, label: String) {
        state = OreState.Analyzing
        scope.launch {
            val analyzed = WalletActions.preview(ctx, owner, ixs)
            state = if (analyzed == null) OreState.Error(ctx.getString(R.string.wa_no_blockhash)) else OreState.Review(analyzed, ixs, label, kind)
        }
    }

    ModalBottomSheet(
        onDismissRequest = { onDismiss(changed) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TokenLogo(Ore.MINT, "ORE", TokenSymbols.image(Ore.MINT), 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ore_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.ink)
                    val v = view
                    Text(
                        when {
                            v == null -> stringResource(R.string.ore_loading)
                            v.board.waiting -> stringResource(R.string.ore_round_waiting, v.board.roundId)
                            v.secondsLeft(now) <= 0.0 -> stringResource(R.string.ore_round_between, v.board.roundId)
                            else -> stringResource(R.string.ore_round_left, v.board.roundId, v.secondsLeft(now).toInt())
                        },
                        fontFamily = Mono, fontSize = 11.5.sp, color = Halo.muted, style = Tabular,
                    )
                }
                SmallChip("ore.com", HIcon.EXTERNAL, tint = Halo.cyan) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ore.com"))) }
                }
            }

            val v = view
            when {
                loading && v == null -> Working(stringResource(R.string.ore_loading))
                v == null -> Banner(stringResource(R.string.ore_unreachable), Halo.amber, HIcon.WARNING)
                else -> {
                    // La griglia: il SOL di tutti su ogni casella, le tue accese, quelle scelte cerchiate.
                    Grid(v, picked, digging) { s -> picked = if (s in picked) picked - s else picked + s }

                    GlassCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatRow(stringResource(R.string.ore_in_play), Ore.sol(v.inPlay) + " SOL", accent = v.inPlay > 0)
                            StatRow(
                                stringResource(R.string.ore_claimable),
                                Ore.ore(v.claimableOre) + " ORE · " + Ore.sol(v.claimableSol) + " SOL" +
                                    (if (v.needsCheckpoint) " · " + stringResource(R.string.ore_checkpoint_pending) else ""),
                                accent = v.hasClaim,
                            )
                            v.miner?.let { m ->
                                StatRow(stringResource(R.string.ore_lifetime), Ore.ore(m.lifetimeRewardsOre) + " ORE · " + Ore.sol(m.lifetimeRewardsSol) + " SOL · −" + Ore.sol(m.lifetimeDeployed) + " SOL")
                            }
                            v.round?.let { r ->
                                StatRow(stringResource(R.string.ore_round_pot), Ore.sol(r.totalDeployed) + " SOL · " + stringResource(R.string.ore_miners, r.totalMiners.toInt()))
                            }
                        }
                    }

                    when (val s = state) {
                        OreState.Idle -> {
                            if (!digging) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    GhostButton(stringResource(R.string.ore_claim), Modifier.weight(1f), HIcon.COINS, tint = if (v.hasClaim) Halo.mint else Halo.muted) {
                                        val k = ownerKey ?: return@GhostButton
                                        val ixs = OreMiner.claimInstructions(k, v)
                                        if (ixs.isEmpty()) state = OreState.Error(ctx.getString(R.string.ore_none))
                                        else review("ore_claim", ixs, ctx.getString(R.string.ore_claim))
                                    }
                                    GhostButton(stringResource(R.string.ore_dig), Modifier.weight(1f), HIcon.SPARK, tint = Halo.cyan) { digging = true }
                                }
                            } else {
                                OutlinedTextField(
                                    value = perSquare, onValueChange = { perSquare = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                                    label = { Text(stringResource(R.string.ore_per_square), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 14.sp, color = Halo.ink),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Halo.mint, unfocusedBorderColor = Halo.stroke,
                                        focusedContainerColor = Halo.cardSoft, unfocusedContainerColor = Halo.cardSoft, cursorColor = Halo.mint,
                                    ),
                                    shape = rs(12),
                                )
                                val lamports = perSquare.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1e9).toLong() } ?: 0L
                                val total = lamports * picked.size
                                Text(
                                    if (picked.isEmpty()) stringResource(R.string.ore_pick_squares)
                                    else stringResource(R.string.ore_total, picked.size, Ore.sol(lamports), Ore.sol(total)) +
                                        (if (v.miner == null) " " + stringResource(R.string.ore_first_fee) else ""),
                                    fontFamily = Inter, fontSize = 12.sp, color = Halo.ink,
                                )
                                Text(stringResource(R.string.ore_wager_note), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.amber)
                                val open = v.open(now)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    GhostButton(stringResource(R.string.back), Modifier.weight(1f)) { digging = false; picked = emptySet() }
                                    PrimaryButton(
                                        if (open) stringResource(R.string.ore_dig) else stringResource(R.string.ore_wait_round),
                                        danger = false, enabled = open && lamports > 0 && picked.isNotEmpty(), fillWidth = false,
                                    ) {
                                        val k = ownerKey ?: return@PrimaryButton
                                        state = OreState.Analyzing
                                        scope.launch {
                                            val cfg = withContext(Dispatchers.IO) { OreMiner.config(SolanaRpc.urlFor(null)) }
                                            if (cfg == null) { state = OreState.Error(ctx.getString(R.string.ore_unreachable)); return@launch }
                                            review("ore_dig", listOf(OreMiner.deploy(k, lamports, picked, v.board, cfg)), ctx.getString(R.string.ore_dig))
                                        }
                                    }
                                }
                            }
                        }
                        OreState.Analyzing -> Working(stringResource(R.string.send_analyzing))
                        is OreState.Review -> {
                            val r = s.analyzed.receipt
                            r.calls.forEach { c ->
                                Text(c.method, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.ink)
                                c.args.forEach { (n, value) -> Text("$n · $value", fontFamily = Mono, fontSize = 11.sp, color = Halo.muted) }
                            }
                            r.outflows.forEach { d -> Text("−" + fmtAmt(d), fontFamily = Mono, fontSize = 12.sp, color = Halo.red, style = Tabular) }
                            r.inflows.forEach { d -> Text("+" + fmtAmt(d), fontFamily = Mono, fontSize = 12.sp, color = Halo.mint, style = Tabular) }
                            r.risks.forEach { RiskRow(it) }
                            if (r.blocksApproval) {
                                Banner(stringResource(R.string.send_blocked), Halo.red, HIcon.BLOCK)
                                GhostButton(stringResource(R.string.back)) { state = OreState.Idle }
                            } else {
                                HoldToConfirm(if (s.kind == "ore_dig") stringResource(R.string.ore_hold_dig) else stringResource(R.string.ore_hold_claim)) {
                                    state = OreState.Signing
                                    scope.launch {
                                        val log = WalletActions.LogInfo(kind = s.kind, outflows = r.outflows.map { "−" + fmtAmt(it) }, inflows = r.inflows.map { "+" + fmtAmt(it) }, receipt = r, recipientLabel = "ORE")
                                        state = when (val res = WalletActions.signAndSend(ctx, signer, owner, s.ixs, log)) {
                                            is WalletActions.Result.Sent -> { changed = true; digging = false; picked = emptySet(); refresh++; OreState.Done(res.signature) }
                                            is WalletActions.Result.Failed -> OreState.Error(res.message)
                                        }
                                    }
                                }
                                GhostButton(stringResource(R.string.back)) { state = OreState.Idle }
                            }
                        }
                        OreState.Signing -> Working(stringResource(R.string.theme_unlock_signing))
                        is OreState.Done -> {
                            Banner(stringResource(R.string.ore_sent), Halo.mint, HIcon.CHECK)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                GhostButton("Solscan", Modifier.weight(1f), HIcon.EXTERNAL, tint = Halo.cyan) {
                                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(solscanTxUrl(s.signature, null)))) }
                                }
                                GhostButton(stringResource(R.string.done), Modifier.weight(1f)) { state = OreState.Idle }
                            }
                        }
                        is OreState.Error -> {
                            Banner(s.message, Halo.red, HIcon.WARNING)
                            GhostButton(stringResource(R.string.back)) { state = OreState.Idle }
                        }
                    }
                }
            }
            Text(stringResource(R.string.ore_stake_link), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted)
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** Cinque per cinque. Il numero e' il SOL di tutti sulla casella; il bordo dice le tue e quelle che stai scegliendo. */
@Composable
private fun Grid(v: OreMiner.View, picked: Set<Int>, picking: Boolean, onPick: (Int) -> Unit) {
    val mine = v.mySquares.toSet()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in 0 until 5) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0 until 5) {
                    val s = row * 5 + col
                    val sol = v.round?.deployed?.getOrNull(s) ?: 0L
                    val isMine = s in mine
                    val isPicked = picking && s in picked
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(rs(8))
                            .background(if (isPicked) Halo.cyan.copy(alpha = 0.22f) else if (isMine) Halo.mint.copy(alpha = 0.18f) else Halo.cardSoft)
                            .border(1.dp, if (isPicked) Halo.cyan else if (isMine) Halo.mint else Halo.stroke, rs(8))
                            .clickable(enabled = picking) { onPick(s) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text((s + 1).toString(), fontFamily = Mono, fontSize = 9.sp, color = Halo.muted)
                            Text(if (sol > 0) fmtSol(sol, 3) else "·", fontFamily = Mono, fontSize = 10.sp, color = if (isMine || isPicked) Halo.ink else Halo.muted, style = Tabular)
                        }
                    }
                }
            }
        }
    }
}
