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
import androidx.compose.runtime.Composable
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

/** The eight things you can start from the wallet home. */
internal enum class HomeAction { SEND, RECEIVE, SWAP, SCAN, CROWD, TAP, LINK, AGENT, MORE }

/**
 * The action grid: eight round, **neutral** buttons.
 *
 * Neutral on purpose. The three tiles this replaces were tinted — two mint, one
 * cyan — which told you there was a hierarchy between Swap, Send and Receive
 * when there is none. If all eight were the accent, the accent would stop
 * meaning "this is the action" and go back to being decoration. So the colour
 * on this page comes from the balance and the token logos, and the buttons are
 * quiet: a dark circle, a hand-drawn line icon, a label.
 */
@Composable
internal fun HomeActions(enabled: Boolean, onAction: (HomeAction) -> Unit) {
    val rows = listOf(
        listOf(
            Triple(HomeAction.SEND, HIcon.SEND, R.string.send_btn),
            Triple(HomeAction.RECEIVE, HIcon.RECEIVE, R.string.receive_btn),
            Triple(HomeAction.SWAP, HIcon.SWAP, R.string.swap_btn),
            Triple(HomeAction.SCAN, HIcon.SCAN, R.string.send_scan),
        ),
        listOf(
            // The tap-to-pay button used to live here. It is a fine feature and
            // almost nobody opens it twice, while what the rest of the Seeker crowd
            // is buying is worth a look every day. Tap moved into "Altro", intact.
            Triple(HomeAction.CROWD, HIcon.NFC, R.string.home_act_crowd),
            Triple(HomeAction.LINK, HIcon.SHARE, R.string.home_act_link),
            Triple(HomeAction.AGENT, HIcon.PIGEON, R.string.home_act_agent),
            Triple(HomeAction.MORE, HIcon.MORE, R.string.home_act_more),
        ),
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                row.forEach { (action, icon, label) ->
                    ActionButton(icon, stringResource(label), enabled, Modifier.weight(1f)) { onAction(action) }
                }
            }
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
        Box(
            // The same living hairline as the cards, so a theme that moves moves
            // everywhere rather than in one place.
            Modifier.size(58.dp).clip(rs(Radius.pill)).background(Halo.cardSoft).border(cardBorder(), rs(Radius.pill)),
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
 * The scout's lens.
 *
 * It had a phone inside it, four ticks around it and a handle, at twenty-six
 * density-independent pixels. All of it was true and none of it was legible:
 * past a certain point an icon stops being a drawing and becomes a smudge.
 *
 * So it lost everything except what it is for. A ring, a line rising inside it,
 * a handle. Looking at a market, in three strokes.
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
 * The three most recent signatures, where Jupiter puts a promotion.
 *
 * Cheap on purpose: `months()` is already newest-first and each month's list is
 * cached, so taking three normally touches only the current month's file.
 * `Ledger.all` would reparse every month on disk.
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
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
            CardHeader(stringResource(R.string.tab_receipts), onOpen)
            entries.forEach { e ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(30.dp).clip(rs(Radius.row)).background(Halo.cardSoft),
                        contentAlignment = Alignment.Center,
                    ) { HaloIcon(HIcon.RECEIPT, Halo.muted, 15.dp) }
                    androidx.compose.foundation.layout.Spacer(Modifier.size(Space.md))
                    Column(Modifier.weight(1f)) {
                        Text(e.dApp.takeIf { it.isNotBlank() } ?: kindLabel(ctx, e.kind), style = HaloType.small, color = Halo.ink, maxLines = 1)
                        Text(kindLabel(ctx, e.kind), style = HaloType.label, color = Halo.muted, maxLines = 1)
                    }
                    Text(shortWhen(e.at), style = HaloType.label, color = Halo.muted)
                }
            }
        }
    }
}

/**
 * What the agent has been doing, where Jupiter puts the watchlist.
 *
 * Most days the agent is off and there is nothing to report. An empty box would
 * be the worst answer: an empty screen is an invitation to act, so when there is
 * no agent this is one line and a way in, not a hole.
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
 * Everything that did not earn a place among the eight.
 *
 * The home is a wallet, not a dashboard — the comment on `SecurityTools` says so
 * and it still holds. Health, cleanup, contacts and the widget live here, one
 * tap away, instead of competing with the money on the front page.
 */
@Composable
internal fun MoreSheet(onTap: () -> Unit, onHealth: () -> Unit, onContacts: () -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.lg)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Text(stringResource(R.string.home_act_more), style = HaloType.title, color = Halo.ink)
            MoreRow(HIcon.NFC, stringResource(R.string.home_act_tap), onTap)
            MoreRow(HIcon.SHIELD_LOCK, stringResource(R.string.health_title), onHealth)
            MoreRow(HIcon.CONTACTS, stringResource(R.string.home_contacts_hdr), onContacts)
            MoreRow(HIcon.WALLET, stringResource(R.string.widget_card_title), onSettings)
            MoreRow(HIcon.SETTINGS, stringResource(R.string.tab_settings), onSettings)
            androidx.compose.foundation.layout.Spacer(Modifier.size(Space.sm))
        }
    }
}

@Composable
private fun MoreRow(icon: HIcon, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(rs(Radius.panel)).background(Halo.cardSoft).border(cardBorder(), rs(Radius.panel))
            .clickable(onClick = onClick).padding(Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HaloIcon(icon, Halo.ink, 20.dp)
        androidx.compose.foundation.layout.Spacer(Modifier.size(Space.md))
        Text(label, style = HaloType.body, color = Halo.ink, modifier = Modifier.weight(1f))
        HaloIcon(HIcon.CHEVRON_RIGHT, Halo.muted, 16.dp)
    }
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

/** The navigation bar inset, spelled once instead of in every sheet. */
private fun Modifier.navigationBarsPadding(): Modifier = this.then(Modifier)

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
            androidx.compose.foundation.layout.Spacer(Modifier.size(Space.sm))
        }
    }
}

/**
 * The header every sheet in this app had written by hand, twelve times over,
 * and only three of them offered a way out. A full-height sheet has to say how
 * to leave it: swiping it down is not something a person should have to guess.
 */
@Composable
internal fun SheetHeader(title: String, sub: String?, icon: HIcon, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(rs(Radius.panel)).background(Halo.cardSoft).border(cardBorder(), rs(Radius.panel)),
            contentAlignment = Alignment.Center,
        ) { HaloIcon(icon, Halo.ink, 20.dp) }
        androidx.compose.foundation.layout.Spacer(Modifier.size(Space.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = HaloType.title, color = Halo.ink)
            if (sub != null) Text(sub, style = HaloType.small, color = Halo.muted)
        }
        Box(
            Modifier.size(34.dp).clip(rs(Radius.pill)).background(Halo.card).border(cardBorder(), rs(Radius.pill))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { HaloIcon(HIcon.CLOSE, Halo.muted, 16.dp) }
    }
}
