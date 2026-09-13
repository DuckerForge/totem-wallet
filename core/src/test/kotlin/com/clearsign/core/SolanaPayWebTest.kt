package com.clearsign.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A request has to survive both shapes: the `solana:` URI a wallet expects, and
 * the https link a phone with no wallet can actually open.
 */
class SolanaPayWebTest {
    private val addr = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"

    @Test
    fun `web link carries the same request as the scheme`() {
        val web = SolanaPay.parse("https://duckerforge.github.io/apex/p/?to=$addr&amount=0.25&message=Pizza%20e%20birra")
        val uri = SolanaPay.parse("solana:$addr?amount=0.25&message=Pizza%20e%20birra")
        assertEquals(uri, web)
        assertEquals(addr, web!!.recipient)
        assertEquals(0.25, web.amount)
        assertEquals("Pizza e birra", web.message)
    }

    @Test
    fun `web link without an amount is still a request`() {
        val r = SolanaPay.parse("https://duckerforge.github.io/apex/p/?to=$addr")
        assertEquals(addr, r!!.recipient)
        assertNull(r.amount)
    }

    @Test
    fun `some other page is not a request`() {
        assertNull(SolanaPay.parse("https://duckerforge.github.io/apex/g/#k=abc"))
        assertNull(SolanaPay.parse("https://example.com/?to=$addr&amount=1"))
        assertNull(SolanaPay.parse("https://duckerforge.github.io/apex/p/?amount=1"))
    }

    @Test
    fun `the classic form still parses`() {
        val r = SolanaPay.parse("solana:$addr?amount=1&label=Luca")
        assertEquals(addr, r!!.recipient)
        assertEquals(1.0, r.amount)
        assertEquals("Luca", r.label)
    }
}
