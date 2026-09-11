@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.animation.ExperimentalAnimationApi::class)

package com.clearsign.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.clearsign.core.BalanceDelta
import com.clearsign.core.Receipt
import com.clearsign.core.Risk
import com.clearsign.core.RiskFlag
import com.clearsign.core.Severity
import com.clearsign.core.TrustLevel
import com.clearsign.core.TxStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

fun deviceLocaleTag(): String = when (Locale.getDefault().language) {
    "it" -> "it"; "es" -> "es"; else -> "en"
}

private fun trustColor(t: TrustLevel) = when (t) {
    TrustLevel.TRUSTED -> Halo.mint
    TrustLevel.KNOWN -> Halo.cyan
    TrustLevel.NEW -> Halo.amber
    TrustLevel.FLAGGED -> Halo.red
}

/** Halo Material scheme: keeps M3 widgets (text field, sheet) on-brand. A getter,
 *  so it follows the selected palette instead of freezing the first one. */
internal val HaloScheme get() = darkColorScheme(
    primary = Halo.mint, onPrimary = Halo.ground,
    secondary = Halo.cyan, onSecondary = Halo.ground,
    background = Halo.ground, onBackground = Halo.ink,
    surface = Halo.ground2, onSurface = Halo.ink,
    surfaceVariant = Halo.card, onSurfaceVariant = Halo.muted,
    outline = Halo.stroke, error = Halo.red,
)

// ---------------------------------------------------------------------------

/** Material scheme + per-theme letter-spacing, applied once at each screen root. */
@Composable
fun HaloRoot(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HaloScheme) {
        androidx.compose.material3.ProvideTextStyle(androidx.compose.material3.LocalTextStyle.current.copy(letterSpacing = Halo.palette.fonts.tracking.sp)) { content() }
    }
}

@Composable
fun MwaScreen(ui: MwaUi) {
    HaloRoot {
        val danger = ui is MwaUi.Error || (ui is MwaUi.SignRequest && ui.receipts.any { it.blocksApproval }) ||
            (ui is MwaUi.SignInRequest && ui.domainMismatch)
        val haloColor by animateFloatAsState(if (danger) 1f else 0f, tween(600), label = "halo")
        val accent = lerp(Halo.cyan, Halo.red, haloColor)
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Halo.ground2, Halo.ground)))
                .background(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(900f, -100f), radius = 900f,
                    ),
                )
                .haloSurface(),
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Header(danger = danger, dApp = ui.dAppOrNull)
                Spacer(Modifier.height(18.dp))
                AnimatedContent(
                    targetState = ui,
                    contentKey = { it::class },
                    transitionSpec = {
                        (fadeIn(tween(260)) + slideInVertically(tween(320)) { it / 14 }) togetherWith fadeOut(tween(160))
                    },
                    label = "screen",
                ) { state ->
                    Column {
                        when (state) {
                            is MwaUi.Preparing -> Working(stringResource(R.string.working_session))
                            is MwaUi.Working -> Working(state.message)
                            is MwaUi.Connect -> ConnectPrompt(state)
                            is MwaUi.AccountPick -> AccountPickPrompt(state)
                            is MwaUi.SignInRequest -> SignInPrompt(state)
                            is MwaUi.MessageRequest -> MessagePrompt(state)
                            is MwaUi.SignRequest -> SignPrompt(state)
                            is MwaUi.Done -> DoneScreen(state)
                            is MwaUi.Error -> ErrorScreen(state.message)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            // The decision never scrolls away: what you pay + hold-to-sign, always on screen.
            ActionBar(ui)
            }
        }
    }
}

/**
 * Fixed bottom bar: the one thing the user must see without scrolling. For a
 * sign request it is the amount and the hold-to-sign gesture; for a blocked
 * one, the block notice; for the others, the approve/decline pair.
 */
@Composable
private fun ActionBar(ui: MwaUi) {
    val content: @Composable () -> Unit = when (ui) {
        is MwaUi.SignRequest -> { { SignActions(ui) } }
        is MwaUi.MessageRequest -> { {
            ActionSummary(stringResource(R.string.bar_sign_message), if (ui.messages.size > 1) stringResource(R.string.bar_messages_n, ui.messages.size) else stringResource(R.string.bar_no_funds), Halo.cyan) { ui.onDecline() }
            Spacer(Modifier.height(10.dp))
            HoldToConfirm(if (ui.messages.size > 1) stringResource(R.string.hold_sign_messages, ui.messages.size) else stringResource(R.string.hold_sign)) { ui.onApprove() }
        } }
        is MwaUi.SignInRequest -> { {
            ActionSummary(stringResource(R.string.bar_login), ui.domain, if (ui.domainMismatch) Halo.red else Halo.cyan) { ui.onDecline() }
            Spacer(Modifier.height(10.dp))
            HoldToConfirm(if (ui.domainMismatch) stringResource(R.string.hold_login_anyway) else stringResource(R.string.hold_login)) { ui.onApprove() }
        } }
        is MwaUi.Connect -> { {
            PrimaryButton(stringResource(R.string.connect_biometric), danger = false, icon = HIcon.FINGERPRINT) { ui.onApprove() }
            Spacer(Modifier.height(8.dp))
            GhostButton(stringResource(R.string.decline)) { ui.onDecline() }
        } }
        is MwaUi.AccountPick -> { { GhostButton(stringResource(R.string.cancel)) { ui.onDecline() } } }
        is MwaUi.Error -> { {
            val ctx = LocalContext.current
            GhostButton(stringResource(R.string.close)) { (ctx as? Activity)?.finishAndRemoveTask() }
        } }
        else -> return
    }
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Halo.ground.copy(alpha = 0.97f)), endY = 60f))
            .padding(top = 10.dp)
            .background(Halo.ground.copy(alpha = 0.97f))
            .border(1.dp, Halo.stroke.copy(alpha = 0.6f), Halo.shapeTop(22))
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) { content() }
}

/** One-line headline for the bar: label + value on the left, stringResource(R.string.decline) on the right. */
@Composable
private fun ActionSummary(label: String, value: String, color: Color, onDecline: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, color = Halo.muted)
            Text(value, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = color, maxLines = 1)
        }
        Text(
            stringResource(R.string.decline), fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Halo.muted,
            modifier = Modifier.clip(rs(10)).clickable { onDecline() }.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SignActions(ui: MwaUi.SignRequest) {
    val receipts = ui.receipts
    val danger = receipts.any { it.blocksApproval }
    if (danger) {
        val blockedTx = receipts.indexOfFirst { it.blocksApproval }
        BlockedNotice(
            if (ui.count > 1) stringResource(R.string.blocked_bundle, blockedTx + 1)
            else stringResource(R.string.blocked_single),
        )
        Spacer(Modifier.height(8.dp))
        GhostButton(stringResource(R.string.close)) { ui.onDecline() }
        return
    }
    val totals = if (ui.count > 1) aggregateOutflows(receipts) else receipts[0].outflows
    val inflows = if (ui.count > 1) emptyList() else receipts[0].inflows
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (ui.count > 1) stringResource(R.string.pay_total_n, ui.count) else stringResource(R.string.pay), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, color = Halo.muted)
            if (totals.isEmpty()) Text(stringResource(R.string.no_transfer), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.ink)
            else totals.take(2).forEach { AmountText("−", it, 20.sp, Halo.mint) }
            inflows.take(1).forEach { AmountText("+", it, 13.sp, Halo.cyan, FontWeight.SemiBold) }
        }
        Text(
            stringResource(R.string.decline), fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Halo.muted,
            modifier = Modifier.clip(rs(10)).clickable { ui.onDecline() }.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
    Spacer(Modifier.height(10.dp))
    val label = when {
        ui.count > 1 && ui.willSend -> stringResource(R.string.hold_sign_send_n, ui.count)
        ui.count > 1 -> stringResource(R.string.hold_sign_n, ui.count)
        ui.willSend -> stringResource(R.string.hold_sign_send)
        else -> stringResource(R.string.hold_sign)
    }
    HoldToConfirm(label) { ui.onApprove() }
}

/** Screens that know who is asking (so the header can show the dApp identity). */
private val MwaUi.dAppOrNull: DappId?
    get() = when (this) {
        is MwaUi.Connect -> dApp; is MwaUi.AccountPick -> dApp; is MwaUi.SignInRequest -> dApp
        is MwaUi.MessageRequest -> dApp; is MwaUi.SignRequest -> dApp; else -> null
    }

internal fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f,
)

@Composable
private fun Header(danger: Boolean, dApp: DappId?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).clip(rs(10))
                .background(Brush.linearGradient(listOf(Halo.mint, Halo.cyan))),
            contentAlignment = Alignment.Center,
        ) { HaloIcon(HIcon.SEAL, Halo.ground, 24.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("ClearSign", fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = Halo.ink)
            Text(
                if (danger) stringResource(R.string.header_danger) else stringResource(R.string.tagline),
                fontFamily = Inter, fontSize = 11.5.sp, color = if (danger) Halo.red else Halo.muted,
            )
        }
        if (dApp != null) DappPill(dApp)
    }
}

// ---- dApp identity ----------------------------------------------------------

private val iconCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

