package com.clearsign.app

import com.clearsign.core.AddressTrust
import com.clearsign.core.BalanceDelta
import com.clearsign.core.DecodedInstruction
import com.clearsign.core.HardwareSigner
import com.clearsign.core.InstructionKind
import com.clearsign.core.NATIVE_SOL_MINT
import com.clearsign.core.ScanResult
import com.clearsign.core.Simulator
import com.clearsign.core.TransactionDecoder
import com.clearsign.core.TransactionScanner

/*
 * Demo wiring for the on-device build: in-memory stand-ins for the real ports (simulate,
 * scan, decode) so the whole :core safety pipeline runs on the Seeker with no dApp.
 */

/** The user's own wallet (in the real app this comes from Seed Vault). */
const val DEMO_WALLET = "7cVfgArCheMR6Cs4t6vz5rfnqd56vZq4ndaByB5D9BW6"

// A contact the user has explicitly saved, and an address they've paid before.
private const val ALICE = "AL1CE1qDqk3vN2mR8tWpZ4yXb7sJfKcU9nGhLxA2pQ0"
private const val ALICE_LABEL = "Alice"
private const val EXCHANGE = "H1st0ryCbShM9nQ2wErTyUiOpAsDfGhJkLzXcVbNm00"

// Address-poisoning pair: the poisoned address shares the shown first/last 4
// chars with an address the user paid before, but the full string differs.
private const val REAL_PAYEE = "Gjw9kF3pQ2mNvZ8tLrY6sXbHwUcE1RtYuIoPaXyZ"
private const val POISONED = "Gjw9PZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZaXyZ"

private const val SYSTEM_PROGRAM = "11111111111111111111111111111111"
private const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"

/** Fake serialized transaction bytes; scenario is selected out-of-band for the demo. */
val DEMO_TX: ByteArray = byteArrayOf(1, 2, 3, 4)

const val DEMO_FEE_LAMPORTS = 5_000L

private fun sol(owner: String, lamports: Long) =
    BalanceDelta(owner, NATIVE_SOL_MINT, "SOL", 9, lamports)

enum class Scenario(val titleRes: Int, val blurbRes: Int) {
    SAFE_PAYMENT(R.string.sc_safe_t, R.string.sc_safe_b),
    DRAINER(R.string.sc_drainer_t, R.string.sc_drainer_b),
    POISONED_LOOKALIKE(R.string.sc_poison_t, R.string.sc_poison_b),
    UNLIMITED_APPROVAL(R.string.sc_approve_t, R.string.sc_approve_b),
    STATE_DRIFT(R.string.sc_drift_t, R.string.sc_drift_b),
}

/** Everything the ports need to reproduce one scenario. [myWallet] is the signer's pubkey. */
class DemoCase(scenario: Scenario, private val myWallet: String = DEMO_WALLET, private val drainerReason: String = "Program known to drain wallets.") {

    val trust: AddressTrust = AddressTrust(
        allowlist = mapOf(ALICE to ALICE_LABEL),
        history = setOf(EXCHANGE, REAL_PAYEE),
        flagged = emptySet(),
        sanctioned = emptySet(),
    )

    val decoder: TransactionDecoder = TransactionDecoder {
        when (scenario) {
            Scenario.SAFE_PAYMENT, Scenario.STATE_DRIFT -> listOf(
                DecodedInstruction(InstructionKind.SOL_TRANSFER, SYSTEM_PROGRAM, destination = ALICE, amountRaw = 1_500_000_000),
            )
            Scenario.DRAINER -> listOf(
                DecodedInstruction(InstructionKind.SOL_TRANSFER, SYSTEM_PROGRAM, destination = POISONED_ATTACKER, amountRaw = 49_900_000_000),
            )
            Scenario.POISONED_LOOKALIKE -> listOf(
                DecodedInstruction(InstructionKind.SOL_TRANSFER, SYSTEM_PROGRAM, destination = POISONED, amountRaw = 2_000_000_000),
            )
            Scenario.UNLIMITED_APPROVAL -> listOf(
                DecodedInstruction(InstructionKind.TOKEN_APPROVE, TOKEN_PROGRAM, destination = DAPP_DELEGATE, isUnlimitedApproval = true),
            )
        }
    }

    val scanner: TransactionScanner = TransactionScanner { _, _ ->
        if (scenario == Scenario.DRAINER) {
            ScanResult(malicious = true, reason = drainerReason)
        } else {
            ScanResult()
        }
    }

    val simulator: Simulator = object : Simulator {
        private var calls = 0
        override fun simulate(serializedTx: ByteArray): List<BalanceDelta>? {
            calls++
            return when (scenario) {
                Scenario.SAFE_PAYMENT -> listOf(
                    sol(myWallet,-1_500_000_000), sol(ALICE, 1_500_000_000),
                )
                Scenario.DRAINER -> listOf(
                    sol(myWallet,-49_900_000_000), sol(POISONED_ATTACKER, 49_900_000_000),
                )
                Scenario.POISONED_LOOKALIKE -> listOf(
                    sol(myWallet,-2_000_000_000), sol(POISONED, 2_000_000_000),
                )
                Scenario.UNLIMITED_APPROVAL -> emptyList() // approval moves nothing now
                Scenario.STATE_DRIFT ->
                    // Preview looks like a small safe transfer; by signing time the
                    // effect has drifted into a near-total drain.
                    if (calls == 1) listOf(sol(DEMO_WALLET, -1_500_000_000), sol(ALICE, 1_500_000_000))
                    else listOf(sol(DEMO_WALLET, -48_000_000_000), sol(ALICE, 48_000_000_000))
            }
        }
    }

    companion object {
        private const val POISONED_ATTACKER = "DraiNerXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX01"
        private const val DAPP_DELEGATE = "DappDe1egateXXXXXXXXXXXXXXXXXXXXXXXXXX02"
    }
}

/** Placeholder signer for phases 1–2; replaced by the Seed Vault signer in phase 3. */
class MockSigner(private val pubkey: String = DEMO_WALLET) : HardwareSigner {
    override fun publicKey(): String = pubkey
    override fun sign(serializedTx: ByteArray): ByteArray =
        ByteArray(64) { (it * 7 + 13).toByte() } // deterministic fake 64-byte signature
}

/** SAM-style adapters so we can build ports with lambdas above. */
private fun TransactionDecoder(block: (ByteArray) -> List<DecodedInstruction>) =
    object : TransactionDecoder { override fun decode(serializedTx: ByteArray) = block(serializedTx) }

private fun TransactionScanner(block: (ByteArray, List<String>) -> ScanResult) =
    object : TransactionScanner {
        override fun scan(serializedTx: ByteArray, recipients: List<String>) = block(serializedTx, recipients)
    }
