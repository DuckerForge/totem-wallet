package com.clearsign.app

import android.util.Log

/**
 * Client for the on-chain reputation registry, the "Trustpilot for wallets". Reputation is a
 * fact about an address, independent of cluster, so it lives on devnet (free, mock-SKR
 * staking) while real transactions are on mainnet. The account is found by its indexed
 * `target` field (memcmp at offset 8), so no PDA derivation on device.
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

    /**
     * Whether the program exists. Measured 22 Sep 2026: on devnet the program account did not
     * exist, zero accounts, and every receipt still paid a `getProgramAccounts` that could answer
     * nothing. Devnet resets now and then, so the question is asked again, once every six hours,
     * and "not there" is kept. When the program returns, reputation returns by itself.
     */
    @Volatile private var deployed: Triple<Long, String, Boolean>? = null
    private const val DEPLOYED_TTL_MS = 6 * 60 * 60_000L

    private fun isDeployed(): Boolean {
        val now = System.currentTimeMillis()
        deployed?.let { (at, on, ok) -> if (on == rpc && now - at < DEPLOYED_TTL_MS) return ok }
        // Without an answer we still try, as before: silence does not mean it is not there.
        val ok = SolanaRpc.accountExists(rpc, PROGRAM_ID) ?: return true
        deployed = Triple(now, rpc, ok)
        if (!ok) Log.i("ClearSign-Rep", "program not on $rpc, skipping lookups for a while")
        return ok
    }

    /** Fetch a target's reputation, or null when nobody has voted / on error. */
    fun fetch(target: String): Rep? {
        if (!isDeployed()) return null
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
