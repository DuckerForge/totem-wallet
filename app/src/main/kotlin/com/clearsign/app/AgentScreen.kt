@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.clearsign.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clearsign.core.AgentMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Agent tab, in two depths.
 *
 * Simple answers the three questions a person actually has, in order: is it
 * working, what does it hold, what did it do. One line of state, one block of
 * money, two buttons, the open coins, the last moves, and a row of small chips
 * for the rest. No paragraphs: the guarantees live behind one link.
 *
 * Pro, a switch at the top that is remembered, adds everything else underneath:
 * the model, the collar's numbers, lane and targets with the shadow book, the
 * live trace, and the bridge to an agent on a computer. The tab used to show
 * all of it to everyone, seven paragraphs and twenty buttons on one scroll, and
 * half of it about a bridge to a PC that most people will never run.
 */
@Composable
internal fun AgentScreen(owner: String?, signer: SeedVaultSigner, onChat: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val session = remember(refresh) { SessionWallet.current(ctx) }
    val policy = remember(refresh) { SessionWallet.policy(ctx) }
    val history = remember(refresh) { SessionWallet.history(ctx) }
    val cfg = remember(refresh) { TraderLoop.config(ctx) }
    val link by AgentLink.state.collectAsState()
    val pro by Settings.agentPro
    /* The account we pay from: the live connection, or the one this phone watches. */
    val account = owner ?: Settings.watchWallet(ctx)

    var balance by remember(refresh, session?.pubkey) { mutableStateOf<Long?>(null) }
    var invested by remember(refresh, session?.pubkey) { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var showNew by remember { mutableStateOf(false) }
    var showRules by remember { mutableStateOf(false) }
    var showTopUp by remember { mutableStateOf(false) }
    var showLane by remember { mutableStateOf(false) }
    var showEyes by remember { mutableStateOf(false) }
    var showTruth by remember { mutableStateOf(false) }
    var showLinkHelp by remember { mutableStateOf(false) }
    var lucky by remember { mutableStateOf(false) }
    var coins by remember { mutableStateOf<List<SolanaRpc.TokenAccountInfo>>(emptyList()) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val scan = rememberAgentScan { scanError = it }

    LaunchedEffect(Unit) { AgentLink.restoreState(ctx) }
    LaunchedEffect(session?.pubkey, refresh) {
        val pub = session?.pubkey ?: return@LaunchedEffect
        balance = withContext(Dispatchers.IO) { runCatching { SolanaRpc.getBalance(SolanaRpc.urlFor(null), pub) }.getOrNull() }
        // What the coins it bought are worth right now. Without this the SOL left
        // over would be called the result, and the minute the agent bought
        // anything it would look like it had lost the money.
        invested = Positions.open(ctx).sumOf { p -> runCatching { SessionActions.quoteValue(ctx, p) }.getOrNull() ?: 0L }
    }

    fun closeNow() {
        if (SessionWallet.current(ctx) == null || account == null) return
        busy = ctx.getString(R.string.env_closing)
        scope.launch {
            val (_, said) = runCatching { SessionActions.closeBudget(ctx, account) }.getOrDefault(false to ctx.getString(R.string.trader_net_down))
            note = said
            busy = null; coins = emptyList(); refresh++
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Name, one line of context, and the one switch this page has.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(38.dp).clip(rs(12)).background(Halo.mint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    HaloIcon(HIcon.AGENT, Halo.mint, 22.dp)
                }
                SweepHalo(Halo.mint, Modifier.size(38.dp), key = FirstRun.at)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tab_agent), style = HaloType.screen, color = Halo.ink)
                Text(
                    when (val l = link) {
                        is AgentLink.State.On -> stringResource(R.string.agent_link_to, l.name)
                        else -> stringResource(R.string.agent_tab_sub)
                    },
                    style = HaloType.small, color = Halo.muted, maxLines = 2,
                )
            }
            ProSwitch(pro) { Settings.setAgentPro(ctx, it); Haptics.tick(ctx) }
        }

        NightCard(refresh)

        if (session == null || policy == null) {
            // No budget: one card, one sentence, two ways in. The chat needs no
            // budget, because it can only ever propose.
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.agent_nobudget_title), style = HaloType.title, color = Halo.ink)
                    Text(stringResource(R.string.env_none), style = HaloType.small, color = Halo.muted)
                    PrimaryButton(stringResource(R.string.env_create), danger = false, enabled = owner != null, icon = HIcon.HOURGLASS) { showNew = true }
                    GhostButton(stringResource(R.string.chat_open), Modifier.fillMaxWidth(), HIcon.AGENT, tint = Halo.cyan) { onChat() }
                    if (owner == null) Text(stringResource(R.string.agent_tab_none), style = HaloType.small, color = Halo.amber)
                }
            }
            note?.let { Banner(it, Halo.amber, HIcon.INFO) }
            // The first minute, said before the first budget: three steps and what to expect.
            if (SessionWallet.lastClose(ctx) == null) GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.first_title).uppercase(), style = HaloType.label, color = Halo.muted)
                    listOf(R.string.first_1, R.string.first_2, R.string.first_3).forEachIndexed { i, r ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text((i + 1).toString(), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Halo.mint, modifier = Modifier.width(18.dp))
                            Text(stringResource(r), style = HaloType.small, color = Halo.ink, lineHeight = 17.sp)
                        }
                    }
                    Text(stringResource(R.string.first_note), style = HaloType.small, color = Halo.muted, lineHeight = 16.sp)
                }
            }
            LastBudget(refresh)
        } else {
            AgentPulse(refresh)

            // The money, in the order a person asks: how much is here, how did
            // it go, and the three numbers behind that.
            val inCoins = invested ?: 0L
            val total = (balance ?: 0L) + inCoins
            val diff = if (balance == null) null else total - session.fundedLamports + session.harvestedLamports
            val openCount = Positions.open(ctx).size
            Column(
                Modifier.fillMaxWidth().clip(rs(Radius.panel)).background(Halo.cardSoft).haloBorder(rs(Radius.panel)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (balance == null) "…" else fmtSol(total, 4), style = HaloType.amount, color = Halo.ink)
                    Spacer(Modifier.width(6.dp))
                    Text("SOL", fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.muted, modifier = Modifier.padding(bottom = 6.dp))
                    Spacer(Modifier.weight(1f))
                    if (diff != null && diff != 0L) {
                        val pct = if (session.fundedLamports > 0) diff * 100.0 / session.fundedLamports else 0.0
                        Text(
                            (if (diff > 0) "+" else "−") + fmtSol(kotlin.math.abs(diff), 4) + String.format(" (%+.1f%%)", pct),
                            fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, style = Tabular,
                            color = if (diff > 0) Halo.mint else Halo.red, modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
                // Da dove e' partita a dove sta adesso.
                //
                // Il totale grande e la differenza col segno c'erano gia', ma la
                // differenza da sola non dice mai da dove viene: "+0,008" e'
                // tanto o poco a seconda di quanto ci hai messo, e quel numero
                // non stava da nessuna parte su questa pagina. Due righe, i due
                // capi, e in mezzo la strada.
                if (balance != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.env_put_in), style = HaloType.small, color = Halo.muted, modifier = Modifier.width(96.dp))
                        Text(fmtSol(session.fundedLamports, 4) + " SOL", fontFamily = Mono, fontSize = 12.sp, color = Halo.ink, style = Tabular)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.env_now), style = HaloType.small, color = Halo.muted, modifier = Modifier.width(96.dp))
                        Text(fmtSol(total, 4) + " SOL", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = if ((diff ?: 0L) >= 0) Halo.mint else Halo.red, style = Tabular)
                    }
                }
                Text(
                    if (openCount > 0) stringResource(R.string.agent_hero_line, fmtSol(balance ?: 0L, 3), openCount, fmtSol(inCoins, 3), fmtSol(history.spentLast24hLamports, 3), fmtSol(policy.dailyLamports, 3))
                    else stringResource(R.string.agent_hero_line_flat, fmtSol(balance ?: 0L, 3), fmtSol(history.spentLast24hLamports, 3), fmtSol(policy.dailyLamports, 3)),
                    fontFamily = Mono, fontSize = 11.5.sp, color = Halo.muted, style = Tabular,
                )
                if (session.expired) Banner(stringResource(R.string.env_expired_note), Halo.amber, HIcon.HOURGLASS)
            }

            // The two things you do here. Starting is a choice of lane, so it
            // opens a small sheet rather than flipping a switch.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (cfg.on) {
                    GhostButton(stringResource(R.string.trader_stop_action), Modifier.weight(1f), HIcon.BLOCK, tint = Halo.amber, height = 54.dp) {
                        TraderLoop.stop(ctx, ctx.getString(R.string.trader_stopped_by_you)); TraderKeeper.sync(ctx); refresh++
                    }
                } else {
                    GhostButton(stringResource(R.string.agent_start), Modifier.weight(1f), HIcon.AGENT, tint = Halo.mint, height = 54.dp) { showLane = true }
                }
                Box(Modifier.weight(1f)) { PrimaryButton(stringResource(R.string.chat_open), danger = false, icon = HIcon.AGENT) { onChat() } }
            }
            // The screen that never sleeps: charts, lines, and the loop's own words.
            GhostButton(stringResource(R.string.eyes_open), Modifier.fillMaxWidth(), HIcon.SEARCH, tint = Halo.cyan) { showEyes = true }

            val open = remember(refresh) { Positions.open(ctx) }
            if (open.isNotEmpty()) {
                GlassCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.agent_positions).uppercase(), style = HaloType.label, color = Halo.muted)
                        // A stopped loop with coins still open is the state nobody
                        // should have to work out for themselves. The stop loss
                        // lives in the loop and nowhere else, so while it is off a
                        // position with no on-chain order has nothing watching it
                        // in either direction. Said here, in red, above the rows.
                        val unwatched = open.count { !it.parked && it.triggerOrder == null }
                        if (!cfg.on && unwatched > 0) {
                            Banner(stringResource(R.string.trader_unwatched, unwatched), Halo.red, HIcon.WARNING)
                        }
                        open.forEach { pos -> PositionRow(pos, refresh) { refresh++ } }
                    }
                }
            }

            RecentMoves(refresh)
            FollowsSection(refresh)

            // Everything else, small. Each opens the same sheet it always did.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (account != null) SmallChip(stringResource(R.string.env_add), HIcon.DOWNLOAD, tint = Halo.cyan) { showTopUp = true }
                val gain = ((balance ?: 0L) - session.fundedLamports).coerceAtLeast(0L)
                if (gain > 5_000L && account != null) {
                    SmallChip(stringResource(R.string.env_harvest_now), HIcon.DOWNLOAD, tint = Halo.mint) {
                        busy = ctx.getString(R.string.env_harvest_now)
                        scope.launch {
                            val took = SessionActions.harvest(ctx, account, force = true)
                            busy = null
                            note = if (took == null) ctx.getString(R.string.env_harvest_failed) else if (took > 0) ctx.getString(R.string.env_harvest_done, fmtSol(took, 5)) else null
                            refresh++
                        }
                    }
                }
                SmallChip(stringResource(R.string.agent_rules), HIcon.SHIELD_LOCK, tint = Halo.cyan) { showRules = true }
                if (inCoins > 0) {
                    SmallChip(stringResource(R.string.env_sell_all), HIcon.SWAP, tint = Halo.amber) {
                        busy = ctx.getString(R.string.env_sell_all_busy)
                        scope.launch {
                            val stuck = runCatching { SessionActions.sellAll(ctx) }.getOrDefault(listOf("?"))
                            busy = null
                            note = if (stuck.isEmpty()) ctx.getString(R.string.env_sell_all_done) else ctx.getString(R.string.env_sell_all_stuck, stuck.joinToString(", "))
                            refresh++
                        }
                    }
                }
                SmallChip(stringResource(R.string.env_close), HIcon.BLOCK, tint = Halo.red) {
                    // Look inside before the key disappears: a coin left in a
                    // closed budget is a coin nobody can ever reach again.
                    busy = ctx.getString(R.string.env_closing)
                    scope.launch {
                        val inside = runCatching { SessionActions.holdings(ctx) }.getOrDefault(emptyList())
                        busy = null
                        if (inside.isEmpty()) closeNow() else coins = inside
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().clip(rs(Radius.row)).clickable { showTruth = true }.padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HaloIcon(HIcon.INFO, Halo.muted, 14.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.agent_truth_link), style = HaloType.small, color = Halo.muted, modifier = Modifier.weight(1f))
                HaloIcon(HIcon.CHEVRON_RIGHT, Halo.muted, 14.dp)
            }
        }

        busy?.let { Working(it) }
        note?.let { Banner(it, Halo.amber, HIcon.WARNING) }

        if (pro) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.agent_pro).uppercase(), style = HaloType.label, color = Halo.amber)

            ProSection(stringResource(R.string.pro_model), HIcon.KEY, openAtFirst = !Brain.configured(ctx)) { BrainFields() }

            if (session != null && policy != null) {
                ProSection(stringResource(R.string.pro_collar), HIcon.SHIELD_LOCK) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModeChip(stringResource(R.string.agent_mode_off), policy.mode == AgentMode.OFF, Halo.muted, Modifier.weight(1f)) { SessionWallet.setMode(ctx, AgentMode.OFF); refresh++ }
                        ModeChip(stringResource(R.string.agent_mode_auto), policy.mode == AgentMode.AUTONOMOUS, Halo.mint, Modifier.weight(1.4f)) { SessionWallet.setMode(ctx, AgentMode.AUTONOMOUS); refresh++ }
                        ModeChip(stringResource(R.string.agent_mode_ask), policy.mode == AgentMode.ASK_ALWAYS, Halo.cyan, Modifier.weight(1.2f)) { SessionWallet.setMode(ctx, AgentMode.ASK_ALWAYS); refresh++ }
                    }
                    StatRow(stringResource(R.string.agent_per_tx), fmtSol(policy.perTxLamports, 4) + " SOL")
                    StatRow(stringResource(R.string.agent_daily), fmtSol(policy.dailyLamports, 4) + " SOL")
                    StatRow(stringResource(R.string.agent_ask_above), fmtSol(policy.askAboveLamports, 4) + " SOL")
                    StatRow(stringResource(R.string.agent_per_hour), policy.maxTxPerHour.toString())
                    StatRow(stringResource(R.string.env_put_in), fmtSol(session.fundedLamports, 4) + " SOL")
                    if (session.harvestedLamports > 0) StatRow(stringResource(R.string.env_harvested), "+" + fmtSol(session.harvestedLamports, 5) + " SOL", accent = true)
                    StatRow(stringResource(R.string.env_expiry), if (session.expired) stringResource(R.string.env_expired) else stringResource(R.string.env_days, session.daysLeft))
                    GhostButton(stringResource(R.string.agent_rules), Modifier.fillMaxWidth(), HIcon.SHIELD_LOCK, tint = Halo.cyan) { showRules = true }
                }

                ProSection(stringResource(R.string.pro_trading), HIcon.GEM) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.trader_lane_one),
                            fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Halo.ink, modifier = Modifier.weight(1f),
                        )
                        Text(stringResource(R.string.trader_targets, cfg.takeProfitPct, cfg.stopLossPct), fontFamily = Mono, fontSize = 11.sp, color = Halo.muted)
                    }
                    TraderLoop.lastNote(ctx)?.let { Text(it, style = HaloType.small, color = Halo.muted, lineHeight = 16.sp) }
                    Text(stringResource(R.string.trader_truth), style = HaloType.small, color = Halo.muted, lineHeight = 16.sp)
                    GhostButton(stringResource(R.string.lucky_title), Modifier.fillMaxWidth(), HIcon.GEM, tint = Halo.cyan) { lucky = true }
                    PaperCard(refresh) { refresh++ }
                }
            }

            ProSection(stringResource(R.string.pro_trace), HIcon.AGENT) { AgentConsole() }

            // The bridge to an agent on a computer: the four cards the tab used
            // to open with, folded into one section for the people who run one.
            ProSection(stringResource(R.string.pro_pc), HIcon.SCAN) {
                Text(stringResource(R.string.agent_tab_how), style = HaloType.small, color = Halo.muted, lineHeight = 18.sp)
                if (owner != null) {
                    val clip = LocalClipboardManager.current
                    Text(stringResource(R.string.agent_tab_wallet), style = HaloType.label, color = Halo.muted)
                    Text(owner, fontFamily = Mono, fontSize = 11.5.sp, color = Halo.ink, lineHeight = 17.sp)
                    GhostButton(stringResource(R.string.copy), Modifier.fillMaxWidth(), HIcon.COPY, tint = Halo.cyan) { clip.setText(AnnotatedString(owner)); Haptics.tick(ctx) }
                } else {
                    Text(stringResource(R.string.agent_tab_none), style = HaloType.small, color = Halo.amber)
                }
                PrimaryButton(stringResource(R.string.gate_scan), danger = false, icon = HIcon.SCAN) { scan() }
                if (session != null) {
                    if (link is AgentLink.State.On) {
                        GhostButton(stringResource(R.string.agent_unlink), Modifier.fillMaxWidth(), HIcon.BLOCK, tint = Halo.amber) { AgentLinkService.revoke(ctx); refresh++ }
                    } else {
                        GhostButton(stringResource(R.string.agent_link_btn), Modifier.fillMaxWidth(), HIcon.SCAN, tint = Halo.muted) { showLinkHelp = true }
                    }
                }
                scanError?.let { Banner(it, Halo.amber, HIcon.WARNING) }
                Text(stringResource(R.string.agent_tab_setup), style = HaloType.small, color = Halo.muted, lineHeight = 16.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showNew && owner != null) NewEnvelopeSheet(owner, signer, onDone = { showNew = false; refresh++ }, onDismiss = { showNew = false })
    if (showLinkHelp) LinkHelpSheet(onScan = { showLinkHelp = false; scan() }) { showLinkHelp = false }
    if (lucky) LuckySheet(onDone = { refresh++ }) { lucky = false }
    if (showTruth) TruthSheet { showTruth = false }
    if (showEyes) EyesDialog(onClose = { showEyes = false }, onStart = { showLane = true })
    if (showLane) LaneSheet(onStarted = { showLane = false; refresh++ }) { showLane = false }
    if (coins.isNotEmpty()) {
        ClosingCoinsSheet(
            coins = coins,
            onSell = {
                busy = ctx.getString(R.string.env_closing)
                scope.launch {
                    val stuck = runCatching { SessionActions.sellAll(ctx) }.getOrDefault(emptyList())
                    if (stuck.isEmpty()) closeNow() else { busy = null; note = ctx.getString(R.string.env_stuck, stuck.joinToString(", ")) }
                }
            },
            onMove = {
                val to = account
                if (to == null) note = ctx.getString(R.string.sv_not_connected) else {
                    busy = ctx.getString(R.string.env_closing)
                    scope.launch {
                        val stuck = runCatching { SessionActions.moveTokensTo(ctx, to) }.getOrDefault(emptyList())
                        if (stuck.isEmpty()) closeNow() else { busy = null; note = ctx.getString(R.string.env_stuck, stuck.joinToString(", ")) }
                    }
                }
            },
            onDismiss = { coins = emptyList() },
        )
    }
    if (showTopUp && account != null) {
        SessionWallet.current(ctx)?.let { s -> TopUpSheet(account, signer, s, onDone = { showTopUp = false; refresh++ }, onDismiss = { showTopUp = false }) }
    }
    if (showRules && session != null && policy != null && owner != null) {
        RulesSheet(policy, session, owner, onTopUp = { showRules = false; showTopUp = true }, onDone = { showRules = false; refresh++ }, onDismiss = { showRules = false })
    }
}

