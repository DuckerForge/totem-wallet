@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clearsign.core.AgentMode
import com.clearsign.core.AgentPolicy
import com.clearsign.core.NATIVE_SOL_MINT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The agent, on one card: which pocket it holds, what it may do on its own,
 * whether it is linked, and the two switches that matter — pause and take it
 * all back. Everything the collar decides shows up here and in the ledger.
 */
@Composable
internal fun ModeChip(label: String, on: Boolean, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(rs(12)).background(if (on) tint.copy(alpha = 0.16f) else Halo.cardSoft).border(1.dp, if (on) tint else Halo.stroke, rs(12))
            .clickable { onClick() }.padding(vertical = 9.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Centred and told not to wrap. Left to itself a label wider than its
        // chip is clipped on the right, and a word missing its last character
        // does not read as clipped: it reads as crooked.
        Text(
            label, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp,
            color = if (on) tint else Halo.muted, maxLines = 1, softWrap = false,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private fun symbolOf(mint: String): String = when (mint) {
    NATIVE_SOL_MINT, AgentPolicy.WSOL -> "SOL"
    AgentPolicy.USDC -> "USDC"
    else -> shorten(mint, 4)
}

/** Set the cap, the days and the preset, then fund it with one Seed Vault approval. */
@Composable
internal fun NewEnvelopeSheet(owner: String, signer: SeedVaultSigner, onDone: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Never offer a cap the account cannot pay: the slider is bounded by the real
    // balance, minus a reserve so there is always something left for fees.
    var have by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(owner) {
        have = withContext(Dispatchers.IO) { runCatching { SolanaRpc.getBalance(SolanaRpc.urlFor(null), owner) }.getOrNull() }
    }
    val reserve = 10_000_000L                       // 0.01 SOL kept back for fees and rent
    val floor = 0.005f
    val ceiling = (((have ?: 0L) - reserve) / 1e9f).coerceAtLeast(0f)
    val enough = have != null && ceiling >= floor
    var cap by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(ceiling) {
        if (cap <= 0f && ceiling > 0f) cap = (ceiling / 3f).coerceIn(floor, ceiling)
    }
    var days by remember { mutableFloatStateOf(7f) }
    var slots by remember { mutableFloatStateOf(TraderLoop.config(ctx).maxPositions.toFloat()) }
    var harvest by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(ceiling) { if (harvest <= 0f && ceiling > 0f) harvest = (ceiling / 5f).coerceIn(0f, ceiling) }
    var state by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // What you are about to sign, read off the real bytes, before you sign it.
    var review by remember { mutableStateOf<ReceiptEngine.Analyzed?>(null) }
    var prepared by remember { mutableStateOf<Pair<ByteArray, String>?>(null) }
    val scope = rememberCoroutineScope()

    val scroll = rememberScrollState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.env_new_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = Halo.ink)
            Text(stringResource(R.string.env_new_body), fontFamily = Inter, fontSize = 12.5.sp, color = Halo.muted, lineHeight = 18.sp)

            when {
                have == null -> Working(stringResource(R.string.w_analyzing))
                !enough -> Banner(stringResource(R.string.env_too_little, fmtSol((reserve + (floor * 1e9).toLong()), 3), fmtSol(have ?: 0L, 4)), Halo.amber, HIcon.WARNING)
                else -> Text(stringResource(R.string.env_balance, fmtSol(have ?: 0L, 4)), fontFamily = Mono, fontSize = 12.sp, color = Halo.muted, style = Tabular)
            }

            if (enough) {
            SliderRow(stringResource(R.string.env_cap), "%.3f SOL".format(cap), cap, floor..ceiling, Halo.mint) { cap = it }
            // How many coins at once. One is a fine answer: all the money on one
            // idea at a time, and the loop looks for the next only after it sells.
            SliderRow(stringResource(R.string.trader_slots), slots.toInt().toString(), slots, 1f..5f, Halo.cyan, steps = 3) { slots = it }
            Text(stringResource(R.string.env_slots_note), style = HaloType.small, color = Halo.muted)

            // The two ceilings of the collar follow from these two numbers, and
            // are said, not asked: one buy is the budget split by the coins,
            // and a day can spend the whole budget. Four sliders in real SOL
            // were honest and nobody could read them. The ceilings stay in the
            // rules, for the person who wants to move them.
            val perTxV = (cap / slots.toInt().coerceIn(1, 5)).coerceIn(cap / 50f, cap)
            val dailyV = cap
            SizingNote((cap * 1e9).toLong(), (perTxV * 1e9).toLong(), (perTxV * 1e9).toLong(), TraderLoop.config(ctx).slicePercent, slots.toInt())

            SliderRow(stringResource(R.string.env_duration), stringResource(R.string.env_days, days.toInt()), days, 1f..30f, Halo.cyan, steps = 28) { days = it }
            Text(stringResource(R.string.env_duration_note), style = HaloType.small, color = Halo.muted)

            Text(stringResource(R.string.env_harvest_title), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
            Text(stringResource(R.string.env_harvest_body), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted, lineHeight = 17.sp)
            SliderRow(
                stringResource(R.string.env_harvest),
                if (harvest < 0.0005f) stringResource(R.string.env_harvest_off) else stringResource(R.string.env_harvest_at, "%.3f".format(harvest)),
                harvest, 0f..ceiling.coerceAtLeast(floor), Halo.mint,
            ) { harvest = it }

            Banner(stringResource(R.string.env_warn), Halo.amber, HIcon.WARNING)

            state?.let { Working(it) }
            error?.let { Banner(it, Halo.red, HIcon.WARNING) }

            if (state == null && review == null) {
                PrimaryButton(stringResource(R.string.env_review), danger = false, icon = HIcon.RECEIPT) {
                    state = ctx.getString(R.string.w_analyzing)
                    scope.launch {
                        val lamports = (cap * 1e9).toLong()
                        val prep = SessionWallet.prepare()
                        prepared = prep
                        val ownerKey = Base58.decodePubkey(owner)
                        val sessionKey = Base58.decodePubkey(prep.second)
                        val ix = if (ownerKey != null && sessionKey != null) listOf(WalletTx.systemTransfer(ownerKey, sessionKey, lamports)) else emptyList()
                        val a = if (ix.isEmpty()) null else WalletActions.preview(ctx, owner, ix)
                        state = null
                        if (a == null) { error = ctx.getString(R.string.wa_sim_generic); prepared = null } else review = a
                    }
                }
            }

            }
            GhostButton(stringResource(R.string.cancel)) { onDismiss() }
        }
    }

    // The receipt, in a window of its own over the sheet: what you are about to
    // sign, the hold, then the print. A receipt under the sliders was a receipt
    // people scrolled past, or never found. This was the first place that got it
    // right; PayOverlay is that same window, lifted out for everywhere else.
    review?.let { r ->
        PayOverlay(
            title = stringResource(R.string.env_pay_title, "%.3f".format(cap)),
            hint = stringResource(R.string.env_review_hint),
            onBack = { if (state == null) { review = null; prepared = null } },
        ) {
            run {
                Column { SignReceiptBody(r.receipt, null, hero = false) }
                state?.let { Working(it) }
                error?.let { Banner(it, Halo.red, HIcon.WARNING) }
                if (state == null) {
                HoldToConfirm(stringResource(R.string.env_hold, "%.3f".format(cap))) {
                    state = ctx.getString(R.string.env_funding)
                    scope.launch {
                        val lamports = (cap * 1e9).toLong()
                        val s = SessionWallet.create(ctx, lamports, days.toInt(), "", prepared)
                        TraderLoop.setConfig(ctx, TraderLoop.config(ctx).copy(maxPositions = slots.toInt().coerceIn(1, 5)))
                        if (harvest >= 0.0005f) SessionWallet.setHarvest(ctx, (harvest * 1e9).toLong())
                        val contacts = Contacts.allowlist(ctx).keys
                        // The two sliders are the policy now; `prudent` is only the
                        // starting shape (programs, assets, destinations, rate).
                        SessionWallet.setPolicy(
                            ctx,
                            AgentPolicy.prudent(lamports, owner, s.pubkey, contacts, s.expiresAt).copy(
                                perTxLamports = ((cap / slots.toInt().coerceIn(1, 5)).coerceIn(cap / 50f, cap) * 1e9).toLong(),
                                dailyLamports = (cap * 1e9).toLong(),
                                askAboveLamports = ((cap / slots.toInt().coerceIn(1, 5)).coerceIn(cap / 50f, cap) * 1e9).toLong(),
                            ),
                        )
                        val out = SessionActions.fund(ctx, signer, owner, s.pubkey, lamports)
                        if (out == null) { SessionWallet.addFunded(ctx, lamports); onDone() } else { error = out; state = null; SessionWallet.forget(ctx) }
                    }
                }
                }
            }
        }
    }
}

/**
 * Is it working, and is anything wrong. In one line, without tapping anything.
 *
 * Four states and they are mutually exclusive, so the colour alone carries it:
 * stopped, paused by you, working, or working and stuck on something. The last
 * one is the reason this exists: a loop that turned itself off at three in the
 * morning used to look exactly like a loop that was running fine.
 */
@Composable
internal fun AgentPulse(refresh: Int) {
    val ctx = LocalContext.current
    // The loop stops itself, in a service, while this card is on screen. Without
    // a heartbeat the card kept saying "working, looking for a coin" in green for
    // as long as you left the tab open, with the sentence explaining why it had
    // stopped printed directly underneath. Three seconds of reading preferences
    // costs nothing; the balance and the quotes stay on [refresh], which is the
    // expensive half.
    var beat by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(3_000); beat++ } }

    val cfg = remember(refresh, beat) { TraderLoop.config(ctx) }
    val mode = remember(refresh, beat) { SessionWallet.policy(ctx)?.mode }
    val note = remember(refresh, beat) { TraderLoop.lastNote(ctx) }
    val at = remember(refresh, beat) { TraderLoop.lastTickAt(ctx) }
    val open = remember(refresh, beat) { Positions.open(ctx).size }

    val paused = mode == AgentMode.OFF || mode == AgentMode.READ_ONLY
    // A note written by the loop as it stopped is the one thing worth shouting.
    val stuck = !cfg.on && note != null && at > 0

    val (tint, label) = when {
        paused -> Halo.amber to stringResource(R.string.pulse_paused)
        cfg.on && open > 0 -> Halo.mint to stringResource(R.string.pulse_holding, open)
        cfg.on -> Halo.mint to stringResource(R.string.pulse_looking)
        stuck -> Halo.red to stringResource(R.string.pulse_stopped)
        else -> Halo.muted to stringResource(R.string.pulse_idle)
    }

    // The dot breathes only while it is actually working, so motion means work.
    val alpha = if (cfg.on && !paused) {
        val p = rememberInfiniteTransition(label = "pulse")
        p.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse), label = "a").value
    } else 1f

    Column(
        Modifier.fillMaxWidth().clip(rs(Radius.row)).background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.30f), rs(Radius.row)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(9.dp)) { drawCircle(tint.copy(alpha = alpha)) }
            Spacer(Modifier.width(9.dp))
            Text(label, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = tint)
        }
        // Why it stopped, or the last thing it did. Never both, never neither.
        (if (stuck) note else note.takeIf { cfg.on })?.let {
            Text(it, style = HaloType.small, color = Halo.muted, lineHeight = 16.sp)
        }
    }
}