@Composable
private fun DappIcon(dApp: DappId, size: androidx.compose.ui.unit.Dp) {
    // The dApp's own icon first; when it gives none (or it fails), the site's favicon,
    // then a favicon service — a recognisable logo beats an initial in a circle.
    val candidates = remember(dApp.iconUrl, dApp.host) {
        listOfNotNull(dApp.iconUrl, dApp.host?.let { "https://$it/favicon.ico" }, dApp.host?.let { "https://www.google.com/s2/favicons?domain=$it&sz=128" })
    }
    val cacheKey = candidates.firstOrNull() ?: ""
    var bmp by remember(cacheKey) { mutableStateOf(iconCache[cacheKey]) }
    LaunchedEffect(cacheKey) {
        if (bmp != null || candidates.isEmpty()) return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            candidates.firstNotNullOfOrNull { url ->
                runCatching {
                    val c = URL(url).openConnection().apply { connectTimeout = 4000; readTimeout = 4000 }
                    c.getInputStream().use { BitmapFactory.decodeStream(it) }
                }.getOrNull()?.takeIf { it.width >= 16 }
            }?.also { iconCache[cacheKey] = it }
        }
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(Halo.cardSoft).border(1.dp, Halo.stroke, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(dApp.name.take(1).uppercase(), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp, color = Halo.cyan)
    }
}

/** Compact identity pill: icon + verified host (what the user must recognise). */
@Composable
private fun DappPill(dApp: DappId) {
    Row(
        Modifier.clip(rs(999)).background(Halo.cardSoft).border(1.dp, Halo.stroke, rs(999))
            .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DappIcon(dApp, 22.dp)
        Spacer(Modifier.width(7.dp))
        Text(dApp.host ?: dApp.name, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, color = Halo.ink, maxLines = 1)
    }
}

/** Big identity block for the connect / sign-in screens. */
@Composable
private fun DappHero(dApp: DappId, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DappIcon(dApp, 52.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(dApp.name, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.ink)
            Text(dApp.host ?: subtitle, fontFamily = Inter, fontSize = 12.5.sp, color = if (dApp.host != null) Halo.cyan else Halo.muted)
            Spacer(Modifier.height(3.dp))
            DappMemory(dApp)
            dApp.store?.let { Spacer(Modifier.height(3.dp)); DappStoreLine(it) }
        }
    }
}

// ---- building blocks --------------------------------------------------------

@Composable
internal fun GlassCard(content: @Composable () -> Unit) {
    val slot = LocalEntrance.current?.let { c -> remember { c.getAndIncrement() } }
    Box(
        (if (slot != null) Modifier.staggeredEntrance(slot.coerceAtMost(7)) else Modifier).fillMaxWidth()
            .clip(rs(22))
            .background(Halo.card)
            .border(1.dp, Halo.stroke, rs(22))
            .padding(20.dp),
    ) { content() }
}

@Composable
internal fun Working(message: String) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val tr = rememberInfiniteTransition(label = "pulse")
        val p by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "p")
        val a by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "a")
        val stroke = with(LocalDensity.current) { 3.dp.toPx() }
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(84.dp)) {
                val r = size.minDimension / 2
                val glow = (sin(p * 2 * Math.PI).toFloat() + 1f) / 2f
                drawCircle(Halo.mint.copy(alpha = 0.05f + 0.08f * glow), r)
                drawCircle(Halo.cyan.copy(alpha = 0.10f + 0.10f * glow), r * 0.72f)
                drawArc(Halo.mint, startAngle = a * 360f, sweepAngle = 80f, useCenter = false, style = Stroke(width = stroke), topLeft = Offset(r * 0.28f, r * 0.28f), size = androidx.compose.ui.geometry.Size(r * 1.44f, r * 1.44f))
            }
            HaloIcon(HIcon.GEM, Halo.mint, 26.dp)
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(message, fontFamily = Inter, color = Halo.muted, fontSize = 13.5.sp)
            Spacer(Modifier.width(6.dp)); BlinkCaret(Halo.mint, 13.dp, 6.dp)
        }
    }
}

@Composable
internal fun Banner(message: String, color: Color, icon: HIcon? = null) {
    Row(
        Modifier.fillMaxWidth().clip(rs(16))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.4f), rs(16))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { HaloIcon(icon, color, 22.dp); Spacer(Modifier.width(12.dp)) }
        Text(message, color = color, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
internal fun PrimaryButton(label: String, danger: Boolean, enabled: Boolean = true, icon: HIcon? = null, onClick: () -> Unit) {
    val shape = rs(16)
    val mod = Modifier.fillMaxWidth().height(54.dp).clip(shape)
    val fg = if (danger) Halo.red else Halo.ground
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier.pressScale(src).then(if (danger) mod.border(1.dp, Halo.red, shape) else mod.background(Brush.linearGradient(listOf(Halo.mint, Halo.cyan)), alpha = if (enabled) 1f else 0.45f))
            .clickable(interactionSource = src, indication = null, enabled = enabled) { onClick() },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { HaloIcon(icon, fg, 20.dp); Spacer(Modifier.width(10.dp)) }
        Text(label, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = fg)
    }
}

@Composable
internal fun GhostButton(label: String, modifier: Modifier = Modifier, icon: HIcon? = null, tint: Color = Halo.muted, onClick: () -> Unit) {
    val shape = rs(16)
    val src = remember { MutableInteractionSource() }
    Row(
        modifier.pressScale(src).fillMaxWidth().height(48.dp).clip(shape).border(1.dp, Halo.stroke, shape).clickable(interactionSource = src, indication = null) { onClick() },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { HaloIcon(icon, tint, 17.dp); Spacer(Modifier.width(8.dp)) }
        Text(label, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = tint)
    }
}

/**
 * Hardware-wallet gesture: hold for ~0.9 s to confirm. A tap does nothing —
 * no accidental signatures — and the fill shows the commitment building up.
 */
@Composable
internal fun HoldToConfirm(label: String, enabled: Boolean = true, onConfirm: () -> Unit) {
    val shape = rs(16)
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var holding by remember { mutableStateOf(false) }
    val glow = remember { Animatable(0f) }
    Box(
        Modifier.pressScale(holding, 0.985f).fillMaxWidth().height(58.dp).clip(shape)
            .drawWithContent {
                drawContent()
                val g = glow.value
                if (g > 0f) drawRoundRect(Halo.mint, alpha = g * 0.45f, cornerRadius = androidx.compose.ui.geometry.CornerRadius(Halo.radius(16).toPx()))
            }
            .background(Halo.mint.copy(alpha = if (enabled) 0.14f else 0.06f))
            .border(1.dp, Halo.mint.copy(alpha = if (enabled) 0.55f else 0.2f), shape)
            .pointerInput(enabled) {
                detectTapGestures(onPress = {
                    if (!enabled) return@detectTapGestures
                    holding = true
                    var fired = false
                    val job: Job = scope.launch {
                        var next = 0.25f
                        progress.animateTo(1f, tween(((1f - progress.value) * 900).toInt(), easing = LinearEasing)) {
                            if (value >= next && next < 1f) { Haptics.tick(ctx); next += 0.25f }
                        }
                        if (progress.value >= 0.999f) {
                            fired = true; Haptics.success(ctx)
                            launch { glow.snapTo(1f); glow.animateTo(0f, tween(520)) }
                            onConfirm()
                        }
                    }
                    tryAwaitRelease()
                    holding = false
                    if (!fired) { job.cancel(); scope.launch { progress.animateTo(0f, tween(220)) } }
                })
            },
    ) {
        // The fill is drawn, not laid out: holding never recomposes or re-measures the bar.
        val fill = Brush.horizontalGradient(listOf(Halo.mint, Halo.cyan))
        Box(Modifier.fillMaxSize().drawBehind { drawRect(fill, size = androidx.compose.ui.geometry.Size(size.width * progress.value, size.height)) })
        val past = remember { derivedStateOf { progress.value > 0.5f } }.value
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            HaloIcon(HIcon.HOLD, if (past) Halo.ground else Halo.mint, 18.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                if (holding && !remember { derivedStateOf { progress.value >= 1f } }.value) stringResource(R.string.hold_continue) else label,
                fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.5.sp,
                color = if (past) Halo.ground else Halo.mint,
            )
        }
    }
}

/** Deterministic gradient avatar from a public key, so a wallet is recognised at a glance. */
@Composable
internal fun Avatar(pubkey: String, size: androidx.compose.ui.unit.Dp) {
    val h = pubkey.hashCode()
    val c1 = Color.hsl(((h and 0xFFFF) % 360).toFloat(), 0.7f, 0.6f)
    val c2 = Color.hsl(((h ushr 16 and 0xFFFF) % 360).toFloat(), 0.8f, 0.45f)
    Box(
        Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(c1, c2))).border(1.dp, Halo.stroke, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(pubkey.take(2), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.34f).sp, color = Halo.ground.copy(alpha = 0.85f)) }
}

