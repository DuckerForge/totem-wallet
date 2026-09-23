package com.clearsign.app

import com.clearsign.core.Ore
import org.json.JSONArray
import org.json.JSONObject

/**
 * ORE for one wallet: what is in play, what can be claimed, and the transactions to do it.
 * Reading is two calls, the owner's Miner and the Board, then the Round the Board says is
 * running, all through `SolanaRpc.call`, so the pool and the cap. Config is read once per
 * process: it only provides the entropy address a Deploy wants. The instructions carry the accounts in the program's order, checked with a real simulation on 22 Sep 2026; `Ore` in core makes the bytes.
 */
object OreMiner {
    val PROGRAM: ByteArray = Base58.decode(Ore.PROGRAM)
    val MINT: ByteArray = Base58.decode(Ore.MINT)
    val BOARD: ByteArray = Base58.decode(Ore.BOARD)
    val CONFIG: ByteArray = Base58.decode(Ore.CONFIG)
    val TREASURY: ByteArray = Base58.decode(Ore.TREASURY)
    val OPEN_EXECUTOR: ByteArray = Base58.decode(Ore.OPEN_EXECUTOR)

    fun minerPda(owner: ByteArray): ByteArray = Pda.findProgramAddress(listOf(Ore.SEED_MINER, owner), PROGRAM)!!.first
    fun automationPda(owner: ByteArray): ByteArray = Pda.findProgramAddress(listOf(Ore.SEED_AUTOMATION, owner), PROGRAM)!!.first
    fun roundPda(id: Long): ByteArray = Pda.findProgramAddress(listOf(Ore.SEED_ROUND, le64(id)), PROGRAM)!!.first
    /** The treasury's ORE ATA, where the ORE to claim sits. */
    val treasuryTokens: ByteArray by lazy { Pda.associatedTokenAddress(TREASURY, MINT, WalletTx.TOKEN_PROGRAM) }

    /** What is known about a wallet on ORE, and when it was read. */
    data class View(
        val miner: Ore.Miner?,
        val board: Ore.Board,
        val round: Ore.Round?,
        /** This wallet's automation, if it has a live one. */
        val automation: Ore.Automation? = null,
        /** The answer's slot: the countdown starts from here. */
        val slot: Long,
        val at: Long,
        /** The pot in the Treasury, in ORE with eleven decimals: weighs one five-hundredth in the outlook. */
        val motherlode: Long = 0L,
        /** The round just closed, with its slot hash written: from here you see who won. */
        val lastRound: Ore.Round? = null,
    ) {
        val inPlay: Long get() = miner?.inPlay(board.roundId) ?: 0L
        val claimableSol: Long get() = miner?.rewardsSol ?: 0L
        val claimableOre: Long get() = miner?.claimableOre ?: 0L
        val needsCheckpoint: Boolean get() = miner?.needsCheckpoint(board.roundId) == true
        val hasClaim: Boolean get() = claimableSol > 0 || claimableOre > 0 || needsCheckpoint
        val mySquares: List<Int> get() = if (miner != null && miner.roundId == board.roundId) miner.squaresNow else emptyList()
        /** How much is left of the round, now, without asking the chain anything else. */
        fun secondsLeft(now: Long = System.currentTimeMillis()): Double = (board.secondsLeft(slot) - (now - at) / 1000.0).coerceAtLeast(0.0)
        /** Between rounds nothing can be placed: the Deploy would fail on the node. [margin] is how much must remain. */
        fun open(now: Long = System.currentTimeMillis(), margin: Double = 5.0): Boolean = board.waiting || secondsLeft(now) > margin
    }