/**
 * One holding, and the two things a person may want to do with it.
 *
 * It used to be a dead line: a symbol and what it cost. That was fine while the
 * loop was the only thing that could ever sell, and it was exactly wrong the
 * night the loop could not. A position nobody can act on is a position you watch
 * fail, so the reason for the last failure is printed here and the sale is one
 * tap away. The tap builds its own quote at the moment it is pressed, and it
 * goes through the same collar the loop goes through, with you in front of the
 * phone to answer anything it asks.
 */
@Composable
internal fun PositionRow(pos: Positions.Position, refresh: Int = 0, onChange: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var value by remember(pos.mint) { mutableStateOf<Long?>(null) }
    var busy by remember(pos.mint) { mutableStateOf(false) }
    var said by remember(pos.mint) { mutableStateOf<String?>(null) }
    var worse by remember(pos.mint) { mutableStateOf<SessionActions.Sale.Worse?>(null) }

    LaunchedEffect(pos.mint, pos.units) {
        value = runCatching { SessionActions.quoteValue(ctx, pos) }.getOrNull()
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(pos.symbol, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Halo.ink, modifier = Modifier.weight(1f))
            // The on-chain exit is the one that survives the app dying, so it is
            // worth a mark of its own rather than a footnote. Parked means the
            // coins are inside that order and not in the wallet.
            // Who is watching this, said on the row. Three answers and no fourth,
            // and the third one is the one that matters: an on-chain order fires
            // with the app dead, the loop fires only while it runs, and when
            // neither is there **nobody is watching**, in either direction. The
            // row used to show the first two and stay silent about the third, so
            // a position left unguarded by a loop that stopped itself looked
            // exactly like a healthy one.
            val watching = remember(refresh) { TraderLoop.config(ctx).on }
            if (pos.parked) {
                Text(stringResource(R.string.trader_parked_tag), fontFamily = Mono, fontSize = 10.sp, color = Halo.amber)
                Spacer(Modifier.width(8.dp))
            } else if (pos.triggerOrder != null) {
                Text(stringResource(R.string.trader_onchain), fontFamily = Mono, fontSize = 10.sp, color = Halo.mint)
                Spacer(Modifier.width(8.dp))
            } else if (!watching) {
                Text(stringResource(R.string.trader_unwatched_tag), fontFamily = Mono, fontSize = 10.sp, color = Halo.red)
                Spacer(Modifier.width(8.dp))
            }
            value?.let { v ->
                val up = v >= pos.costLamports
                Text(
                    stringResource(R.string.trader_value_now, fmtSol(v, 4) + " SOL"),
                    fontFamily = Mono, fontSize = 11.sp, color = if (up) Halo.mint else Halo.amber,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(fmtSol(pos.costLamports, 4) + " SOL", fontFamily = Mono, fontSize = 11.5.sp, color = Halo.muted)
        }
        (said ?: pos.lastError)?.let { Text(it, style = HaloType.small, color = Halo.amber, lineHeight = 15.sp) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            GhostButton(
                if (busy) stringResource(R.string.trader_selling)
                else worse?.let { stringResource(R.string.trader_sell_anyway, fmtSol(it.realLamports, 4)) } ?: stringResource(R.string.trader_sell_now),
                Modifier.weight(1f), HIcon.SWAP, tint = if (worse != null) Halo.red else Halo.mint,
            ) {
                if (!busy) {
                    busy = true
                    scope.launch {
                        val r = SessionActions.sellSaid(ctx, pos, AgentBroker.Job.Source.IN_APP, acceptReal = worse != null)
                        busy = false
                        said = r.text
                        worse = r.worse
                        onChange()
                    }
                }
            }
            GhostButton(stringResource(R.string.trader_forget), Modifier.weight(1f), HIcon.CLOSE, tint = Halo.muted) {
                Positions.remove(ctx, pos.mint)
                onChange()
            }
        }
    }
}

/**
 * What to do with the coins before the key disappears.
 *
 * Closing a budget erases the only copy of its key. Any coin still inside is
 * then unreachable for good, and "close it and take everything back" had been
 * taking back only the SOL. So the coins are named, and there are two ways out
 * and no third: sell them for SOL, or move them to the account you keep.
 *
 * Both are offered rather than one chosen, because they are not the same
 * decision. Selling is faster and gives you one number back; moving keeps the
 * coin, which matters when the reason you are closing is that you do not trust
 * this budget rather than that you are done with the coin.
 */
@Composable
internal fun ClosingCoinsSheet(
    coins: List<SolanaRpc.TokenAccountInfo>,
    onSell: () -> Unit,
    onMove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.env_coins_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = Halo.ink)
            Text(stringResource(R.string.env_coins_body), style = HaloType.small, color = Halo.muted, lineHeight = 17.sp)
            coins.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().clip(rs(Radius.row)).background(Halo.cardSoft).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(TokenSymbols.symbol(c.mint), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.ink, modifier = Modifier.weight(1f))
                    Text(
                        "%.4f".format(c.amount / Math.pow(10.0, c.decimals.toDouble())),
                        fontFamily = Mono, fontSize = 12.sp, color = Halo.muted,
                    )
                }
            }
            PrimaryButton(stringResource(R.string.env_coins_sell), danger = false, icon = HIcon.DOWNLOAD) { onSell() }
            GhostButton(stringResource(R.string.env_coins_move), Modifier.fillMaxWidth(), HIcon.SEND, tint = Halo.cyan) { onMove() }
            GhostButton(stringResource(R.string.cancel)) { onDismiss() }
        }
    }
}

