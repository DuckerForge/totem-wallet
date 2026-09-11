@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The Jupiter-style top of the wallet: total value + a valued portfolio list. */
@Composable
internal fun WalletHero(owner: String?, onSwap: () -> Unit, onSend: () -> Unit, onReceive: () -> Unit) {
    val currency by Settings.currency
    val pv by produceState<PortfolioView?>(initialValue = null, owner, currency) {
        value = owner?.let { runCatching { Portfolio.load(it, currency) }.getOrNull() }
    }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.hero_total), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
            Text(
                pv?.let { fmtFiat(it.total, it.currency) } ?: "…",
                fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = Halo.ink, style = Tabular,
            )
            // actions
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroAction(HIcon.SWAP, stringResource(R.string.swap_btn), Halo.mint, Modifier.weight(1f), owner != null, onSwap)
                HeroAction(HIcon.SEND, stringResource(R.string.send_btn), Halo.mint, Modifier.weight(1f), owner != null, onSend)
                HeroAction(HIcon.RECEIVE, stringResource(R.string.receive_btn), Halo.cyan, Modifier.weight(1f), owner != null, onReceive)
            }
            pv?.holdings?.filter { it.raw > 0 }?.take(6)?.let { list ->
                if (list.isNotEmpty()) {
                    Text(stringResource(R.string.hero_portfolio), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
                    list.forEach { h -> HoldingRow(h, currency) }
                    if ((pv?.unpriced ?: 0) > 0) Text(stringResource(R.string.hero_some_unpriced), fontFamily = Inter, fontSize = 10.5.sp, color = Halo.muted)
                }
            }
        }
    }
}

@Composable
private fun HeroAction(icon: HIcon, label: String, tint: androidx.compose.ui.graphics.Color, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier.clip(rs(14)).background(tint.copy(alpha = if (enabled) 0.10f else 0.04f)).border(1.dp, tint.copy(alpha = if (enabled) 0.4f else 0.15f), rs(14))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        HaloIcon(icon, if (enabled) tint else Halo.muted, 22.dp)
        Text(label, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = if (enabled) Halo.ink else Halo.muted)
    }
}

@Composable
private fun HoldingRow(h: Holding, currency: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(rs(999)).background(Halo.cardSoft), contentAlignment = Alignment.Center) {
            Text(h.symbol.take(2), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Halo.cyan)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(h.symbol, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = Halo.ink, maxLines = 1)
            Text(fmtUi(h.ui) + " " + h.symbol, fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, style = Tabular)
        }
        Text(h.fiat?.let { fmtFiat(it, currency) } ?: "—", fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Halo.ink, style = Tabular)
    }
}
