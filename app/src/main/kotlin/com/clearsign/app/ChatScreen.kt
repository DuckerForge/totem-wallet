package com.clearsign.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clearsign.core.AgentMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The conversation with the agent, as a page of its own.
 *
 * A page and not a sheet: it sits over everything, tab bar included, because it
 * is somewhere you go rather than something you peek at. The sheet it replaces
 * ended at 96% of the screen with a keyboard that could push the composer off
 * the bottom; a page owns its insets.
 *
 * Three things the old chat did not do. The answer **streams**: the first word
 * lands in a second, not the whole paragraph after ten. A proposal comes back
 * as a **receipt card** with its verdict in colour, not a line of JSON. And a
 * strip at the top says what the agent is doing and what it holds, so you never
 * leave the chat to find out whether the thing you are talking to is alive.
 */
@Composable
internal fun ChatScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Read back from disk, so closing the page is not the same as burning the
    // transcript. See [ChatHistory] for why it is a local file and not a server.
    val turns = remember { mutableStateListOf<Brain.Turn>().apply { addAll(ChatHistory.load(ctx)) } }
    var draft by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    /** The answer as it is being written. Null between answers. */
    var live by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var traceOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var showBrain by remember { mutableStateOf(false) }
    var configured by remember { mutableStateOf(Brain.configured(ctx)) }

    // Follow the newest line, whether it is a finished turn or one still arriving.
    LaunchedEffect(turns.size, live?.length) {
        val n = turns.size + (if (live != null || thinking) 1 else 0)
        if (n > 0) listState.animateScrollToItem(n)
    }

    fun send(preset: String? = null) {
        val text = (preset ?: draft).trim()
        if (text.isEmpty() || thinking) return
        draft = ""; error = null
        turns += Brain.Turn("user", text)
        ChatHistory.save(ctx, turns.toList())
        thinking = true
        scope.launch {
            // Only the recent turns go to the model. The whole transcript stays
            // on screen, but re-sending an afternoon of it on every message is
            // how a free tier's per-minute allowance disappears into nothing.
            val r = Brain.ask(ctx, ChatHistory.context(turns.toList()), "Apex chat") { partial -> live = partial }
            live = null
            when (r) {
                is Brain.Reply.Ok -> turns += r.turns
                is Brain.Reply.Failed -> error = r.message
            }
            thinking = false
            ChatHistory.save(ctx, turns.toList())
        }
    }

    Column(Modifier.fillMaxSize().background(Halo.ground).statusBarsPadding().imePadding()) {
        // The same bar Scout has: a way back, a name, and one small action.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(rs(12)).background(Halo.cardSoft).clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) { HaloIcon(HIcon.CHEVRON_LEFT, Halo.ink, 20.dp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.chat_title), style = HaloType.screen, color = Halo.ink)
                Text(stringResource(R.string.chat_sub), style = HaloType.small, color = Halo.muted)
            }
            if (turns.isNotEmpty()) {
                SmallChip(stringResource(R.string.chat_clear), null, tint = Halo.muted) {
                    turns.clear(); error = null; ChatHistory.clear(ctx)
                }
            }
        }

        StatusStrip(open = traceOpen) { traceOpen = !traceOpen }
        if (traceOpen) AgentConsole()

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (turns.isEmpty()) {
                item {
                    GlassCard {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                stringResource(if (configured) R.string.chat_empty else R.string.chat_no_key),
                                fontFamily = Inter, fontSize = 13.sp, color = Halo.muted, lineHeight = 19.sp,
                            )
                            if (configured) {
                                // The same groups as the row above the composer, laid
                                // out in full because there is room for them here.
                                openerGroups().forEach { (label, tint, items) ->
                                    Text(stringResource(label).uppercase(), style = HaloType.label, color = tint)
                                    items.forEach { ex -> val t = stringResource(ex); SmallChip(t, null, tint = tint) { send(t) } }
                                }
                            } else {
                                PrimaryButton(stringResource(R.string.brain_missing), danger = false, icon = HIcon.KEY) { showBrain = true }
                            }
                        }
                    }
                }
            }
            itemsIndexed(turns) { _, t -> TurnRow(t) }
            // The answer, as it arrives. The dots only while nothing has arrived yet.
            live?.let { l -> item { AssistantBubble(l, live = true) } }
            if (thinking && live == null) item { TypingDots() }
            error?.let { e -> item { Banner(e, Halo.red, HIcon.WARNING) } }
        }

        // Suggestions come back whenever the field is empty, which is exactly
        // when you would want one. Grouped, because "what do I look at" and
        // "what do I do" are different questions, and nine chips in a row were
        // nine things to read before finding the one you meant.
        if (turns.isNotEmpty() && draft.isBlank()) {
            val asked = false
            Box(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (asked) {
                        // When the agent has just asked which lane to trade, the two
                        // answers are the only useful suggestions there are.
                        listOf(R.string.chat_lane_careful, R.string.chat_lane_bold).forEach { ex ->
                            val t = stringResource(ex); SmallChip(t, null, tint = Halo.mint) { send(t) }
                        }
                    } else {
                        openerGroups().forEach { (label, tint, items) ->
                            Text(stringResource(label).uppercase(), style = HaloType.label, color = tint, modifier = Modifier.padding(start = 4.dp, end = 2.dp))
                            items.forEach { ex -> val t = stringResource(ex); SmallChip(t, null, tint = tint) { send(t) } }
                        }
                    }
                }
                // A chip cut off by the screen edge looks broken; the same chip
                // fading out looks like a row that continues.
                if (!asked) {
                    Box(
                        Modifier.matchParentSize().background(
                            Brush.horizontalGradient(0.86f to Halo.ground.copy(alpha = 0f), 1f to Halo.ground),
                        ),
                    )
                }
            }
        }

        Row(
            // The row is as tall as the field wants to be, bounded. Forcing 86dp
            // and stretching the field to fill it put the text box taller than the
            // line it draws, and the placeholder was sliced in half along the
            // bottom edge. Let the field measure itself; cap the growth instead.
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp, max = 120.dp),
                placeholder = { Text(stringResource(R.string.chat_hint), fontFamily = Inter, fontSize = 13.sp, color = Halo.muted) },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Inter, fontSize = 14.sp, color = Halo.ink),
                maxLines = 2,
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Halo.mint, unfocusedBorderColor = Halo.stroke,
                    focusedContainerColor = Halo.cardSoft, unfocusedContainerColor = Halo.cardSoft,
                    cursorColor = Halo.mint,
                ),
                shape = rs(14),
            )
            SendButton(enabled = draft.isNotBlank() && !thinking) { send() }
        }
    }

    if (showBrain) BrainSheet { showBrain = false; configured = Brain.configured(ctx) }
}

