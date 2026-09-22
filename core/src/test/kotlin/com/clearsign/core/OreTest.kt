package com.clearsign.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ORE letto dai byte veri.
 *
 * I quattro conti in `resources/ore` sono stati scaricati dalla catena il 22
 * settembre 2026: la Board al giro 413544, la Config, quel Round, e il Miner
 * con piu' ORE da riscuotere fra i primi sessanta trovati. Gli offset qui
 * sotto non vengono da un riassunto del sorgente, vengono da quei byte: il
 * primo riassunto diceva 728 byte per il Miner e 56 per la parte admin della
 * Config, ed erano 744 e 72. Un test sui byte veri e' l'unico modo di non
 * ripetere quell'errore in silenzio.
 */
class OreTest {
    private fun fixture(name: String): ByteArray =
        java.util.Base64.getDecoder().decode(javaClass.getResource("/ore/$name.b64")!!.readText().trim())

    private fun le64(v: Long) = ByteArray(8) { ((v shr (8 * it)) and 0xFF).toByte() }
    private fun le32(v: Int) = ByteArray(4) { ((v shr (8 * it)) and 0xFF).toByte() }

    private val me = "D1nsSnCRwcmjweKNZAfHtApfPBskuALHD7dEKhybzNkj"
    private val other = "39FeRfGVCH5Bq5fzChHmL3LAsQCXAvFt7idaLipAQsCs"

    // ---- i conti ----------------------------------------------------------------

    @Test fun `la Board dice il giro e quanto manca`() {
        val b = assertNotNull(Ore.board(fixture("board")))
        assertEquals(413544L, b.roundId)
        assertEquals(200L, b.endSlot - b.startSlot)
        assertEquals(457905902L, b.productionCostEma)
        assertEquals(164 * 0.26, b.secondsLeft(449318204L), 0.01)
        assertEquals(0.0, b.secondsLeft(b.endSlot + 10))
        assertFalse(b.waiting)
    }

    @Test fun `la Config ha l'entropia a 160 e i giri a 200 slot`() {
        val c = assertNotNull(Ore.config(fixture("config")))
        assertEquals(40L, c.intermissionSlots)
        assertEquals(200L, c.roundSlots)
        assertTrue(c.entropyVar.any { it != 0.toByte() })
        // Sulla catena il programma dell'entropia scritto in Config e' il System
        // Program: tutti zeri. Il Deploy simulato con questi due conti passa.
        assertTrue(c.entropyProgram.all { it == 0.toByte() })
    }

    @Test fun `il Round ha 25 caselle piene e 87 minatori`() {
        val r = assertNotNull(Ore.round(fixture("round")))
        assertEquals(413544L, r.id)
        assertEquals(25, r.deployed.size)
        assertTrue(r.deployed.all { it > 0 })
        assertEquals(99_300_000L, r.deployed[0] / 100_000 * 100_000, "la prima casella era 0,0993 SOL")
        assertEquals(87L, r.totalMiners)
        assertTrue(r.topMiner.all { it == 0.toByte() })
        // Giro in corso: il premio non e' ancora scritto, lo slot hash e' vuoto, e la
        // casella vincente non c'e'. L'atteso conta l'ORE che verra' coniato.
        assertTrue(r.rewards.all { it == 0L }, "rewards si scrive a giro chiuso")
        assertEquals(0L, r.rewardOre)
        assertEquals(Ore.ONE_ORE, r.expectedReward)
        assertEquals(0L, r.motherlode)
        assertEquals(null, r.winningSquare)
        assertEquals(449606368L, r.expiresAt)
    }

    @Test fun `il Miner vero, con i numeri visti sulla catena`() {
        val m = assertNotNull(Ore.miner(fixture("miner")))
        assertEquals(92165L, m.roundId)
        assertEquals(92165L, m.checkpointId)
        assertEquals(86_903_161L, m.rewardsSol)
        assertEquals(399_633_444_150L, m.refinedOre)
        assertEquals(26_341_433_694_810L, m.rewardsOre)
        assertEquals(26_741_067_138_960L, m.lifetimeRewardsOre)
        assertEquals(1_373_412_000_000L, m.lifetimeDeployed)
        assertEquals(1_230_946_682_139L, m.lifetimeRewardsSol)
        assertEquals(25, m.squaresNow.size)
        assertEquals(100_000_000L, m.deployed.sum())
        // Il suo giro e' vecchio: niente in gioco adesso, e i conti sono gia' chiusi.
        assertEquals(0L, m.inPlay(413544L))
        assertEquals(100_000_000L, m.inPlay(92165L))
        assertFalse(m.needsCheckpoint(413544L))
        assertTrue(m.copy(checkpointId = 92164L).needsCheckpoint(413544L))
        assertEquals("263.4143", Ore.ore(m.rewardsOre))
        assertEquals("0.086903", Ore.sol(m.rewardsSol))
    }

    @Test fun `l'Automation vera, quella di una balena che gioca tutte le caselle`() {
        val a = assertNotNull(Ore.automation(fixture("automation")))
        assertEquals(20_000_000L, a.amountPerSquare)
        assertEquals(191_162_004_000L, a.balance)
        assertEquals(7_000L, a.fee)
        assertEquals(0L, a.strategy)
        assertEquals(25, a.squares)
        assertTrue(a.reload)
        assertEquals(632_000_000_000L, a.totalSolSpent)
        assertEquals(13_890_795_059_028L, a.totalOreEarned)
        assertEquals(-1L, a.maxProductionCost)
        assertEquals(25 * 20_000_000L + 7_000L, a.perRound)
        assertEquals(382, a.roundsLeft)
        assertNull(Ore.automation(fixture("miner")))
    }

