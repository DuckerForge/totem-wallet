@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Talking to the wallet in words.
 *
 * Every tool the model runs leaves a visible receipt in the transcript — what it
 * tried, and what Apex decided — so the conversation is also an audit trail. A
 * "signed on its own" line here matches a row in the ledger; nothing happens
 * that this screen hides.
 */
@Composable
internal fun ChatScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Read back from disk, so closing the sheet is not the same as burning the
    // transcript. See [ChatHistory] for why it is a local file and not a server.
    val turns = remember { mutableStateListOf<Brain.Turn>().apply { addAll(ChatHistory.load(ctx)) } }
    var draft by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    var showBrain by remember { mutableStateOf(false) }
    var configured by remember { mutableStateOf(Brain.configured(ctx)) }

    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1) }

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
            when (val r = Brain.ask(ctx, ChatHistory.context(turns.toList()), "Apex chat")) {
                is Brain.Reply.Ok -> turns += r.turns
                is Brain.Reply.Failed -> error = r.message
            }
            thinking = false
            ChatHistory.save(ctx, turns.toList())
        }
    }

    // The same shape Send uses, because it is the one that demonstrably works on
    // this phone: a full-height sheet. A plain composable had no height to fill,
    // and inside a Dialog the window insets never arrived — measured on device,
    // the field and the Send button both ended exactly on the screen edge.
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Halo.ground,
        contentColor = Halo.ink,
        dragHandle = null,
    ) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.96f).imePadding()) {
        // `GhostButton` forces `fillMaxWidth` on itself, so a Close button in a
        // header row eats the whole row and squeezes the title to nothing. The
        // shared header has a small round close instead.
        SheetHeader(stringResource(R.string.chat_title), stringResource(R.string.chat_sub), HIcon.PIGEON, onClose = onClose)
        if (turns.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                SmallChip(stringResource(R.string.chat_clear), null) {
                    turns.clear(); error = null; ChatHistory.clear(ctx)
                }
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (turns.isEmpty()) {
                item {
                    GlassCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(if (configured) R.string.chat_empty else R.string.chat_no_key),
                                fontFamily = Inter, fontSize = 13.sp, color = Halo.muted, lineHeight = 19.sp,
                            )
                            if (configured) {
                                // Openers covering what people actually ask a wallet
                                // assistant. The first one is the one that changes what
                                // the app is, so it leads. Each maps to a tool that
                                // exists, so none of them is a dead end.
                                openers().forEach { ex ->
                                    val t = stringResource(ex)
                                    // A ready-made question is a question, not a draft:
                                    // tapping it asks it.
                                    SmallChip(t, null) { send(t) }
                                }
                            } else {
                                PrimaryButton(stringResource(R.string.brain_missing), danger = false, icon = HIcon.KEY) { showBrain = true }
                            }
                        }
                    }
                }
            }
            // Only the newest answer types itself out; scrolling back through old
            // ones should not replay them.
            itemsIndexed(turns) { i, t -> TurnRow(t, live = i == turns.lastIndex) }
            if (thinking) item { TypingDots() }
            error?.let { e -> item { Banner(e, Halo.red, HIcon.WARNING) } }
        }

        // The openers used to exist only on the empty screen, so the moment you
        // asked one thing every suggestion disappeared for good. They come back
        // whenever the field is empty, which is exactly when you would want one.
        if (turns.isNotEmpty() && draft.isBlank()) {
            // When the agent has just asked which lane to trade, the two answers
            // are the only useful suggestions there are.
            val asked = turns.lastOrNull { it.role == "assistant" }?.text?.contains("degen", true) == true
            val chips = if (asked) listOf(R.string.chat_lane_careful, R.string.chat_lane_bold) else openers()
            Box(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    chips.forEach { ex ->
                        val t = stringResource(ex)
                        SmallChip(t, null) { send(t) }
                    }
                }
                // A chip cut off by the screen edge looks broken; the same chip
                // fading out looks like a row that continues.
                //
                // `matchParentSize` and not `fillMaxHeight`: inside a Box that
                // wraps its content, fillMaxHeight takes the *incoming* max
                // height, which here is the whole rest of the screen. That is
                // what flattened the conversation and pushed the composer off the
                // bottom. matchParentSize takes the parent's resolved size and
                // never contributes to measuring it.
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
            // A fixed height: with the field free to grow, the row kept ending up
            // taller than the space the column had left and got cut by the screen.
            Modifier.fillMaxWidth().height(86.dp).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                modifier = Modifier.weight(1f).fillMaxHeight(),
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
            // Was a labelled button sharing the row with the field, which left it
            // whatever width remained and clipped the word off the screen edge.
            // A round one has a width of its own that nothing can squeeze.
            SendButton(enabled = draft.isNotBlank() && !thinking) { send() }
        }
    }

    }

    if (showBrain) BrainSheet { showBrain = false; configured = Brain.configured(ctx) }
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