/* The one switch: Simple or Pro, as a small pill that reads as a toggle. */
@Composable
private fun ProSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val tint = if (on) Halo.amber else Halo.muted
    Row(
        Modifier.clip(rs(Radius.pill)).background(tint.copy(alpha = if (on) 0.16f else 0.08f)).border(1.dp, tint.copy(alpha = 0.6f), rs(Radius.pill))
            .clickable { onChange(!on) }.padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(rs(Radius.pill)).background(tint))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.agent_pro), fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, color = tint)
    }
}

/* A Pro section: a title you tap, and its content when open. Closed by default, so the page stays a page. */
@Composable
private fun ProSection(title: String, icon: HIcon, openAtFirst: Boolean = false, content: @Composable () -> Unit) {
    var open by remember(title) { mutableStateOf(openAtFirst) }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                HaloIcon(icon, Halo.amber, 15.dp)
                Spacer(Modifier.width(8.dp))
                Text(title, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Halo.ink, modifier = Modifier.weight(1f))
                HaloIcon(HIcon.CHEVRON_DOWN, Halo.muted, 14.dp, Modifier.rotate(if (open) 180f else 0f))
            }
            if (open) content()
        }
    }
}

/*
 * What the agent did last, five lines. Each is a ledger row, so the receipt
 * behind it is one tab away; here it is the sentence, the sum, and when.
 */
