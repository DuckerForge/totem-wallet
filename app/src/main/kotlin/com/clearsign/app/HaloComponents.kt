package com.clearsign.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The interface's common language: what says "this is tappable", what says "this is only
 * read", and the pieces every page uses alike. No network and no app state here, only
 * shape, color and motion in `HaloTheme`'s tones; every animation is read in draw or
 * `graphicsLayer`, never in composition. Two rules. A chevron is a promise: only what
 * opens something carries one, and everything that opens something does. The living
 * edge belongs to cards: only `GlassCard` carries the flowing gradient; on every row it
 * turned a list into a row of bubbles.
 */

// ---- l'orlo ------------------------------------------------------------------------

/**
 * One phase for every living border. Each card had its own infinite animation read in
 * composition: on palettes with `livingStroke` every `GlassCard` recomposed sixty times a
 * second and allocated a brush per frame, forever. `HaloRoot` moves one number per frame
 * and the borders read it in draw.
 */
object LivingStroke {
    val phase = mutableFloatStateOf(0f)
    const val PERIOD_MS = 5200L
}

private val LIVING_COLORS = listOf(Color(0xFF9945FF), Color(0xFF14F195), Color(0xFF9945FF))

/**
 * A card's edge: a thin line in `stroke`, or the flowing gradient on palettes that have it.
 * Drawn inside the border like `border`, read in draw: a palette or phase change redraws,
 * never recomposes. [color] forces a color; [living] false always gives the `stroke` line,
 * the edge of what is not a card.
 */
fun Modifier.haloBorder(shape: Shape, width: Dp = 1.dp, color: Color? = null, living: Boolean = true): Modifier = this.then(
    Modifier.drawWithCache {
        val w = width.toPx()
        val inner = Size((size.width - w).coerceAtLeast(0f), (size.height - w).coerceAtLeast(0f))
        val outline = shape.createOutline(inner, layoutDirection, this)
        // The gradient repeats along its axis: to turn with no seam the phase must
        // travel exactly one axis vector per cycle.
        val dx = 520f
        val dy = 520f * 0.7f
        onDrawWithContent {
            drawContent()
            translate(w / 2f, w / 2f) {
                when {
                    color != null -> drawOutline(outline, color, style = Stroke(w))
                    !living || !Halo.palette.livingStroke -> drawOutline(outline, Halo.stroke, style = Stroke(w))
                    else -> {
                        val t = LivingStroke.phase.floatValue
                        drawOutline(
                            outline,
                            Brush.linearGradient(LIVING_COLORS, start = Offset(t * dx, t * dy), end = Offset(t * dx + dx, t * dy + dy), tileMode = TileMode.Repeated),
                            style = Stroke(w + 0.2.dp.toPx()),
                        )
                    }
                }
            }
        }
    },
)

// ---- what you touch ----------------------------------------------------------------

/**
 * The treatment of everything tappable, once: contained surface, no edge unless asked,
 * shrinks a touch under the finger and veils in ink while it stays. No ripple: invisible
 * on these dark grounds, while a box changing shape is visible. The veil is read in draw,
 * so a press does not recompose the row.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Modifier.tappable(
    src: MutableInteractionSource,
    shape: Shape,
    fill: Color = Halo.cardSoft,
    border: Color? = null,
    enabled: Boolean = true,
    down: Float = 0.98f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val pressed by src.collectIsPressedAsState()
    val pressT = animateFloatAsState(if (pressed) 1f else 0f, tween(120), label = "press")
    val ink = Halo.ink
    return this
        .pressScale(src, down)
        .clip(shape)
        .background(fill)
        .then(if (border != null) Modifier.haloBorder(shape, color = border) else Modifier)
        .drawWithContent {
            drawContent()
            val t = pressT.value
            if (t > 0f) drawRect(ink, alpha = 0.07f * t)
        }
        .then(
            if (onLongClick == null) Modifier.clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            else Modifier.combinedClickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        )
}

/**
 * The row that opens something: at least 52 dp, a chevron on the right, contained, with
 * [tappable]'s press. Inside a card it uses the row radius; on the page, [onGround], the
 * panel radius and the card color.
 */
