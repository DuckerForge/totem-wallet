package com.clearsign.app

import android.util.Base64
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
 * Minimal Solana JSON-RPC client: simulate (to verify a transaction the way the
 * network will actually run it — this handles v0 + address-lookup-tables + SPL
 * token programs server-side, which the on-device decoder cannot) and send.
 *
 * All calls are blocking; never invoke on the main thread.
 *
 * Every read uses `commitment: processed`, the same state the simulation runs
 * against, so pre/post balance diffs never include a phantom delta from a
 * deposit that landed but is not yet finalized.
 */
object SolanaRpc {

    private const val TAG = "ClearSign-RPC"

    /** Dedicated mainnet RPC (Helius) for reliable simulation + token balances,
     *  free of public rate limits. Injected from local.properties at build time
     *  (never committed); when blank, the public nodes are used. */
    @Volatile var customRpc: String? = BuildConfig.HELIUS_RPC_URL.takeIf { it.isNotBlank() }

    // mainnet-beta supports every method (token + simulate); publicnode is the
    // rate-limit escape hatch (great for simulate/balance, blocks token calls).
    private const val MAINNET_PRIMARY = "https://api.mainnet-beta.solana.com"
    private const val MAINNET_FALLBACK = "https://solana-rpc.publicnode.com"

    private const val COMMITMENT = "processed"

    /** Small pool for the fan-out reads; bounded so a 10-tx bundle can't spawn 60 threads. */
    private val pool = Executors.newFixedThreadPool(6) { r ->
        Thread(r, "apex-rpc").apply { isDaemon = true }
    }
    private fun <T> async(block: () -> T): Future<T> = pool.submit(Callable(block))

