package com.clearsign.app

import com.clearsign.core.Ore
import org.json.JSONArray
import org.json.JSONObject

/**
 * ORE per un portafoglio: cosa ha in gioco, cosa puo' riscuotere, e le
 * transazioni per farlo.
 *
 * La lettura e' due chiamate: prima il conto Miner del proprietario e la
 * Board, poi il Round che la Board dice in corso. Tutto passa da
 * `SolanaRpc.call`, quindi dal pool e dal tetto. La Config si legge una volta
 * per processo: serve solo per l'indirizzo dell'entropia che un Deploy vuole
 * fra i conti, e non cambia.
 *
 * Le istruzioni sono scritte con gli stessi conti nello stesso ordine del
 * programma, controllati con una simulazione vera il 22 settembre 2026. I
 * byte li fa `Ore` in core, qui si mettono solo i conti davanti.
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
    /** L'ATA del tesoro per ORE, dove sta l'ORE che si riscuote. */
    val treasuryTokens: ByteArray by lazy { Pda.associatedTokenAddress(TREASURY, MINT, WalletTx.TOKEN_PROGRAM) }

    /** Quello che si sa di un portafoglio su ORE, e quando lo si e' letto. */
    data class View(
        val miner: Ore.Miner?,
        val board: Ore.Board,
        val round: Ore.Round?,
        /** L'automazione di questo portafoglio, se ne ha una viva. */
        val automation: Ore.Automation? = null,
        /** Lo slot della risposta: il conto alla rovescia parte da qui. */
        val slot: Long,
        val at: Long,
    ) {
        val inPlay: Long get() = miner?.inPlay(board.roundId) ?: 0L
        val claimableSol: Long get() = miner?.rewardsSol ?: 0L
        val claimableOre: Long get() = miner?.claimableOre ?: 0L
        val needsCheckpoint: Boolean get() = miner?.needsCheckpoint(board.roundId) == true
        val hasClaim: Boolean get() = claimableSol > 0 || claimableOre > 0 || needsCheckpoint
        val mySquares: List<Int> get() = if (miner != null && miner.roundId == board.roundId) miner.squaresNow else emptyList()
        /** Quanto manca alla fine del giro, adesso, senza chiedere altro alla catena. */
        fun secondsLeft(now: Long = System.currentTimeMillis()): Double = (board.secondsLeft(slot) - (now - at) / 1000.0).coerceAtLeast(0.0)
        /** Fra un giro e l'altro non si puo' mettere niente: il Deploy fallirebbe sul nodo. [margin] e' quanto deve restare. */
        fun open(now: Long = System.currentTimeMillis(), margin: Double = 5.0): Boolean = board.waiting || secondsLeft(now) > margin
    }

    /** Bloccante: chiamare su IO. Null quando la catena non ha risposto. */
    fun read(rpcUrl: String, owner: String, withRound: Boolean = true): View? {
        val ownerKey = Base58.decodePubkey(owner) ?: return null
        val minerKey = Base58.encode(minerPda(ownerKey))
        val autoKey = Base58.encode(automationPda(ownerKey))
        val first = multi(rpcUrl, listOf(minerKey, Ore.BOARD, autoKey)) ?: return null
        val board = first.accounts[Ore.BOARD]?.let { Ore.board(it) } ?: return null
        val miner = first.accounts[minerKey]?.let { Ore.miner(it) }
        val automation = first.accounts[autoKey]?.let { Ore.automation(it) }
        // Il giro serve alla griglia, non alla riga del portafoglio: una chiamata in meno a chi non lo guarda.
        val round = if (!withRound) null else {
            val roundKey = Base58.encode(roundPda(board.roundId))
            multi(rpcUrl, listOf(roundKey))?.accounts?.get(roundKey)?.let { Ore.round(it) }
        }
        return View(miner, board, round, automation, first.slot, System.currentTimeMillis())
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

    /** La Config, una volta per processo. Null quando non si e' potuta leggere. */
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

    /** Metti [amountPerSquare] su ognuna delle [squares]. Il totale e' per il numero di caselle. */
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

    /** Porta sul conto i premi di un giro finito. Chiunque puo' farlo per chiunque. */
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
     * Affida a un esecutore: [amountPerSquare] su ognuna delle [squares] a
     * ogni giro, finche' il [deposit] dura, [fee] all'esecutore per giro.
     */
    fun automate(owner: ByteArray, amountPerSquare: Long, squares: Collection<Int>, deposit: Long, fee: Long, reload: Boolean, maxProductionCost: Long, executor: ByteArray = OPEN_EXECUTOR): WalletTx.Instruction =
        WalletTx.Instruction(
            PROGRAM,
            listOf(signer(owner), w(automationPda(owner)), w(executor), w(minerPda(owner)), r(WalletTx.SYSTEM_PROGRAM)),
            // Caselle fisse, scelte da noi: cosi' un giro costa sempre lo stesso e il deposito dura quanto detto.
            Ore.automateData(amountPerSquare, deposit, fee, Ore.maskOf(squares).toLong(), Ore.STRATEGY_PREFERRED, reload, maxProductionCost),
            lamportsMoved = deposit,
        )

    /**
     * Ferma l'automazione e riprendi quello che resta: e' `Automate` con
     * l'esecutore vuoto, che nel programma vuol dire chiudere il conto e
     * restituire il saldo a chi firma.
     */
    fun stopAutomation(owner: ByteArray): WalletTx.Instruction = WalletTx.Instruction(
        PROGRAM,
        listOf(signer(owner), w(automationPda(owner)), w(ByteArray(32)), w(minerPda(owner)), r(WalletTx.SYSTEM_PROGRAM)),
        Ore.automateData(0L, 0L, 0L, 0L, Ore.STRATEGY_RANDOM, reload = false),
    )

    /**
     * Riscuoti: una transazione sola con quello che serve e niente di piu'.
     * Prima il giro da chiudere, poi il SOL, poi l'ORE con l'ATA creata se
     * manca. Vuota quando non c'e' niente da riscuotere.
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