/** Amount with a lighter unit: "0.5 SOL" reads as a number, not a code. */
@Composable
internal fun AmountText(prefix: String, d: BalanceDelta, size: androidx.compose.ui.unit.TextUnit, color: Color, weight: FontWeight = FontWeight.Bold, countUp: Boolean = false) {
    // Count-up on reveal: the progress scales the raw amount, and the final frame is the exact value.
    val p = if (countUp) rememberReveal(d.rawAmount) else 1f
    val shown = if (p >= 1f) d else d.copy(rawAmount = (d.rawAmount * p.toDouble()).toLong())
    Text(
        buildAnnotatedString {
            append(prefix + fmtNumber(shown))
            withStyle(SpanStyle(fontSize = size * 0.55f, color = color.copy(alpha = 0.75f), fontWeight = FontWeight.SemiBold)) { append("  " + d.symbol) }
        },
        fontFamily = Sora, fontWeight = weight, fontSize = size, color = color, style = Tabular,
    )
}

// ---- prompts -----------------------------------------------------------------

@Composable
private fun ConnectPrompt(ui: MwaUi.Connect) {
    GlassCard {
        Column {
            DappHero(ui.dApp, stringResource(R.string.origin_unverified))
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.connect_wants), fontFamily = Inter, fontSize = 14.5.sp, color = Halo.ink)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.connect_keys_note), fontFamily = Inter, fontSize = 12.5.sp, color = Halo.muted)
        }
    }
}

@Composable
private fun SignInPrompt(ui: MwaUi.SignInRequest) {
    var showRaw by remember { mutableStateOf(false) }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DappHero(ui.dApp, stringResource(R.string.siws_sub))
            Text(stringResource(R.string.siws_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Halo.ink)
            if (ui.domainMismatch) {
                RiskRow(Risk(RiskFlag.LOOKALIKE_ADDRESS, Severity.DANGER, stringResource(R.string.siws_mismatch_detail, ui.domain, ui.dApp.host ?: "?")))
            }
            StatRow(stringResource(R.string.siws_domain), ui.domain, accent = !ui.domainMismatch)
            ui.statement?.let { Text("“$it”", fontFamily = Inter, fontSize = 13.5.sp, color = Halo.ink) }
            ui.uri?.let { StatRow("URI", it) }
            ui.nonce?.let { StatRow("Nonce", it.take(16)) }
            ui.issuedAt?.let { StatRow(stringResource(R.string.siws_issued), it.replace('T', ' ').take(19)) }
            Text(stringResource(R.string.siws_note), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
            Row(Modifier.clickable { showRaw = !showRaw }, verticalAlignment = Alignment.CenterVertically) {
                Text(if (showRaw) stringResource(R.string.hide_message) else stringResource(R.string.show_message), fontFamily = Inter, fontSize = 12.sp, color = Halo.cyan)
                Spacer(Modifier.width(4.dp)); HaloIcon(if (showRaw) HIcon.CHEVRON_DOWN else HIcon.CHEVRON_RIGHT, Halo.cyan, 14.dp)
            }
            if (showRaw) {
                Box(Modifier.fillMaxWidth().clip(rs(12)).background(Halo.cardSoft).padding(12.dp)) {
                    Text(ui.fullMessage, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, color = Halo.ink)
                }
            }
        }
    }
    if (ui.domainMismatch) {
        Spacer(Modifier.height(12.dp))
        Banner(stringResource(R.string.siws_suspicious), Halo.red)
    }
}

@Composable
private fun MessagePrompt(ui: MwaUi.MessageRequest) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DappHero(ui.dApp, stringResource(R.string.msg_sub))
            Text(
                if (ui.messages.size > 1) stringResource(R.string.msg_many, ui.messages.size)
                else stringResource(R.string.msg_one),
                fontFamily = Inter, fontSize = 14.sp, color = Halo.muted,
            )
            ui.messages.forEachIndexed { i, m ->
                Column {
                    if (ui.messages.size > 1) Text(stringResource(R.string.msg_n, i + 1), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, color = Halo.muted)
                    Box(Modifier.fillMaxWidth().clip(rs(12)).background(Halo.cardSoft).padding(14.dp)) {
                        Text(m, fontFamily = Inter, fontSize = 13.sp, color = Halo.ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountPickPrompt(ui: MwaUi.AccountPick) {
    Text(stringResource(R.string.pick_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Halo.ink)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.pick_sub), fontFamily = Inter, fontSize = 13.sp, color = Halo.muted)
    Spacer(Modifier.height(14.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ui.accounts.forEach { (acc, lamports, tokenCount) ->
            val sol = lamports?.let { fmtSol(it, 4) + " SOL" } ?: "n/d"
            val assets = if (tokenCount > 0) stringResource(R.string.assets_with_tokens, sol, tokenCount) else sol
            val hasFunds = (lamports ?: 0L) > 0L || tokenCount > 0
            Box(
                Modifier.fillMaxWidth().clip(rs(16)).background(Halo.card)
                    .border(1.dp, Halo.stroke, rs(16))
                    .clickable { ui.onPick(acc) }.padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(acc.pubkeyBase58, 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(acc.label ?: shorten(acc.pubkeyBase58), fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Halo.ink)
                        Text(shorten(acc.pubkeyBase58), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                    }
                    Text(assets, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (hasFunds) Halo.mint else Halo.muted, style = Tabular)
                }
            }
        }
    }
}

// ---- sign request --------------------------------------------------------------

/** One node on the flow map / one row in the split list. */
internal data class NodeDest(
    val label: String,
    val address: String?,
    val amountText: String,
    val color: Color,
    val isFee: Boolean,
    val isNewAccount: Boolean,
    val sharePct: Int,
    val share: Double,
    val trust: TrustLevel?,
    val deltaText: String,
)

internal fun destsFor(r: Receipt, danger: Boolean): List<NodeDest> =
    r.distributions.map { s ->
        NodeDest(
            label = s.label ?: shorten(s.address),
            address = s.address,
            amountText = "+" + fmtAmt(s.delta),
            color = when {
                s.isNewAccount -> Halo.cyan          // rent to create an account
                danger && !s.isFee -> Halo.red
                s.isFee -> Halo.amber                // real fee to an existing wallet
                else -> Halo.mint                    // main recipient
            },
            isFee = s.isFee,
            isNewAccount = s.isNewAccount,
            sharePct = (s.share * 100).toInt(),
            share = s.share,
            trust = s.trust,
            deltaText = "+" + fmtAmt(s.delta),
        )
    }

@Composable
private fun SignPrompt(ui: MwaUi.SignRequest) {
    val receipts = ui.receipts
    val danger = receipts.any { it.blocksApproval }   // any DANGER in the bundle blocks all
    var sel by remember { mutableStateOf(0) }
    val selIdx = sel.coerceIn(0, receipts.size - 1)

    // Feel the warning before you read it.
    val ctx = LocalContext.current
    LaunchedEffect(danger) { if (danger) Haptics.warn(ctx) }

    Text(
        (if (ui.count > 1) stringResource(R.string.requests_many, ui.dApp.name, ui.count) else stringResource(R.string.requests_one, ui.dApp.name)) + (if (ui.willSend) stringResource(R.string.and_send) else ""),
        fontFamily = Inter, fontSize = 13.sp, color = Halo.muted,
    )
    Spacer(Modifier.height(4.dp))
    DappMemory(ui.dApp)
    ui.dApp.store?.let { Spacer(Modifier.height(3.dp)); DappStoreLine(it) }
    Spacer(Modifier.height(12.dp))

    // Bundle of >1 tx: show the aggregate first, then a per-tx selector, so the
    // user reviews every transaction — not just the first — before approving all.
    if (ui.count > 1) {
        // The bundle total lives in the fixed bar below; up here only the per-tx browser.
        Banner(stringResource(R.string.bundle_notice, ui.count), Halo.amber, HIcon.INFO)
        Spacer(Modifier.height(10.dp))
        BundleStrip(receipts, danger)
        Spacer(Modifier.height(10.dp))
        TxSelector(receipts, selIdx) { sel = it }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.tx_n_of, selIdx + 1, ui.count),
            fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            color = if (receipts[selIdx].blocksApproval) Halo.red else Halo.ink,
        )
        Spacer(Modifier.height(8.dp))
    }

    // key(selIdx): reset the per-receipt state (open sheet) when switching tx.
    key(selIdx) { SignReceiptBody(receipts[selIdx], ui.cluster) }

}

@Composable
private fun BlockedNotice(text: String) {
    val shape = rs(16)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Halo.redSoft).border(1.dp, Halo.red.copy(alpha = 0.6f), shape).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HaloIcon(HIcon.BLOCK, Halo.red, 26.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stringResource(R.string.blocked_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.red)
            Text(text, fontFamily = Inter, fontSize = 12.sp, color = Halo.red.copy(alpha = 0.9f))
        }
    }
}

