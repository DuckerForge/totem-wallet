package com.clearsign.core

/**
 * ORE, read and written by hand. ORE today is a grid of 25 squares and one round a minute: put
 * SOL on squares, one wins, whoever is on it splits one ORE, the SOL comes back minus fees (see
 * [OreOdds]). The program is Steel, not Anchor: no IDL on chain, so the bytes are written here.
 * Everything in this file was checked on chain on 22 Sep 2026: recomputed PDAs match the fixed
 * accounts, a real Miner reads with these offsets, a Deploy built here passes simulation. The program is `oreV3…`, not the v2 the sites list. Pure: keys as base58 strings or 32 bytes.
 */
object Ore {
    const val PROGRAM = "oreV3EG1i9BEgiAJ8b177Z2S2rMarzak4NMv1kULvWv"
    const val MINT = "oreoU2P8bN6jkk3jbaiVxYnG1dCXcYxwhwyK9jSybcp"
    const val DECIMALS = 11
    const val BOARD = "BrcSxdp1nXFzou1YyDnQJcPNBNHgoypZmTsyKBSLLXzi"
    const val CONFIG = "9c9X7aDRAF41faiDs94ELjT19UrGnn72wBW9hPsS4Awy"
    const val TREASURY = "45db2FSR4mcXdSVVZbKbwojU6uYDpMyhpEi7cC8nHaWG"
    /** The open executor: anyone can play the rounds of whoever picks it, for the fee. */
    const val OPEN_EXECUTOR = "executor11111111111111111111111111111111112"
    const val SQUARES = 25
    /** The `top_miner` of a closed round whose prize is split: `SpLiT111…112`. */
    const val SPLIT_ADDRESS = "SpLiT11111111111111111111111111111111111112"
    /** The same 32 bytes, decoded once: `core` has no Base58. */
    private val SPLIT_BYTES: ByteArray = byteArrayOf(6, -99, 12, 49, -121, 89, -79, -26, 115, 111, 41, -98, 119, 77, -2, -3, 56, 29, 124, 92, -40, 81, 47, 16, -56, -111, -114, 0, 0, 0, 0, 1)
    const val CHECKPOINT_FEE_LAMPORTS = 10_000L
    /**
     * One slot, roughly, for the countdown only. Nominal is 400 ms; measured 22 Sep 2026 on the
     * network, 46 slots in twelve seconds, 260 ms. At 400 the countdown ran at half speed and said "six seconds" after the round had ended.
     */
    const val SLOT_MS = 260L
    const val ONE_ORE = 100_000_000_000L
    /**
     * The ORE a round pays. Minted at round end, `min(MAX_SUPPLY - supply, 1 ORE)`, written in
     * `rewards[0]` of the closed round; on the live round `rewards` is all zero. With half a
     * million ORE out of a three million cap (`ore_mint_api::MAX_SUPPLY`), it is one ORE.
     */
    const val ROUND_REWARD = ONE_ORE

    /** The PDA seeds, as bytes. */
    val SEED_MINER = "miner".toByteArray()
    val SEED_AUTOMATION = "automation".toByteArray()
    val SEED_ROUND = "round".toByteArray()

    // The first byte of every instruction.
    const val IX_AUTOMATE = 0
    const val IX_CHECKPOINT = 2
    const val IX_CLAIM_SOL = 3
    const val IX_CLAIM_ORE = 4
    const val IX_CLOSE = 5
    const val IX_DEPLOY = 6

    // The first byte of every account. Steel puts eight before the body.
    const val HEADER = 8
    const val ACC_AUTOMATION = 100
    const val ACC_CONFIG = 101
    const val ACC_MINER = 103
    const val ACC_BOARD = 105
    const val ACC_ROUND = 109

    // ---- le istruzioni, lette --------------------------------------------------