/** The last budget's account: what went in, what came back, how it went. Shown where the next one is made. */
@Composable
private fun LastBudget(refresh: Int) {
    val ctx = LocalContext.current
    val c = remember(refresh) { SessionWallet.lastClose(ctx) } ?: return
    val up = c.resultLamports >= 0
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.env_last_title).uppercase(), style = HaloType.label, color = Halo.muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.env_last_in, fmtSol(c.fundedLamports, 4)), fontFamily = Inter, fontSize = 12.5.sp, color = Halo.ink)
                    Text(stringResource(R.string.env_last_out, fmtSol(c.backLamports + c.harvestedLamports, 4)), fontFamily = Inter, fontSize = 12.5.sp, color = Halo.ink)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        (if (up) "+" else "−") + fmtSol(kotlin.math.abs(c.resultLamports), 4) + " SOL",
                        fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (up) Halo.mint else Halo.red, style = Tabular,
                    )
                    Text(String.format(java.util.Locale.ROOT, "%+.1f%%", c.resultPct), fontFamily = Mono, fontSize = 11.5.sp, color = if (up) Halo.mint else Halo.red, style = Tabular)
                }
            }
            val mins = ((c.closedAt - c.createdAt) / 60_000L).coerceAtLeast(0L)
            val dur = if (mins >= 60) String.format(java.util.Locale.ROOT, "%dh %02dm", mins / 60, mins % 60) else "$mins min"
            Text(stringResource(R.string.env_last_moves, c.buys, c.sells, dur), style = HaloType.small, color = Halo.muted)
            GhostButton(stringResource(R.string.pnl_card_share), Modifier.fillMaxWidth(), HIcon.SHARE, tint = Halo.cyan) {
                PnlCard.share(
                    ctx, PnlCard.Face(
                        ctx.getString(R.string.env_last_title), ctx.getString(R.string.env_last_moves, c.buys, c.sells, dur), c.resultPct,
                        (if (up) "+" else "−") + fmtSol(kotlin.math.abs(c.resultLamports), 4) + " SOL", ctx.getString(R.string.pnl_card_foot),
                    ),
                    "budget-" + c.closedAt + ".png",
                )
            }
        }
    }
}

