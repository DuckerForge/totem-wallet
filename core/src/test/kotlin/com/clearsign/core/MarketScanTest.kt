package com.clearsign.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gates and the ranking, checked against the cases they were written for.
 *
 * The ones that matter most are the negatives: a veto that stops firing is
 * invisible in an app until it costs money.
 */
class MarketScanTest {

    /** A healthy small cap: every field present, nothing alarming. */
    private fun healthy(
        mint: String = "Good1111111111111111111111111111111111111111",
        symbol: String = "GOOD",
    ) = Candidate(
        mint = mint, symbol = symbol, name = "Good Coin", decimals = 6, usd = 0.01,
        liquidity = 400_000.0, mcap = 6_000_000.0, holders = 4_000, organicScore = 70.0,
        verified = true, topHoldersPct = 12.0, devMints = 1, ageMinutes = 5_000.0,
        s5m = ScanWindow(priceChange = 0.4, liquidityChange = 0.2),
        s1h = ScanWindow(
            priceChange = 2.0, liquidityChange = 0.5, volume = 200_000.0,
            buyVolume = 120_000.0, sellVolume = 80_000.0,
            buyOrganicVolume = 90_000.0, sellOrganicVolume = 40_000.0,
            numBuys = 900, numTraders = 700, numOrganicBuyers = 450, holderChange = 0.05,
        ),
        s6h = ScanWindow(priceChange = 4.0),
        s24h = ScanWindow(priceChange = 9.0, volume = 900_000.0),
    )

    @Test fun aHealthyCoinPasses() {
        assertNull(passesGate(healthy(), ScanGate.CAREFUL))
        assertNull(passesGate(healthy(), ScanGate.BOLD))
    }

    @Test fun aLiveFreezeAuthorityIsVetoedEvenInTheWildLane() {
        val c = healthy().copy(canFreeze = true)
        assertNotNull(passesGate(c, ScanGate.CAREFUL))
        assertNotNull(passesGate(c, ScanGate.BOLD), "the bold lane is looser, not blind")
    }

    @Test fun aLiveMintAuthorityIsVetoedEverywhere() {
        val c = healthy().copy(canMint = true)
        assertNotNull(passesGate(c, ScanGate.CAREFUL))
        assertNotNull(passesGate(c, ScanGate.BOLD))
    }

    @Test fun aMarketCapWithNoLiquidityUnderItIsVetoed() {
        // Ten million of "market cap" on five thousand dollars of depth.
        val c = healthy().copy(liquidity = 5_000.0, mcap = 10_000_000.0)
        assertNotNull(passesGate(c, ScanGate.BOLD))
    }

    @Test fun concentratedSupplyIsVetoed() {
        assertNotNull(passesGate(healthy().copy(topHoldersPct = 62.0), ScanGate.BOLD))
        // and the careful lane draws the line earlier
        assertNotNull(passesGate(healthy().copy(topHoldersPct = 38.0), ScanGate.CAREFUL))
        assertNull(passesGate(healthy().copy(topHoldersPct = 38.0), ScanGate.BOLD))
    }

    /** The house rule, and the one most likely to be broken by a later edit. */
    @Test fun missingDataNeverVetoes() {
        val bare = Candidate(
            mint = "New11111111111111111111111111111111111111111", symbol = "NEW",
            liquidity = 40_000.0,
            s1h = ScanWindow(priceChange = 1.0, buyVolume = 1_000.0, sellVolume = 900.0, numBuys = 60, numTraders = 50),
        )
        assertNull(passesGate(bare, ScanGate.CAREFUL), "no holders, no audit, no mcap: unknown, not guilty")
        assertTrue(runnerScore(bare, ScanGate.CAREFUL).first > 0.0)
    }

    @Test fun aWashTradedCoinRanksBelowAnHonestOne() {
        // All the volume on the buy side, and the price does not move.
        val wash = healthy(mint = "Wash11111111111111111111111111111111111111", symbol = "WASH").copy(
            s5m = ScanWindow(priceChange = 0.0, liquidityChange = 0.0),
            s1h = ScanWindow(
                priceChange = 0.5, volume = 200_000.0, buyVolume = 190_000.0, sellVolume = 5_000.0,
                buyOrganicVolume = 1_000.0, sellOrganicVolume = 500.0,
                numBuys = 900, numTraders = 700, numOrganicBuyers = 20,
            ),
            s6h = ScanWindow(priceChange = 0.2), s24h = ScanWindow(priceChange = 0.3, volume = 900_000.0),
        )
        val a = runnerScore(healthy(), ScanGate.CAREFUL).first
        val b = runnerScore(wash, ScanGate.CAREFUL)
        assertTrue(b.first < a / 3, "wash ${b.first} vs honest $a")
        assertTrue(b.second.any { it.contains("wash") }, b.second.toString())
    }

