package com.clearsign.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The TLV walk over a Token-2022 mint.
 *
 * Worth testing byte by byte rather than against a live mint: the whole point of
 * this parser is to be right about a coin nobody has seen before, and the two
 * ways to be wrong are opposite and both expensive. Miss a permanent delegate
 * and the agent buys a coin the creator can burn out of the budget. Invent one
 * and the wallet calls an honest coin a scam.
 */
class MintExtensionsTest {

    /** A mint account: 165 bytes of base and padding, the type byte, then TLV. */
    private fun mint(vararg tlv: Pair<Int, ByteArray>, accountType: Int = 1): ByteArray {
        val body = tlv.fold(ByteArray(0)) { acc, (type, data) ->
            acc + byteArrayOf(
                (type and 0xFF).toByte(), ((type shr 8) and 0xFF).toByte(),
                (data.size and 0xFF).toByte(), ((data.size shr 8) and 0xFF).toByte(),
            ) + data
        }
        return ByteArray(165) + byteArrayOf(accountType.toByte()) + body
    }

    private fun key(fill: Byte = 7) = ByteArray(32) { fill }
    private fun zeros(n: Int) = ByteArray(n)

    @Test fun aPlainMintHasNoExtensions() {
        assertEquals(MintExtensions.NONE, readMintExtensions(ByteArray(82)))
        assertEquals(MintExtensions.NONE, readMintExtensions(ByteArray(165)))
        assertFalse(readMintExtensions(ByteArray(82)).any)
    }

    @Test fun aTokenAccountIsNotAMint() {
        // Type 2 is an account, not a mint. Reading its bytes as a mint would find
        // extensions that describe somebody's balance, not the coin.
        val data = mint(12 to key(), accountType = 2)
        assertEquals(MintExtensions.NONE, readMintExtensions(data))
    }

    @Test fun aPermanentDelegateIsFound() {
        val e = readMintExtensions(mint(12 to key()))
        assertTrue(e.permanentDelegate)
        assertTrue(e.any)
    }

    /** The field exists on plenty of honest mints, holding nobody. */
    @Test fun anEmptyPermanentDelegateIsNobody() {
        assertFalse(readMintExtensions(mint(12 to zeros(32))).permanentDelegate)
    }

    @Test fun aTransferHookNeedsAProgramToBeOne() {
        // Authority set, program zero: declared and not wired to anything.
        assertFalse(readMintExtensions(mint(14 to (key() + zeros(32)))).transferHook)
        assertTrue(readMintExtensions(mint(14 to (key() + key(9)))).transferHook)
    }

    @Test fun theTransferFeeIsTheNewerOfThePair() {
        // 32 authority + 32 withdraw + 8 withheld + older(18) + newer(18).
        val older = zeros(16) + byteArrayOf(100, 0)   // 100 bps
        val newer = zeros(16) + byteArrayOf(0x10, 0x27) // 10000 bps, little endian
        val e = readMintExtensions(mint(1 to (key() + key(2) + zeros(8) + older + newer)))
        assertEquals(10_000, e.transferFeeBps)
    }

    @Test fun nonTransferableAndDefaultFrozenAreRead() {
        assertTrue(readMintExtensions(mint(9 to ByteArray(0))).nonTransferable)
        assertTrue(readMintExtensions(mint(6 to byteArrayOf(2))).defaultFrozen)
        assertFalse(readMintExtensions(mint(6 to byteArrayOf(1))).defaultFrozen, "1 is initialized, not frozen")
    }

    @Test fun severalExtensionsInOneAccount() {
        val e = readMintExtensions(
            mint(
                19 to ByteArray(40),                  // metadata, ignored
                12 to key(),
                14 to (key() + key(3)),
            ),
        )
        assertTrue(e.permanentDelegate)
        assertTrue(e.transferHook)
    }

    /** A truncated or lying length must stop the walk, not throw. */
    @Test fun garbageIsNotACrashAndNotAnAccusation() {
        val truncated = ByteArray(165) + byteArrayOf(1) + byteArrayOf(12, 0, 32, 0) + ByteArray(4)
        val e = readMintExtensions(truncated)
        assertFalse(e.permanentDelegate)
        assertFalse(e.any)
    }

    // ---- what the grade does with them --------------------------------------

    private fun facts(ext: MintExtensions, verified: Boolean = false) =
        TokenFacts(verified = verified, token2022 = true, holders = 5_000, liquidityUsd = 900_000.0, sellable = true, ext = ext)

    @Test fun aSeizableCoinIsRefusedHoweverGoodItLooks() {
        // Deep liquidity, thousands of holders, a live quote back to SOL. None of
        // it matters: somebody else can empty the position at will.
        val s = assessToken(facts(MintExtensions(permanentDelegate = true)))
        assertTrue(s.bad, s.toString())
        assertTrue(SafetyFlag.SEIZABLE in s.flags)
    }

    /** PYUSD and EURC need exactly this power. Calling them scams teaches people to ignore us. */
    @Test fun onAVerifiedIssuerItIsADisclosedProperty() {
        val s = assessToken(facts(MintExtensions(permanentDelegate = true), verified = true))
        assertFalse(s.bad)
        assertTrue(SafetyFlag.ISSUER_CONTROLLED in s.flags)
    }

    @Test fun aHoneypotShapeIsRefused() {
        assertTrue(assessToken(facts(MintExtensions(nonTransferable = true))).bad)
        assertTrue(assessToken(facts(MintExtensions(defaultFrozen = true))).bad)
        assertTrue(assessToken(facts(MintExtensions(transferHook = true))).bad)
    }

    @Test fun aTaxIsAPenaltyNotAVerdict() {
        val small = assessToken(facts(MintExtensions(transferFeeBps = 50)))
        assertFalse(small.bad, "half a percent is a cost, not a trap")
        assertTrue(SafetyFlag.TRANSFER_TAX in small.flags)
        assertTrue(assessToken(facts(MintExtensions(transferFeeBps = 3_000))).bad, "thirty percent is a trap")
    }

    /** Token-2022 with nothing dangerous in it is just a newer standard. */
    @Test fun theStandardOnItsOwnIsNotASignal() {
        val s = assessToken(facts(MintExtensions.NONE))
        assertFalse(s.bad)
        assertTrue(SafetyFlag.NEW_TOKEN_PROGRAM in s.flags)
    }
}