/**
 * Copy trading, where the person can see it. The star in Scout follows a
 * wallet; this is the list of what that star did, with each wallet's last buy
 * from the feed. What they buy goes to the front of the loop's list, through
 * the same gates as everything else.
 */
@Composable
private fun FollowsSection(refresh: Int) {
    val ctx = LocalContext.current
    val follows = remember(refresh) { Follows.all(ctx).toList() }
    val events = remember(refresh) { runCatching { SeekerFeed.cached(ctx)?.events }.getOrNull().orEmpty() }
    val now = System.currentTimeMillis()
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HaloIcon(HIcon.STAR, Halo.amber, 14.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.agent_copy_title).uppercase(), style = HaloType.label, color = Halo.muted, modifier = Modifier.weight(1f))
                if (follows.isNotEmpty()) Text(stringResource(R.string.crowd_followed, follows.size), style = HaloType.small, color = Halo.muted)
            }
            if (follows.isEmpty()) {
                Text(stringResource(R.string.agent_copy_none), style = HaloType.small, color = Halo.muted, lineHeight = 16.sp)
            }
            follows.forEach { w ->
                val last = events.filter { it.wallet == w }.maxByOrNull { it.at }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(com.clearsign.core.SeekerCrowd.nickname(w), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Halo.ink, maxLines = 1)
                        Text(
                            if (last != null) stringResource(R.string.agent_copy_last, last.symbol, ((now - last.at) / 60_000L).coerceAtLeast(0L))
                            else stringResource(R.string.agent_copy_quiet),
                            fontFamily = Inter, fontSize = 10.5.sp, color = if (last != null) Halo.mint else Halo.muted, maxLines = 1,
                        )
                    }
                    // Tre comandi, e nessuno dei tre sembrava un comando.
                    //
                    // Erano tre scritte nude: "sells too", "buys only", "Following".
                    // Toccandole succedeva qualcosa, ma niente diceva che si
                    // potessero toccare ne' quale fosse lo stato acceso. E la
                    // domanda che la gente si fa qui e' "come lo metto a
                    // copiare", a cui la risposta e' che lo sta gia' facendo:
                    // seguire uno **e'** copiarlo. Adesso c'e' scritto, e le due
                    // cose che si possono davvero cambiare hanno la forma di
                    // cose che si cambiano.
                    var mirror by remember(w) { mutableStateOf(Follows.mirrors(ctx, w)) }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            stringResource(if (mirror) R.string.follow_copy_both else R.string.follow_copy_buys),
                            fontFamily = Inter, fontSize = 10.sp, color = if (mirror) Halo.mint else Halo.muted, maxLines = 1,
                        )
                        androidx.compose.material3.Switch(
                            checked = mirror,
                            onCheckedChange = { mirror = it; Follows.setMirror(ctx, w, it); Haptics.tick(ctx) },
                            modifier = Modifier.scale(0.72f),
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                    // La stella, la stessa di Scout: accesa vuol dire che lo
                    // segui, e toccarla smette. Un'icona che si riconosce batte
                    // una parola che va letta.
                    Box(
                        Modifier.size(34.dp).clip(rs(999)).clickable { Follows.toggle(ctx, w); Haptics.tick(ctx) },
                        contentAlignment = Alignment.Center,
                    ) { HaloIcon(HIcon.STAR_FILLED, Halo.amber, 17.dp) }
                }
            }
            Text(stringResource(R.string.agent_copy_how), style = HaloType.small, color = Halo.muted, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun RecentMoves(refresh: Int) {
    val ctx = LocalContext.current
    val recent = remember(refresh) {
        runCatching { Ledger.all(ctx).filter { it.kind == "agent" } }.getOrDefault(emptyList()).sortedByDescending { it.at }.take(5)
    }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(stringResource(R.string.agent_recent).uppercase(), style = HaloType.label, color = Halo.muted)
            if (recent.isEmpty()) {
                Text(stringResource(R.string.agent_recent_none), style = HaloType.small, color = Halo.muted)
            }
            recent.forEach { e ->
                val refused = e.host == "refused" || e.host == "expired"
                val tint = if (refused) Halo.red else if (e.host == "asked") Halo.cyan else Halo.mint
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HaloIcon(if (refused) HIcon.BLOCK else if (e.sent) HIcon.CHECK else HIcon.PEN, tint, 14.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        val out = e.outflows.firstOrNull()
                        val inn = e.inflows.firstOrNull()
                        Text(
                            when {
                                out != null && inn != null -> fmtUi(kotlin.math.abs(out.uiAmount)) + " " + out.symbol + " → " + inn.symbol
                                out != null -> "−" + fmtUi(kotlin.math.abs(out.uiAmount)) + " " + out.symbol
                                else -> e.dApp
                            } + agentHow(ctx, e.host),
                            fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Halo.ink, maxLines = 1,
                        )
                        if (refused && e.note.isNotBlank()) Text(e.note, fontFamily = Inter, fontSize = 10.5.sp, color = Halo.red, maxLines = 2, lineHeight = 14.sp)
                    }
                    Text(
                        android.text.format.DateUtils.getRelativeTimeSpanString(e.at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString(),
                        fontFamily = Inter, fontSize = 10.5.sp, color = Halo.muted,
                    )
                }
            }
        }
    }
}

