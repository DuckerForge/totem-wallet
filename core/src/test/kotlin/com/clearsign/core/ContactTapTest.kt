package com.clearsign.core

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContactTapTest {
    private val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val addr = "DEM0ownerWa11etF0rTests0n1yNotARea1Key111jQ"
    private fun sign(p: String) = Signature.getInstance("SHA256withECDSA").apply { initSign(kp.private); update(p.toByteArray()) }.sign()

    @Test fun roundTripVerifies() {
        val uri = ContactTap.encode(addr, "Oliver & co", 1789500000000L, kp.public.encoded, sign(ContactTap.payload(addr, "Oliver & co", 1789500000000L)))
        val c = ContactTap.parse(uri)
        assertNotNull(c)
        assertEquals("Oliver & co", c!!.name)
        assertEquals(addr, c.address)
        assertTrue(ContactTap.verify(c))
    }

    @Test fun aChangedAddressFails() {
        val uri = ContactTap.encode(addr, "Oliver", 1L, kp.public.encoded, sign(ContactTap.payload(addr, "Oliver", 1L)))
        val forged = uri.replace(addr, "7NzrN8GzgAykyNrGE8eUWNM5rBHZjHrzKGuLTeeCFgf1")
        val c = ContactTap.parse(forged)
        assertNotNull(c)
        assertFalse(ContactTap.verify(c!!))
        assertEquals(null, ContactTap.parse("solana:$addr"))
    }
}
