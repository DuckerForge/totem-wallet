package com.clearsign.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * The two chain operations the budget needs: filling it (a normal transfer from the
 * Seed Vault account, receipt and one biometric approval) and taking it back (signed
 * by the budget key this app holds, no biometrics: ending the agent's power must be instant).
 */
/**
 * One hand on the budget key at a time. The loop had its own lock and nothing else
 * that signs with the key went through it: sell from a notification, sell everything,
 * harvest, the keeper closing an expired budget. That ends with the keeper forgetting
 * the key mid-sale. Not reentrant: each public action has a private twin for callers
 * already under the lock.
 */
internal object EnvelopeLock {
    private val m = kotlinx.coroutines.sync.Mutex()
    suspend fun <T> withLock(block: suspend () -> T): T = m.withLock { block() }
}

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
     * The coins the budget holds, minus dust. Invisible elsewhere: the wallet screen shows
     * the Seed Vault account and the agent buys with another key. Closing used to move
     * only the SOL and erase the key, leaving real tokens nobody could ever open again.
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
     * What the budget really holds, raw units per mint, from the chain: the loop needs
     * it before believing its book, the panel before offering a sell button. [force]
     * when the number must be now, not a minute old: a sale swaps exactly what it reads
     * here, and a stale larger read fails on chain. Reconciling is fine with the cache.
     */
    suspend fun heldRaw(ctx: Context, owner: String, force: Boolean = false): Map<String, Long>? = withContext(Dispatchers.IO) {
        // Null when the chain did not answer, never an empty map: the loop treats
        // an empty map as "the coins are gone", and a rate-limited node is not that.
        runCatching { SolanaRpc.tokensOf(SolanaRpc.urlFor(null), owner, force) }
            .getOrNull()
            ?.filter { it.amount > 0 }
            ?.associate { it.mint to it.amount }
    }

    /**
     * A sale asked by a person, answered in words: true when the coins are gone and the
     * row removed, false with the reason. Card, notification and eyes say the same thing.
     */
    class Said(val ok: Boolean, val text: String, val worse: Sale.Worse? = null)

    suspend fun sellSaid(ctx: Context, pos: Positions.Position, source: AgentBroker.Job.Source, acceptReal: Boolean = false): Said =
        EnvelopeLock.withLock { sellSaidInner(ctx, pos, source, acceptReal) }

    private suspend fun sellSaidInner(ctx: Context, pos: Positions.Position, source: AgentBroker.Job.Source, acceptReal: Boolean): Said {
        val why = ctx.getString(R.string.trader_why_you, pos.symbol)
        val sale = runCatching { sellNow(ctx, pos, why, source, acceptReal) }.getOrNull()
        val v = (sale as? Sale.Judged)?.verdict
        if (sale is Sale.Worse) {
            val drop = ((1 - sale.realLamports.toDouble() / sale.quotedLamports) * 100).toInt()
            return Said(false, ctx.getString(R.string.trader_sell_worse, fmtSol(sale.quotedLamports, 4), fmtSol(sale.realLamports, 4), drop), sale)
        }
        val r: Pair<Boolean, String> = when {
            v is AgentBroker.Verdict.SignedSilently || v is AgentBroker.Verdict.Confirmed -> {
                Positions.remove(ctx, pos.mint)
                val m = ctx.getString(R.string.trader_sold_you, pos.symbol)
                AgentTrace.say(m, AgentTrace.Kind.ACTED)
                true to m
            }
            v is AgentBroker.Verdict.Timeout -> false to ctx.getString(R.string.trader_needed_you)
            v != null -> false to (v.reason ?: ctx.getString(R.string.trader_net_down))
            sale is Sale.Nothing -> false to ctx.getString(R.string.trader_no_coins)
            sale is Sale.NoRoute -> false to ctx.getString(R.string.trader_no_route)
            else -> false to ctx.getString(R.string.trader_net_down)
        }
        return Said(r.first, r.second)
    }

    /*
     * Selling one holding back to SOL, now, through the collar: the one door out, used by
     * the loop on a target or a stop and by the button. The transaction is built at the
     * moment of the decision, a swap's blockhash dies in about ninety seconds. The amount
     * comes from the chain, never the book, and the answer says which of four things
     * happened: all-null answers once pushed a live stop-loss into the six-hour lane.
     */
    /**
     * A swap transaction, Ultra first and swap v1 when Ultra does not answer. [quote]
     * reads as always; [ultraRequestId] goes to whoever sends, so Jupiter lands its own bytes.
     */
    class Built(val tx: ByteArray, val quote: Jupiter.Quote, val ultraRequestId: String?)

    suspend fun buildSwap(inMint: String, outMint: String, amount: Long, taker: String, slippageBps: Int? = null): Built? = withContext(Dispatchers.IO) {
        runCatching { JupiterUltra.order(inMint, outMint, amount, taker, slippageBps) }.getOrNull()?.let { o ->
            return@withContext Built(o.tx, o.asQuote(), o.requestId)
        }
        val q = runCatching { Jupiter.quote(inMint, outMint, amount, slippageBps = slippageBps ?: 50, feeBps = 0) }.getOrNull() ?: return@withContext null
        val tx = runCatching { Jupiter.swapTransaction(q, taker, null) }.getOrNull() ?: return@withContext null
        Built(tx, q, null)
    }

    sealed class Sale {
        /** The chain says the budget holds none of this coin. The book is wrong, not the sale. */
        object Nothing : Sale()
        /** The node did not answer. Try again, count nothing. */
        object Unreachable : Sale()
        /** Jupiter gave no quote or no transaction for it. A real problem with this coin, worth counting. */
        object NoRoute : Sale()
        class Judged(val verdict: AgentBroker.Verdict) : Sale()
        /**
         * Jupiter's quote and the chain's simulation disagree beyond the collar's tolerance:
         * the sale would bring [realLamports], not [quotedLamports]. Nothing proposed; a
         * person can say "sell anyway" and it is proposed again declaring the real number.
         */
        class Worse(val quotedLamports: Long, val realLamports: Long) : Sale()
    }

    suspend fun sellNow(
        ctx: Context,
        pos: Positions.Position,
        reason: String,
        source: AgentBroker.Job.Source,
        acceptReal: Boolean = false,
    ): Sale {
        val s = SessionWallet.current(ctx) ?: return Sale.Nothing
        val held = heldRaw(ctx, s.pubkey, force = true) ?: return Sale.Unreachable
        val raw = held[pos.mint] ?: return Sale.Nothing
        if (raw <= 0L) return Sale.Nothing
        // Wider slippage on the way out than on the way in: a stop that does not
        // fill because the price moved while we asked is not a stop at all.
        val built = buildSwap(pos.mint, Jupiter.SOL_MINT, raw, s.pubkey, slippageBps = 300) ?: return Sale.NoRoute
        val quote = built.quote
        val tx = built.tx
        val units = raw / Math.pow(10.0, pos.decimals.toDouble())
        // What the chain says comes back, before anything is declared. On a thin coin the
        // quote and the simulated route can be far apart and the collar rightly refuses a
        // declaration that differs. If the real number is worse than the guard tolerates,
        // say so and stop, or, when the caller accepts, declare the real number.
        val real = withContext(Dispatchers.IO) {
            runCatching {
                ReceiptEngine.analyze(ctx, BlocklistScanner(ctx), tx, s.pubkey, null, requireSim = true).receipt
                    .inflows.firstOrNull { it.mint == com.clearsign.core.NATIVE_SOL_MINT }?.rawAmount
            }.getOrNull()
        }
        var declared = quote.outAmount
        if (real != null && real > 0 && real < quote.outAmount * 0.97) {
            if (!acceptReal) return Sale.Worse(quote.outAmount, real)
            declared = (real * 0.99).toLong()
        }
        val intent = JSONObject()
            .put("action", "swap").put("outMint", pos.symbol).put("outAmount", units)
            .put("inMint", "SOL").put("inAmount", declared / 1e9)
            .put("agent", TraderLoop.AGENT).put("reason", reason)
        return Sale.Judged(
            AgentBroker.handle(
                ctx,
                AgentBroker.Job(
                    id = LedgerRecorder.newId(), tx = tx, intentJson = intent.toString(),
                    cluster = null, agent = TraderLoop.AGENT, source = source, ultraRequestId = built.ultraRequestId,
                ),
            ),
        )
    }

    /* What the whole holding would fetch in lamports right now, or null. */
    suspend fun quoteValue(ctx: Context, pos: Positions.Position): Long? {
        val s = SessionWallet.current(ctx) ?: return null
        val raw = heldRaw(ctx, s.pubkey, force = true)?.get(pos.mint) ?: return null
        if (raw <= 0L) return null
        return withContext(Dispatchers.IO) {
            runCatching { Jupiter.quote(pos.mint, Jupiter.SOL_MINT, raw, feeBps = 0) }.getOrNull()?.outAmount
        }
    }

    /**
     * Sell one coin back to SOL; true when the transaction went out. Its own function
     * because "sell everything" is this repeated, and a retry must be the same operation.
     */
    private suspend fun sellOne(ctx: Context, owner: String, h: SolanaRpc.TokenAccountInfo): Boolean {
        // Through the collar like every sale: signing directly made this the one button
        // that skipped the receipt. The chain's real price is accepted: the person asked to
        // be out, not to be asked again.
        val cfg = TraderLoop.config(ctx)
        val pos = Positions.open(ctx).firstOrNull { it.mint == h.mint } ?: Positions.Position(
            mint = h.mint, symbol = TokenSymbols.symbol(h.mint), decimals = h.decimals,
            units = h.amount / Math.pow(10.0, h.decimals.toDouble()), owner = owner,
            costLamports = 0L, openedAt = System.currentTimeMillis(),
            takeProfitPct = cfg.takeProfitPct, stopLossPct = cfg.stopLossPct,
        )
        val why = ctx.getString(R.string.trader_why_you, pos.symbol)
        val sale = runCatching { sellNow(ctx, pos, why, AgentBroker.Job.Source.IN_APP, acceptReal = true) }.getOrNull()
        val v = (sale as? Sale.Judged)?.verdict
        val ok = v is AgentBroker.Verdict.SignedSilently || v is AgentBroker.Verdict.Confirmed
        if (ok) Positions.remove(ctx, pos.mint)
        return ok
    }

    /**
     * Sell everything the budget holds back to SOL, and mean it. The first version
     * walked the list once and came home having sold one coin: Jupiter rate-limits a
     * burst of quotes, so every coin after the first was filed as "could not sell". So: a
     * breath between coins, a second pass, holdings re-read from the chain between passes,
     * [onProgress] as "n of total". Returns the symbols that would not sell, which is not
     * always a failure: a coin with no route out cannot be sold by anybody.
     */
    suspend fun sellAll(ctx: Context, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<String> =
        EnvelopeLock.withLock { sellAllInner(ctx, onProgress) }

    internal suspend fun sellAllInner(ctx: Context, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<String> {
        val s = SessionWallet.current(ctx) ?: return emptyList()
        var left = holdings(ctx)
        val total = left.size
        var done = 0
        // Keyed by mint, never by symbol: two mints calling themselves USDC is the oldest
        // trick here. Keyed by symbol one got sold, the other left the stuck list, and the
        // caller heard "empty" when it was not. People see the symbol; the code counts the mint.
        val stuck = LinkedHashMap<String, SolanaRpc.TokenAccountInfo>()

        repeat(2) { pass ->
            if (left.isEmpty()) return@repeat
            for ((i, h) in left.withIndex()) {
                if (sellOne(ctx, s.pubkey, h)) {
                    done++
                    stuck.remove(h.mint)
                } else {
                    stuck[h.mint] = h
                }
                onProgress(done, total)
                // Jupiter rate-limits a burst from one client, and this loop is a burst. Three
                // quarters of a second between coins is the difference between selling one and all.
                if (i < left.lastIndex) kotlinx.coroutines.delay(750)
            }
            if (stuck.isEmpty()) return@repeat
            // Between passes, believe the chain rather than our own list: a coin
            // that did land is gone from it, and one that did not is still there.
            kotlinx.coroutines.delay(1_500)
            val onChain = holdings(ctx).associateBy { it.mint }
            left = stuck.keys.mapNotNull { onChain[it] }
            stuck.keys.retainAll(onChain.keys)
        }
        return stuck.keys.map { TokenSymbols.symbol(it) }
    }

    /**
     * Move every coin the budget holds to the owner's account, untouched: one transaction
     * per coin, opening the receiving account first. Nothing is sold, so a coin nobody
     * wants still lands where you can reach it: a bad trade, not a lost one.
     */
    suspend fun moveTokensTo(ctx: Context, owner: String): List<String> =
        EnvelopeLock.withLock { moveTokensToInner(ctx, owner) }

    private suspend fun moveTokensToInner(ctx: Context, owner: String): List<String> {
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
            val signed = SolanaTx.attachSignature(tx, 0, sig)
            val out = withContext(Dispatchers.IO) { SolanaRpc.send(SolanaRpc.urlFor(null), signed) }
            if (out.signature == null) { stuck += sym; continue }
            // A coin that came home is a line in the book. These moves used to
            // be the only transactions of the budget without one: the coin
            // appeared in the wallet and the receipts had nothing to say.
            val receipt = withContext(Dispatchers.IO) {
                runCatching { ReceiptEngine.analyze(ctx, BlocklistScanner(ctx), signed, owner, null, requireSim = false).receipt }.getOrNull()
            }
            LedgerRecorder.record(
                ctx,
                LedgerRecorder.fromReceipt(
                    at = System.currentTimeMillis(), kind = "envelope", dApp = ctx.getString(R.string.env_title), host = null, pkg = ctx.packageName,
                    cluster = null, wallet = owner, r = receipt, signature = out.signature, sent = true,
                    txIndex = 0, txCount = 1, groupId = LedgerRecorder.newId(), attestation = null, attestationSig = null,
                    recipientLabelFallback = ctx.getString(R.string.env_log_moved, sym),
                ),
            )
        }
        return stuck
    }

    /**
     * Take the winnings home, leave the working capital. Whatever the budget holds above
     * what you funded is gain; past the armed threshold it moves to your Seed Vault
     * account and the stake keeps working. No approval: the money only travels toward
     * you. Returns the lamports harvested (0 when nothing), or null on failure.
     */
    suspend fun harvest(ctx: Context, owner: String, force: Boolean = false): Long? =
        EnvelopeLock.withLock { harvestInner(ctx, owner, force) }

    internal suspend fun harvestInner(ctx: Context, owner: String, force: Boolean = false): Long? {
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

    /*
     * Closing the budget's empty token accounts and sending their rent to [owner]. Every
     * coin bought opens an account holding about 0.002 SOL; forgetting the key with those
     * open loses that rent for good (it happened: two accounts, 0.003 SOL). So it runs
     * before the sweep, while the key still signs and still has SOL for the fee.
     */
    /** What closing did: how many landed, how many did not, the rent that reached the owner. */
    class Closed(val closed: Int, val failed: Int, val rentLamports: Long)

    suspend fun closeEmpty(ctx: Context, owner: String): Closed = withContext(Dispatchers.IO) {
        val session = SessionWallet.current(ctx) ?: return@withContext Closed(0, 0, 0L)
        val from = Base58.decodePubkey(session.pubkey) ?: return@withContext Closed(0, 0, 0L)
        val to = Base58.decodePubkey(owner) ?: return@withContext Closed(0, 0, 0L)
        val rpc = SolanaRpc.urlFor(null)
        // A read that did not come back is not an empty wallet: "nothing to close" here would
        // let the caller forget the key with rent still inside, so it counts as one failure.
        val accounts = runCatching { SolanaRpc.tokenAccountsOf(rpc, session.pubkey, force = true) }.getOrNull()
            ?: return@withContext Closed(0, 1, 0L)
        val empty = accounts
            .filter { it.amount == 0L && it.state != "frozen" && (it.closeAuthority == null || it.closeAuthority == session.pubkey) }
        if (empty.isEmpty()) return@withContext Closed(0, 0, 0L)
        var closed = 0
        var failed = 0
        var rent = 0L
        // A dozen per transaction keeps each one small; a budget rarely has more.
        for (batch in empty.chunked(12)) {
            val bh = SolanaRpc.latestBlockhash(rpc)
            val ixs = batch.mapNotNull { a ->
                val acct = Base58.decodePubkey(a.pubkey) ?: return@mapNotNull null
                WalletTx.tokenCloseAccount(acct, to, from, WalletTx.tokenProgramFor(a.program))
            }
            if (bh == null || ixs.isEmpty()) { failed += batch.size; continue }
            val tx = WalletTx.build(from, Base58.decode(bh.hash), ixs)
            val signature = SessionWallet.sign(ctx, SolanaTx.messageBytes(tx))
            if (signature == null) { failed += batch.size; continue }
            val signed = SolanaTx.attachSignature(tx, 0, signature)
            val out = SolanaRpc.send(rpc, signed)
            // Sent is not landed: recorded as done twice for transactions that never reached the
            // chain, and the key was forgotten with the rent inside. Row and count wait for the chain.
            if (out.signature == null || !SolanaRpc.confirmed(rpc, out.signature)) { failed += batch.size; continue }
            // Count the rent of the accounts this transaction actually closed.
            // Summing the whole batch overstated it whenever a pubkey would not
            // decode and its instruction was dropped.
            closed += ixs.size
            rent += batch.take(ixs.size).sumOf { it.lamports }
            if (ixs.size < batch.size) failed += batch.size - ixs.size
            val receipt = runCatching {
                ReceiptEngine.analyze(ctx, BlocklistScanner(ctx), signed, owner, null, requireSim = false).receipt
            }.getOrNull()
            LedgerRecorder.record(
                ctx,
                LedgerRecorder.fromReceipt(
                    at = System.currentTimeMillis(), kind = "envelope", dApp = ctx.getString(R.string.env_title), host = null, pkg = ctx.packageName,
                    cluster = null, wallet = owner, r = receipt, signature = out.signature, sent = true,
                    txIndex = 0, txCount = 1, groupId = LedgerRecorder.newId(),
                    attestation = null, attestationSig = null,
                    recipientLabelFallback = ctx.getString(R.string.env_log_rent),
                ),
            )
        }
        Closed(closed, failed, rent)
    }

    /**
     * Close the budget for good: sell, close the empty accounts, bring the SOL home, write
     * the account, forget the key. Every step waits for the chain; if one does not land
     * the budget stays open and the message says why. Close button and expiry. Returns
     * (closed, what to say).
     */
    suspend fun closeBudget(ctx: Context, owner: String): Pair<Boolean, String> =
        EnvelopeLock.withLock { closeBudgetInner(ctx, owner) }

    /** No burying below this gain: a transaction for crumbs is not worth it. */
    private const val BURY_MIN_LAMPORTS = 5_000_000L
    /** What stays in SOL for the fees of the exits that follow. */
    private const val BURY_KEEP_LAMPORTS = 1_000_000L

    internal suspend fun closeBudgetInner(ctx: Context, owner: String): Pair<Boolean, String> {
        val s = SessionWallet.current(ctx) ?: return false to ctx.getString(R.string.trader_stop_nobudget)
        runCatching { AgentLinkService.revoke(ctx) }
        // ORE first: the automation stops, its leftovers return to the budget, the dug ORE
        // goes to the owner. An error here stops the close like any step: closing with money
        // parked in another program's account is forgetting the key with the money outside.
        runCatching { OreAgent.bringHome(ctx, owner) }.getOrDefault(null)?.let { return false to it }
        val stuck = runCatching { sellAllInner(ctx) }.getOrDefault(listOf("?"))
        if (stuck.isNotEmpty()) return false to ctx.getString(R.string.env_sell_all_stuck, stuck.joinToString(", "))
        var rent = runCatching { closeEmpty(ctx, owner) }.getOrNull() ?: Closed(0, 1, 0L)
        if (rent.failed > 0) return false to ctx.resources.getQuantityString(R.plurals.env_rent_stuck, rent.failed, rent.failed)
        // Bury the gain. Everything is SOL by now: what sits above the capital, counting what
        // already went home, is swapped into ORE and leaves with the rest. Never the capital.
        // If the swap fails (no route, collar asking) the gain stays SOL and goes home; the
        // close does not stop for this.
        if (TraderLoop.config(ctx).oreBury) {
            val now = withContext(Dispatchers.IO) { runCatching { SolanaRpc.getBalance(SolanaRpc.urlFor(null), s.pubkey) }.getOrNull() }
            val gain = if (now == null) 0L else now + s.harvestedLamports - s.fundedLamports - BURY_KEEP_LAMPORTS
            if (gain >= BURY_MIN_LAMPORTS) {
                val why = runCatching { TraderLoop.buryInOre(ctx, gain) }.getOrElse { it.message }
                if (why == null) {
                    // The ORE sits on the budget's account: bring it home, and the empty
                    // account returns its rent like any other.
                    val stuckOre = runCatching { moveTokensToInner(ctx, owner) }.getOrDefault(listOf("ORE"))
                    if (stuckOre.isNotEmpty()) return false to ctx.getString(R.string.env_sell_all_stuck, stuckOre.joinToString(", "))
                    val again = runCatching { closeEmpty(ctx, owner) }.getOrNull() ?: Closed(0, 1, 0L)
                    if (again.failed > 0) return false to ctx.resources.getQuantityString(R.plurals.env_rent_stuck, again.failed, again.failed)
                    rent = Closed(rent.closed + again.closed, 0, rent.rentLamports + again.rentLamports)
                } else {
                    AgentTrace.say(ctx.getString(R.string.trader_bury_skipped, why), AgentTrace.Kind.REFUSED)
                }
            }
        }
        // Fail closed here too: an unreadable balance read as zero once sent nothing home and
        // forgot the key with the money still on the chain. This was the last hole.
        val left = withContext(Dispatchers.IO) { runCatching { SolanaRpc.getBalance(SolanaRpc.urlFor(null), s.pubkey) }.getOrNull() }
        val back = BudgetMath.sweepBack(left) ?: return false to ctx.getString(R.string.env_balance_unknown)
        if (back > 0L) sweep(ctx, owner, back)?.let { return false to it }
        val rows = runCatching { Ledger.all(ctx) }.getOrDefault(emptyList())
            .filter { it.kind == "agent" && it.sent && it.at >= s.createdAt && (it.host == "auto" || it.host == "asked") }
        val close = SessionWallet.Close(
            fundedLamports = s.fundedLamports, harvestedLamports = s.harvestedLamports,
            backLamports = back + rent.rentLamports, createdAt = s.createdAt, closedAt = System.currentTimeMillis(),
            buys = rows.count { r -> r.outflows.any { it.mint == com.clearsign.core.NATIVE_SOL_MINT } },
            sells = rows.count { r -> r.inflows.any { it.mint == com.clearsign.core.NATIVE_SOL_MINT } },
        )
        SessionWallet.recordClose(ctx, close)
        val said = ctx.getString(
            R.string.env_close_summary, fmtSol(close.fundedLamports, 4), fmtSol(close.backLamports + close.harvestedLamports, 4),
            (if (close.resultLamports >= 0) "+" else "−") + fmtSol(kotlin.math.abs(close.resultLamports), 4),
            String.format(java.util.Locale.ROOT, "%+.1f%%", close.resultPct),
        )
        SessionWallet.forget(ctx)
        return true to said
    }

    /** Sweep [lamports] from the budget back to [owner], signed with the budget key. An error, or null when it landed. */
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
        // The key is about to be forgotten on the strength of this. Wait for
        // the chain, not the node.
        if (!SolanaRpc.confirmed(rpc, out.signature)) return@withContext ctx.getString(R.string.env_sweep_unconfirmed)
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
