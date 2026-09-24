@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas

/* The eight things you can start from the wallet home. */
internal enum class HomeAction { SEND, RECEIVE, SWAP, SCAN, CROWD, TAP, LINK, AGENT, BRIDGE, MORE }

/*
 * The action grid: eight round, neutral buttons. The three tinted tiles this replaces implied
 * a hierarchy between Swap, Send and Receive that does not exist, and eight accents would
 * turn the accent into decoration. Color comes from the balance and the logos; the buttons
 * are a dark circle, a hand-drawn line icon, a label.
 */
/** Every circle the home can show: its icon and its name. "More" stays out of the list: it is always last. */
internal fun homeActionIcon(a: HomeAction): HIcon = when (a) {
    HomeAction.SEND -> HIcon.SEND; HomeAction.RECEIVE -> HIcon.RECEIVE; HomeAction.SWAP -> HIcon.SWAP; HomeAction.SCAN -> HIcon.SCAN
    HomeAction.CROWD -> HIcon.NFC; HomeAction.TAP -> HIcon.NFC; HomeAction.LINK -> HIcon.SHARE; HomeAction.AGENT -> HIcon.AGENT
    HomeAction.BRIDGE -> HIcon.BRIDGE; HomeAction.MORE -> HIcon.MORE
}
internal fun homeActionLabel(a: HomeAction): Int = when (a) {
    HomeAction.SEND -> R.string.send_btn; HomeAction.RECEIVE -> R.string.receive_btn; HomeAction.SWAP -> R.string.swap_btn; HomeAction.SCAN -> R.string.send_scan
    HomeAction.CROWD -> R.string.home_act_crowd; HomeAction.TAP -> R.string.home_act_tap; HomeAction.LINK -> R.string.home_act_link
    HomeAction.AGENT -> R.string.home_act_agent; HomeAction.BRIDGE -> R.string.bridge_short; HomeAction.MORE -> R.string.home_act_more
}
internal val CHOOSABLE_ACTIONS = listOf(HomeAction.SEND, HomeAction.RECEIVE, HomeAction.SWAP, HomeAction.SCAN, HomeAction.CROWD, HomeAction.TAP, HomeAction.LINK, HomeAction.AGENT, HomeAction.BRIDGE)

