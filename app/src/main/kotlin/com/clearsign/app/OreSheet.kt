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
import com.clearsign.core.OreOdds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ORE, dal portafoglio. In alto quello che si puo' fare, sotto la griglia,
 * in fondo i numeri: i bottoni e lo scontrino si vedono senza scorrere, e
 * la griglia sta subito sotto il dito quando tocca scegliere le caselle.
 *
 * Il conto alla rovescia si ridisegna ogni secondo dallo slot letto una
 * volta e non chiede niente alla catena: si rilegge quando il giro e' finito
 * e la pausa e' passata. Riscuoti e Scava passano da anteprima, scontrino e
 * impronta come ogni cosa che questo portafoglio spedisce.
 *
 * Un giro dura una cinquantina di secondi e l'impronta ne porta via
 * qualcuno: il Deploy si ricostruisce sul giro del momento quando si tiene
 * premuto, e solo se restano almeno [SIGN_MARGIN_S] secondi. Visto sul
 * telefono il 22 settembre: firmato a giro finito, il nodo rifiuta.
 */
private sealed interface OreState {
    object Idle : OreState
    object Analyzing : OreState
    /** [dig] e' quanto per casella e quali caselle: alla firma il Deploy si ricostruisce sul giro di adesso. */
    data class Review(val analyzed: ReceiptEngine.Analyzed, val ixs: List<WalletTx.Instruction>, val kind: String, val dig: Pair<Long, Set<Int>>? = null) : OreState
    object Signing : OreState
    data class Done(val signature: String, val what: String, val dig: Boolean) : OreState
    data class Error(val message: String) : OreState
}

