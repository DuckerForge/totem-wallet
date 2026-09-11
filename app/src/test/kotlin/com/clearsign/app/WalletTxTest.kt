package com.clearsign.app

import com.clearsign.core.InstructionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WalletTxTest {
    private fun key(seed: Int) = ByteArray(32) { ((seed * 31 + it * 7) and 0xFF).toByte() }
    private val me = key(1); private val hash = key(99)

    @Test fun revokeDecodesAsOneSignerTx() {
        val tx = WalletTx.build(me, hash, listOf(WalletTx.tokenRevoke(key(5), me)))
        val d = SolanaTx.decode(tx); assertNotNull(d)
        assertEquals(1, d.numRequiredSignatures)
        assertEquals(Base58.encode(me), d.feePayer)
        val ix = d.instructions.single()
        assertEquals(SolanaTx.TOKEN_PROGRAM, d.staticAccountKeys[ix.programIdIndex])
        assertEquals(listOf(5.toByte()), ix.data.toList())
        assertEquals(Base58.encode(key(5)), d.staticAccountKeys[ix.accounts[0]])
        assertEquals(Base58.encode(me), d.staticAccountKeys[ix.accounts[1]])
    }

    @Test fun tenClosesFitInOneTx() {
        val ixs = (10..19).map { WalletTx.tokenCloseAccount(key(it), me, me) }
        val tx = WalletTx.build(me, hash, ixs)
        assertTrue(tx.size < WalletTx.MAX_TX_BYTES, "tx is ${tx.size} bytes")
        val d = SolanaTx.decode(tx)!!
        assertEquals(10, d.instructions.size)
        assertTrue(SolanaTx.instructions(d).all { it.kind == InstructionKind.CLOSE_ACCOUNT })
    }

    @Test fun transferCheckedAndAtaCreate() {
        val mint = key(7); val dest = key(8)
        val ata = Pda.associatedTokenAddress(dest, mint, WalletTx.TOKEN_PROGRAM)
        val tx = WalletTx.build(me, hash, listOf(
            WalletTx.createAtaIdempotent(me, ata, dest, mint),
            WalletTx.tokenTransferChecked(key(6), mint, ata, me, 1_500_000, 6),
            WalletTx.memo("clearsign"),
        ))
        val d = SolanaTx.decode(tx)!!
        val decoded = SolanaTx.instructions(d)
        val t = decoded.single { it.kind == InstructionKind.SPL_TRANSFER }
        assertEquals(Base58.encode(ata), t.destination); assertEquals(1_500_000L, t.amountRaw)
        assertEquals(0, d.numReadonlySigned)
        // signature splice lands in slot 0 and leaves the message intact
        val signed = SolanaTx.attachSignature(tx, 0, ByteArray(64) { 7 })
        assertTrue(signed.copyOfRange(65, signed.size).contentEquals(tx.copyOfRange(65, tx.size)))
    }
}