/** The chosen circles, in order, with More at the end. Rows of four, the last row padded so nothing stretches. */
@Composable
internal fun HomeActions(enabled: Boolean, onAction: (HomeAction) -> Unit) {
    val ctx = LocalContext.current
    val tick by Settings.homeActionsTick
    val chosen = remember(tick) {
        Settings.homeActions(ctx).mapNotNull { n -> runCatching { HomeAction.valueOf(n) }.getOrNull() }
            .filter { it != HomeAction.MORE && (it != HomeAction.BRIDGE || RocketX.enabled) } + HomeAction.MORE
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        chosen.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                row.forEach { action ->
                    ActionButton(homeActionIcon(action), stringResource(homeActionLabel(action)), enabled, Modifier.weight(1f)) { onAction(action) }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Which circles, and in what order. Switch one on or off, move it up or down. */
@Composable
internal fun HomeActionsSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var chosen by remember { mutableStateOf(Settings.homeActions(ctx).mapNotNull { n -> runCatching { HomeAction.valueOf(n) }.getOrNull() }.filter { it != HomeAction.MORE }) }
    fun save(list: List<HomeAction>) { chosen = list; Settings.setHomeActions(ctx, list.map { it.name }) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SheetHeader(stringResource(R.string.home_customize), stringResource(R.string.home_customize_sub), HIcon.SETTINGS, onClose = onDismiss)
            // The home itself, above the switches. Every change lands here
            // first, so nobody has to close the sheet to see what they did.
            Text(stringResource(R.string.home_customize_preview).uppercase(), style = HaloType.label, color = Halo.muted)
            Column(
                Modifier.fillMaxWidth().clip(rs(18)).background(Halo.ground).padding(horizontal = 14.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(Space.lg),
            ) {
                val shown = chosen.filter { it != HomeAction.BRIDGE || RocketX.enabled } + HomeAction.MORE
                shown.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        row.forEach { a -> ActionButton(homeActionIcon(a), stringResource(homeActionLabel(a)), true, Modifier.weight(1f)) {} }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            val all = chosen + CHOOSABLE_ACTIONS.filter { it !in chosen }
            all.forEach { a ->
                val on = a in chosen
                val idx = chosen.indexOf(a)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    HaloIcon(homeActionIcon(a), if (on) Halo.ink else Halo.muted, 20.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(homeActionLabel(a)), style = HaloType.body, color = if (on) Halo.ink else Halo.muted, modifier = Modifier.weight(1f))
                    if (on) {
                        SmallChip("▲", null, tint = if (idx > 0) Halo.cyan else Halo.stroke) { if (idx > 0) save(chosen.toMutableList().also { it.add(idx - 1, it.removeAt(idx)) }) }
                        Spacer(Modifier.width(6.dp))
                        SmallChip("▼", null, tint = if (idx < chosen.lastIndex) Halo.cyan else Halo.stroke) { if (idx < chosen.lastIndex) save(chosen.toMutableList().also { it.add(idx + 1, it.removeAt(idx)) }) }
                        Spacer(Modifier.width(6.dp))
                    }
                    androidx.compose.material3.Switch(checked = on, onCheckedChange = { want -> save(if (want) chosen + a else chosen - a) })
                }
            }
            Text(stringResource(R.string.home_customize_note), style = HaloType.small, color = Halo.muted, lineHeight = 15.sp)
            GhostButton(stringResource(R.string.home_customize_reset), Modifier.fillMaxWidth(), HIcon.HISTORY, tint = Halo.muted) {
                save(Settings.DEFAULT_HOME_ACTIONS.mapNotNull { n -> runCatching { HomeAction.valueOf(n) }.getOrNull() })
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** The wallet chip, opened: the address, and the three things people look for there. */
@Composable
internal fun WalletChipSheet(address: String, onSettings: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(address, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.chip_title), style = HaloType.title, color = Halo.ink)
                    Text(address, fontFamily = Mono, fontSize = 11.sp, color = Halo.muted, lineHeight = 15.sp)
                }
            }
            MoreRow(HIcon.COPY, stringResource(R.string.copy_address)) { copyText(ctx, address); Haptics.tick(ctx); onDismiss() }
            MoreRow(HIcon.SHARE, stringResource(R.string.share)) {
                val i = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, address)
                Door.hold(); runCatching { ctx.startActivity(android.content.Intent.createChooser(i, null)) }; onDismiss()
            }
            MoreRow(HIcon.EXTERNAL, "Solscan") { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://solscan.io/account/$address"))) } }
            MoreRow(HIcon.SETTINGS, stringResource(R.string.tab_settings)) { onSettings(); onDismiss() }
            Text(stringResource(R.string.chip_info), style = HaloType.small, color = Halo.muted, lineHeight = 15.sp)
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ActionButton(icon: HIcon, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val tint = if (enabled) Halo.ink else Halo.muted
    Column(
        modifier
            .clip(rs(Radius.panel))
            .then(if (enabled) Modifier.clickable(interactionSource = src, indication = null, onClick = onClick) else Modifier)
            .pressScale(src, 0.93f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        // The circle answers the finger, like every other surface: it used to only shrink,
        // and a shrink alone reads as the page moving rather than the button taking the tap.
        val pressed by src.collectIsPressedAsState()
        val glow = animateFloatAsState(if (pressed) 1f else 0f, tween(120), label = "tap")
        val lit = Halo.mint
        Box(
            // The same living hairline as the cards, so a theme that moves moves
            // everywhere rather than in one place.
            Modifier.size(58.dp).clip(rs(Radius.pill)).background(Halo.cardSoft)
                .drawWithContent { drawContent(); if (glow.value > 0f) drawRect(lit, alpha = 0.14f * glow.value) }
                .haloBorder(rs(Radius.pill), living = false),
            contentAlignment = Alignment.Center,
        ) {
            // This button wears the Seeker itself rather than a generic glyph: it
            // is our own drawing, and the thing behind it is the crowd of people
            // holding that exact object.
            if (icon == HIcon.NFC) CrowdGlyph(tint) else HaloIcon(icon, tint, 23.dp)
        }
        Text(label, style = HaloType.label, color = tint, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/**
 * The scout's lens. It had a phone inside, four ticks and a handle, at twenty-six dp: all
 * true, none legible, past a point an icon becomes a smudge. A ring, a line rising in it, a
 * handle: looking at a market in three strokes.
 */
@Composable
private fun CrowdGlyph(tint: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(26.dp)) {
        val r = size.minDimension * 0.355f
        val cx = size.width * 0.42f
        val cy = size.height * 0.40f
        val c = androidx.compose.ui.geometry.Offset(cx, cy)

        // The glass, faintly filled so the ring reads as a lens and not as a circle.
        drawCircle(tint.copy(alpha = 0.10f), r, c)
        drawCircle(tint, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.9f * density))

        // What is inside the glass: a line going somewhere. Clipped to the lens, so
        // it belongs to the glass rather than sitting on top of it.
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx - r * 0.58f, cy + r * 0.34f)
            lineTo(cx - r * 0.12f, cy - r * 0.16f)
            lineTo(cx + r * 0.20f, cy + r * 0.12f)
            lineTo(cx + r * 0.60f, cy - r * 0.46f)
        }
        clipPath(androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r - 1f * density)) }) {
            drawPath(
                path, tint,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 1.8f * density,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
        }

        // The handle, thicker than the rim, which is what makes it read as held.
        drawLine(
            tint,
            androidx.compose.ui.geometry.Offset(cx + r * 0.70f, cy + r * 0.70f),
            androidx.compose.ui.geometry.Offset(cx + r * 0.70f + r * 0.80f, cy + r * 0.70f + r * 0.80f),
            strokeWidth = 2.4f * density,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/** The phone from the tap screen, shrunk to icon size. */
@Composable
private fun SeekerGlyph(tint: androidx.compose.ui.graphics.Color) {
    val body = Halo.cardSoft
    Canvas(Modifier.size(24.dp)) {
        // The real Seeker is 46% as wide as it is tall, which at icon size reads as a
        // stick. An icon is a sign, not a scale drawing: it gets a body.
        val h = size.height * 0.92f
        val w = h * 0.62f
        drawPhone((size.width - w) / 2f, (size.height - h) / 2f, w, h, back = true, body = body, edge = tint, ink = tint, glass = body)
    }
}

/**
 * The three most recent signatures, where Jupiter puts a promotion. Cheap: `months()` is
 * newest-first and each month is cached, so three normally touch only the current month's
 * file; `Ledger.all` would reparse every month on disk.
 */
@Composable
internal fun RecentReceiptsCard(onOpen: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val version = Ledger.version.value
    val entries: List<LedgerEntry> by androidx.compose.runtime.produceState(initialValue = emptyList(), version) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                Ledger.months(ctx).asSequence().flatMap { Ledger.month(ctx, it).asSequence() }.take(3).toList()
            }.getOrDefault(emptyList())
        }
    }
    if (entries.isEmpty()) return
    // A row taps and opens that receipt: it had the shape of the Receipts rows and
    // did nothing, while the title opened the tab.
    var selected by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<LedgerEntry?>(null) }
    selected?.let { e -> ReceiptDetailSheet(e) { selected = null } }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            CardHeader(stringResource(R.string.tab_receipts), onOpen)
            entries.forEach { e ->
                HaloRow(
                    title = e.dApp.takeIf { it.isNotBlank() } ?: kindLabel(ctx, e.kind),
                    sub = kindLabel(ctx, e.kind),
                    leading = {
                        Box(Modifier.size(30.dp).clip(rs(Radius.row)).background(Halo.card), contentAlignment = Alignment.Center) { HaloIcon(HIcon.RECEIPT, Halo.muted, 15.dp) }
                    },
                    trailing = { Text(shortWhen(e.at), style = HaloType.label, color = Halo.muted) },
                ) { selected = e }
            }
        }
    }
}

