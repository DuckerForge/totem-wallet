package com.clearsign.app

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenAccountParseTest {
    private val owner = "5tzFkiKscXHK5ZXCGbXZxdw7gTjjD1mBwuoFbhUvuAi9"

    /** One entry of getTokenAccountsByOwner(jsonParsed), as the node returns it. */
    private fun entry(
        amount: String = "1500000", delegate: String? = null, delegated: String? = null,
        state: String = "initialized", closeAuthority: String? = null, program: String = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
    ): JSONObject {
        val info = JSONObject()
            .put("isNative", false).put("mint", "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v").put("owner", owner).put("state", state)
            .put("tokenAmount", JSONObject().put("amount", amount).put("decimals", 6).put("uiAmountString", "1.5"))
        if (delegate != null) {
            info.put("delegate", delegate)
            info.put("delegatedAmount", JSONObject().put("amount", delegated).put("decimals", 6))
        }
        if (closeAuthority != null) info.put("closeAuthority", closeAuthority)
        val account = JSONObject()
            .put("lamports", 2039280L).put("owner", program).put("executable", false)
            .put("data", JSONObject().put("program", "spl-token").put("space", 165).put("parsed", JSONObject().put("type", "account").put("info", info)))
        return JSONObject().put("pubkey", "39FeRfGVCH5Bq5fzChHmL3LAsQCXAvFt7idaLipAQsCs").put("account", account)
    }

    @Test fun plainAccount() {
        val t = assertNotNull(SolanaRpc.parseTokenAccount(entry()))
        assertEquals(1_500_000L, t.amount)
        assertEquals(6, t.decimals)
        assertEquals(2_039_280L, t.lamports)
        assertNull(t.delegate)
        assertFalse(t.hasActiveDelegate)
        assertFalse(t.isClosableBy(owner))          // not empty
        assertEquals("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA", t.program)
    }

    @Test fun delegateBoundedAndUnlimited() {
        val bounded = assertNotNull(SolanaRpc.parseTokenAccount(entry(delegate = "DeLeGaTe1111111111111111111111111111111111", delegated = "250000")))
        assertTrue(bounded.hasActiveDelegate)
        assertEquals(250_000L, bounded.delegatedAmount)
        assertFalse(bounded.isUnlimitedDelegation)

        val unlimited = assertNotNull(SolanaRpc.parseTokenAccount(entry(delegate = "DeLeGaTe1111111111111111111111111111111111", delegated = "18446744073709551615")))
        assertTrue(unlimited.hasActiveDelegate)
        assertTrue(unlimited.isUnlimitedDelegation)

        // A delegate with nothing left to spend is not a live approval.
        val spent = assertNotNull(SolanaRpc.parseTokenAccount(entry(delegate = "DeLeGaTe1111111111111111111111111111111111", delegated = "0")))
        assertFalse(spent.hasActiveDelegate)
        // Frozen accounts can't be spent from, delegate or not.
        val frozen = assertNotNull(SolanaRpc.parseTokenAccount(entry(delegate = "DeLeGaTe1111111111111111111111111111111111", delegated = "5", state = "frozen")))
        assertFalse(frozen.hasActiveDelegate)
    }

    @Test fun closable() {
        assertTrue(assertNotNull(SolanaRpc.parseTokenAccount(entry(amount = "0"))).isClosableBy(owner))
        assertTrue(assertNotNull(SolanaRpc.parseTokenAccount(entry(amount = "0", closeAuthority = owner))).isClosableBy(owner))
        assertFalse(assertNotNull(SolanaRpc.parseTokenAccount(entry(amount = "0", closeAuthority = "SomeoneE1se11111111111111111111111111111111"))).isClosableBy(owner))
        assertFalse(assertNotNull(SolanaRpc.parseTokenAccount(entry(amount = "0", state = "frozen"))).isClosableBy(owner))
    }

    @Test fun malformedEntriesAreSkipped() {
        assertNull(SolanaRpc.parseTokenAccount(JSONObject()))
        assertNull(SolanaRpc.parseTokenAccount(JSONObject().put("pubkey", "x")))
        assertEquals(0L, SolanaRpc.parseUnsignedLong("abc"))
        assertEquals(0L, SolanaRpc.parseUnsignedLong(null))
        assertEquals(-1L, SolanaRpc.parseUnsignedLong("99999999999999999999999"))
    }
}
