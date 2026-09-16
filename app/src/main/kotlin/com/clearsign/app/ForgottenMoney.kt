package com.clearsign.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DecimalFormat

/**
 * The card that says whether this wallet left money behind on Orca or Raydium.
 *
 * It is quiet by design. Most wallets have never provided liquidity, and for
 * them this is one line saying both venues were checked and there is nothing
 * waiting. When there is something, it says how much, in the pool's own two
 * coins and in money, and sends the person to the venue's page to collect it.
 *
 * It never offers a collect button of its own. Building those instructions
 * would mean carrying two more programs' worth of maths, and a receipt the
 * person could not check is exactly what this app exists to avoid.
 */
@Composable
internal fun ForgottenMoneyCard(owner: String?, refreshKey: Int = 0) {
    val ctx = LocalContext.current
    if (owner == null) return
    val found by produceState<List<LpFees.Found>?>(initialValue = null, owner, refreshKey) {
        value = withContext(Dispatchers.IO) { runCatching { LpFees.of(SolanaRpc.urlFor(null), owner) }.getOrDefault(emptyList()) }
    }
    val rows = found ?: return

    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HaloIcon(HIcon.GEM, if (rows.isEmpty()) Halo.muted else Halo.mint, 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.lp_title),
                    fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = if (rows.isEmpty()) Halo.muted else Halo.mint, modifier = Modifier.weight(1f),
                )
            }
            if (rows.isEmpty()) {
                Text(stringResource(R.string.lp_none), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted, lineHeight = 16.sp)
                return@Column
            }
            rows.forEach { f ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            f.owed.venue.label + " · " + f.symbolA + "/" + f.symbolB,
                            fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp,
                            color = Halo.ink, modifier = Modifier.weight(1f),
                        )
                        f.usd?.let {
                            Text(money(it), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Halo.mint)
                        }
                    }
                    Text(
                        stringResource(R.string.lp_owed, amount(f.uiA), f.symbolA, amount(f.uiB), f.symbolB),
                        fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, lineHeight = 15.sp,
                    )
                    if (f.owed.abandoned) {
                        Text(stringResource(R.string.lp_abandoned), fontFamily = Inter, fontSize = 11.sp, color = Halo.amber, lineHeight = 15.sp)
                    }
                }
            }
            Text(stringResource(R.string.lp_atleast), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, lineHeight = 15.sp)
            rows.map { it.owed.venue }.distinct().forEach { v ->
                GhostButton(stringResource(R.string.lp_open, v.label), icon = HIcon.EXTERNAL, tint = Halo.mint) {
                    Haptics.tick(ctx)
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(v.site))) }
                }
            }
        }
    }
}

private fun amount(v: Double): String = when {
    v == 0.0 -> "0"
    v < 0.001 -> DecimalFormat("#.########").format(v)
    v < 1 -> DecimalFormat("#.####").format(v)
    else -> DecimalFormat("#,##0.##").format(v)
}

private fun money(v: Double): String = "$" + if (v < 1) DecimalFormat("0.00").format(v) else DecimalFormat("#,##0.00").format(v)
