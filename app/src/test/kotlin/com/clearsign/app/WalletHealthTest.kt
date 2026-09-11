package com.clearsign.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WalletHealthTest {
    private val owner = "MEwa11etxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx1"
    private fun acct(amount: Long = 100, delegate: String? = null, unlimited: Boolean = false, frozen: Boolean = false, close: String? = null) =
        SolanaRpc.TokenAccountInfo("pk-$amount-$delegate-$frozen", "EPjF", 6, amount, 2_039_280L, delegate, if (unlimited) -1L else if (delegate != null) 50L else 0L, "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", close, if (frozen) "frozen" else "initialized")

    @Test fun cleanWalletIs100() {
        val h = WalletHealth.of(owner, listOf(acct(amount = 100)))
        assertEquals(100, h.score); assertTrue(h.isClean)
    }
    @Test fun unlimitedApprovalHurtsMost() {
        val h = WalletHealth.of(owner, listOf(acct(delegate = "D", unlimited = true)))
        assertEquals(78, h.score)
        assertTrue(h.issues.any { it.kind == HealthIssue.Kind.UNLIMITED_APPROVAL })
        assertEquals(WalletHealth.Band.OK, h.band)
    }
    @Test fun dustReclaimable() {
        val h = WalletHealth.of(owner, listOf(acct(amount = 0), acct(amount = 0)))
        assertEquals(96, h.score)
        assertEquals(2 * 2_039_280L, h.reclaimableLamports)
    }
    @Test fun deductionsAreCapped() {
        val h = WalletHealth.of(owner, (1..10).map { acct(delegate = "D$it", unlimited = true) })
        assertEquals(34, h.score)
    }
}