    @Test fun `un conto sbagliato non si legge`() {
        assertNull(Ore.miner(fixture("board")))
        assertNull(Ore.board(fixture("miner")))
        assertNull(Ore.round(ByteArray(10)))
        assertNull(Ore.config(fixture("round")))
    }

    // ---- le istruzioni --------------------------------------------------------------

    @Test fun `Deploy si legge e si scrive uguale, e l'ammontare e' per casella`() {
        val data = Ore.deployData(10_000_000L, Ore.maskOf(listOf(0, 6, 24)))
        assertEquals(13, data.size)
        val c = assertNotNull(Ore.decode(data, listOf(me, me, "auto", Ore.BOARD))) as Ore.Call.Deploy
        assertEquals(listOf(0, 6, 24), c.squares)
        assertEquals(10_000_000L, c.amountPerSquare)
        assertEquals(30_000_000L, c.total)
        assertEquals(30_000_000L, Ore.wager(c))
        assertEquals(me, c.authority)
    }

    @Test fun `la maschera va e torna`() {
        assertEquals((0 until 25).toList(), Ore.squaresOf(Ore.maskOf((0 until 25).toList())))
        assertEquals(emptyList(), Ore.squaresOf(0))
        assertEquals(1, Ore.maskOf(listOf(0, 30, -1)))
    }

    @Test fun `Automate, 66 byte, e la maschera a caso e' un numero di caselle`() {
        val data = Ore.automateData(5_000_000L, 300_000_000L, 20_000L, Ore.maskOf(listOf(2, 9, 17)).toLong(), Ore.STRATEGY_PREFERRED, reload = true, maxProductionCost = 500_000_000L)
        assertEquals(66, data.size)
        val c = assertNotNull(Ore.decode(data, listOf(me, "auto", Ore.OPEN_EXECUTOR, "miner"))) as Ore.Call.Automate
        assertEquals(300_000_000L, c.deposit)
        assertEquals(20_000L, c.fee)
        assertEquals(3, c.squares)
        assertTrue(c.reload)
        assertEquals(Ore.OPEN_EXECUTOR, c.executor)
        assertEquals(300_000_000L, Ore.wager(c))
        val pref = Ore.decode(Ore.automateData(1L, 2L, 3L, Ore.maskOf(listOf(1, 2)).toLong(), Ore.STRATEGY_PREFERRED, false), listOf(me, "a", "x", "m")) as Ore.Call.Automate
        assertEquals(2, pref.squares)
    }

    @Test fun `i claim e il checkpoint`() {
        assertEquals(Ore.Call.ClaimSol, Ore.decode(Ore.claimSolData(), emptyList()))
        assertEquals(Ore.Call.ClaimOre(10_000L), Ore.decode(Ore.claimOreData(), emptyList()))
        assertEquals(Ore.Call.ClaimOre(2_500L), Ore.decode(Ore.claimOreData(2_500L), emptyList()))
        assertEquals(Ore.Call.Checkpoint(me), Ore.decode(Ore.checkpointData(), listOf(other, me)))
        assertEquals(Ore.Call.Close, Ore.decode(Ore.closeData(), emptyList()))
        assertEquals(Ore.Call.Other(24), Ore.decode(byteArrayOf(24), emptyList()))
        assertNull(Ore.decode(ByteArray(0), emptyList()))
        assertNull(Ore.decode(byteArrayOf(6, 1, 2), emptyList()))
    }

    // ---- lo scontrino -------------------------------------------------------------

    @Test fun `le frasi dello scontrino, in tutte e due le lingue`() {
        val deploy = Ore.decode(Ore.deployData(10_000_000L, Ore.maskOf(listOf(0, 6, 24))), listOf(me, me))!!
        assertEquals("Metti 0.03 SOL su 3 caselle", Ore.render(deploy, "it").method)
        assertEquals("Put 0.03 SOL on 3 squares", Ore.render(deploy, "en").method)
        assertEquals("1, 7, 25", Ore.render(deploy, "en").args.first { it.first == "squares" }.second)
        assertEquals("ORE", Ore.render(deploy).programName)
        val one = Ore.decode(Ore.deployData(1_000_000L, 1), listOf(me, me))!!
        assertEquals("Put 0.001 SOL on 1 square", Ore.render(one, "en").method)
        val auto = Ore.decode(Ore.automateData(5_000_000L, 300_000_000L, 20_000L, 7L, 1, true), listOf(me, "a", other, "m"))!!
        val r = Ore.render(auto, "it")
        assertEquals("Affida 0.3 SOL a un esecutore", r.method)
        assertTrue(r.args.any { it.first == "esecutore" && it.second.startsWith("39Fe") })
        assertEquals("Riscuoti l'ORE scavato", Ore.render(Ore.Call.ClaimOre(10_000L), "it").method)
        assertEquals(listOf("share" to "25%"), Ore.render(Ore.Call.ClaimOre(2_500L), "en").args)
    }

    @Test fun `il cancello, una scommessa avvisa e pagare le caselle di un altro blocca`() {
        assertTrue(Localization.riskDetail(RiskFlag.WAGER, "it", "0.03").contains("scommessa"))
        assertTrue(Localization.riskDetail(RiskFlag.WAGER_FOR_OTHER, "en", "0.03", "39Fe…QsCs").contains("another wallet"))
    }
}
