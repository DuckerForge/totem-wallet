package com.clearsign.app

import android.content.Context
import android.util.Log
import com.clearsign.core.Receipt
import com.clearsign.core.ReceiptBuilder
import com.clearsign.core.Risk
import com.clearsign.core.RiskEngine
import com.clearsign.core.RiskFlag
import com.clearsign.core.ScanResult
import com.clearsign.core.Severity
import com.clearsign.core.SimulationGuard
import com.clearsign.core.TransactionScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

private const val TAG = "ClearSign-Engine"

/**
 * The receipt engine: turns raw transaction bytes into the plain-language
 * receipt (what moves, to whom, which risks) that every ClearSign screen shows —
 * the MWA endpoint for dApp requests and the wallet's own Send flow alike.
 * Everything a screen needs is derived here once; the UI only renders it.
 */
object ReceiptEngine {

    /** Programs the decoder already itemizes — no IDL lookup needed. */
    private val NATIVE_PROGRAMS = setOf(
        SolanaTx.SYSTEM_PROGRAM, SolanaTx.TOKEN_PROGRAM, SolanaTx.TOKEN_2022_PROGRAM, SolanaTx.COMPUTE_BUDGET_PROGRAM,
        "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL", "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr", "AddressLookupTab1e1111111111111111111111111",
    )

    /** Everything we learned about one payload, kept for the anti-TOCTOU re-check. */
    data class Analyzed(
        val payload: ByteArray,
        val receipt: Receipt,
        val destinations: List<String>,
        val txKeys: Set<String>?,
        /**
         * The mints this analysis was told to expect. Carried so the pre-signing
         * re-check can watch the same accounts: without it the preview saw a coin
         * arriving and the re-check did not, the two delta sets disagreed, and
         * every purchase of a coin you had never held was blocked as "the state
         * changed" when nothing had.
         */
        val expectMints: List<String> = emptyList(),
    )

