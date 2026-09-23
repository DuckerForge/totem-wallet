package com.clearsign.app

import android.content.Context
import android.util.Log
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The RPC pool inside the app: compiled providers, the prefs where the pool remembers, one
 * semaphore per provider, the cap read from the archive. The semaphore pays most: one
 * receipt analysis fires nine to fifteen calls almost at once, and a free key's ten per
 * second are global across all users, so five simultaneous approvals worldwide saturated
 * every provider with quota to spare. Each provider grants a few slots; whoever finds none
 * within two seconds moves on instead of being told 429.
 */
object Rpc {
    private const val TAG = "ClearSign-RPC"
    private const val PREFS = "clearsign_rpc"

    /** The node written in Settings. Comes before everything, with the pool behind. */
    @Volatile var ownNode: String? = null

    /** The own node as a provider: no quota of ours, no cap. */
    private fun ownProvider(): RpcPool.Provider? =
        ownNode?.takeIf { it.isNotBlank() }?.let { RpcPool.Provider("own", it, perSecond = 20, weight = 1, das = false) }

    /**
     * The providers this build knows. An empty field in `local.properties` is a provider that
     * is not there; weights follow the monthly quota, which decides how often an install picks one.
     */
    private fun providers(): List<RpcPool.Provider> = listOfNotNull(
        BuildConfig.HELIUS_RPC_URL.takeIf { it.isNotBlank() }?.let { RpcPool.Provider("helius", it, perSecond = 10, weight = 10, das = true) },
        BuildConfig.ALCHEMY_RPC_URL.takeIf { it.isNotBlank() }?.let { RpcPool.Provider("alchemy", it, perSecond = 15, weight = 20) },
        BuildConfig.CHAINSTACK_RPC_URL.takeIf { it.isNotBlank() }?.let { RpcPool.Provider("chainstack", it, perSecond = 25, weight = 30) },
        BuildConfig.RPCFAST_RPC_URL.takeIf { it.isNotBlank() }?.let {
            RpcPool.Provider(
                "rpcfast", it, perSecond = 15, weight = 15,
                // Measured on the free tier: answers -32099 to these, and limits getProgramAccounts to one a second.
                unsupported = setOf("getTokenAccountsByOwner", "getTokenAccountsByDelegate", "getTokenLargestAccounts", "getProgramAccounts"),
            )
        },
        // dRPC: the biggest quota of all, limited per IP and not per key, but the free
        // tier runs on public nodes: reads yes, sends no.
        BuildConfig.DRPC_RPC_URL.takeIf { it.isNotBlank() }?.let { RpcPool.Provider("drpc", it, perSecond = 20, weight = 25, sends = false) },
        RpcPool.Provider("mainnet", "https://api.mainnet-beta.solana.com", perSecond = 5, weight = 1, lastResort = true),
        // publicnode blocks the token-account calls: it would be learned anyway, but it is already known.
        RpcPool.Provider("publicnode", "https://solana-rpc.publicnode.com", perSecond = 5, weight = 1, lastResort = true, unsupported = setOf("getTokenAccountsByOwner", "getTokenAccountsByDelegate", "getProgramAccounts")),
    )

    private class Prefs(ctx: Context) : RpcPool.Store {
        private val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override fun get(key: String): String? = p.getString(key, null)
        override fun put(key: String, value: String?) { p.edit().putString(key, value).apply() }
    }

    @Volatile private var built: RpcPool? = null
    private val semaphores = java.util.concurrent.ConcurrentHashMap<String, Semaphore>()

    /**
     * The pool with this install's seed: a random number made once and saved, so a rebooted
     * phone keeps its queue instead of regrouping with the others. Never derived from the wallet.
     */
    fun init(ctx: Context) {
        val store = Prefs(ctx)
        val seed = store.get("seed")?.toLongOrNull() ?: java.security.SecureRandom().nextLong().also { store.put("seed", it.toString()) }
        built = RpcPool(providers(), seed, store)
    }