/** The full single-receipt view: hero, risks, flow map, split breakdown, details, stats. */
@Composable
internal fun SignReceiptBody(r: Receipt, cluster: String?) {
    val danger = r.blocksApproval
    var sheetAddr by remember { mutableStateOf<NodeDest?>(null) }

    // Every wallet that receives value. Fall back to the single decoded recipient
    // when simulation couldn't itemize the split (e.g. RPC unavailable).
    val dests = destsFor(r, danger).ifEmpty {
        val addr = r.primaryRecipient
        if (addr == null) emptyList() else listOf(
            NodeDest(
                label = r.recipientLabel ?: shorten(addr), address = addr, amountText = heroLine(r),
                color = if (danger) Halo.red else Halo.mint, isFee = false, isNewAccount = false, sharePct = 100, share = 1.0,
                trust = r.recipientTrust, deltaText = heroLine(r),
            ),
        )
    }

    val style = Halo.palette.receiptStyle
    if (style == ReceiptStyle.PAPER) { PaperReceipt(r, dests, danger) { sheetAddr = it } }
    else if (style == ReceiptStyle.TERMINAL) { TerminalReceipt(r, dests, danger) { sheetAddr = it } }
    else {
    Box(Modifier.staggeredEntrance(0, r)) { HeroPay(r, danger) }
    if (r.isSplit) {
        val fees = dests.count { it.isFee }
        val news = dests.count { it.isNewAccount }
        val parts = buildList {
            if (fees > 0) add(stringResource(if (fees == 1) R.string.fees_one else R.string.fees_many, fees))
            if (news > 0) add(stringResource(if (news == 1) R.string.new_accounts_one else R.string.new_accounts_many, news))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.split_on, dests.size) + parts.joinToString(prefix = if (parts.isEmpty()) "" else " · ", separator = " · "),
            fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Halo.amber,
        )
    }

    // Risks right under the headline: the reason to stop must never be below the fold.
    if (r.risks.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Box(Modifier.staggeredEntrance(1, r)) { RisksCard(r.risks) }
    }
    Spacer(Modifier.height(12.dp))

    Box(
        Modifier.staggeredEntrance(2, r).fillMaxWidth().height(if (dests.size > 2) 250.dp else 200.dp).clip(rs(18))
            .background(Halo.cardSoft)
            .background(
                Brush.radialGradient(
                    colors = listOf((if (danger) Halo.red else Halo.cyan).copy(alpha = 0.10f), Color.Transparent),
                    radius = 520f,
                ),
            )
            .border(1.dp, Halo.stroke, rs(18)),
    ) {
        NodeMap(dests = dests, danger = danger) { d -> if (d.address != null) sheetAddr = d }
        Text(
            stringResource(R.string.tap_node),
            fontFamily = Inter, fontSize = 11.sp, color = Halo.muted,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
        )
    }

    Spacer(Modifier.height(14.dp))
    if (dests.size > 1) {
        SplitBreakdown(dests) { d -> if (d.address != null) sheetAddr = d }
        Spacer(Modifier.height(12.dp))
    }
    ReceiptDetails(r, dests.firstOrNull()?.label ?: stringResource(R.string.recipient_lower), showRecipient = dests.size <= 1) {
        dests.firstOrNull { it.address != null }?.let { sheetAddr = it }
    }

    if (r.calls.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        CallsCard(r.calls)
    }
    r.stats?.let {
        Spacer(Modifier.height(12.dp))
        StatsCard(it)
    }
    }

    sheetAddr?.let { d ->
        if (d.address != null) {
            AddressSheet(d.address, d.label.takeIf { r.recipientLabel != null || d.trust == TrustLevel.TRUSTED }, d.trust ?: TrustLevel.NEW, cluster, d.deltaText, d.isNewAccount) { sheetAddr = null }
        }
    }
}

/** Methods decoded from on-chain IDLs: what an "unknown program" is actually asked to do. */
@Composable
internal fun CallsCard(calls: List<com.clearsign.core.ProgramCall>) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.calls_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Halo.cyan)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.calls_sub), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted)
            }
            calls.forEach { c ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.programName ?: programName(c.programId) ?: shorten(c.programId), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.ink)
                        Spacer(Modifier.width(6.dp))
                        Text("·", color = Halo.muted); Spacer(Modifier.width(6.dp))
                        Text(c.method, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = Halo.mint)
                    }
                    c.args.take(8).forEach { (k, v) ->
                        Row(Modifier.padding(start = 10.dp, top = 2.dp)) {
                            Text(k, fontFamily = Mono, fontSize = 11.sp, color = Halo.muted)
                            Spacer(Modifier.width(8.dp))
                            Text(v.let { if (it.length > 44) shorten(it, 6) else it }, fontFamily = Mono, fontSize = 11.sp, color = Halo.ink, style = Tabular, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Store intel for a native dApp: origin (dApp Store / Play / sideload), version, install and update dates. */
@Composable
private fun DappStoreLine(store: StoreInfo) {
    val ctx = LocalContext.current
    val now = System.currentTimeMillis()
    fun ago(t: Long) = android.text.format.DateUtils.getRelativeTimeSpanString(t, now, android.text.format.DateUtils.DAY_IN_MILLIS).toString()
    val origin = when {
        store.fromDappStore -> stringResource(R.string.store_dapp_store)
        store.fromPlay -> stringResource(R.string.store_play)
        else -> stringResource(R.string.store_sideload)
    }
    val trusted = store.fromDappStore || store.fromPlay
    val col = if (trusted) Halo.muted else Halo.amber
    // Store reputation (free Seeker Tracker catalog): rating, publisher, last update.
    val rep by produceState<StoreRep?>(initialValue = null, store.packageName) {
        value = withContext(Dispatchers.IO) { runCatching { StoreReputation.fetch(store.packageName) }.getOrNull() }
    }
    // Tap → the app's listing in the Solana dApp Store (publisher, updates, reviews).
    val openListing = {
        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("solanadappstore://details?id=" + store.packageName))) }
    }
    Column(Modifier.clickable { openListing() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HaloIcon(if (trusted) HIcon.SHIELD_LOCK else HIcon.WARNING, if (trusted) Halo.mint else Halo.amber, 13.dp); Spacer(Modifier.width(5.dp))
            Text(
                origin + " · v" + (store.versionName ?: store.versionCode.toString()) + " (" + store.versionCode + ")",
                fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, color = col, style = Tabular, maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 18.dp)) {
            Text(
                stringResource(R.string.store_dates, ago(store.firstInstall), if (store.neverUpdated) stringResource(R.string.store_never_updated) else ago(store.lastUpdate)),
                fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, style = Tabular,
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.store_open), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, color = Halo.cyan)
            Spacer(Modifier.width(2.dp)); HaloIcon(HIcon.EXTERNAL, Halo.cyan, 10.dp)
        }
        rep?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 18.dp, top = 2.dp)) {
                if (!r.listed) {
                    HaloIcon(HIcon.WARNING, Halo.amber, 12.dp); Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.store_not_listed), fontFamily = Inter, fontSize = 11.sp, color = Halo.amber)
                } else {
                    HaloIcon(HIcon.SPARK, Halo.mint, 12.dp); Spacer(Modifier.width(5.dp))
                    Text(
                        buildString {
                            r.rating?.let { append("★ %.1f".format(java.util.Locale.ROOT, it)) }
                            r.reviews?.let { append(" · " + nf(it.toLong()) + " " + ctx.getString(R.string.store_reviews)) }
                            r.publisher?.let { append(" · " + it + (if (r.verified) " ✓" else "")) }
                        },
                        fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, style = Tabular, maxLines = 1,
                    )
                }
            }
        }
    }
}

/** "3rd signature · since Sep 10" or "first time with this dApp" — the local memory of a dApp. */
@Composable
private fun DappMemory(dApp: DappId) {
    val ctx = LocalContext.current
    val stats = remember(dApp.host, dApp.name) { Ledger.statsFor(ctx, dApp.host, dApp.name) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (stats.count == 0) {
            HaloIcon(HIcon.SPARK, Halo.amber, 13.dp); Spacer(Modifier.width(5.dp))
            Text(stringResource(R.string.dapp_first_time), fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, color = Halo.amber)
        } else {
            val since = android.text.format.DateUtils.formatDateTime(ctx, stats.firstAt ?: 0L, android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_MONTH)
            HaloIcon(HIcon.HISTORY, Halo.muted, 13.dp); Spacer(Modifier.width(5.dp))
            Text(stringResource(R.string.dapp_memory, stats.count + 1, since), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted, style = Tabular)
        }
    }
}

@Composable
internal fun RisksCard(risks: List<Risk>) {
    val top = risks.maxByOrNull { it.severity.ordinal }?.severity ?: Severity.INFO
    val col = sevColor(top)
    val shape = rs(18)
    val breath = rememberBreathState()
    val danger = top == Severity.DANGER
    Column(
        Modifier.fillMaxWidth().clip(shape).background(col.copy(alpha = 0.07f)).border(1.dp, col.copy(alpha = 0.35f), shape)
            .drawWithContent {
                drawContent()
                // Heartbeat: a DANGER card breathes so the eye lands on it.
                if (danger) drawRoundRect(col, alpha = 0.15f + 0.45f * breath.value, cornerRadius = androidx.compose.ui.geometry.CornerRadius(Halo.radius(18).toPx()), style = Stroke(width = 2.dp.toPx()))
            }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (top) { Severity.DANGER -> stringResource(R.string.risk_hdr_danger); Severity.WARN -> stringResource(R.string.risk_hdr_warn); Severity.INFO -> stringResource(R.string.risk_hdr_info) },
                fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = col,
            )
            Spacer(Modifier.weight(1f))
            Text("${risks.size}", fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = col)
        }
        risks.forEach { RiskRow(it) }
    }
}

