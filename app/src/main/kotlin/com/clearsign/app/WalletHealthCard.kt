@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The wow card: a live security score for the connected wallet, with issues → the fix flow below. */
@Composable
internal fun WalletHealthCard(owner: String?, refreshKey: Int = 0) {
    val ctx = LocalContext.current
    if (owner == null) return
    val health by produceState<WalletHealth?>(initialValue = null, owner, refreshKey) {
        value = withContext(Dispatchers.IO) {
            runCatching { WalletHealth.of(owner, SolanaRpc.tokenAccountsOf(SolanaRpc.urlFor(null), owner, force = refreshKey > 0)) }.getOrNull()
        }
    }
    val h = health
    val color = when (h?.band) {
        WalletHealth.Band.GREAT -> Halo.mint; WalletHealth.Band.OK -> Halo.cyan
        WalletHealth.Band.WEAK -> Halo.amber; WalletHealth.Band.RISKY -> Halo.red; null -> Halo.muted
    }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreRing(h?.score, color, Modifier.size(64.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.health_title), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
                    Text(
                        when (h?.band) {
                            WalletHealth.Band.GREAT -> stringResource(R.string.health_great); WalletHealth.Band.OK -> stringResource(R.string.health_ok)
                            WalletHealth.Band.WEAK -> stringResource(R.string.health_weak); WalletHealth.Band.RISKY -> stringResource(R.string.health_risky)
                            null -> stringResource(R.string.analyzing)
                        },
                        fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Halo.ink,
                    )
                    if (h != null) Text(
                        if (h.isClean) stringResource(R.string.health_clean) else stringResource(R.string.health_issues, h.issues.sumOf { it.count }),
                        fontFamily = Inter, fontSize = 12.sp, color = if (h.isClean) Halo.mint else color,
                    )
                }
            }
            h?.issues?.forEach { issue ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HaloIcon(iconFor(issue.kind), colorFor(issue.kind), 15.dp); Spacer(Modifier.width(8.dp))
                        Text(textFor(ctx, issue), fontFamily = Inter, fontSize = 12.5.sp, color = Halo.ink, modifier = Modifier.weight(1f))
                    }
                    // A frozen account is the one line here with no fix behind it.
                    // Only the freeze authority can thaw it, it cannot even be
                    // closed to get the rent back, and listing it next to things
                    // you *can* fix promised a button that could never exist.
                    if (issue.kind == HealthIssue.Kind.FROZEN) {
                        Text(
                            stringResource(R.string.health_frozen_why),
                            fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, lineHeight = 15.sp,
                            modifier = Modifier.padding(start = 23.dp, top = 2.dp),
                        )
                    }
                }
            }
            // The hint is about the issues that have a fix. With only a frozen
            // account to report there is nothing to send anybody to.
            if (h != null && h.issues.any { it.kind != HealthIssue.Kind.FROZEN }) {
                Text(stringResource(R.string.health_fix_hint), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted)
            }
        }
    }
}

@Composable
private fun ScoreRing(score: Int?, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val target = (score ?: 0) / 100f
    val p by animateFloatAsState(target, tween(900), label = "score")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier) {
            val sw = 6.dp.toPx(); val inset = sw / 2
            drawArc(Halo.stroke, -90f, 360f, false, topLeft = Offset(inset, inset), size = Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(color, -90f, 360f * p, false, topLeft = Offset(inset, inset), size = Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
        }
        Text(score?.toString() ?: "…", fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = color, style = Tabular)
    }
}

private fun iconFor(k: HealthIssue.Kind) = when (k) {
    HealthIssue.Kind.UNLIMITED_APPROVAL -> HIcon.INFINITY; HealthIssue.Kind.LIMITED_APPROVAL -> HIcon.UNLOCK
    HealthIssue.Kind.DUST_ACCOUNTS -> HIcon.TRASH; HealthIssue.Kind.FROZEN -> HIcon.LOCK
}
private fun colorFor(k: HealthIssue.Kind) = when (k) {
    HealthIssue.Kind.UNLIMITED_APPROVAL -> Halo.red; HealthIssue.Kind.LIMITED_APPROVAL -> Halo.amber
    HealthIssue.Kind.DUST_ACCOUNTS -> Halo.cyan; HealthIssue.Kind.FROZEN -> Halo.muted
}
private fun textFor(ctx: android.content.Context, i: HealthIssue): String = when (i.kind) {
    HealthIssue.Kind.UNLIMITED_APPROVAL -> ctx.getString(R.string.health_unlimited, i.count, i.detail)
    HealthIssue.Kind.LIMITED_APPROVAL -> ctx.getString(R.string.health_limited, i.count)
    HealthIssue.Kind.DUST_ACCOUNTS -> ctx.getString(R.string.health_dust, i.count)
    HealthIssue.Kind.FROZEN -> ctx.getString(R.string.health_frozen, i.count, i.detail)
}