/**
 * One line that answers "is this thing alive" without leaving the chat: the
 * loop's state with its breathing dot, what the budget can still spend, how
 * many coins it holds. Tap to open the live trace underneath.
 */
@Composable
private fun StatusStrip(open: Boolean, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    var beat by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(3_000); beat++ } }
    val cfg = remember(beat) { TraderLoop.config(ctx) }
    val session = remember(beat) { SessionWallet.current(ctx) }
    val mode = remember(beat) { SessionWallet.policy(ctx)?.mode }
    val openCount = remember(beat) { Positions.open(ctx).size }
    var free by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(session?.pubkey, beat / 10) {
        val pub = session?.pubkey ?: return@LaunchedEffect
        free = withContext(Dispatchers.IO) { runCatching { SolanaRpc.getBalance(SolanaRpc.urlFor(null), pub) }.getOrNull() }
    }

    val paused = mode == AgentMode.OFF || mode == AgentMode.READ_ONLY
    val (tint, label) = when {
        session == null -> Halo.muted to stringResource(R.string.env_none_short)
        paused -> Halo.amber to stringResource(R.string.pulse_paused)
        cfg.on && openCount > 0 -> Halo.mint to stringResource(R.string.pulse_holding, openCount)
        cfg.on -> Halo.mint to stringResource(R.string.pulse_looking)
        else -> Halo.muted to stringResource(R.string.pulse_idle)
    }
    val alpha = if (cfg.on && !paused) {
        rememberInfiniteTransition(label = "strip").animateFloat(0.35f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "a").value
    } else 1f

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(rs(Radius.row)).background(tint.copy(alpha = 0.08f))
            .border(1.dp, tint.copy(alpha = 0.28f), rs(Radius.row)).clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(8.dp)) { drawCircle(tint.copy(alpha = alpha)) }
        Spacer(Modifier.width(8.dp))
        Text(label, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = tint, modifier = Modifier.weight(1f))
        if (session != null) {
            Text(
                stringResource(R.string.chat_status_free, free?.let { fmtSol(it, 3) } ?: "…") +
                    if (openCount > 0) " · " + stringResource(R.string.chat_status_positions, openCount) else "",
                fontFamily = Mono, fontSize = 11.sp, color = Halo.muted, style = Tabular,
            )
            Spacer(Modifier.width(6.dp))
        }
        HaloIcon(HIcon.CHEVRON_DOWN, Halo.muted, 13.dp, Modifier.rotate(if (open) 180f else 0f))
    }
}

/**
 * What the agent is doing, line by line, while it works. Shared with the Agent
 * tab's Pro view: the same six lines in both places, because "is it alive" is
 * asked from both.
 */