@Composable
internal fun RiskRow(risk: Risk) {
    val col = sevColor(risk.severity)
    Row(verticalAlignment = Alignment.Top) {
        HaloIcon(riskIcon(risk.flag), col, 20.dp, Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(stringResource(riskTitle(risk.flag)), fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = col)
            Text(risk.detail, fontFamily = Inter, fontSize = 12.sp, color = Halo.ink.copy(alpha = 0.85f))
        }
    }
}

internal fun sevColor(s: Severity) = when (s) { Severity.DANGER -> Halo.red; Severity.WARN -> Halo.amber; Severity.INFO -> Halo.mint }

internal fun riskIcon(f: RiskFlag): HIcon = when (f) {
    RiskFlag.BLOCKED_MALICIOUS -> HIcon.SKULL; RiskFlag.SANCTIONED -> HIcon.BAN; RiskFlag.UNLIMITED_APPROVAL -> HIcon.INFINITY
    RiskFlag.AUTHORITY_CHANGE -> HIcon.KEY; RiskFlag.ACCOUNT_CLOSE -> HIcon.TRASH; RiskFlag.NEW_UNKNOWN_RECIPIENT -> HIcon.SPARK
    RiskFlag.LOOKALIKE_ADDRESS -> HIcon.MASK; RiskFlag.SIMULATION_FAILED -> HIcon.FLASK; RiskFlag.STATE_DRIFT -> HIcon.DRIFT
    RiskFlag.COMMUNITY_FLAGGED -> HIcon.MEGAPHONE; RiskFlag.DRAINS_BALANCE -> HIcon.DRAIN; RiskFlag.WALLET_OWNER_CHANGE -> HIcon.FLAG
    RiskFlag.DURABLE_NONCE -> HIcon.HOURGLASS; RiskFlag.FOREIGN_FEE_PAYER -> HIcon.GIFT; RiskFlag.BRAND_NEW_RECIPIENT -> HIcon.SEEDLING; RiskFlag.LIMITED_APPROVAL -> HIcon.UNLOCK
    RiskFlag.FEE_EXCESSIVE -> HIcon.COINS
    RiskFlag.EXTRA_SIGNERS -> HIcon.PEN
}

internal fun riskTitle(f: RiskFlag): Int = when (f) {
    RiskFlag.BLOCKED_MALICIOUS -> R.string.rt_malicious; RiskFlag.SANCTIONED -> R.string.rt_sanctioned
    RiskFlag.UNLIMITED_APPROVAL -> R.string.rt_unlimited; RiskFlag.AUTHORITY_CHANGE -> R.string.rt_authority
    RiskFlag.ACCOUNT_CLOSE -> R.string.rt_close; RiskFlag.NEW_UNKNOWN_RECIPIENT -> R.string.rt_new_recipient
    RiskFlag.LOOKALIKE_ADDRESS -> R.string.rt_lookalike; RiskFlag.SIMULATION_FAILED -> R.string.rt_simulation
    RiskFlag.STATE_DRIFT -> R.string.rt_drift; RiskFlag.COMMUNITY_FLAGGED -> R.string.rt_community
    RiskFlag.DRAINS_BALANCE -> R.string.rt_drain; RiskFlag.WALLET_OWNER_CHANGE -> R.string.rt_owner_change
    RiskFlag.DURABLE_NONCE -> R.string.rt_nonce; RiskFlag.FOREIGN_FEE_PAYER -> R.string.rt_fee_payer
    RiskFlag.BRAND_NEW_RECIPIENT -> R.string.rt_brand_new; RiskFlag.LIMITED_APPROVAL -> R.string.rt_limited
    RiskFlag.FEE_EXCESSIVE -> R.string.rt_fee_excessive
    RiskFlag.EXTRA_SIGNERS -> R.string.rt_extra_signers
}

/** One line for a bundle: how many tx, the total, the fees — the big number is in the action bar. */
@Composable
private fun BundleStrip(receipts: List<Receipt>, danger: Boolean) {
    val accent = if (danger) Halo.red else Halo.mint
    val totals = aggregateOutflows(receipts)
    val totalFee = receipts.sumOf { it.feeLamports }
    Row(
        Modifier.fillMaxWidth().clip(rs(14)).background(accent.copy(alpha = 0.07f)).border(1.dp, accent.copy(alpha = 0.28f), rs(14)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.bundle_strip_title, receipts.size), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
            Text(
                if (totals.isEmpty()) stringResource(R.string.no_transfer) else totals.joinToString(" · ") { "−" + fmtAmt(it) },
                fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = accent, style = Tabular, maxLines = 2,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(stringResource(R.string.bundle_strip_fees), fontFamily = Inter, fontSize = 10.5.sp, color = Halo.muted)
            Text(fmtSol(totalFee, 6) + " SOL", fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Halo.ink, style = Tabular)
        }
    }
}

/** Chip row to browse each transaction in the bundle; red chip = that tx is DANGER. */
@Composable
private fun TxSelector(receipts: List<Receipt>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        receipts.forEachIndexed { i, r ->
            val isSel = i == selected
            val col = if (r.blocksApproval) Halo.red else if (isSel) Halo.mint else Halo.muted
            val amt = r.outflows.firstOrNull()?.let { " · −" + fmtAmt(it) } ?: ""
            val bg by animateColorAsState(if (isSel) col.copy(alpha = 0.14f) else Color.Transparent, tween(220), label = "bg")
            val bd by animateColorAsState(if (isSel) col else Halo.stroke, tween(220), label = "bd")
            Box(
                Modifier.clip(rs(12))
                    .background(bg)
                    .border(1.dp, bd, rs(12))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    "tx ${i + 1}" + (if (r.blocksApproval) " !" else "") + amt,
                    fontFamily = Sora, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 12.5.sp, color = col, style = Tabular,
                )
            }
        }
    }
}

/** Sum every receipt's outflows per asset — the bundle total. */
private fun aggregateOutflows(receipts: List<Receipt>): List<BalanceDelta> =
    receipts.flatMap { it.outflows }
        .groupBy { Triple(it.mint, it.symbol, it.decimals) }
        .map { (k, ds) -> BalanceDelta(owner = "", mint = k.first, symbol = k.second, decimals = k.third, rawAmount = ds.sumOf { it.rawAmount }) }
        .filter { it.rawAmount != 0L }

@Composable
internal fun SplitBreakdown(dests: List<NodeDest>, onTap: (NodeDest) -> Unit) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.where_funds), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
            // Stacked share bar: the split at a glance.
            val total = dests.sumOf { it.share }.takeIf { it > 0 } ?: 1.0
            Row(Modifier.fillMaxWidth().height(8.dp).clip(rs(4)).background(Halo.cardSoft)) {
                dests.forEach { d ->
                    val w = (d.share / total).toFloat().coerceAtLeast(0.02f)
                    Box(Modifier.fillMaxHeight().weight(w).background(d.color))
                    Spacer(Modifier.width(1.dp))
                }
            }
            dests.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().clickable { onTap(d) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(rs(4)).background(d.color))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.label, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Halo.ink)
                            val badge = when {
                                d.isNewAccount -> stringResource(R.string.badge_new_rent) to Halo.cyan
                                d.isFee -> stringResource(R.string.badge_fee) to Halo.amber
                                else -> null
                            }
                            badge?.let { (txt, col) ->
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    Modifier.clip(rs(999)).border(1.dp, col, rs(999)).padding(horizontal = 7.dp, vertical = 1.dp),
                                ) { Text(txt, color = col, fontFamily = Inter, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
                            }
                        }
                        Text(
                            if (d.isNewAccount) stringResource(R.string.rent_to_create) else stringResource(R.string.share_of_total, d.sharePct),
                            fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted,
                        )
                    }
                    Text(d.amountText, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = d.color, style = Tabular)
                }
            }
        }
    }
}

@Composable
internal fun HeroPay(r: Receipt, danger: Boolean) {
    val accent = if (danger) Halo.red else Halo.mint
    Box(
        Modifier.fillMaxWidth().clip(rs(20))
            .background(accent.copy(alpha = 0.08f))
            .border(1.dp, accent.copy(alpha = 0.32f), rs(20))
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Column {
            Text(stringResource(R.string.pay), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
            Spacer(Modifier.height(6.dp))
            if (r.outflows.isEmpty()) {
                Text(stringResource(R.string.no_transfer), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Halo.ink)
            } else {
                r.outflows.forEachIndexed { i, d -> AmountText("−", d, 32.sp, accent, countUp = i == 0) }
            }
            if (r.inflows.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.receive), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
                Spacer(Modifier.height(2.dp))
                r.inflows.forEach { AmountText("+", it, 20.sp, Halo.cyan, FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun ReceiptDetails(r: Receipt, recipientLabel: String, showRecipient: Boolean, onRecipientTap: () -> Unit) {
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
            if (showRecipient) {
                Row(
                    Modifier.fillMaxWidth().clickable { onRecipientTap() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.to_label), fontFamily = Inter, fontSize = 13.sp, color = Halo.muted)
                    Spacer(Modifier.width(8.dp))
                    Text(recipientLabel, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Halo.ink)
                    Spacer(Modifier.width(8.dp))
                    TrustChip(r.recipientTrust)
                    Spacer(Modifier.weight(1f))
                    HaloIcon(HIcon.CHEVRON_RIGHT, Halo.muted, 18.dp)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.network_fee), fontFamily = Inter, fontSize = 13.sp, color = Halo.muted)
                Spacer(Modifier.weight(1f))
                Text(fmtSol(r.feeLamports, 6) + " SOL", fontFamily = Inter, fontSize = 14.sp, color = Halo.ink, style = Tabular)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.verdict), fontFamily = Inter, fontSize = 13.sp, color = Halo.muted)
                Spacer(Modifier.weight(1f))
                val (txt, col) = when (r.highestSeverity) {
                    Severity.DANGER -> stringResource(R.string.verdict_danger) to Halo.red
                    Severity.WARN -> stringResource(R.string.verdict_warn) to Halo.amber
                    Severity.INFO -> stringResource(R.string.verdict_ok) to Halo.mint
                }
                Text(txt, fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = col)
            }
        }
    }
}

