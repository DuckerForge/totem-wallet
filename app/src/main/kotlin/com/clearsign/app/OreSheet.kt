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
import com.clearsign.core.OreCrowd
import com.clearsign.core.PastRound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ORE, from the wallet: what you can do on top, the grid under it, the numbers at the bottom, so
 * buttons and receipt show without scrolling. The countdown redraws every second from a slot
 * read once and rereads when the round is over. Claim and Dig go through preview, receipt and
 * print like everything this wallet sends. A round lasts about fifty seconds and the print takes
 * a few: the Deploy is rebuilt on the current round when held, and only if [SIGN_MARGIN_S] seconds remain (22 Sep: signed at round end, the node refuses).
 */
private sealed interface OreState {
    object Idle : OreState
    object Analyzing : OreState
    /** [dig] is how much per square and which squares: at signing the Deploy is rebuilt on the current round. */
    data class Review(val analyzed: ReceiptEngine.Analyzed, val ixs: List<WalletTx.Instruction>, val kind: String, val dig: Pair<Long, Set<Int>>? = null) : OreState
    object Signing : OreState
    data class Done(val signature: String, val what: String, val dig: Boolean) : OreState
    data class Error(val message: String) : OreState
}

/** How much of the round must remain to sign a Deploy: the preview, the finger, the print, the send. */
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
    /** "Wait for the next round", which disappears on its own when the round restarts: not an error, a moment. */
    var waiting by remember { mutableStateOf(false) }
    /**
     * The rolling die: when a new closed round arrives the grid lights square by square and stops
     * on the winner; [reveal] is the lit square, [revealed] the round already shown.
     */
    var reveal by remember { mutableStateOf<Int?>(null) }
    var revealed by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(view?.lastRound?.id) {
        val last = view?.lastRound ?: return@LaunchedEffect
        val win = last.winningSquare ?: return@LaunchedEffect
        // The first read is not an event: shown, no more. From the second round on you see it roll.
        if (revealed < 0) { revealed = last.id; return@LaunchedEffect }
        if (last.id == revealed) return@LaunchedEffect
        revealed = last.id
        var wait = 45L
        for (i in 0 until 30) { reveal = i % Ore.SQUARES; delay(wait); wait += 6 }
        reveal = win
        Haptics.tick(ctx)
        val v = view
        val mine = v?.miner?.takeIf { it.roundId == last.id }?.deployed?.getOrNull(win) ?: 0L
        if (mine > 0L) Haptics.success(ctx)
        delay(2_500)
        reveal = null
    }
    /** The closed rounds from the archive, for the "last rounds" view: where the network digs, and what came out. */
    var past by remember { mutableStateOf<List<PastRound>>(emptyList()) }
    var history by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) { past = withContext(Dispatchers.IO) { runCatching { OreArchive.rounds() }.getOrDefault(emptyList()) } }
    /** What one ORE is worth in SOL, to say whether the stake pays at today's price. Null until known. */
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
    // One second at a time. At round end wait the pause, forty slots, and reread;
    // if the new round has not started, retry every twelve seconds, only with the
    // sheet open in the pause.
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

    /** A node refusal that speaks of the finished round, in words. */
    fun explain(message: String): String =
        if (message.contains("invalid account data", true) || message.contains("InvalidAccountData") || message.contains("invalid seeds", true)) ctx.getString(R.string.ore_round_ended_signing)
        else message

    ModalBottomSheet(
        onDismissRequest = { onDismiss(changed) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null,
    ) {
        // The sheet takes the whole screen as a separate window that eats the system
        // insets: `statusBarsPadding` is zero here. The status bar is measured from
        // resources, which do not lie.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val statusBar = remember(density) {
            with(density) {
                val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
                (if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0).toDp()
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = statusBar).imePadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val v = view
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TokenLogo(Ore.MINT, "ORE", TokenSymbols.image(Ore.MINT), 32.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ore_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.ink)
                    Text(stringResource(R.string.ore_subtitle), style = HaloType.small, color = Halo.muted)
                }
                SmallChip("ore.com", HIcon.EXTERNAL, tint = Halo.cyan) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ore.com"))) }
                }
            }
            // The round: a bar draining with time, in brand colors, the round number
            // beside it. In the pause the bar is empty and amber.
            if (v != null) {
                val left = v.secondsLeft(now)
                val roundS = 200 * Ore.SLOT_MS / 1000.0
                val frac = if (v.board.waiting) 0f else (left / roundS).toFloat().coerceIn(0f, 1f)
                val paused = v.board.waiting || left <= 0.0
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

            // The round just closed: the square that came out, how it paid, whether you were on it.
            v?.lastRound?.let { last -> last.winningSquare?.let { win -> LastRound(v, last, win) } }

            when {
                loading && v == null -> Working(stringResource(R.string.ore_loading))
                v == null -> Banner(stringResource(R.string.ore_unreachable), Halo.amber, HIcon.WARNING)
                else -> {
                    // The end-of-round crowd, the average of the last archived rounds: the share is counted on that.
                    val crowd = if (past.size >= 5) OreCrowd.averageDeployed(past) else null
                    // ---- what you can do, above the grid -----------------------------------
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
                                // The emptiest squares now: the ORE share is my part of the square, and
                                // the cost is the same everywhere.
                                v.round?.let { r ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(stringResource(R.string.ore_best_label), style = HaloType.small, color = Halo.muted)
                                        listOf(1, 3, 5).forEach { k ->
                                            // A square you are already on the program skips: not offered again.
                                            SmallChip(k.toString(), null, tint = Halo.cyan) {
                                                val mine = v.mySquares.toSet()
                                                picked = OreOdds.best(Ore.SQUARES, r.deployed, r.count).filter { it !in mine }.take(k).toSet(); Haptics.tick(ctx)
                                            }
                                        }
                                    }
                                }
                                // The outlook, by the program's rules: how much ORE, how much SOL stays on
                                // the table, and whether it pays at today's price.
                                val r = v.round
                                if (r != null && lamports > 0 && picked.isNotEmpty()) {
                                    // The share is counted on the square as it is at round end, not now: at round
                                    // start it is empty and the network always fills it to the average.
                                    val others = picked.sorted().map { maxOf(r.deployed[it], crowd?.getOrNull(it) ?: 0L) }
                                    val outlook = OreOdds.outlook(lamports, others, r.expectedReward, v.motherlode)
                                    val pct = String.format(java.util.Locale.ROOT, "%.1f", outlook.costFraction * 100)
                                    val px = oreSol
                                    val worth = px?.let { outlook.expectedOre / Ore.ONE_ORE.toDouble() * it }
                                    Text(
                                        if (worth != null) stringResource(R.string.ore_outlook, Ore.ore(outlook.expectedOre), Ore.sol((worth * 1e9).toLong()), Ore.sol(outlook.expectedCost), pct)
                                        else stringResource(R.string.ore_outlook_nopx, Ore.ore(outlook.expectedOre), Ore.sol(outlook.expectedCost), pct),
                                        style = HaloType.small, color = Halo.ink,
                                    )
                                    if (worth != null) {
                                        // What comes back per SOL put in, ORE sold at today's price: an average, never a certainty.
                                        val ratio = (outlook.expectedSolBack + worth * 1e9) / outlook.stake
                                        val good = ratio >= 1.0
                                        Text(
                                            stringResource(if (good) R.string.ore_outlook_good else R.string.ore_outlook_bad, String.format(java.util.Locale.ROOT, "%.2f", ratio), picked.size),
                                            style = HaloType.label, color = if (good) Halo.mint else Halo.amber,
                                        )
                                    }
                                    crowd?.let { c -> Text(stringResource(R.string.ore_outlook_crowd, Ore.sol(c.average().toLong())), style = HaloType.label, color = Halo.muted) }
                                } else {
                                    Text(stringResource(R.string.ore_wager_note), style = HaloType.small, color = Halo.muted)
                                }
                                val open = v.open(now, SIGN_MARGIN_S)
                                if (waiting || !open) Banner(stringResource(R.string.ore_wait_banner), Halo.amber, HIcon.HOURGLASS)
                                // The button says what is missing: the squares, or the round.
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
                            // The same receipt as Send and Swap, not a thinner one of its own.
                            Column { SignReceiptBody(r, null, plain = true, hero = false) }
                            if (r.blocksApproval) {
                                Banner(stringResource(R.string.send_blocked), Halo.red, HIcon.BLOCK)
                                GhostButton(stringResource(R.string.back), Modifier.fillMaxWidth()) { state = OreState.Idle }
                            } else {
                                val stillOpen = s.dig == null || v.open(now, SIGN_MARGIN_S)
                                if (!stillOpen) Banner(stringResource(R.string.ore_round_gone), Halo.amber, HIcon.HOURGLASS)
                                HoldToConfirm(if (s.kind == "ore_dig") stringResource(R.string.ore_hold_dig) else stringResource(R.string.ore_hold_claim), enabled = stillOpen) {
                                    state = OreState.Signing
                                    scope.launch {
                                        // The Deploy is rebuilt on the current round: between preview and finger a
                                        // whole round can pass. Same squares, same SOL.
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
                            // The receipt stays here after signing, and is in Receipts too.
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

                    // ---- the grid: everybody's SOL on each square, yours lit, the picked ones circled ----
                    val typed = perSquare.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1e9).toLong() } ?: 0L
                    // Now, or the average of the last rounds: the same grid, another snapshot.
                    // The two views and the dot legend on one line.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (past.size >= 5) {
                            SmallChip(stringResource(R.string.ore_view_now), null, tint = if (!history) Halo.mint else Halo.muted) { history = false }
                            SmallChip(stringResource(R.string.ore_view_history, past.size), null, tint = if (history) Halo.mint else Halo.muted) { history = true }
                        }
                        Spacer(Modifier.weight(1f))
                        if (v.round != null) {
                            Box(Modifier.height(6.dp).width(6.dp).clip(rs(3)).background(Halo.amber))
                            Text(stringResource(R.string.ore_solo_legend), style = HaloType.label, color = Halo.muted, maxLines = 1)
                        }
                    }
                    val heat = if (history) crowd else null
                    Grid(v, picked, digging && state == OreState.Idle, if (digging) typed else 0L, reveal, v.lastRound?.takeIf { reveal == null }?.winningSquare, heat, crowd) { sq -> picked = if (sq in picked) picked - sq else picked + sq }
                    if (history && past.size >= 5) {
                        val wins = OreCrowd.winners(past).take(12).joinToString(" · ") { (it + 1).toString() }
                        Text(stringResource(R.string.ore_history_wins, wins), style = HaloType.label, color = Halo.muted)
                        OreCrowd.hottest(past)?.let { (sq, n) -> if (n > 1) Text(stringResource(R.string.ore_history_hot, sq + 1, n), style = HaloType.label, color = Halo.muted) }
                        Text(stringResource(R.string.ore_history_best, OreCrowd.best(3, past).joinToString(", ") { (it + 1).toString() }), style = HaloType.label, color = Halo.cyan)
                    }

                    // ---- i numeri ----------------------------------------------------------------
                    GlassCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        }
    }
}

