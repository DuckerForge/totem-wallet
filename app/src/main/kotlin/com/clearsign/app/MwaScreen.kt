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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
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

/**
 * In che lingua parla l'app, non in che lingua parla il telefono.
 *
 * Chiedeva `Locale.getDefault()`, cioe' il sistema. Android pero' lascia
 * scegliere la lingua **per singola app**, e allora le due si separano: schermi
 * in inglese perche' le risorse rispondono in inglese, e in mezzo una riga
 * italiana dello scontrino perche' il motore aveva chiesto al sistema. Vista dal
 * vivo: "L'agente ha dichiarato..." dentro un'app tutta inglese.
 *
 * `getAdjustedDefault` e' la lista che Android usa davvero per risolvere le
 * risorse, sistemata con la scelta per app davanti. La stessa che decide le
 * scritte decide anche le frasi dello scontrino.
 */
fun deviceLocaleTag(): String {
    val l = androidx.core.os.LocaleListCompat.getAdjustedDefault()[0] ?: Locale.getDefault()
    return when (l.language) { "it" -> "it"; "es" -> "es"; else -> "en" }
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
    // Una fase sola per tutti i bordi vivi, mossa a fotogramma solo sulle
    // palette che li hanno. I bordi la leggono in draw: vedi `haloBorder`.
    val living = Halo.palette.livingStroke
    androidx.compose.runtime.LaunchedEffect(living) {
        if (!living) return@LaunchedEffect
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            androidx.compose.runtime.withFrameNanos { t ->
                LivingStroke.phase.floatValue = (((t - start) / 1_000_000L) % LivingStroke.PERIOD_MS) / LivingStroke.PERIOD_MS.toFloat()
            }
        }
    }
    MaterialTheme(colorScheme = HaloScheme) {
        androidx.compose.material3.ProvideTextStyle(androidx.compose.material3.LocalTextStyle.current.copy(letterSpacing = Halo.palette.fonts.tracking.sp)) { content() }
    }
}

@Composable
fun MwaScreen(ui: MwaUi) {
    HaloRoot {
        // Un errore non e' un pericolo, e vanno tenuti separati.
        //
        // Qui dentro `Error` faceva scattare tutto l'apparato dell'allarme:
        // schermo rosso, "Rischio rilevato" sotto il nome, e la vibrazione di
        // avvertimento. Per una dApp che non si e' connessa. Non e' successo
        // niente di pericoloso: e' solo non successo niente. Gridare al lupo su
        // un non evento e' il modo piu' rapido di insegnare a qualcuno a
        // ignorare il rosso il giorno che il lupo c'e' davvero.
        val problem = ui is MwaUi.Error
        val danger = (ui is MwaUi.SignRequest && ui.receipts.any { it.blocksApproval }) ||
            (ui is MwaUi.SignInRequest && ui.domainMismatch)
        val haloColor by animateFloatAsState(if (danger) 1f else 0f, tween(600), label = "halo")
        val accent = lerp(if (problem) Halo.amber else Halo.cyan, Halo.red, haloColor)
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
                Header(danger = danger, problem = problem, dApp = ui.dAppOrNull)
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
private fun Header(danger: Boolean, problem: Boolean, dApp: DappId?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).clip(rs(10))
                .background(Brush.linearGradient(listOf(Halo.mint, Halo.cyan))),
            contentAlignment = Alignment.Center,
        ) { HaloIcon(HIcon.AGENT, Halo.ground, 24.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_name), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = Halo.ink)
            Text(
                when {
                    danger -> stringResource(R.string.header_danger)
                    problem -> stringResource(R.string.header_problem)
                    else -> stringResource(R.string.tagline)
                },
                fontFamily = Inter, fontSize = 11.5.sp,
                color = if (danger) Halo.red else if (problem) Halo.amber else Halo.muted,
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
        Modifier.size(size).clip(CircleShape).background(Halo.cardSoft).haloBorder(CircleShape),
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
        Modifier.clip(rs(999)).background(Halo.cardSoft).haloBorder(rs(999))
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
            AgentOrigin(dApp)
            dApp.store?.let { Spacer(Modifier.height(3.dp)); DappStoreLine(it) }
        }
    }
}

// ---- building blocks --------------------------------------------------------

/**
 * The hairline around a card.
 *
 * Normally a flat colour. On a theme that asks for it, the same hairline becomes
 * Solana's purple and green sliding along the edge — one gradient translated by
 * exactly its own width per cycle, so it flows without ever showing a seam.
 */

@Composable
internal fun GlassCard(content: @Composable () -> Unit) {
    val slot = LocalEntrance.current?.let { c -> remember { c.getAndIncrement() } }
    Box(
        (if (slot != null) Modifier.staggeredEntrance(slot.coerceAtMost(7)) else Modifier).fillMaxWidth()
            .clip(rs(22))
            .background(Halo.card)
            .haloBorder(rs(22))
            // 16 and not 20. The screen already keeps its own margin outside this
            // card, so every line of text was starting 40dp in from the edge on a
            // phone that is 411dp wide — a tenth of the screen on each side, spent
            // on nothing, and it reads as cramped rather than as roomy.
            .padding(16.dp),
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
internal fun PrimaryButton(label: String, danger: Boolean, enabled: Boolean = true, icon: HIcon? = null, fillWidth: Boolean = true, onClick: () -> Unit) {
    val shape = rs(16)
    // These two buttons used to force `fillMaxWidth` on themselves, which is right
    // for the bottom of a sheet and wrong inside a row: the button ate the row and
    // squeezed whatever shared it — a title, a text field — down to nothing.
    val mod = (if (fillWidth) Modifier.fillMaxWidth() else Modifier).height(54.dp).clip(shape)
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
internal fun GhostButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: HIcon? = null,
    tint: Color = Halo.muted,
    fillWidth: Boolean = true,
    /**
     * Quarantotto di suo, cinquantaquattro quando sta accanto a un pieno.
     *
     * I due tipi di bottone hanno altezze diverse di proposito: uno pieno pesa
     * di piu' anche fisicamente. Messi fianco a fianco nella stessa riga pero'
     * quella differenza non legge come gerarchia, legge come sbaglio, e in quella
     * riga sono due cose che si fanno allo stesso modo.
     */
    height: androidx.compose.ui.unit.Dp = 48.dp,
    onClick: () -> Unit,
) {
    val shape = rs(16)
    val src = remember { MutableInteractionSource() }
    Row(
        // Outlined in its own colour, a little faded: a second-rank action,
        // not a second card. The living stroke belongs to the cards.
        modifier.pressScale(src).then(if (fillWidth) Modifier.fillMaxWidth() else Modifier).height(height).clip(shape).haloBorder(shape, color = tint.copy(alpha = 0.42f)).clickable(interactionSource = src, indication = null) { onClick() },
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
        Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(c1, c2))).haloBorder(CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(pubkey.take(2), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.34f).sp, color = Halo.ground.copy(alpha = 0.85f)) }
}

