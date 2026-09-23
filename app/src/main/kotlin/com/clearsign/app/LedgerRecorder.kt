package com.clearsign.app

import android.content.Context
import com.clearsign.core.Receipt
import com.clearsign.core.RiskFlag
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    /** Append, price it in the background, and ask the chain whether it went through. */
    fun record(ctx: Context, e: LedgerEntry) {
        Ledger.append(ctx, e)
        if (e.hasValue) FiatRates.fillAsync(ctx.applicationContext, e.id, e.ym, e.outflows + e.inflows)
        watch(ctx, e)
    }

    /** The tag a row gets when the chain refused the transaction after the node had taken it. */
    const val FAILED = "failed"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val WATCH_STEPS_MS = longArrayOf(4_000, 10_000, 25_000, 60_000)

    /**
     * A row is written when the node accepts the bytes, and the chain can then refuse them: a
     * swap past its slippage, a send that lost the blockhash race. That row said "sent" forever
     * and the P&L counted it. Now the chain is asked four times over a hundred seconds; a refusal
     * tags the row, the receipts say it, the analytics skip it. Silence changes nothing: not knowing is not failed.
     */
    fun watch(ctx: Context, e: LedgerEntry) {
        val sig = e.signature ?: return
        if (!e.sent || e.tags.contains(FAILED)) return
        val app = ctx.applicationContext
        scope.launch {
            for (wait in WATCH_STEPS_MS) {
                delay(wait)
                when (runCatching { SolanaRpc.verdictOf(SolanaRpc.urlFor(e.cluster), sig) }.getOrNull()) {
                    true -> return@launch
                    false -> { Ledger.update(app, e.id, e.ym) { it.copy(tags = it.tags + FAILED) }; return@launch }
                    null -> {}
                }
            }
        }
    }

    /** A move the app made without a receipt to analyse: what came in or went out, and from which wallet. */
    fun plainMove(
        ctx: Context, kind: String, wallet: String, signature: String?, inflows: List<Leg>, outflows: List<Leg>,
        label: String?, feeLamports: Long = 0L, feePaidByMe: Boolean = true,
    ): LedgerEntry = LedgerEntry(
        id = newId(), groupId = newId(), at = System.currentTimeMillis(), kind = kind, dApp = ctx.getString(R.string.app_name), host = null,
        pkg = ctx.packageName, cluster = null, wallet = wallet, signature = signature, sent = signature != null, txIndex = 0, txCount = 1,
        outflows = outflows, inflows = inflows, feeLamports = feeLamports, feePaidByMe = feePaidByMe, counterparties = emptyList(),
        primaryRecipient = null, recipientLabel = label, risks = emptyList(),
    )
}
