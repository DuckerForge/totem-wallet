package com.clearsign.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Real accounts, read from mainnet on the 16th of September 2026.
 *
 * The numbers here were checked twice over: the mints decoded out of each pool
 * account match what Orca's and Raydium's own public APIs say about the same
 * pool, and each position's address comes back out of the derivation from its
 * NFT mint. If a program ever changes its layout, these fail loudly rather
 * than quietly reporting the wrong money.
 */
class LpFeesTest {
    private fun b(s: String) = Base64.getDecoder().decode(s)

    private val orcaPos = b("qryP5HpA99CyNpDX0HWNHV2LiVDOx6m018ea6P+1xroNvWKhmDeTW0VJYd8inuXJzi1AMa0zZhH4cbXMa4gTKXAjGxMXCm3XM9E52AUAAAAAAAAAAAAAAPiK//8gq///TIOhUZkSowwAAAAAAAAAAOXzFwYAAAAAovTPwNdUy/////////////CmnwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
    private val orcaPool = b("P5XRDOGAYwkT5EH4ORPKaLBjT7Al/eqohzfoQRDRJV41ezN33e4czf8EAAQAkAEUBf+1Ti6FiwIAAAAAAAAAAAAdaVgzmiooUAAAAAAAAAAAR6X//6NzIiQAAAAAnIRsBgAAAAAGm4hX/quBhPtof2NGGMA12sQ53BrrO1WYoPAAAAAAAchN8kM4mDvkqFswl7r0C8lXEQjSiawAs2jfF11Edc96OSPl7Rfn7LcAAAAAAAAAAMb6evO+2606PWXzaqvJdDGxu+TC0vbg5HymAgNFL11hFl+VcsWpaqUC3VEQVKJqbSWO98HW1sGu4SkZFNxRAjKm9XJbdHiCFwAAAAAAAAAACIOqagAAAAAMANCv64YU2n8Zq6AtQPGMaSWF9lAg387T1eX5qcDE")
    private val rayPos = b("Rm+WfuYPGXX85bo2OZMQuDH7wAfNnVQow670wb0T0DPG1Xe3hjdmg/ErMZNgv6LOy5LEsKRgQJoPBCgDP2ATALiz/Pmc82Auh3CR//+xr///jP5JCA8AAAAAAAAAAAAAAMfUOyCsiJvv//////////+EBuP2LjAi/P//////////h3LmUAAAAABiM2kGAAAAADcNZ4Vhcfv///////////+VrSoAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAOMDAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
    private val rayPool = b("9+3j9dfD3kb/J/h1kZHXDyshRTJ1cBKA+75w9IwihSFNbCJrm2AsTU2n4L9mLmKyVEpBQSbsZYZslSyLgLfqrjxPjwNPsqt58AabiFf+q4GE+2h/Y0YYwDXaxDncGus7VZig8AAAAAABxvp6877brTo9ZfNqq8l0MbG75MLS9uDkfKYCA0UvXWE1xC8EegCgoA4uXlAv1Mq8Ujt5easRI0mT0Kd5/M0SaUYpXTwujyqOjii0GtMaFsBn/mlkafyZcZXVyvv1WhbIJa4wmFjRjYV3XU2tkbL5lj49adulPU/iZbZpnkdbsRkJBgEArfy1v6tgAAAAAAAAAAAAAH0q7JyGxidQAAAAAAAAAABGpf//AAAAABSJuP2rb3dYAAAAAAAAAACRfi2WZS0C")

    @Test fun `orca position carries the fees it owes`() {
        val o = LpFees.decodeOrca("8DWSUfcDBokqkt4327zg2QtX3zixp8DGbVzrVqGPeoqG", orcaPos)!!
        assertEquals(LpFees.Venue.ORCA, o.venue)
        assertEquals("Czfq3xZZDmsdGdUyrNLtRhGc47cXcZtLG4crryfu44zE", o.pool)
        assertEquals(102233061L, o.rawA)
        assertEquals(10462960L, o.rawB)
        assertTrue(o.liquidity.signum() > 0)
    }

    @Test fun `orca pool names its two coins`() {
        val p = LpFees.poolOrca(orcaPool)!!
        assertEquals("So11111111111111111111111111111111111111112", p.mintA)
        assertEquals("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", p.mintB)
    }

    @Test fun `raydium position carries the fees it owes`() {
        val o = LpFees.decodeRaydium("4GkPbQ8V5ti1Y5jTzwACbSbVZQAhY7VJc5oi43Z7U4U3", rayPos)!!
        assertEquals(LpFees.Venue.RAYDIUM, o.venue)
        assertEquals("3ucNos4NbumPLZNWztqGHNFFgkHeRMBQAVemeeomsUxv", o.pool)
        assertEquals(1357279879L, o.rawA)
        assertEquals(107557730L, o.rawB)
    }

    @Test fun `raydium pool names its coins and their decimals`() {
        val p = LpFees.poolRaydium(rayPool)!!
        assertEquals("So11111111111111111111111111111111111111112", p.mintA)
        assertEquals("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", p.mintB)
        assertEquals(9, p.decA)
        assertEquals(6, p.decB)
    }

    @Test fun `the position address comes back from its NFT mint`() {
        assertEquals(
            "8DWSUfcDBokqkt4327zg2QtX3zixp8DGbVzrVqGPeoqG",
            LpFees.positionOf(LpFees.Venue.ORCA, "5fU2niy2YiGUktHB5mYNJAh9iLZ6uu28D4mf9sfCKeNa"),
        )
        assertEquals(
            "4GkPbQ8V5ti1Y5jTzwACbSbVZQAhY7VJc5oi43Z7U4U3",
            LpFees.positionOf(LpFees.Venue.RAYDIUM, "GTm3sPWckSbP4QPjCMRjrfU7FqwurRx6RynfHYwCa2VW"),
        )
    }

    @Test fun `a position owed nothing is not a finding`() {
        val empty = orcaPos.copyOf()
        for (i in 112 until 120) empty[i] = 0
        for (i in 136 until 144) empty[i] = 0
        assertNull(LpFees.decodeOrca("x", empty))
    }

    @Test fun `only a single indivisible token can be a position`() {
        fun acct(mint: String, amount: Long, decimals: Int) =
            SolanaRpc.TokenAccountInfo("p" + mint, mint, decimals, amount, 2_039_280L, null, 0L, "tok", null, "initialized")
        val mints = LpFees.candidateMints(
            listOf(acct("nft", 1L, 0), acct("coin", 1_000_000L, 6), acct("burned", 0L, 0)),
        )
        assertEquals(listOf("nft"), mints)
    }
}