/**
 * Amount with a lighter unit: "0.5 SOL" reads as a number, not a code.
 *
 * The same one-line rule holds for a number that comes from a quote rather than
 * from a balance delta.
 */
@Composable
private fun FitAmount(number: String, symbol: String, size: androidx.compose.ui.unit.TextUnit, color: Color, weight: FontWeight) {
    var actual by remember(number, symbol) { mutableStateOf(size) }
    Text(
        buildAnnotatedString {
            append(number)
            withStyle(SpanStyle(fontSize = actual * 0.55f, color = color.copy(alpha = 0.75f), fontWeight = FontWeight.SemiBold)) { append("  " + symbol) }
        },
        fontFamily = Sora, fontWeight = weight, fontSize = actual, color = color, style = Tabular,
        maxLines = 1, softWrap = false,
        onTextLayout = { r -> if (r.hasVisualOverflow && actual.value > 11f) actual = actual * 0.88f },
    )
}

@Composable
internal fun AmountText(
    prefix: String,
    d: BalanceDelta,
    size: androidx.compose.ui.unit.TextUnit,
    color: Color,
    weight: FontWeight = FontWeight.Bold,
    countUp: Boolean = false,
    /**
     * Shrink until it fits on one line.
     *
     * An amount is not a sentence and must never wrap: "−0.303824 SOL" broken
     * after the fifth decimal put a lonely "4 SOL" on the next line, which reads
     * as a second number. Where the room is fixed and the number is not, the type
     * gives way, not the value.
     */
    fit: Boolean = false,
) {
    // Count-up on reveal: the progress scales the raw amount, and the final frame is the exact value.
    val p = if (countUp) rememberReveal(d.rawAmount) else 1f
    val shown = if (p >= 1f) d else d.copy(rawAmount = (d.rawAmount * p.toDouble()).toLong())
    var actual by remember(d.rawAmount, d.symbol, size) { mutableStateOf(size) }
    Text(
        buildAnnotatedString {
            append(prefix + fmtNumber(shown))
            withStyle(SpanStyle(fontSize = actual * 0.55f, color = color.copy(alpha = 0.75f), fontWeight = FontWeight.SemiBold)) { append("  " + d.symbol) }
        },
        fontFamily = Sora, fontWeight = weight, fontSize = actual, color = color, style = Tabular,
        maxLines = if (fit) 1 else Int.MAX_VALUE,
        softWrap = !fit,
        onTextLayout = { r ->
            // A couple of frames of stepping down, then it settles. The floor stops
            // it turning into something nobody can read.
            if (fit && r.hasVisualOverflow && actual.value > 11f) actual = actual * 0.88f
        },
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
                    .haloBorder(rs(16))
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
    // A share with neither a name nor an address is not a destination. One of
    // those was drawing an empty row inside "where the funds go", which reads as
    // a missing piece of the answer rather than as nothing at all.
    r.distributions.filter { it.label != null || it.address.isNotBlank() }.map { s ->
        NodeDest(
            label = s.label?.takeIf { it.isNotBlank() } ?: shorten(s.address),
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
    // Which transactions the user has actually opened — so a bundle can nudge them to review each.
    val viewed = remember { mutableStateListOf(0) }

    // Feel the warning before you read it.
    val ctx = LocalContext.current
    LaunchedEffect(danger) { if (danger) Haptics.warn(ctx) }

    // Without the name: the card directly below is the dApp, with its name in
    // bold and its badges. Saying it here too made the same word appear three
    // times in the first four lines of the screen.
    Text(
        (if (ui.count > 1) stringResource(R.string.requests_many, ui.count) else stringResource(R.string.requests_one)) + (if (ui.willSend) stringResource(R.string.and_send) else ""),
        fontFamily = Inter, fontSize = 13.sp, color = Halo.muted,
    )
    Spacer(Modifier.height(4.dp))

    // The answer to the only question this screen raises: why me, why now. The
    // agent signs on its own under the rules you set; when it cannot, the rule
    // that stopped it belongs at the top, not inside a risk row further down.
    ui.askedWhy?.let { why ->
        Column(
            Modifier.fillMaxWidth().clip(rs(14)).background(Halo.amber.copy(alpha = 0.10f))
                .border(1.dp, Halo.amber.copy(alpha = 0.45f), rs(14)).padding(14.dp),
        ) {
            Text(
                stringResource(R.string.gate_why_title),
                fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.amber,
            )
            Spacer(Modifier.height(4.dp))
            Text(why, fontFamily = Inter, fontSize = 13.sp, color = Halo.ink, lineHeight = 18.sp)
        }
        Spacer(Modifier.height(10.dp))
    }

    DappMemory(ui.dApp)
    AgentOrigin(ui.dApp)
    ui.dApp.store?.let { Spacer(Modifier.height(3.dp)); DappStoreLine(it) }
    Spacer(Modifier.height(12.dp))

    // Bundle of >1 tx: show the aggregate first, then a per-tx selector, so the
    // user reviews every transaction — not just the first — before approving all.
    if (ui.count > 1) {
        // Everything a bundle needs to say, said once each. It used to open with a
        // banner ("this request signs 3 transactions at once"), then a card with
        // the total, then the chips, then "Transaction 1 of 3" — four ways of
        // saying three, before a single amount appeared. The chips are the
        // browser, the fixed bar below is the total, and the only fact neither of
        // them carries is the fee across all three, which rides with the hint.
        TxSelector(receipts, selIdx, viewed.toSet()) { sel = it; if (it !in viewed) viewed.add(it) }
        Spacer(Modifier.height(6.dp))
        val allSeen = viewed.size >= receipts.size
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (allSeen) stringResource(R.string.bundle_all_seen) else stringResource(R.string.bundle_tap_hint),
                fontFamily = Inter, fontSize = 11.sp, color = if (allSeen) Halo.mint else Halo.amber,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.bundle_fees_all, fmtSol(receipts.sumOf { it.feeLamports }, 6)),
                fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, style = Tabular,
            )
        }
        Spacer(Modifier.height(10.dp))
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

/**
 * The two legs of a swap as the *quote* knows them.
 *
 * Only for drawing, and only when the simulation could not tell us: a route that
 * fails on chain returns a receipt with no legs at all, and the screen then had
 * nothing to show but "no funds transferred" over an empty picture. What you
 * were trying to do is still worth drawing. What will actually happen is the
 * risks' job, and they sit right underneath.
 */
internal data class SwapPair(
    val outMint: String, val outSymbol: String, val outUi: String,
    val inMint: String, val inSymbol: String, val inUi: String,
)

/** The full single-receipt view: hero, risks, flow map, split breakdown, details, stats. */
@Composable
internal fun SignReceiptBody(
    r: Receipt,
    cluster: String?,
    pair: SwapPair? = null,
    /**
     * The plain version, for a screen that has already explained the trade.
     *
     * The swap sheet grades the coin on the form and names the route in its
     * summary, so repeating both here is the same sentence twice. It also drops
     * the parts written for somebody debugging a transaction: the decoded program
     * calls, and percentages of a total that does not mean anything in a swap
     * (the pool receives more SOL than you paid, because the route passes through
     * it more than once, and "129% of the total" is not a fact anybody can use).
     */
    plain: Boolean = false,
    /**
     * Draw the headline amount at the top.
     *
     * Off where the screen has already said the number: PayOverlay carries a
     * title and a line of its own above this, and under them "YOU PAY −0.005005"
     * landed on top of the same amount drawn again in the map and again in the
     * summary. Three times on one page is not emphasis, it is noise, and the eye
     * stops trusting which one is the real one.
     */
    hero: Boolean = true,
) {
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

    // An exchange is not a payment: the coin leaves and another one comes back to
    // the same wallet. That round trip is the thing worth looking at, so for a
    // swap the map *is* the headline instead of a picture half a screen below it.
    //
    // "A different coin comes back" is the whole test. It used to also demand that
    // the receiving account already existed, which is false for every first
    // purchase of a coin: that account is created by this very transaction, and
    // so the one leg that mattered was the one being filtered out.
    val outLeg = r.outflows.firstOrNull { it.rawAmount < 0 }
    val back = r.inflows.firstOrNull { it.rawAmount > 0 && it.mint != outLeg?.mint }
    val isSwap = (outLeg != null && back != null) || pair != null
    val outCoin = rememberCoinBitmap(outLeg?.mint ?: pair?.outMint)
    val backCoin = rememberCoinBitmap(back?.mint ?: pair?.inMint)
    val backSymbol = back?.symbol ?: pair?.inSymbol.orEmpty()

    // What the entrance animations key on. Not the receipt: a refreshed quote is
    // a new Receipt object every fifteen seconds, and keying on it replayed the
    // whole screen each time — cards sliding in, the map fading from nothing —
    // while you were reading it. The trade is the same trade; only the numbers
    // moved.
    val animKey = pair?.let { it.outMint + it.inMint }
        ?: (r.outflows.firstOrNull()?.mint.orEmpty() + (r.primaryRecipient ?: ""))

    val style = Halo.palette.receiptStyle
    if (style == ReceiptStyle.PAPER) { PaperReceipt(r, dests, danger, backCoin) { sheetAddr = it } }
    else if (style == ReceiptStyle.TERMINAL) { TerminalReceipt(r, dests, danger, backCoin) { sheetAddr = it } }
    else {
    if (hero) Box(Modifier.staggeredEntrance(0, animKey)) {
        if (isSwap) SwapFlowHero(r, danger, dests, outCoin, backCoin, backSymbol, pair) { d -> if (d.address != null) sheetAddr = d }
        else HeroPay(r, danger)
    }
    // "split across 2 destinations · 1 new account (rent)" is a warning shape for
    // a payment that fans out unexpectedly. Every swap does this by construction,
    // so on a swap it is an alarm about nothing.
    if (r.isSplit && !plain) {
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

    // What is arriving, judged on its own. Right under the amount, because a coin
    // that cannot be sold back is a reason to stop and belongs above the fold
    // just as much as a risk in the transaction itself. On a swap the risks go
    // first: the headline is now a picture, and the reason to stop must be the
    // first words under it.
    if (!isSwap && !plain) {
        Spacer(Modifier.height(12.dp))
        IncomingCoinCard(r, null)
    }

    // Risks right under the headline: the reason to stop must never be below the fold.
    if (r.risks.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Box(Modifier.staggeredEntrance(1, animKey)) { RisksCard(r.risks) }
    }

    if (isSwap) {
        if (!plain) {
            Spacer(Modifier.height(12.dp))
            IncomingCoinCard(r, null)
        }
    } else {
        // Already drawn, at the top, with the way home in it.
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.staggeredEntrance(2, animKey).fillMaxWidth().height(if (dests.size > 2) 250.dp else 200.dp).clip(rs(18))
                .background(Halo.cardSoft)
                .background(
                    Brush.radialGradient(
                        colors = listOf((if (danger) Halo.red else Halo.cyan).copy(alpha = 0.10f), Color.Transparent),
                        radius = 520f,
                    ),
                )
                .haloBorder(rs(18)),
        ) {
            NodeMap(dests = dests, danger = danger, coin = outCoin) { d -> if (d.address != null) sheetAddr = d }
            Text(
                stringResource(R.string.tap_node),
                fontFamily = Inter, fontSize = 11.sp, color = Halo.muted,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    if (dests.size > 1) {
        SplitBreakdown(dests, plain) { d -> if (d.address != null) sheetAddr = d }
        Spacer(Modifier.height(12.dp))
    }
    ReceiptDetails(r, dests.firstOrNull()?.label ?: stringResource(R.string.recipient_lower), showRecipient = dests.size <= 1) {
        dests.firstOrNull { it.address != null }?.let { sheetAddr = it }
    }

    // "CALLS · decoded from the program's published IDL · jupiter · route" is a
    // good answer to a question a normal person never asks. It stays where the
    // transaction came from somewhere else and needs auditing; on our own swap,
    // where the screen above already says what happens, it is noise.
    if (r.calls.isNotEmpty() && !plain) {
        Spacer(Modifier.height(12.dp))
        CallsCard(r.calls)
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
private fun DappStoreLine(store: StoreInfo) = DappStoreCard(store)

/** App logo straight from the installed package — the real "store logo", not a favicon. */
@Composable
private fun rememberPkgIcon(pkg: String): androidx.compose.ui.graphics.ImageBitmap? {
    val ctx = LocalContext.current
    return remember(pkg) {
        runCatching {
            val d = ctx.packageManager.getApplicationIcon(pkg)
            val w = d.intrinsicWidth.coerceIn(1, 192); val h = d.intrinsicHeight.coerceIn(1, 192)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp); d.setBounds(0, 0, w, h); d.draw(c)
            bmp.asImageBitmap()
        }.getOrNull()
    }
}

/** One labelled stat in the store card (rating, version, age…). */
@Composable
private fun StatTile(modifier: Modifier, icon: HIcon, value: String, label: String, tint: Color = Halo.cyan) {
    Column(
        modifier.clip(rs(12)).background(Halo.cardSoft).haloBorder(rs(12)).padding(horizontal = 8.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HaloIcon(icon, tint, 12.dp); Spacer(Modifier.width(4.dp))
            Text(
                value, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Halo.ink, style = Tabular,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Text(label, fontFamily = Inter, fontSize = 9.sp, color = Halo.muted, maxLines = 2, lineHeight = 10.sp)
    }
}

/**
 * The "who is this app" card: its real logo, a verified/sideload badge, and a
 * grid of stats (rating, reviews, version, install age, publisher). Everything is
 * either from the phone (package info, tamper-proof) or the free Seeker Tracker
 * catalog. Tap opens the app's dApp Store listing.
 */
@Composable
private fun DappStoreCard(store: StoreInfo) {
    val ctx = LocalContext.current
    val now = System.currentTimeMillis()
    fun ago(t: Long) = android.text.format.DateUtils.getRelativeTimeSpanString(t, now, android.text.format.DateUtils.DAY_IN_MILLIS).toString()
    val trusted = store.fromDappStore || store.fromPlay
    val badgeTint = if (trusted) Halo.mint else Halo.amber
    val origin = when { store.fromDappStore -> stringResource(R.string.store_dapp_store); store.fromPlay -> stringResource(R.string.store_play); else -> stringResource(R.string.store_sideload) }
    val rep by produceState<StoreRep?>(initialValue = null, store.packageName) {
        value = withContext(Dispatchers.IO) { runCatching { StoreReputation.fetch(store.packageName) }.getOrNull() }
    }
    val icon = rememberPkgIcon(store.packageName)
    val openListing = { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("solanadappstore://details?id=" + store.packageName))) }; Unit }
    // Collapsed by default: who is asking and whether it came from a store is
    // what you must read before signing. The numbers are one tap away.
    var open by remember(store.packageName) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().clip(rs(16)).background(Halo.card).haloBorder(rs(16)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(rs(12)).background(Halo.cardSoft).haloBorder(rs(12)), contentAlignment = Alignment.Center) {
                if (icon != null) androidx.compose.foundation.Image(icon, contentDescription = null, modifier = Modifier.size(38.dp).clip(rs(10)))
                else HaloIcon(HIcon.WALLET, Halo.muted, 20.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(rep?.name ?: store.label, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Halo.ink, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Box(Modifier.clip(rs(6)).background(badgeTint.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HaloIcon(if (trusted) HIcon.SHIELD_LOCK else HIcon.WARNING, badgeTint, 10.dp); Spacer(Modifier.width(3.dp))
                            Text(origin, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = badgeTint, maxLines = 1)
                        }
                    }
                    rep?.publisher?.let { pub ->
                        Spacer(Modifier.width(6.dp))
                        Text(pub + (if (rep?.verified == true) " ✓" else ""), fontFamily = Inter, fontSize = 10.5.sp, color = if (rep?.verified == true) Halo.mint else Halo.muted, maxLines = 1)
                    }
                }
            }
            Box(
                Modifier.size(26.dp).clip(rs(999)).background(Halo.cardSoft).haloBorder(rs(999)).clickable { open = !open },
                contentAlignment = Alignment.Center,
            ) { HaloIcon(HIcon.INFO, if (open) Halo.cyan else Halo.muted, 14.dp, description = stringResource(R.string.a11y_details)) }
        }
        if (!open) return@Column
        Row(Modifier.fillMaxWidth().clickable { openListing() }, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.store_open), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.cyan)
            Spacer(Modifier.width(4.dp)); HaloIcon(HIcon.EXTERNAL, Halo.cyan, 13.dp)
        }
        // Stat tiles: two rows of three.
        val ver = "v" + (store.versionName ?: store.versionCode.toString())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(Modifier.weight(1f), HIcon.SPARK, rep?.rating?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "—", stringResource(R.string.store_stat_rating), if (rep?.rating != null) Halo.mint else Halo.muted)
            StatTile(Modifier.weight(1f), HIcon.CONTACTS, rep?.reviews?.let { nf(it.toLong()) } ?: "—", stringResource(R.string.store_stat_reviews))
            StatTile(Modifier.weight(1f), HIcon.INFO, ver, stringResource(R.string.store_stat_version), if (trusted) Halo.cyan else Halo.amber)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(Modifier.weight(1f), HIcon.SEEDLING, ago(store.firstInstall), stringResource(R.string.store_stat_installed))
            StatTile(Modifier.weight(1f), HIcon.HISTORY, if (store.neverUpdated) stringResource(R.string.store_never_updated) else ago(store.lastUpdate), stringResource(R.string.store_stat_updated))
            StatTile(Modifier.weight(1f), if (rep?.listed == true) HIcon.SHIELD_LOCK else HIcon.WARNING, if (rep == null) "…" else if (rep?.listed == true) stringResource(R.string.store_stat_listed_yes) else stringResource(R.string.store_stat_listed_no), stringResource(R.string.store_stat_listed), if (rep?.listed == true) Halo.mint else Halo.amber)
        }
        if (!trusted) {
            Row(verticalAlignment = Alignment.Top) {
                HaloIcon(HIcon.INFO, Halo.amber, 13.dp); Spacer(Modifier.width(6.dp))
                Text(
                    if (rep?.listed == true) stringResource(R.string.store_sideload_but_listed) else stringResource(R.string.store_sideload_explain),
                    fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, lineHeight = 15.sp,
                )
            }
        }
    }
}

/**
 * For a request that named itself (an agent through the Agent Gate): what we could
 * verify, and a plain warning that the name above is only a claim. An app can call
 * itself anything; it cannot fake the package Android reports or how the link arrived.
 */
@Composable
private fun AgentOrigin(dApp: DappId) {
    val origin = dApp.origin ?: return
    val verified = origin.startsWith(stringResource(R.string.agent_origin_app).substringBefore("%"))
    Column(Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HaloIcon(if (verified) HIcon.SHIELD_LOCK else HIcon.INFO, if (verified) Halo.mint else Halo.amber, 13.dp)
            Spacer(Modifier.width(5.dp))
            Text(origin, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, color = if (verified) Halo.mint else Halo.amber, maxLines = 2)
        }
        if (dApp.nameIsClaimed) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 18.dp, top = 2.dp)) {
                Text(stringResource(R.string.agent_name_claimed), fontFamily = Inter, fontSize = 10.5.sp, color = Halo.muted, maxLines = 2)
            }
        }
    }
}