@Composable
internal fun AgentConsole() {
    val ctx = LocalContext.current
    val on = remember { TraderLoop.config(ctx).on }
    val lines = AgentTrace.lines
    val busy by AgentTrace.busy

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(rs(12)).background(Halo.ground2).border(cardBorder(), rs(12))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HaloIcon(HIcon.AGENT, if (busy) Halo.mint else Halo.muted, 13.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.live_title).uppercase(),
                fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 9.5.sp,
                color = if (busy) Halo.mint else Halo.muted,
            )
        }
        if (lines.isEmpty()) {
            Text(stringResource(if (on) R.string.trace_idle else R.string.live_off), fontFamily = Mono, fontSize = 11.sp, color = Halo.muted)
        }
        // The last six, oldest at the top, so the newest line is the one nearest
        // the caret and the eye lands on it.
        val shown = lines.takeLast(6)
        shown.forEachIndexed { i, line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "· " + line.text,
                    fontFamily = Mono, fontSize = 11.sp, maxLines = 2,
                    color = when (line.kind) {
                        AgentTrace.Kind.ACTED -> Halo.mint
                        AgentTrace.Kind.REFUSED -> Halo.amber
                        AgentTrace.Kind.FOUND -> Halo.ink
                        else -> Halo.muted
                    },
                )
                if (i == shown.lastIndex && busy) { Spacer(Modifier.width(4.dp)); BlinkCaret(Halo.mint, 11.dp, 6.dp) }
            }
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(
        Modifier.pressScale(src).size(54.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(Brush.linearGradient(listOf(Halo.mint, Halo.cyan)), alpha = if (enabled) 1f else 0.35f)
            .clickable(interactionSource = src, indication = null, enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        HaloIcon(HIcon.SEND, Halo.ground, 22.dp)
    }
}

/** A group of suggestions: its label, its colour, and the questions in it. */
private data class OpenerGroup(val label: Int, val tint: Color, val items: List<Int>)

/**
 * The openers, in three groups: what to look at, what to do, and the agent
 * itself. The last one flips with the state: while the loop is running, "start
 * working on your own" is the one suggestion that cannot do anything.
 */
@Composable
private fun openerGroups(): List<OpenerGroup> {
    val ctx = LocalContext.current
    val running = remember { TraderLoop.config(ctx).on }
    return listOf(
        OpenerGroup(R.string.chat_group_agent, Halo.amber, listOf(if (running) R.string.chat_ex9 else R.string.chat_ex7, R.string.chat_ex8)),
        OpenerGroup(R.string.chat_group_look, Halo.cyan, listOf(R.string.chat_ex3, R.string.chat_ex1, R.string.chat_ex2, R.string.chat_ex5)),
        OpenerGroup(R.string.chat_group_do, Halo.mint, listOf(R.string.chat_ex4, R.string.chat_ex6)),
    )
}

@Composable
private fun TurnRow(t: Brain.Turn) {
    when (t.role) {
        // The bubble is as wide as the words, up to a limit. `fillMaxWidth(0.85f)`
        // took 85% of the screen for "vendi tutto" too, so five short messages
        // read as five identical slabs and the conversation lost its shape.
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier.widthIn(max = 300.dp).clip(rs(16)).background(Halo.mint.copy(alpha = 0.14f))
                    .padding(horizontal = 13.dp, vertical = 9.dp),
            ) {
                Text(t.text, fontFamily = Inter, fontSize = 13.5.sp, color = Halo.ink, lineHeight = 19.sp)
            }
        }
        "assistant" -> AssistantBubble(t.text, live = false)
        // A receipt is for a decision about money. Everything else a tool did is
        // one quiet line: "Ho eseguito positions" was arriving as a full card with
        // a border, as loud as a signature, for having read a list.
        "tool" -> if (t.verdict in SIGNED_VERDICTS) ReceiptCard(t) else ToolLine(t)
    }
}

/** The four answers that mean a signature was, or was not, given. */
private val SIGNED_VERDICTS = setOf("signed_silently", "confirmed_by_user", "refused", "timeout")

@Composable
private fun AssistantBubble(text: String, live: Boolean) {
    Box(Modifier.fillMaxWidth(0.92f).clip(rs(16)).background(Halo.cardSoft).border(cardBorder(), rs(16)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(lite(text), style = HaloType.small, color = Halo.ink, modifier = Modifier.weight(1f, fill = false))
            if (live) { Spacer(Modifier.width(3.dp)); BlinkCaret(Halo.mint, 13.dp, 7.dp) }
        }
    }
}

/**
 * A proposal and what became of it, as a receipt.
 *
 * The three things a person wants from it, in this order: did it happen, what
 * was it, why not. The verdict is the colour, the claim is the title, the
 * judge's reason is the line under it. The signature is there for whoever wants
 * to go and look.
 */
