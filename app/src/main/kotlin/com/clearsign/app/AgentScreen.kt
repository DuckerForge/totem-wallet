package com.clearsign.app

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Agent Gate, given a home of its own.
 *
 * It used to hide behind a scan icon, which made the one feature nobody else has
 * the hardest to find. Here it explains itself, hands over the address an agent
 * needs, opens the scanner, and lists what has already been signed for an agent.
 */
@Composable
internal fun AgentScreen(owner: String?, signer: SeedVaultSigner, onChat: () -> Unit = {}) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var scanError by remember { mutableStateOf<String?>(null) }
    val scan = rememberAgentScan { scanError = it }
    val signed = remember(owner) { runCatching { Ledger.all(ctx).filter { it.kind == "agent" } }.getOrDefault(emptyList()).sortedByDescending { it.at }.take(6) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(rs(12)).background(Halo.mint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                HaloIcon(HIcon.PIGEON, Halo.mint, 22.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.tab_agent), style = HaloType.screen, color = Halo.ink)
                Text(stringResource(R.string.agent_tab_sub), style = HaloType.small, color = Halo.muted)
            }
        }

        // Pocket one: the envelope, with its collar. This is where autonomy lives.
        AgentCard(owner, signer, onLink = { scan() }, onChat = onChat)

        // Pocket two: the Seed Vault account. Nothing here is ever pre-authorised.
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HaloIcon(HIcon.SHIELD_LOCK, Halo.mint, 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.agent_state_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Halo.mint)
                }
                Text(stringResource(R.string.agent_state_body), fontFamily = Inter, fontSize = 12.5.sp, color = Halo.muted, lineHeight = 18.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Counted, not asserted. Autonomous signings are recorded against
                    // the budget key, so the old `wallet == owner` test never saw them.
                    AgentStat(signed.size.toString(), stringResource(R.string.agent_stat_signed), Modifier.weight(1f))
                    AgentStat(
                        signed.firstOrNull()?.let { e ->
                            android.text.format.DateUtils.getRelativeTimeSpanString(e.at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
                        } ?: "—",
                        stringResource(R.string.agent_stat_last), Modifier.weight(1f),
                    )
                    // The screen used to swear "never" in a hardcoded string while the
                    // budget above it signed on its own. Say the real number.
                    val alone = signed.count { it.host == "auto" }
                    AgentStat(
                        if (alone == 0) stringResource(R.string.agent_stat_auto_value) else alone.toString(),
                        stringResource(R.string.agent_stat_auto), Modifier.weight(1f),
                        if (alone == 0) Halo.mint else Halo.amber,
                    )
                }
            }
        }

        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.agent_tab_how), fontFamily = Inter, fontSize = 12.5.sp, color = Halo.muted, lineHeight = 18.sp)
                PrimaryButton(stringResource(R.string.gate_scan), danger = false, icon = HIcon.SCAN) { scan() }
                scanError?.let { Banner(it, Halo.amber, HIcon.WARNING) }
            }
        }

        // The one thing the agent needs from you, ready to copy.
        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.agent_tab_wallet), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
                if (owner == null) {
                    Text(stringResource(R.string.agent_tab_none), fontFamily = Inter, fontSize = 12.5.sp, color = Halo.amber)
                } else {
                    Text(owner, fontFamily = Mono, fontSize = 12.sp, color = Halo.ink, lineHeight = 18.sp)
                    GhostButton(stringResource(R.string.copy), Modifier.fillMaxWidth(), HIcon.COPY, tint = Halo.cyan) {
                        clip.setText(AnnotatedString(owner)); Haptics.tick(ctx)
                    }
                }
            }
        }

        GlassCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.agent_tab_recent), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
                if (signed.isEmpty()) {
                    Text(stringResource(R.string.agent_tab_empty), fontFamily = Inter, fontSize = 12.5.sp, color = Halo.muted)
                } else {
                    signed.forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val refused = e.host == "refused"
                            HaloIcon(if (refused) HIcon.BLOCK else if (e.sent) HIcon.CHECK else HIcon.PEN, if (refused) Halo.red else Halo.mint, 15.dp)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.dApp + agentHow(ctx, e.host), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.ink, maxLines = 1)
                                if (refused && e.note.isNotBlank()) Text(e.note, fontFamily = Inter, fontSize = 10.5.sp, color = Halo.red, maxLines = 2, lineHeight = 14.sp)
                                Text(
                                    android.text.format.DateUtils.getRelativeTimeSpanString(e.at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString(),
                                    fontFamily = Inter, fontSize = 10.5.sp, color = Halo.muted,
                                )
                            }
                            val out = e.outflows.firstOrNull()
                            if (out != null) {
                                Text(
                                    "−" + fmtUi(kotlin.math.abs(out.uiAmount)) + " " + out.symbol,
                                    fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = Halo.ink, style = Tabular, maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }

        Text(stringResource(R.string.agent_tab_setup), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, lineHeight = 16.sp)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AgentStat(value: String, label: String, modifier: Modifier, tint: androidx.compose.ui.graphics.Color = Halo.ink) {
    Column(
        modifier.clip(rs(12)).background(Halo.cardSoft).border(1.dp, Halo.stroke, rs(12)).padding(horizontal = 9.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(value, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = tint, style = Tabular, maxLines = 1)
        Text(label, fontFamily = Inter, fontSize = 9.sp, color = Halo.muted, maxLines = 2, lineHeight = 10.sp)
    }
}