/** "3rd signature · since Sep 10" or "first time with this dApp" — the local memory of a dApp. */
@Composable
private fun DappMemory(dApp: DappId) {
    val ctx = LocalContext.current
    // Read on IO: this row sits on the signing screen, and the signing screen
    // must draw the moment it opens. Until the count lands the row is empty.
    val loaded by produceState<SignLog.DappStats?>(null, dApp.host, dApp.name) {
        value = withContext(Dispatchers.IO) { runCatching { Ledger.statsFor(ctx, dApp.host, dApp.name) }.getOrNull() }
    }
    val stats = loaded ?: return
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
        // Six alerts at once used to draw six alerts at once, and the card grew
        // until the amount, the map and the button were all below the fold. The
        // ones that stop you stay visible; the tail is one tap away. Risks arrive
        // sorted by severity, so "the first three" is "the three worst".
        var all by remember(risks) { mutableStateOf(false) }
        val keep = if (all) risks else risks.take(3)
        keep.forEach { RiskRow(it) }
        if (risks.size > keep.size) {
            Text(
                stringResource(R.string.risk_more, risks.size - keep.size),
                fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = col,
                modifier = Modifier.clickable { all = true },
            )
        }
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
    RiskFlag.LOOKALIKE_ADDRESS -> HIcon.MASK; RiskFlag.SIMULATION_FAILED -> HIcon.FLASK; RiskFlag.SIMULATION_UNAVAILABLE -> HIcon.FLASK; RiskFlag.STATE_DRIFT -> HIcon.DRIFT
    RiskFlag.AGENT_INTENT_MISMATCH -> HIcon.MASK; RiskFlag.AGENT_INTENT_OK -> HIcon.CHECK
    RiskFlag.COMMUNITY_FLAGGED -> HIcon.MEGAPHONE; RiskFlag.DRAINS_BALANCE -> HIcon.DRAIN; RiskFlag.WALLET_OWNER_CHANGE -> HIcon.FLAG
    RiskFlag.DURABLE_NONCE -> HIcon.HOURGLASS; RiskFlag.FOREIGN_FEE_PAYER -> HIcon.GIFT; RiskFlag.BRAND_NEW_RECIPIENT -> HIcon.SEEDLING; RiskFlag.LIMITED_APPROVAL -> HIcon.UNLOCK
    RiskFlag.FEE_EXCESSIVE -> HIcon.COINS
    RiskFlag.EXTRA_SIGNERS -> HIcon.PEN
    RiskFlag.WAGER -> HIcon.COINS; RiskFlag.WAGER_FOR_OTHER -> HIcon.MASK
}

