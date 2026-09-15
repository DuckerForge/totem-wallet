package com.clearsign.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrdersTest {
    private fun order(kind: Orders.Kind, key: String) = Orders.Order(
        kind = kind, mint = "Mint$key", symbol = "X", decimals = 6, key = key, amountRaw = 1_000_000L,
        targetUsd = 1.5, createdAt = 1L, expiresAt = 2L,
    )

    /** What Jupiter no longer lists is gone; what could not be asked stays. */
    @Test fun theChainDecidesWhatIsStillOpen() {
        val tp = order(Orders.Kind.TP, "A")
        val limit = order(Orders.Kind.LIMIT, "B")
        val dca = order(Orders.Kind.DCA, "C")
        val (keep, gone) = Orders.reconcile(listOf(tp, limit, dca), liveTrigger = setOf("A"), liveDca = setOf("C"))
        assertEquals(listOf(tp, dca), keep)
        assertEquals(listOf(limit), gone)
        // Jupiter's trigger side unreachable: nothing on that side moves.
        val (keep2, gone2) = Orders.reconcile(listOf(tp, limit, dca), liveTrigger = null, liveDca = emptySet())
        assertEquals(listOf(tp, limit), keep2)
        assertEquals(listOf(dca), gone2)
    }

    /** A DCA we never learned the key of is kept while any DCA is live, dropped when none is. */
    @Test fun anUnnamedDcaFollowsTheList() {
        val unnamed = order(Orders.Kind.DCA, "sig:abc")
        assertTrue(Orders.reconcile(listOf(unnamed), emptySet(), setOf("other")).first.isNotEmpty())
        assertTrue(Orders.reconcile(listOf(unnamed), emptySet(), emptySet()).second.isNotEmpty())
    }

    @Test fun anAlertFiresOnItsSideOnly() {
        val up = Orders.Alert("M", "X", above = true, priceUsd = 2.0, createdAt = 0L)
        val down = Orders.Alert("M", "X", above = false, priceUsd = 1.0, createdAt = 0L)
        assertEquals(listOf(up), Orders.fired(listOf(up, down), mapOf("M" to 2.5)))
        assertEquals(listOf(down), Orders.fired(listOf(up, down), mapOf("M" to 0.9)))
        assertTrue(Orders.fired(listOf(up, down), mapOf("M" to 1.5)).isEmpty())
        // No price, nothing fires: an unknown is not a crossing.
        assertTrue(Orders.fired(listOf(up, down), emptyMap()).isEmpty())
    }

    @Test fun ordersAndAlertsSurviveTheRoundTrip() {
        val o = order(Orders.Kind.DCA, "K").copy(rounds = 4, intervalSec = 604_800L)
        assertEquals(o, Orders.orderFromJson(Orders.orderToJson(o)))
        val a = Orders.Alert("M", "X", above = false, priceUsd = 0.00001234, createdAt = 42L)
        assertEquals(a, Orders.alertFromJson(Orders.alertToJson(a)))
        assertEquals("M-", a.id)
    }
}