    /**
     * Build a receipt from the real transaction bytes. We itemize what we can
     * decode on-device (SOL transfers, token ops) and verify the whole thing by
     * simulating it on the cluster — simulation is what handles v0 + lookup
     * tables + SPL programs, so a transaction the network accepts is not blocked
     * as "unverifiable".
     *
     * Wire work is concurrent: lookup-table resolution → (simulation ∥ pre-state)
     * ∥ community reputation. Everything else is local.
     */
    suspend fun analyze(
        ctx: Context,
        scanner: TransactionScanner,
        payload: ByteArray,
        myWallet: String,
        cluster: String?,
        requireSim: Boolean,
        /** Mints the caller expects to receive; see [SolanaRpc.simulateEffects]. */
        expectMints: List<String> = emptyList(),
    ): Analyzed = coroutineScope {
        val rpc = SolanaRpc.urlFor(cluster)
        // Trust from the local address book: enables trusted/known badges and
        // address-poisoning look-alike detection against addresses the user knows.
        val trust = Contacts.addressTrust(ctx)
        val hasTrustData = Contacts.allowlist(ctx).isNotEmpty() || Contacts.history(ctx).isNotEmpty()
        val decoded = SolanaTx.decode(payload)
        val instructions = decoded?.let { SolanaTx.instructions(it) } ?: emptyList()
        // Required signers beyond the fee payer / the user: the first numRequiredSignatures keys.
        val otherSigners = decoded?.let { d -> d.staticAccountKeys.take(d.numRequiredSignatures).filter { it != myWallet } } ?: emptyList()

        // v0: addresses loaded from lookup tables are part of what the tx can
        // touch — resolve them so the split detection sees Jupiter's vaults too.
        val (loadedW, loadedR) = if (decoded != null && decoded.lookups.isNotEmpty()) {
            withContext(Dispatchers.IO) { SolanaRpc.resolveLookups(rpc, decoded.lookups) }
        } else emptyList<String>() to emptyList()
        val txKeys = decoded?.let { (it.staticAccountKeys + loadedW + loadedR).toSet() }

        // Watch every writable wallet (bar the owner) so a payment that fans out to
        // a recipient + hidden fee/referral wallets shows each split, not one total.
        val destinations = decoded?.let { (it.writableKeys + loadedW).filter { k -> k != myWallet }.distinct() } ?: emptyList()

        val simD = async(Dispatchers.IO) { SolanaRpc.simulateEffects(rpc, payload, myWallet, destinations, txKeys, expectMints) }
        // On-chain community reputation on the primary recipient, bounded so a slow
        // devnet read can never hold the receipt hostage.
        val primaryDest = instructions.firstOrNull { it.destination != null && it.kind != com.clearsign.core.InstructionKind.ASSIGN_OWNER }?.destination
        val repD = async(Dispatchers.IO) {
            primaryDest?.let { dest -> withTimeoutOrNull(4_000) { runCatching { Reputation.fetch(dest) }.getOrNull() } }
        }
        // Counterparty intel (history / brand-new) feeds the effect-based risks inline,
        // not just the address sheet: "empties your wallet into a wallet with zero history".
        val intelD = async(Dispatchers.IO) {
            primaryDest?.let { dest -> withTimeoutOrNull(4_000) { runCatching { SolanaRpc.walletIntel(rpc, dest) }.getOrNull() } }
        }
        // Fee sanity needs the going rate — only worth a call when the tx sets a priority price.
        val cb = decoded?.let { SolanaTx.computeBudget(it) }
        val feeD = async(Dispatchers.IO) {
            if (cb?.priceMicroLamports == null) null
            else withTimeoutOrNull(3_000) { runCatching { SolanaRpc.recentPrioritizationFees(rpc, decoded.writableKeys.take(6)) }.getOrNull() }
        }
        // Unknown programs: read their on-chain IDL so the receipt names the method being called.
        val callsD = async(Dispatchers.IO) {
            val d = decoded ?: return@async emptyList()
            withTimeoutOrNull(4_000) {
                d.instructions.mapNotNull { ix ->
                    val pid = d.staticAccountKeys.getOrNull(ix.programIdIndex) ?: return@mapNotNull null
                    if (pid in NATIVE_PROGRAMS) null else pid to ix.data
                }.take(6).map { (pid, data) -> async { runCatching { AnchorIdl.decode(ctx, rpc, pid, data) }.getOrNull() } }.mapNotNull { it.await() }
            } ?: emptyList()
        }

        // Real reputation scan: match every address the tx touches against the
        // bundled blocklist (known drainers / burn / sanctioned) → BLOCKED_MALICIOUS.
        val scan = try {
            scanner.scan(payload, instructions.mapNotNull { it.destination } + loadedW)
        } catch (e: Exception) {
            Log.w(TAG, "scan failed", e); ScanResult()
        }

        val outcome = simD.await()
        Log.i(TAG, "sim outcome=${outcome::class.simpleName} deltas=${(outcome as? SolanaRpc.SimOutcome.Ok)?.deltas?.size ?: 0}")
        val deltas = (outcome as? SolanaRpc.SimOutcome.Ok)?.deltas ?: emptyList()

        // Base risks from the instructions (unlimited approval, setAuthority, …).
        // Drop NEW_UNKNOWN_RECIPIENT only when there's no address book at all,
        // where every address is "new" and the warning would be pure noise.
        val engine = RiskEngine(deviceLocaleTag())
        val baseRisks = engine
            .assess(instructions, trust, scan, simulationSucceeded = true, myWallet = myWallet, otherSigners = otherSigners)
            .filter { hasTrustData || it.flag != RiskFlag.NEW_UNKNOWN_RECIPIENT }
        val intel = intelD.await()
        val units = (outcome as? SolanaRpc.SimOutcome.Ok)?.computeUnits
        // Priority fee = price(µlamports/CU) × units / 1e6, rounded up.
        val priorityFee = cb?.priceMicroLamports?.let { price -> (units ?: cb.unitLimit)?.let { (price * it + 999_999L) / 1_000_000L } }
        val median = feeD.await()
        val effectRisks = engine.assessEffects(
            deltas,
            com.clearsign.core.EffectContext(
                myWallet = myWallet,
                preBalances = (outcome as? SolanaRpc.SimOutcome.Ok)?.preBalances ?: emptyMap(),
                feePayer = decoded?.feePayer,
                primaryRecipient = primaryDest ?: deltas.filter { it.owner != myWallet && it.rawAmount > 0 && !it.createdAccount }.maxByOrNull { it.rawAmount }?.owner,
                recipientBrandNew = intel?.isBrandNew,
                priorityFeeLamports = priorityFee,
                priorityPriceMicroLamports = cb?.priceMicroLamports,
                medianPriorityPriceMicroLamports = median,
            ),
            trust,
        )

        val simRisk = when (outcome) {
            is SolanaRpc.SimOutcome.Ok -> null
            is SolanaRpc.SimOutcome.Failed -> Risk(
                RiskFlag.SIMULATION_FAILED,
                if (requireSim) Severity.DANGER else Severity.WARN,
                ctx.getString(R.string.risk_sim_failed, outcome.err),
            )
            SolanaRpc.SimOutcome.Unavailable -> Risk(
                RiskFlag.SIMULATION_FAILED,
                Severity.WARN,
                ctx.getString(R.string.risk_sim_unavailable),
            )
        }
        val rep = repD.await()
        val repRisk = if (rep?.verdict == Reputation.Verdict.FLAGGED) {
            Risk(RiskFlag.COMMUNITY_FLAGGED, Severity.DANGER, ctx.getString(R.string.risk_community, rep.voters, String.format(Locale.ROOT, "%.3f", rep.stakedSol)))
        } else null

        val risks = (baseRisks + effectRisks + listOfNotNull(simRisk, repRisk)).distinctBy { it.flag to it.detail }.sortedByDescending { it.severity.ordinal }
        val baseFee = (decoded?.numRequiredSignatures ?: 1) * 5_000L
        val stats = decoded?.let { d ->
            val logs = (outcome as? SolanaRpc.SimOutcome.Ok)?.logCount ?: 0
            val destCount = deltas.count { it.owner != myWallet && it.rawAmount > 0 }
            com.clearsign.core.TxStats(
                version = d.version,
                instructionCount = d.instructions.size,
                accountsTotal = d.staticAccountKeys.size + loadedW.size + loadedR.size,
                writableAccounts = d.writableAccounts + loadedW.size,
                signerAccounts = d.numRequiredSignatures,
                programs = d.programs,
                computeUnits = units,
                computeUnitLimit = cb?.unitLimit,
                computeUnitPriceMicroLamports = cb?.priceMicroLamports,
                baseFeeLamports = baseFee,
                priorityFeeLamports = priorityFee,
                logCount = logs,
                destinationsCount = destCount,
                networkMedianPriceMicroLamports = median,
            )
        }
        val fee = baseFee + (stats?.priorityFeeLamports ?: 0L)
        var receipt = ReceiptBuilder(trust).build(myWallet, deltas, instructions, fee, risks, stats).copy(calls = callsD.await())
        // The signer's own wallet is not a stranger: name it and trust it (rent refunds, self-transfers).
        if (receipt.primaryRecipient == myWallet) {
            receipt = receipt.copy(recipientTrust = com.clearsign.core.TrustLevel.TRUSTED, recipientLabel = ctx.getString(R.string.recipient_self))
        }
        Analyzed(payload, receipt, destinations, txKeys, expectMints)
    }