/**
 * Starting is a choice of lane, said in the two words people in crypto use.
 * The reason it cannot start, when there is one, is here before the button,
 * not six minutes later in a note.
 */
@Composable
private fun LaneSheet(onStarted: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val blocked = remember { TraderLoop.cannotStart(ctx) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            var cfg by remember { mutableStateOf(TraderLoop.config(ctx)) }
            Text(stringResource(R.string.lane_title), style = HaloType.title, color = Halo.ink)
            Text(stringResource(R.string.lane_body), style = HaloType.small, color = Halo.muted)
            StatRow(stringResource(R.string.trader_tp), "+" + cfg.takeProfitPct + "%", accent = true)
            StatRow(stringResource(R.string.trader_sl), if (cfg.stopLossPct < 1) stringResource(R.string.agent_payout_off) else "-" + cfg.stopLossPct + "%")
            // I due numeri che si vogliono cambiare sulla porta, e sono due
            // domande diverse che sembravano una sola.
            //
            // Quante monete alla volta risponde a "su quante cose scommetto".
            // Quanto ci mette risponde a "quanto rischio su ognuna", ed era
            // l'unica delle due che non si poteva toccare: stava nel motore,
            // ferma all'ottanta per cento. Con un posto solo l'ottanta per cento
            // di tutta la paghetta sembra tutta la paghetta, e l'unico modo di
            // metterci meno era stringere il collare, cioe' rispondere a una
            // terza domanda che nessuno aveva fatto. La riga qui sotto dice la
            // fetta in SOL e in soldi, e si muove con tutti e due i cursori.
            SliderRow(stringResource(R.string.trader_slots), cfg.maxPositions.toString(), cfg.maxPositions.toFloat(), 1f..5f, Halo.cyan, steps = 3) {
                cfg = cfg.copy(maxPositions = it.toInt().coerceIn(1, 5))
            }
            SliderRow(
                stringResource(R.string.agent_slice), cfg.slicePercent.toString() + "%",
                cfg.slicePercent / 100f, 0.1f..1f, Halo.mint, steps = 17,
            ) { cfg = cfg.copy(slicePercent = (it * 100).toInt().coerceIn(10, 100)) }
            // La commissione che la moneta stessa trattiene. Zero e' la porta
            // chiusa, ed e' il valore di partenza: si apre solo di proposito.
            SliderRow(
                stringResource(R.string.agent_fee_max),
                if (cfg.maxFeePct == 0) stringResource(R.string.agent_payout_off) else cfg.maxFeePct.toString() + "%",
                cfg.maxFeePct / 10f, 0f..1f, Halo.amber, steps = 9,
            ) { cfg = cfg.copy(maxFeePct = (it * 10).toInt().coerceIn(0, 10)) }
            run {
                val s = SessionWallet.current(ctx); val p = SessionWallet.policy(ctx)
                if (s != null && p != null) SizingNote(s.capLamports, p.perTxLamports, p.askAboveLamports, cfg.slicePercent, cfg.maxPositions)
            }
            // Scavare ORE: una parte della paghetta affidata a un esecutore,
            // tanto al giorno su tante caselle, sotto lo stesso collare.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.agent_ore_title), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.ink)
                    Text(stringResource(R.string.agent_ore_sub), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted)
                }
                androidx.compose.material3.Switch(checked = cfg.oreOn, onCheckedChange = { cfg = cfg.copy(oreOn = it) })
            }
            if (cfg.oreOn) {
                SliderRow(
                    stringResource(R.string.agent_ore_day), fmtSol(cfg.oreLamportsPerDay, 3) + " SOL",
                    (cfg.oreLamportsPerDay / 1_000_000L).toFloat(), 10f..200f, Halo.cyan, steps = 18,
                ) { cfg = cfg.copy(oreLamportsPerDay = (it.toLong() * 1_000_000L).coerceIn(10_000_000L, 200_000_000L)) }
                SliderRow(stringResource(R.string.agent_ore_squares), cfg.oreSquares.toString(), cfg.oreSquares.toFloat(), 1f..10f, Halo.mint, steps = 8) {
                    cfg = cfg.copy(oreSquares = it.toInt().coerceIn(1, 10))
                }
                run {
                    val s = SessionWallet.current(ctx); val p = SessionWallet.policy(ctx)
                    val ceiling = if (p != null) minOf(p.perTxLamports, p.askAboveLamports.takeIf { it > 0 } ?: p.perTxLamports) else 0L
                    val size = OreAgent.sizing(cfg.oreLamportsPerDay, cfg.oreSquares, ceiling, s?.capLamports ?: 0L)
                    Text(
                        if (size == null) stringResource(R.string.agent_ore_too_small)
                        else stringResource(R.string.agent_ore_note, fmtSol(size.amountPerSquare, 5), size.squares, fmtSol(size.deposit, 4), size.rounds),
                        fontFamily = Inter, fontSize = 11.sp, color = if (size == null) Halo.amber else Halo.muted,
                    )
                }
                Text(stringResource(R.string.ore_wager_note), fontFamily = Inter, fontSize = 11.sp, color = Halo.amber)
            }
            blocked?.let { Banner(it, Halo.amber, HIcon.WARNING) }
            PrimaryButton(stringResource(R.string.lane_start), danger = false, enabled = blocked == null, icon = HIcon.AGENT) {
                TraderLoop.start(ctx, cfg)
                TraderKeeper.sync(ctx)
                Haptics.tick(ctx)
                onStarted()
            }
            GhostButton(stringResource(R.string.cancel), Modifier.fillMaxWidth()) { onDismiss() }
        }
    }
}

