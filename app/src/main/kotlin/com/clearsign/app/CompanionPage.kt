@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.clearsign.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The bubble and the widget, on one page of their own.
 *
 * The bubble floats over every app; the widget sits on the home screen. Both
 * are yours to shape: what the small circle says, which rows the open bubble
 * carries, which coin it watches, how big it is. The preview at the top is
 * the same drawing the bubble uses, so what you see is what floats.
 */
@Composable
internal fun CompanionPage(owner: String?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        var tick by remember { mutableIntStateOf(0) }
        var face by remember { mutableStateOf(CompanionPrefs.face(ctx)) }
        var size by remember { mutableIntStateOf(CompanionPrefs.size(ctx)) }
        var coin by remember { mutableStateOf(CompanionPrefs.coin(ctx)) }
        var auto by remember { mutableStateOf(CompanionPrefs.autoStart(ctx)) }
        var canDraw by remember { mutableStateOf(CompanionService.canRun(ctx)) }
        val shows = remember { mutableStateOf(listOf("total", "agent", "health", "coin").associateWith { CompanionPrefs.show(ctx, it) }) }
        var coins by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        var note by remember { mutableStateOf<String?>(null) }

        // The coins on offer: what the wallet holds and what is followed.
        LaunchedEffect(owner) {
            coins = withContext(Dispatchers.IO) {
                val held = owner?.let { Portfolio.cached(it, Settings.currency.value)?.holdings?.filter { h -> h.isMain }?.map { h -> h.mint to h.symbol } }.orEmpty()
                val followed = Watchlist.all(ctx).filter { !it.startsWith("cg:") }.map { it to TokenSymbols.symbol(it) }
                (held + followed).distinctBy { it.first }.take(12)
            }
        }
        fun restart() { if (CompanionService.canRun(ctx)) { CompanionService.stop(ctx); scope.launch { kotlinx.coroutines.delay(300); CompanionService.start(ctx) } } }

        Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Halo.ground2, Halo.ground))).statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SheetHeader(stringResource(R.string.comp_page_title), stringResource(R.string.comp_page_sub), HIcon.SPARK, onClose = onDismiss)

            // ---- the preview: the very face the bubble will wear ----
            val px = with(density) { size.dp.roundToPx() }
            val previewData = remember(tick, face, owner) {
                val trading = TraderLoop.config(ctx).on
                CompanionPrefs.FaceData(
                    HealthWidgetData.load(ctx)?.score, if (trading) Positions.open(ctx).size else null,
                    owner?.let { Portfolio.cached(it, Settings.currency.value)?.let { v -> fmtFiat(v.total, v.currency).take(8) } },
                    coin?.let { TokenSymbols.symbol(it) }, null, trading,
                )
            }
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                        Image(CompanionPrefs.faceBitmap(px, face, previewData).asImageBitmap(), null, Modifier.size(size.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.comp_preview), style = HaloType.label, color = Halo.muted)
                        Text(stringResource(R.string.comp_preview_sub), style = HaloType.small, color = Halo.muted, lineHeight = 15.sp)
                    }
                }
            }

            // ---- on, off, permission ----
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.comp_bubble).uppercase(), style = HaloType.label, color = Halo.muted)
                    if (!canDraw) {
                        Banner(stringResource(R.string.companion_needs_perm), Halo.amber, HIcon.WARNING)
                        GhostButton(stringResource(R.string.companion_grant), Modifier.fillMaxWidth(), HIcon.UNLOCK, tint = Halo.amber) {
                            runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName))) }
                        }
                        LaunchedEffect(Unit) { while (!canDraw) { kotlinx.coroutines.delay(1500); canDraw = CompanionService.canRun(ctx) } }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GhostButton(stringResource(R.string.companion_on), Modifier.weight(1f), HIcon.CHECK, tint = Halo.mint) { CompanionService.start(ctx); Haptics.tick(ctx) }
                            GhostButton(stringResource(R.string.companion_off), Modifier.weight(1f), HIcon.BLOCK) { CompanionService.stop(ctx); Haptics.tick(ctx) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.comp_auto), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.ink)
                                Text(stringResource(R.string.comp_auto_sub), style = HaloType.small, color = Halo.muted)
                            }
                            Switch(checked = auto, onCheckedChange = { auto = it; CompanionPrefs.setAutoStart(ctx, it) })
                        }
                    }
                }
            }

            // ---- the face ----
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.comp_face).uppercase(), style = HaloType.label, color = Halo.muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            CompanionPrefs.Face.ROTATE to R.string.comp_face_rotate,
                            CompanionPrefs.Face.BUDGET to R.string.comp_face_budget,
                            CompanionPrefs.Face.AGENT to R.string.comp_face_agent, CompanionPrefs.Face.HEALTH to R.string.comp_face_health,
                            CompanionPrefs.Face.TOTAL to R.string.comp_face_total, CompanionPrefs.Face.COIN to R.string.comp_face_coin,
                        ).forEach { (f, label) ->
                            val on = f == face
                            Text(
                                stringResource(label), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = if (on) Halo.ink else Halo.muted,
                                modifier = Modifier.clip(rs(999)).background(if (on) Halo.mint.copy(alpha = 0.16f) else Halo.cardSoft).border(1.dp, if (on) Halo.mint else Halo.stroke, rs(999))
                                    .clickable { face = f; CompanionPrefs.setFace(ctx, f); tick++; restart() }.padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                    Text(stringResource(R.string.comp_size), style = HaloType.label, color = Halo.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(44, 54, 66).forEach { s ->
                            ModeChip(if (s == 44) stringResource(R.string.comp_size_s) else if (s == 54) stringResource(R.string.comp_size_m) else stringResource(R.string.comp_size_l), s == size, Halo.cyan, Modifier.weight(1f)) {
                                size = s; CompanionPrefs.setSize(ctx, s); restart()
                            }
                        }
                    }
                }
            }

            // ---- the rows of the open bubble ----
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.comp_rows).uppercase(), style = HaloType.label, color = Halo.muted)
                    // Il totale e il prezzo della moneta stanno gia' in cima al
                    // pannello nuovo, con la barra e i due numeri della paghetta:
                    // due interruttori per righe che non esistono piu' sarebbero
                    // due interruttori che non fanno niente.
                    listOf("agent" to R.string.comp_row_agent, "health" to R.string.comp_row_health).forEach { (k, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(label), style = HaloType.body, color = Halo.ink, modifier = Modifier.weight(1f))
                            Switch(checked = shows.value[k] == true, onCheckedChange = { on -> CompanionPrefs.setShow(ctx, k, on); shows.value = shows.value + (k to on); restart() })
                        }
                    }
                }
            }

            // ---- the coin to watch ----
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.comp_coin).uppercase(), style = HaloType.label, color = Halo.muted)
                    if (coins.isEmpty()) Text(stringResource(R.string.comp_coin_none), style = HaloType.small, color = Halo.muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        coins.forEach { (mint, sym) ->
                            val on = mint == coin
                            Text(
                                sym, fontFamily = Mono, fontSize = 12.sp, color = if (on) Halo.ink else Halo.muted,
                                modifier = Modifier.clip(rs(999)).background(if (on) Halo.cyan.copy(alpha = 0.16f) else Halo.cardSoft).border(1.dp, if (on) Halo.cyan else Halo.stroke, rs(999))
                                    .clickable { coin = mint; CompanionPrefs.setCoin(ctx, mint); tick++; restart() }.padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }

            // ---- the widget ----
            GlassCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.widget_card_title).uppercase(), style = HaloType.label, color = Halo.muted)
                    Text(note ?: stringResource(R.string.widget_card_sub), style = HaloType.small, color = if (note != null) Halo.mint else Halo.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GhostButton(stringResource(R.string.widget_add), Modifier.weight(1f), HIcon.QR, tint = Halo.cyan) {
                            val awm = ctx.getSystemService(android.appwidget.AppWidgetManager::class.java)
                            val provider = android.content.ComponentName(ctx, HealthWidgetReceiver::class.java)
                            note = if (awm != null && awm.isRequestPinAppWidgetSupported && awm.requestPinAppWidget(provider, null, null)) ctx.getString(R.string.widget_added) else ctx.getString(R.string.widget_unsupported)
                            Haptics.tick(ctx)
                        }
                        GhostButton(stringResource(R.string.widget_refresh), Modifier.weight(1f), HIcon.HISTORY) {
                            scope.launch { runCatching { HealthWidgetData.refresh(ctx) }; note = ctx.getString(R.string.widget_refreshed); Haptics.tick(ctx) }
                        }
                    }
                    Text(stringResource(R.string.widget_card_note), style = HaloType.small, color = Halo.muted, lineHeight = 15.sp)
                }
            }
            Text(stringResource(R.string.companion_note), style = HaloType.small, color = Halo.muted, lineHeight = 15.sp)
            Spacer(Modifier.height(8.dp))
        }
    }
}
