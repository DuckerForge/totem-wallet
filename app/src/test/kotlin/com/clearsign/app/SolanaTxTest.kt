package com.clearsign.app

import com.clearsign.core.InstructionKind
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Wire-format tests for the on-device decoder: what we show is what the bytes say. */
class SolanaTxTest {

    private fun key(seed: Int): ByteArray = ByteArray(32) { ((seed * 31 + it * 7) and 0xFF).toByte() }
    private fun b58(k: ByteArray) = Base58.encode(k)

    private class W {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun shortVec(n: Int) { var v = n; while (true) { val b = v and 0x7F; v = v ushr 7; if (v == 0) { u8(b); break } else u8(b or 0x80) } }
        fun bytes(b: ByteArray) = out.write(b)
        fun u32(v: Int) { repeat(4) { u8(v ushr (8 * it)) } }
        fun u64(v: Long) { repeat(8) { u8((v ushr (8 * it)).toInt()) } }
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private val system = ByteArray(32) // all zeros = "1111…" System program
    private val computeBudget = decodeB58("ComputeBudget111111111111111111111111111111")

    /** Base58 decode (test-only helper; the app only needs to encode). */
    private fun decodeB58(s: String): ByteArray {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var num = java.math.BigInteger.ZERO
        for (c in s) num = num.multiply(java.math.BigInteger.valueOf(58)).add(java.math.BigInteger.valueOf(alphabet.indexOf(c).toLong()))
        var bytes = num.toByteArray(); if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes = bytes.copyOfRange(1, bytes.size)
        val zeros = s.takeWhile { it == '1' }.length
        return ByteArray(zeros) + bytes
    }

    /** legacy tx: [payer, dest, system] ; ix: transfer payer→dest 1.5 SOL */
    private fun legacyTransfer(payer: ByteArray, dest: ByteArray, lamports: Long, extraIx: ((W) -> Unit)? = null, keys: List<ByteArray> = emptyList()): ByteArray {
        val w = W()
        w.shortVec(1); w.bytes(ByteArray(64)) // one empty signature slot
        w.u8(1); w.u8(0); w.u8(1)             // 1 signer, 0 ro-signed, 1 ro-unsigned (the program)
        val all = listOf(payer, dest) + keys + listOf(system)
        w.shortVec(all.size); all.forEach { w.bytes(it) }
        w.bytes(ByteArray(32))                 // blockhash
        val ixCount = 1 + (if (extraIx != null) 1 else 0)
        w.shortVec(ixCount)
        w.u8(all.size - 1); w.shortVec(2); w.u8(0); w.u8(1); w.shortVec(12); w.u32(2); w.u64(lamports)
        extraIx?.invoke(w)
        return w.toByteArray()
    }

    @Test fun decodesLegacyTransfer() {
        val payer = key(1); val dest = key(2)
        val d = SolanaTx.decode(legacyTransfer(payer, dest, 1_500_000_000))
        assertNotNull(d)
        assertEquals(-1, d.version)
        assertEquals(b58(payer), d.feePayer)
        assertEquals(listOf(b58(payer), b58(dest)), d.writableKeys)
        val ix = SolanaTx.instructions(d).single()
        assertEquals(InstructionKind.SOL_TRANSFER, ix.kind)
        assertEquals(b58(dest), ix.destination)
        assertEquals(1_500_000_000L, ix.amountRaw)
    }

    @Test fun attachSignatureSplicesIntoSlot() {
        val tx = legacyTransfer(key(1), key(2), 1)
        val sig = ByteArray(64) { 0x5A }
        val signed = SolanaTx.attachSignature(tx, 0, sig)
        assertTrue(signed.copyOfRange(1, 65).contentEquals(sig))
        assertTrue(signed.copyOfRange(65, signed.size).contentEquals(tx.copyOfRange(65, tx.size)), "message bytes untouched")
    }

    @Test fun decodesAssignOfOwnWalletAndDurableNonce() {
        val payer = key(1); val prog = key(9)
        val tx = legacyTransfer(payer, key(2), 1, extraIx = { w ->
            // Assign(payer → prog): tag 1 + 32-byte owner
            w.u8(2); w.shortVec(1); w.u8(0); w.shortVec(36); w.u32(1); w.bytes(prog)
        })
        val d = SolanaTx.decode(tx)!!
        val ixs = SolanaTx.instructions(d)
        val assign = ixs.single { it.kind == InstructionKind.ASSIGN_OWNER }
        assertEquals(b58(payer), assign.subject)
        assertEquals(b58(prog), assign.destination)

        val nonceTx = legacyTransfer(payer, key(2), 1, extraIx = { w ->
            w.u8(2); w.shortVec(1); w.u8(1); w.shortVec(4); w.u32(4) // AdvanceNonceAccount
        })
        assertTrue(SolanaTx.instructions(SolanaTx.decode(nonceTx)!!).any { it.kind == InstructionKind.DURABLE_NONCE })
    }

    @Test fun decodesV0WithLookupTables() {
        val payer = key(1); val table = key(7)
        val w = W()
        w.shortVec(1); w.bytes(ByteArray(64))
        w.u8(0x80)                             // version prefix: v0
        w.u8(1); w.u8(0); w.u8(1)
        w.shortVec(2); w.bytes(payer); w.bytes(system)
        w.bytes(ByteArray(32))
        w.shortVec(1); w.u8(1); w.shortVec(2); w.u8(0); w.u8(2); w.shortVec(12); w.u32(2); w.u64(5)
        w.shortVec(1); w.bytes(table); w.shortVec(2); w.u8(3); w.u8(5); w.shortVec(1); w.u8(0)
        val d = SolanaTx.decode(w.toByteArray())
        assertNotNull(d)
        assertEquals(0, d.version)
        val l = d.lookups.single()
        assertEquals(b58(table), l.table)
        assertEquals(listOf(3, 5), l.writableIndexes.toList())
        assertEquals(listOf(0), l.readonlyIndexes.toList())
    }

    @Test fun computeBudgetIsRead() {
        val payer = key(1)
        val tx = legacyTransfer(payer, key(2), 1, keys = listOf(computeBudget), extraIx = { w ->
            w.u8(2); w.shortVec(0); w.shortVec(5); w.u8(2); w.u32(200_000) // SetComputeUnitLimit(200k) on key index 2
        })
        val d = SolanaTx.decode(tx)!!
        assertEquals(200_000L, SolanaTx.computeBudget(d).unitLimit)
    }

    @Test fun base58RoundTripsWithLeadingZeros() {
        assertEquals("11111111111111111111111111111111", Base58.encode(ByteArray(32)))
        val k = decodeB58("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")
        assertEquals("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", Base58.encode(k))
    }

    @Test fun messageBytesSkipsSignatureArray() {
        // 2 signature slots (128 bytes) then a message: the message is what gets signed.
        val msg = byteArrayOf(1, 0, 0, 3, 9, 9, 9)
        val tx = byteArrayOf(2) + ByteArray(128) + msg
        assertTrue(SolanaTx.messageBytes(tx).contentEquals(msg))
        val signed = SolanaTx.attachSignature(tx, 1, ByteArray(64) { 7 })
        assertTrue(SolanaTx.messageBytes(signed).contentEquals(msg))
        assertEquals(7, signed[1 + 64])
    }
}
