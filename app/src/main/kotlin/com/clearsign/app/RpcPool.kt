package com.clearsign.app

import org.json.JSONObject

/**
 * Il pool dei nodi RPC: chi chiedere, in che ordine, e cosa imparare da ogni
 * risposta.
 *
 * Le chiavi gratuite sono poche e sono di tutti: una quota al mese per chiave,
 * globale su ogni installazione, e un limite al secondo che morde molto prima
 * della quota. Un telefono non ha modo di sapere quanto resta sulla chiave,
 * quindi qui non si contano crediti per decidere. Si fanno tre cose che un
 * telefono puo' fare da solo:
 *
 *  * **spargere.** Ogni installazione ordina i fornitori a caso, pesati sulla
 *    quota, con un seme suo: mille telefoni non martellano lo stesso nodo in
 *    fila finche' non cade, si dividono.
 *  * **imparare.** Un `-32099` o un 4xx su un metodo scrive che quel fornitore
 *    quel metodo non lo fa, e non glielo si chiede piu'. La tabella dichiarata
 *    e' solo il seme: un piano gratuito cambia senza avvisare.
 *  * **distinguere freddo da finito.** Un 429 passeggero raffredda per minuti.
 *    Un 402, o un 429 che non passa piu', e' un mese esaurito: senza carta
 *    collegata e' un rifiuto fino al primo del mese, e ritentarlo ogni cinque
 *    minuti sarebbe ottomila chiamate buttate.
 *
 * E un tetto giornaliero per telefono sulle chiavi condivise, che chi lo
 * supera non rompe niente: rallenta la caccia, mai le uscite. Il tetto lo
 * puo' cambiare l'archivio, cosi' si stringe o si allarga senza spedire un APK.
 *
 * Puro: niente Android, niente rete, l'orologio passato da fuori. Cosi' si
 * prova sulla JVM.
 */
class RpcPool(
    providers: List<Provider>,
    /** Un numero a caso fatto una volta per installazione. Mai il portafoglio. */
    private val seed: Long,
    private val store: Store = NoStore,
) {
    /** Un fornitore: nome, indirizzo, e quello che puo' fare. L'indirizzo non si stampa mai. */
    data class Provider(
        val name: String,
        val url: String,
        /** Quante richieste al secondo concede la chiave, per tutto il mondo. */
        val perSecond: Int,
        /** Quanto spesso va scelto rispetto agli altri: proporzionale alla quota mensile. */
        val weight: Int,
        /** Serve le API DAS (`getAsset*`), che sono di Helius e basta. */
        val das: Boolean = false,
        /** Accetta `sendTransaction` da noi: un gratuito su nodi pubblici no. */
        val sends: Boolean = true,
        /** Ultima spiaggia: si prova solo dopo tutti gli altri. Nessuna chiave, nessuna quota nostra. */
        val lastResort: Boolean = false,
        /** Metodi che si sa gia' che non fa. */
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

    /** Cosa ha risposto un nodo, ridotto a quello che cambia una decisione. */
    enum class Outcome { OK, REFUSED, RATE_LIMITED, EXHAUSTED, UNSUPPORTED, FORBIDDEN, SERVER, TRANSPORT }

    enum class State { OK, COLD, EXHAUSTED }

    /** Una riga della diagnostica. Il nome, mai l'indirizzo. */
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
     * In che ordine chiedere [method], adesso. Il nodo proprio, se c'e', viene
     * prima di tutto: e' la scelta di chi lo ha scritto. Poi i fornitori con
     * chiave, sani e capaci, in ordine casuale pesato e stabile per questa
     * installazione. In coda le ultime spiagge.
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
     * Una permutazione casuale pesata, sempre la stessa per lo stesso seme:
     * ogni fornitore pesca un numero dal suo peso e si ordina per quello. Chi
     * pesa di piu' esce prima piu' spesso, su tante installazioni; su una sola
     * l'ordine e' fisso, cosi' un telefono che si riavvia non cambia fila.
     */
    private fun weighted(list: List<Provider>): List<Provider> =
        list.sortedBy { p ->
            val u = uniform(seed, p.name).coerceIn(1e-9, 1.0)
            -Math.log(u) / p.weight.coerceAtLeast(1)
        }

    /**
     * Un numero fra zero e uno da seme e nome, ben mescolato: `java.util.Random`
     * dava lo stesso primo numero a semi vicini, e mille telefoni sceglievano
     * tutti lo stesso fornitore. Questo e' splitmix64.
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
     * Cosa fare della risposta di [name] a [method]. Un OK scalda, un rifiuto
     * deterministico non dice niente del nodo, e tutto il resto lo raffredda
     * per un tempo che dipende da cosa e' successo.
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
                // Un 429 che non passa piu' e' un mese finito, non un secondo affollato.
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

    /**
     * Quante chiamate sulle chiavi condivise questo telefono ha fatto oggi. Le
     * ultime spiagge e il nodo proprio non si contano: non consumano niente di
     * nostro.
     */
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
         * Chiamate al giorno sulle chiavi condivise, per telefono, se l'archivio
         * non dice altro. Un portafoglio guardato ne fa un centinaio, un agente
         * a caccia tutto il giorno sui seicento: a questo tetto l'agente senza
         * nodo proprio caccia per gran parte della giornata e poi guarda e basta.
         */
        const val DEFAULT_CAP = 600

        /** L'ultimo istante del mese, UTC: quando una chiave esaurita torna a vivere. */
        fun monthEnd(now: Long): Long {
            val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            c.timeInMillis = now
            c.set(java.util.Calendar.DAY_OF_MONTH, 1)
            c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
            c.add(java.util.Calendar.MONTH, 1)
            return c.timeInMillis
        }

        /**
         * La tabella di verita': da un codice HTTP (null = trasporto caduto) e da
         * un errore JSON-RPC (null = nessuno) a quello che cambia una decisione.
         */
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
