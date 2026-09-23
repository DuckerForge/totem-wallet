package com.clearsign.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One swap, built in one place for every screen that offers one. The swap sheet did this
 * well (Ultra first, our fee account only on the old route, the receipt read from the real
 * bytes and tidied so the pool has a name); a second caller meant two copies, and the feed
 * could show a different receipt from the sheet for the same operation. Both callers get the
 * same bytes and the same words.
 */
internal data class SwapBuilt(
    val tx: ByteArray,
    val analyzed: ReceiptEngine.Analyzed,
    val quote: Jupiter.Quote,
    val pair: SwapPair,
    val outUi: String,
    val outSymbol: String,
    val at: Long,
    /** Set when Jupiter Ultra built it: its slippage, its priority fee, and Jupiter lands it. */
    val ultraRequestId: String?,
    val ultraSlippageBps: Int,
    val gasless: Boolean,
) {
    /**
     * Two builds describe the same trade when route and destinations match: a fresher price on
     * the same shape can be swapped in under the reader; a different shape has to be offered.
     */
    fun sameShape(other: SwapBuilt): Boolean =
        quote.routeLabels == other.quote.routeLabels &&
            analyzed.receipt.distributions.size == other.analyzed.receipt.distributions.size

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

internal object SwapBuild {
    /**
     * One side of the trade, reduced to what building needs: the sheet has a richer picker type,
     * the feed has a mint and whatever the registry knows.
     */
    data class Side(val mint: String, val symbol: String, val decimals: Int)

    /**
     * Quote, build, simulate, tidy; null when nothing could be built, shown instead of a button
     * that cannot work. Ultra first: route, slippage and priority fee chosen and landed by
     * Jupiter, out of the sandwich bots' sight. When Ultra does not answer, swap v1 with our fee
     * account when one exists.
     */
    suspend fun build(ctx: Context, owner: String, from: Side, to: Side, raw: Long, known: Jupiter.Quote? = null): SwapBuilt? {
        val ultra = withContext(Dispatchers.IO) { runCatching { JupiterUltra.order(from.mint, to.mint, raw, owner) }.getOrNull() }
        val feeAccount = if (ultra != null) null else withContext(Dispatchers.IO) { Jupiter.feeAccountIfUsable(to.mint) }
        val priced = ultra?.asQuote()
            ?: (if (feeAccount != null) known else null)
            ?: withContext(Dispatchers.IO) { Jupiter.quote(from.mint, to.mint, raw, feeBps = 0) }
            ?: known
            ?: return null
        val tx = ultra?.tx ?: withContext(Dispatchers.IO) {
            Jupiter.swapTransaction(priced, owner, feeAccount) ?: Jupiter.swapTransaction(priced, owner, null)
        } ?: return null
        val analyzed = withContext(Dispatchers.IO) {
            ReceiptEngine.analyze(ctx, BlocklistScanner(ctx), tx, owner, null, requireSim = false)
        }
        return SwapBuilt(
            tx = tx,
            analyzed = tidy(ctx, analyzed, owner),
            quote = priced,
            pair = SwapPair(
                outMint = from.mint, outSymbol = from.symbol, outUi = fmtUnits(priced.inAmount, from.decimals),
                inMint = to.mint, inSymbol = to.symbol, inUi = fmtUnits(priced.outAmount, to.decimals),
            ),
            outUi = fmtUnits(priced.outAmount, to.decimals),
            outSymbol = to.symbol,
            at = System.currentTimeMillis(),
            ultraRequestId = ultra?.requestId,
            ultraSlippageBps = ultra?.slippageBps ?: 0,
            gasless = ultra?.gasless ?: false,
        )
    }

    /**
     * A swap is not a transfer, and the generic reading made it look like one: the "new unknown
     * recipient" is the AMM vault and the "account close" is the wSOL account unwrapping. Amounts
     * and destination stay visible and recorded; the pool gets its name and the two transfer-only
     * warnings go.
     */
    fun tidy(ctx: Context, analyzed: ReceiptEngine.Analyzed, owner: String): ReceiptEngine.Analyzed {
        val r = analyzed.receipt
        // Short, because this name is read under a circle on a map and at the head
        // of a row. The full route lives one line up, in the summary.
        val pool = ctx.getString(R.string.swap_pool_short)
        val drop = setOf(com.clearsign.core.RiskFlag.NEW_UNKNOWN_RECIPIENT, com.clearsign.core.RiskFlag.ACCOUNT_CLOSE)
        return analyzed.copy(
            receipt = r.copy(
                risks = r.risks.filter { it.flag !in drop },
                recipientLabel = r.recipientLabel ?: r.primaryRecipient?.takeIf { it != owner }?.let { pool },
                distributions = r.distributions.map { d ->
                    when {
                        d.label != null -> d
                        // An account this transaction opens. Not your token account for the coin, that one belongs
                        // to you; these belong to the route, and "Account for CATE" put one wrong name on two accounts.
                        d.isNewAccount -> d.copy(label = ctx.getString(R.string.swap_new_account))
                        d.address == owner -> d
                        else -> d.copy(label = pool)
                    }
                },
            ),
        )
    }
}