    /** A call to the program, in the words a receipt needs. */
    sealed interface Call {
        /** [amountPerSquare] goes on every chosen square. [authority] pays for the squares: account 1. */
        data class Deploy(val amountPerSquare: Long, val squares: List<Int>, val authority: String?) : Call {
            val total: Long get() = amountPerSquare * squares.size
        }
        data class Checkpoint(val authority: String?) : Call
        object ClaimSol : Call
        /** [bps] su 10000: quanta parte dell'ORE si riscuote. */
        data class ClaimOre(val bps: Long) : Call
        /** [executor] is account 2, who will play the rounds. [deposit] is what is handed over. */
        data class Automate(
            val amountPerSquare: Long, val deposit: Long, val fee: Long, val mask: Long,
            val strategy: Int, val reload: Boolean, val executor: String?,
        ) : Call {
            /** The squares in the mask. On chain even random automations carry a square mask. */
            val squares: Int get() = squaresOf(mask.toInt()).size
        }
        object Close : Call
        data class Other(val discriminator: Int) : Call
    }

    const val STRATEGY_RANDOM = 0
    const val STRATEGY_PREFERRED = 1

    /** An instruction's bytes and its accounts, in the program's order. Null if not ORE. */
    fun decode(data: ByteArray, accounts: List<String>): Call? {
        if (data.isEmpty()) return null
        return when (data[0].toInt() and 0xFF) {
            IX_DEPLOY -> if (data.size >= 13) Call.Deploy(le64(data, 1), squaresOf(le32(data, 9)), accounts.getOrNull(1)) else null
            IX_CHECKPOINT -> Call.Checkpoint(accounts.getOrNull(1))
            IX_CLAIM_SOL -> Call.ClaimSol
            IX_CLAIM_ORE -> Call.ClaimOre(if (data.size >= 9) le64(data, 1) else 10_000L)
            IX_AUTOMATE -> if (data.size >= 42) Call.Automate(
                amountPerSquare = le64(data, 1), deposit = le64(data, 9), fee = le64(data, 17), mask = le64(data, 25),
                strategy = data[33].toInt() and 0xFF, reload = le64(data, 34) != 0L, executor = accounts.getOrNull(2),
            ) else null
            IX_CLOSE -> Call.Close
            else -> Call.Other(data[0].toInt() and 0xFF)
        }
    }

    /** What it puts in play, in lamports: a Deploy's squares, an Automate's deposit. */
    fun wager(call: Call): Long = when (call) {
        is Call.Deploy -> call.total
        is Call.Automate -> call.deposit
        else -> 0L
    }

    /** The squares lit in a 25-bit mask, 0 to 24. */
    fun squaresOf(mask: Int): List<Int> = (0 until SQUARES).filter { (mask shr it) and 1 == 1 }

    fun maskOf(squares: Collection<Int>): Int = squares.fold(0) { m, s -> if (s in 0 until SQUARES) m or (1 shl s) else m }

    /** The receipt line: the method is the sentence, the arguments the numbers, the same shape `AnchorIdl` produces for programs with an IDL. */
    fun render(call: Call, locale: String = "en"): ProgramCall {
        val it = locale == "it"
        val (method, args) = when (call) {
            is Call.Deploy -> (if (it) "Metti ${sol(call.total)} SOL su ${call.squares.size} ${if (call.squares.size == 1) "casella" else "caselle"}" else "Put ${sol(call.total)} SOL on ${call.squares.size} ${if (call.squares.size == 1) "square" else "squares"}") to listOf(
                (if (it) "per casella" else "per square") to sol(call.amountPerSquare) + " SOL",
                (if (it) "caselle" else "squares") to call.squares.joinToString(", ") { s -> (s + 1).toString() },
            )
            is Call.Checkpoint -> (if (it) "Chiudi i conti del giro" else "Settle the round") to emptyList()
            Call.ClaimSol -> (if (it) "Riscuoti il SOL vinto" else "Claim the SOL won") to emptyList()
            is Call.ClaimOre -> (if (it) "Riscuoti l'ORE scavato" else "Claim the ORE mined") to
                (if (call.bps in 1 until 10_000) listOf((if (it) "parte" else "share") to "${call.bps / 100}%") else emptyList())
            is Call.Automate -> (if (it) "Affida ${sol(call.deposit)} SOL a un esecutore" else "Hand ${sol(call.deposit)} SOL to an executor") to listOf(
                (if (it) "a giro" else "per round") to "${sol(call.amountPerSquare)} SOL × ${call.squares}",
                (if (it) "fee a giro" else "fee per round") to sol(call.fee) + " SOL",
                (if (it) "rigioca le vincite" else "replays winnings") to (if (call.reload) (if (it) "sì" else "yes") else "no"),
            ) + (if (call.executor != null && call.executor != OPEN_EXECUTOR) listOf((if (it) "esecutore" else "executor") to short(call.executor)) else emptyList())
            Call.Close -> (if (it) "Chiudi un giro finito" else "Close a finished round") to emptyList()
            is Call.Other -> (if (it) "Chiamata a ORE" else "Call to ORE") to listOf((if (it) "istruzione" else "instruction") to call.discriminator.toString())
        }
        return ProgramCall(PROGRAM, "ORE", method, args)
    }

