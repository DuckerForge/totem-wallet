package com.clearsign.core

import kotlin.math.pow

/**
 * The domain model of the clear-signing engine. Pure Kotlin, no device or network, fully
 * unit-tested off-device; Seed Vault, MWA and RPC live behind the interfaces in [Ports.kt].
 */

const val NATIVE_SOL_MINT = "SOL"

/** A single balance change the pending signature will cause, revealed by simulation. */
data class BalanceDelta(
    val owner: String,      // account/wallet affected (base58)
    val mint: String,       // token mint, or NATIVE_SOL_MINT for native SOL
    val symbol: String,     // human symbol, e.g. "USDC", "SOL"
    val decimals: Int,
    val rawAmount: Long,    // signed: negative = leaving `owner`, positive = arriving
    val createdAccount: Boolean = false, // true when this account didn't exist pre-tx (gain = rent, not a payment)
) {
    val uiAmount: Double get() = rawAmount / 10.0.pow(decimals)
    val isOutgoing: Boolean get() = rawAmount < 0
}

enum class InstructionKind {
    SOL_TRANSFER,
    SPL_TRANSFER,
    TOKEN_APPROVE,   // delegate authority over a token account
    SET_AUTHORITY,   // change owner/authority of an account or mint
    CLOSE_ACCOUNT,   // close a token account and reclaim rent
    ASSIGN_OWNER,    // System Assign: hands an account (maybe *your wallet*) to a program
    DURABLE_NONCE,   // AdvanceNonceAccount: the signature never expires (classic drainer setup)
    SWAP,
    WAGER,           // SOL put on a bet the chain settles: an ORE deploy, or SOL handed to an ORE executor
    UNKNOWN,
}

/** One decoded instruction from the transaction being signed. */
data class DecodedInstruction(
    val kind: InstructionKind,
    val programId: String,
    val destination: String? = null,   // recipient / delegate / new authority / new owner program
    val amountRaw: Long? = null,
    val isUnlimitedApproval: Boolean = false,
    val subject: String? = null,       // the account acted upon (e.g. the one being assigned)
)

/** How much we trust the counterparty address. */
enum class TrustLevel { TRUSTED, KNOWN, NEW, FLAGGED }

enum class Severity { INFO, WARN, DANGER }

enum class RiskFlag {
    BLOCKED_MALICIOUS,     // external scanner flags a drainer / malicious program
    SANCTIONED,            // recipient on a sanctions list
    UNLIMITED_APPROVAL,    // approve for effectively unlimited amount
    AUTHORITY_CHANGE,      // setAuthority present
    ACCOUNT_CLOSE,         // closeAccount to a non-owned/unknown destination
    NEW_UNKNOWN_RECIPIENT, // never transacted with, not in allowlist
    LOOKALIKE_ADDRESS,     // resembles a known address (address-poisoning)
    SIMULATION_FAILED,     // the node ran it and it failed → never blind-sign
    SIMULATION_UNAVAILABLE, // could not ask the node → never blind-sign, but worth asking again
    STATE_DRIFT,           // re-simulation at approval differs from preview (TOCTOU)
    COMMUNITY_FLAGGED,     // on-chain community reputation voted this address down
    DRAINS_BALANCE,        // an outflow empties (≥90%) one of your balances
    WALLET_OWNER_CHANGE,   // System Assign on your own wallet account → a program owns it
    DURABLE_NONCE,         // durable-nonce tx: valid forever, can be replayed whenever
    FOREIGN_FEE_PAYER,     // someone else pays the fee ("gasless"): common in drainer kits
    BRAND_NEW_RECIPIENT,   // recipient has zero on-chain history
    LIMITED_APPROVAL,      // a (bounded) delegate is granted over your tokens
    FEE_EXCESSIVE,         // priority fee far above what the network is paying right now
    EXTRA_SIGNERS,         // the transaction needs signatures from keys other than yours
    AGENT_INTENT_MISMATCH, // an AI agent declared one thing; the simulated effect is another (Agent Gate)
    AGENT_INTENT_OK,       // the agent's declared intent matches the simulated effect (informational)
    WAGER,                 // puts SOL on the ORE grid: it can go to the other squares
    WAGER_FOR_OTHER,       // pays ORE squares whose miner belongs to another wallet
}

