package com.clearsign.app

import java.security.SecureRandom
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec

/**
 * An Ed25519 key this app holds itself, rather than the Seed Vault.
 *
 * Two things need one: the agent envelope, because the Seed Vault will not sign
 * without a person present and the envelope must sign while you sleep; and a
 * gift link, because whoever opens the link has to be able to sweep it. Both are
 * deliberately small and disposable. Nothing that matters lives on such a key.
 */
object SoftKey {
    private const val CURVE = "Ed25519"

    fun newSeed(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun pubkeyOf(seed: ByteArray): String =
        Base58.encode(EdDSAPrivateKeySpec(seed, EdDSANamedCurveTable.getByName(CURVE)).a.toByteArray())

    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        val spec = EdDSAPrivateKeySpec(seed, EdDSANamedCurveTable.getByName(CURVE))
        val engine = EdDSAEngine(java.security.MessageDigest.getInstance("SHA-512"))
        engine.initSign(EdDSAPrivateKey(spec))
        engine.update(message)
        return engine.sign()
    }

    /**
     * Move everything this key holds to [to], minus the fee. Returns the
     * signature, or null with a reason. Used to claim a gift and to reclaim one.
     */
    /** What a sweep did: the signature, or the reason it did not, and the lamports that moved. */
    data class Sweep(val signature: String?, val error: String?, val lamports: Long)

    suspend fun sweepAll(from: ByteArray, to: String, cluster: String? = null): Sweep {
        val pub = pubkeyOf(from)
        val rpc = SolanaRpc.urlFor(cluster)
        val balance = runCatching { SolanaRpc.getBalance(rpc, pub) }.getOrNull() ?: 0L
        val fee = 5_000L
        if (balance <= fee) return Sweep(null, "empty", 0L)
        val fromKey = Base58.decodePubkey(pub) ?: return Sweep(null, "bad key", 0L)
        val toKey = Base58.decodePubkey(to) ?: return Sweep(null, "bad address", 0L)
        val bh = SolanaRpc.latestBlockhash(rpc) ?: return Sweep(null, "no blockhash", 0L)
        val tx = WalletTx.build(fromKey, Base58.decode(bh.hash), listOf(WalletTx.systemTransfer(fromKey, toKey, balance - fee)))
        val signed = SolanaTx.attachSignature(tx, 0, sign(from, SolanaTx.messageBytes(tx)))
        val out = SolanaRpc.send(rpc, signed)
        return Sweep(out.signature, out.error, balance - fee)
    }
}
