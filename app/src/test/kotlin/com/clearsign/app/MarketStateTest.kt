package com.clearsign.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** Lo stato della lista del mercato: cosa si mostra quando il listino non c'e', mentre arriva, e cercando. */
class MarketStateTest {
    @Test fun `senza listino si distingue «sta arrivando» da «non e' arrivato»`() {
        assertEquals(MarketState.LOADING, marketState(loading = true, rankedEmpty = true, query = "", searching = false, shownEmpty = true))
        assertEquals(MarketState.DOWN, marketState(loading = false, rankedEmpty = true, query = "", searching = false, shownEmpty = true))
        assertEquals(MarketState.LIST, marketState(loading = false, rankedEmpty = false, query = "", searching = false, shownEmpty = false))
        // Il listino c'e' ma tutte le monete sono seguite: la lista e' vuota e va bene cosi'.
        assertEquals(MarketState.LIST, marketState(loading = false, rankedEmpty = false, query = "", searching = false, shownEmpty = true))
    }

    @Test fun `la ricerca ha i suoi stati`() {
        assertEquals(MarketState.SEARCHING, marketState(loading = false, rankedEmpty = false, query = "bo", searching = true, shownEmpty = true))
        assertEquals(MarketState.NO_RESULTS, marketState(loading = false, rankedEmpty = false, query = "bonk", searching = false, shownEmpty = true))
        assertEquals(MarketState.LIST, marketState(loading = false, rankedEmpty = false, query = "bonk", searching = false, shownEmpty = false))
        // Una lettera sola non e' una ricerca.
        assertEquals(MarketState.LIST, marketState(loading = false, rankedEmpty = false, query = "b", searching = false, shownEmpty = false))
    }
}