    /** Blocking: call on IO. Null when the chain did not answer. */
    fun read(rpcUrl: String, owner: String, withRound: Boolean = true): View? {
        val ownerKey = Base58.decodePubkey(owner) ?: return null
        val minerKey = Base58.encode(minerPda(ownerKey))
        val autoKey = Base58.encode(automationPda(ownerKey))
        val first = multi(rpcUrl, listOf(minerKey, Ore.BOARD, autoKey)) ?: return null
        val board = first.accounts[Ore.BOARD]?.let { Ore.board(it) } ?: return null
        val miner = first.accounts[minerKey]?.let { Ore.miner(it) }
        val automation = first.accounts[autoKey]?.let { Ore.automation(it) }
        // The round is for the grid, not the wallet row: one call less for whoever is not looking at it.
        var motherlode = 0L
        var lastRound: Ore.Round? = null
        val round = if (!withRound) null else {
            val roundKey = Base58.encode(roundPda(board.roundId))
            val prevKey = Base58.encode(roundPda(board.roundId - 1))
            // The previous round and the Treasury travel in one call: the first says
            // who won, the second the pot. Grid only.
            val second = multi(rpcUrl, listOf(roundKey, prevKey, Ore.TREASURY))
            motherlode = second?.accounts?.get(Ore.TREASURY)?.let { Ore.treasuryMotherlode(it) } ?: 0L
            lastRound = second?.accounts?.get(prevKey)?.let { Ore.round(it) }
            second?.accounts?.get(roundKey)?.let { Ore.round(it) }
        }
        return View(miner, board, round, automation, first.slot, System.currentTimeMillis(), motherlode = motherlode, lastRound = lastRound)
    }

    private class Multi(val slot: Long, val accounts: Map<String, ByteArray>)

    private fun multi(rpcUrl: String, keys: List<String>): Multi? {
        val params = JSONArray().put(JSONArray(keys)).put(JSONObject().put("encoding", "base64").put("commitment", "confirmed"))
        val a = SolanaRpc.call(rpcUrl, "getMultipleAccounts", params) as? SolanaRpc.Answer.Answered ?: return null
        val arr = a.json.optJSONObject("result")?.optJSONArray("value") ?: return null
        val out = HashMap<String, ByteArray>()
        for (i in keys.indices) {
            val b64 = arr.optJSONObject(i)?.optJSONArray("data")?.optString(0) ?: continue
            runCatching { java.util.Base64.getMimeDecoder().decode(b64) }.getOrNull()?.let { out[keys[i]] = it }
        }
        return Multi(a.slot ?: 0L, out)
    }

    @Volatile private var config: Ore.Config? = null

    /** The Config, once per process. Null when it could not be read. */
    fun config(rpcUrl: String): Ore.Config? {
        config?.let { return it }
        val got = multi(rpcUrl, listOf(Ore.CONFIG))?.accounts?.get(Ore.CONFIG)?.let { Ore.config(it) } ?: return null
        config = got
        return got
    }

    // ---- le istruzioni ------------------------------------------------------------------

    private fun w(k: ByteArray) = WalletTx.AccountMeta(k, false, true)
    private fun r(k: ByteArray) = WalletTx.AccountMeta(k, false, false)
    private fun signer(k: ByteArray) = WalletTx.AccountMeta(k, true, true)

    /** Put [amountPerSquare] on each of [squares]. The total is times the number of squares. */
    fun deploy(owner: ByteArray, amountPerSquare: Long, squares: Collection<Int>, board: Ore.Board, config: Ore.Config): WalletTx.Instruction {
        val mask = Ore.maskOf(squares)
        return WalletTx.Instruction(
            PROGRAM,
            listOf(
                signer(owner), w(owner), w(automationPda(owner)), w(BOARD), w(CONFIG), w(minerPda(owner)), w(roundPda(board.roundId)), w(TREASURY),
                r(WalletTx.SYSTEM_PROGRAM), r(PROGRAM), w(config.entropyVar), r(config.entropyProgram),
            ),
            Ore.deployData(amountPerSquare, mask),
            lamportsMoved = amountPerSquare * Ore.squaresOf(mask).size,
        )
    }