/** Quanto deve restare del giro per firmare un Deploy: l'anteprima, il dito, l'impronta e l'invio. */
private const val SIGN_MARGIN_S = 15.0

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
    /** «Aspetta il prossimo giro», che sparisce da solo quando il giro riparte: non e' un errore, e' un momento. */
    var waiting by remember { mutableStateOf(false) }
    /** Quanto vale un ORE in SOL, per dire se al prezzo di oggi la puntata conviene. Null finche' non si sa. */
    var oreSol by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) {
        val px = withContext(Dispatchers.IO) { runCatching { Prices.usd(listOf(Ore.MINT, com.clearsign.core.NATIVE_SOL_MINT)) }.getOrNull() }
        val o = px?.get(Ore.MINT); val s = px?.get(com.clearsign.core.NATIVE_SOL_MINT)
        if (o != null && s != null && s > 0) oreSol = o / s
    }

    LaunchedEffect(refresh) {
        loading = true
        view = withContext(Dispatchers.IO) { runCatching { OreMiner.read(SolanaRpc.urlFor(null), owner) }.getOrNull() }
        loading = false
    }
    // Un secondo alla volta. A giro finito si aspetta la pausa, quaranta slot,
    // e si rilegge; se il giro nuovo non e' ancora partito si riprova ogni
    // dodici secondi, solo con il foglio aperto nella pausa.
    LaunchedEffect(view) {
        val v = view ?: return@LaunchedEffect
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
            if (waiting && v.open(now, SIGN_MARGIN_S)) waiting = false
            if (v.secondsLeft(now) <= 0.0 && now - v.at > 12_000L) { refresh++; break }
        }
    }

    val ownerKey = remember(owner) { Base58.decodePubkey(owner) }

    fun review(kind: String, ixs: List<WalletTx.Instruction>, dig: Pair<Long, Set<Int>>? = null) {
        state = OreState.Analyzing
        scope.launch {
            val analyzed = WalletActions.preview(ctx, owner, ixs)
            state = if (analyzed == null) OreState.Error(ctx.getString(R.string.wa_no_blockhash)) else OreState.Review(analyzed, ixs, kind, dig)
        }
    }

    /** Un rifiuto del nodo che parla del giro finito, detto in parole. */
    fun explain(message: String): String =
        if (message.contains("invalid account data", true) || message.contains("InvalidAccountData") || message.contains("invalid seeds", true)) ctx.getString(R.string.ore_round_ended_signing)
        else message

    ModalBottomSheet(
        onDismissRequest = { onDismiss(changed) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null,
    ) {
        // Il foglio prende tutto lo schermo ed e' una finestra a parte, che si
        // mangia i margini di sistema: `statusBarsPadding` qui vale zero. La
        // barra di stato si misura dalle risorse, che non mentono.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val statusBar = remember(density) {
            with(density) {
                val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
                (if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0).toDp()
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = statusBar).imePadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val v = view
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TokenLogo(Ore.MINT, "ORE", TokenSymbols.image(Ore.MINT), 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ore_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Halo.ink)
                    Text(stringResource(R.string.ore_subtitle), style = HaloType.small, color = Halo.muted)
                }
                SmallChip("ore.com", HIcon.EXTERNAL, tint = Halo.cyan) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ore.com"))) }
                }
            }
            // Il giro: una barra che si svuota col tempo, nei colori del marchio,
            // e il numero del giro accanto. Nella pausa la barra e' vuota e ambra.
            if (v != null) {
                val left = v.secondsLeft(now)
                val roundS = 200 * Ore.SLOT_MS / 1000.0
                val frac = if (v.board.waiting) 0f else (left / roundS).toFloat().coerceIn(0f, 1f)
                val paused = v.board.waiting || left <= 0.0
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.ore_round_n, v.board.roundId).uppercase(), style = HaloType.label, color = Halo.muted, modifier = Modifier.weight(1f))
                        Text(
                            when {
                                v.board.waiting -> stringResource(R.string.ore_round_waiting_short)
                                paused -> stringResource(R.string.ore_round_between_short)
                                else -> stringResource(R.string.ore_seconds, left.toInt())
                            },
                            fontFamily = Mono, fontSize = 12.sp, color = if (paused) Halo.amber else Halo.mint, style = Tabular,
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(rs(3)).background(Halo.cardSoft)) {
                        Box(
                            Modifier.fillMaxWidth(frac).height(5.dp).clip(rs(3))
                                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Halo.mint, Halo.cyan))),
                        )
                    }
                }
            }

            when {
                loading && v == null -> Working(stringResource(R.string.ore_loading))
                v == null -> Banner(stringResource(R.string.ore_unreachable), Halo.amber, HIcon.WARNING)
                else -> {
                    // ---- quello che si puo' fare, sopra la griglia ------------------------
                    when (val s = state) {
                        OreState.Idle -> {
                            if (!digging) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    GhostButton(stringResource(R.string.ore_claim), Modifier.weight(1f), HIcon.COINS, tint = if (v.hasClaim) Halo.mint else Halo.muted) {
                                        val k = ownerKey ?: return@GhostButton
                                        val ixs = OreMiner.claimInstructions(k, v)
                                        if (ixs.isEmpty()) state = OreState.Error(ctx.getString(R.string.ore_none))
                                        else review("ore_claim", ixs)
                                    }
                                    Chunky(stringResource(R.string.ore_dig), HIcon.SPARK, Modifier.weight(1f)) { digging = true }
                                }
                                Text(stringResource(R.string.ore_hint_idle), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted)
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
                                if (picked.isNotEmpty()) {
                                    Text(
                                        stringResource(R.string.ore_total, picked.size, Ore.sol(lamports), Ore.sol(total)) +
                                            (if (v.miner == null) " " + stringResource(R.string.ore_first_fee) else ""),
                                        fontFamily = Inter, fontSize = 12.sp, color = Halo.ink,
                                    )
                                }
                                // Le caselle piu' vuote adesso: la quota di ORE e' la mia parte
                                // della casella, e il costo e' lo stesso ovunque.
                                v.round?.let { r ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(stringResource(R.string.ore_best_label), style = HaloType.small, color = Halo.muted)
                                        listOf(1, 3, 5).forEach { k ->
                                            SmallChip(k.toString(), null, tint = Halo.cyan) { picked = OreOdds.best(k, r.deployed, r.count).toSet(); Haptics.tick(ctx) }
                                        }
                                    }
                                }
                                // L'atteso, con le regole del programma: quanto ORE, quanto SOL
                                // resta sul tavolo, e se al prezzo di oggi torna.
                                val r = v.round
                                if (r != null && lamports > 0 && picked.isNotEmpty()) {
                                    val outlook = OreOdds.outlook(lamports, picked.sorted().map { r.deployed[it] }, r.expectedReward, v.motherlode)
                                    val pct = String.format(java.util.Locale.ROOT, "%.1f", outlook.costFraction * 100)
                                    val px = oreSol
                                    val worth = px?.let { outlook.expectedOre / Ore.ONE_ORE.toDouble() * it }
                                    Text(
                                        if (worth != null) stringResource(R.string.ore_outlook, Ore.ore(outlook.expectedOre), Ore.sol((worth * 1e9).toLong()), Ore.sol(outlook.expectedCost), pct)
                                        else stringResource(R.string.ore_outlook_nopx, Ore.ore(outlook.expectedOre), Ore.sol(outlook.expectedCost), pct),
                                        style = HaloType.small, color = Halo.ink,
                                    )
                                    if (worth != null) {
                                        val good = worth * 1e9 > outlook.expectedCost
                                        Text(stringResource(if (good) R.string.ore_outlook_good else R.string.ore_outlook_bad), style = HaloType.label, color = if (good) Halo.mint else Halo.amber)
                                    }
                                }
                                Text(stringResource(R.string.ore_wager_note), style = HaloType.small, color = Halo.muted)
                                val open = v.open(now, SIGN_MARGIN_S)
                                if (waiting || !open) Banner(stringResource(R.string.ore_wait_banner), Halo.amber, HIcon.HOURGLASS)
                                // Il bottone dice cosa manca: le caselle, o il giro.
                                PrimaryButton(
                                    when {
                                        picked.isEmpty() -> stringResource(R.string.ore_pick_squares)
                                        !open -> stringResource(R.string.ore_wait_round)
                                        else -> stringResource(R.string.ore_dig)
                                    },
                                    danger = false, enabled = open && lamports > 0 && picked.isNotEmpty(),
                                ) {
                                    val k = ownerKey ?: return@PrimaryButton
                                    state = OreState.Analyzing
                                    scope.launch {
                                        val rpc = SolanaRpc.urlFor(null)
                                        val (cfg, fresh) = withContext(Dispatchers.IO) { OreMiner.config(rpc) to runCatching { OreMiner.read(rpc, owner, withRound = false) }.getOrNull() }
                                        if (cfg == null || fresh == null) { state = OreState.Error(ctx.getString(R.string.ore_unreachable)); return@launch }
                                        if (!fresh.open(margin = SIGN_MARGIN_S)) { view = fresh; waiting = true; state = OreState.Idle; return@launch }
                                        review("ore_dig", listOf(OreMiner.deploy(k, lamports, picked, fresh.board, cfg)), dig = lamports to picked)
                                    }
                                }
                                GhostButton(stringResource(R.string.back), Modifier.fillMaxWidth()) { digging = false; picked = emptySet() }
                            }
                        }
                        OreState.Analyzing -> Working(stringResource(R.string.send_analyzing))
                        is OreState.Review -> {
                            val r = s.analyzed.receipt
                            GlassCard {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    r.calls.forEach { c ->
                                        Text(c.method, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.ink)
                                        c.args.forEach { (n, value) -> Text("$n · $value", fontFamily = Mono, fontSize = 11.sp, color = Halo.muted) }
                                    }
                                    r.outflows.forEach { d -> Text("−" + fmtAmt(d), fontFamily = Mono, fontSize = 12.sp, color = Halo.red, style = Tabular) }
                                    r.inflows.forEach { d -> Text("+" + fmtAmt(d), fontFamily = Mono, fontSize = 12.sp, color = Halo.mint, style = Tabular) }
                                }
                            }
                            r.risks.forEach { RiskRow(it) }
                            if (r.blocksApproval) {
                                Banner(stringResource(R.string.send_blocked), Halo.red, HIcon.BLOCK)
                                GhostButton(stringResource(R.string.back), Modifier.fillMaxWidth()) { state = OreState.Idle }
                            } else {
                                val stillOpen = s.dig == null || v.open(now, SIGN_MARGIN_S)
                                if (!stillOpen) Banner(stringResource(R.string.ore_round_gone), Halo.amber, HIcon.HOURGLASS)
                                HoldToConfirm(if (s.kind == "ore_dig") stringResource(R.string.ore_hold_dig) else stringResource(R.string.ore_hold_claim), enabled = stillOpen) {
                                    state = OreState.Signing
                                    scope.launch {
                                        // Il Deploy si ricostruisce sul giro di adesso: fra l'anteprima e il
                                        // dito puo' passare un giro intero. Stesse caselle, stessi SOL.
                                        val ixs = if (s.dig == null) s.ixs else {
                                            val k = ownerKey
                                            val rpc = SolanaRpc.urlFor(null)
                                            val (cfg, fresh) = withContext(Dispatchers.IO) { OreMiner.config(rpc) to runCatching { OreMiner.read(rpc, owner, withRound = false) }.getOrNull() }
                                            if (k == null || cfg == null || fresh == null) { state = OreState.Error(ctx.getString(R.string.ore_unreachable)); return@launch }
                                            if (!fresh.open(margin = SIGN_MARGIN_S)) { view = fresh; waiting = true; state = OreState.Idle; return@launch }
                                            listOf(OreMiner.deploy(k, s.dig.first, s.dig.second, fresh.board, cfg))
                                        }
                                        val log = WalletActions.LogInfo(kind = s.kind, outflows = r.outflows.map { "−" + fmtAmt(it) }, inflows = r.inflows.map { "+" + fmtAmt(it) }, receipt = r, recipientLabel = "ORE")
                                        state = when (val res = WalletActions.signAndSend(ctx, signer, owner, ixs, log)) {
                                            is WalletActions.Result.Sent -> {
                                                changed = true; digging = false; picked = emptySet(); refresh++
                                                OreState.Done(res.signature, r.calls.joinToString(" · ") { it.method }, dig = s.dig != null)
                                            }
                                            is WalletActions.Result.Failed -> OreState.Error(explain(res.message))
                                        }
                                    }
                                }
                                GhostButton(stringResource(R.string.back), Modifier.fillMaxWidth()) { state = OreState.Idle }
                            }
                        }
                        OreState.Signing -> Working(stringResource(R.string.theme_unlock_signing))
                        is OreState.Done -> {
                            // Lo scontrino resta qui dopo la firma, e sta anche fra gli Scontrini.
                            Banner(stringResource(R.string.ore_sent), Halo.mint, HIcon.CHECK)
                            GlassCard {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(s.what, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.ink)
                                    Text(s.signature, fontFamily = Mono, fontSize = 10.5.sp, color = Halo.muted, maxLines = 2)
                                    Text(stringResource(if (s.dig) R.string.ore_done_receipt else R.string.ore_done_receipt_claim), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                GhostButton("Solscan", Modifier.weight(1f), HIcon.EXTERNAL, tint = Halo.cyan) {
                                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(solscanTxUrl(s.signature, null)))) }
                                }
                                GhostButton(stringResource(R.string.done), Modifier.weight(1f)) { state = OreState.Idle }
                            }
                        }
                        is OreState.Error -> {
                            Banner(s.message, Halo.red, HIcon.WARNING)
                            GhostButton(stringResource(R.string.back), Modifier.fillMaxWidth()) { state = OreState.Idle }
                        }
                    }

                    // ---- la griglia: il SOL di tutti su ogni casella, le tue accese, le scelte cerchiate ----
                    val typed = perSquare.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1e9).toLong() } ?: 0L
                    Grid(v, picked, digging && state == OreState.Idle, if (digging) typed else 0L) { sq -> picked = if (sq in picked) picked - sq else picked + sq }

                    // ---- i numeri ----------------------------------------------------------------
                    GlassCard {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Stat(stringResource(R.string.ore_in_play), Ore.sol(v.inPlay) + " SOL", if (v.inPlay > 0) Halo.mint else Halo.ink, Modifier.weight(1f))
                                Stat(
                                    stringResource(R.string.ore_claimable), Ore.ore(v.claimableOre) + " ORE", if (v.hasClaim) Halo.mint else Halo.ink, Modifier.weight(1f),
                                    sub = Ore.sol(v.claimableSol) + " SOL" + (if (v.needsCheckpoint) " · " + stringResource(R.string.ore_checkpoint_pending) else ""),
                                )
                            }
                            Row(Modifier.fillMaxWidth()) {
                                val m = v.miner
                                Stat(
                                    stringResource(R.string.ore_lifetime), if (m == null) "—" else Ore.ore(m.lifetimeRewardsOre) + " ORE", Halo.ink, Modifier.weight(1f),
                                    sub = if (m == null) null else "+" + Ore.sol(m.lifetimeRewardsSol) + " · −" + Ore.sol(m.lifetimeDeployed) + " SOL",
                                )
                                val r = v.round
                                Stat(
                                    stringResource(R.string.ore_round_pot), if (r == null) "—" else Ore.sol(r.totalDeployed) + " SOL", Halo.ink, Modifier.weight(1f),
                                    sub = r?.let { stringResource(R.string.ore_miners, it.totalMiners.toInt()) },
                                )
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.ore_stake_link), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted)
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * Cinque per cinque. Ogni casella si scalda col SOL che ha sopra, nel colore
 * secondario del tema; le tue sono nel colore primario, quelle che stai
 * scegliendo hanno il bordo acceso. Il numero e' il SOL di tutti.
 */
