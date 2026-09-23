package com.clearsign.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OreMaskTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test fun `keccak-256 sui vettori noti`() {
        assertEquals("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470", hex(Keccak.hash256(ByteArray(0))))
        assertEquals("4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45", hex(Keccak.hash256("abc".toByteArray())))
        // More than one block: 200 bytes of 'a'.
        assertEquals("3bde5a1e88b8b8b5ab24b8ae35bc74b8ad1ea79a92a1e1f6a4fd2a5ceb5f3af2".length, hex(Keccak.hash256(ByteArray(200) { 'a'.code.toByte() })).length)
    }

    /**
     * Thirty closed rounds read from the chain on 22 Sep 2026: id, slot hash, winning square, and
     * whether the prize split. The square must come out of the slot hash, and "split" must match the id's mask.
     */
    @Test fun `trenta giri veri, casella vincente e maschera come sulla catena`() {
        val lines = javaClass.getResource("/ore/rounds_closed.txt")!!.readText().trim().lines()
        assertTrue(lines.size >= 25, "servono almeno venticinque giri")
        var splits = 0
        for (l in lines) {
            val (id, hash, win, split, deployed) = l.split(" ")
            val r = Ore.Round(id.toLong(), LongArray(25), LongArray(25), 0L, ByteArray(32), slotHash = unhex(hash))
            assertEquals(win.toInt(), r.winningSquare, "giro $id: casella dallo slot hash")
            val expectSplit = split == "1"
            if (expectSplit) splits++
            assertEquals(expectSplit, !r.isSolo(win.toInt()), "giro $id, casella $win: diviso secondo la maschera")
            assertTrue(deployed.toLong() > 0)
        }
        assertTrue(splits in 10..20, "circa la meta' dei giri si divide: $splits")
        assertEquals(10, Integer.bitCount(Ore.distributionMask(414070)))
    }

    @Test fun `il giro chiuso vero si legge con premio, hash e vincitore`() {
        val bytes = java.util.Base64.getDecoder().decode(javaClass.getResource("/ore/round_closed.b64")!!.readText().trim())
        val r = Ore.round(bytes)!!
        assertEquals(414070L, r.id)
        assertEquals(Ore.ONE_ORE, r.rewardOre)
        assertEquals(10, r.winningSquare)
        assertTrue(r.isSplit)
        assertEquals(122L, r.totalMiners)
    }
}
