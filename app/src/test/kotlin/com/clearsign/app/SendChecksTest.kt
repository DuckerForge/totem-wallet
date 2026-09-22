package com.clearsign.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendChecksTest {
    @Test
    fun `a fresh address or a system account is a wallet`() {
        assertFalse(SendChecks.notAWallet(null))
        assertFalse(SendChecks.notAWallet("11111111111111111111111111111111"))
    }

    @Test
    fun `a token account or a program is not`() {
        assertTrue(SendChecks.notAWallet("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"))
        assertTrue(SendChecks.notAWallet("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"))
        assertTrue(SendChecks.notAWallet("JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4"))
    }
}