    // ---- le istruzioni, scritte -----------------------------------------------

    fun deployData(amountPerSquare: Long, mask: Int): ByteArray = byteArrayOf(IX_DEPLOY.toByte()) + le64(amountPerSquare) + le32(mask)
    fun checkpointData(): ByteArray = byteArrayOf(IX_CHECKPOINT.toByte())
    fun claimSolData(): ByteArray = byteArrayOf(IX_CLAIM_SOL.toByte())
    fun claimOreData(bps: Long = 10_000L): ByteArray = byteArrayOf(IX_CLAIM_ORE.toByte()) + le64(bps)
    fun closeData(): ByteArray = byteArrayOf(IX_CLOSE.toByte())

    /**
     * Automate in its 66-byte form with conditions; the program tells it apart by length.
     * [maxProductionCost] in lamports per ORE, zero for no limit. Square preferences only matter with the random strategy.
     */
    fun automateData(
        amountPerSquare: Long, deposit: Long, fee: Long, mask: Long, strategy: Int, reload: Boolean,
        maxProductionCost: Long = 0L, minMotherlode: Int = 0, maxMotherlode: Int = 0, splitTiles: Int = 0, soloTiles: Int = 0,
    ): ByteArray = byteArrayOf(IX_AUTOMATE.toByte()) + le64(amountPerSquare) + le64(deposit) + le64(fee) + le64(mask) +
        byteArrayOf(strategy.toByte()) + le64(if (reload) 1L else 0L) +
        le64(maxProductionCost) + le16(minMotherlode) + le16(maxMotherlode) + le16(splitTiles) + le16(soloTiles) + ByteArray(8)

    // ---- i conti -----------------------------------------------------------------

    data class Board(val roundId: Long, val startSlot: Long, val endSlot: Long, val productionCostEma: Long) {
        /** How much is left of the round, from the current slot. Zero when over. */
        fun secondsLeft(slot: Long): Double = ((endSlot - slot) * SLOT_MS / 1000.0).coerceAtLeast(0.0)
        /** The round has not started yet: the first Deploy opens it. */
        val waiting: Boolean get() = endSlot == -1L
    }

    fun board(bytes: ByteArray): Board? {
        if (bytes.size < HEADER + 32 || (bytes[0].toInt() and 0xFF) != ACC_BOARD) return null
        val b = HEADER
        return Board(le64(bytes, b), le64(bytes, b + 8), le64(bytes, b + 16), le64(bytes, b + 24))
    }

    data class Config(val intermissionSlots: Long, val roundSlots: Long, val entropyVar: ByteArray, val entropyProgram: ByteArray)

    fun config(bytes: ByteArray): Config? {
        if (bytes.size < HEADER + 224 || (bytes[0].toInt() and 0xFF) != ACC_CONFIG) return null
        val b = HEADER
        return Config(le64(bytes, b + 144), le64(bytes, b + 152), bytes.copyOfRange(b + 160, b + 192), bytes.copyOfRange(b + 192, b + 224))
    }

