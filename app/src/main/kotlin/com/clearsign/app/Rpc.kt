package com.clearsign.app

import android.content.Context
import android.util.Log
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Il pool RPC dentro l'app: i fornitori compilati, le preferenze dove il pool
 * ricorda, un semaforo per fornitore, e il tetto letto dall'archivio.
 *
 * Il semaforo e' il pezzo che rende di piu'. Una sola analisi di uno scontrino
 * spara dalle nove alle quindici chiamate quasi insieme, e le dieci al secondo
 * di una chiave gratuita sono globali su tutti gli utenti: cinque approvazioni
 * contemporanee nel mondo saturavano ogni fornitore con la quota ancora piena.
 * Qui ogni fornitore concede pochi posti alla volta, e chi non trova posto in
 * due secondi passa al prossimo invece di farsi dire 429.
 */
object Rpc {
    private const val TAG = "ClearSign-RPC"
    private const val PREFS = "clearsign_rpc"

    /** Il nodo scritto nelle impostazioni. Viene prima di tutto, e il pool sta dietro. */
    @Volatile var ownNode: String? = null

    /** Il nodo proprio come fornitore: senza quota nostra, senza tetto. */
    private fun ownProvider(): RpcPool.Provider? =
        ownNode?.takeIf { it.isNotBlank() }?.let { RpcPool.Provider("own", it, perSecond = 20, weight = 1, das = false) }

