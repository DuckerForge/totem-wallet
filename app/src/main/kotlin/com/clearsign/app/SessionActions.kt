package com.clearsign.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The two chain operations the envelope needs: filling it, and taking it back.
 *
 * Filling it is a normal transfer out of your Seed Vault account, so it goes
 * through the usual receipt and one biometric approval. Taking it back is signed
 * by the envelope key itself, which this app holds, so it needs no biometrics —
 * the point of the button is that ending the agent's power must be instant.
 */
object SessionActions {

    /** Move [lamports] from the main account into the envelope. Returns an error, or null. */
    suspend fun fund(ctx: Context, signer: SeedVaultSigner, owner: String, session: String, lamports: Long): String? {
        val ownerKey = Base58.decodePubkey(owner) ?: return ctx.getString(R.string.wa_bad_address)
        val sessionKey = Base58.decodePubkey(session) ?: return ctx.getString(R.string.wa_bad_address)
        val ix = listOf(WalletTx.systemTransfer(ownerKey, sessionKey, lamports))
        val log = WalletActions.LogInfo(
            kind = "envelope",
            outflows = listOf("−" + fmtSol(lamports, 5) + " SOL"),
            recipient = session,
            recipientLabel = ctx.getString(R.string.env_log_fund),
        )
        return when (val r = WalletActions.signAndSend(ctx, signer, owner, ix, log)) {
            is WalletActions.Result.Sent -> null
            is WalletActions.Result.Failed -> r.message
        }
    }