@Composable
private fun ReceiptCard(t: Brain.Turn) {
    val ctx = LocalContext.current
    val json = remember(t.text) { runCatching { JSONObject(t.text) }.getOrNull() }
    val decision = t.verdict
    val tint = when (decision) {
        "signed_silently", "confirmed_by_user" -> Halo.mint
        "refused", "timeout" -> Halo.red
        else -> Halo.cyan
    }
    val label = when (decision) {
        "signed_silently" -> ctx.getString(R.string.chat_signed)
        "confirmed_by_user" -> ctx.getString(R.string.chat_confirmed)
        "refused" -> ctx.getString(R.string.chat_refused)
        "timeout" -> ctx.getString(R.string.chat_expired)
        else -> ctx.getString(R.string.chat_checked, t.tool ?: "?")
    }
    fun field(k: String) = json?.optString(k)?.takeIf { it.isNotBlank() && it != "null" }
    val said = field("said")
    val simulated = field("simulated")
    val reason = field("reason") ?: field("error")
    val sig = field("signature")

    Column(
        Modifier.fillMaxWidth().clip(rs(14)).background(tint.copy(alpha = 0.07f)).border(1.dp, tint.copy(alpha = 0.4f), rs(14)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HaloIcon(
                when (decision) { "signed_silently", "confirmed_by_user" -> HIcon.CHECK; "refused", "timeout" -> HIcon.BLOCK; else -> HIcon.INFO },
                tint, 15.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text(label, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = tint, modifier = Modifier.weight(1f))
            sig?.let { Text(shorten(it, 6), fontFamily = Mono, fontSize = 10.sp, color = Halo.muted) }
        }
        (said ?: simulated)?.let { Text(it, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.ink, lineHeight = 18.sp) }
        // What the network says will happen, when it differs from the claim: the
        // judge judges this, and a person should be able to judge the judge.
        if (said != null && simulated != null && simulated != said) {
            Text(simulated, fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted, lineHeight = 16.sp)
        }
        reason?.let { Text(it, fontFamily = Inter, fontSize = 12.sp, color = if (tint == Halo.red) Halo.red else Halo.muted, lineHeight = 17.sp) }
    }
}

/**
 * A tool that only read something: one quiet line, and the raw answer behind a
 * tap for whoever wants to see what the model saw.
 */
@Composable
private fun ToolLine(t: Brain.Turn) {
    var open by remember(t.text) { mutableStateOf(false) }
    val err = remember(t.text) { runCatching { JSONObject(t.text).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" } }
    Column(
        Modifier.fillMaxWidth().clip(rs(10)).background(Halo.cardSoft.copy(alpha = 0.6f)).clickable { open = !open }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HaloIcon(if (err != null) HIcon.WARNING else HIcon.SEARCH, if (err != null) Halo.amber else Halo.muted, 12.dp)
            Spacer(Modifier.width(7.dp))
            Text(
                err ?: stringResource(R.string.chat_tool_line, t.tool ?: "?"),
                fontFamily = Inter, fontSize = 11.5.sp, color = if (err != null) Halo.amber else Halo.muted, modifier = Modifier.weight(1f), maxLines = 2,
            )
            HaloIcon(HIcon.CHEVRON_DOWN, Halo.muted, 11.dp, Modifier.rotate(if (open) 180f else 0f))
        }
        if (open) Text(t.text, fontFamily = Mono, fontSize = 10.5.sp, color = Halo.muted, lineHeight = 14.sp, maxLines = 24)
    }
}

/**
 * The little markdown a model writes anyway, rendered instead of printed.
 *
 * The prompt asks for none, and every model produces some: `**Borsello di
 * spesa**` arrived on screen with the asterisks in it. Two marks and no more,
 * because those are the two that turn up: `**bold**`, and a leading `- ` that
 * becomes a real bullet. Anything else is left exactly as written, which is the
 * safe half of the bargain.
 */
private fun lite(text: String): androidx.compose.ui.text.AnnotatedString = androidx.compose.ui.text.buildAnnotatedString {
    val clean = text.replace(Regex("(?m)^\\s*[-*]\\s+"), "· ")
    var i = 0
    while (i < clean.length) {
        val open = clean.indexOf("**", i)
        if (open < 0) { append(clean.substring(i)); break }
        val close = clean.indexOf("**", open + 2)
        if (close < 0) { append(clean.substring(i)); break }
        append(clean.substring(i, open))
        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
        append(clean.substring(open + 2, close))
        pop()
        i = close + 2
    }
}

/** Three dots, while it is away reading the market. */
@Composable
private fun TypingDots() {
    val t = rememberInfiniteTransition(label = "typing")
    Row(
        Modifier.clip(rs(Radius.panel)).background(Halo.cardSoft).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val a by t.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(520, delayMillis = i * 160), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(7.dp).clip(rs(Radius.pill)).background(Halo.mint.copy(alpha = a)))
        }
    }
}