    data class Round(
        val id: Long, val deployed: LongArray, val count: LongArray, val totalMiners: Long, val topMiner: ByteArray,
        /** The ORE minted for this round, written when it closes: zero while it runs. See [ROUND_REWARD]. */
        val rewards: LongArray = LongArray(SQUARES),
        /** The pot, if this round took it: almost always zero. */
        val motherlode: Long = 0L,
        /** Written at round end: the winning square comes out of it. All zeros while the round runs. */
        val slotHash: ByteArray = ByteArray(32),
        val expiresAt: Long = 0L,
    ) {
        val totalDeployed: Long get() = deployed.sum()
        val rewardOre: Long get() = rewards.sum()
        /** The prize to expect: the written one if the round is closed, otherwise the ORE the program will mint. */
        val expectedReward: Long get() = rewardOre.takeIf { it > 0 } ?: ROUND_REWARD
        /** The ten squares of this round that pay one miner only: known beforehand, from the id. */
        val soloMask: Int get() = distributionMask(id)
        fun isSolo(square: Int): Boolean = soloMask and (1 shl square) != 0
        /** Il giro e' chiuso e diviso pro quota: `top_miner` e' l'indirizzo SPLIT. */
        val isSplit: Boolean get() = topMiner.contentEquals(SPLIT_BYTES)
        /** The winning square, if the round is closed: `(r1 ^ r2 ^ r3 ^ r4) % 25` over the slot hash's four u64, as `Round::winning_square`. */
        val winningSquare: Int? get() {
            if (slotHash.all { it == 0.toByte() } || slotHash.all { it == 0xFF.toByte() }) return null
            val r = le64(slotHash, 0) xor le64(slotHash, 8) xor le64(slotHash, 16) xor le64(slotHash, 24)
            return java.lang.Long.remainderUnsigned(r, SQUARES.toLong()).toInt()
        }
    }

    fun round(bytes: ByteArray): Round? {
        if (bytes.size < HEADER + 944 || (bytes[0].toInt() and 0xFF) != ACC_ROUND) return null
        val b = HEADER
        // id 8, deployed 200, mass 200, count 200, slot_hash 32, expires_at 8, motherlode 8,
        // rent_payer 32, rewards 200, total_vaulted 8, total_returned_sol 8, total_miners 8, top_miner 32.
        return Round(
            id = le64(bytes, b),
            deployed = LongArray(SQUARES) { le64(bytes, b + 8 + 8 * it) },
            count = LongArray(SQUARES) { le64(bytes, b + 408 + 8 * it) },
            totalMiners = le64(bytes, b + 904),
            topMiner = bytes.copyOfRange(b + 912, b + 944),
            rewards = LongArray(SQUARES) { le64(bytes, b + 688 + 8 * it) },
            motherlode = le64(bytes, b + 648),
            slotHash = bytes.copyOfRange(b + 608, b + 640),
            expiresAt = le64(bytes, b + 640),
        )
    }

    /**
     * `Round::distribution_mask`, as the program does it: a keccak of the round id shuffles the
     * twenty-five squares Fisher-Yates, two bytes per step, rehashing when the bytes run out; the
     * first ten of the shuffle pay one miner only. Checked on thirty real rounds.
     */
    fun distributionMask(id: Long): Int {
        var randomness = Keccak.hash256(ByteArray(8) { ((id ushr (8 * it)) and 0xFF).toByte() })
        var off = 0
        val idx = IntArray(SQUARES) { it }
        for (i in SQUARES - 1 downTo 1) {
            if (off + 2 > randomness.size) { randomness = Keccak.hash256(randomness); off = 0 }
            val r = (randomness[off].toInt() and 0xFF) or ((randomness[off + 1].toInt() and 0xFF) shl 8)
            val j = r % (i + 1)
            val t = idx[i]; idx[i] = idx[j]; idx[j] = t
            off += 2
        }
        var mask = 0
        for (k in 0 until 10) mask = mask or (1 shl idx[k])
        return mask
    }

    /** The Treasury's pot: the first u64 of the body. Null if the bytes are not a Treasury. */
    fun treasuryMotherlode(bytes: ByteArray): Long? = if (bytes.size < HEADER + 8) null else le64(bytes, HEADER)

    /** A miner's account: 744 bytes of body after the header. */
    data class Miner(
        val authority: ByteArray,
        val checkpointId: Long,
        val deployed: LongArray,
        val roundId: Long,
        val rewardsSol: Long,
        val refinedOre: Long,
        val rewardsOre: Long,
        val lifetimeRewardsOre: Long,
        val lifetimeDeployed: Long,
        val lifetimeRewardsSol: Long,
    ) {
        /** The SOL on the squares now: valid only if the account's round is the current one. */
        fun inPlay(boardRoundId: Long): Long = if (roundId == boardRoundId) deployed.sum() else 0L
        /** A finished round whose prizes have not been brought onto the account yet. */
        fun needsCheckpoint(boardRoundId: Long): Boolean = roundId < boardRoundId && checkpointId < roundId
        val claimableOre: Long get() = rewardsOre + refinedOre
        val squaresNow: List<Int> get() = deployed.indices.filter { deployed[it] > 0 }
    }