internal fun riskTitle(f: RiskFlag): Int = when (f) {
    RiskFlag.BLOCKED_MALICIOUS -> R.string.rt_malicious; RiskFlag.SANCTIONED -> R.string.rt_sanctioned
    RiskFlag.UNLIMITED_APPROVAL -> R.string.rt_unlimited; RiskFlag.AUTHORITY_CHANGE -> R.string.rt_authority
    RiskFlag.ACCOUNT_CLOSE -> R.string.rt_close; RiskFlag.NEW_UNKNOWN_RECIPIENT -> R.string.rt_new_recipient
    RiskFlag.LOOKALIKE_ADDRESS -> R.string.rt_lookalike; RiskFlag.SIMULATION_FAILED -> R.string.rt_simulation; RiskFlag.SIMULATION_UNAVAILABLE -> R.string.rt_simulation
    RiskFlag.STATE_DRIFT -> R.string.rt_drift; RiskFlag.COMMUNITY_FLAGGED -> R.string.rt_community
    RiskFlag.AGENT_INTENT_MISMATCH -> R.string.rt_agent_mismatch; RiskFlag.AGENT_INTENT_OK -> R.string.rt_agent_ok
    RiskFlag.DRAINS_BALANCE -> R.string.rt_drain; RiskFlag.WALLET_OWNER_CHANGE -> R.string.rt_owner_change
    RiskFlag.DURABLE_NONCE -> R.string.rt_nonce; RiskFlag.FOREIGN_FEE_PAYER -> R.string.rt_fee_payer
    RiskFlag.BRAND_NEW_RECIPIENT -> R.string.rt_brand_new; RiskFlag.LIMITED_APPROVAL -> R.string.rt_limited
    RiskFlag.FEE_EXCESSIVE -> R.string.rt_fee_excessive
    RiskFlag.EXTRA_SIGNERS -> R.string.rt_extra_signers
    RiskFlag.WAGER -> R.string.rt_wager; RiskFlag.WAGER_FOR_OTHER -> R.string.rt_wager_other
}

