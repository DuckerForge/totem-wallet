package com.clearsign.app

import android.util.Log

/**
 * Client for the on-chain ClearSign reputation registry (the "Trustpilot for
 * wallets"). Reputation is a fact about an *address*, independent of the cluster
 * a transaction is on, so it lives on devnet (free, safe, mock-SKR staking) while
 * ClearSign can still be signing real mainnet transactions. We read a target's
 * aggregate at sign-time and turn it into a trust verdict.
 *
 * The account is found by its indexed `target` field (memcmp at offset 8) so no
 * ed25519 PDA derivation is needed on-device.
 */
object Reputation {

    // Program id from `anchor keys sync` (updated after deploy).
    const val PROGRAM_ID = "AdJRTSZ9pBrgzHsKErZZUruRkqVG929zsUPNrJeo3dBM"

    // Reputation cluster — devnet, decoupled from whatever cluster we sign on.
    @Volatile var rpc: String = "https://api.devnet.solana.com"

    private const val LAMPORTS_PER_SOL = 1_000_000_000.0

    /** Aggregate community reputation for one address. */
    data class Rep(
        val up: Long,        // stake-weighted "trust" (lamports)
        val down: Long,      // stake-weighted "scam" (lamports)
        val staked: Long,
        val voters: Int,
        val firstSeen: Long,
        val lastSeen: Long,
    ) {
        val verdict: Verdict get() = when {
            voters == 0 -> Verdict.NONE
            down > up -> Verdict.FLAGGED
            up > down -> Verdict.TRUSTED
            else -> Verdict.MIXED
        }
        val stakedSol: Double get() = staked / LAMPORTS_PER_SOL
    }

    enum class Verdict { TRUSTED, MIXED, FLAGGED, NONE }

    /** Fetch a target's reputation, or null when nobody has voted / on error. */
    fun fetch(target: String): Rep? {
        val datas = try {
            SolanaRpc.programAccountsMemcmp(rpc, PROGRAM_ID, offset = 8, valueBase58 = target)
        } catch (e: Exception) {
            Log.w("ClearSign-Rep", "fetch failed", e); return null
        }
        val d = datas.firstOrNull { it.size >= 85 } ?: return null
        // Layout after the 8-byte Anchor discriminator: target(32) up(8) down(8)
        // staked(8) voters(4) first_seen(8) last_seen(8) bump(1). All little-endian.
        return Rep(
            up = u64(d, 40),
            down = u64(d, 48),
            staked = u64(d, 56),
            voters = u32(d, 64),
            firstSeen = u64(d, 68),
            lastSeen = u64(d, 76),
        )
    }

    private fun u64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun u32(b: ByteArray, off: Int): Int {
        var v = 0
        for (i in 0 until 4) v = v or ((b[off + i].toInt() and 0xFF) shl (8 * i))
        return v
    }
}
