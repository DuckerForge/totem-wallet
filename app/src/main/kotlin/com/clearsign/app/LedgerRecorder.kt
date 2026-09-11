package com.clearsign.app

import android.content.Context
import com.clearsign.core.Receipt
import com.clearsign.core.RiskFlag
import java.util.UUID

/** Turns a receipt (what the user saw) into a ledger entry (what is kept). */
object LedgerRecorder {
    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(20)

    fun fromReceipt(
        at: Long, kind: String, dApp: String, host: String?, pkg: String?, cluster: String?, wallet: String,
        r: Receipt?, signature: String?, sent: Boolean, txIndex: Int, txCount: Int, groupId: String,
        attestation: String?, attestationSig: String?, recipientLabelFallback: String? = null,
    ): LedgerEntry = LedgerEntry(
        id = newId(), groupId = groupId, at = at, kind = kind, dApp = dApp, host = host, pkg = pkg, cluster = cluster, wallet = wallet,
        signature = signature, sent = sent, txIndex = txIndex, txCount = txCount,
        outflows = r?.outflows?.map { Leg(it.mint, it.symbol, it.decimals, it.rawAmount) } ?: emptyList(),
        inflows = r?.inflows?.map { Leg(it.mint, it.symbol, it.decimals, it.rawAmount) } ?: emptyList(),
        feeLamports = r?.feeLamports ?: 0L,
        feePaidByMe = r?.risks?.none { it.flag == RiskFlag.FOREIGN_FEE_PAYER } ?: true,
        counterparties = r?.distributions?.map { d ->
            Counterparty(d.address, d.label, d.trust.name, d.isFee, d.isNewAccount, d.delta.rawAmount, d.delta.mint, d.delta.symbol, d.delta.decimals)
        } ?: emptyList(),
        primaryRecipient = r?.primaryRecipient, recipientLabel = r?.recipientLabel ?: recipientLabelFallback,
        risks = r?.risks?.map { RiskNote(it.flag.name, it.severity.name, it.detail) } ?: emptyList(),
        attestation = attestation, attestationSig = attestationSig,
    )

    /** Append, then price it in the background. */
    fun record(ctx: Context, e: LedgerEntry) {
        Ledger.append(ctx, e)
        if (e.hasValue) FiatRates.fillAsync(ctx.applicationContext, e.id, e.ym, e.outflows + e.inflows)
    }
}