/** Chip row to browse each transaction in the bundle; red chip = that tx is DANGER. */
@Composable
private fun TxSelector(receipts: List<Receipt>, selected: Int, viewed: Set<Int>, onSelect: (Int) -> Unit) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // A dot on transactions not yet opened; a check once reviewed.
                    if (i in viewed) { HaloIcon(HIcon.CHECK, col, 11.dp); Spacer(Modifier.width(5.dp)) }
                    else { Box(Modifier.size(6.dp).clip(rs(999)).background(Halo.amber)); Spacer(Modifier.width(5.dp)) }
                    Text(
                        "tx ${i + 1}" + (if (r.blocksApproval) " !" else "") + amt,
                        fontFamily = Sora, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 12.5.sp, color = col, style = Tabular,
                    )
                }
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
internal fun SplitBreakdown(dests: List<NodeDest>, plain: Boolean = false, onTap: (NodeDest) -> Unit) {
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
                // A share over 100% is arithmetic about a total that does not
                // exist, and saying nothing beats saying "129%".
                val sub = when {
                    d.isNewAccount -> stringResource(R.string.rent_to_create)
                    plain || d.sharePct !in 1..100 -> null
                    else -> stringResource(R.string.share_of_total, d.sharePct)
                }
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
                                d.isNewAccount -> stringResource(R.string.badge_deposit) to Halo.cyan
                                d.isFee -> stringResource(R.string.badge_fee) to Halo.amber
                                else -> null
                            }
                            badge?.let { (txt, col) ->
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    Modifier.clip(rs(999)).border(1.dp, col, rs(999)).padding(horizontal = 7.dp, vertical = 1.dp),
                                ) {
                                    // One word, one line. "new account · rent" in a
                                    // pill this wide wrapped onto three.
                                    Text(txt, color = col, fontFamily = Inter, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                }
                            }
                        }
                    }
                    Text(d.amountText, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = d.color, style = Tabular, maxLines = 1)
                }
                // Underneath, across the whole card. Beside the amount it had a
                // third of the width and broke into four ragged lines.
                sub?.let {
                    Text(
                        it, fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted, lineHeight = 16.sp,
                        modifier = Modifier.padding(start = 17.dp, bottom = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * The swap headline: the round trip, with the two numbers under it.
 *
 * The map used to sit below the amounts, the incoming coin card and the risks,
 * which in the swap sheet put it under the fold: you had to scroll to find the
 * one picture that explains what you are about to do. Here it is the first thing
 * on the screen and the amounts hang off it, so the shape and the numbers are
 * read in one glance.
 *
 * The receiving node is relabelled with the symbol of the coin coming back. A
 * shortened pool address under a USDC logo tells nobody anything.
 */
@Composable
private fun SwapFlowHero(
    r: Receipt,
    danger: Boolean,
    dests: List<NodeDest>,
    coin: ImageBitmap?,
    backCoin: ImageBitmap?,
    backSymbol: String,
    pair: SwapPair?,
    onTap: (NodeDest) -> Unit,
) {
    val accent = if (danger) Halo.red else Halo.mint
    val labelled = remember(dests, backSymbol, pair) {
        // The route's own name is "Jupiter pool · PancakeSwap · Meteora DLMM",
        // which is true, useless under a circle, and three times wider than the
        // canvas. Under the node goes the coin you are getting; the route is in
        // the details card, where there is room to read it.
        val i = dests.indexOfFirst { !it.isFee }
        when {
            i >= 0 -> dests.mapIndexed { k, d -> if (k == i) d.copy(label = backSymbol) else d }
            // No destinations at all: the simulation failed and there is nothing
            // to itemise. Draw what the quote says we were trying to do.
            pair != null -> listOf(
                NodeDest(
                    label = pair.inSymbol, address = null, amountText = "+" + pair.inUi + " " + pair.inSymbol,
                    color = Halo.mint, isFee = false, isNewAccount = false, sharePct = 100, share = 1.0,
                    trust = null, deltaText = "",
                ),
            )
            else -> dests
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(rs(20))
            .background(accent.copy(alpha = 0.08f))
            .background(Brush.radialGradient(colors = listOf(Halo.cyan.copy(alpha = 0.10f), Color.Transparent), radius = 520f))
            .border(1.dp, accent.copy(alpha = 0.32f), rs(20)),
    ) {
        Box(Modifier.fillMaxWidth().height(170.dp)) {
            NodeMap(dests = labelled, danger = danger, coin = coin, backCoin = backCoin, onTap = onTap)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // Half the row each, so neither number can starve the other: the pay
            // side used to get whatever the receive side left over, which on a
            // six-decimal amount was not enough for one line.
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.pay), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
                Spacer(Modifier.height(4.dp))
                if (r.outflows.isEmpty() && pair != null) {
                    FitAmount("−" + pair.outUi, pair.outSymbol, 20.sp, accent, FontWeight.Bold)
                } else {
                    r.outflows.forEachIndexed { i, d -> AmountText("−", d, 20.sp, accent, countUp = i == 0, fit = true) }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.receive), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
                Spacer(Modifier.height(4.dp))
                val ins = r.inflows.filter { it.rawAmount > 0 }
                if (ins.isEmpty() && pair != null) {
                    FitAmount("+" + pair.inUi, pair.inSymbol, 20.sp, Halo.cyan, FontWeight.SemiBold)
                } else {
                    ins.forEach { AmountText("+", it, 20.sp, Halo.cyan, FontWeight.SemiBold, fit = true) }
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
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Column {
            Text(stringResource(R.string.pay), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
            Spacer(Modifier.height(6.dp))
            if (r.outflows.isEmpty()) {
                Text(stringResource(R.string.no_transfer), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Halo.ink)
            } else {
                r.outflows.forEachIndexed { i, d -> AmountText("−", d, 26.sp, accent, countUp = i == 0) }
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
            // The technical numbers used to hang off a small circle floating
            // under the card, which was one more thing on the screen and looked
            // like a stray. They live behind this row now: same tap, nothing
            // floating, and the numbers are still one tap away when something
            // looks wrong.
            var stats by remember(r) { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().then(if (r.stats != null) Modifier.clickable { stats = !stats } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.verdict), fontFamily = Inter, fontSize = 13.sp, color = Halo.muted)
                Spacer(Modifier.weight(1f))
                val (txt, col) = when (r.highestSeverity) {
                    Severity.DANGER -> stringResource(R.string.verdict_danger) to Halo.red
                    Severity.WARN -> stringResource(R.string.verdict_warn) to Halo.amber
                    Severity.INFO -> stringResource(R.string.verdict_ok) to Halo.mint
                }
                Text(txt, fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = col)
                if (r.stats != null) {
                    Spacer(Modifier.width(6.dp))
                    HaloIcon(if (stats) HIcon.CHEVRON_DOWN else HIcon.CHEVRON_RIGHT, Halo.muted, 15.dp)
                }
            }
            if (stats) r.stats?.let { StatsCard(it) }
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
                Text(stringResource(R.string.stats_hdr), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Halo.cyan)
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

/** Address scan: what this wallet does, with whom, and the patterns that matter. */
@Composable
private fun TraceBlock(t: AddressTrace.Trace?, running: Boolean, ran: Boolean, isPro: Boolean, onRun: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(rs(14)).background(Halo.cardSoft).haloBorder(rs(14)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

/**
 * The coin's logo as something a Canvas can draw.
 *
 * Loaded through the image loader directly rather than with a painter:
 * `rememberAsyncImagePainter` only starts its request when something draws it,
 * and nothing draws this one, so the painter sat empty forever and the logo
 * never arrived.
 *
 * Null until it loads, and null forever for a coin with no icon, which is why
 * the map keeps working without it: the logo is an improvement on the dot, not
 * a requirement for it.
 */
@Composable
internal fun rememberCoinBitmap(mint: String?): ImageBitmap? {
    val ctx = LocalContext.current
    val url = mint?.let { TokenSymbols.image(it) }
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        val req = coil.request.ImageRequest.Builder(ctx).data(url).size(72).allowHardware(false).build()
        // The app's one loader, with its memory and disk cache, and the
        // rasterisation off the main thread: this runs for every coin on a map.
        val d = runCatching { coil.Coil.imageLoader(ctx).execute(req).drawable }.getOrNull() ?: return@LaunchedEffect
        bmp = withContext(Dispatchers.Default) { runCatching {
            // A bitmap drawable already is one; anything else gets rasterised once
            // at a size the canvas will never need to grow past.
            (d as? android.graphics.drawable.BitmapDrawable)?.bitmap?.asImageBitmap() ?: run {
                val b = android.graphics.Bitmap.createBitmap(72, 72, android.graphics.Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, 72, 72)
                d.draw(android.graphics.Canvas(b))
                b.asImageBitmap()
            }
        }.getOrNull() }
    }
    return bmp
}

// ---- node map (all geometry in dp → px, so it looks the same on every density) ----

/**
 * Where the money goes, and — for a swap — where it comes back from.
 *
 * One direction was right for a payment and half a story for an exchange. In a
 * swap the coin leaves, the route turns it into another coin, and that other
 * coin lands back in the same wallet it left. Drawing only the outbound leg made
 * a swap look like giving money away.
 *
 * [backCoin] is what turns it into a circuit: pass the logo of the coin that
 * arrives and the map grows a second arc under the first, running the other way,
 * carrying that logo home. Pass null and nothing changes at all, which is what a
 * plain send wants.
 */
@Composable
internal fun NodeMap(
    dests: List<NodeDest>,
    danger: Boolean,
    coin: ImageBitmap? = null,
    backCoin: ImageBitmap? = null,
    onTap: (NodeDest) -> Unit,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val sora = remember { runCatching { ResourcesCompat.getFont(ctx, R.font.sora) }.getOrNull() ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    // The flow used to stop after twenty seconds to save frames, and a receipt you
    // were still reading turned into a frozen picture: money that had stopped
    // moving. It runs for as long as the receipt is on screen, and stops when the
    // composable leaves, which is the only moment it costs nothing to stop.
    val flow = rememberInfiniteTransition(label = "flow")
    val t by flow.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "t")
    val pulse by flow.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "p")
    val reveal = remember { Animatable(0f) }
    // The shape of the map, not its numbers. A new quote every fifteen seconds
    // changes every amount and no destination, and redrawing the whole thing from
    // zero each time is how a live price turned into a flicker.
    val shape = remember(dests) { dests.map { it.label } }
    LaunchedEffect(shape) { reveal.snapTo(0f); reveal.animateTo(1f, tween(700)) }
    val labelPaint = remember { Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = sora } }
    val amtPaint = remember { Paint().apply { isAntiAlias = true; textAlign = Paint.Align.RIGHT; typeface = sora } }
    val youLabel = stringResource(R.string.you)
    // Three, and then a count. A route can fan out to six accounts and the canvas
    // cannot: at five the circles touch and the names print on top of each other,
    // which turns the one picture meant to be counted into a smear. The full list
    // is right underneath, in words, where length costs nothing.
    val extra = (dests.size - 3).coerceAtLeast(0)
    val shown = if (extra > 0) {
        dests.take(3) + NodeDest(
            label = "+$extra", address = null, amountText = "", color = Halo.muted,
            isFee = false, isNewAccount = false, sharePct = 0, share = 0.0, trust = null, deltaText = "",
        )
    } else {
        dests
    }
    val n = shown.size.coerceAtLeast(1)
    // The one wire that carries the trade. Rent for a new account and a fee are
    // real destinations but they are not where your coin goes, and giving all of
    // them a coin logo put four glowing discs on the canvas that read as four
    // more accounts appearing and disappearing.
    val mainWire = shown.indexOfFirst { !it.isFee && !it.isNewAccount }
        .takeIf { it >= 0 } ?: shown.indexOfFirst { !it.isFee }.coerceAtLeast(0)
    val backFrom = if (backCoin == null) -1 else mainWire
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
            val coinSize = dp(if (n > 3) 13f else 16f)
            // One thing per wire. Three made a row of dots that, on a picture whose
            // job is to be counted, looked like more destinations.
            run {
                val p = (t % 1f) * alpha; val mt = 1 - p
                val px = mt * mt * you.x + 2 * mt * p * ctrl.x + p * p * dest.x
                val py = mt * mt * you.y + 2 * mt * p * ctrl.y + p * p * dest.y
                // The coin's own face rides the wire that actually carries it, so
                // what you watch crossing is the thing you are sending.
                drawWireParticle(Offset(px, py), d.color, if (i == mainWire) coin else null, coinSize, alpha, dp)
            }

            // The way home. Same arc mirrored below, running the other way, half a
            // turn out of phase so the two read as one circuit instead of two
            // streams crossing.
            if (i == backFrom && backCoin != null) {
                val ctrlBack = Offset((you.x + dest.x) / 2f, (you.y + dest.y) / 2f + dp(22f))
                val back = Path().apply { moveTo(dest.x, dest.y); quadraticBezierTo(ctrlBack.x, ctrlBack.y, you.x, you.y) }
                drawPath(back, color = Halo.cyan.copy(alpha = 0.4f * alpha), style = Stroke(width = stroke))
                run {
                    val p = ((t + 0.5f) % 1f) * alpha; val mt = 1 - p
                    val px = mt * mt * dest.x + 2 * mt * p * ctrlBack.x + p * p * you.x
                    val py = mt * mt * dest.y + 2 * mt * p * ctrlBack.y + p * p * you.y
                    drawWireParticle(Offset(px, py), Halo.cyan, backCoin, coinSize, alpha, dp)
                }
            }
            // Amount sits just left of its node, so five edges never pile up mid-canvas.
            // An em dash is what [heroLine] returns when there is no amount to
            // show. On a wire, between two circles, it reads as a thing rather
            // than as an absence, so nothing is drawn instead.
            val amount = d.amountText.takeIf { it.isNotBlank() && it.trim() != "—" && it.trim() != "-" }
            if (amount != null) {
                amtPaint.color = d.color.copy(alpha = alpha).toArgb(); amtPaint.textSize = dp(if (n > 3) 11f else 13f)
                drawContext.canvas.nativeCanvas.drawText(amount, dest.x - nodeR - dp(10f), dest.y + dp(4f), amtPaint)
            }
            // The node that hands the coin back wears it, so the picture answers
            // "what am I getting" without a word of text.
            drawMapNode(dest, d.color, d.label, labelPaint, pulse, i, nodeR, dp, alpha, if (i == backFrom) backCoin else null)
        }
        drawMapNode(you, Halo.mint, youLabel, labelPaint, pulse, 9, dp(17f), dp, 1f)
    }
}

/**
 * Something crossing a wire. Never something standing on one.
 *
 * This is the line between decoration and a lie. A node on this map is a real
 * destination and can be counted; a particle is the money moving. They used to
 * be drawn the same way — a disc, a halo, a ring around it — so a person reading
 * the picture counted two accounts one second and six the next and asked, fairly,
 * which one was true.
 *
 * So the moving things lost the halo and the ring. What is left is a small
 * bright mark, and on one wire the coin's own face, small enough that it never
 * reads as a coin parked somewhere.
 */
private fun DrawScope.drawWireParticle(at: Offset, color: Color, coin: ImageBitmap?, size: Float, alpha: Float, dp: (Float) -> Float) {
    if (coin == null) {
        drawCircle(color.copy(alpha = 0.85f * alpha), dp(2.2f), at)
        return
    }
    val s = size * 0.72f
    clipPath(Path().apply { addOval(Rect(at.x - s / 2f, at.y - s / 2f, at.x + s / 2f, at.y + s / 2f)) }) {
        drawImage(
            image = coin,
            dstOffset = androidx.compose.ui.unit.IntOffset((at.x - s / 2f).toInt(), (at.y - s / 2f).toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(s.toInt(), s.toInt()),
            alpha = 0.92f * alpha,
        )
    }
}

private fun DrawScope.drawMapNode(
    c: Offset, color: Color, label: String, paint: Paint, pulse: Float, seed: Int, r: Float, dp: (Float) -> Float, alpha: Float,
    badge: ImageBitmap? = null,
) {
    val p = sin(pulse + seed) * dp(1.5f)
    drawCircle(color.copy(alpha = 0.06f * alpha), r + dp(12f) + p, c)
    drawCircle(color.copy(alpha = 0.10f * alpha), r + dp(7f) + p, c)
    drawCircle(color.copy(alpha = 0.16f * alpha), r + dp(3f) + p, c)
    drawCircle(Halo.ground.copy(alpha = 0.92f), r, c)
    if (badge != null) {
        val s = r * 1.7f
        clipPath(Path().apply { addOval(Rect(c.x - s / 2f, c.y - s / 2f, c.x + s / 2f, c.y + s / 2f)) }) {
            drawImage(
                image = badge,
                dstOffset = androidx.compose.ui.unit.IntOffset((c.x - s / 2f).toInt(), (c.y - s / 2f).toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(s.toInt(), s.toInt()),
                alpha = alpha,
            )
        }
    }
    drawCircle(color.copy(alpha = alpha), r, c, style = Stroke(width = dp(2f)))
    paint.color = color.copy(alpha = alpha).toArgb(); paint.textSize = dp(11.5f)
    // Centred on the node, so the room it has is twice the distance to the nearer
    // edge. "Jupiter pool · PancakeSwap · Meteora DLMM" used to be drawn in full
    // and simply left the screen on the right.
    val room = 2f * minOf(c.x, size.width - c.x) - dp(8f)
    drawContext.canvas.nativeCanvas.drawText(ellipsize(label, paint, room), c.x, c.y + r + dp(15f), paint)
}

/** [text] shortened with a tail ellipsis until it fits [room] pixels. */
private fun ellipsize(text: String, paint: Paint, room: Float): String {
    if (room <= 0f || paint.measureText(text) <= room) return text
    var cut = text.length
    while (cut > 1 && paint.measureText(text.take(cut) + "…") > room) cut--
    return text.take(cut).trimEnd() + "…"
}

// ---- done / error ------------------------------------------------------------------

@Composable
private fun DoneScreen(ui: MwaUi.Done) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    LaunchedEffect(Unit) { Haptics.success(ctx) }
    val draw = remember { Animatable(0f) }
    LaunchedEffect(Unit) { draw.animateTo(1f, tween(650)) }
    // Hand control back to the dApp after a beat (see MobileWalletAdapterActivity.backToDapp).
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
        PrimaryButton(stringResource(R.string.back_to_dapp, left), danger = false, icon = HIcon.CHEVRON_RIGHT) { activity?.backToDapp(explicit = true) }
        ui.signedTx?.let { raw ->
            Column(
                Modifier.fillMaxWidth().clip(rs(16)).background(Halo.card).haloBorder(rs(16)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.agent_signed_tx), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
                QrTile(raw, size = 200.dp)
                Text(stringResource(R.string.agent_signed_note), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted)
                GhostButton(stringResource(R.string.copy), Modifier.fillMaxWidth(), HIcon.COPY) { clip.setText(AnnotatedString(raw)) }
            }
        }
        ui.signature?.let { sig ->
            Box(
                Modifier.fillMaxWidth().clip(rs(16)).background(Halo.card).haloBorder(rs(16)).padding(14.dp),
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
                .background(Halo.card).haloBorder(rs(16)).padding(16.dp),
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

/**
 * "0.5", "1 234.56", "0.000005": no trailing zeros, thin-space thousands, never scientific.
 *
 * ROOT on purpose, twice over. On a screen that is about to move money, "1.234" must not
 * be readable as both a thousand and as one-point-two-three-four, so the thousands get a
 * thin space and the decimal point stays a point, in every language. And the same text
 * goes verbatim into the attested statement the hardware key signs, so a proof made on an
 * Italian phone has to match one made on an English one. Do not make this follow the locale.
 */
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