/**
 * Putting more money into a budget that already exists.
 *
 * Until now the only way to add to one was to close it and open another, which
 * meant the single button under a running budget was the one that ends it. This
 * is the same signed transfer the creation sheet makes, so it goes through the
 * Seed Vault and your fingerprint like any other payment out of the vault.
 */
@Composable
internal fun TopUpSheet(owner: String, signer: SeedVaultSigner, session: SessionWallet.Session, onDone: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var ceiling by remember { mutableFloatStateOf(0f) }
    var amount by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // The receipt comes before the signature here too. Every other place money
    // leaves the vault shows one first, and a top-up is money leaving the vault.
    var review by remember { mutableStateOf<ReceiptEngine.Analyzed?>(null) }

    // What the main account can actually spare, minus enough to still pay fees.
    LaunchedEffect(owner) {
        val lam = withContext(Dispatchers.IO) { runCatching { SolanaRpc.getBalance(SolanaRpc.urlFor(null), owner) }.getOrNull() } ?: 0L
        ceiling = ((lam - 10_000_000L).coerceAtLeast(0L) / 1e9).toFloat()
        if (amount <= 0f && ceiling > 0f) amount = (ceiling / 4f).coerceIn(0f, ceiling)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        // The title sits outside the scrolling part. Padding alone only fixed
        // where it starts: once the receipt made the content scrollable, the
        // title slid up underneath the clock and the status icons.
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.env_add), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = Halo.ink)
                Text(stringResource(R.string.env_add_body), style = HaloType.small, color = Halo.muted, lineHeight = 17.sp)
            }
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(bottom = 18.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (ceiling <= 0f) {
                    Text(stringResource(R.string.env_add_empty), style = HaloType.small, color = Halo.amber, lineHeight = 17.sp)
                } else {
                    // Moving the slider invalidates the receipt: it described a
                    // different amount.
                    SliderRow(stringResource(R.string.env_add_amount), "%.3f SOL".format(amount), amount, 0.001f..ceiling, Halo.mint) { amount = it; review = null }

                    if (busy == null && review == null) {
                        PrimaryButton(stringResource(R.string.env_review), danger = false, icon = HIcon.RECEIPT) {
                            busy = ctx.getString(R.string.w_analyzing)
                            scope.launch {
                                val lamports = (amount * 1e9).toLong()
                                val from = Base58.decodePubkey(owner)
                                val to = Base58.decodePubkey(session.pubkey)
                                val ix = if (from != null && to != null) listOf(WalletTx.systemTransfer(from, to, lamports)) else emptyList()
                                val a = if (ix.isEmpty()) null else WalletActions.preview(ctx, owner, ix)
                                busy = null
                                if (a == null) error = ctx.getString(R.string.wa_sim_generic) else review = a
                            }
                        }
                    }
                }
                busy?.let { Working(it) }
                error?.let { Banner(it, Halo.red, HIcon.WARNING) }
                GhostButton(stringResource(R.string.cancel)) { onDismiss() }
            }
        }
    }

    // The receipt over the slider, not under it: see PayOverlay.
    review?.let { r ->
        PayOverlay(
            title = stringResource(R.string.env_add),
            hint = stringResource(R.string.env_review_hint),
            onBack = { if (busy == null) review = null },
        ) {
            Column { SignReceiptBody(r.receipt, null, hero = false) }
            error?.let { Banner(it, Halo.red, HIcon.WARNING) }
            if (busy != null) {
                Working(busy!!)
            } else {
                HoldToConfirm(stringResource(R.string.env_hold, "%.3f".format(amount))) {
                    busy = ctx.getString(R.string.env_funding)
                    scope.launch {
                        val lamports = (amount * 1e9).toLong()
                        val out = SessionActions.fund(ctx, signer, owner, session.pubkey, lamports)
                        busy = null
                        if (out == null) { SessionWallet.addFunded(ctx, lamports); onDone() } else error = out
                    }
                }
            }
        }
    }
}


