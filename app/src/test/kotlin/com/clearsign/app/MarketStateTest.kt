package com.clearsign.app

import kotlin.test.Test
import kotlin.test.assertEquals

/** The state of the market list: what shows with no list, while it arrives, and while searching. */
class MarketStateTest {
    @Test fun `senza listino si distingue «sta arrivando» da «non e' arrivato»`() {
        assertEquals(MarketState.LOADING, marketState(loading = true, rankedEmpty = true, query = "", searching = false, shownEmpty = true))
        assertEquals(MarketState.DOWN, marketState(loading = false, rankedEmpty = true, query = "", searching = false, shownEmpty = true))
        assertEquals(MarketState.LIST, marketState(loading = false, rankedEmpty = false, query = "", searching = false, shownEmpty = false))
        // The list is there but every coin is followed: the list is empty and that is fine.
        assertEquals(MarketState.LIST, marketState(loading = false, rankedEmpty = false, query = "", searching = false, shownEmpty = true))
    }

    @Test fun `la ricerca ha i suoi stati`() {
        assertEquals(MarketState.SEARCHING, marketState(loading = false, rankedEmpty = false, query = "bo", searching = true, shownEmpty = true))
        assertEquals(MarketState.NO_RESULTS, marketState(loading = false, rankedEmpty = false, query = "bonk", searching = false, shownEmpty = true))
        assertEquals(MarketState.LIST, marketState(loading = false, rankedEmpty = false, query = "bonk", searching = false, shownEmpty = false))
        // A single letter is not a search.
        assertEquals(MarketState.LIST, marketState(loading = false, rankedEmpty = false, query = "b", searching = false, shownEmpty = false))
    }
}