@Composable
internal fun StatsCard(s: TxStats) {
    var open by remember { mutableStateOf(false) }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("STATS", fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Halo.cyan)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (open) stringResource(R.string.tech_details)
                    else "${if (s.version < 0) "legacy" else "v${s.version}"} · ${s.instructionCount} ix · ${s.programs.size} prog" + (s.computeUnits?.let { " · cu ${nf(it)}" } ?: ""),
                    fontFamily = Mono, fontSize = 11.sp, color = Halo.muted,
                )
                Spacer(Modifier.weight(1f))
                HaloIcon(if (open) HIcon.CHEVRON_DOWN else HIcon.CHEVRON_RIGHT, Halo.muted, 18.dp)
            }
            if (open) {
                StatRow(stringResource(R.string.stat_version), if (s.version < 0) "legacy" else "v${s.version}")
                StatRow(stringResource(R.string.stat_instructions), "${s.instructionCount}")
                StatRow(stringResource(R.string.stat_accounts), stringResource(R.string.stat_accounts_fmt, s.accountsTotal, s.writableAccounts, s.signerAccounts))
                StatRow(stringResource(R.string.stat_destinations), "${s.destinationsCount}")
                s.computeUnits?.let { cu ->
                    StatRow("Compute units", buildString {
                        append(nf(cu))
                        s.computeUnitLimit?.let { lim -> append(" / ${nf(lim)}") }
                    })
                    s.computeUnitLimit?.takeIf { it > 0 }?.let { lim ->
                        val frac = (cu.toFloat() / lim).coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth().height(5.dp).clip(rs(3)).background(Halo.cardSoft)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(if (frac > 0.9f) Halo.amber else Halo.cyan))
                        }
                    }
                }
                s.computeUnitPriceMicroLamports?.let { StatRow(stringResource(R.string.stat_priority_price), "${nf(it)} µlamports/CU") }
                s.networkMedianPriceMicroLamports?.let { StatRow(stringResource(R.string.stat_median_price), "${nf(it)} µlamports/CU") }
                StatRow(stringResource(R.string.stat_fee_base), fmtSol(s.baseFeeLamports, 6) + " SOL")
                s.priorityFeeLamports?.let { if (it > 0) StatRow(stringResource(R.string.stat_fee_priority), fmtSol(it, 6) + " SOL") }
                StatRow(stringResource(R.string.stat_fee_total), fmtSol(s.totalFeeLamports, 6) + " SOL", accent = true)
                StatRow(stringResource(R.string.stat_logs), stringResource(R.string.stat_lines, s.logCount))
                if (s.programs.isNotEmpty()) {
                    Text(stringResource(R.string.stat_programs), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                    s.programs.forEach { p ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("·", fontFamily = Inter, fontSize = 12.sp, color = Halo.cyan)
                            Spacer(Modifier.width(6.dp))
                            Text(programName(p) ?: stringResource(R.string.unknown), fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, color = Halo.ink)
                            Spacer(Modifier.width(6.dp))
                            Text(shorten(p), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Halo.muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun StatRow(label: String, value: String, accent: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontFamily = Inter, fontSize = 12.5.sp, color = Halo.muted)
        Spacer(Modifier.weight(1f))
        Text(
            value, fontFamily = Sora, fontWeight = if (accent) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 12.5.sp, color = if (accent) Halo.mint else Halo.ink, style = Tabular,
        )
    }
}

private fun nf(v: Long): String = "%,d".format(Locale.ROOT, v).replace(',', ' ')

internal fun programName(id: String): String? = when (id) {
    "11111111111111111111111111111111" -> "System"
    "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA" -> "SPL Token"
    "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb" -> "Token-2022"
    "ComputeBudget111111111111111111111111111111" -> "Compute Budget"
    "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL" -> "Associated Token"
    "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4" -> "Jupiter v6"
    "JUP4Fb2cqiRUcaTHdrPC8h2gNsA2ETXiPDD33WcGuJB" -> "Jupiter v4"
    "whirLbMiicVdio4qvUfM5KAg6Ct8VwpYzGff3uctyCc" -> "Orca Whirlpool"
    "675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8" -> "Raydium AMM"
    "CAMMCzo5YL8w4VFF8KVHrK22GGUsp5VTaW7grrKgrWqK" -> "Raydium CLMM"
    "metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s" -> "Metaplex Token Metadata"
    "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr" -> "Memo"
    "AddressLookupTab1e1111111111111111111111111" -> "Address Lookup Table"
    else -> null
}

@Composable
internal fun TrustChip(t: TrustLevel) {
    val (txt, col) = when (t) {
        TrustLevel.TRUSTED -> stringResource(R.string.trust_trusted) to Halo.mint
        TrustLevel.KNOWN -> stringResource(R.string.trust_known) to Halo.cyan
        TrustLevel.NEW -> stringResource(R.string.trust_new) to Halo.amber
        TrustLevel.FLAGGED -> stringResource(R.string.trust_flagged) to Halo.red
    }
    Box(
        Modifier.clip(rs(999)).border(1.dp, col, rs(999))
            .padding(horizontal = 9.dp, vertical = 2.dp),
    ) { Text(txt, color = col, fontFamily = Inter, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
}

/** Address in groups of 4, with the shown ends highlighted: what a wallet UI
 *  abbreviates is exactly what a poisoner mimics — ClearSign compares the whole thing. */
@Composable
internal fun AddressText(address: String) {
    val grouped = address.chunked(4).joinToString(" ")
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Halo.mint, fontWeight = FontWeight.Bold)) { append(grouped.take(4)) }
            append(grouped.substring(4, grouped.length - 4))
            withStyle(SpanStyle(color = Halo.mint, fontWeight = FontWeight.Bold)) { append(grouped.takeLast(4)) }
        },
        fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Halo.ink, lineHeight = 20.sp,
    )
}

@Composable
private fun AddressSheet(
    address: String, label: String?, trust: TrustLevel, cluster: String?, deltaText: String,
    isNewAccount: Boolean = false, onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val state = rememberModalBottomSheetState()
    var intel by remember { mutableStateOf<SolanaRpc.WalletIntel?>(null) }
    var rep by remember { mutableStateOf<Reputation.Rep?>(null) }
    var repLoaded by remember { mutableStateOf(false) }
    // The deep scan is the most expensive call (Helius Enhanced). It is a Pro feature
    // and only runs when the user asks, so it never burns quota on its own.
    var trace by remember { mutableStateOf<AddressTrace.Trace?>(null) }
    var traceRunning by remember { mutableStateOf(false) }
    var traceRan by remember { mutableStateOf(false) }
    var labelInput by remember { mutableStateOf(label ?: "") }
    var saved by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(address) {
        intel = withContext(Dispatchers.IO) { SolanaRpc.walletIntel(SolanaRpc.urlFor(cluster), address) }
    }
    LaunchedEffect(address) {
        rep = withContext(Dispatchers.IO) { Reputation.fetch(address) }; repLoaded = true
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Halo.ground2) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(address, 34.dp)
                Spacer(Modifier.width(10.dp))
                Text(if (isNewAccount) stringResource(R.string.sheet_new_account) else stringResource(R.string.sheet_recipient), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.ink)
                Spacer(Modifier.width(10.dp)); TrustChip(trust)
            }
            if (isNewAccount) {
                Text(
                    stringResource(R.string.sheet_rent_note),
                    fontFamily = Inter, fontSize = 12.sp, color = Halo.cyan,
                )
            }
            Box(Modifier.fillMaxWidth().clip(rs(12)).background(Halo.cardSoft).padding(14.dp)) { AddressText(address) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(if (copied) stringResource(R.string.copied) else stringResource(R.string.copy), Modifier.weight(1f), icon = if (copied) HIcon.CHECK else HIcon.COPY, tint = if (copied) Halo.mint else Halo.muted) { clip.setText(AnnotatedString(address)); copied = true }
                GhostButton("Solscan", Modifier.weight(1f), icon = HIcon.EXTERNAL) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(solscanUrl(address, cluster)))) }
                }
            }
            WalletIntelBlock(intel)
            if (AddressTrace.available) {
                val scope = rememberCoroutineScope()
                TraceBlock(trace, traceRunning, traceRan, Pro.isPro.value) {
                    traceRunning = true
                    scope.launch { trace = withContext(Dispatchers.IO) { runCatching { AddressTrace.scan(address) }.getOrNull() }; traceRunning = false; traceRan = true }
                }
            }
            ReputationBlock(rep, repLoaded)
            Row(Modifier.fillMaxWidth()) {
                Text(if (isNewAccount) stringResource(R.string.sheet_rent_label) else stringResource(R.string.sheet_receives), fontFamily = Inter, fontSize = 13.sp, color = Halo.muted)
                Spacer(Modifier.weight(1f))
                Text(deltaText, fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (isNewAccount) Halo.cyan else Halo.mint, style = Tabular)
            }
            OutlinedTextField(
                value = labelInput, onValueChange = { labelInput = it; saved = false },
                singleLine = true, label = { Text(stringResource(R.string.contact_name)) }, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Halo.mint, unfocusedBorderColor = Halo.stroke, cursorColor = Halo.mint,
                    focusedTextColor = Halo.ink, unfocusedTextColor = Halo.ink,
                    focusedLabelColor = Halo.mint, unfocusedLabelColor = Halo.muted,
                ),
            )
            PrimaryButton(if (saved) stringResource(R.string.saved) else stringResource(R.string.save_contact), danger = false, icon = if (saved) HIcon.CHECK else null) {
                Contacts.saveContact(ctx, address, labelInput); saved = true
            }
        }
    }
}