    /**
     * The coins the budget is holding, not counting dust.
     *
     * These are invisible everywhere else in the app: the wallet screen shows
     * the Seed Vault account, and the agent buys with a different key. Closing
     * the budget used to move only the SOL and erase that key, which left real
     * tokens in a wallet nobody could ever open again.
     */
    suspend fun holdings(ctx: Context): List<SolanaRpc.TokenAccountInfo> {
        val s = SessionWallet.current(ctx) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching { SolanaRpc.tokenAccountsOf(SolanaRpc.urlFor(null), s.pubkey, force = true) }
                .getOrDefault(emptyList())
                .filter { it.amount > 0 }
        }
    }

    /**
     * What the budget really holds, raw units per mint, straight from the chain.
     *
     * The loop needs this before it believes its own book, and the panel needs it
     * before it offers a button that sells something.
     */
    suspend fun heldRaw(ctx: Context, owner: String): Map<String, Long>? = withContext(Dispatchers.IO) {
        // Null when the chain did not answer, never an empty map: the loop treats
        // an empty map as "the coins are gone", and a rate-limited node is not that.
        runCatching { SolanaRpc.tokensOf(SolanaRpc.urlFor(null), owner) }
            .getOrNull()
            ?.filter { it.amount > 0 }
            ?.associate { it.mint to it.amount }
    }

    /**
     * Sell one holding back to SOL, now, and let the collar judge it.
     *
     * The one door out, used by the loop on a target or a stop and by the button
     * on the position. Both build the transaction **here, at the moment of the
     * decision**: a swap carries a blockhash that dies in about ninety seconds,
     * so a transaction prepared earlier and approved later is a transaction that
     * fails on send.
     *
     * The amount comes from the chain and never from the book. What comes back
     * says which of four things happened, because they used to all be null and
     * the loop counted every one of them as a sale that failed: three blinks of
     * the network in a row pushed a live stop-loss into the six-hour lane.
     */
    sealed class Sale {
        /** The chain says the budget holds none of this coin. The book is wrong, not the sale. */
        object Nothing : Sale()
        /** The node did not answer. Try again, count nothing. */
        object Unreachable : Sale()
        /** Jupiter gave no quote or no transaction for it. A real problem with this coin, worth counting. */
        object NoRoute : Sale()
        class Judged(val verdict: AgentBroker.Verdict) : Sale()
    }

    suspend fun sellNow(
        ctx: Context,
        pos: Positions.Position,
        reason: String,
        source: AgentBroker.Job.Source,
    ): Sale {
        val s = SessionWallet.current(ctx) ?: return Sale.Nothing
        val held = heldRaw(ctx, s.pubkey) ?: return Sale.Unreachable
        val raw = held[pos.mint] ?: return Sale.Nothing
        if (raw <= 0L) return Sale.Nothing
        // Wider slippage on the way out than on the way in: a stop that does not
        // fill because the price moved while we asked is not a stop at all.
        val quote = withContext(Dispatchers.IO) {
            runCatching { Jupiter.quote(pos.mint, Jupiter.SOL_MINT, raw, slippageBps = 300, feeBps = 0) }.getOrNull()
        } ?: return Sale.NoRoute
        val tx = withContext(Dispatchers.IO) {
            runCatching { Jupiter.swapTransaction(quote, s.pubkey, null) }.getOrNull()
        } ?: return Sale.NoRoute
        val units = raw / Math.pow(10.0, pos.decimals.toDouble())
        val intent = JSONObject()
            .put("action", "swap").put("outMint", pos.symbol).put("outAmount", units)
            .put("inMint", "SOL").put("inAmount", quote.outAmount / 1e9)
            .put("agent", TraderLoop.AGENT).put("reason", reason)
        return Sale.Judged(
            AgentBroker.handle(
                ctx,
                AgentBroker.Job(
                    id = LedgerRecorder.newId(), tx = tx, intentJson = intent.toString(),
                    cluster = null, agent = TraderLoop.AGENT, source = source,
                ),
            ),
        )
    }

    /** What the whole holding would fetch in lamports right now, or null. */
    suspend fun quoteValue(ctx: Context, pos: Positions.Position): Long? {
        val s = SessionWallet.current(ctx) ?: return null
        val raw = heldRaw(ctx, s.pubkey)?.get(pos.mint) ?: return null
        if (raw <= 0L) return null
        return withContext(Dispatchers.IO) {
            runCatching { Jupiter.quote(pos.mint, Jupiter.SOL_MINT, raw, feeBps = 0) }.getOrNull()?.outAmount
        }
    }

    /**
     * Sell everything the budget holds back to SOL, one coin at a time.
     *
     * Returns the symbols it could not sell, which is not always a failure: a
     * coin with no route out cannot be sold by anybody, and saying so is more
     * useful than retrying.
     */
    /**
     * Sell one coin back to SOL. Returns true when the transaction went out.
     *
     * Its own function because "sell everything" is this, repeated, and a retry
     * of one coin has to be exactly the same operation as the first attempt.
     */
    private suspend fun sellOne(ctx: Context, owner: String, h: SolanaRpc.TokenAccountInfo): Boolean {
        val quote = withContext(Dispatchers.IO) {
            runCatching { Jupiter.quote(h.mint, Jupiter.SOL_MINT, h.amount, slippageBps = 500, feeBps = 0) }.getOrNull()
        } ?: return false
        val tx = withContext(Dispatchers.IO) { runCatching { Jupiter.swapTransaction(quote, owner, null) }.getOrNull() } ?: return false
        val sig = SessionWallet.sign(ctx, SolanaTx.messageBytes(tx)) ?: return false
        val idx = SolanaTx.decode(tx)?.let { d -> d.staticAccountKeys.indexOf(owner).takeIf { it in 0 until d.numRequiredSignatures } } ?: 0
        val out = withContext(Dispatchers.IO) { SolanaRpc.send(SolanaRpc.urlFor(null), SolanaTx.attachSignature(tx, idx, sig)) }
        return out.signature != null
    }

    /**
     * Sell everything the budget holds back to SOL, and mean it.
     *
     * The first version walked the list once, back to back, and came home having
     * sold one coin: Jupiter answers a burst of quotes from the same client with
     * a rate limit, so every coin after the first got no quote and was quietly
     * filed as "could not sell". A button that says *everything* and does one is
     * worse than no button, because you press it three times and never know
     * whether the third press did anything.
     *
     * So: a breath between coins, a second pass for whatever did not go, and the
     * holdings re-read from the chain between passes — the only honest way to
     * know there is nothing left. [onProgress] reports "n of total" so the screen
     * can show it working instead of looking frozen.
     *
     * Returns the symbols that would not sell, which is not always a failure: a
     * coin with no route out cannot be sold by anybody.
     */
    suspend fun sellAll(ctx: Context, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<String> {
        val s = SessionWallet.current(ctx) ?: return emptyList()
        var left = holdings(ctx)
        val total = left.size
        var done = 0
        val stuck = LinkedHashMap<String, SolanaRpc.TokenAccountInfo>()

        repeat(2) { pass ->
            if (left.isEmpty()) return@repeat
            for ((i, h) in left.withIndex()) {
                val sym = TokenSymbols.symbol(h.mint)
                if (sellOne(ctx, s.pubkey, h)) {
                    done++
                    stuck.remove(sym)
                } else {
                    stuck[sym] = h
                }
                onProgress(done, total)
                // Jupiter rate-limits a burst from one client, and this loop is a
                // burst by definition. Three quarters of a second between coins
                // costs nothing on a handful of them and is the difference
                // between selling one and selling all of them.
                if (i < left.lastIndex) kotlinx.coroutines.delay(750)
            }
            if (stuck.isEmpty()) return@repeat
            // Between passes, believe the chain rather than our own list: a coin
            // that did land is gone from it, and one that did not is still there.
            kotlinx.coroutines.delay(1_500)
            val onChain = holdings(ctx).associateBy { TokenSymbols.symbol(it.mint) }
            left = stuck.keys.mapNotNull { onChain[it] }
            stuck.keys.retainAll(onChain.keys)
        }
        return stuck.keys.toList()
    }

    /**
     * Move every coin the budget holds to the owner's account, untouched.
     *
     * One transaction per coin, each opening the receiving account on the
     * owner's side first. Nothing is sold, so a coin nobody wants still arrives
     * somewhere you can reach it, which is the difference between a bad trade
     * and a lost one.
     */
    suspend fun moveTokensTo(ctx: Context, owner: String): List<String> {
        val s = SessionWallet.current(ctx) ?: return emptyList()
        val ownerKey = Base58.decodePubkey(owner) ?: return listOf(owner)
        val fromKey = Base58.decodePubkey(s.pubkey) ?: return listOf(s.pubkey)
        val stuck = ArrayList<String>()
        for (h in holdings(ctx)) {
            val sym = TokenSymbols.symbol(h.mint)
            val mint = Base58.decodePubkey(h.mint)
            if (mint == null) { stuck += sym; continue }
            val program = Base58.decode(h.program)
            val dest = Pda.associatedTokenAddress(ownerKey, mint, program)
            val ix = listOf(
                // The budget pays the rent for the receiving account, because the
                // budget is the thing being emptied.
                WalletTx.createAtaIdempotent(fromKey, dest, ownerKey, mint, program),
                WalletTx.tokenTransferChecked(Base58.decode(h.pubkey), mint, dest, fromKey, h.amount, h.decimals, program),
            )
            val bh = withContext(Dispatchers.IO) { SolanaRpc.latestBlockhash(SolanaRpc.urlFor(null)) }
            if (bh == null) { stuck += sym; continue }
            val tx = WalletTx.build(fromKey, Base58.decode(bh.hash), ix)
            val sig = SessionWallet.sign(ctx, SolanaTx.messageBytes(tx))
            if (sig == null) { stuck += sym; continue }
            val out = withContext(Dispatchers.IO) { SolanaRpc.send(SolanaRpc.urlFor(null), SolanaTx.attachSignature(tx, 0, sig)) }
            if (out.signature == null) stuck += sym
        }
        return stuck
    }

    /**
     * Take the winnings home and leave the working capital in place.
     *
     * Anything the envelope holds above what you funded is gain. Once that gain
     * reaches the threshold you armed, this moves it to your Seed Vault account
     * and leaves the original stake behind so the agent keeps working. It needs
     * no approval because the money only ever travels one way: to you.
     *
     * Returns the lamports harvested (0 when there was nothing to take), or null
     * on failure.
     */
    suspend fun harvest(ctx: Context, owner: String, force: Boolean = false): Long? {
        val s = SessionWallet.current(ctx) ?: return 0L
        val threshold = s.harvestLamports
        if (!force && threshold <= 0L) return 0L
        val rpc = SolanaRpc.urlFor(null)
        val balance = withContext(Dispatchers.IO) { runCatching { SolanaRpc.getBalance(rpc, s.pubkey) }.getOrNull() } ?: return 0L
        val fee = 5_000L
        val gain = balance - s.fundedLamports
        if (gain <= fee) return 0L
        if (!force && gain < threshold) return 0L
        val take = gain - fee
        val err = sweepTo(ctx, owner, take, ctx.getString(R.string.env_log_harvest))
        if (err != null) return null
        SessionWallet.addHarvested(ctx, take)
        return take
    }

    /**
     * Sweep [lamports] from the envelope back to [owner], signed with the
     * envelope key. Returns an error, or null when it landed.
     */
    suspend fun sweep(ctx: Context, owner: String, lamports: Long): String? =
        sweepTo(ctx, owner, lamports, ctx.getString(R.string.env_log_sweep))

    private suspend fun sweepTo(ctx: Context, owner: String, lamports: Long, label: String): String? = withContext(Dispatchers.IO) {
        val session = SessionWallet.current(ctx) ?: return@withContext ctx.getString(R.string.env_key_missing)
        val from = Base58.decodePubkey(session.pubkey) ?: return@withContext ctx.getString(R.string.wa_bad_address)
        val to = Base58.decodePubkey(owner) ?: return@withContext ctx.getString(R.string.wa_bad_address)
        val rpc = SolanaRpc.urlFor(null)
        val bh = SolanaRpc.latestBlockhash(rpc) ?: return@withContext ctx.getString(R.string.wa_no_blockhash)
        val tx = WalletTx.build(from, Base58.decode(bh.hash), listOf(WalletTx.systemTransfer(from, to, lamports)))
        val signature = SessionWallet.sign(ctx, SolanaTx.messageBytes(tx)) ?: return@withContext ctx.getString(R.string.env_key_missing)
        val signed = SolanaTx.attachSignature(tx, 0, signature)
        val out = SolanaRpc.send(rpc, signed)
        if (out.signature == null) return@withContext ctx.getString(R.string.wa_send_failed, out.error ?: "?")
        val at = System.currentTimeMillis()
        // Read the bytes we just sent, so the ledger row says how much came back and
        // from where. It used to record `r = null`: the receipt said a budget had
        // been closed and never said what for.
        val receipt = runCatching {
            ReceiptEngine.analyze(ctx, BlocklistScanner(ctx), signed, owner, null, requireSim = false).receipt
        }.getOrNull()
        LedgerRecorder.record(
            ctx,
            LedgerRecorder.fromReceipt(
                at = at, kind = "envelope", dApp = ctx.getString(R.string.env_title), host = null, pkg = ctx.packageName,
                cluster = null, wallet = owner, r = receipt, signature = out.signature, sent = true,
                txIndex = 0, txCount = 1, groupId = LedgerRecorder.newId(),
                attestation = null, attestationSig = null,
                recipientLabelFallback = label,
            ),
        )
        null
    }
}