/** The guarantees, all of them, in one place, for whoever asks. Off the page, because the page is for what is happening. */
@Composable
private fun TruthSheet(onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.agent_truth_link), style = HaloType.title, color = Halo.ink)
            TruthBlock(HIcon.SHIELD_LOCK, stringResource(R.string.agent_state_title), stringResource(R.string.agent_state_body))
            TruthBlock(HIcon.HOURGLASS, stringResource(R.string.agent_card_title), stringResource(R.string.env_truth))
            TruthBlock(HIcon.AGENT, stringResource(R.string.chat_title), stringResource(R.string.brain_truth))
            GhostButton(stringResource(R.string.close), Modifier.fillMaxWidth()) { onDismiss() }
        }
    }
}

@Composable
private fun TruthBlock(icon: HIcon, title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HaloIcon(icon, Halo.mint, 15.dp)
            Spacer(Modifier.width(8.dp))
            Text(title, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Halo.ink)
        }
        Text(body, style = HaloType.small, color = Halo.muted)
    }
}

/** Twelve hours: long enough to hold a night, short enough that it is still news. */
private const val NIGHT_MS = 12 * 3600_000L

/**
 * What happened while you slept.
 *
 * A trading desk spends the first hour of the day building context before it
 * touches anything: what moved overnight, what it is still holding, how much it
 * is allowed to lose today. On a phone that hour does not exist, and the parts
 * of it a wallet can honestly answer are already sitting on this device.
 *
 * So this costs **nothing**. Four local reads: the ledger file, the positions in
 * prefs, the crowd file Scout already downloaded, and the budget's own caps. A
 * morning brief that spent a request every time it was opened would be a brief
 * people learn not to open, and the quota it eats is the quota the crowd scan
 * needs.
 *
 * It draws nothing when the night was empty. A card that says "nothing happened"
 * every morning teaches people to scroll past it, and then it is not there on
 * the morning something did.
 */
