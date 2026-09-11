package com.clearsign.core

import java.util.Locale
import kotlin.math.abs

/**
 * Builds the human-readable receipt from the raw effects of a transaction.
 * Given the signer's wallet and the simulated balance deltas, it separates what
 * leaves from what arrives, picks the primary recipient, attaches the trust
 * badge, and renders plain-language lines in the user's locale.
 */
class ReceiptBuilder(private val trust: AddressTrust) {

    fun build(
        myWallet: String,
        deltas: List<BalanceDelta>,
        instructions: List<DecodedInstruction>,
        feeLamports: Long,
        risks: List<Risk>,
        stats: TxStats? = null,
    ): Receipt {
        val mine = deltas.filter { it.owner == myWallet }
        val outflows = mine.filter { it.rawAmount < 0 }
        val inflows = mine.filter { it.rawAmount > 0 }

        val distributions = distributionsOf(deltas, myWallet, outflows)
        val recipient = primaryRecipient(instructions, deltas, myWallet)
            ?: distributions.firstOrNull()?.address
        val level = recipient?.let { trust.level(it) } ?: TrustLevel.NEW

        return Receipt(
            primaryRecipient = recipient,
            recipientLabel = recipient?.let { trust.label(it) },
            recipientTrust = level,
            outflows = outflows,
            inflows = inflows,
            feeLamports = feeLamports,
            risks = risks,
            distributions = distributions,
            stats = stats,
            deltas = deltas,
        )
    }

    /**
     * Every external wallet that receives value, largest first, each with its
     * share of the total outflow. Reveals a payment that splits across several
     * wallets (recipient + hidden fee/referral accounts) instead of one total.
     * The largest same-mint receiver is the "main" recipient; the smaller
     * same-mint ones are flagged as fee/side splits.
     */
    private fun distributionsOf(
        deltas: List<BalanceDelta>,
        myWallet: String,
        outflows: List<BalanceDelta>,
    ): List<RecipientShare> {
        val received = deltas
            .filter { it.owner != myWallet && it.rawAmount > 0 }
            .sortedByDescending { abs(it.rawAmount) }
        if (received.isEmpty()) return emptyList()

        // Total the user pays out, per mint, so shares are computed against the
        // matching asset (a SOL fee is a share of the SOL you spend, not of USDC).
        val outByMint = outflows.groupBy { it.mint }
            .mapValues { (_, ds) -> ds.sumOf { abs(it.rawAmount) } }
        val topByMint = received.groupBy { it.mint }
            .mapValues { (_, ds) -> ds.maxByOrNull { abs(it.rawAmount) }?.owner }

        return received.map { d ->
            val denom = outByMint[d.mint]?.takeIf { it > 0 } ?: abs(d.rawAmount)
            RecipientShare(
                address = d.owner,
                label = trust.label(d.owner),
                trust = trust.level(d.owner),
                delta = d,
                share = abs(d.rawAmount).toDouble() / denom.toDouble(),
                // A fee is a side-payment to an *existing* wallet; a created account
                // is rent, not a payment, so it is never labelled a fee.
                isFee = !d.createdAccount && topByMint[d.mint] != d.owner,
                isNewAccount = d.createdAccount,
            )
        }
    }

    /** Prefer an explicit instruction destination; else the largest external receiver. */
    private fun primaryRecipient(
        instructions: List<DecodedInstruction>,
        deltas: List<BalanceDelta>,
        myWallet: String,
    ): String? {
        instructions.firstOrNull { it.destination != null }?.destination?.let { return it }
        return deltas.filter { it.owner != myWallet && it.rawAmount > 0 }
            .maxByOrNull { abs(it.rawAmount) }?.owner
    }

    companion object {
        private const val LAMPORTS_PER_SOL = 1_000_000_000.0

        /** Render the receipt as plain lines in [locale] (for TUI/logs and as UI source of truth). */
        fun render(receipt: Receipt, locale: String = "en"): List<String> {
            val lines = mutableListOf<String>()
            val who = receipt.recipientLabel
                ?: receipt.primaryRecipient?.let { shorten(it) }
                ?: "?"
            val badge = Localization.trustWord(receipt.recipientTrust, locale)

            for (o in receipt.outflows) {
                lines += "${Localization.t(Msg.YOU_SEND, locale)} ${fmt(o)} " +
                    "${Localization.t(Msg.TO, locale)} $who ($badge)"
            }
            if (receipt.inflows.isEmpty()) {
                lines += Localization.t(Msg.NOTHING_ELSE, locale)
            } else {
                for (i in receipt.inflows) {
                    lines += "${Localization.t(Msg.YOU_RECEIVE, locale)} ${fmt(i)}"
                }
            }
            lines += "${Localization.t(Msg.FEE, locale)}: ${String.format(Locale.ROOT, "%.6f", receipt.feeLamports / LAMPORTS_PER_SOL)} SOL"
            if (receipt.risks.isNotEmpty()) {
                lines += Localization.t(Msg.REVIEW_WARNINGS, locale)
                receipt.risks.forEach { lines += "  [${it.severity}] ${it.detail}" }
            }
            return lines
        }

        private fun fmt(d: BalanceDelta): String =
            "${String.format(Locale.ROOT, "%.${minOf(d.decimals, 6)}f", abs(d.uiAmount))} ${d.symbol}"

        private fun shorten(addr: String, ends: Int = 4): String =
            if (addr.length <= ends * 2) addr else "${addr.take(ends)}…${addr.takeLast(ends)}"
    }
}