    @Test fun aFallingKnifeRanksBelowACoinHoldingUp() {
        val knife = healthy(mint = "Knif11111111111111111111111111111111111111", symbol = "KNIF").copy(
            s5m = ScanWindow(priceChange = -3.0, liquidityChange = -1.0),
            s1h = healthy().s1h!!.copy(priceChange = -8.0),
            s6h = ScanWindow(priceChange = -20.0),
            s24h = ScanWindow(priceChange = -40.0, volume = 900_000.0),
        )
        val r = runnerScore(knife, ScanGate.CAREFUL)
        assertTrue(r.first < runnerScore(healthy(), ScanGate.CAREFUL).first / 5, r.first.toString())
        assertTrue(r.second.any { it.contains("red") }, r.second.toString())
    }

    /** Real wallets selling into bot buying is distribution, not demand. */
    @Test fun organicSellingIsPenalised() {
        val distributing = healthy().copy(
            s1h = healthy().s1h!!.copy(buyOrganicVolume = 10_000.0, sellOrganicVolume = 90_000.0),
        )
        assertTrue(runnerScore(distributing, ScanGate.CAREFUL).first < runnerScore(healthy(), ScanGate.CAREFUL).first)
    }

    @Test fun theScanSortsAndSaysWhatItThrewOut() {
        val list = listOf(
            healthy(),
            healthy(mint = "Froz11111111111111111111111111111111111111", symbol = "FROZ").copy(canFreeze = true),
            healthy(mint = "Thin11111111111111111111111111111111111111", symbol = "THIN").copy(liquidity = 900.0),
        )
        val r = scanMarket(list, ScanGate.CAREFUL, limit = 5)
        assertEquals(1, r.picks.size)
        assertEquals("GOOD", r.picks[0].c.symbol)
        assertEquals(3, r.looked)
        assertEquals(2, r.rejected.values.sum())
    }

    /**
     * A real one that got through, kept as a test so it cannot get through again.
     *
     * DRANK, mint 5GU3VELV…BoKc, as Jupiter had it three days after launch: up
     * 288% on the day, down 64% over six hours, liquidity down 44% in the same
     * window. Every fatal check passed — no mint authority, no freeze authority,
     * 413 holders, top holders under 30% — because nothing about the coin was
     * wrong. What was wrong was happening to it.
     */
    private fun drank() = Candidate(
        mint = "5GU3VELVxiQVu83AtMvHmQyWPVkhCM721DLPiLnVBoKc", symbol = "DRANK", name = "DRANK",
        decimals = 6, usd = 0.00008747, liquidity = 24_761.0, mcap = 87_471.0, holders = 413,
        organicScore = 58.9, verified = false, topHoldersPct = 29.25, devMints = 2, ageMinutes = 4_320.0,
        s5m = ScanWindow(priceChange = -7.8, liquidityChange = -3.1, numTraders = 5, numOrganicBuyers = 1, numBuys = 6),
        s1h = ScanWindow(priceChange = -25.2, liquidityChange = -14.0, numTraders = 59, numOrganicBuyers = 5, numBuys = 64),
        s6h = ScanWindow(priceChange = -63.7, liquidityChange = -44.4, numTraders = 438, numOrganicBuyers = 30, numBuys = 816),
        s24h = ScanWindow(priceChange = 288.0, liquidityChange = 321.8, numTraders = 700, numOrganicBuyers = 34, numBuys = 2044),
    )

    @Test fun theCarefulLaneNeverSawIt() {
        // Thin for this lane, and that alone was enough.
        assertNotNull(passesGate(drank(), ScanGate.CAREFUL))
    }

    @Test fun aDrainingPoolIsVetoedInBothLanes() {
        assertNotNull(passesGate(drank(), ScanGate.BOLD), "the bold lane used to let this through")
        assertTrue(passesGate(drank(), ScanGate.BOLD)!!.contains("drained"))
    }

    /** The veto is about the pool leaving, not about a bad day. */
    @Test fun aPriceFallingOnItsOwnIsNotADrain() {
        val sold = drank().copy(
            liquidity = 400_000.0,
            s6h = ScanWindow(priceChange = -63.7, liquidityChange = -4.0),
            s24h = ScanWindow(priceChange = 12.0, liquidityChange = 2.0),
        )
        assertNull(passesGate(sold, ScanGate.BOLD), "a hard sell-off with the pool intact is still tradeable")
    }

    @Test fun ageIsScoredAgainstTheLaneNotInTheAbsolute() {
        // Four hours old: right in the bold lane's window, early for the careful one.
        val young = 240.0
        assertTrue(ageScore(young, ScanGate.BOLD) > ageScore(young, ScanGate.CAREFUL))
        assertEquals(0.5, ageScore(null, ScanGate.BOLD), "unknown age is neutral")
    }

    @Test fun momentumBlendsTheWindowsItHas() {
        val c = healthy().copy(s1h = ScanWindow(priceChange = 10.0), s6h = null, s24h = null)
        assertEquals(10.0, momentumBlend(c)!!, 0.001, "one window present means that window")
        assertNull(momentumBlend(healthy().copy(s1h = null, s6h = null, s24h = null)))
    }
}
