package com.clearsign.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * What a budget's numbers mean before the money moves.
 *
 * The person sets a budget and two ceilings; the loop derives a slice from
 * them; Jupiter accepts an on-chain order only from about five dollars up.
 * Nobody should have to do that arithmetic in their head, or find out from a
 * red line after the first buy that the slice was too small for the chain to
 * guard it. So the same sentence appears wherever a number is set: at
 * creation, in the rules, at start.
 */
object BudgetMath {
    /** Jupiter's floor for a trigger order, measured on the 15th of September. */
    const val ORDER_MIN_USD = 5.0
    const val ATA_RENT = 2_040_000L

    data class Sizing(
        val sliceLamports: Long,
        val sliceUsd: Double?,
        /** Null when the SOL price is unknown. */
        val onChain: Boolean?,
        /** How many positions the budget can actually hold at this slice. */
        val fits: Int,
        /** The per-move ceiling that would put the slice on the chain. */
        val perTxNeeded: Long?,
    )

    fun sizing(capLamports: Long, perTxLamports: Long, askAboveLamports: Long, slicePct: Int, slots: Int, solUsd: Double?): Sizing {
        val ceiling = minOf(perTxLamports, askAboveLamports.takeIf { it > 0 } ?: perTxLamports).coerceAtLeast(0L)
        val slice = ceiling * slicePct / 100
        val usd = solUsd?.takeIf { it > 0 }?.let { slice / 1e9 * it }
        val fits = if (slice > 0) (capLamports / (slice + ATA_RENT)).toInt().coerceIn(0, slots) else 0
        // A slice of zero percent means there is no slice, not a slice so small
        // that the ceiling it would need overflows a Long and comes back negative.
        val needed = solUsd?.takeIf { it > 0 && slicePct > 0 }?.let { ((ORDER_MIN_USD / it * 1e9) * 100 / slicePct).toLong() + 1 }
        return Sizing(slice, usd, usd?.let { it >= ORDER_MIN_USD }, fits, needed)
    }

    /** What the fee reserve leaves behind, so the last transaction can still pay for itself. */
    const val FEE_RESERVE = 5_000L

    /**
     * How much of the budget goes home when it is closed.
     *
     * Null in, null out, and that is the whole point. A balance that could not
     * be read is not a balance of zero: reading it as zero means sending nothing
     * home and then forgetting the key, which loses everything still sitting
     * there. Paid for once, with a node that was rate-limiting.
     */
    fun sweepBack(balanceLamports: Long?, reserve: Long = FEE_RESERVE): Long? =
        balanceLamports?.let { if (it > reserve) it - reserve else 0L }
}

/** The sentence, with the SOL price fetched once. Amounts in lamports. */
@Composable
internal fun SizingNote(capLamports: Long, perTxLamports: Long, askAboveLamports: Long, slicePct: Int, slots: Int) {
    var solUsd by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) {
        solUsd = withContext(Dispatchers.IO) { runCatching { Prices.usd(listOf(com.clearsign.core.NATIVE_SOL_MINT))[com.clearsign.core.NATIVE_SOL_MINT] }.getOrNull() }
    }
    val s = BudgetMath.sizing(capLamports, perTxLamports, askAboveLamports, slicePct, slots, solUsd)
    val usd = s.sliceUsd?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "?"
    val tint = when (s.onChain) { true -> Halo.mint; false -> Halo.amber; null -> Halo.muted }
    Column(
        Modifier.fillMaxWidth().clip(rs(14)).background(Halo.cardSoft).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.size_each), style = HaloType.small, color = Halo.muted, modifier = Modifier.weight(1f))
            Text(fmtSol(s.sliceLamports, 4) + " SOL", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Halo.ink, style = Tabular)
            Spacer(Modifier.width(8.dp))
            Text("$usd $", fontFamily = Mono, fontSize = 12.sp, color = tint, style = Tabular)
        }
        Text(
            when (s.onChain) {
                true -> stringResource(R.string.size_onchain_ok)
                false -> stringResource(R.string.size_onchain_no)
                null -> stringResource(R.string.size_noprice)
            },
            style = HaloType.small, color = tint, lineHeight = 16.sp,
        )
        if (s.fits < slots && s.sliceLamports > 0) Text(stringResource(R.string.size_fits, s.fits, slots), style = HaloType.small, color = Halo.amber)
    }
}
