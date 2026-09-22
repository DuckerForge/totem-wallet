package com.clearsign.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OreCrowdTest {
    private fun round(id: Long, win: Int, vararg heavy: Pair<Int, Long>): PastRound {
        val d = LongArray(25) { 1_000_000L }
        for ((s, v) in heavy) d[s] = v
        return PastRound(id, win, split = true, deployed = d, count = LongArray(25) { 1L }, miners = 25L)
    }

    @Test fun `l'affollamento e' la quota media della casella, e le migliori sono le piu' vuote`() {
        val rounds = listOf(round(1, 3, 0 to 10_000_000L, 7 to 0L), round(2, 3, 0 to 10_000_000L, 7 to 0L, 9 to 0L))
        val c = OreCrowd.crowding(rounds)
        assertEquals(0.0, c[7])
        assert(c[0] > c[1])
        assertEquals(listOf(7, 9, 1), OreCrowd.best(3, rounds))
    }

    @Test fun `senza giri tutto e' zero e le migliori sono le prime`() {
        assertEquals(25, OreCrowd.crowding(emptyList()).size)
        assertEquals(listOf(0, 1), OreCrowd.best(2, emptyList()))
        assertNull(OreCrowd.hottest(emptyList()))
    }

    @Test fun `uscite e piu' uscita`() {
        val rounds = listOf(round(1, 3), round(3, 5), round(2, 3))
        assertEquals(listOf(5, 3, 3), OreCrowd.winners(rounds))
        assertEquals(3 to 2, OreCrowd.hottest(rounds))
        assertEquals(1_000_000L, OreCrowd.averageDeployed(rounds)[4])
    }
}
