package com.clearsign.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetMathTest {
    /** Tonight's budget: 0.1028 SOL, ceilings 0.0436, 80% slices, SOL at 100 $. */
    @Test fun tonightsBudgetWasUnderTheFloor() {
        val s = BudgetMath.sizing(102_835_811L, 43_574_344L, 43_574_344L, 80, 3, 100.0)
        assertEquals(34_859_475L, s.sliceLamports)
        assertEquals(3.49, s.sliceUsd!!, 0.01)
        assertFalse(s.onChain!!)
        assertEquals(2, s.fits)
        assertTrue(s.perTxNeeded!! > 62_000_000L)
    }

    @Test fun aBiggerCeilingClearsTheFloor() {
        val s = BudgetMath.sizing(200_000_000L, 70_000_000L, 70_000_000L, 80, 3, 100.0)
        assertTrue(s.onChain!!)
        assertEquals(3, s.fits)
    }

    @Test fun noPriceMeansNoVerdict() {
        val s = BudgetMath.sizing(100_000_000L, 40_000_000L, 0L, 80, 3, null)
        assertNull(s.onChain)
        assertNull(s.perTxNeeded)
        assertEquals(32_000_000L, s.sliceLamports)
    }
}