/**
 * Five by five. Each square warms with the SOL on it, in the theme's second color; yours are
 * in the first, the ones being picked have a lit edge. The number is everybody's SOL.
 */
@Composable
private fun Grid(v: OreMiner.View, picked: Set<Int>, picking: Boolean, perSquare: Long, reveal: Int?, lastWin: Int?, heat: LongArray?, crowd: LongArray?, onPick: (Int) -> Unit) {
    val mine = v.mySquares.toSet()
    val source = heat ?: v.round?.deployed
    val max = (source?.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val solo = v.round?.soloMask ?: 0
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in 0 until 5) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0 until 5) {
                    val s = row * 5 + col
                    val sol = source?.getOrNull(s) ?: 0L
                    val heat = (sol.toDouble() / max).toFloat().coerceIn(0f, 1f)
                    val isMine = s in mine
                    val isPicked = picking && s in picked
                    val lit = reveal == s
                    val won = lastWin == s
                    val fill = when {
                        lit -> Halo.amber.copy(alpha = 0.55f)
                        isPicked -> Halo.cyan.copy(alpha = 0.32f)
                        isMine -> Halo.mint.copy(alpha = 0.26f)
                        else -> Halo.cyan.copy(alpha = 0.06f + 0.22f * heat)
                    }
                    val edge = when {
                        lit -> Halo.amber
                        won -> Halo.amber.copy(alpha = 0.7f)
                        isPicked -> Halo.cyan
                        isMine -> Halo.mint
                        picking -> Halo.cyan.copy(alpha = 0.35f)
                        else -> Halo.stroke
                    }
                    Box(
                        Modifier.weight(1f).aspectRatio(1.3f).clip(rs(10))
                            .background(Halo.cardSoft).background(fill)
                            .border(if (isPicked || isMine || lit || won) 1.5.dp else 1.dp, edge, rs(10))
                            // Where you already are is not re-placed: the program would skip the square.
                            .clickable(enabled = picking && !isMine) { onPick(s) },
                    ) {
                        Text((s + 1).toString(), fontFamily = Mono, fontSize = 9.sp, color = Halo.muted.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = 4.dp))
                        Text(
                            if (sol > 0) fmtSol(sol, 3) else "·",
                            fontFamily = Mono, fontWeight = if (isMine || isPicked) FontWeight.Bold else FontWeight.Normal, fontSize = 10.5.sp,
                            color = if (isMine || isPicked) Halo.ink else Halo.ink.copy(alpha = 0.6f + 0.4f * heat), style = Tabular,
                            modifier = Modifier.align(Alignment.Center).padding(top = 2.dp),
                        )
                        if (isMine) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).height(6.dp).width(6.dp).clip(rs(3)).background(Halo.mint))
                        // The squares that pay one miner only, known before the round.
                        if (solo and (1 shl s) != 0) Box(Modifier.align(Alignment.BottomEnd).padding(5.dp).height(5.dp).width(5.dp).clip(rs(3)).background(Halo.amber))
                        // While picking: the ORE share you would have if this one won, with the typed
                        // amount, counted on the full square as at round end.
                        if (picking && perSquare > 0) {
                            val share = OreOdds.share(perSquare, maxOf(sol, crowd?.getOrNull(s) ?: 0L))
                            Text(
                                String.format(java.util.Locale.ROOT, if (share >= 0.1) "%.0f%%" else "%.1f%%", share * 100),
                                fontFamily = Mono, fontSize = 8.5.sp, color = if (isPicked) Halo.cyan else Halo.muted,
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The round just closed, in one line: which square came out, whether the prize was split or
 * taken by one, what it means for you. Your Miner account still carries that round's squares
 * until you checkpoint, and from there we know whether you were on it and what you get.
 */
@Composable
private fun LastRound(v: OreMiner.View, last: Ore.Round, win: Int) {
    val onIt = v.miner?.takeIf { it.roundId == last.id }?.deployed?.getOrNull(win) ?: 0L
    val total = last.deployed.getOrNull(win) ?: 0L
    val meTop = v.miner != null && last.topMiner.contentEquals(v.miner.authority)
    val how = if (last.isSplit) stringResource(R.string.ore_last_split, last.count.getOrNull(win)?.toInt() ?: 0) else stringResource(R.string.ore_last_solo)
    val outcome = when {
        // Pro rata on the round's ORE plus the pot if it came out: as in `checkpoint.rs`.
        onIt > 0L && last.isSplit && total > 0 -> stringResource(R.string.ore_last_you_won, Ore.ore(java.math.BigInteger.valueOf(last.rewardOre + last.motherlode).multiply(java.math.BigInteger.valueOf(onIt)).divide(java.math.BigInteger.valueOf(total)).toLong()))
        onIt > 0L && meTop -> stringResource(R.string.ore_last_you_won, Ore.ore(last.rewardOre + java.math.BigInteger.valueOf(last.motherlode).multiply(java.math.BigInteger.valueOf(onIt)).divide(java.math.BigInteger.valueOf(total.coerceAtLeast(1))).toLong()))
        onIt > 0L -> stringResource(R.string.ore_last_you_lost_draw)
        v.miner?.roundId == last.id -> stringResource(R.string.ore_last_you_missed)
        else -> ""
    }
    val won = onIt > 0L && (last.isSplit || meTop)
    val ctx = LocalContext.current
    val title = stringResource(R.string.ore_last_title, last.id, win + 1)
    SoftPanel(padding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.height(28.dp).width(28.dp).clip(rs(8)).background(Halo.amber.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Text((win + 1).toString(), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Halo.amber)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = HaloType.small.copy(fontWeight = FontWeight.SemiBold), color = Halo.ink)
                Text(how + (if (outcome.isNotEmpty()) " · $outcome" else ""), style = HaloType.label, color = if (won) Halo.mint else Halo.muted)
            }
            // A won round is a card to show, like the closed budget.
            if (won) {
                Spacer(Modifier.width(8.dp))
                RoundIconButton(HIcon.SHARE, tint = Halo.mint, description = stringResource(R.string.share)) {
                    PnlCard.share(ctx, PnlCard.Face("ORE · " + title, how, null, outcome, ctx.getString(R.string.pnl_card_foot)), "ore-" + last.id + ".png")
                }
            }
        }
    }
}

/** A number with its label above, as in the wallet cards. */
@Composable
private fun Stat(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier, sub: String? = null) {
    Column(modifier) {
        Text(label.uppercase(), style = HaloType.label, color = Halo.muted)
        Text(value, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = color, style = Tabular, maxLines = 1)
        if (sub != null) Text(sub, fontFamily = Mono, fontSize = 10.5.sp, color = Halo.muted, style = Tabular, maxLines = 1)
    }
}

/** The filled button, in brand colors, that sits in a row next to another. */
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
