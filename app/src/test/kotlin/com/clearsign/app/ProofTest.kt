package com.clearsign.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class ProofTest {
    private val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val statement = """{"v":1,"at":1789500000000,"dApp":"Apex","outflows":["−0.05 SOL"],"inflows":[],"signer":"DEM0ownerWa11etF0rTests0n1yNotARea1Key111jQ","txSignature":"5Kx"}"""
    private fun sign(s: String) = Base64.getEncoder().encodeToString(
        Signature.getInstance("SHA256withECDSA").apply { initSign(kp.private); update(s.toByteArray()) }.sign(),
    )
    private val key = Base64.getEncoder().encodeToString(kp.public.encoded)

    @Test fun roundTrip() {
        val line = Proof.encode(statement, sign(statement), key)
        val v = Proof.verify(line)
        assertNotNull(v)
        assertEquals("DEM0ownerWa11etF0rTests0n1yNotARea1Key111jQ", v!!.signer)
        assertEquals("5Kx", v.txSignature)
        assertEquals(listOf("−0.05 SOL"), v.outflows)
    }

    @Test fun oneChangedByteFails() {
        val line = Proof.encode(statement.replace("0.05", "0.50"), sign(statement), key)
        assertNull(Proof.verify(line))
        assertNull(Proof.verify("solana:abc"))
    }
}