    /**
     * I fornitori che questa build conosce. Un campo vuoto in `local.properties`
     * e' un fornitore che non c'e'. I pesi seguono la quota mensile, che e' quello
     * che decide quanto spesso ogni installazione deve pescarne uno.
     */
    private fun providers(): List<RpcPool.Provider> = listOfNotNull(
        BuildConfig.HELIUS_RPC_URL.takeIf { it.isNotBlank() }?.let { RpcPool.Provider("helius", it, perSecond = 10, weight = 10, das = true) },
        BuildConfig.ALCHEMY_RPC_URL.takeIf { it.isNotBlank() }?.let { RpcPool.Provider("alchemy", it, perSecond = 15, weight = 20) },
        BuildConfig.CHAINSTACK_RPC_URL.takeIf { it.isNotBlank() }?.let { RpcPool.Provider("chainstack", it, perSecond = 25, weight = 30) },
        BuildConfig.RPCFAST_RPC_URL.takeIf { it.isNotBlank() }?.let {
            RpcPool.Provider(
                "rpcfast", it, perSecond = 15, weight = 15,
                // Misurato sul gratuito: risponde -32099 a questi, e limita getProgramAccounts a una al secondo.
                unsupported = setOf("getTokenAccountsByOwner", "getTokenAccountsByDelegate", "getTokenLargestAccounts", "getProgramAccounts"),
            )
        },
        // dRPC: la quota piu' grande di tutte, limite per indirizzo IP e non per
        // chiave, ma il gratuito gira su nodi pubblici: letture si', invii no.
        BuildConfig.DRPC_RPC_URL.takeIf { it.isNotBlank() }?.let { RpcPool.Provider("drpc", it, perSecond = 20, weight = 25, sends = false) },
        RpcPool.Provider("mainnet", "https://api.mainnet-beta.solana.com", perSecond = 5, weight = 1, lastResort = true),
        // publicnode blocca le chiamate sui conti token: lo si impara comunque, ma si sa gia'.
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
     * Il pool, con il seme di questa installazione. Il seme e' un numero a
     * caso fatto una volta, salvato nelle preferenze: un telefono che si
     * riavvia resta nella sua fila invece di raggrupparsi con gli altri. Mai
     * derivato dal portafoglio.
     */
    fun init(ctx: Context) {
        val store = Prefs(ctx)
        val seed = store.get("seed")?.toLongOrNull() ?: java.security.SecureRandom().nextLong().also { store.put("seed", it.toString()) }
        built = RpcPool(providers(), seed, store)
    }

    val pool: RpcPool get() = built ?: RpcPool(providers(), 0L).also { built = it }

    /**
     * In che ordine provare [method] per chi ha chiesto [rpcUrl].
     *
     * Una rete di prova si chiede solo a se stessa. Il nodo proprio viene per
     * primo e il pool dietro, mai da solo: un nodo personale ballerino con
     * nessun ripiego trasforma ogni cancello in `Unavailable`, che non blocca
     * mai, ed e' lo spegnimento silenzioso reintrodotto da un campo nelle
     * impostazioni. Un indirizzo che non e' ne' il nodo proprio ne' uno del pool
     * si tratta come un nodo proprio.
     */
    fun lanes(rpcUrl: String, method: String): List<RpcPool.Provider> {
        if (rpcUrl.contains("devnet") || rpcUrl.contains("testnet")) return listOf(RpcPool.Provider("cluster", rpcUrl, perSecond = 10, weight = 1, lastResort = true))
        val p = pool
        // DAS e' di Helius e basta: il nodo proprio non ha i nomi delle monete.
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
     * Un posto sul fornitore, per il tempo di una chiamata. Null quando non
     * c'e' posto entro due secondi: chi chiama passa al prossimo, senza che
     * questo conti come un errore del fornitore.
     */
    fun <T> withLane(p: RpcPool.Provider, block: () -> T): T? {
        val s = semaphores.computeIfAbsent(p.name) { Semaphore((p.perSecond / 2).coerceIn(2, 8), true) }
        if (!s.tryAcquire(2, TimeUnit.SECONDS)) { Log.i(TAG, "${p.name} busy"); return null }
        try { return block() } finally { s.release() }
    }

    /** Una riga per chiamata: il nome del fornitore, mai l'indirizzo. */
    fun record(p: RpcPool.Provider, method: String, outcome: RpcPool.Outcome, ms: Long, detail: String? = null) {
        val now = System.currentTimeMillis()
        val pool = pool
        if (p.name != "own" && p.name != "caller" && p.name != "cluster") {
            pool.onOutcome(p.name, method, outcome, now, detail)
            pool.countCall(p, now)
        }
        Log.i(TAG, "${p.name} $method $outcome ${ms}ms" + (detail?.let { " $it" } ?: ""))
    }

    /** Sopra il tetto del giorno sulle chiavi condivise. Con un nodo proprio non c'e' tetto. */
    fun overBudget(): Boolean {
        if (ownNode != null) return false
        refreshCap()
        return pool.overBudget(System.currentTimeMillis())
    }

    /** Il fornitore che serve DAS. Helius, e non il nodo proprio: un nodo qualsiasi non ha i nomi delle monete. */
    fun dasUrl(): String? = pool.providers.firstOrNull { it.das && pool.state(it.name, System.currentTimeMillis()) == RpcPool.State.OK }?.url

    // ---- il tetto dall'archivio ---------------------------------------------------

    @Volatile private var capAt = 0L
    private const val CAP_EVERY_MS = 60 * 60_000L

    /**
     * Il tetto lo puo' cambiare l'archivio: `/clearsign/rpc.json` con `{"cap": N}`.
     * Cosi' si stringe o si allarga per tutti senza spedire un APK, che e' la
     * sola manopola che esiste quando le chiavi sono di tutti e i telefoni no.
     * Letto una volta l'ora; se non c'e' resta quello che si sa.
     */
    private fun refreshCap() {
        val now = System.currentTimeMillis()
        if (now - capAt < CAP_EVERY_MS) return
        capAt = now
        val cap = Archive.read("rpc", CAP_EVERY_MS)?.optInt("cap", -1)?.takeIf { it >= 0 } ?: return
        if (cap != pool.cap) { pool.cap = cap; Log.i(TAG, "cap from archive: $cap") }
    }

    /** Le righe della diagnostica, piu' quante chiamate oggi e il tetto. */
    fun report(): Pair<List<RpcPool.Line>, Pair<Int, Int>> {
        val now = System.currentTimeMillis()
        return pool.report(now) to (pool.usedToday(now) to pool.cap)
    }
}
