package com.clearsign.app

import org.json.JSONObject

/**
 * The RPC node pool: whom to ask, in what order, what to learn from each answer. Free keys
 * are few and shared, with a monthly quota across every install and a per-second limit
 * that bites first, and a phone cannot know what is left, so it does three things it can
 * do alone. Spread: each install orders providers at random, weighted by quota, with its
 * own seed, so a thousand phones do not hammer one node until it falls. Learn: a `-32099`
 * or a 4xx on a method marks that provider as not doing it. Tell cold from spent: a passing
 * 429 cools for minutes, a 402 or a 429 that stays is a spent month, refused until the 1st.
 * Plus a daily per-phone cap on shared keys that slows the hunt, never the exits, and that
 * the archive can change without an APK. Pure: no Android, no network, clock from outside.
 */
class RpcPool(
    providers: List<Provider>,
    /** A random number made once per install. Never the wallet. */
    private val seed: Long,
    private val store: Store = NoStore,
) {
    /** A provider: name, URL, and what it can do. The URL is never printed. */
    data class Provider(
        val name: String,
        val url: String,
        /** Requests per second the key allows, for the whole world. */
        val perSecond: Int,
        /** Quanto spesso va scelto rispetto agli altri: proporzionale alla quota mensile. */
        val weight: Int,
        /** Serves the DAS API (`getAsset*`), which is Helius only. */
        val das: Boolean = false,
        /** Accetta `sendTransaction` da noi: un gratuito su nodi pubblici no. */
        val sends: Boolean = true,
        /** Last resort: tried only after all the others. No key, no quota of ours. */
        val lastResort: Boolean = false,
        /** Methods it is already known not to do. */
        val unsupported: Set<String> = emptySet(),
    )

    /** Dove il pool ricorda quello che ha imparato, da un avvio all'altro. */
    interface Store {
        fun get(key: String): String?
        fun put(key: String, value: String?)
    }

    object NoStore : Store {
        override fun get(key: String): String? = null
        override fun put(key: String, value: String?) {}
    }

    /** What a node answered, reduced to what changes a decision. */
    enum class Outcome { OK, REFUSED, RATE_LIMITED, EXHAUSTED, UNSUPPORTED, FORBIDDEN, SERVER, TRANSPORT }

    enum class State { OK, COLD, EXHAUSTED }

    /** One diagnostics row. The name, never the URL. */
    data class Line(val name: String, val state: State, val calls: Long, val failures: Long, val lastError: String?, val unsupported: Set<String>)

    private class Health(declared: Set<String>) {
        @Volatile var coldUntil = 0L
        @Volatile var exhaustedUntil = 0L
        @Volatile var rateStreak = 0
        val unsupported: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { addAll(declared) }
        @Volatile var calls = 0L
        @Volatile var failures = 0L
        @Volatile var lastError: String? = null
    }

    val providers: List<Provider> = providers
    private val health: Map<String, Health> = providers.associate { p ->
        p.name to Health(p.unsupported + store.get("unsupported:${p.name}").orEmpty().split(',').filter { it.isNotBlank() }).also { h ->
            h.exhaustedUntil = store.get("exhausted:${p.name}")?.toLongOrNull() ?: 0L
        }
    }

    // ---- l'ordine -------------------------------------------------------------

    /**
     * In what order to ask [method], now. The own node first if there is one, the writer's
     * choice; then keyed providers, healthy and capable, in a weighted random order stable for
     * this install; the last resorts at the end.
     */
    fun lanes(method: String, now: Long, own: Provider? = null): List<Provider> {
        val das = method.startsWith("getAsset")
        val send = method == "sendTransaction"
        fun capable(p: Provider) = method !in health.getValue(p.name).unsupported && (!das || p.das) && (!send || p.sends)
        val keyed = providers.filter { !it.lastResort && capable(it) && state(it.name, now) == State.OK }
        val last = providers.filter { it.lastResort && capable(it) && state(it.name, now) != State.EXHAUSTED }
        return listOfNotNull(own) + weighted(keyed) + last
    }

    /**
     * A weighted random permutation, always the same for the same seed: each provider draws a
     * number from its weight and sorts by it. Heavier ones come first more often across
     * installs; on one install the order is fixed, so a rebooted phone keeps its queue.
     */
    private fun weighted(list: List<Provider>): List<Provider> =
        list.sortedBy { p ->
            val u = uniform(seed, p.name).coerceIn(1e-9, 1.0)
            -Math.log(u) / p.weight.coerceAtLeast(1)
        }

    /**
     * A number between zero and one from seed and name, well mixed: `java.util.Random` gave
     * the same first number to nearby seeds, and a thousand phones all picked one provider.
     * This is splitmix64.
     */
    private fun uniform(seed: Long, name: String): Double {
        var z = seed + name.hashCode().toLong() * -7046029254386353131L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        z = z xor (z ushr 31)
        return (z ushr 11) * (1.0 / (1L shl 53))
    }

    fun state(name: String, now: Long): State {
        val h = health[name] ?: return State.OK
        return when {
            h.exhaustedUntil > now -> State.EXHAUSTED
            h.coldUntil > now -> State.COLD
            else -> State.OK
        }
    }

    // ---- imparare ----------------------------------------------------------------

    /**
     * What to make of [name]'s answer to [method]: an OK warms it, a deterministic refusal says
     * nothing about the node, everything else cools it for a time that depends on what happened.
     */
    fun onOutcome(name: String, method: String, outcome: Outcome, now: Long, detail: String? = null) {
        val h = health[name] ?: return
        h.calls++
        when (outcome) {
            Outcome.OK -> { h.rateStreak = 0; h.coldUntil = 0L }
            Outcome.REFUSED -> {}
            Outcome.RATE_LIMITED -> {
                h.failures++; h.lastError = detail ?: "429"
                h.rateStreak++
                h.coldUntil = now + (COLD_RATE_MS * h.rateStreak).coerceAtMost(COLD_RATE_MAX_MS)
                // A 429 that no longer passes is a spent month, not a crowded second.
                if (h.rateStreak >= RATE_STREAK_EXHAUSTED) exhaust(name, h, now)
            }
            Outcome.EXHAUSTED -> { h.failures++; h.lastError = detail ?: "402"; exhaust(name, h, now) }
            Outcome.UNSUPPORTED -> {
                h.lastError = detail ?: "unsupported $method"
                if (h.unsupported.add(method)) store.put("unsupported:$name", h.unsupported.sorted().joinToString(","))
            }
            Outcome.FORBIDDEN -> { h.failures++; h.lastError = detail ?: "403"; h.coldUntil = now + COLD_FORBIDDEN_MS }
            Outcome.SERVER -> { h.failures++; h.lastError = detail ?: "5xx"; h.coldUntil = now + COLD_SERVER_MS }
            Outcome.TRANSPORT -> { h.failures++; h.lastError = detail ?: "transport"; h.coldUntil = now + COLD_TRANSPORT_MS }
        }
    }

    private fun exhaust(name: String, h: Health, now: Long) {
        h.exhaustedUntil = monthEnd(now)
        h.rateStreak = 0
        store.put("exhausted:$name", h.exhaustedUntil.toString())
    }

    fun report(now: Long): List<Line> = providers.map { p ->
        val h = health.getValue(p.name)
        Line(p.name, state(p.name, now), h.calls, h.failures, h.lastError, h.unsupported.toSet())
    }

    // ---- il tetto del giorno ---------------------------------------------------

    /** How many calls on shared keys this phone made today. Last resorts and the own node do not count: they spend nothing of ours. */
    @Volatile private var day: Long = -1L
    @Volatile private var used: Int = 0

    /** Il tetto. Zero vuol dire nessuno. */
    @Volatile var cap: Int = store.get("cap")?.toIntOrNull() ?: DEFAULT_CAP
        set(v) { field = v.coerceAtLeast(0); store.put("cap", field.toString()) }

    init {
        store.get("day")?.split(':')?.takeIf { it.size == 2 }?.let { (d, n) ->
            day = d.toLongOrNull() ?: -1L; used = n.toIntOrNull() ?: 0
        }
    }

    fun countCall(p: Provider, now: Long) {
        if (p.lastResort || p !in providers) return
        val d = now / DAY_MS
        if (d != day) { day = d; used = 0 }
        used++
        if (used % 10 == 0) store.put("day", "$day:$used")
    }

    fun usedToday(now: Long): Int = if (now / DAY_MS == day) used else 0

    /** Sopra il tetto: la caccia si ferma, le uscite no. */
    fun overBudget(now: Long): Boolean = cap > 0 && usedToday(now) >= cap

    companion object {
        const val DAY_MS = 86_400_000L
        const val COLD_TRANSPORT_MS = 30_000L
        const val COLD_SERVER_MS = 2 * 60_000L
        const val COLD_RATE_MS = 2 * 60_000L
        const val COLD_RATE_MAX_MS = 30 * 60_000L
        const val COLD_FORBIDDEN_MS = 6 * 3600_000L
        const val RATE_STREAK_EXHAUSTED = 6
        /**
         * Calls per day on shared keys, per phone, unless the archive says otherwise. A watched
         * wallet makes about a hundred, an agent hunting all day about six hundred: at this cap
         * an agent without its own node hunts most of the day, then only watches.
         */
        const val DEFAULT_CAP = 600

        /** The last instant of the month, UTC: when a spent key comes back to life. */
        fun monthEnd(now: Long): Long {
            val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            c.timeInMillis = now
            c.set(java.util.Calendar.DAY_OF_MONTH, 1)
            c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
            c.add(java.util.Calendar.MONTH, 1)
            return c.timeInMillis
        }

        /** The truth table: from an HTTP code (null = fallen transport) and a JSON-RPC error (null = none) to what changes a decision. */
        fun classify(httpCode: Int?, error: JSONObject?): Outcome {
            if (httpCode == null) return Outcome.TRANSPORT
            if (httpCode !in 200..299) return when {
                httpCode == 429 -> Outcome.RATE_LIMITED
                httpCode == 402 -> Outcome.EXHAUSTED
                httpCode >= 500 -> Outcome.SERVER
                else -> Outcome.FORBIDDEN
            }
            if (error == null) return Outcome.OK
            val code = error.optInt("code")
            val msg = error.optString("message").lowercase()
            return when {
                code == -32099 || code == -32601 || msg.contains("method not") || msg.contains("not supported") || msg.contains("not available") || msg.contains("disabled") -> Outcome.UNSUPPORTED
                msg.contains("credit") || msg.contains("quota") || msg.contains("exceeded your") || msg.contains("your plan") || msg.contains("upgrade your") -> Outcome.EXHAUSTED
                code == 429 || code == -32429 || msg.contains("rate") || msg.contains("too many") || msg.contains("limit exceeded") -> Outcome.RATE_LIMITED
                else -> Outcome.REFUSED
            }
        }
    }
}