/** The collar, adjustable: caps as a share of the pocket, the silent threshold, the pace, who may be paid. */
@Composable
internal fun RulesSheet(policy: AgentPolicy, session: SessionWallet.Session, owner: String, onTopUp: () -> Unit, onDone: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cap = session.capLamports.coerceAtLeast(1L).toFloat()
    var perTx by remember { mutableFloatStateOf((policy.perTxLamports / cap).coerceIn(0.01f, 1f)) }
    var daily by remember { mutableFloatStateOf((policy.dailyLamports / cap).coerceIn(0.01f, 1f)) }
    var askAbove by remember { mutableFloatStateOf((policy.askAboveLamports / cap).coerceIn(0f, 1f)) }
    var perHour by remember { mutableFloatStateOf(policy.maxTxPerHour.toFloat()) }
    var usdc by remember { mutableStateOf(AgentPolicy.USDC in policy.allowedMints) }
    var anyMint by remember { mutableStateOf(policy.allowAnyMint) }
    // Starting from here as well as from the chat: the chat needs a model key,
    // and a budget should not become unusable because a free tier ran out.
    var trade by remember { mutableStateOf(TraderLoop.config(ctx)) }
    // Until now this could only be set when the budget was created, which meant
    // the one number that decides when profit leaves the agent's reach was the
    // one number you could not change afterwards.
    var payout by remember { mutableFloatStateOf((session.harvestLamports / cap).coerceIn(0f, 1f)) }
    val contacts = remember { Contacts.allowlist(ctx) }
    var whom by remember { mutableStateOf(policy.allowedDestinations.filter { it in contacts.keys }.toSet()) }
    var rules by remember { mutableStateOf(UserRules.get(ctx) ?: "") }
    var rulesName by remember { mutableStateOf(UserRules.name(ctx)) }

    fun sol(f: Float) = fmtSol((f * cap).toLong(), 4) + " SOL"

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.agent_rules_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = Halo.ink)

            // First thing on the screen, because it is the first question anybody
            // has here: is this thing on. The state used to be four chips at the
            // bottom, under four sliders about caps, and you had to work it out.
            val saved = remember { TraderLoop.config(ctx).on }
            val on = trade.on
            Column(
                Modifier.fillMaxWidth().clip(rs(14))
                    .background((if (on) Halo.mint else Halo.muted).copy(alpha = 0.10f))
                    .border(1.dp, (if (on) Halo.mint else Halo.muted).copy(alpha = 0.45f), rs(14))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(rs(999)).background(if (on) Halo.mint else Halo.muted))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(if (on) R.string.rules_state_on else R.string.rules_state_off),
                            fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                            color = if (on) Halo.mint else Halo.ink,
                        )
                        Text(
                            if (on) stringResource(R.string.rules_state_on_sub, sol(perTx), sol(daily))
                            else stringResource(R.string.rules_state_off_sub),
                            style = HaloType.small, color = Halo.muted, lineHeight = 16.sp,
                        )
                    }
                    Text(
                        stringResource(if (on) R.string.watch_disable else R.string.watch_enable),
                        fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp,
                        color = if (on) Halo.muted else Halo.mint,
                        modifier = Modifier.clip(rs(10)).background((if (on) Halo.muted else Halo.mint).copy(alpha = 0.14f))
                            .clickable { trade = trade.copy(on = !on); Haptics.tick(ctx) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
                // The one thing a settings screen must never do is lie about
                // whether what you see is what is running.
                if (on != saved) {
                    Text(stringResource(R.string.rules_unsaved), style = HaloType.small, color = Halo.amber)
                }
            }

            Text(stringResource(R.string.agent_rules_body), fontFamily = Inter, fontSize = 12.5.sp, color = Halo.muted, lineHeight = 18.sp)

            // Ogni cursore dice anche che fetta della paghetta e', e dove finisce.
            //
            // Erano quattro numeri in SOL su una paghetta da diciassette dollari:
            // senza un riferimento non si capisce ne' quanto sia tanto, ne'
            // perche' il cursore si fermi. Si ferma all'intero della paghetta, e
            // adesso e' scritto: piu' di quello che hai non puo' uscire.
            SliderRow(
                stringResource(R.string.agent_per_tx),
                sol(perTx) + "  ·  " + (perTx * 100).toInt() + "%",
                perTx, 0.01f..1f, Halo.mint,
            ) { perTx = it }
            SliderRow(
                stringResource(R.string.agent_daily),
                sol(daily) + "  ·  " + (daily * 100).toInt() + "%",
                daily, 0.01f..1f, Halo.mint,
            ) { daily = it }
            // Quanto della giornata e' gia' andato, e la regola che spiega perche'
            // spesso e' meno di quanto sembra.
            run {
                val spent = remember { runCatching { SessionWallet.history(ctx).spentLast24hLamports }.getOrDefault(0L) }
                Text(
                    stringResource(R.string.rules_daily_used, fmtSol(spent, 4), fmtSol((daily * cap).toLong(), 4)),
                    style = HaloType.small, color = if (spent >= (daily * cap).toLong()) Halo.amber else Halo.muted, lineHeight = 16.sp,
                )
            }
            // Un cursore che non va piu' a destra e' un vicolo cieco finche' non
            // dice dove finisce la strada e come si va oltre.
            //
            // Il tetto e' la paghetta, e ogni cursore qui e' una fetta di quella:
            // arrivato al cento per cento non c'e' niente a destra da prendere.
            // Il modo di alzarlo non sta in questo foglio, sta nel mettere altri
            // soldi dentro la paghetta, che e' un'altra pagina e un'altra firma.
            // Scritto in piccolo sotto i cursori non bastava: era una frase fra
            // le altre, e chi guarda un cursore fermo cerca il cursore, non il
            // paragrafo. Adesso compare solo quando serve, dice il numero, e
            // porta dove si fa.
            if (perTx >= 0.995f || daily >= 0.995f) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.rules_cap_is_budget, fmtSol(cap.toLong(), 4)),
                        style = HaloType.small, color = Halo.amber, lineHeight = 16.sp, modifier = Modifier.weight(1f),
                    )
                    SmallChip(stringResource(R.string.env_add), HIcon.DOWNLOAD, tint = Halo.cyan) { onTopUp() }
                }
                Text(stringResource(R.string.rules_daily_roundtrip), style = HaloType.small, color = Halo.mint, lineHeight = 16.sp)
            }
            // The arithmetic nobody does in their head, said out loud before it
            // bites. A daily cap of 0.047 with a slice of 0.031 is one move a day
            // and a half; the second one asks for a signature the agent cannot
            // get on its own, and the loop stops. That happened today.
            run {
                val slicePart = minOf(perTx, askAbove.takeIf { it > 0f } ?: perTx) * (trade.slicePercent / 100f)
                val moves = if (slicePart > 0f) (daily / slicePart).toInt() else 0
                if (trade.on) {
                    Text(
                        if (moves < 2) stringResource(R.string.rules_moves_few, sol(slicePart), moves)
                        else stringResource(R.string.rules_moves, moves, sol(slicePart)),
                        style = HaloType.small,
                        color = if (moves < 2) Halo.amber else Halo.muted,
                        lineHeight = 16.sp,
                    )
                }
            }

            // Quanto ne mette in una mossa, che non e' il tetto.
            //
            // Il tetto per operazione dice quanto **puo'** mettere; questo dice
            // quanto **ne mette**. Erano la stessa cosa sullo schermo perche'
            // questa manopola esisteva solo nel motore, ferma all'ottanta per
            // cento, e con un posto solo l'ottanta per cento di tutta la paghetta
            // sembra tutta la paghetta. Chi vuole rischiare meta' di quello che
            // gli e' concesso adesso puo' dirlo senza stringere il collare.
            SliderRow(
                stringResource(R.string.agent_slice), trade.slicePercent.toString() + "%",
                trade.slicePercent / 100f, 0.1f..1f, Halo.mint, steps = 17,
            ) { trade = trade.copy(slicePercent = (it * 100).toInt().coerceIn(10, 100)) }
            SliderRow(stringResource(R.string.agent_ask_above), sol(askAbove), askAbove, 0f..1f, Halo.cyan) { askAbove = it }
            SliderRow(stringResource(R.string.agent_per_hour), perHour.toInt().toString(), perHour, 1f..120f, Halo.amber, steps = 118) { perHour = it }

            Text(stringResource(R.string.agent_mints), style = HaloType.label, color = Halo.muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // SOL is not optional: the budget is denominated in it and the fees
                // are paid in it. It used to look like a toggle with an empty click
                // handler, which is worse than not offering the choice at all.
                ModeChip(stringResource(R.string.agent_mint_sol), true, Halo.muted, Modifier.weight(1f)) { }
                ModeChip("USDC", usdc, Halo.mint, Modifier.weight(1.4f)) { usdc = !usdc }
            }
            Text(stringResource(R.string.agent_usdc_note), style = HaloType.small, color = Halo.muted)

            ModeChip(stringResource(R.string.agent_any_mint), anyMint, Halo.mint, Modifier.fillMaxWidth()) { anyMint = !anyMint }
            Text(stringResource(R.string.agent_any_mint_note), style = HaloType.small, color = Halo.muted)

            // The person's own rules. What we ship is one way to trade; somebody
            // with years of their own has better ones, and a file they already
            // wrote for another tool should work here without retyping. They can
            // only forbid, and the note says so before anybody expects otherwise.
            Text(stringResource(R.string.rules_yours), style = HaloType.label, color = Halo.muted)
            Text(stringResource(R.string.rules_yours_note), style = HaloType.small, color = Halo.muted, lineHeight = 16.sp)
            val pickRules = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    UserRules.read(ctx, uri)?.let { rules = it; rulesName = UserRules.displayName(ctx, uri) }
                }
            }
            androidx.compose.material3.OutlinedTextField(
                value = rules, onValueChange = { rules = it.take(UserRules.MAX_CHARS); rulesName = null },
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 220.dp),
                placeholder = { Text(stringResource(R.string.rules_yours_hint), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted, lineHeight = 17.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Inter, fontSize = 12.5.sp, color = Halo.ink, lineHeight = 18.sp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Halo.mint, unfocusedBorderColor = Halo.stroke,
                    focusedContainerColor = Halo.cardSoft, unfocusedContainerColor = Halo.cardSoft, cursorColor = Halo.mint,
                ),
                shape = rs(12),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                // Any type: a .md arrives as text/markdown, text/plain or
                // application/octet-stream depending on who wrote it to disk.
                GhostButton(stringResource(R.string.rules_yours_import), Modifier.weight(1f), HIcon.DOWNLOAD, tint = Halo.cyan) { pickRules.launch(arrayOf("*/*")) }
                if (rules.isNotBlank()) {
                    GhostButton(stringResource(R.string.rules_yours_clear), Modifier.weight(1f), HIcon.TRASH, tint = Halo.red) { rules = ""; rulesName = null }
                }
            }
            if (rules.isNotBlank()) {
                Text(
                    stringResource(R.string.rules_yours_len, rulesName ?: stringResource(R.string.rules_yours_typed), rules.length),
                    style = HaloType.small, color = Halo.muted,
                )
                if (!Secrets.model(ctx).ready) Banner(stringResource(R.string.rules_yours_nomodel), Halo.amber, HIcon.WARNING)
            }

            SliderRow(
                stringResource(R.string.env_harvest),
                if (payout <= 0.001f) stringResource(R.string.agent_payout_off) else sol(payout),
                payout, 0f..1f, Halo.mint,
            ) { payout = it }
            Text(stringResource(R.string.agent_payout_note), style = HaloType.small, color = Halo.muted)

            Text(stringResource(R.string.trader_setup_title), style = HaloType.label, color = Halo.muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(stringResource(R.string.trader_off), !trade.on, Halo.muted, Modifier.weight(1f)) { trade = trade.copy(on = false) }
                ModeChip(stringResource(R.string.rules_state_on), trade.on, Halo.mint, Modifier.weight(1f)) { trade = trade.copy(on = true, bold = false) }
            }
            // Said here, while you are choosing, instead of six minutes later.
            val blocked = remember(trade.on) { if (trade.on) TraderLoop.cannotStart(ctx) else null }
            blocked?.let { Banner(it, Halo.amber, HIcon.WARNING) }
            if (trade.on) {
                SliderRow(stringResource(R.string.trader_tp), "+" + trade.takeProfitPct + "%", trade.takeProfitPct.toFloat(), 5f..200f, Halo.mint) {
                    trade = trade.copy(takeProfitPct = it.toInt())
                }
                SliderRow(
                    stringResource(R.string.trader_sl),
                    if (trade.stopLossPct < 1) stringResource(R.string.agent_payout_off) else "-" + trade.stopLossPct + "%",
                    trade.stopLossPct.toFloat(), 0f..80f, Halo.red,
                ) { trade = trade.copy(stopLossPct = it.toInt()) }
                SliderRow(stringResource(R.string.trader_slots), trade.maxPositions.toString(), trade.maxPositions.toFloat(), 1f..5f, Halo.cyan, steps = 3) {
                    trade = trade.copy(maxPositions = it.toInt())
                }
                SizingNote(session.capLamports, (perTx * cap).toLong(), (askAbove * cap).toLong(), trade.slicePercent, trade.maxPositions)
                Text(stringResource(R.string.trader_truth), style = HaloType.small, color = Halo.muted, lineHeight = 16.sp)
            }

            Text(stringResource(R.string.agent_destinations), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
            if (contacts.isEmpty()) {
                Text(stringResource(R.string.agent_no_contacts), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted, lineHeight = 17.sp)
            } else {
                contacts.forEach { (addr, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(rs(12)).background(Halo.cardSoft).border(1.dp, if (addr in whom) Halo.mint else Halo.stroke, rs(12))
                            .clickable { whom = if (addr in whom) whom - addr else whom + addr }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HaloIcon(if (addr in whom) HIcon.CHECK else HIcon.BLOCK, if (addr in whom) Halo.mint else Halo.muted, 14.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(label, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Halo.ink, modifier = Modifier.weight(1f))
                        Text(shorten(addr, 4), fontFamily = Mono, fontSize = 11.sp, color = Halo.muted)
                    }
                }
            }

            Text(stringResource(R.string.env_truth), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, lineHeight = 16.sp)

            PrimaryButton(stringResource(R.string.save), danger = false, icon = HIcon.CHECK) {
                val mints = if (usdc) AgentPolicy.BASE_MINTS else AgentPolicy.BASE_MINTS - AgentPolicy.USDC
                SessionWallet.setHarvest(ctx, if (payout <= 0.001f) 0L else (payout * cap).toLong())
                UserRules.set(ctx, rules, rulesName)
                // Switching on from here clears the last stop's note too: it was
                // about a run that is over.
                if (trade.on && !TraderLoop.config(ctx).on) TraderLoop.start(ctx, trade) else TraderLoop.setConfig(ctx, trade)
                TraderKeeper.sync(ctx)
                SessionWallet.setPolicy(
                    ctx,
                    policy.copy(
                        perTxLamports = (perTx * cap).toLong(), dailyLamports = (daily * cap).toLong(), askAboveLamports = (askAbove * cap).toLong(),
                        maxTxPerHour = perHour.toInt().coerceAtLeast(1), allowedMints = mints, allowAnyMint = anyMint,
                        allowedDestinations = whom + owner + session.pubkey,
                    ),
                )
                onDone()
            }
            GhostButton(stringResource(R.string.cancel)) { onDismiss() }
        }
    }
}

@Composable
internal fun SliderRow(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, tint: Color, steps: Int = 0, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Halo.muted)
            Text(value, fontFamily = Mono, fontSize = 13.sp, color = Halo.ink, style = Tabular)
        }
        Slider(
            value = v, onValueChange = onChange, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = tint, activeTrackColor = tint.copy(alpha = 0.7f), inactiveTrackColor = Halo.stroke),
        )
    }
}