/**
 * What the simulation and the wallet know about this signing beyond the instructions, for
 * [RiskEngine.assessEffects]: a transfer is benign, a transfer of everything to a wallet with no history is not.
 */
data class EffectContext(
    val myWallet: String,
    val preBalances: Map<String, Long> = emptyMap(),  // mint -> raw balance before the tx
    val feePayer: String? = null,
    val primaryRecipient: String? = null,
    val recipientBrandNew: Boolean? = null,           // null = unknown (no intel)
    val priorityFeeLamports: Long? = null,            // what this tx pays on top of the base fee
    val priorityPriceMicroLamports: Long? = null,     // its compute-unit price
    val medianPriorityPriceMicroLamports: Long? = null, // what the network is paying right now (null = unknown)
)

data class Risk(val flag: RiskFlag, val severity: Severity, val detail: String)

/** Result of an external transaction/address scan (e.g. Blockaid). */
data class ScanResult(
    val malicious: Boolean = false,
    val sanctioned: Set<String> = emptySet(),
    val reason: String? = null,
)

/**
 * One external wallet receiving value, with its slice of the total outflow. What makes a split
 * visible: a payment fanning out to a recipient and two fee wallets is three shares, not one total.
 */
data class RecipientShare(
    val address: String,
    val label: String?,          // contact name, if known
    val trust: TrustLevel,
    val delta: BalanceDelta,     // what this wallet receives (rawAmount > 0)
    val share: Double,           // 0..1 fraction of the total outflow (by |amount|, same mint)
    val isFee: Boolean = false,  // heuristic: a small side-payment to an *existing* wallet
    val isNewAccount: Boolean = false, // this is rent to create a new account, not a payment
) {
    /** True when this wallet is not the largest receiver — i.e. a fee/side split. */
    val uiAmount: Double get() = delta.uiAmount
}

/** The nerd stats a power user wants before signing: compute budget, fee breakdown, accounts and programs touched, wire version. */
data class TxStats(
    val version: Int,                    // -1 legacy, 0 = v0
    val instructionCount: Int,
    val accountsTotal: Int,              // keys in the message (excl. lookup-table loads)
    val writableAccounts: Int,
    val signerAccounts: Int,
    val programs: List<String>,          // distinct program ids invoked, in order
    val computeUnits: Long?,             // units the simulation actually consumed
    val computeUnitLimit: Long?,         // requested limit (ComputeBudget), if set
    val computeUnitPriceMicroLamports: Long?, // priority price, if set
    val baseFeeLamports: Long,           // 5000 * signatures
    val priorityFeeLamports: Long?,      // price * units / 1e6, if both known
    val logCount: Int,                   // program log lines the sim emitted
    val destinationsCount: Int,          // distinct external wallets that received value
    val networkMedianPriceMicroLamports: Long? = null, // recent median priority price on the network
) {
    val totalFeeLamports: Long get() = baseFeeLamports + (priorityFeeLamports ?: 0L)
}

/** The human-readable "receipt" shown before the Seed Vault is asked to sign. */
data class Receipt(
    val primaryRecipient: String?,
    val recipientLabel: String?,        // e.g. contact name, if trusted/known
    val recipientTrust: TrustLevel,
    val outflows: List<BalanceDelta>,   // what leaves the user's wallet
    val inflows: List<BalanceDelta>,    // what the user receives
    val feeLamports: Long,
    val risks: List<Risk>,
    val distributions: List<RecipientShare> = emptyList(), // every wallet that receives value
    val stats: TxStats? = null,
    val deltas: List<BalanceDelta> = emptyList(),   // raw sim deltas, for anti-TOCTOU re-check
    val calls: List<ProgramCall> = emptyList(),      // program methods decoded from on-chain IDLs
) {
    val highestSeverity: Severity =
        risks.maxByOrNull { it.severity.ordinal }?.severity ?: Severity.INFO

    /** A receipt is safe to present for one-tap approval only when nothing is DANGER. */
    val blocksApproval: Boolean = risks.any { it.severity == Severity.DANGER }

    /** True when the outflow fans out to more than one destination wallet. */
    val isSplit: Boolean = distributions.size > 1
}

/** A program call decoded from its published (Anchor) IDL: "Jupiter v6 · route(in_amount: 2000000)". */
data class ProgramCall(
    val programId: String,
    val programName: String?,
    val method: String,
    val args: List<Pair<String, String>>,   // name → rendered value, in IDL order
)
