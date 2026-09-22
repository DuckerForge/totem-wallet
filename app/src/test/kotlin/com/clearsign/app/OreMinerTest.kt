package com.clearsign.app

import com.clearsign.core.Ore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Le transazioni di ORE: i conti giusti nell'ordine giusto, e Riscuoti che
 * mette dentro quello che serve e niente di piu'. L'ordine dei conti e' quello
 * che ha passato la simulazione sulla catena il 22 settembre 2026.
 */
class OreMinerTest {
    private val owner = Base58.decode("D1nsSnCRwcmjweKNZAfHtApfPBskuALHD7dEKhybzNkj")
    private val board = Ore.Board(413544L, 449318168L, 449318368L, 457905902L)
    private val config = Ore.Config(40L, 200L, ByteArray(32) { 7 }, ByteArray(32))

    private fun miner(roundId: Long, checkpointId: Long, sol: Long, ore: Long) = Ore.Miner(
        authority = owner, checkpointId = checkpointId, deployed = LongArray(25), roundId = roundId,
        rewardsSol = sol, refinedOre = 0L, rewardsOre = ore, lifetimeRewardsOre = 0L, lifetimeDeployed = 0L, lifetimeRewardsSol = 0L,
    )

    @Test fun `le PDA sono quelle della catena`() {
        assertEquals("3tNmgFN7QCEiYz9bSaA4uQ39FvosHr8FQFx5JsZqcH9G", Base58.encode(OreMiner.minerPda(owner)))
        assertEquals("3EEyetBeWgaGhsEc8JR77w48Vt2Yac7kVJNzrz87oy3S", Base58.encode(OreMiner.roundPda(413544L)))
    }

    @Test fun `Deploy ha dodici conti nell'ordine simulato`() {
        val ix = OreMiner.deploy(owner, 1_000_000L, listOf(0), board, config)
        assertEquals(12, ix.keys.size)
        val names = ix.keys.map { Base58.encode(it.pubkey) }
        assertEquals(Base58.encode(owner), names[0]); assertTrue(ix.keys[0].isSigner)
        assertEquals(Base58.encode(owner), names[1])
        assertEquals(Base58.encode(OreMiner.automationPda(owner)), names[2])
        assertEquals(Ore.BOARD, names[3]); assertEquals(Ore.CONFIG, names[4])
        assertEquals("3tNmgFN7QCEiYz9bSaA4uQ39FvosHr8FQFx5JsZqcH9G", names[5])
        assertEquals("3EEyetBeWgaGhsEc8JR77w48Vt2Yac7kVJNzrz87oy3S", names[6])
        assertEquals(Ore.TREASURY, names[7])
        assertEquals("11111111111111111111111111111111", names[8])
        assertEquals(Ore.PROGRAM, names[9])
        assertEquals(Base58.encode(config.entropyVar), names[10]); assertTrue(ix.keys[10].isWritable)
        assertEquals("11111111111111111111111111111111", names[11])
        assertEquals(13, ix.data.size)
        assertEquals(1_000_000L, ix.lamportsMoved)
        assertEquals(3_000_000L, OreMiner.deploy(owner, 1_000_000L, listOf(1, 2, 3), board, config).lamportsMoved)
    }

    @Test fun `Riscuoti mette solo quello che serve`() {
        val none = OreMiner.View(miner(413544L, 413543L, 0L, 0L), board, null, slot = 0L, at = 0L)
        assertTrue(OreMiner.claimInstructions(owner, none).isEmpty())
        assertTrue(OreMiner.claimInstructions(owner, OreMiner.View(null, board, null, slot = 0L, at = 0L)).isEmpty())

        val solOnly = OreMiner.View(miner(413544L, 413543L, 5_000L, 0L), board, null, slot = 0L, at = 0L)
        assertEquals(listOf(Ore.IX_CLAIM_SOL), OreMiner.claimInstructions(owner, solOnly).map { it.data[0].toInt() })

        val oreOnly = OreMiner.View(miner(413544L, 413543L, 0L, 7L), board, null, slot = 0L, at = 0L)
        val ixs = OreMiner.claimInstructions(owner, oreOnly)
        assertEquals(2, ixs.size)
        assertEquals(Base58.encode(WalletTx.ATA_PROGRAM), Base58.encode(ixs[0].programId))
        assertEquals(Ore.IX_CLAIM_ORE, ixs[1].data[0].toInt())
        assertEquals(11, ixs[1].keys.size)

        // Un giro vecchio non chiuso: prima si chiude, poi si riscuote tutto.
        val stale = OreMiner.View(miner(413540L, 413539L, 0L, 0L), board, null, slot = 0L, at = 0L)
        val all = OreMiner.claimInstructions(owner, stale)
        assertEquals(listOf(Ore.IX_CHECKPOINT, Ore.IX_CLAIM_SOL, 1, Ore.IX_CLAIM_ORE), all.map { it.data[0].toInt() })
        assertEquals(Base58.encode(OreMiner.roundPda(413540L)), Base58.encode(all[0].keys[5].pubkey))
    }

    @Test fun `Automate affida all'esecutore aperto`() {
        val ix = OreMiner.automate(owner, 1_000_000L, listOf(1, 5, 9), 300_000_000L, 20_000L, reload = true, maxProductionCost = 500_000_000L)
        assertEquals(5, ix.keys.size)
        assertEquals(Ore.OPEN_EXECUTOR, Base58.encode(ix.keys[2].pubkey))
        assertEquals(66, ix.data.size)
        assertEquals(300_000_000L, ix.lamportsMoved)
        val stop = OreMiner.stopAutomation(owner)
        assertEquals("11111111111111111111111111111111", Base58.encode(stop.keys[2].pubkey))
        assertEquals(66, stop.data.size)
    }

    @Test fun `il conto alla rovescia scende senza chiedere niente`() {
        val v = OreMiner.View(null, board, null, slot = 449318204L, at = 1_000_000L)
        assertEquals(65.6, v.secondsLeft(1_000_000L), 0.01)
        assertEquals(55.6, v.secondsLeft(1_010_000L), 0.01)
        assertTrue(v.open(1_000_000L))
        assertTrue(!v.open(1_061_000L))
    }
}
