package com.clearsign.core

/**
 * The device/network boundary. The pure engine above depends only on these
 * interfaces; the Android/Seeker app provides the real implementations
 * (Seed Vault, Mobile Wallet Adapter, Helius RPC, Blockaid). Keeping them as
 * ports is what lets the whole safety brain be unit-tested off-device.
 */

/** Simulates a transaction and returns the resulting balance deltas. */
interface Simulator {
    /** @return deltas, or null if simulation failed (never blind-sign on null). */
    fun simulate(serializedTx: ByteArray): List<BalanceDelta>?
}

/** Decodes raw transaction bytes into instructions we can reason about. */
interface TransactionDecoder {
    fun decode(serializedTx: ByteArray): List<DecodedInstruction>
}

/** External reputation scan (e.g. Blockaid); implementations may be async-wrapped. */
interface TransactionScanner {
    fun scan(serializedTx: ByteArray, recipients: List<String>): ScanResult
}

/**
 * The only component allowed to touch private keys. On the Seeker this is backed
 * by the Seed Vault TEE and gated by biometrics; keys never leave the vault.
 */
interface HardwareSigner {
    fun publicKey(): String
    /** Sign only after ClearSign has approved the receipt. */
    fun sign(serializedTx: ByteArray): ByteArray
}

/**
 * Orchestrates the safe path: decode → simulate → assess → (user approves) →
 * re-simulate (anti-TOCTOU) → sign. It never signs when a DANGER risk stands or
 * when state drifted between preview and approval.
 */
class ClearSignFlow(
    private val decoder: TransactionDecoder,
    private val simulator: Simulator,
    private val scanner: TransactionScanner,
    private val signer: HardwareSigner,
    private val trust: AddressTrust,
    private val riskEngine: RiskEngine = RiskEngine(),
) {
    private val receiptBuilder = ReceiptBuilder(trust)

    data class Preview(val receipt: Receipt, val previewDeltas: List<BalanceDelta>, val feeLamports: Long)

    /** Step 1: what the user sees before deciding. */
    fun preview(serializedTx: ByteArray, feeLamports: Long): Preview {
        val instructions = decoder.decode(serializedTx)
        val deltas = simulator.simulate(serializedTx)
        val recipients = instructions.mapNotNull { it.destination }
        val scan = scanner.scan(serializedTx, recipients)
        val risks = riskEngine.assess(instructions, trust, scan, simulationSucceeded = deltas != null)
        val receipt = receiptBuilder.build(
            myWallet = signer.publicKey(),
            deltas = deltas ?: emptyList(),
            instructions = instructions,
            feeLamports = feeLamports,
            risks = risks,
        )
        return Preview(receipt, deltas ?: emptyList(), feeLamports)
    }

    sealed interface SignOutcome {
        data class Signed(val signature: ByteArray) : SignOutcome
        data class Refused(val risk: Risk) : SignOutcome
    }

    /**
     * Step 2: called only after the user tapped OK on [Preview.receipt].
     * Re-simulates and refuses if anything now blocks approval or state drifted.
     */
    fun approveAndSign(serializedTx: ByteArray, preview: Preview): SignOutcome {
        if (preview.receipt.blocksApproval) {
            return SignOutcome.Refused(preview.receipt.risks.first { it.severity == Severity.DANGER })
        }
        val fresh = simulator.simulate(serializedTx)
            ?: return SignOutcome.Refused(
                Risk(RiskFlag.SIMULATION_FAILED, Severity.DANGER, "Re-simulation failed before signing."),
            )
        val guard = SimulationGuard.confirm(preview.previewDeltas, fresh)
        if (guard.driftDetected) return SignOutcome.Refused(guard.risk!!)
        return SignOutcome.Signed(signer.sign(serializedTx))
    }
}
