package com.clearsign.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * There is one Seeker here, so the tap between two phones cannot be tried. What
 * can be tried is the only thing that decides whether it will work: the exact
 * bytes a reader receives. This walks the whole conversation.
 */
class NfcTapTest {
    private val addr = "DEM0ownerWa11etF0rTests0n1yNotARea1Key111jQ"

    private fun select(aid: ByteArray) = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, aid.size.toByte()) + aid
    private fun selectFile(id: ByteArray) = byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, id.size.toByte()) + id
    private fun read(offset: Int, length: Int) =
        byteArrayOf(0x00, 0xB0.toByte(), (offset ushr 8).toByte(), offset.toByte(), length.toByte())

    private fun ByteArray.body() = copyOfRange(0, size - 2)
    private fun ByteArray.status() = copyOfRange(size - 2, size)

    @Test fun aReaderWalksTheWholeConversation() {
        val uri = "solana:$addr?amount=0.01"
        val tag = Type4Tag(Ndef.uriMessage(uri))

        assertTrue(tag.process(select(Type4Tag.AID_NDEF)).contentEquals(Type4Tag.SW_OK), "the NDEF application must be selectable")
        assertTrue(tag.process(selectFile(Type4Tag.FILE_CC)).contentEquals(Type4Tag.SW_OK))

        val cc = tag.process(read(0, 15))
        assertTrue(cc.status().contentEquals(Type4Tag.SW_OK))
        assertEquals(15, cc.body().size)
        assertEquals(0x20, cc.body()[2].toInt(), "mapping version 2.0")

        assertTrue(tag.process(selectFile(Type4Tag.FILE_NDEF)).contentEquals(Type4Tag.SW_OK))
        val len = tag.process(read(0, 2)).body()
        val size = ((len[0].toInt() and 0xFF) shl 8) or (len[1].toInt() and 0xFF)
        val message = tag.process(read(2, size)).body()
        assertEquals(size, message.size)
        assertEquals(uri, Ndef.uriOf(message), "what the reader gets back must be the request we put in")
    }

    @Test fun theFileSurvivesBeingReadTwoBytesAtATime() {
        // A reader picks its own chunk size, and small ones are the ones that break things.
        val uri = "solana:$addr?amount=1.5&message=due%20caff%C3%A8"
        val tag = Type4Tag(Ndef.uriMessage(uri))
        tag.process(select(Type4Tag.AID_NDEF))
        tag.process(selectFile(Type4Tag.FILE_NDEF))
        val total = tag.process(read(0, 2)).body().let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }
        val out = ArrayList<Byte>()
        var offset = 2
        while (out.size < total) {
            val chunk = tag.process(read(offset, 2))
            assertTrue(chunk.status().contentEquals(Type4Tag.SW_OK))
            chunk.body().forEach { out.add(it) }
            offset += chunk.body().size
        }
        assertEquals(uri, Ndef.uriOf(out.toByteArray()))
    }

    @Test fun aLongRequestUsesTheExtendedRecord() {
        val uri = "solana:$addr?amount=2&message=" + "a".repeat(300)
        val msg = Ndef.uriMessage(uri)
        assertEquals(0, msg[0].toInt() and 0x10, "the short-record flag must be off above 255 bytes")
        assertEquals(uri, Ndef.uriOf(msg))
    }

    @Test fun nothingIsServedBeforeTheApplicationIsSelected() {
        val tag = Type4Tag(Ndef.uriMessage("solana:$addr"))
        assertTrue(tag.process(read(0, 2)).contentEquals(Type4Tag.SW_NOT_FOUND), "a read before any select must be refused")
    }

    @Test fun anUnknownCommandIsRefusedNotGuessed() {
        val tag = Type4Tag(Ndef.uriMessage("solana:$addr"))
        tag.process(select(Type4Tag.AID_NDEF))
        assertTrue(tag.process(byteArrayOf(0x00, 0xD6.toByte(), 0x00, 0x00, 0x01, 0x42)).contentEquals(Type4Tag.SW_NOT_FOUND), "a write must never be accepted")
        assertTrue(tag.process(byteArrayOf(0x00, 0xA4.toByte())).contentEquals(Type4Tag.SW_WRONG_LENGTH))
        assertTrue(tag.process(select(byteArrayOf(1, 2, 3))).contentEquals(Type4Tag.SW_NOT_FOUND), "another application is not ours")
    }

    @Test fun readingPastTheEndIsBoundedNotCrashing() {
        val tag = Type4Tag(Ndef.uriMessage("solana:$addr"))
        tag.process(select(Type4Tag.AID_NDEF))
        tag.process(selectFile(Type4Tag.FILE_NDEF))
        val whole = tag.process(read(0, 255))
        assertTrue(whole.status().contentEquals(Type4Tag.SW_OK))
        val tail = tag.process(read(whole.body().size, 16))
        assertEquals(0, tail.body().size, "past the end there is simply nothing left")
        assertTrue(tag.process(read(9000, 4)).contentEquals(Type4Tag.SW_WRONG_PARAMS))
    }

    @Test fun theRequestIsReadBackWhole() {
        val r = SolanaPay.parse("solana:$addr?amount=0.25&message=pizza%20e%20birra&label=Bar")!!
        assertEquals(addr, r.recipient)
        assertEquals(0.25, r.amount)
        assertEquals("pizza e birra", r.message)
        assertEquals("Bar", r.label)
    }

    @Test fun accentsAndCommasSurvive() {
        val r = SolanaPay.parse("solana:$addr?amount=1,5&message=caff%C3%A8+macchiato")!!
        assertEquals(1.5, r.amount, "a comma is what an Italian keyboard produces")
        assertEquals("caffè macchiato", r.message)
    }

    @Test fun aBareAddressIsStillARequest() {
        val r = SolanaPay.parse("solana:$addr")!!
        assertEquals(addr, r.recipient)
        assertNull(r.amount, "no amount means the payer decides")
    }

    @Test fun whatIsNotAPaymentRequestIsRejected() {
        assertNull(SolanaPay.parse("https://example.com/pay"))
        assertNull(SolanaPay.parse("apex://claim?k=abc"))
        assertNull(SolanaPay.parse("solana:"))
        // A Solana Pay *transaction request* points at a server that hands back a
        // transaction. That is a different, much larger trust decision: not here.
        assertNull(SolanaPay.parse("solana:https://merchant.example/tx"))
    }

    @Test fun aZeroOrBrokenAmountIsTreatedAsNoAmount() {
        assertNull(SolanaPay.parse("solana:$addr?amount=0")!!.amount)
        assertNull(SolanaPay.parse("solana:$addr?amount=abc")!!.amount)
    }
}