    val pool: RpcPool get() = built ?: RpcPool(providers(), 0L).also { built = it }

    /**
     * In what order to try [method] for whoever asked [rpcUrl]. A test network is asked only of
     * itself. The own node comes first with the pool behind, never alone: a flaky personal node
     * with no fallback turns every gate into `Unavailable`, which never blocks, a silent
     * switch-off reintroduced by a settings field. An unknown URL is treated as an own node.
     */
    fun lanes(rpcUrl: String, method: String): List<RpcPool.Provider> {
        if (rpcUrl.contains("devnet") || rpcUrl.contains("testnet")) return listOf(RpcPool.Provider("cluster", rpcUrl, perSecond = 10, weight = 1, lastResort = true))
        val p = pool
        // DAS is Helius only: the own node has no coin names.
        if (method.startsWith("getAsset")) return p.lanes(method, System.currentTimeMillis(), null)
        val own = ownProvider()
        val first = when {
            own != null && rpcUrl == own.url -> own
            p.providers.any { it.url == rpcUrl } -> own
            else -> RpcPool.Provider("caller", rpcUrl, perSecond = 10, weight = 1)
        }
        val lanes = p.lanes(method, System.currentTimeMillis(), first)
        return if (own != null && first != own) listOf(own) + lanes.filter { it.name != "own" } else lanes
    }

    /**
     * A slot on the provider for one call. Null when none within two seconds: the caller moves
     * on, and it does not count against the provider.
     */
    fun <T> withLane(p: RpcPool.Provider, block: () -> T): T? {
        val s = semaphores.computeIfAbsent(p.name) { Semaphore((p.perSecond / 2).coerceIn(2, 8), true) }
        if (!s.tryAcquire(2, TimeUnit.SECONDS)) { Log.i(TAG, "${p.name} busy"); return null }
        try { return block() } finally { s.release() }
    }

    /** One line per call: the provider's name, never the URL. */
    fun record(p: RpcPool.Provider, method: String, outcome: RpcPool.Outcome, ms: Long, detail: String? = null) {
        val now = System.currentTimeMillis()
        val pool = pool
        if (p.name != "own" && p.name != "caller" && p.name != "cluster") {
            pool.onOutcome(p.name, method, outcome, now, detail)
            pool.countCall(p, now)
        }
        Log.i(TAG, "${p.name} $method $outcome ${ms}ms" + (detail?.let { " $it" } ?: ""))
    }

    /** Over the day's cap on shared keys. With an own node there is no cap. */
    fun overBudget(): Boolean {
        if (ownNode != null) return false
        refreshCap()
        return pool.overBudget(System.currentTimeMillis())
    }

    /** The provider that serves DAS. Helius, not the own node: an ordinary node has no coin names. */
    fun dasUrl(): String? = pool.providers.firstOrNull { it.das && pool.state(it.name, System.currentTimeMillis()) == RpcPool.State.OK }?.url

    // ---- the cap from the archive -------------------------------------------------

    @Volatile private var capAt = 0L
    private const val CAP_EVERY_MS = 60 * 60_000L

    /**
     * The archive can change the cap: `/clearsign/rpc.json` with `{"cap": N}`, tightened or
     * widened for everyone without an APK, the one knob there is when the keys are everybody's
     * and the phones are not. Read once an hour; absent, the known value stays.
     */
    private fun refreshCap() {
        val now = System.currentTimeMillis()
        if (now - capAt < CAP_EVERY_MS) return
        capAt = now
        val cap = Archive.read("rpc", CAP_EVERY_MS)?.optInt("cap", -1)?.takeIf { it >= 0 } ?: return
        if (cap != pool.cap) { pool.cap = cap; Log.i(TAG, "cap from archive: $cap") }
    }

    /** The diagnostics rows, plus today's call count and the cap. */
    fun report(): Pair<List<RpcPool.Line>, Pair<Int, Int>> {
        val now = System.currentTimeMillis()
        return pool.report(now) to (pool.usedToday(now) to pool.cap)
    }
}