/** On-chain reputation-from-usage for a counterparty: history, age, kind, balance. */
/** Address scan: what this wallet does, with whom, and the patterns that matter. */
@Composable
private fun TraceBlock(t: AddressTrace.Trace?, running: Boolean, ran: Boolean, isPro: Boolean, onRun: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(rs(14)).background(Halo.cardSoft).border(1.dp, Halo.stroke, rs(14)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HaloIcon(HIcon.SCAN, Halo.cyan, 14.dp); Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.trace_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Halo.cyan)
            Spacer(Modifier.weight(1f))
            if (running) { Text(stringResource(R.string.analyzing), fontFamily = Mono, fontSize = 11.sp, color = Halo.muted); Spacer(Modifier.width(4.dp)); BlinkCaret(Halo.cyan, 11.dp, 5.dp) }
        }
        when {
            !ran && !running -> {
                if (isPro) GhostButton(stringResource(R.string.trace_run), icon = HIcon.SCAN, tint = Halo.cyan) { onRun() }
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    HaloIcon(HIcon.GEM, Halo.cyan, 13.dp); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.trace_pro), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
                }
            }
            running -> {}
            t == null || t.txCount == 0 -> Text(stringResource(R.string.trace_none), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted)
            else -> {
                val kinds = t.kinds.take(4).joinToString(" · ") { (k, n) -> "$n " + kindName(ctx, k) }
                StatRow(stringResource(R.string.trace_recent, t.txCount), kinds)
                if (t.programs.isNotEmpty()) StatRow(stringResource(R.string.trace_programs), t.programs.joinToString(", "))
                StatRow(stringResource(R.string.trace_parties), stringResource(R.string.trace_parties_fmt, t.counterparties, t.senders, t.receivers))
                t.oldest?.let { o ->
                    val span = android.text.format.DateUtils.getRelativeTimeSpanString(o * 1000, System.currentTimeMillis(), android.text.format.DateUtils.HOUR_IN_MILLIS).toString()
                    StatRow(stringResource(R.string.trace_window), span)
                }
                if (t.exchangeFunders.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    HaloIcon(HIcon.SHIELD_LOCK, Halo.mint, 12.dp); Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.trace_exchange, t.exchangeFunders.joinToString(", ")), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.mint)
                }
                if (t.fanOut) Row(verticalAlignment = Alignment.CenterVertically) {
                    HaloIcon(HIcon.INFO, Halo.cyan, 12.dp); Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.trace_fanout), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.cyan)
                }
                if (t.collector) Row(verticalAlignment = Alignment.CenterVertically) {
                    HaloIcon(HIcon.DRAIN, Halo.amber, 12.dp); Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.trace_collector), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, color = Halo.amber)
                }
            }
        }
    }
}

private fun kindName(ctx: android.content.Context, k: String): String = when (k) {
    "TRANSFER" -> ctx.getString(R.string.k_transfer); "SWAP" -> ctx.getString(R.string.k_swap); "BURN" -> ctx.getString(R.string.k_burn)
    "NFT_SALE", "NFT_LISTING", "NFT_MINT", "NFT_BID", "NFT_CANCEL_LISTING" -> "NFT"; "STAKE_SOL", "UNSTAKE_SOL" -> ctx.getString(R.string.k_stake)
    "TOKEN_MINT" -> ctx.getString(R.string.k_mint); "UNKNOWN" -> ctx.getString(R.string.k_other)
    else -> k.lowercase().replace('_', ' ')
}

@Composable
private fun WalletIntelBlock(intel: SolanaRpc.WalletIntel?) {
    if (intel == null) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.intel_title), fontFamily = Inter, fontSize = 13.sp, color = Halo.muted)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.analyzing), fontFamily = Inter, fontSize = 13.sp, color = Halo.muted)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (intel.isBrandNew) {
            Box(
                Modifier.fillMaxWidth().clip(rs(12))
                    .background(Halo.amber.copy(alpha = 0.10f))
                    .border(1.dp, Halo.amber.copy(alpha = 0.5f), rs(12)).padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    HaloIcon(HIcon.WARNING, Halo.amber, 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.intel_brand_new_note),
                        fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = Halo.amber,
                    )
                }
            }
        }
        StatRow(stringResource(R.string.intel_tx), if (intel.capped) "${intel.sigCount}+" else "${intel.sigCount}")
        StatRow(stringResource(R.string.intel_activity), ageText(intel.ageDays, intel.sigCount, intel.capped))
        StatRow(stringResource(R.string.intel_kind), kindText(intel.kind))
        StatRow(stringResource(R.string.intel_balance), intel.lamports?.let { fmtSol(it, 4) + " SOL" } ?: "—")
    }
}

/** Community reputation for the address — the on-chain "Trustpilot" verdict. */
@Composable
private fun ReputationBlock(rep: Reputation.Rep?, loaded: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.rep_title), fontFamily = Inter, fontSize = 13.sp, color = Halo.muted)
        Spacer(Modifier.weight(1f))
        when {
            !loaded -> Text("…", fontFamily = Inter, fontSize = 13.sp, color = Halo.muted)
            rep == null || rep.verdict == Reputation.Verdict.NONE ->
                Text(stringResource(R.string.rep_none), fontFamily = Inter, fontSize = 13.sp, color = Halo.muted)
            else -> {
                val (txt, col) = when (rep.verdict) {
                    Reputation.Verdict.TRUSTED -> stringResource(R.string.rep_trusted) to Halo.mint
                    Reputation.Verdict.FLAGGED -> stringResource(R.string.rep_flagged) to Halo.red
                    Reputation.Verdict.MIXED -> stringResource(R.string.rep_mixed) to Halo.amber
                    Reputation.Verdict.NONE -> "" to Halo.muted
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(txt, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = col)
                    Text(
                        stringResource(R.string.rep_voters_fmt, rep.voters, "%.3f".format(Locale.ROOT, rep.stakedSol)),
                        fontFamily = Inter, fontSize = 11.sp, color = Halo.muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun ageText(days: Long?, sigCount: Int, capped: Boolean): String {
    val prefix = stringResource(if (capped) R.string.active_since_at_least else R.string.active_since)
    return when {
        sigCount == 0 -> stringResource(R.string.age_never)
        days == null -> stringResource(R.string.age_unknown)
        days <= 0 -> stringResource(R.string.age_today)
        days < 60 -> stringResource(R.string.age_days, prefix, days)
        days < 730 -> stringResource(R.string.age_months, prefix, days / 30)
        else -> stringResource(R.string.age_years, prefix, days / 365)
    }
}

@Composable
private fun kindText(k: SolanaRpc.WalletIntel.Kind): String = stringResource(
    when (k) {
        SolanaRpc.WalletIntel.Kind.WALLET -> R.string.kind_wallet
        SolanaRpc.WalletIntel.Kind.TOKEN_ACCOUNT -> R.string.kind_token_account
        SolanaRpc.WalletIntel.Kind.PROGRAM -> R.string.kind_program
        SolanaRpc.WalletIntel.Kind.PROGRAM_OWNED -> R.string.kind_pda
        SolanaRpc.WalletIntel.Kind.EMPTY -> R.string.kind_empty
        SolanaRpc.WalletIntel.Kind.UNKNOWN -> R.string.kind_unknown
    },
)

// ---- node map (all geometry in dp → px, so it looks the same on every density) ----

@Composable
internal fun NodeMap(dests: List<NodeDest>, danger: Boolean, onTap: (NodeDest) -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val sora = remember { runCatching { ResourcesCompat.getFont(ctx, R.font.sora) }.getOrNull() ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    // The particles flow for a while, then rest: a receipt left open must not burn a frame budget forever.
    var animate by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(9_000); animate = false }
    val tState: State<Float>; val pulseState: State<Float>
    if (animate) {
        val flow = rememberInfiniteTransition(label = "flow")
        tState = flow.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "t")
        pulseState = flow.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "p")
    } else {
        tState = remember { mutableStateOf(0.62f) }; pulseState = remember { mutableStateOf(0f) }
    }
    val t by tState
    val pulse by pulseState
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(dests) { reveal.snapTo(0f); reveal.animateTo(1f, tween(700)) }
    val labelPaint = remember { Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = sora } }
    val amtPaint = remember { Paint().apply { isAntiAlias = true; textAlign = Paint.Align.RIGHT; typeface = sora } }
    val youLabel = stringResource(R.string.you)
    val shown = dests.take(5) // keep the canvas legible; the full list is in the breakdown
    val n = shown.size.coerceAtLeast(1)
    val dp = { v: Float -> with(density) { v.dp.toPx() } }
    val nodeR = dp(if (n > 3) 14f else 17f)
    val tapR = dp(28f)

    fun destY(h: Float, i: Int) = if (n == 1) h * 0.5f else h * (0.2f + 0.6f * (i / (n - 1f)))

    Canvas(
        Modifier.fillMaxSize().pointerInput(shown) {
            detectTapGestures { pos ->
                shown.forEachIndexed { i, d ->
                    if (hypot(pos.x - size.width * 0.82f, pos.y - destY(size.height.toFloat(), i)) < tapR) onTap(d)
                }
            }
        },
    ) {
        val w = size.width; val h = size.height
        val you = Offset(w * 0.17f, h * 0.5f)
        val stroke = dp(1.5f)
        shown.forEachIndexed { i, d ->
            val dest = Offset(w * 0.82f, destY(h, i))
            val ctrl = Offset((you.x + dest.x) / 2f, (you.y + dest.y) / 2f - dp(22f))
            val edge = Path().apply { moveTo(you.x, you.y); quadraticBezierTo(ctrl.x, ctrl.y, dest.x, dest.y) }
            val alpha = reveal.value
            drawPath(edge, color = d.color.copy(alpha = 0.4f * alpha), style = if (d.isFee) Stroke(width = stroke, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(dp(5f), dp(5f)))) else Stroke(width = stroke))
            for (k in 0..2) {
                val p = ((t + k / 3f) % 1f) * alpha; val mt = 1 - p
                val px = mt * mt * you.x + 2 * mt * p * ctrl.x + p * p * dest.x
                val py = mt * mt * you.y + 2 * mt * p * ctrl.y + p * p * dest.y
                drawCircle(d.color.copy(alpha = 0.22f * alpha), dp(4f), Offset(px, py))
                drawCircle(d.color, dp(1.6f), Offset(px, py))
            }
            // Amount sits just left of its node, so five edges never pile up mid-canvas.
            amtPaint.color = d.color.copy(alpha = alpha).toArgb(); amtPaint.textSize = dp(if (n > 3) 11f else 13f)
            drawContext.canvas.nativeCanvas.drawText(d.amountText, dest.x - nodeR - dp(10f), dest.y + dp(4f), amtPaint)
            drawMapNode(dest, d.color, d.label, labelPaint, pulse, i, nodeR, dp, alpha)
        }
        drawMapNode(you, Halo.mint, youLabel, labelPaint, pulse, 9, dp(17f), dp, 1f)
    }
}