@Composable
private fun NightCard(refresh: Int) {
    val ctx = LocalContext.current
    val now = remember(refresh) { System.currentTimeMillis() }
    val since = now - NIGHT_MS

    val moves = remember(refresh) {
        runCatching { Ledger.range(ctx, since, now).filter { it.kind == "agent" } }
            .getOrDefault(emptyList()).sortedByDescending { it.at }
    }
    val held = remember(refresh) { runCatching { Positions.open(ctx) }.getOrDefault(emptyList()) }
    // Scout's own file, whatever age it is. Refreshing it here would turn opening
    // this page into a request, and the page is opened every morning.
    val crowd = remember(refresh) {
        runCatching { SeekerFeed.cached(ctx)?.events.orEmpty().filter { it.at >= since && !it.sell } }
            .getOrDefault(emptyList())
    }
    val policy = remember(refresh) { SessionWallet.policy(ctx) }
    val spent = remember(refresh) { runCatching { SessionWallet.history(ctx).spentLast24hLamports }.getOrDefault(0L) }

    if (moves.isEmpty() && held.isEmpty() && crowd.isEmpty()) return

    val coins = crowd.map { it.mint }.distinct()
    val people = crowd.map { it.wallet }.distinct().size
    // Morning or not, the window is the same twelve hours. Only the name changes,
    // because calling six in the evening "the night" would be a small lie.
    val hour = remember(refresh) { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val title = if (hour in 4..12) R.string.night_title else R.string.night_title_recent

    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HaloIcon(HIcon.HOURGLASS, Halo.cyan, 15.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(title).uppercase(), style = HaloType.label, color = Halo.cyan)
            }
            Text(
                stringResource(
                    when {
                        // "Mentre dormivi" alle due del pomeriggio e' una frase
                        // che non regge: la finestra e' la stessa dodici ore, ma
                        // il nome cambia con l'ora e cosi' deve fare il verdetto.
                        moves.isNotEmpty() && hour in 4..12 -> R.string.night_worked
                        moves.isNotEmpty() -> R.string.night_worked_recent
                        coins.isNotEmpty() -> R.string.night_still
                        else -> R.string.night_quiet
                    },
                ),
                fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.ink, lineHeight = 18.sp,
            )

            NightRow(stringResource(R.string.night_agent)) {
                if (moves.isEmpty()) {
                    Text(stringResource(R.string.night_agent_none), style = HaloType.small, color = Halo.muted)
                } else {
                    moves.take(3).forEach { e ->
                        val out = e.outflows.firstOrNull()
                        val inn = e.inflows.firstOrNull()
                        Text(
                            when {
                                out != null && inn != null -> fmtUi(kotlin.math.abs(out.uiAmount)) + " " + out.symbol + " → " + inn.symbol
                                out != null -> "−" + fmtUi(kotlin.math.abs(out.uiAmount)) + " " + out.symbol
                                inn != null -> "+" + fmtUi(kotlin.math.abs(inn.uiAmount)) + " " + inn.symbol
                                else -> e.dApp
                            },
                            style = HaloType.small, color = Halo.ink, maxLines = 1,
                        )
                    }
                }
                if (held.isNotEmpty()) Text(stringResource(R.string.night_holding, held.size), style = HaloType.small, color = Halo.muted)
            }

            if (SeekerFeed.available) NightRow(stringResource(R.string.night_crowd)) {
                if (coins.isEmpty()) {
                    Text(stringResource(R.string.night_crowd_none), style = HaloType.small, color = Halo.muted)
                } else {
                    Text(stringResource(R.string.night_crowd_line, people, coins.size), style = HaloType.small, color = Halo.ink)
                    // The three that most different people bought, which is the only
                    // count that means a crowd: one wallet buying nine times is one
                    // person changing their mind.
                    crowd.groupBy { it.mint }
                        .map { (_, b) -> b.first().symbol to b.map { it.wallet }.distinct().size }
                        .sortedByDescending { it.second }.take(3)
                        .forEach { (sym, n) ->
                            Text("$sym · $n", fontFamily = Mono, fontSize = 11.sp, color = Halo.muted, style = Tabular)
                        }
                }
            }

            policy?.let { p ->
                val left = (p.dailyLamports - spent).coerceAtLeast(0L)
                NightRow(stringResource(R.string.night_cap)) {
                    Text(
                        if (left == 0L) stringResource(R.string.night_cap_spent)
                        else stringResource(R.string.night_cap_left, fmtSol(left, 4), fmtSol(p.dailyLamports, 4)),
                        style = HaloType.small, color = if (left == 0L) Halo.amber else Halo.ink,
                    )
                }
            }
        }
    }
}

/** A label on the left, the lines it introduces on the right. */
@Composable
private fun NightRow(label: String, content: @Composable ColumnScope.() -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, style = HaloType.label, color = Halo.muted, modifier = Modifier.width(74.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp), content = content)
    }
}
