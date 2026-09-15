package com.clearsign.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeekerCrowdTest {
    private val now = 1_700_000_000_000L
    private val h = 3600_000L

    private fun buy(w: String, mint: String, agoH: Long, tier: SeekerTier = SeekerTier.DOLPHIN, sol: Double = 0.5) =
        CrowdBuy(w, tier, mint, mint, now - agoH * h, sol)

    /**
     * The whole point. On the sixty richest Seeker wallets the top "trend" of the
     * week was one address buying TOS ten times. Counting purchases would have
     * shown that as the hottest coin on the phone.
     */
    @Test fun onePersonBuyingTenTimesIsNotACrowd() {
        val r = SeekerCrowd.rank((1..10).map { buy("solo", "TOS", 1) }, now)
        assertTrue(r.isEmpty(), "una persona sola non è una folla: $r")
    }

    @Test fun threeDistinctWalletsIsTheFloor() {
        val two = SeekerCrowd.rank(listOf(buy("a", "X", 1), buy("b", "X", 1)), now)
        assertTrue(two.isEmpty())
        val three = SeekerCrowd.rank(listOf(buy("a", "X", 1), buy("b", "X", 1), buy("c", "X", 1)), now)
        assertEquals(1, three.size)
        assertEquals(3, three[0].wallets)
        assertEquals(3, three[0].buys)
    }

    /** Buying USDC is not picking a coin, and a census that says otherwise is noise. */
    @Test fun moneyIsNeverAPick() {
        val buys = SeekerCrowd.MONEY.flatMap { m -> listOf(buy("a", m, 1), buy("b", m, 1), buy("c", m, 1)) }
        assertTrue(SeekerCrowd.rank(buys, now).isEmpty())
    }

    @Test fun whalesOutweighDolphinsAtEqualCount() {
        val r = SeekerCrowd.rank(
            listOf(
                buy("w1", "BIG", 1, SeekerTier.WHALE), buy("w2", "BIG", 1, SeekerTier.WHALE), buy("w3", "BIG", 1, SeekerTier.WHALE),
                buy("d1", "SMALL", 1), buy("d2", "SMALL", 1), buy("d3", "SMALL", 1),
            ), now,
        )
        assertEquals("BIG", r[0].symbol)
        assertEquals(3, r[0].whales)
        assertEquals(0, r[1].whales)
    }

    /** Same crowd, colder trail: the one that stopped a day ago loses. */
    @Test fun freshBeatsStaleAtEqualCrowd() {
        val r = SeekerCrowd.rank(
            listOf(
                buy("a", "OLD", 20), buy("b", "OLD", 20), buy("c", "OLD", 20),
                buy("d", "NEW", 1), buy("e", "NEW", 1), buy("f", "NEW", 1),
            ), now,
        )
        assertEquals("NEW", r[0].symbol)
    }

    @Test fun outsideTheWindowIsGone() {
        val buys = listOf(buy("a", "X", 30), buy("b", "X", 30), buy("c", "X", 30))
        assertTrue(SeekerCrowd.rank(buys, now, windowMs = 24 * h).isEmpty())
        assertEquals(1, SeekerCrowd.rank(buys, now, windowMs = 48 * h).size)
    }

    /**
     * The budget arithmetic the design rests on. Ten thousand wallets on a free
     * key is only possible because finding the movers is one call per hundred.
     */
    @Test fun tenThousandWalletsFitAFreeKey() {
        val passes = SeekerCrowd.passesPerDay(10_527, 1_000_000)
        assertTrue(passes >= 24, "almeno un giro all'ora, invece $passes")
        // One call per wallet instead, which is what the naive version costs.
        val naive = SeekerCrowd.passesPerDay(10_527, 1_000_000, moverRate = 1.0)
        assertTrue(naive < 3, "il modo ingenuo non ci sta: $naive")
    }

    /**
     * The airdrop claim, which is what this crowd is mostly made of. A token
     * arrives, a couple of thousandths of a SOL leave for the fee and the rent of
     * the account holding it, and nothing about that was a decision.
     */
    @Test fun aClaimIsNotAPurchase() {
        val claims = listOf(
            buy("a", "FREE", 1, sol = 0.0021), buy("b", "FREE", 1, sol = 0.0017), buy("c", "FREE", 1, sol = 0.0032),
        )
        assertTrue(SeekerCrowd.rank(claims, now).isEmpty(), "0.002 SOL è affitto, non una scelta")
        val bought = claims.map { it.copy(solSpent = 0.05) }
        assertEquals(1, SeekerCrowd.rank(bought, now).size)
    }

    /**
     * The same wallet must be the same name on every phone and after every
     * restart, because it is a handle people will start recognising.
     */
    @Test fun nicknameIsStableAndCarriesTheAddress() {
        val a = "7rTHtV2tuczeSaru2hN3WKyZnxkAZWckceGMGb5pDRRR"
        val b = "49HmKKAdu7yWevhxnfXTvZFKTC9EbmK2v4veFZxAiqK8"
        assertEquals(SeekerCrowd.nickname(a), SeekerCrowd.nickname(a))
        assertTrue(SeekerCrowd.nickname(a) != SeekerCrowd.nickname(b))
        assertTrue(SeekerCrowd.nickname(a).endsWith("·7r"), SeekerCrowd.nickname(a))
    }

    @Test fun rosterSkipsCommentsAndJunk() {
        val r = SeekerCrowd.parseRoster(
            sequenceOf(
                "# intestazione",
                "",
                "2EayrcZsDBxvRyGTN942ZFFeRf5TkAzJ3Cpdzwts357r b 817173",
                "5otKuL7mCKtdZrxZqjqs9EupdQRCrNWx2cxKd2pUnv6m d 250",
                "corto d 1",
                "senzacampi",
            ),
        )
        assertEquals(2, r.size)
        assertEquals(SeekerTier.WHALE, r[0].tier)
        assertEquals(817173L, r[0].centiSol)
        assertEquals(SeekerTier.DOLPHIN, r[1].tier)
    }
}