/**
 * What the agent has been doing, where Jupiter puts the watchlist. Most days the agent is
 * off; an empty box invites action, so with no agent this is one line and a way in, not a hole.
 */
@Composable
internal fun AgentGlanceCard(onOpen: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val line = remember { runCatching { HealthWidgetData.agentLine(ctx) }.getOrNull() }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            CardHeader(stringResource(R.string.home_agent_hdr), onOpen)
            Text(
                line ?: stringResource(R.string.home_agent_off),
                style = HaloType.small, color = if (line != null) Halo.ink else Halo.muted,
            )
        }
    }
}

/** A quiet card title with the arrow that says there is more behind it. */
@Composable
private fun CardHeader(title: String, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(rs(Radius.row)).clickable(onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = HaloType.label, color = Halo.muted, modifier = Modifier.weight(1f))
        HaloIcon(HIcon.CHEVRON_RIGHT, Halo.muted, 16.dp)
    }
}

private fun shortWhen(at: Long): String {
    val mins = ((System.currentTimeMillis() - at) / 60_000L).coerceAtLeast(0)
    return when {
        mins < 60 -> mins.toString() + "m"
        mins < 60 * 24 -> (mins / 60).toString() + "h"
        else -> (mins / 60 / 24).toString() + "g"
    }
}

/**
 * Everything that did not earn a place among the eight. The home is a wallet, not a
 * dashboard: health, cleanup, contacts and the widget live here, one tap away.
 */