private fun DrawScope.drawMapNode(
    c: Offset, color: Color, label: String, paint: Paint, pulse: Float, seed: Int, r: Float, dp: (Float) -> Float, alpha: Float,
) {
    val p = sin(pulse + seed) * dp(1.5f)
    drawCircle(color.copy(alpha = 0.06f * alpha), r + dp(12f) + p, c)
    drawCircle(color.copy(alpha = 0.10f * alpha), r + dp(7f) + p, c)
    drawCircle(color.copy(alpha = 0.16f * alpha), r + dp(3f) + p, c)
    drawCircle(Halo.ground.copy(alpha = 0.92f), r, c)
    drawCircle(color.copy(alpha = alpha), r, c, style = Stroke(width = dp(2f)))
    paint.color = color.copy(alpha = alpha).toArgb(); paint.textSize = dp(11.5f)
    drawContext.canvas.nativeCanvas.drawText(label, c.x, c.y + r + dp(15f), paint)
}

// ---- done / error ------------------------------------------------------------------

@Composable
private fun DoneScreen(ui: MwaUi.Done) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    LaunchedEffect(Unit) { Haptics.success(ctx) }
    val draw = remember { Animatable(0f) }
    LaunchedEffect(Unit) { draw.animateTo(1f, tween(650)) }
    // Hand control back to the dApp after a beat; the session stays alive for its next request.
    val activity = ctx as? MobileWalletAdapterActivity
    var left by remember { mutableStateOf(3) }
    LaunchedEffect(Unit) {
        while (left > 0) { kotlinx.coroutines.delay(1000); left-- }
        activity?.backToDapp()
    }
    val stroke = with(LocalDensity.current) { 4.dp.toPx() }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(96.dp)) {
                val r = size.minDimension / 2
                drawCircle(Brush.radialGradient(listOf(Halo.mint.copy(alpha = 0.38f * draw.value), Color.Transparent), radius = r * 1.5f), r * 1.5f)
                drawCircle(Halo.mint.copy(alpha = 0.10f * draw.value), r)
                drawCircle(Halo.mint.copy(alpha = 0.8f), r * 0.72f, style = Stroke(width = stroke))
                // Check mark drawn progressively.
                val p1 = Offset(r * 0.66f, r * 1.02f); val p2 = Offset(r * 0.9f, r * 1.26f); val p3 = Offset(r * 1.36f, r * 0.78f)
                val path = Path().apply {
                    moveTo(p1.x, p1.y)
                    val a = (draw.value * 2f).coerceIn(0f, 1f); lineTo(p1.x + (p2.x - p1.x) * a, p1.y + (p2.y - p1.y) * a)
                    val b = (draw.value * 2f - 1f).coerceIn(0f, 1f); if (b > 0) lineTo(p2.x + (p3.x - p2.x) * b, p2.y + (p3.y - p2.y) * b)
                }
                drawPath(path, Halo.mint, style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
        Text(ui.message, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Halo.ink, modifier = Modifier.padding(horizontal = 8.dp))
        PrimaryButton(stringResource(R.string.back_to_dapp, left), danger = false, icon = HIcon.CHEVRON_RIGHT) { activity?.backToDapp() }
        ui.signature?.let { sig ->
            Box(
                Modifier.fillMaxWidth().clip(rs(16)).background(Halo.card).border(1.dp, Halo.stroke, rs(16)).padding(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.signature_hdr), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
                    Text(shorten(sig, 10), fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, color = Halo.ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton(stringResource(R.string.copy), Modifier.weight(1f), icon = HIcon.COPY) { clip.setText(AnnotatedString(sig)) }
                        GhostButton("Solscan", Modifier.weight(1f), icon = HIcon.EXTERNAL) {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(solscanTxUrl(sig, ui.cluster)))) }
                        }
                    }
                }
            }
        }
        // Hardware attestation: the signature came from the Seeker's Seed Vault TEE,
        // never from software — the whole point of signing on this device.
        Box(
            Modifier.fillMaxWidth().clip(rs(16))
                .background(Halo.card).border(1.dp, Halo.stroke, rs(16)).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HaloIcon(HIcon.SHIELD_LOCK, Halo.mint, 26.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.done_hw_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Halo.ink)
                    Text(stringResource(R.string.done_hw_sub), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted)
                }
            }
        }
    }
}

@Composable
private fun ErrorScreen(message: String) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(rs(16)).background(Halo.redSoft).border(1.dp, Halo.red.copy(alpha = 0.5f), rs(16)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HaloIcon(HIcon.WARNING, Halo.red, 26.dp)
            Spacer(Modifier.width(12.dp))
            Text(message, color = Halo.red, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

// ---- formatting ---------------------------------------------------------------------

/** "0.5", "1 234.56", "0.000005": no trailing zeros, thin-space thousands, never scientific. */
internal fun fmtNumber(d: BalanceDelta): String {
    val v = abs(d.uiAmount)
    val s = String.format(Locale.ROOT, "%,.${minOf(d.decimals, 6)}f", v)
    val trimmed = if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    return trimmed.replace(',', ' ')
}

internal fun fmtAmt(d: BalanceDelta): String = fmtNumber(d) + " " + d.symbol
internal fun fmtSol(lamports: Long, decimals: Int): String {
    val s = String.format(Locale.ROOT, "%,.${decimals}f", lamports / 1_000_000_000.0)
    return (if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s).replace(',', ' ').ifEmpty { "0" }
}

internal fun heroLine(r: Receipt): String = r.outflows.firstOrNull()?.let { "−" + fmtAmt(it) } ?: "—"
internal fun solscanUrl(a: String, cluster: String?): String =
    "https://solscan.io/account/$a" + if (cluster?.contains("devnet") == true) "?cluster=devnet" else ""
internal fun solscanTxUrl(sig: String, cluster: String?): String =
    "https://solscan.io/tx/$sig" + if (cluster?.contains("devnet") == true) "?cluster=devnet" else ""

internal fun shorten(a: String, ends: Int = 4): String =
    if (a.length <= ends * 2) a else "${a.take(ends)}…${a.takeLast(ends)}"