@Composable
fun HaloRow(
    title: String,
    sub: String? = null,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    chevron: Boolean = true,
    onGround: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val shape = rs(if (onGround) Radius.panel else Radius.row)
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp)
            .tappable(src, shape, fill = if (onGround) Halo.card else Halo.cardSoft, border = if (danger) Halo.red.copy(alpha = 0.5f) else null, enabled = enabled, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.sm + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(Space.md)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = HaloType.body, color = Halo.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = HaloType.label, color = Halo.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(Space.sm)); trailing() }
        if (chevron) { Spacer(Modifier.width(Space.sm)); HaloIcon(HIcon.CHEVRON_RIGHT, Halo.muted, 14.dp) }
    }
}

/** The tile that opens something: contained, pressable, chevron top right. */
@Composable
fun HaloTile(width: Dp, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val src = remember { MutableInteractionSource() }
    Box(Modifier.width(width).tappable(src, rs(14), onClick = onClick)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        HaloIcon(HIcon.CHEVRON_RIGHT, Halo.muted, 12.dp, Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 8.dp))
    }
}

/** An icon in a round button: refresh, close, share. Spins when [spinning]. */
@Composable
fun RoundIconButton(icon: HIcon, tint: Color = Halo.muted, spinning: Boolean = false, size: Dp = 34.dp, description: String? = null, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val spin = remember { Animatable(0f) }
    LaunchedEffect(spinning) {
        if (!spinning) { spin.snapTo(0f); return@LaunchedEffect }
        while (true) { spin.snapTo(0f); spin.animateTo(360f, tween(900, easing = androidx.compose.animation.core.LinearEasing)) }
    }
    Box(
        Modifier.size(size).tappable(src, rs(Radius.pill), fill = Halo.card, border = Halo.stroke, down = 0.92f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        HaloIcon(icon, tint, 16.dp, Modifier.graphicsLayer { rotationZ = spin.value }, description = description)
    }
}

// ---- what you only read ------------------------------------------------------------

/**
 * The panel that is only read: a step below the card, thin edge, no chevron, no press. A
 * chevron here would be a lie.
 */
@Composable
fun SoftPanel(modifier: Modifier = Modifier, padding: Dp = 14.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(rs(Radius.panel)).background(Halo.cardSoft).haloBorder(rs(Radius.panel), living = false).padding(padding),
        content = content,
    )
}

// ---- il chip ------------------------------------------------------------------------

/**
 * One chip for the whole app. Off, a light action in [tint]; [selected], a filter or a mode
 * that is on, filled. [pulse] beats three times on arrival, for the chip with an invisible
 * second gesture: hold to change it, and nothing else says so.
 */
@Composable
fun HaloChip(
    label: String,
    icon: HIcon? = null,
    tint: Color = Halo.cyan,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
    pulse: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val beat = remember { Animatable(0f) }
    LaunchedEffect(pulse) {
        if (!pulse) return@LaunchedEffect
        delay(400)
        beat.animateTo(1f, repeatable(6, tween(320), RepeatMode.Reverse))
        beat.snapTo(0f)
    }
    val b = beat.value
    val fill = if (selected) tint.copy(alpha = 0.16f) else tint.copy(alpha = 0.08f + 0.18f * b)
    val edge = if (selected || b > 0.01f) tint.copy(alpha = if (selected) 1f else 0.55f * b + 0.45f) else tint.copy(alpha = 0.45f)
    Row(
        modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier).heightIn(min = 34.dp)
            .tappable(src, rs(Radius.row), fill = fill, border = edge, down = 0.96f, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (fillWidth) Arrangement.Center else Arrangement.Start,
    ) {
        if (icon != null) { HaloIcon(icon, tint, 14.dp); Spacer(Modifier.width(6.dp)) }
        Text(
            label, style = HaloType.small.copy(fontWeight = FontWeight.SemiBold), color = if (selected) tint else tint,
            maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
        )
    }
}

// ---- le intestazioni ------------------------------------------------------------------

/**
 * A page header, the same for the five tabs: a 22 sp title, a subtitle, an icon tile or a
 * [leading] of choice, and an action on the right. [sweep] lights the sweep once, for the agent.
 */
