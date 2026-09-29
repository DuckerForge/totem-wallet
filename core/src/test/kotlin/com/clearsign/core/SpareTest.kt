package com.clearsign.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SpareTest {
    @Test fun `gia' tondo non lascia spiccioli`() {
        assertEquals(0, Spare.roundUp(10_000_000))
        assertEquals(0, Spare.roundUp(0))
        assertEquals(0, Spare.roundUp(-5))
    }

    @Test fun `un lamport sopra lascia quasi un passo`() {
        assertEquals(Spare.STEP - 1, Spare.roundUp(10_000_001))
        assertEquals(1, Spare.roundUp(Spare.STEP - 1))
    }

    @Test fun `0,0132 SOL arrotonda a 0,015`() {
        assertEquals(1_800_000, Spare.roundUp(13_200_000))
    }

    @Test fun `importi grandi non traboccano`() {
        assertEquals(1, Spare.roundUp(Long.MAX_VALUE - (Long.MAX_VALUE % Spare.STEP) - 1))
    }

    @Test fun `si muove solo quello che il saldo regge`() {
        assertEquals(20_000_000, Spare.movable(20_000_000, 100_000_000, 10_000_000))
        assertEquals(5_000_000, Spare.movable(20_000_000, 15_000_000, 10_000_000))
        assertEquals(0, Spare.movable(20_000_000, 5_000_000, 10_000_000))
    }

    @Test fun `sotto un passo non si muove niente`() {
        assertEquals(0, Spare.movable(20_000_000, 14_999_999, 10_000_000))
        assertEquals(0, Spare.movable(Spare.STEP - 1, 100_000_000, 10_000_000))
        assertEquals(Spare.STEP, Spare.movable(Spare.STEP, 100_000_000, 10_000_000))
    }
}
