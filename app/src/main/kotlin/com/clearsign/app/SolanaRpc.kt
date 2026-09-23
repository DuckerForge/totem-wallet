package com.clearsign.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Minimal Solana JSON-RPC client: simulate (the node runs v0, lookup tables and
 * the token programs, which the on-device decoder cannot) and send. All calls
 * block; never on the main thread. Every read uses `commitment: processed`, the
 * simulation's level, but two nodes are not on one slot: what lines them up is
 * the slot in `result.context`, checked in [simulateEffects].
 */
object SolanaRpc {

    private const val TAG = "ClearSign-RPC"

    /** Dedicated mainnet RPC (Helius) for reliable simulation + token balances,
     *  free of public rate limits. Injected from local.properties at build time
     *  (never committed); when blank, the public nodes are used. */
    @Volatile var customRpc: String? = BuildConfig.HELIUS_RPC_URL.takeIf { it.isNotBlank() }

    // Who actually answers is the pool's call, in [Rpc]: this is only the name
    // readers use to ask for "mainnet".
    private const val MAINNET_PRIMARY = "https://api.mainnet-beta.solana.com"

    private const val COMMITMENT = "processed"

    /** Small pool for the fan-out reads; bounded so a 10-tx bundle can't spawn 60 threads. */
    private val pool = Executors.newFixedThreadPool(6) { r ->
        Thread(r, "apex-rpc").apply { isDaemon = true }
    }
    private fun <T> async(block: () -> T): Future<T> = pool.submit(Callable(block))