    /**
     * Wait for one of those reads, but never forever.
     *
     * Every timeout inside the HTTP layer is already bounded, so a get that has
     * not come back in a minute means the task never started. Waiting on it with
     * no deadline is how a stuck pool became a stuck app.
     */
    private fun <T> Future<T>.await(): T? =
        runCatching { get(60, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()

    fun urlFor(cluster: String?): String = when (cluster?.lowercase()) {
        "devnet", "solana:devnet" -> "https://api.devnet.solana.com"
        "testnet", "solana:testnet" -> "https://api.testnet.solana.com"
        else -> customRpc?.takeIf { it.isNotBlank() } ?: MAINNET_PRIMARY
    }

    private fun candidates(rpcUrl: String): List<String> = when {
        rpcUrl.contains("devnet") || rpcUrl.contains("testnet") -> listOf(rpcUrl)
        else -> listOf(rpcUrl, MAINNET_PRIMARY, MAINNET_FALLBACK).distinct()
    }

    data class SimResult(val ok: Boolean, val err: String?, val logs: List<String>, val unitsConsumed: Long?)

    /** Simulate a serialized transaction. Returns null on a network/transport failure. */
    fun simulate(rpcUrl: String, txBytes: ByteArray): SimResult? {
        val b64 = Base64.encodeToString(txBytes, Base64.NO_WRAP)
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
     * One of the owner's SPL token accounts, as the node parses it. Beyond the
     * balance this carries what the "hygiene" screens need: an active delegate
     * (an approval that can spend on the owner's behalf), the rent locked in the
     * account (what closing it gives back) and which token program owns it.
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
         * Simulated cleanly. [deltas] are the real balance changes — the owner's
         * *and* those of every external destination wallet we tracked (so a split
         * across multiple wallets is visible). [computeUnits]/[logCount] are the
         * simulation's own reported stats.
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

    /** The owner's token accounts (both token programs), cached for a minute unless [force]. */
    fun tokenAccountsOf(rpcUrl: String, owner: String, force: Boolean = false): List<TokenAccountInfo> {
        val key = "$rpcUrl|$owner"
        val now = System.currentTimeMillis()
        if (!force) tokenListCache[key]?.let { if (now - it.at < TOKEN_LIST_TTL_MS) return it.value }
        val list = getTokenAccounts(rpcUrl, owner)
        tokenListCache[key] = Cached(list, now)
        return list
    }

    // ---- effects -------------------------------------------------------------

    /**
     * Work out the real balance effects on [owner]'s wallet — and on every wallet
     * in [destinations] — by simulating the transaction on the cluster and diffing
     * SOL + SPL-token balances. Tracking the destinations is what reveals a payment
     * that *splits* across several wallets (recipient + hidden fee/referral
     * accounts), not just the opaque total the owner pays.
     *
     * [txKeys] = every account the transaction can touch (static + lookup-table
     * loaded). Only the owner's token accounts among them are tracked, so a wallet
     * holding 200 tokens can never have the *one* being drained fall outside the
     * tracked window. When null (undecodable tx) the first 25 are tracked.
     *
     * Wire cost: the token-account list (cached), then the simulation and the
     * pre-balance read run concurrently — ~1 round-trip on the hot path.
     */
    fun simulateEffects(
        rpcUrl: String,
        txBytes: ByteArray,
        owner: String,
        destinations: List<String> = emptyList(),
        txKeys: Set<String>? = null,
        /**
         * Mints the caller expects to receive. Buying a coin for the first time
         * creates its token account inside the same transaction, and a Jupiter
         * swap hides that account in an address lookup table, so it is in neither
         * the wallet's account list nor the transaction's static keys. Given the
         * mint we can derive the address ourselves and watch it.
         */
        expectMints: List<String> = emptyList(),
    ): SimOutcome {
        val allTokens = tokenAccountsOf(rpcUrl, owner)
        val tokenAccts = when {
            txKeys != null -> allTokens.filter { it.pubkey in txKeys }.take(32)
            else -> allTokens.take(25)
        }
        val tokenPubkeys = tokenAccts.map { it.pubkey }.toSet()
        // Accounts this transaction touches that we do not own a token account for
        // yet. Buying a coin for the first time creates its account inside the same
        // transaction, so it can never appear in the list above; without this the
        // simulation showed the SOL leaving and nothing arriving, and the intent
        // check correctly called that a lie.
        val fresh = expectedAtas(owner, expectMints).filter { it !in tokenPubkeys }
        // Candidate destination wallets (excl. the owner and its own token accounts).
        val dests = destinations.asSequence().filter { it != owner && it !in tokenPubkeys }.distinct().take(24).toList()
        Log.i(TAG, "effects: tokenAccts=${tokenAccts.size}/${allTokens.size}, dests=${dests.size}")

        // Pre-state (one getMultipleAccounts) and the simulation are independent:
        // run them concurrently. Fallbacks shrink the tracked set if the node
        // rejects the fat request, so the SOL cost still shows instead of a blanket fail.
        val preOrder = listOf(owner) + tokenAccts.map { it.pubkey } + dests + fresh
        val preF = async { getAccountsMulti(rpcUrl, preOrder) }

        var trackedTokens = tokenAccts
        var trackedDests = dests
        var trackedFresh = fresh
        var value = simulateValue(rpcUrl, txBytes, listOf(owner) + tokenAccts.map { it.pubkey } + dests + fresh)
        if (value == null && fresh.isNotEmpty()) {
            Log.i(TAG, "full simulate failed → retry without the new accounts")
            trackedFresh = emptyList()
            value = simulateValue(rpcUrl, txBytes, listOf(owner) + tokenAccts.map { it.pubkey } + dests)
        }
        if (value == null && (tokenAccts.isNotEmpty() || dests.isNotEmpty())) {
            Log.i(TAG, "full simulate failed → retry SOL accounts only")
            trackedTokens = emptyList(); trackedFresh = emptyList()
            value = simulateValue(rpcUrl, txBytes, listOf(owner) + dests)
        }
        if (value == null && dests.isNotEmpty()) {
            Log.i(TAG, "retry owner-only")
            trackedDests = emptyList(); trackedFresh = emptyList()
            value = simulateValue(rpcUrl, txBytes, listOf(owner))
        }
        val pre = preF.await() ?: emptyMap()
        if (value == null) { Log.i(TAG, "simulate Unavailable"); return SimOutcome.Unavailable }
        if (!value.isNull("err")) { Log.i(TAG, "simulate Failed: ${value.get("err")}"); return SimOutcome.Failed(value.get("err").toString()) }

        val computeUnits = if (value.isNull("unitsConsumed")) null else value.optLong("unitsConsumed")
        val logCount = value.optJSONArray("logs")?.length() ?: 0
        // Owner's pre-balances by mint: what "you send 95% of your SOL" is measured against.
        val preBalances = HashMap<String, Long>()
        pre[owner]?.lamports?.let { preBalances[com.clearsign.core.NATIVE_SOL_MINT] = it }
        allTokens.forEach { ta -> preBalances[ta.mint] = (preBalances[ta.mint] ?: 0L) + (pre[ta.pubkey]?.tokenAmount ?: ta.amount) }
        val accounts = value.optJSONArray("accounts") ?: return SimOutcome.Ok(emptyList(), computeUnits, logCount, preBalances)
        val deltas = ArrayList<com.clearsign.core.BalanceDelta>()

        // index 0 = owner SOL
        pre[owner]?.lamports?.let { ownerPre ->
            accounts.optJSONObject(0)?.let { acc ->
                val d = acc.optLong("lamports", ownerPre) - ownerPre
                if (d != 0L) deltas.add(com.clearsign.core.BalanceDelta(owner, com.clearsign.core.NATIVE_SOL_MINT, "SOL", 9, d))
            }
        }
        // next: owner's token accounts
        trackedTokens.forEachIndexed { i, ta ->
            val preAmt = pre[ta.pubkey]?.tokenAmount ?: ta.amount
            val acc = accounts.optJSONObject(i + 1)
            val post = acc?.let { tokenAmountFromAccountData(it) } ?: preAmt
            val d = post - preAmt
            if (d != 0L) deltas.add(com.clearsign.core.BalanceDelta(owner, ta.mint, TokenSymbols.symbol(ta.mint), ta.decimals, d))
        }
        // finally: destination wallets — their SOL change reveals the split
        val destBase = 1 + trackedTokens.size
        val freshBase = destBase + trackedDests.size
        trackedDests.forEachIndexed { i, addr ->
            val preLam = pre[addr]?.lamports ?: return@forEachIndexed
            val acc = accounts.optJSONObject(destBase + i) ?: return@forEachIndexed
            val d = acc.optLong("lamports", preLam) - preLam
            // pre == 0 → the account didn't exist before this tx: its SOL gain is
            // rent to *create* it (ATA/PDA), not a payment to someone's wallet.
            if (d != 0L) deltas.add(com.clearsign.core.BalanceDelta(addr, com.clearsign.core.NATIVE_SOL_MINT, "SOL", 9, d, createdAccount = preLam == 0L))
        }
        // Token accounts that came into existence inside this transaction. We only
        // count one if the simulated account says, in its own bytes, that we own
        // it: a token account carries its mint at offset 0 and its owner at 32, so
        // this is the account itself claiming us, not us assuming.
        trackedFresh.forEachIndexed { i, addr ->
            val acc = accounts.optJSONObject(freshBase + i) ?: return@forEachIndexed
            val before = pre[addr]?.tokenAmount ?: 0L
            val t = tokenAccountFrom(acc) ?: return@forEachIndexed
            if (t.owner != owner) return@forEachIndexed
            val d = t.amount - before
            if (d == 0L) return@forEachIndexed
            // Already counted if we were tracking this mint through an account we
            // owned before the transaction.
            if (trackedTokens.any { it.mint == t.mint }) return@forEachIndexed
            deltas.add(com.clearsign.core.BalanceDelta(owner, t.mint, TokenSymbols.symbol(t.mint), t.decimals, d))
        }
        Log.i(TAG, "effects OK: deltas=${deltas.size}, cu=$computeUnits, dests=${trackedDests.size}, new=${trackedFresh.size}")
        return SimOutcome.Ok(deltas, computeUnits, logCount, preBalances)
    }

    /**
     * Where a coin would land: the owner's associated token account, under both
     * token programs because we do not know which one the mint uses until we
     * read it back, and asking costs a round trip on the hot path.
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
     * Decode a token account from the simulation's account data.
     *
     * Layout, both Token and Token-2022: mint at 0, owner at 32, amount as a
     * little-endian u64 at 64. Decimals are not in the account, they are on the
     * mint, so we take the symbol table's answer and fall back to a sane guess
     * rather than another round trip inside the hot path.
     */
    private fun tokenAccountFrom(acc: JSONObject): TokenAcct? {
        val b64 = acc.optJSONArray("data")?.optString(0) ?: return null
        val bytes = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { return null }
        if (bytes.size < 72) return null
        val ownerProg = acc.optString("owner")
        if (ownerProg != TOKEN_PROGRAM && ownerProg != TOKEN_2022) return null
        val mint = Base58.encode(bytes.copyOfRange(0, 32))
        val who = Base58.encode(bytes.copyOfRange(32, 64))
        var v = 0L
        for (i in 0 until 8) v = v or ((bytes[64 + i].toLong() and 0xFF) shl (8 * i))
        val decimals = JupiterTokens.cached(mint)?.decimals ?: 6
        return TokenAcct(mint, who, v, decimals)
    }

    /** What a raw account read tells us: lamports (0 = doesn't exist) and, for SPL token accounts, the amount. */
    private data class AcctPre(val lamports: Long, val tokenAmount: Long?)

    /** One getMultipleAccounts (base64) for many accounts; missing accounts read as 0 lamports. */
    private fun getAccountsMulti(rpcUrl: String, pubkeys: List<String>): Map<String, AcctPre> {
        if (pubkeys.isEmpty()) return emptyMap()
        val out = HashMap<String, AcctPre>()
        // RPC caps getMultipleAccounts at 100 keys; chunk defensively.
        pubkeys.chunked(64).forEach { chunk ->
            val addrArr = JSONArray(); chunk.forEach { addrArr.put(it) }
            val params = JSONArray().put(addrArr).put(JSONObject().put("encoding", "base64").put("commitment", COMMITMENT))
            val resp = post(rpcUrl, "getMultipleAccounts", params) ?: return@forEach
            val arr = resp.optJSONObject("result")?.optJSONArray("value") ?: return@forEach
            for (i in chunk.indices) {
                val acc = arr.optJSONObject(i)
                out[chunk[i]] = AcctPre(acc?.optLong("lamports", 0L) ?: 0L, acc?.let { tokenAmountFromAccountData(it) })
            }
        }
        return out
    }

    /**
     * Resolve a v0 transaction's lookup-table-loaded addresses (writable, readonly)
     * with one getMultipleAccounts on the tables. Returns empty lists when there
     * are no lookups or the tables can't be read.
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
            val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: continue
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

    private fun simulateValue(rpcUrl: String, txBytes: ByteArray, addresses: List<String>): JSONObject? {
        val addrArr = JSONArray(); addresses.forEach { addrArr.put(it) }
        val params = JSONArray()
            .put(Base64.encodeToString(txBytes, Base64.NO_WRAP))
            .put(
                JSONObject()
                    .put("encoding", "base64")
                    .put("sigVerify", false)
                    .put("replaceRecentBlockhash", true)
                    .put("commitment", COMMITMENT)
                    .put("accounts", JSONObject().put("encoding", "base64").put("addresses", addrArr)),
            )
        val resp = post(rpcUrl, "simulateTransaction", params) ?: return null
        return resp.optJSONObject("result")?.optJSONObject("value")
    }

    /** Public SOL balance (lamports) for [pubkey], or null on failure. */
    fun balanceOf(rpcUrl: String, pubkey: String): Long? = getBalance(rpcUrl, pubkey)

    /** (SOL lamports or null, number of token types with a non-zero balance). */
    fun assetsSummary(rpcUrl: String, pubkey: String): Pair<Long?, Int> {
        val lamF = async { getBalance(rpcUrl, pubkey) }
        val tokens = tokenAccountsOf(rpcUrl, pubkey).count { it.amount > 0 }
        return lamF.await() to tokens
    }

    /**
     * Balances for many accounts at once (the account picker): one read for all
     * the SOL balances, token counts fanned out concurrently. Also warms the
     * token-list cache for whichever account gets picked next.
     */
    fun assetsSummaryMulti(rpcUrl: String, pubkeys: List<String>): Map<String, Pair<Long?, Int>> {
        val lamF = async { getAccountsMulti(rpcUrl, pubkeys) }
        val tokF = pubkeys.map { pk -> pk to async { tokenAccountsOf(rpcUrl, pk).count { it.amount > 0 } } }
        val lam = lamF.await() ?: emptyMap()
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
     * Counterparty intelligence: how many transactions the address has, how old it
     * is, its balance, and what kind of account it is. "Nuovo di zecca, 0 storico"
     * vs "attivo da mesi" is a strong, free trust signal — computed with two RPC
     * calls run in parallel, cached for a few minutes. Never call on the main thread.
     *
     * Note: when the signature window is capped, [WalletIntel.ageDays] is the age of
     * the *oldest signature in the window*, i.e. a lower bound (UI shows "almeno").
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
     * getProgramAccounts with a single memcmp filter — used to find a program's
     * account by an indexed field (e.g. a reputation PDA carrying its target
     * pubkey) without deriving the PDA on-device. Returns each account's raw data.
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
            runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    /**
     * What a wallet holds, or **null when the chain could not be asked**.
     *
     * The lenient reader below turns a failed call into an empty list, which is
     * the right shape for a portfolio screen and the wrong one for anything that
     * acts on the answer: the trading loop reconciled its book against it, read
     * a rate-limited node as "the wallet holds nothing", and dropped every open
     * position, coins still in the wallet and no stop watching them. Here a
     * failed read is a null, and both programs must answer for the list to count.
     *
     * Also the reader for wallets that are not ours: the Seeker whale card uses it
     * with the scanner's own key, deliberately never the agent's, so reading a
     * stranger's balances cannot eat the month that real trades depend on.
     * Blocking, two calls, so call it on IO and only when somebody asked.
     */
    fun tokensOf(rpcUrl: String, owner: String): List<TokenAccountInfo>? {
        val parts = readTokenAccounts(rpcUrl, owner)
        if (parts.any { it == null }) return null
        val list = parts.flatMap { it.orEmpty() }
        TokenSymbols.resolve(list.map { it.mint })
        tokenListCache["$rpcUrl|$owner"] = Cached(list, System.currentTimeMillis())
        return list
    }

    /* Both token programs queried concurrently (they're independent). A failed call reads as empty. */
    private fun getTokenAccounts(rpcUrl: String, owner: String): List<TokenAccountInfo> {
        val list = readTokenAccounts(rpcUrl, owner).flatMap { it.orEmpty() }
        // Learn the symbols of whatever tokens this wallet holds (one DAS call, cached).
        TokenSymbols.resolve(list.map { it.mint })
        return list
    }

    /* One list per token program, in order; null for a program whose call did not come back. */
    private fun readTokenAccounts(rpcUrl: String, owner: String): List<List<TokenAccountInfo>?> {
        // Read the two token programs in a row, on whatever thread called us.
        //
        // These used to be two more tasks handed to the same fixed pool, waited
        // on with a blocking get. The callers of this function are themselves
        // tasks on that pool, so with six wallets in the Seed Vault all six
        // threads sat waiting for work that could never be scheduled, and from
        // then on every simulation in the process hung too. Two sequential HTTP
        // calls are slower by one round trip and cannot deadlock.
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

    /*
     * Token symbols from the Digital Asset Standard API (Helius only): mint → symbol.
     * A symbol is only trusted when it's short and printable; anything else stays
     * an address, so a scam token can't impersonate "USDC" through metadata alone.
     */
    /** What DAS knows about a mint: symbol/name, a logo URL, and whether it's an NFT-like asset. */
    data class DasAsset(val symbol: String?, val name: String?, val image: String?, val isNft: Boolean)

    /** Metadata for [mints] via DAS `getAssetBatch` (Helius only; one call per 100 mints, best effort). */
    fun dasAssets(mints: List<String>): Map<String, DasAsset> {
        val das = customRpc ?: return emptyMap()
        val out = HashMap<String, DasAsset>()
        mints.chunked(100).forEach { chunk ->
            val params = JSONObject().put("ids", JSONArray(chunk))
            val resp = runCatching { postOnce(das, "getAssetBatch", params) }.getOrNull() as? Http.Ok ?: return@forEach
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

    fun dasSymbols(mints: List<String>): Map<String, String> =
        dasAssets(mints).mapNotNull { (k, v) -> v.symbol?.let { k to it } }.toMap()

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
     * Median priority fee (µlamports per CU) the network recently paid on the given
     * accounts (last ~150 slots). This is the yardstick for "you're paying 20× the
     * going rate". Null when the node can't answer.
     */
    fun recentPrioritizationFees(rpcUrl: String, accounts: List<String> = emptyList()): Long? {
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
        val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return null
        return RawAccount(bytes, v.optString("owner"), v.optLong("lamports"))
    }

    private fun tokenAmountFromAccountData(acc: JSONObject): Long? {
        val dataArr = acc.optJSONArray("data") ?: return null
        val b64 = dataArr.optString(0)
        val bytes = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { return null }
        if (bytes.size < 72) return null // SPL token account layout: amount is u64 LE at offset 64
        val ownerProg = acc.optString("owner")
        if (ownerProg != TOKEN_PROGRAM && ownerProg != TOKEN_2022) return null
        var v = 0L
        for (i in 0 until 8) v = v or ((bytes[64 + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    /** Result of a send: the signature, or the node's reason for rejecting it. */
    data class SendOutcome(val signature: String?, val error: String?)

    /** Submit a fully-signed transaction. A deterministic rejection (expired
     *  blockhash, insufficient funds, …) is returned as [SendOutcome.error], not retried. */
    fun send(rpcUrl: String, signedTx: ByteArray): SendOutcome {
        val b64 = Base64.encodeToString(signedTx, Base64.NO_WRAP)
        val params = JSONArray().put(b64).put(JSONObject().put("encoding", "base64").put("preflightCommitment", COMMITMENT))
        val resp = post(rpcUrl, "sendTransaction", params) ?: return SendOutcome(null, "network unreachable")
        if (resp.has("result")) return SendOutcome(resp.getString("result"), null)
        val err = resp.optJSONObject("error")
        val msg = err?.optJSONObject("data")?.optJSONArray("logs")?.let { logs ->
            // The last program log usually carries the human reason ("insufficient lamports").
            (0 until logs.length()).map { logs.optString(it) }.lastOrNull { it.contains("failed") || it.contains("insufficient") || it.contains("Error") }
        } ?: err?.optString("message") ?: "rifiutata dal nodo"
        return SendOutcome(null, msg)
    }

    /**
     * Wait for [signature] to land. "Sent" is what the node said; "landed" is
     * what the chain says, and only the second one is true. Polls the status
     * every second and a half for up to [timeoutMs]. True when confirmed or
     * finalised without error; false on an error or when time runs out, which
     * the caller must treat as "not landed", never as "landed".
     */
    fun confirmed(rpcUrl: String, signature: String, timeoutMs: Long = 30_000L): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val resp = post(rpcUrl, "getSignatureStatuses", JSONArray().put(JSONArray().put(signature)).put(JSONObject().put("searchTransactionHistory", true)))
            val st = resp?.optJSONObject("result")?.optJSONArray("value")?.optJSONObject(0)
            if (st != null) {
                if (!st.isNull("err")) return false
                val c = st.optString("confirmationStatus")
                if (c == "confirmed" || c == "finalized") return true
            }
            try { Thread.sleep(1_500) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    /**
     * SOL parked in native stake accounts, which the token list never shows
     * and Jupiter's wallet lists under DeFi. Found by the withdraw authority,
     * which is what "yours" means for a stake account.
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

    fun epoch(rpcUrl: String): Long? = post(rpcUrl, "getEpochInfo", JSONArray())?.optJSONObject("result")?.optLong("epoch")

    fun stakeAccounts(rpcUrl: String, owner: String): List<StakeAccount> {
        val params = JSONArray().put("Stake11111111111111111111111111111111111111").put(
            JSONObject().put("encoding", "jsonParsed").put(
                "filters", JSONArray().put(JSONObject().put("memcmp", JSONObject().put("offset", 44).put("bytes", owner))),
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
    fun inflationRate(rpcUrl: String): Double? =
        post(rpcUrl, "getInflationRate", JSONArray())?.optJSONObject("result")?.optDouble("validator")?.takeIf { !it.isNaN() && it > 0 }

    /** Total supply of a mint, raw. */
    fun tokenSupply(rpcUrl: String, mint: String): Long? =
        post(rpcUrl, "getTokenSupply", JSONArray().put(mint))?.optJSONObject("result")?.optJSONObject("value")?.optString("amount")?.toLongOrNull()

    /** The raw bytes of one account, or null. */
    fun accountBytes(rpcUrl: String, pubkey: String): ByteArray? {
        val v = post(rpcUrl, "getAccountInfo", JSONArray().put(pubkey).put(JSONObject().put("encoding", "base64")))
            ?.optJSONObject("result")?.optJSONObject("value") ?: return null
        val b64 = v.optJSONArray("data")?.optString(0) ?: return null
        return runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
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
                runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()?.let { out[chunk[i]] = it }
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
            runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()?.let { out[key] = it }
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
        val user = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return null
        val config = accountBytes(rpcUrl, SkrStake.CONFIG) ?: return null
        return SkrStake.decode(user, config)?.takeIf { it.rawSkr > 0 }
    }

    // ---- transport -----------------------------------------------------------

    private sealed interface Http {
        data class Ok(val json: JSONObject) : Http     // 2xx, parsed; may still carry a JSON-RPC "error"
        data class Status(val code: Int) : Http        // non-2xx
        data object Transport : Http                   // timeout / DNS / parse
    }

    /**
     * POST with endpoint fallback. Retries only *transient* failures (transport,
     * 429, 5xx) with a short backoff; a JSON-RPC error from the node is
     * deterministic and is returned as-is (the caller reads `error`), except
     * rate-limit style errors, which move on to the next endpoint. A 4xx moves
     * to the next endpoint immediately (bad key / unsupported method).
     */
    private fun post(rpcUrl: String, method: String, params: JSONArray): JSONObject? {
        var last: JSONObject? = null
        for (url in candidates(rpcUrl)) {
            var attempt = 0
            while (attempt < 2) {
                when (val r = postOnce(url, method, params)) {
                    is Http.Ok -> {
                        val err = r.json.optJSONObject("error")
                        if (err == null) return r.json
                        last = r.json
                        val code = err.optInt("code"); val msg = err.optString("message").lowercase()
                        val transient = code == 429 || code == -32429 || msg.contains("rate") || msg.contains("too many") || msg.contains("limit")
                        if (!transient) return r.json      // deterministic: don't hammer other nodes
                        break                              // rate-limited here → next endpoint
                    }
                    is Http.Status -> {
                        if (r.code == 429 || r.code >= 500) { attempt++; backoff(attempt); continue }
                        break                              // 4xx: this endpoint can't serve it
                    }
                    Http.Transport -> { attempt++; backoff(attempt) }
                }
            }
        }
        return last
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
