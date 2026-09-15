package com.clearsign.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The translation from a node's answer to a sentence.
 *
 * Tested because the failure mode is silent: an unrecognised shape falls back to
 * the raw JSON, which is what the screen showed before this existed, so nothing
 * breaks visibly when a case is missed. Only a test notices.
 */
class SimErrorTest {

    /** The one that started this: a swap to CATE, seen on the phone. */
    @Test fun theRouteErrorReadsLikeAdvice() {
        val s = SimError.explain("""{"InstructionError":[6,{"Custom":6025}]}""", it = true)!!
        assertTrue(s.startsWith("La rotta non regge"), s)
        assertTrue(s.endsWith("(Custom 6025)"), "the code stays, for the bug report: $s")
    }

    @Test fun englishToo() {
        val s = SimError.explain("""{"InstructionError":[6,{"Custom":6025}]}""")!!
        assertTrue(s.contains("fresh quote"), s)
    }

    @Test fun theOtherShapeOfTheSameCode() {
        assertTrue(SimError.explain("Error processing Instruction 3: custom program error: Custom(6001)")!!.contains("Custom 6001"))
    }

    @Test fun rentAndBalanceAreDifferentProblems() {
        assertTrue(SimError.explain("Transaction results in an account with insufficient funds for rent", it = true)!!.contains("affitto"))
        assertTrue(SimError.explain("insufficient lamports 890880, need 1000000", it = true)!!.contains("saldo"))
    }

    @Test fun anExpiredTransactionSaysSo() {
        assertTrue(SimError.explain("BlockhashNotFound", it = true)!!.contains("scaduta"))
    }

    /** Unknown stays unknown: the caller falls back to the raw text rather than inventing a reason. */
    @Test fun anUnknownErrorIsNotGuessedAt() {
        assertNull(SimError.explain("something nobody has seen before"))
        assertNull(SimError.explain(""))
        assertNull(SimError.explain(null))
    }

    @Test fun noCodeMeansNoBrackets() {
        assertEquals(
            "The transaction has expired. It has to be built again.",
            SimError.explain("BlockhashNotFound"),
        )
    }
}