/**
 * The openers, shared by the empty screen and the row above the composer.
 *
 * The first one flips with the state: while the loop is running, "start working
 * on your own" is the one suggestion that cannot do anything, and the one you
 * actually want is the opposite.
 */
@Composable
private fun openers(): List<Int> {
    val ctx = LocalContext.current
    val running = remember { TraderLoop.config(ctx).on }
    return listOf(
        if (running) R.string.chat_ex9 else R.string.chat_ex7,
        R.string.chat_ex3, R.string.chat_ex8,
        R.string.chat_ex1, R.string.chat_ex2, R.string.chat_ex4,
        R.string.chat_ex5, R.string.chat_ex6,
    )
}

@Composable
private fun TurnRow(t: Brain.Turn, live: Boolean = false) {
    when (t.role) {
        "user" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(Modifier.fillMaxWidth(0.85f).clip(rs(16)).background(Halo.mint.copy(alpha = 0.14f)).padding(12.dp)) {
                Text(t.text, fontFamily = Inter, fontSize = 13.5.sp, color = Halo.ink, lineHeight = 19.sp)
            }
        }
        "assistant" -> Box(Modifier.fillMaxWidth(0.92f).clip(rs(16)).background(Halo.cardSoft).border(1.dp, Halo.stroke, rs(16)).padding(12.dp)) {
            Typewriter(t.text, live = live)
        }
        "tool" -> ToolRow(t)
    }
}

/** The receipt of one tool call: what it was, and what the judge said. */
@Composable
private fun ToolRow(t: Brain.Turn) {
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
    val detail = json?.optString("reason")?.takeIf { it.isNotBlank() && it != "null" }
        ?: json?.optString("simulated")?.takeIf { it.isNotBlank() && it != "null" }
        ?: json?.optString("error")?.takeIf { it.isNotBlank() && it != "null" }
    Row(
        Modifier.fillMaxWidth().clip(rs(12)).background(tint.copy(alpha = 0.08f)).border(1.dp, tint.copy(alpha = 0.35f), rs(12)).padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        HaloIcon(
            when (decision) {
                "signed_silently", "confirmed_by_user" -> HIcon.CHECK
                "refused", "timeout" -> HIcon.BLOCK
                else -> HIcon.INFO
            },
            tint, 14.dp,
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = tint)
            detail?.let { Text(it, fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted, lineHeight = 16.sp) }
            json?.optString("signature")?.takeIf { it.isNotBlank() && it != "null" }?.let { sig ->
                Text(shorten(sig, 8), fontFamily = Mono, fontSize = 10.5.sp, color = Halo.muted)
            }
        }
    }
}

/**
 * The answer arrives as it is written.
 *
 * Not decoration: a model that has gone off to read prices and quote a swap can
 * take seconds, and text that lands in one block gives no sense of that. It runs
 * once, on the newest message only, and any recomposition finds it already done.
 */
@Composable
private fun Typewriter(text: String, live: Boolean) {
    var shown by remember(text) { mutableStateOf(if (live) 0 else text.length) }
    LaunchedEffect(text, live) {
        if (!live) { shown = text.length; return@LaunchedEffect }
        val step = (text.length / 90).coerceAtLeast(1)
        while (shown < text.length) {
            shown = (shown + step).coerceAtMost(text.length)
            kotlinx.coroutines.delay(16)
        }
    }
    Text(text.take(shown), style = HaloType.small, color = Halo.ink)
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