    /** An automation's account: 152 bytes of body. Read on chain on 22 Sep 2026. */
    data class Automation(
        val amountPerSquare: Long,
        val authority: ByteArray,
        val balance: Long,
        val executor: ByteArray,
        val fee: Long,
        val strategy: Long,
        val mask: Long,
        val reload: Boolean,
        val totalSolSpent: Long,
        val totalOreEarned: Long,
        val maxProductionCost: Long,
    ) {
        val squares: Int get() = squaresOf(mask.toInt()).size
        /** What a round costs: the squares plus the executor's fee. */
        val perRound: Long get() = amountPerSquare * squares + fee
        val roundsLeft: Int get() = if (perRound <= 0) 0 else (balance / perRound).toInt()
    }

    fun automation(bytes: ByteArray): Automation? {
        if (bytes.size < HEADER + 152 || (bytes[0].toInt() and 0xFF) != ACC_AUTOMATION) return null
        val b = HEADER
        return Automation(
            amountPerSquare = le64(bytes, b), authority = bytes.copyOfRange(b + 8, b + 40), balance = le64(bytes, b + 40),
            executor = bytes.copyOfRange(b + 48, b + 80), fee = le64(bytes, b + 80), strategy = le64(bytes, b + 88), mask = le64(bytes, b + 96),
            reload = le64(bytes, b + 104) != 0L, totalSolSpent = le64(bytes, b + 112), totalOreEarned = le64(bytes, b + 120),
            maxProductionCost = le64(bytes, b + 128),
        )
    }

    fun miner(bytes: ByteArray): Miner? {
        if (bytes.size < HEADER + 744 || (bytes[0].toInt() and 0xFF) != ACC_MINER) return null
        val b = HEADER
        return Miner(
            authority = bytes.copyOfRange(b, b + 32),
            checkpointId = le64(bytes, b + 40),
            deployed = LongArray(SQUARES) { le64(bytes, b + 56 + 8 * it) },
            roundId = le64(bytes, b + 656),
            rewardsSol = le64(bytes, b + 680),
            refinedOre = le64(bytes, b + 688),
            rewardsOre = le64(bytes, b + 696),
            lifetimeRewardsOre = le64(bytes, b + 720),
            lifetimeDeployed = le64(bytes, b + 728),
            lifetimeRewardsSol = le64(bytes, b + 736),
        )
    }

    // ---- numeri ----------------------------------------------------------------------

    /** Lamports as SOL, with the digits needed and no trailing zeros. */
    fun sol(lamports: Long): String {
        val v = lamports / 1e9
        val s = String.format(java.util.Locale.ROOT, if (v >= 1) "%.4f" else "%.6f", v)
        return s.trimEnd('0').trimEnd('.')
    }

    /** Grammi in ORE, allo stesso modo. */
    fun ore(raw: Long): String {
        val v = raw / 1e11
        val s = String.format(java.util.Locale.ROOT, if (v >= 1) "%.4f" else "%.6f", v)
        return s.trimEnd('0').trimEnd('.')
    }

    private fun short(a: String) = if (a.length > 10) a.take(4) + "…" + a.takeLast(4) else a

    private fun le64(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[at + i].toLong() and 0xFF) shl (8 * i))
        return v
    }
    private fun le32(b: ByteArray, at: Int): Int {
        var v = 0
        for (i in 0 until 4) v = v or ((b[at + i].toInt() and 0xFF) shl (8 * i))
        return v
    }
    private fun le64(v: Long) = ByteArray(8) { ((v shr (8 * it)) and 0xFF).toByte() }
    private fun le32(v: Int) = ByteArray(4) { ((v shr (8 * it)) and 0xFF).toByte() }
    private fun le16(v: Int) = ByteArray(2) { ((v shr (8 * it)) and 0xFF).toByte() }
}