@Composable
internal fun MoreSheet(onTap: () -> Unit, onHealth: () -> Unit, onContacts: () -> Unit, onSettings: () -> Unit, onBridge: () -> Unit = {}, onGift: () -> Unit = {}, onContactTap: () -> Unit = {}, onCompanion: () -> Unit = {}, hidden: List<HomeAction> = emptyList(), onAction: (HomeAction) -> Unit = {}, onDismiss: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        SheetHeader(stringResource(R.string.home_act_more), stringResource(R.string.home_act_more_sub), HIcon.MORE, onClose = onDismiss)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.xl).padding(bottom = Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            // Every row its own icon, so the list can be read by shape alone.
            // First the actions taken off the front page: Swap, Scan, Agent, the crowd.
            // Removed from there, they must stay one tap from here.
            for (a in hidden) MoreRow(homeActionIcon(a), stringResource(homeActionLabel(a))) { onAction(a) }
            MoreRow(HIcon.NFC, stringResource(R.string.home_act_tap), onTap)
            if (RocketX.enabled) MoreRow(HIcon.BRIDGE, stringResource(R.string.bridge_title), onBridge)
            // Sending money with a link was not in here because it was always on the front page. When
            // the bridge took its place it stayed reachable only from Send and Customize, for those who
            // knew. What leaves the front page enters this list at the same moment.
            MoreRow(HIcon.GIFT, stringResource(R.string.gift_title), onGift)
            MoreRow(HIcon.CONTACTS, stringResource(R.string.ctap_open), onContactTap)
            MoreRow(HIcon.SHIELD_LOCK, stringResource(R.string.more_health), onHealth)
            MoreRow(HIcon.PEOPLE, stringResource(R.string.more_contacts), onContacts)
            MoreRow(HIcon.PIGEON, stringResource(R.string.comp_page_title), onCompanion)
            MoreRow(HIcon.SETTINGS, stringResource(R.string.tab_settings), onSettings)
            androidx.compose.foundation.layout.Spacer(Modifier.size(Space.sm))
        }
        }
    }
}

@Composable
private fun MoreRow(icon: HIcon, label: String, onClick: () -> Unit) {
    HaloRow(label, onGround = true, leading = { HaloIcon(icon, Halo.ink, 20.dp) }, onClick = onClick)
}

/** Realized P&L, behind the balance's own change. The card already existed. */
@Composable
internal fun PnlSheet(onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val currency = Settings.currency.value
    val version = Ledger.version.value
    val analytics: Analytics? by androidx.compose.runtime.produceState(initialValue = null, version, currency) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { AnalyticsEngine.of(Ledger.all(ctx), currency) }.getOrNull()
        }
    }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.lg).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Text(stringResource(R.string.pnl_title), style = HaloType.title, color = Halo.ink)
            val a = analytics
            when {
                a == null -> Working(stringResource(R.string.claim_checking))
                !a.hasData -> Text(stringResource(R.string.pnl_note), style = HaloType.small, color = Halo.muted)
                else -> AnalyticsCard(a)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(Space.sm))
        }
    }
}


/** Wallet health, reachable from the home without turning the home into a dashboard. */
@Composable
internal fun HealthSheet(owner: String?, onDismiss: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.lg).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Text(stringResource(R.string.health_title), style = HaloType.title, color = Halo.ink)
            WalletHealthCard(owner)
            ForgottenMoneyCard(owner)
            androidx.compose.foundation.layout.Spacer(Modifier.size(Space.sm))
        }
    }
}

/**
 * The header every sheet had written by hand, twelve times, only three with a way out. A
 * full-height sheet must say how to leave it: swiping down is not something to guess.
 */
@Composable
internal fun SheetHeader(title: String, sub: String?, icon: HIcon, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(rs(Radius.panel)).background(Halo.cardSoft).haloBorder(rs(Radius.panel), living = false),
            contentAlignment = Alignment.Center,
        ) { HaloIcon(icon, Halo.ink, 20.dp) }
        androidx.compose.foundation.layout.Spacer(Modifier.size(Space.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = HaloType.title, color = Halo.ink)
            if (sub != null) Text(sub, style = HaloType.small, color = Halo.muted)
        }
        Box(
            Modifier.size(34.dp).clip(rs(Radius.pill)).background(Halo.card).haloBorder(rs(Radius.pill), living = false)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { HaloIcon(HIcon.CLOSE, Halo.muted, 16.dp, description = stringResource(R.string.close)) }
    }
}

