package com.clearsign.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clearsign.core.SafetyBand
import com.clearsign.core.SafetyFlag
import com.clearsign.core.TokenSafety

/** Green, amber, red — the same three the receipt uses for risks. */
internal fun bandColor(band: SafetyBand): Color = when (band) {
    SafetyBand.GOOD -> Halo.mint
    SafetyBand.MID -> Halo.amber
    SafetyBand.BAD -> Halo.red
}

@Composable
internal fun safetyLabel(flag: SafetyFlag): String = stringResource(
    when (flag) {
        SafetyFlag.NO_WAY_OUT -> R.string.safe_no_way_out
        SafetyFlag.CAN_FREEZE -> R.string.safe_can_freeze
        SafetyFlag.ISSUER_CONTROLLED -> R.string.safe_issuer
        SafetyFlag.CAN_MINT -> R.string.safe_can_mint
        SafetyFlag.WHALE -> R.string.safe_whale
        SafetyFlag.DEV_HEAVY -> R.string.safe_dev_heavy
        SafetyFlag.SERIAL_CREATOR -> R.string.safe_serial
        SafetyFlag.NEW_TOKEN_PROGRAM -> R.string.safe_token2022
        SafetyFlag.SEIZABLE -> R.string.safe_seizable
        SafetyFlag.TRANSFER_HOOK -> R.string.safe_hook
        SafetyFlag.TRANSFER_TAX -> R.string.safe_tax
        SafetyFlag.NON_TRANSFERABLE -> R.string.safe_frozen_forever
        SafetyFlag.DEFAULT_FROZEN -> R.string.safe_default_frozen
        SafetyFlag.THIN -> R.string.safe_thin
        SafetyFlag.FEW_HOLDERS -> R.string.safe_few_holders
        SafetyFlag.UNVERIFIED -> R.string.safe_unverified
    },
)

/**
 * What the coin itself can do to you, next to what the transaction does.
 *
 * A clean transaction can still hand you a token whose creator can freeze it or
 * that nobody will buy back — and that is invisible in the receipt, because on
 * chain it is a perfectly ordinary transfer.
 */
@Composable
internal fun SafetyCard(safety: TokenSafety, symbol: String) {
    if (safety.flags.isEmpty()) return
    val tint = bandColor(safety.band)
    Column(
        Modifier.fillMaxWidth().clip(rs(16)).background(tint.copy(alpha = 0.08f)).border(1.dp, tint.copy(alpha = 0.35f), rs(16)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HaloIcon(if (safety.bad) HIcon.WARNING else HIcon.SHIELD_LOCK, tint, 20.dp)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.safe_title, symbol), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Halo.ink)
                Text(stringResource(R.string.safe_score, safety.score), fontFamily = Inter, fontSize = 11.5.sp, color = tint, style = Tabular)
            }
        }
        safety.flags.forEach { f ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 5.dp).size(5.dp).clip(rs(999)).background(tint))
                Spacer(Modifier.width(8.dp))
                Text(safetyLabel(f), fontFamily = Inter, fontSize = 12.sp, color = Halo.ink, lineHeight = 17.sp)
            }
        }
        Text(stringResource(R.string.safe_note), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, lineHeight = 15.sp)
    }
}

/** The same verdict as one dot, for a list row. Nothing is drawn for a clean coin. */
@Composable
internal fun SafetyDot(safety: TokenSafety?) {
    if (safety == null || safety.band == SafetyBand.GOOD) return
    Box(Modifier.size(7.dp).clip(rs(999)).background(bandColor(safety.band)))
}


/**
 * What you are about to *receive*, judged on its own.
 *
 * The receipt answers "what does this transaction do", and for a coin that is
 * about to be drained that answer is "nothing wrong": you pay, you receive,
 * every address is ordinary, no risk. Buying a rug is an honest transaction.
 *
 * So when something other than SOL is arriving, the receipt also says what the
 * thing arriving *is*: whether it can be sold back, whether its creator can
 * freeze it, and whether its pool is being pulled out from under it right now.
 * Best effort and never in the way of signing. It appears when the answer
 * arrives, and its absence means we could not reach the registry, not that the
 * coin is fine.
 */
@Composable
internal fun IncomingCoinCard(r: com.clearsign.core.Receipt, owner: String?) {
    fun isSol(m: String) = m == com.clearsign.core.NATIVE_SOL_MINT || m == com.clearsign.core.AgentPolicy.WSOL
    val leg = remember(r) {
        r.inflows.firstOrNull { (owner == null || it.owner == owner) && !isSol(it.mint) && it.rawAmount > 0 }
    } ?: return

    var safety by remember(leg.mint) { mutableStateOf<TokenSafety?>(null) }
    var shape by remember(leg.mint) { mutableStateOf<String?>(null) }

    LaunchedEffect(leg.mint) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val c = runCatching { JupiterTokens.candidateOf(leg.mint) }.getOrNull()
            val tok = JupiterTokens.cached(leg.mint)
            val sellable = runCatching { Jupiter.sellableBack(leg.mint, leg.decimals, tok?.usd) }.getOrNull()
            val ext = tok?.let { TokenExtensions.of(leg.mint, it.token2022) }
            safety = tok?.let { com.clearsign.core.assessToken(it.facts(sellable, ext)) }
            // The bold lane is the loosest thing the agent would ever use. If even
            // that turns the coin down, the reason is worth saying out loud here.
            shape = c?.let { com.clearsign.core.passesGate(it, com.clearsign.core.ScanGate.CAREFUL) }
        }
    }

    val s = safety
    if (s == null && shape == null) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        s?.let { SafetyCard(it, leg.symbol) }
        shape?.let { why ->
            Row(
                Modifier.fillMaxWidth().clip(rs(Radius.row)).background(Halo.red.copy(alpha = 0.12f))
                    .border(1.dp, Halo.red.copy(alpha = 0.45f), rs(Radius.row)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                HaloIcon(HIcon.WARNING, Halo.red, 15.dp)
                Spacer(Modifier.width(9.dp))
                Text(
                    stringResource(R.string.coin_shape_bad, leg.symbol, why),
                    fontFamily = Inter, fontSize = 12.5.sp, color = Halo.ink, lineHeight = 18.sp,
                )
            }
        }
    }
}