    /** Analyze every payload concurrently (bounds latency to ~1 analysis). */
    suspend fun analyzeAll(ctx: Context, scanner: TransactionScanner, payloads: List<ByteArray>, myWallet: String, cluster: String?, requireSim: Boolean): List<Analyzed> =
        coroutineScope { payloads.map { p -> async { analyze(ctx, scanner, p, myWallet, cluster, requireSim) } }.map { it.await() } }

    /**
     * Anti-TOCTOU: re-simulate each payload in the instant before signing and abort
     * if the balance effects drifted from the previewed receipt (beyond the small
     * tolerance a live market legitimately moves). Returns the drift risk, or null
     * when everything still matches — or simulation is unavailable: we don't
     * fabricate drift out of a network hiccup. All payloads are re-checked concurrently.
     */
    suspend fun driftGuard(items: List<Analyzed>, myWallet: String, cluster: String?): Risk? = coroutineScope {
        val rpc = SolanaRpc.urlFor(cluster)
        val checks = items.mapIndexed { i, a ->
            async(Dispatchers.IO) {
                val fresh = (SolanaRpc.simulateEffects(rpc, a.payload, myWallet, a.destinations, a.txKeys, a.expectMints)
                    as? SolanaRpc.SimOutcome.Ok)?.deltas ?: return@async null
                val guard = SimulationGuard.confirm(a.receipt.deltas, fresh)
                if (guard.driftDetected) { Log.w(TAG, "STATE_DRIFT on payload $i"); guard.risk } else null
            }
        }
        checks.map { it.await() }.firstOrNull { it != null }
    }
}
