package com.clearsign.app

import kotlin.test.Test
import kotlin.test.assertEquals

class LedgerRecentTest {
    @Test
    fun `stops reading months once the list is full`() {
        var opened = 0
        val months = listOf(
            { opened++; listOf(9, 8, 7) },
            { opened++; listOf(6, 5) },
            { opened++; listOf(4, 3, 2, 1) },
        )
        assertEquals(listOf(9, 8, 7, 6), Ledger.takeAcross(months, 4) { true })
        assertEquals(2, opened)
    }

    @Test
    fun `filters and keeps order across months`() {
        val months = listOf({ listOf(9, 8, 7) }, { listOf(6, 5) }, { listOf(4, 3, 2, 1) })
        assertEquals(listOf(8, 6, 4), Ledger.takeAcross(months, 3) { it % 2 == 0 })
        assertEquals(listOf(8, 6, 4, 2), Ledger.takeAcross(months, 10) { it % 2 == 0 })
    }
}