    /** Bring a finished round's prizes onto the account. Anyone can do it for anyone. */
    fun checkpoint(owner: ByteArray, roundId: Long): WalletTx.Instruction = WalletTx.Instruction(
        PROGRAM,
        listOf(signer(owner), w(owner), w(automationPda(owner)), w(BOARD), w(minerPda(owner)), w(roundPda(roundId)), w(TREASURY), r(WalletTx.SYSTEM_PROGRAM)),
        Ore.checkpointData(),
    )

    fun claimSol(owner: ByteArray): WalletTx.Instruction = WalletTx.Instruction(
        PROGRAM,
        listOf(signer(owner), w(BOARD), w(minerPda(owner)), r(WalletTx.SYSTEM_PROGRAM), r(PROGRAM)),
        Ore.claimSolData(),
    )

    fun claimOre(owner: ByteArray, bps: Long = 10_000L): WalletTx.Instruction = WalletTx.Instruction(
        PROGRAM,
        listOf(
            signer(owner), w(BOARD), w(minerPda(owner)), w(MINT), w(Pda.associatedTokenAddress(owner, MINT, WalletTx.TOKEN_PROGRAM)),
            w(TREASURY), w(treasuryTokens), r(WalletTx.SYSTEM_PROGRAM), r(WalletTx.TOKEN_PROGRAM), r(WalletTx.ATA_PROGRAM), r(PROGRAM),
        ),
        Ore.claimOreData(bps),
    )

    /**
     * Hand to an executor: [amountPerSquare] on each of [squares] every round while the
     * [deposit] lasts, [fee] to the executor per round.
     */
    fun automate(owner: ByteArray, amountPerSquare: Long, squares: Collection<Int>, deposit: Long, fee: Long, reload: Boolean, maxProductionCost: Long, executor: ByteArray = OPEN_EXECUTOR): WalletTx.Instruction =
        WalletTx.Instruction(
            PROGRAM,
            listOf(signer(owner), w(automationPda(owner)), w(executor), w(minerPda(owner)), r(WalletTx.SYSTEM_PROGRAM)),
            // Fixed squares, chosen by us: a round always costs the same and the deposit lasts as said.
            Ore.automateData(amountPerSquare, deposit, fee, Ore.maskOf(squares).toLong(), Ore.STRATEGY_PREFERRED, reload, maxProductionCost),
            lamportsMoved = deposit,
        )

    /**
     * Stop the automation and take back what is left: `Automate` with an empty executor, which
     * the program reads as close the account and return the balance to the signer.
     */
    fun stopAutomation(owner: ByteArray): WalletTx.Instruction = WalletTx.Instruction(
        PROGRAM,
        listOf(signer(owner), w(automationPda(owner)), w(ByteArray(32)), w(minerPda(owner)), r(WalletTx.SYSTEM_PROGRAM)),
        Ore.automateData(0L, 0L, 0L, 0L, Ore.STRATEGY_RANDOM, reload = false),
    )

    /**
     * Claim: one transaction with what is needed and nothing more. First the round to close, then
     * the SOL, then the ORE with its ATA created if missing. Empty when there is nothing to claim.
     */
    fun claimInstructions(owner: ByteArray, view: View): List<WalletTx.Instruction> {
        val m = view.miner ?: return emptyList()
        val out = ArrayList<WalletTx.Instruction>()
        if (view.needsCheckpoint) out += checkpoint(owner, m.roundId)
        if (m.rewardsSol > 0 || view.needsCheckpoint) out += claimSol(owner)
        if (m.claimableOre > 0 || view.needsCheckpoint) {
            out += WalletTx.createAtaIdempotent(owner, Pda.associatedTokenAddress(owner, MINT, WalletTx.TOKEN_PROGRAM), owner, MINT, WalletTx.TOKEN_PROGRAM)
            out += claimOre(owner)
        }
        return out
    }

    private fun le64(v: Long) = ByteArray(8) { ((v shr (8 * it)) and 0xFF).toByte() }
}
