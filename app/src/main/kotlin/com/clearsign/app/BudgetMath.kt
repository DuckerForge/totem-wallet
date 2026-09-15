package com.clearsign.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
        val needed = solUsd?.takeIf { it > 0 }?.let { ((ORDER_MIN_USD / it * 1e9) * 100 / slicePct).toLong() + 1 }
        return Sizing(slice, usd, usd?.let { it >= ORDER_MIN_USD }, fits, needed)
    }
}

/** The sentence, with the SOL price fetched once. Amounts in lamports. */
@Composable
internal fun SizingNote(capLamports: Long, perTxLamports: Long, askAboveLamports: Long, slicePct: Int, slots: Int) {
    var solUsd by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) {
        solUsd = withContext(Dispatchers.IO) { runCatching { Prices.usd(listOf(com.clearsign.core.NATIVE_SOL_MINT))[com.clearsign.core.NATIVE_SOL_MINT] }.getOrNull() }
    }
    val s = BudgetMath.sizing(capLamports, perTxLamports, askAboveLamports, slicePct, slots, solUsd)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val usd = s.sliceUsd?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "?"
        val head = stringResource(R.string.size_slice, fmtSol(s.sliceLamports, 4), usd)
        when (s.onChain) {
            true -> Banner(head + " " + stringResource(R.string.size_onchain_ok), Halo.mint, HIcon.SHIELD_LOCK)
            false -> Banner(head + " " + stringResource(R.string.size_onchain_no, fmtSol(s.perTxNeeded ?: 0L, 3)), Halo.amber, HIcon.WARNING)
            null -> Banner(head + " " + stringResource(R.string.size_noprice), Halo.muted, HIcon.INFO)
        }
        if (s.fits < slots && s.sliceLamports > 0) Banner(stringResource(R.string.size_fits, s.fits, slots), Halo.amber, HIcon.WARNING)
    }
}