    /**
     * Wait for a read, never forever. The HTTP timeouts are bounded, so a get still
     * pending after a minute never started; waiting with no deadline turned a stuck
     * pool into a stuck app.
     */
    private fun <T> Future<T>.await(): T? =
        runCatching { get(60, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()

    fun urlFor(cluster: String?): String = when (cluster?.lowercase()) {
        "devnet", "solana:devnet" -> "https://api.devnet.solana.com"
        "testnet", "solana:testnet" -> "https://api.testnet.solana.com"
        else -> customRpc?.takeIf { it.isNotBlank() } ?: MAINNET_PRIMARY
    }

    data class SimResult(val ok: Boolean, val err: String?, val logs: List<String>, val unitsConsumed: Long?)

    /** Simulate a serialized transaction. Returns null on a network/transport failure. */
    fun simulate(rpcUrl: String, txBytes: ByteArray): SimResult? {
        val b64 = B64.encode(txBytes)
        val params = JSONArray()
            .put(b64)
            .put(
                JSONObject()
                    .put("encoding", "base64")
                    .put("sigVerify", false)
                    .put("replaceRecentBlockhash", true)
                    .put("commitment", COMMITMENT),
            )
        val resp = post(rpcUrl, "simulateTransaction", params) ?: return null
        return try {
            val value = resp.getJSONObject("result").getJSONObject("value")
            val err = if (value.isNull("err")) null else value.get("err").toString()
            val logs = value.optJSONArray("logs")?.let { arr ->
                List(arr.length()) { arr.getString(it) }
            } ?: emptyList()
            val units = if (value.isNull("unitsConsumed")) null else value.optLong("unitsConsumed")
            SimResult(ok = err == null, err = err, logs = logs, unitsConsumed = units)
        } catch (_: Exception) {
            // A JSON-RPC error object (not "result") means the node rejected it.
            SimResult(ok = false, err = resp.optJSONObject("error")?.optString("message") ?: "simulation failed", logs = emptyList(), unitsConsumed = null)
        }
    }

    private const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    private const val TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"

    /**
     * One of the owner's token accounts as the node parses it, plus what the hygiene
     * screens need: an active delegate, the rent locked inside, the owning program.
     */
    data class TokenAccountInfo(
        val pubkey: String,
        val mint: String,
        val decimals: Int,
        val amount: Long,               // raw units
        val lamports: Long,             // rent held by the account (returned on close)
        val delegate: String?,          // spender approved by the owner, if any
        val delegatedAmount: Long,      // raw units; -1 = u64::MAX ("unlimited")
        val program: String,            // TOKEN_PROGRAM or TOKEN_2022
        val closeAuthority: String?,    // who may close it (null = the owner)
        val state: String,              // "initialized" | "frozen" | "uninitialized"
    ) {
        val isFrozen: Boolean get() = state == "frozen"
        val isUnlimitedDelegation: Boolean get() = delegatedAmount == -1L || delegatedAmount >= Long.MAX_VALUE / 2
        /** An approval a third party can still spend against. */
        val hasActiveDelegate: Boolean get() = delegate != null && delegatedAmount != 0L && !isFrozen
        /** Empty and closable by the owner: closing refunds [lamports]. */
        fun isClosableBy(owner: String): Boolean = amount == 0L && !isFrozen && (closeAuthority == null || closeAuthority == owner)
    }

    /** Parse one entry of a jsonParsed `getTokenAccountsByOwner` value array. */
    internal fun parseTokenAccount(obj: JSONObject): TokenAccountInfo? {
        val pubkey = obj.optString("pubkey").takeIf { it.isNotEmpty() } ?: return null
        val account = obj.optJSONObject("account") ?: return null
        val info = account.optJSONObject("data")?.optJSONObject("parsed")?.optJSONObject("info") ?: return null
        val mint = info.optString("mint").takeIf { it.isNotEmpty() } ?: return null
        val amt = info.optJSONObject("tokenAmount")
        val delegated = info.optJSONObject("delegatedAmount")?.optString("amount")
        return TokenAccountInfo(
            pubkey = pubkey,
            mint = mint,
            decimals = amt?.optInt("decimals") ?: 0,
            amount = parseUnsignedLong(amt?.optString("amount")),
            lamports = account.optLong("lamports", 0L),
            delegate = info.optString("delegate").takeIf { it.isNotEmpty() },
            delegatedAmount = if (delegated == null) 0L else parseUnsignedLong(delegated),
            program = account.optString("owner").takeIf { it.isNotEmpty() } ?: TOKEN_PROGRAM,
            closeAuthority = info.optString("closeAuthority").takeIf { it.isNotEmpty() },
            state = info.optString("state").takeIf { it.isNotEmpty() } ?: "initialized",
        )
    }

    /** u64 as the node prints it (decimal string, may exceed Long). u64::MAX and anything
     *  above Long.MAX_VALUE come back as -1 ("unlimited"); garbage → 0. */
    internal fun parseUnsignedLong(text: String?): Long {
        val t = text?.trim().orEmpty()
        if (t.isEmpty() || !t.all { it.isDigit() }) return 0L
        return t.toLongOrNull() ?: -1L
    }

    /** Outcome of trying to work out a transaction's real effects. */
    sealed interface SimOutcome {
        /**
         * Simulated cleanly. [deltas] are the real balance changes, the owner's and every
         * tracked destination's, so a split across wallets shows. [computeUnits] and
         * [logCount] are the simulation's own stats.
         */
        data class Ok(
            val deltas: List<com.clearsign.core.BalanceDelta>,
            val computeUnits: Long? = null,
            val logCount: Int = 0,
            val preBalances: Map<String, Long> = emptyMap(), // owner's balances before the tx, by mint (raw units)
        ) : SimOutcome
        /** Simulated, but the network reported the transaction would fail. */
        data class Failed(val err: String) : SimOutcome
        /** Could not reach/parse the RPC — verification unavailable (don't hard-block). */
        data object Unavailable : SimOutcome
    }

    // ---- session cache -------------------------------------------------------

    /** The owner's token-account list changes rarely (a new ATA); cache it briefly so a
     *  bundle of N tx, and the anti-TOCTOU re-check, don't refetch it N+1 times. */
    private data class Cached<T>(val value: T, val at: Long)
    private val tokenListCache = ConcurrentHashMap<String, Cached<List<TokenAccountInfo>>>()
    private const val TOKEN_LIST_TTL_MS = 60_000L

    /** Warm the caches for [owner] (call right after the account is picked, off-main). */
    fun prefetch(rpcUrl: String, owner: String) {
        runCatching { tokenAccountsOf(rpcUrl, owner, force = true) }
    }

    /**
     * The owner's token accounts (both programs), cached a minute unless [force].
     * Lenient: a failed call reads as an empty list, right for a screen. But a half
     * answer is never cached: when the second program did not answer, the truncated
     * list sat there sixty seconds and `WalletHealth` scored it as if whole.
     */
    fun tokenAccountsOf(rpcUrl: String, owner: String, force: Boolean = false): List<TokenAccountInfo> {
        val key = "$rpcUrl|$owner"
        val now = System.currentTimeMillis()
        if (!force) tokenListCache[key]?.let { if (now - it.at < TOKEN_LIST_TTL_MS) return it.value }
        val parts = readTokenAccounts(rpcUrl, owner)
        val list = parts.flatMap { it.orEmpty() }
        // Learn the symbols of whatever tokens this wallet holds (one DAS call, cached).
        TokenSymbols.resolve(list.map { it.mint })
        if (parts.none { it == null }) tokenListCache[key] = Cached(list, now)
        return list
    }

    // ---- effects -------------------------------------------------------------

    /**
     * The real balance effects on [owner] and on every wallet in [destinations]: simulate
     * on the cluster, diff SOL and token balances. That shows a payment split across hidden
     * fee or referral accounts. Only the owner's token accounts among [txKeys] are tracked,
     * so the one being drained never falls outside the window. The diff is taken only between
     * two states on the same slot (`result.context.slot`): retry once, else accept a state
     * boxed in by two identical reads ([alignedPre]), else `Unavailable`, which means retry.
     */
    fun simulateEffects(
        rpcUrl: String,
        txBytes: ByteArray,
        owner: String,
        destinations: List<String> = emptyList(),
        txKeys: Set<String>? = null,
        /**
         * Mints the caller expects to receive. A first buy creates the token account in the
         * same transaction and Jupiter hides it in a lookup table, so it is in neither list;
         * from the mint we derive the address and watch it.
         */
        expectMints: List<String> = emptyList(),
    ): SimOutcome {
        val allTokens = tokenAccountsOf(rpcUrl, owner)
        val tokenAccts = when {
            txKeys != null -> allTokens.filter { it.pubkey in txKeys }.take(32)
            else -> allTokens.take(25)
        }
        val tokenPubkeys = tokenAccts.map { it.pubkey }.toSet()
        // Accounts this transaction creates that we do not own yet. Without them the
        // simulation showed SOL leaving and nothing arriving, and the intent check
        // rightly called that a lie.
        val fresh = expectedAtas(owner, expectMints).filter { it !in tokenPubkeys }
        // Candidate destination wallets (excl. the owner and its own token accounts).
        val dests = destinations.asSequence().filter { it != owner && it !in tokenPubkeys }.distinct().take(24).toList()
        Log.i(TAG, "effects: tokenAccts=${tokenAccts.size}/${allTokens.size}, dests=${dests.size}")

        // Pre-state (one getMultipleAccounts) and the simulation are independent:
        // run them concurrently. Fallbacks shrink the tracked set if the node
        // rejects the fat request, so the SOL cost still shows instead of a blanket fail.
        var tracked = Tracked(owner, tokenAccts, dests, fresh)
        val preOrder = tracked.order()
        val preF = async { getAccountsMulti(rpcUrl, preOrder) }

        var sim = simulateValue(rpcUrl, txBytes, tracked.order())
        if (sim == null && fresh.isNotEmpty()) {
            Log.i(TAG, "full simulate failed → retry without the new accounts")
            tracked = tracked.copy(fresh = emptyList())
            sim = simulateValue(rpcUrl, txBytes, tracked.order())
        }
        if (sim == null && (tokenAccts.isNotEmpty() || dests.isNotEmpty())) {
            Log.i(TAG, "full simulate failed → retry SOL accounts only")
            tracked = tracked.copy(tokens = emptyList(), fresh = emptyList())
            sim = simulateValue(rpcUrl, txBytes, tracked.order())
        }
        if (sim == null && dests.isNotEmpty()) {
            Log.i(TAG, "retry owner-only")
            tracked = tracked.copy(dests = emptyList(), fresh = emptyList())
            sim = simulateValue(rpcUrl, txBytes, tracked.order())
        }
        var pre = preF.await()
        if (sim == null) { Log.i(TAG, "simulate Unavailable"); return SimOutcome.Unavailable }
        if (pre == null) { Log.i(TAG, "pre-state missing → Unavailable"); return SimOutcome.Unavailable }
        // A refusal is an answer whatever the slot: the node ran it and it failed.
        failureOf(sim)?.let { return it }

        if (pre.slot != sim.slot) {
            Log.i(TAG, "slots differ: pre=${pre.slot} sim=${sim.slot} → realign")
            // The read was ahead of the simulation: simulate again, which a moment later
            // almost certainly sits on the same slot or past it.
            if (pre.slot != null && sim.slot != null && pre.slot > sim.slot) {
                sim = simulateValue(rpcUrl, txBytes, tracked.order()) ?: return SimOutcome.Unavailable
                failureOf(sim)?.let { return it }
            }
            if (pre.slot != sim.slot) {
                val again = getAccountsMulti(rpcUrl, preOrder) ?: return SimOutcome.Unavailable
                pre = alignedPre(pre, again, sim.slot, preOrder)
                    ?: run { Log.i(TAG, "slots never aligned: pre=${pre.slot}/${again.slot} sim=${sim.slot} → Unavailable"); return SimOutcome.Unavailable }
            }
        }
        return effectsOf(pre, sim, tracked, allTokens, { TokenSymbols.symbol(it) }) { JupiterTokens.cached(it)?.decimals ?: 6 }
    }

    /** A simulation the node ran and saw fail, already in the shape that flows downstream. */
    private fun failureOf(sim: Sim): SimOutcome? =
        if (sim.value.isNull("err")) null
        else SimOutcome.Failed(sim.value.get("err").toString()).also { Log.i(TAG, "simulate Failed: ${it.err}") }

    /** I conti che la simulazione riporta, nell'ordine in cui li riporta. */
    internal data class Tracked(val owner: String, val tokens: List<TokenAccountInfo>, val dests: List<String>, val fresh: List<String>) {
        fun order(): List<String> = listOf(owner) + tokens.map { it.pubkey } + dests + fresh
    }

    /** The starting state: the accounts read, and the slot the chain read them at. */
    internal data class PreState(val slot: Long?, val accounts: Map<String, AcctPre>)

    /** A simulation that reached the node: its `value`, and the slot it ran on. */
    internal data class Sim(val slot: Long?, val value: JSONObject)

    /**
     * Which starting read can be subtracted from a simulation at [simSlot], given
     * [first] and [second] taken before and after. Clean answer: the second, if it sits
     * exactly on the simulation's slot. Else the first, if the two reads box the
     * simulation in and every account in [keys] is identical in both: a state that did
     * not move on either side was that state in the middle. Otherwise null, `Unavailable`.
     */
    internal fun alignedPre(first: PreState, second: PreState, simSlot: Long?, keys: List<String>): PreState? {
        if (simSlot == null) return null
        if (second.slot == simSlot) return second
        val a = first.slot ?: return null
        val c = second.slot ?: return null
        if (a > simSlot || simSlot > c) return null
        if (keys.any { first.accounts[it] != second.accounts[it] }) return null
        return first.copy(slot = simSlot)
    }

    /**
     * The part without network: from a starting state and a simulation, the diffs.
     * Lives alone so a JSON file can test it, and with it the drain gate downstream.
     * Two things make it answer `Unavailable` before subtracting: slots that differ,
     * and an owner missing from the read. Both used to be silences that became `Ok`.
     */
    internal fun effectsOf(
        pre: PreState,
        sim: Sim,
        tracked: Tracked,
        allTokens: List<TokenAccountInfo>,
        symbol: (String) -> String,
        decimals: (String) -> Int,
    ): SimOutcome {
        val owner = tracked.owner
        val value = sim.value
        if (!value.isNull("err")) return SimOutcome.Failed(value.get("err").toString())
        if (pre.slot == null || sim.slot == null || pre.slot != sim.slot) { Log.i(TAG, "slot mismatch pre=${pre.slot} sim=${sim.slot} → Unavailable"); return SimOutcome.Unavailable }
        // Without the starting state there is nothing to subtract from. A failed read once
        // became an empty map and everything went quiet: no SOL pre-balance, no owner
        // diff, and the drain gate in `RiskEngine.assessEffects` skips coins with no
        // starting balance, so DRAINS_BALANCE never fired and this returned `Ok`.
        // `Unavailable` means "retry", distinct from `Failed`, "the node ran it and it broke".
        val accts = pre.accounts
        if (owner !in accts) { Log.i(TAG, "pre-state missing → Unavailable"); return SimOutcome.Unavailable }
        // The same for every account the simulation reports: a diff against a number
        // that was never read is invented.
        if (tracked.order().any { it !in accts }) { Log.i(TAG, "pre-state incomplete → Unavailable"); return SimOutcome.Unavailable }

        val computeUnits = if (value.isNull("unitsConsumed")) null else value.optLong("unitsConsumed")
        val logCount = value.optJSONArray("logs")?.length() ?: 0
        // Owner's pre-balances by mint: what "you send 95% of your SOL" is measured against.
        // Only from this read. Accounts not read used to fall back on the cached list, up
        // to a minute old, and that number fed the drain gate's denominator. An untracked
        // coin has no diff, so the gate has nothing to ask it.
        val preBalances = HashMap<String, Long>()
        accts[owner]?.lamports?.let { preBalances[com.clearsign.core.NATIVE_SOL_MINT] = it }
        allTokens.forEach { ta -> accts[ta.pubkey]?.let { preBalances[ta.mint] = (preBalances[ta.mint] ?: 0L) + (it.tokenAmount ?: 0L) } }
        val accounts = value.optJSONArray("accounts") ?: return SimOutcome.Ok(emptyList(), computeUnits, logCount, preBalances)
        val deltas = ArrayList<com.clearsign.core.BalanceDelta>()

        // index 0 = owner SOL
        accts[owner]?.lamports?.let { ownerPre ->
            accounts.optJSONObject(0)?.let { acc ->
                val d = acc.optLong("lamports", ownerPre) - ownerPre
                if (d != 0L) deltas.add(com.clearsign.core.BalanceDelta(owner, com.clearsign.core.NATIVE_SOL_MINT, "SOL", 9, d))
            }
        }
        // next: owner's token accounts. A tracked account the read did not find
        // (lamports 0) is an account that no longer exists: it holds nothing.
        tracked.tokens.forEachIndexed { i, ta ->
            val preAmt = accts[ta.pubkey]?.tokenAmount ?: 0L
            val acc = accounts.optJSONObject(i + 1)
            val post = acc?.let { tokenAmountFromAccountData(it) } ?: preAmt
            val d = post - preAmt
            if (d != 0L) deltas.add(com.clearsign.core.BalanceDelta(owner, ta.mint, symbol(ta.mint), ta.decimals, d))
        }
        // finally: destination wallets — their SOL change reveals the split
        val destBase = 1 + tracked.tokens.size
        val freshBase = destBase + tracked.dests.size
        tracked.dests.forEachIndexed { i, addr ->
            val preLam = accts[addr]?.lamports ?: return@forEachIndexed
            val acc = accounts.optJSONObject(destBase + i) ?: return@forEachIndexed
            val d = acc.optLong("lamports", preLam) - preLam
            // pre == 0 → the account didn't exist before this tx: its SOL gain is
            // rent to *create* it (ATA/PDA), not a payment to someone's wallet.
            if (d != 0L) deltas.add(com.clearsign.core.BalanceDelta(addr, com.clearsign.core.NATIVE_SOL_MINT, "SOL", 9, d, createdAccount = preLam == 0L))
        }
        // Token accounts born inside this transaction count only if the simulated account
        // says in its own bytes that we own it: mint at offset 0, owner at 32. The account
        // claims us, we do not assume.
        tracked.fresh.forEachIndexed { i, addr ->
            val acc = accounts.optJSONObject(freshBase + i) ?: return@forEachIndexed
            val before = accts[addr]?.tokenAmount ?: 0L
            val t = tokenAccountFrom(acc, decimals) ?: return@forEachIndexed
            if (t.owner != owner) return@forEachIndexed
            val d = t.amount - before
            if (d == 0L) return@forEachIndexed
            // Already counted if we were tracking this mint through an account we
            // owned before the transaction.
            if (tracked.tokens.any { it.mint == t.mint }) return@forEachIndexed
            deltas.add(com.clearsign.core.BalanceDelta(owner, t.mint, symbol(t.mint), t.decimals, d))
        }
        Log.i(TAG, "effects OK: deltas=${deltas.size}, cu=$computeUnits, dests=${tracked.dests.size}, new=${tracked.fresh.size}, slot=${sim.slot}")
        return SimOutcome.Ok(deltas, computeUnits, logCount, preBalances)
    }

    /**
     * Where a coin would land: the owner's associated token account under both token
     * programs, because which one the mint uses costs a round trip to learn.
     */
    private fun expectedAtas(owner: String, mints: List<String>): List<String> {
        if (mints.isEmpty()) return emptyList()
        val o = Base58.decodePubkey(owner) ?: return emptyList()
        val out = ArrayList<String>()
        for (m in mints.distinct().take(4)) {
            val mint = Base58.decodePubkey(m) ?: continue
            for (prog in listOf(TOKEN_PROGRAM, TOKEN_2022)) {
                runCatching { Base58.encode(Pda.associatedTokenAddress(o, mint, Base58.decode(prog))) }.getOrNull()?.let { out.add(it) }
            }
        }
        return out
    }

    /** A simulated SPL token account, read out of its own bytes. */
    private data class TokenAcct(val mint: String, val owner: String, val amount: Long, val decimals: Int)

    /**
     * Decode a token account from simulation data. Both Token and Token-2022: mint at 0,
     * owner at 32, amount as little-endian u64 at 64. Decimals live on the mint, so the
     * symbol table answers, with a sane guess as fallback.
     */
    private fun tokenAccountFrom(acc: JSONObject, decimals: (String) -> Int): TokenAcct? {
        val b64 = acc.optJSONArray("data")?.optString(0) ?: return null
        val bytes = try { B64.decode(b64) } catch (_: Exception) { return null }
        if (bytes.size < 72) return null
        val ownerProg = acc.optString("owner")
        if (ownerProg != TOKEN_PROGRAM && ownerProg != TOKEN_2022) return null
        val mint = Base58.encode(bytes.copyOfRange(0, 32))
        val who = Base58.encode(bytes.copyOfRange(32, 64))
        var v = 0L
        for (i in 0 until 8) v = v or ((bytes[64 + i].toLong() and 0xFF) shl (8 * i))
        return TokenAcct(mint, who, v, decimals(mint))
    }

    /** What a raw account read tells us: lamports (0 = doesn't exist) and, for SPL token accounts, the amount. */
    internal data class AcctPre(val lamports: Long, val tokenAmount: Long?)

    /**
     * One getMultipleAccounts (base64) for many accounts; missing ones read as 0.
     * All or nothing: a half read was a half map, and a half map is the number one
     * hole. The node caps at 100 keys and we never ask more than 65, so it is one
     * call and one slot.
     */
    private fun getAccountsMulti(rpcUrl: String, pubkeys: List<String>): PreState? {
        if (pubkeys.isEmpty()) return PreState(null, emptyMap())
        val out = HashMap<String, AcctPre>()
        var slot: Long? = null
        for (chunk in pubkeys.chunked(100)) {
            val addrArr = JSONArray(); chunk.forEach { addrArr.put(it) }
            val params = JSONArray().put(addrArr).put(JSONObject().put("encoding", "base64").put("commitment", COMMITMENT))
            val a = call(rpcUrl, "getMultipleAccounts", params) as? Answer.Answered ?: return null
            val part = preStateOf(a.json, chunk) ?: return null
            if (slot != null && part.slot != slot) return null
            slot = part.slot
            out.putAll(part.accounts)
        }
        return PreState(slot, out)
    }

    /** A getMultipleAccounts body for [pubkeys], as a state with its slot. Null when it is not one. */
    internal fun preStateOf(json: JSONObject, pubkeys: List<String>): PreState? {
        val arr = json.optJSONObject("result")?.optJSONArray("value") ?: return null
        val out = HashMap<String, AcctPre>()
        for (i in pubkeys.indices) {
            val acc = arr.optJSONObject(i)
            out[pubkeys[i]] = AcctPre(acc?.optLong("lamports", 0L) ?: 0L, acc?.let { tokenAmountFromAccountData(it) })
        }
        return PreState(slotOf(json), out)
    }

    /** A simulateTransaction body, as the value with its slot. Null when it is not one. */
    internal fun simOf(json: JSONObject): Sim? {
        val value = json.optJSONObject("result")?.optJSONObject("value") ?: return null
        return Sim(slotOf(json), value)
    }

    /**
     * Resolve a v0 transaction's lookup-table addresses (writable, readonly) with one
     * getMultipleAccounts on the tables. Empty lists when none or unreadable.
     */
    fun resolveLookups(rpcUrl: String, lookups: List<SolanaTx.Lookup>): Pair<List<String>, List<String>> {
        if (lookups.isEmpty()) return emptyList<String>() to emptyList()
        val tables = lookups.map { it.table }.distinct()
        val addrArr = JSONArray(); tables.forEach { addrArr.put(it) }
        val params = JSONArray().put(addrArr).put(JSONObject().put("encoding", "base64").put("commitment", COMMITMENT))
        val resp = post(rpcUrl, "getMultipleAccounts", params) ?: return emptyList<String>() to emptyList()
        val arr = resp.optJSONObject("result")?.optJSONArray("value") ?: return emptyList<String>() to emptyList()
        val entries = HashMap<String, List<String>>()
        for (i in tables.indices) {
            val b64 = arr.optJSONObject(i)?.optJSONArray("data")?.optString(0) ?: continue
            val bytes = runCatching { B64.decode(b64) }.getOrNull() ?: continue
            // Lookup-table layout: 56-byte meta header, then packed 32-byte addresses.
            if (bytes.size < 56) continue
            val n = (bytes.size - 56) / 32
            entries[tables[i]] = List(n) { k -> Base58.encode(bytes.copyOfRange(56 + k * 32, 56 + k * 32 + 32)) }
        }
        val writable = ArrayList<String>(); val readonly = ArrayList<String>()
        for (l in lookups) {
            val e = entries[l.table] ?: continue
            l.writableIndexes.forEach { idx -> e.getOrNull(idx)?.let { writable.add(it) } }
            l.readonlyIndexes.forEach { idx -> e.getOrNull(idx)?.let { readonly.add(it) } }
        }
        return writable to readonly
    }

    private fun simulateValue(rpcUrl: String, txBytes: ByteArray, addresses: List<String>): Sim? {
        val addrArr = JSONArray(); addresses.forEach { addrArr.put(it) }
        val params = JSONArray()
            .put(B64.encode(txBytes))
            .put(
                JSONObject()
                    .put("encoding", "base64")
                    .put("sigVerify", false)
                    .put("replaceRecentBlockhash", true)
                    .put("commitment", COMMITMENT)
                    .put("accounts", JSONObject().put("encoding", "base64").put("addresses", addrArr)),
            )
        val a = call(rpcUrl, "simulateTransaction", params) as? Answer.Answered ?: return null
        return simOf(a.json)
    }

    /**
     * Balances for many accounts at once (the account picker): one read for all SOL
     * balances, token counts fanned out concurrently. Warms the token-list cache too.
     */
    fun assetsSummaryMulti(rpcUrl: String, pubkeys: List<String>): Map<String, Pair<Long?, Int>> {
        val lamF = async { getAccountsMulti(rpcUrl, pubkeys) }
        val tokF = pubkeys.map { pk -> pk to async { tokenAccountsOf(rpcUrl, pk).count { it.amount > 0 } } }
        val lam = lamF.await()?.accounts.orEmpty()
        return tokF.associate { (pk, f) -> pk to (lam[pk]?.lamports to (f.await() ?: 0)) }
    }

    fun getBalance(rpcUrl: String, pubkey: String): Long? {
        val resp = post(rpcUrl, "getBalance", JSONArray().put(pubkey).put(JSONObject().put("commitment", COMMITMENT))) ?: return null
        return resp.optJSONObject("result")?.optLong("value")
    }

    private const val SYSTEM_PROGRAM = "11111111111111111111111111111111"

    /** On-chain "reputation from usage" for a counterparty wallet. */
    data class WalletIntel(
        val sigCount: Int,          // recent signatures seen (capped)
        val capped: Boolean,        // true when there are at least [sigCount] (hit the cap)
        val ageDays: Long?,         // days since the oldest signature in the window, or null
        val lamports: Long?,        // current SOL balance
        val kind: Kind,
    ) {
        enum class Kind { WALLET, TOKEN_ACCOUNT, PROGRAM, PROGRAM_OWNED, EMPTY, UNKNOWN }
        /** A brand-new address with no history is the classic drainer signature. */
        val isBrandNew: Boolean get() = kind != Kind.PROGRAM && sigCount == 0
    }

    private val intelCache = ConcurrentHashMap<String, Cached<WalletIntel>>()
    private const val INTEL_TTL_MS = 5 * 60_000L

    /**
     * Counterparty intelligence: transaction count, age, balance, account kind. "Brand
     * new, no history" against "active for months" is a strong free trust signal: two
     * RPC calls in parallel, cached a few minutes, never on the main thread. With a
     * capped signature window [WalletIntel.ageDays] is a lower bound (the UI says "at least").
     */
    fun walletIntel(rpcUrl: String, address: String): WalletIntel {
        val key = "$rpcUrl|$address"
        val now = System.currentTimeMillis()
        intelCache[key]?.let { if (now - it.at < INTEL_TTL_MS) return it.value }

        val sigF = async { getSignatures(rpcUrl, address, 1000) }
        val acctF = async { getAccountInfo(rpcUrl, address) }
        val sigs = sigF.await()
        val acct = acctF.await()

        val n = sigs?.length() ?: 0
        val oldest = sigs?.let { arr ->
            var t: Long? = null
            for (i in 0 until arr.length()) {
                val bt = arr.optJSONObject(i)?.optLong("blockTime", 0L) ?: 0L
                if (bt > 0L && (t == null || bt < t!!)) t = bt
            }
            t
        }
        val ageDays = oldest?.let { (System.currentTimeMillis() / 1000 - it) / 86_400 }?.coerceAtLeast(0)
        val lamports = acct?.optLong("lamports")
        val owner = acct?.optString("owner")?.takeIf { it.isNotBlank() }
        val executable = acct?.optBoolean("executable") ?: false
        val kind = when {
            acct == null -> WalletIntel.Kind.EMPTY
            executable -> WalletIntel.Kind.PROGRAM
            owner == SYSTEM_PROGRAM -> WalletIntel.Kind.WALLET
            owner == TOKEN_PROGRAM || owner == TOKEN_2022 -> WalletIntel.Kind.TOKEN_ACCOUNT
            owner != null -> WalletIntel.Kind.PROGRAM_OWNED
            else -> WalletIntel.Kind.UNKNOWN
        }
        val intel = WalletIntel(sigCount = n, capped = n >= 1000, ageDays = ageDays, lamports = lamports, kind = kind)
        if (sigs != null || acct != null) intelCache[key] = Cached(intel, now)
        return intel
    }

    private fun getSignatures(rpcUrl: String, address: String, limit: Int): JSONArray? {
        val params = JSONArray().put(address).put(JSONObject().put("limit", limit).put("commitment", "confirmed"))
        val resp = post(rpcUrl, "getSignaturesForAddress", params) ?: return null
        return resp.optJSONArray("result")
    }

    private fun getAccountInfo(rpcUrl: String, address: String): JSONObject? {
        val params = JSONArray().put(address).put(JSONObject().put("encoding", "base64").put("commitment", COMMITMENT))
        val resp = post(rpcUrl, "getAccountInfo", params) ?: return null
        return resp.optJSONObject("result")?.optJSONObject("value") // null when the account doesn't exist
    }

    /**
     * getProgramAccounts with one memcmp filter: find a program's account by an indexed
     * field (a reputation PDA carrying its target) without deriving the PDA. Raw data back.
     */
    fun programAccountsMemcmp(rpcUrl: String, programId: String, offset: Int, valueBase58: String): List<ByteArray> {
        val filter = JSONObject().put("memcmp", JSONObject().put("offset", offset).put("bytes", valueBase58))
        val cfg = JSONObject()
            .put("encoding", "base64")
            .put("commitment", "confirmed")
            .put("filters", JSONArray().put(filter))
        val resp = post(rpcUrl, "getProgramAccounts", JSONArray().put(programId).put(cfg)) ?: return emptyList()
        val arr = resp.optJSONArray("result") ?: return emptyList()
        val out = ArrayList<ByteArray>(arr.length())
        for (i in 0 until arr.length()) {
            val dataArr = arr.optJSONObject(i)?.optJSONObject("account")?.optJSONArray("data") ?: continue
            val b64 = dataArr.optString(0)
            runCatching { B64.decode(b64) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    /**
     * What a wallet holds, or null when the chain could not be asked. The lenient reader
     * turns a failed call into an empty list, right for a screen and wrong for anything
     * that acts: the loop once read a rate-limited node as "holds nothing" and dropped
     * every open position, coins still in the wallet, no stop watching. Both programs
     * must answer. Also reads strangers' wallets (whale card) on the scanner's key, never
     * the agent's. Blocking, two calls: IO only, and only when somebody asked.
     */
    fun tokensOf(rpcUrl: String, owner: String, force: Boolean = false): List<TokenAccountInfo>? {
        val key = "$rpcUrl|$owner"
        val now = System.currentTimeMillis()
        // This cache was written and never read, so every call was two fresh reads even
        // a moment after the same answer: a tick reconciles the book and a sale rereads
        // the same wallet right after. Whoever needs the exact number asks with [force].
        if (!force) tokenListCache[key]?.let { if (now - it.at < TOKEN_LIST_TTL_MS) return it.value }
        val parts = readTokenAccounts(rpcUrl, owner)
        if (parts.any { it == null }) return null
        val list = parts.flatMap { it.orEmpty() }
        TokenSymbols.resolve(list.map { it.mint })
        tokenListCache[key] = Cached(list, now)
        return list
    }

    /* One list per token program, in order; null for a program whose call did not come back. */
    private fun readTokenAccounts(rpcUrl: String, owner: String): List<List<TokenAccountInfo>?> {
        // The two token programs in a row, on the caller's thread. They used to be two
        // more tasks on the same fixed pool, waited on with a blocking get; with six
        // wallets all six threads waited for work that could never run, and every
        // simulation in the process hung. One extra round trip, no deadlock.
        return listOf(TOKEN_PROGRAM, TOKEN_2022).map { program ->
            runCatching {
                val params = JSONArray()
                    .put(owner)
                    .put(JSONObject().put("programId", program))
                    .put(JSONObject().put("encoding", "jsonParsed").put("commitment", COMMITMENT))
                val resp = post(rpcUrl, "getTokenAccountsByOwner", params) ?: return@runCatching null
                val arr = resp.optJSONObject("result")?.optJSONArray("value") ?: return@runCatching null
                val out = ArrayList<TokenAccountInfo>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    parseTokenAccount(obj)?.let { out.add(it) }
                }
                out
            }.getOrNull()
        }
    }

    // Token symbols from Helius DAS (mint → symbol). A symbol is trusted only when short
    // and printable, so a scam token cannot impersonate "USDC" through metadata alone.
    /** What DAS knows about a mint: symbol, name, logo URL, and whether it is NFT-like. */
    data class DasAsset(val symbol: String?, val name: String?, val image: String?, val isNft: Boolean)

    /** Metadata for [mints] via DAS `getAssetBatch` (Helius only; one call per 100 mints, best effort). */
    fun dasAssets(mints: List<String>): Map<String, DasAsset> {
        // Not `customRpc`: an own node in Settings must not lose the coin names, so
        // DAS stays on the Helius lane.
        val das = Rpc.dasUrl() ?: return emptyMap()
        val out = HashMap<String, DasAsset>()
        mints.chunked(100).forEach { chunk ->
            val params = JSONObject().put("ids", JSONArray(chunk))
            val resp = call(das, "getAssetBatch", params) as? Answer.Answered ?: return@forEach
            val arr = resp.json.optJSONArray("result") ?: return@forEach
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val id = a.optString("id"); if (id.isEmpty()) continue
                val content = a.optJSONObject("content")
                val meta = content?.optJSONObject("metadata")
                val ti = a.optJSONObject("token_info")
                val symRaw = (ti?.optString("symbol")?.takeIf { it.isNotBlank() } ?: meta?.optString("symbol"))?.trim().orEmpty()
                val sym = symRaw.takeIf { it.length in 1..10 && it.all { c -> c.isLetterOrDigit() || c in "._-\$" } }
                val name = meta?.optString("name")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 }
                val files = content?.optJSONArray("files")?.optJSONObject(0)
                val image = listOfNotNull(
                    content?.optJSONObject("links")?.optString("image"),
                    files?.optString("cdn_uri"), files?.optString("uri"),
                ).firstOrNull { it.startsWith("https://") }
                val iface = a.optString("interface")
                val isNft = iface.contains("NFT", ignoreCase = true) ||
                    (ti != null && ti.optInt("decimals", -1) == 0 && ti.optLong("supply", -1L) == 1L)
                out[id] = DasAsset(sym, name, image, isNft)
            }
        }
        return out
    }

    // ---- building our own transactions ---------------------------------------

    data class Blockhash(val hash: String, val lastValidBlockHeight: Long)

    /** A recent blockhash to build a transaction on (confirmed, so it's valid on every node). */
    fun latestBlockhash(rpcUrl: String): Blockhash? {
        val resp = post(rpcUrl, "getLatestBlockhash", JSONArray().put(JSONObject().put("commitment", "confirmed"))) ?: return null
        val v = resp.optJSONObject("result")?.optJSONObject("value") ?: return null
        val hash = v.optString("blockhash").takeIf { it.isNotEmpty() } ?: return null
        return Blockhash(hash, v.optLong("lastValidBlockHeight"))
    }

    /**
     * Median priority fee (µlamports per CU) the network recently paid on these
     * accounts, last ~150 slots: the yardstick for "you're paying 20× the going rate".
     * Null when the node cannot answer.
     */
    fun recentPrioritizationFees(rpcUrl: String, accounts: List<String> = emptyList()): Long? {
        // The whole-network yardstick only when nobody asked about specific accounts:
        // on a busy account the rate differs, and a yardstick off by ten would call a
        // normal fee excessive.
        if (accounts.isEmpty()) Archive.chain(15 * 60_000L)?.takeIf { !it.isNull("fee") }?.optLong("fee", -1L)?.takeIf { it >= 0 }?.let { return it }
        val params = JSONArray().apply { if (accounts.isNotEmpty()) put(JSONArray(accounts.take(128))) }
        val resp = post(rpcUrl, "getRecentPrioritizationFees", params) ?: return null
        val arr = resp.optJSONArray("result") ?: return null
        val fees = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optLong("prioritizationFee", -1L)?.takeIf { f -> f >= 0 } }
        if (fees.isEmpty()) return null
        return fees.sorted()[fees.size / 2]
    }

    /** A raw account: its bytes, owning program and lamports. Null when it doesn't exist / unreachable. */
    data class RawAccount(val data: ByteArray, val owner: String, val lamports: Long)

    fun getAccountInfoRaw(rpcUrl: String, address: String): RawAccount? {
        val v = getAccountInfo(rpcUrl, address) ?: return null
        val b64 = v.optJSONArray("data")?.optString(0) ?: return null
        val bytes = runCatching { B64.decode(b64) }.getOrNull() ?: return null
        return RawAccount(bytes, v.optString("owner"), v.optLong("lamports"))
    }

    private fun tokenAmountFromAccountData(acc: JSONObject): Long? {
        val dataArr = acc.optJSONArray("data") ?: return null
        val b64 = dataArr.optString(0)
        val bytes = try { B64.decode(b64) } catch (_: Exception) { return null }
        if (bytes.size < 72) return null // SPL token account layout: amount is u64 LE at offset 64
        val ownerProg = acc.optString("owner")
        if (ownerProg != TOKEN_PROGRAM && ownerProg != TOKEN_2022) return null
        var v = 0L
        for (i in 0 until 8) v = v or ((bytes[64 + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    /** Result of a send: the signature, or the node's reason for rejecting it. */
    data class SendOutcome(val signature: String?, val error: String?)

    /**
     * Submit a signed transaction. A deterministic rejection (expired blockhash,
     * insufficient funds) is [SendOutcome.error], not retried. "Not sent" only when
     * true: a transport falling after the node propagated the bytes once left the
     * transaction alive while the answer said otherwise. The signature is in the bytes,
     * so before any retry the chain is asked; "already processed" means landed.
     */
    fun send(rpcUrl: String, signedTx: ByteArray): SendOutcome {
        val sig = SolanaTx.firstSignature(signedTx)
        val method = "sendTransaction"
        val params = JSONArray().put(B64.encode(signedTx)).put(JSONObject().put("encoding", "base64").put("preflightCommitment", COMMITMENT))
        for (p in Rpc.lanes(rpcUrl, method)) {
            var attempt = 0
            while (attempt < 2) {
                val t0 = System.currentTimeMillis()
                val r = Rpc.withLane(p) { postOnce(p.url, method, params) } ?: break
                val (outcome, err) = outcomeOf(r)
                Rpc.record(p, method, outcome, System.currentTimeMillis() - t0, err?.optString("message")?.take(80))
                when (outcome) {
                    RpcPool.Outcome.OK -> return SendOutcome((r as Http.Ok).json.optString("result").ifEmpty { sig ?: "" }, null)
                    RpcPool.Outcome.REFUSED -> {
                        if (sig != null && err != null && isAlreadyProcessed(err)) return SendOutcome(sig, null)
                        return SendOutcome(null, err?.let { rejectionReason(it) } ?: "rifiutata dal nodo")
                    }
                    // A 5xx or a fallen transport can arrive after the node propagated the
                    // bytes: ask the chain before retrying.
                    RpcPool.Outcome.SERVER, RpcPool.Outcome.TRANSPORT -> {
                        attempt++; backoff(attempt)
                        if (sig != null && landed(rpcUrl, sig)) return SendOutcome(sig, null)
                    }
                    // Rate limit, spent month, unsupported method: it never reached the runtime.
                    else -> break
                }
            }
        }
        return SendOutcome(null, "network unreachable")
    }

    /** The node's way of saying the signature is already on chain. */
    internal fun isAlreadyProcessed(error: JSONObject): Boolean {
        val msg = error.optString("message").lowercase()
        return msg.contains("already been processed") || msg.contains("alreadyprocessed") ||
            error.optJSONObject("data")?.opt("err")?.toString()?.contains("AlreadyProcessed") == true
    }

    /** Why a node refused a send, in the words a person can act on. */
    internal fun rejectionReason(error: JSONObject): String =
        error.optJSONObject("data")?.optJSONArray("logs")?.let { logs ->
            // The last program log usually carries the human reason ("insufficient lamports").
            (0 until logs.length()).map { logs.optString(it) }.lastOrNull { it.contains("failed") || it.contains("insufficient") || it.contains("Error") }
        } ?: error.optString("message").takeIf { it.isNotEmpty() } ?: "rifiutata dal nodo"

    /**
     * What the chain says of [signature], once: true landed, false failed on chain, null
     * unknown or unreachable. "The node took it" and "it went through" are two sentences.
     */
    fun verdictOf(rpcUrl: String, signature: String): Boolean? {
        val resp = post(rpcUrl, "getSignatureStatuses", JSONArray().put(JSONArray().put(signature)).put(JSONObject().put("searchTransactionHistory", true)))
        val value = resp?.optJSONObject("result")?.optJSONArray("value") ?: return null
        return chainVerdict(value.optJSONObject(0))
    }

    /** Pure: one status object from getSignatureStatuses, read as a verdict. */
    internal fun chainVerdict(st: JSONObject?): Boolean? {
        if (st == null) return null
        if (!st.isNull("err")) return false
        val c = st.optString("confirmationStatus")
        return if (c == "confirmed" || c == "finalized") true else null
    }

    /** Whether the chain knows [signature] at all, at any commitment. Unknown network → false. */
    private fun landed(rpcUrl: String, signature: String): Boolean {
        val resp = post(rpcUrl, "getSignatureStatuses", JSONArray().put(JSONArray().put(signature)).put(JSONObject().put("searchTransactionHistory", false)))
        return resp?.optJSONObject("result")?.optJSONArray("value")?.optJSONObject(0) != null
    }

    /**
     * Pauses between confirmation checks. Fixed 1.5 s for 30 s was twenty calls per
     * confirmation, each with its fallback chain, and the first ones almost always too
     * early. Growing steps: seven calls worst case, three or four normally, same window.
     * [confirmed] polls with them: true when confirmed or finalized without error, false
     * on error or timeout, which callers must read as "not landed".
     */
    private val CONFIRM_STEPS_MS = longArrayOf(1_000, 1_000, 2_000, 3_000, 5_000, 8_000, 10_000)

    fun confirmed(rpcUrl: String, signature: String, timeoutMs: Long = 30_000L): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        var step = 0
        while (System.currentTimeMillis() < until) {
            // Only the last attempt searches history: on a transaction just sent it means
            // nothing, and on many nodes that option costs far more.
            val deep = step >= CONFIRM_STEPS_MS.size - 1
            val resp = post(rpcUrl, "getSignatureStatuses", JSONArray().put(JSONArray().put(signature)).put(JSONObject().put("searchTransactionHistory", deep)))
            val st = resp?.optJSONObject("result")?.optJSONArray("value")?.optJSONObject(0)
            if (st != null) {
                if (!st.isNull("err")) return false
                val c = st.optString("confirmationStatus")
                if (c == "confirmed" || c == "finalized") return true
            }
            val wait = CONFIRM_STEPS_MS[step.coerceAtMost(CONFIRM_STEPS_MS.size - 1)]
            step++
            try { Thread.sleep(wait) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    /**
     * SOL in native stake accounts, which the token list never shows and Jupiter files
     * under DeFi. Found by withdraw authority, which is what "yours" means for a stake.
     */
    data class StakeAccount(
        val pubkey: String, val lamports: Long, val stakedLamports: Long, val voter: String?,
        val activationEpoch: Long, val deactivationEpoch: Long,
    ) {
        fun state(epoch: Long): String = when {
            voter == null -> "inactive"
            deactivationEpoch != Long.MAX_VALUE && epoch > deactivationEpoch -> "inactive"
            deactivationEpoch != Long.MAX_VALUE -> "deactivating"
            epoch > activationEpoch -> "active"
            else -> "activating"
        }
    }

    /**
     * Two network constants, remembered six hours. An epoch lasts about two days and
     * inflation moves once a year; they were fetched on every load for a staker.
     */
    private val netConst = ConcurrentHashMap<String, Cached<Double>>()
    private const val NET_CONST_TTL_MS = 6 * 3600_000L

    private fun netConstant(key: String, read: () -> Double?): Double? {
        val now = System.currentTimeMillis()
        netConst[key]?.let { if (now - it.at < NET_CONST_TTL_MS) return it.value }
        val v = read() ?: return null
        netConst[key] = Cached(v, now)
        return v
    }

    /** The archive before the chain: the epoch is the same for everyone and the worker publishes it every ten minutes. */
    fun epoch(rpcUrl: String): Long? = netConstant("epoch|$rpcUrl") {
        Archive.chain(NET_CONST_TTL_MS)?.optLong("epoch", 0L)?.takeIf { it > 0 }?.toDouble()
            ?: post(rpcUrl, "getEpochInfo", JSONArray())?.optJSONObject("result")?.optLong("epoch")?.toDouble()
    }?.toLong()

    fun stakeAccounts(rpcUrl: String, owner: String): List<StakeAccount> {
        // `dataSize` before the memcmp, or this scans the whole Stake program: an unfiltered
        // `getProgramAccounts` is the most expensive call there is and the classic way to
        // get a key suspended, and it ran on every wallet load. A stake account is 200
        // bytes; with the filter the node searches an index.
        val params = JSONArray().put("Stake11111111111111111111111111111111111111").put(
            JSONObject().put("encoding", "jsonParsed").put(
                "filters",
                JSONArray()
                    .put(JSONObject().put("dataSize", 200))
                    .put(JSONObject().put("memcmp", JSONObject().put("offset", 44).put("bytes", owner))),
            ),
        )
        val arr = post(rpcUrl, "getProgramAccounts", params)?.optJSONArray("result") ?: return emptyList()
        val out = ArrayList<StakeAccount>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val info = a.optJSONObject("account")?.optJSONObject("data")?.optJSONObject("parsed")?.optJSONObject("info") ?: continue
            val d = info.optJSONObject("stake")?.optJSONObject("delegation")
            fun big(s: String?): Long = s?.toBigIntegerOrNull()?.let { if (it > java.math.BigInteger.valueOf(Long.MAX_VALUE)) Long.MAX_VALUE else it.toLong() } ?: Long.MAX_VALUE
            out += StakeAccount(
                pubkey = a.optString("pubkey"), lamports = a.optJSONObject("account")?.optLong("lamports") ?: 0L,
                stakedLamports = d?.optString("stake")?.toLongOrNull() ?: 0L, voter = d?.optString("voter")?.takeIf { it.isNotEmpty() },
                activationEpoch = d?.optString("activationEpoch")?.toLongOrNull() ?: Long.MAX_VALUE,
                deactivationEpoch = big(d?.optString("deactivationEpoch")),
            )
        }
        return out
    }

    /** The network's inflation, validator share, as a fraction per year. */
    fun inflationRate(rpcUrl: String): Double? = netConstant("inflation|$rpcUrl") {
        Archive.chain(NET_CONST_TTL_MS)?.optDouble("inflation", Double.NaN)?.takeIf { !it.isNaN() && it > 0 }
            ?: post(rpcUrl, "getInflationRate", JSONArray())?.optJSONObject("result")?.optDouble("validator")?.takeIf { !it.isNaN() && it > 0 }
    }

    /** Total supply of a mint, raw. */
    /** The raw bytes of one account, or null. */
    /** Whether [pubkey] exists on chain: true, false, or null when nobody could be asked. */
    fun accountExists(rpcUrl: String, pubkey: String): Boolean? {
        val params = JSONArray().put(pubkey).put(JSONObject().put("encoding", "base64").put("dataSlice", JSONObject().put("offset", 0).put("length", 0)))
        val a = call(rpcUrl, "getAccountInfo", params) as? Answer.Answered ?: return null
        val result = a.json.optJSONObject("result") ?: return null
        return !result.isNull("value")
    }

    fun accountBytes(rpcUrl: String, pubkey: String): ByteArray? {
        val v = post(rpcUrl, "getAccountInfo", JSONArray().put(pubkey).put(JSONObject().put("encoding", "base64")))
            ?.optJSONObject("result")?.optJSONObject("value") ?: return null
        val b64 = v.optJSONArray("data")?.optString(0) ?: return null
        return runCatching { B64.decode(b64) }.getOrNull()
    }

    /** The raw bytes of many accounts at once, missing ones left out. Blocking: call on IO. */
    fun accountsBytes(rpcUrl: String, pubkeys: List<String>): Map<String, ByteArray> {
        if (pubkeys.isEmpty()) return emptyMap()
        val out = HashMap<String, ByteArray>()
        pubkeys.distinct().chunked(64).forEach { chunk ->
            val addrArr = JSONArray(); chunk.forEach { addrArr.put(it) }
            val params = JSONArray().put(addrArr).put(JSONObject().put("encoding", "base64"))
            val arr = post(rpcUrl, "getMultipleAccounts", params)?.optJSONObject("result")?.optJSONArray("value") ?: return@forEach
            for (i in chunk.indices) {
                val b64 = arr.optJSONObject(i)?.optJSONArray("data")?.optString(0) ?: continue
                runCatching { B64.decode(b64) }.getOrNull()?.let { out[chunk[i]] = it }
            }
        }
        return out
    }

    /** Every account of [programId] of exactly [size] bytes whose field at [offset] is [valueBase58], as pubkey to bytes. */
    fun programAccountsSized(rpcUrl: String, programId: String, size: Int, offset: Int, valueBase58: String): Map<String, ByteArray> {
        val params = JSONArray().put(programId).put(
            JSONObject().put("encoding", "base64").put(
                "filters",
                JSONArray().put(JSONObject().put("dataSize", size))
                    .put(JSONObject().put("memcmp", JSONObject().put("offset", offset).put("bytes", valueBase58))),
            ),
        )
        val arr = post(rpcUrl, "getProgramAccounts", params)?.optJSONArray("result") ?: return emptyMap()
        val out = HashMap<String, ByteArray>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("pubkey").takeIf { it.isNotEmpty() } ?: continue
            val b64 = o.optJSONObject("account")?.optJSONArray("data")?.optString(0) ?: continue
            runCatching { B64.decode(b64) }.getOrNull()?.let { out[key] = it }
        }
        return out
    }

    /** The SKR this wallet has staked with the Seeker Guardians, or null when it has none or the node did not answer. */
    fun skrStake(rpcUrl: String, owner: String): SkrStake.Position? {
        val params = JSONArray().put(SkrStake.PROGRAM).put(
            JSONObject().put("encoding", "base64").put(
                "filters",
                JSONArray().put(JSONObject().put("dataSize", SkrStake.USER_STAKE_SIZE))
                    .put(JSONObject().put("memcmp", JSONObject().put("offset", SkrStake.OWNER_OFFSET).put("bytes", owner))),
            ),
        )
        val arr = post(rpcUrl, "getProgramAccounts", params)?.optJSONArray("result") ?: return null
        val b64 = arr.optJSONObject(0)?.optJSONObject("account")?.optJSONArray("data")?.optString(0) ?: return null
        val account = arr.optJSONObject(0)?.optString("pubkey").orEmpty()
        val user = runCatching { B64.decode(b64) }.getOrNull() ?: return null
        val config = accountBytes(rpcUrl, SkrStake.CONFIG) ?: return null
        return SkrStake.decode(user, config)?.takeIf { it.rawSkr > 0 }?.copy(account = account)
    }

    // ---- transport -----------------------------------------------------------

    private sealed interface Http {
        data class Ok(val json: JSONObject) : Http     // 2xx, parsed; may still carry a JSON-RPC "error"
        data class Status(val code: Int) : Http        // non-2xx
        data object Transport : Http                   // timeout / DNS / parse
    }

    /**
     * What the network answered: three things, not two. `post()` used to return a body
     * or nothing, and the body could be the last error seen, a rate limit on the first
     * node while the other two died on transport. Callers read "the chain said no" where
     * the truth was "nobody answered". A refusal is an answer; a silence is a retry.
     */
    sealed interface Answer {
        /** A node answered with a result. [slot] is the one in `result.context`, when present. */
        data class Answered(val json: JSONObject, val slot: Long?) : Answer
        /** A node ran it and said no. Deterministic: another node would say the same. */
        data class Refused(val body: JSONObject) : Answer {
            val code: Int get() = body.optJSONObject("error")?.optInt("code") ?: 0
            val message: String get() = body.optJSONObject("error")?.optString("message").orEmpty()
        }
        /** Nobody answered: fallen transport, 5xx, or rate limit on every node. */
        data object Unreachable : Answer
    }

    /** The slot a node writes next to its answer, when it does. */
    internal fun slotOf(json: JSONObject): Long? =
        json.optJSONObject("result")?.optJSONObject("context")?.takeIf { it.has("slot") && !it.isNull("slot") }?.optLong("slot")

    /** A JSON-RPC error worth retrying elsewhere: it speaks of the node, not of the call. */
    internal fun isTransient(error: JSONObject): Boolean = RpcPool.classify(200, error) != RpcPool.Outcome.REFUSED

    /**
     * POST along the pool's lanes: the own node first, then keyed providers in this
     * install's order, the last resorts at the end. A fallen transport or a 5xx retries
     * once in place; a rate limit, a spent month or an unsupported method move to the
     * next lane and the pool notes it. A deterministic error is [Answer.Refused] at once,
     * another node would say the same. Lanes exhausted: [Answer.Unreachable].
     */
    fun call(rpcUrl: String, method: String, params: Any): Answer {
        for (p in Rpc.lanes(rpcUrl, method)) {
            var attempt = 0
            while (attempt < 2) {
                val t0 = System.currentTimeMillis()
                val r = Rpc.withLane(p) { postOnce(p.url, method, params) } ?: break
                val (outcome, err) = outcomeOf(r)
                Rpc.record(p, method, outcome, System.currentTimeMillis() - t0, err?.optString("message")?.take(80))
                when (outcome) {
                    RpcPool.Outcome.OK -> { val j = (r as Http.Ok).json; return Answer.Answered(j, slotOf(j)) }
                    RpcPool.Outcome.REFUSED -> return Answer.Refused((r as Http.Ok).json)
                    RpcPool.Outcome.SERVER, RpcPool.Outcome.TRANSPORT -> { attempt++; backoff(attempt) }
                    else -> break
                }
            }
        }
        return Answer.Unreachable
    }

    /** Cosa e' successo, nelle parole del pool, piu' l'errore JSON-RPC se c'era. */
    private fun outcomeOf(r: Http): Pair<RpcPool.Outcome, JSONObject?> = when (r) {
        is Http.Ok -> { val err = r.json.optJSONObject("error"); RpcPool.classify(200, err) to err }
        is Http.Status -> RpcPool.classify(r.code, null) to null
        Http.Transport -> RpcPool.classify(null, null) to null
    }

    /** [call] for the readers that only want a body: a refusal is a body with `error`, a silence is null. */
    private fun post(rpcUrl: String, method: String, params: JSONArray): JSONObject? = when (val a = call(rpcUrl, method, params)) {
        is Answer.Answered -> a.json
        is Answer.Refused -> a.body
        Answer.Unreachable -> null
    }

    private fun backoff(attempt: Int) {
        try { Thread.sleep(250L * attempt) } catch (_: InterruptedException) {}
    }

    private fun postOnce(rpcUrl: String, method: String, params: Any): Http = try {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params).toString()
        val conn = (URL(rpcUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept-Encoding", "gzip")
            connectTimeout = 8000; readTimeout = 15000
        }
        OutputStreamWriter(conn.outputStream).use { it.write(body) }
        val code = conn.responseCode
        if (code !in 200..299) { conn.disconnect(); Http.Status(code) } else {
            val raw = conn.inputStream
            val stream = if (conn.contentEncoding.equals("gzip", ignoreCase = true)) java.util.zip.GZIPInputStream(raw) else raw
            val text = stream.bufferedReader().use { it.readText() }
            conn.disconnect()
            Http.Ok(JSONObject(text))
        }
    } catch (_: Exception) {
        Http.Transport
    }
}

/**
 * The JVM's Base64, not Android's, so the pure part of this file runs in tests where
 * `android.util.Base64` is a stub. The MIME decoder ignores newlines like `Base64.DEFAULT`.
 */
private object B64 {
    fun encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
    fun decode(text: String): ByteArray = java.util.Base64.getMimeDecoder().decode(text)
}