@Composable
private fun Grid(v: OreMiner.View, picked: Set<Int>, picking: Boolean, perSquare: Long, onPick: (Int) -> Unit) {
    val mine = v.mySquares.toSet()
    val max = (v.round?.deployed?.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for (row in 0 until 5) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (col in 0 until 5) {
                    val s = row * 5 + col
                    val sol = v.round?.deployed?.getOrNull(s) ?: 0L
                    val heat = (sol.toDouble() / max).toFloat().coerceIn(0f, 1f)
                    val isMine = s in mine
                    val isPicked = picking && s in picked
                    val fill = when {
                        isPicked -> Halo.cyan.copy(alpha = 0.32f)
                        isMine -> Halo.mint.copy(alpha = 0.26f)
                        else -> Halo.cyan.copy(alpha = 0.06f + 0.22f * heat)
                    }
                    val edge = when {
                        isPicked -> Halo.cyan
                        isMine -> Halo.mint
                        picking -> Halo.cyan.copy(alpha = 0.35f)
                        else -> Halo.stroke
                    }
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(rs(10))
                            .background(Halo.cardSoft).background(fill)
                            .border(if (isPicked || isMine) 1.5.dp else 1.dp, edge, rs(10))
                            .clickable(enabled = picking) { onPick(s) },
                    ) {
                        Text((s + 1).toString(), fontFamily = Mono, fontSize = 9.sp, color = Halo.muted.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = 4.dp))
                        Text(
                            if (sol > 0) fmtSol(sol, 3) else "·",
                            fontFamily = Mono, fontWeight = if (isMine || isPicked) FontWeight.Bold else FontWeight.Normal, fontSize = 10.5.sp,
                            color = if (isMine || isPicked) Halo.ink else Halo.ink.copy(alpha = 0.6f + 0.4f * heat), style = Tabular,
                            modifier = Modifier.align(Alignment.Center).padding(top = 6.dp),
                        )
                        if (isMine) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).height(6.dp).width(6.dp).clip(rs(3)).background(Halo.mint))
                        // Mentre scegli: la quota dell'ORE che avresti se vincesse questa, con la cifra scritta.
                        if (picking && perSquare > 0) {
                            val share = OreOdds.share(perSquare, sol)
                            Text(
                                String.format(java.util.Locale.ROOT, if (share >= 0.1) "%.0f%%" else "%.1f%%", share * 100),
                                fontFamily = Mono, fontSize = 8.5.sp, color = if (isPicked) Halo.cyan else Halo.muted,
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Un numero con la sua etichetta sopra, come nelle schede del portafoglio. */
@Composable
private fun Stat(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier, sub: String? = null) {
    Column(modifier) {
        Text(label.uppercase(), style = HaloType.label, color = Halo.muted)
        Text(value, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = color, style = Tabular, maxLines = 1)
        if (sub != null) Text(sub, fontFamily = Mono, fontSize = 10.5.sp, color = Halo.muted, style = Tabular, maxLines = 1)
    }
}

/** Il bottone pieno, nei colori del marchio, che sta in una riga accanto a un altro. */
@Composable
private fun Chunky(label: String, icon: HIcon, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(48.dp).clip(rs(16))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Halo.mint, Halo.cyan)))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        HaloIcon(icon, Halo.ground, 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.ground)
    }
}