@Composable
fun PageHeader(
    title: String,
    sub: String? = null,
    icon: HIcon? = null,
    tint: Color = Halo.cyan,
    leading: (@Composable () -> Unit)? = null,
    sweep: Boolean = false,
    /** For the line under the title: the home shows it when the big number scrolls away. */
    subModifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        when {
            leading != null -> { leading(); Spacer(Modifier.width(Space.md)) }
            icon != null -> {
                Box(Modifier.size(38.dp).clip(rs(Radius.row)).background(tint.copy(alpha = 0.14f)).haloBorder(rs(Radius.row), color = tint.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    HaloIcon(icon, tint, 20.dp)
                    if (sweep) SweepHalo(tint, Modifier.size(38.dp), key = FirstRun.at)
                }
                Spacer(Modifier.width(Space.md))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = HaloType.screen, color = Halo.ink, maxLines = 1)
            if (sub != null) Text(sub, style = HaloType.small, color = Halo.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = subModifier)
        }
        trailing()
    }
}

// ---- gli stati vuoti -------------------------------------------------------------------

/** The empty state that says what to do: an icon, a title, a line, and an action if needed. */
@Composable
fun EmptyState(icon: HIcon, title: String, body: String, action: Pair<String, () -> Unit>? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = Space.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Box(Modifier.size(52.dp).clip(rs(Radius.panel)).background(Halo.cardSoft).haloBorder(rs(Radius.panel)), contentAlignment = Alignment.Center) {
            HaloIcon(icon, Halo.muted, 24.dp)
        }
        Text(title, style = HaloType.title, color = Halo.ink, textAlign = TextAlign.Center)
        Text(body, style = HaloType.small, color = Halo.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = Space.lg))
        if (action != null) {
            Spacer(Modifier.height(Space.xs))
            PrimaryButton(action.first, danger = false, fillWidth = false) { action.second() }
        }
    }
}

/** The empty row inside a card: a small icon and a sentence, nothing more. */
@Composable
fun EmptyLine(icon: HIcon, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        HaloIcon(icon, Halo.muted, 16.dp)
        Spacer(Modifier.width(Space.sm))
        Text(text, style = HaloType.small, color = Halo.muted)
    }
}

// ---- l'interruttore -------------------------------------------------------------------

/** A title, a line under it, and the switch in the house colors. */
@Composable
fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = HaloType.body, color = Halo.ink)
            if (sub != null) Text(sub, style = HaloType.small, color = Halo.muted)
        }
        Spacer(Modifier.width(Space.md))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Halo.ground, checkedTrackColor = Halo.mint,
                uncheckedThumbColor = Halo.muted, uncheckedTrackColor = Halo.cardSoft, uncheckedBorderColor = Halo.stroke,
            ),
        )
    }
}

/**
 * The change with its sign, green up, red down, in a soft pill of its own color. Shared by
 * the wallet's "today" and the agent's "since you funded it". `chevron` only when it opens something.
 */
@Composable
fun DeltaPill(text: String, up: Boolean, modifier: Modifier = Modifier, chevron: Boolean = false) {
    val tint = if (up) Halo.mint else Halo.red
    Row(
        modifier.clip(rs(Radius.pill)).background(tint.copy(alpha = 0.12f))
            .padding(start = 12.dp, end = if (chevron) 8.dp else 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = HaloType.small, color = tint)
        if (chevron) HaloIcon(HIcon.CHEVRON_RIGHT, tint, 15.dp)
    }
}

/**
 * A row that opens a group under itself: the icon tile lights and the chevron turns in
 * graphicsLayer, so opening redraws and never recomposes the row. Settings and the agent's Pro sections.
 */
@Composable
fun DisclosureRow(title: String, sub: String?, icon: HIcon, open: Boolean, tint: Color = Halo.cyan, onToggle: () -> Unit) {
    val turn = animateFloatAsState(if (open) 90f else 0f, label = "disclosure")
    HaloRow(
        title, sub, onGround = true, chevron = false,
        leading = {
            Box(Modifier.size(34.dp).clip(rs(10)).background(if (open) Halo.mint.copy(alpha = 0.14f) else tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                HaloIcon(icon, if (open) Halo.mint else tint, 18.dp)
            }
        },
        trailing = { HaloIcon(HIcon.CHEVRON_RIGHT, Halo.muted, 16.dp, Modifier.graphicsLayer { rotationZ = turn.value }) },
        onClick = onToggle,
    )
}
