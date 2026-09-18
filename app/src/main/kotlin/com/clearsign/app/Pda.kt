package com.clearsign.app

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Program-derived addresses, done on-device with no crypto library: SHA-256 from
 * the JDK plus just enough ed25519 field arithmetic to tell whether 32 bytes
 * decode to a curve point (a PDA must *not*). Powers ATA derivation (send /
 * theme payment) and Anchor IDL account lookup.
 */
object Pda {
    const val ATA_PROGRAM = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    private val PDA_MARKER = "ProgramDerivedAddress".toByteArray(Charsets.US_ASCII)

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    // ---- ed25519 point decompression (RFC 8032 §5.1.3) --------------------------
    //
    // `BigInteger.TWO` esiste da API 33 e il minSdk qui e' 31. Questo e' un
    // `object`: il campo che manca non fa fallire una chiamata, fa fallire
    // l'inizializzazione della classe, e con lei tutto quello che deriva un PDA
    // (token account, swap, burn, rent). Su Android 12 l'app non avrebbe mai
    // mandato una moneta. Sul Seeker, che e' API 34, non si vedeva.
    private val TWO = BigInteger.valueOf(2)
    private val P = TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val I = TWO.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P) // sqrt(-1)

    /** True when [pubkey] is a valid compressed ed25519 point (i.e. a possible user key, not a PDA). */
    fun isOnCurve(pubkey: ByteArray): Boolean {
        if (pubkey.size != 32) return false
        val le = pubkey.copyOf(); le[31] = (le[31].toInt() and 0x7F).toByte()
        val y = BigInteger(1, le.reversedArray())
        if (y >= P) return false
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)                 // y² − 1
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)          // d·y² + 1
        val x2 = u.multiply(v.modInverse(P)).mod(P)
        if (x2.signum() == 0) return true                          // x = 0 is a valid point
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P)
        if (x.multiply(x).mod(P) != x2) x = x.multiply(I).mod(P)
        return x.multiply(x).mod(P) == x2
    }

    /** Derive the PDA (address, bump) for [seeds] under [programId]; null if no bump works. */
    fun findProgramAddress(seeds: List<ByteArray>, programId: ByteArray): Pair<ByteArray, Int>? {
        require(seeds.all { it.size <= 32 } && seeds.size <= 16) { "invalid seeds" }
        for (bump in 255 downTo 0) {
            val md = MessageDigest.getInstance("SHA-256")
            seeds.forEach { md.update(it) }
            md.update(bump.toByte()); md.update(programId); md.update(PDA_MARKER)
            val h = md.digest()
            if (!isOnCurve(h)) return h to bump
        }
        return null
    }

    /** System `createAccountWithSeed` address: sha256(base ‖ seed ‖ owner). */
    fun createWithSeed(base: ByteArray, seed: String, owner: ByteArray): ByteArray =
        sha256(base, seed.toByteArray(Charsets.UTF_8), owner)

    /** Associated token account of [owner] for [mint] under [tokenProgram]. */
    fun associatedTokenAddress(owner: ByteArray, mint: ByteArray, tokenProgram: ByteArray): ByteArray =
        findProgramAddress(listOf(owner, tokenProgram, mint), Base58.decode(ATA_PROGRAM))!!.first
}
