package com.clearsign.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AddressPoisonTest {
    private val friend = "DEM0ownerWa11etF0rTests0n1yNotARea1Key111jQ"
    private val poison = "cHAHx9Qz2LmN4pRt8VwYb3CdEfGh6JkLmN7PqRsTVZjQ"

    @Test fun sameEndsDifferentMiddleIsALookalike() {
        val hit = AddressPoison.lookalike(poison, listOf(friend, "7NzrN8GzgAykyNrGE8eUWNM5rBHZjHrzKGuLTeeCFgf1"))
        assertEquals(friend, hit?.of)
    }

    @Test fun theRealAddressIsNotItsOwnLookalike() {
        assertNull(AddressPoison.lookalike(friend, listOf(friend)))
    }

    @Test fun unrelatedAddressesPass() {
        assertNull(AddressPoison.lookalike("7NzrN8GzgAykyNrGE8eUWNM5rBHZjHrzKGuLTeeCFgf1", listOf(friend)))
        assertNull(AddressPoison.lookalike("short", listOf(friend)))
    }
}
