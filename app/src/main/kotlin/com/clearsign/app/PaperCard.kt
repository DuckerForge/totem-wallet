package com.clearsign.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clearsign.core.ExitRule

/**
 * What the other exit rules would have done, on your coins.
 *
 * Two questions get answered here and nowhere else in the app. Whether the target
 * and stop you set are the right ones for what this scan finds — and whether the
 * walls that stop the agent are saving you money or costing it. Both are numbers
 * from your own trades, which is the only kind worth having.
 *
 * The card refuses to give advice on too little. Under [ENOUGH] closed trades it
 * shows the table and says plainly that it does not mean anything yet, because a
 * suggestion made on eight trades is a suggestion made on noise.
 */
private const val ENOUGH = 20
private const val MARGIN = 1.20   // 20% better before it is worth changing anything

@Composable
internal fun PaperCard(refresh: Int, onChange: () -> Unit) {
    val ctx = LocalContext.current
    val cfg = remember(refresh) { TraderLoop.config(ctx) }
    val stats = remember(refresh) { Paper.stats(ctx) }
    val blocked = remember(refresh) { Paper.blockedVerdict(ctx) }
    val rows = remember(refresh) { Paper.all(ctx) }
    if (rows.isEmpty()) return

    val yours = stats.firstOrNull { it.rule == ExitRule.YOURS }
    val best = stats.filter { it.closed > 0 }.maxByOrNull { it.netLamports }
    val closed = yours?.closed ?: 0

    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HaloIcon(HIcon.FLASK, Halo.cyan, 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.shadow_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Halo.cyan, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.shadow_rows, rows.size), fontFamily = Mono, fontSize = 11.sp, color = Halo.muted)
            }
            Text(stringResource(R.string.shadow_sub), style = HaloType.small, color = Halo.muted, lineHeight = 16.sp)

            stats.forEach { s ->
                val lead = best != null && s.rule == best.rule && s.closed > 0
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(ruleName(s.rule)),
                        fontFamily = Inter, fontWeight = if (lead) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 12.5.sp, color = if (lead) Halo.mint else Halo.ink, modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.shadow_trades, s.closed, s.wins),
                        fontFamily = Mono, fontSize = 11.sp, color = Halo.muted,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        (if (s.netLamports >= 0) "+" else "−") + fmtSol(kotlin.math.abs(s.netLamports), 4),
                        fontFamily = Mono, fontSize = 12.sp,
                        color = if (s.netLamports > 0) Halo.mint else if (s.netLamports < 0) Halo.red else Halo.muted,
                    )
                }
            }

            // The whole point of the table: one decision, when there is enough to
            // decide on. Under the bar it says so instead of suggesting anything.
            if (closed < ENOUGH) {
                Text(stringResource(R.string.shadow_not_yet, closed, ENOUGH), style = HaloType.small, color = Halo.amber, lineHeight = 16.sp)
            } else if (best != null && yours != null && best.rule != ExitRule.YOURS &&
                best.netLamports > 0 && best.netLamports > yours.netLamports * MARGIN
            ) {
                Column(
                    Modifier.fillMaxWidth().clip(rs(12)).background(Halo.mint.copy(alpha = 0.10f))
                        .border(1.dp, Halo.mint.copy(alpha = 0.4f), rs(12)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(
                            R.string.shadow_switch,
                            stringResource(ruleName(best.rule)),
                            fmtSol(best.netLamports, 4), fmtSol(yours.netLamports, 4), best.closed,
                        ),
                        style = HaloType.small, color = Halo.ink, lineHeight = 17.sp,
                    )
                    GhostButton(stringResource(R.string.shadow_apply), tint = Halo.mint) {
                        applyRule(best.rule)?.let { (tp, sl) ->
                            TraderLoop.setConfig(ctx, cfg.copy(takeProfitPct = tp, stopLossPct = sl))
                            onChange()
                        }
                    }
                }
            }

            // And the question no other wallet can answer about its own safety.
            val (blockedClosed, blockedNet) = blocked
            if (blockedClosed > 0) {
                Text(
                    stringResource(
                        if (blockedNet > 0) R.string.shadow_walls_cost else R.string.shadow_walls_saved,
                        blockedClosed, fmtSol(kotlin.math.abs(blockedNet), 4),
                    ),
                    style = HaloType.small, color = if (blockedNet > 0) Halo.amber else Halo.mint, lineHeight = 16.sp,
                )
            }

            GhostButton(stringResource(R.string.shadow_reset), Modifier.fillMaxWidth(), HIcon.TRASH, tint = Halo.muted) {
                Paper.clear(ctx); onChange()
            }
        }
    }
}

private fun ruleName(rule: String) = when (rule) {
    ExitRule.YOURS -> R.string.shadow_rule_yours
    ExitRule.QUICK -> R.string.shadow_rule_quick
    ExitRule.PATIENT -> R.string.shadow_rule_patient
    ExitRule.TRAILING -> R.string.shadow_rule_trailing
    else -> R.string.shadow_rule_timed
}

/**
 * The two numbers a rule becomes when you accept it.
 *
 * Only the fixed rules can be applied: the loop's exit is a target and a stop, and
 * pretending a trailing rule fits in those two fields would set something that is
 * not the rule that won.
 */
private fun applyRule(rule: String): Pair<Int, Int>? = when (rule) {
    ExitRule.QUICK -> 15 to 10
    ExitRule.PATIENT -> 50 to 20
    else -> null
}
