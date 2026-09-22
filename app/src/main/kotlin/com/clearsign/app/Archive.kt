package com.clearsign.app

import org.json.JSONObject

/**
 * L'archivio condiviso, letto. Senza chiave: e' il ramo pubblico di Firebase
 * dove il worker scrive i fatti sul mondo, e una lettura non costa niente a
 * nessuno se non traffico. Quello che sta qui lo chiede una volta il worker
 * per tutti invece di ogni telefono per se'.
 *
 * Ogni risposta si tiene in memoria per il tempo detto da chi la chiede, e un
 * archivio che non risponde vale come niente: chi chiama ripiega sulla catena,
 * come faceva prima che l'archivio esistesse.
 */
object Archive {
    val available: Boolean get() = BuildConfig.ARCHIVE_URL.isNotBlank()

    private data class Kept(val at: Long, val body: JSONObject?)
    private val mem = java.util.concurrent.ConcurrentHashMap<String, Kept>()

    /** `/clearsign/<path>.json`, ricordato per [keepMs]. Bloccante: chiamare su IO. */
    fun read(path: String, keepMs: Long): JSONObject? {
        val base = BuildConfig.ARCHIVE_URL.takeIf { it.isNotBlank() } ?: return null
        val now = System.currentTimeMillis()
        mem[path]?.let { if (now - it.at < keepMs) return it.body }
        val body = runCatching {
            val c = (java.net.URL(base.trimEnd('/') + "/clearsign/" + path + ".json").openConnection() as java.net.HttpURLConnection)
                .apply { connectTimeout = 6_000; readTimeout = 12_000; setRequestProperty("Accept", "application/json") }
            if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()?.takeIf { it.isNotBlank() && it.trim() != "null" }?.let { runCatching { JSONObject(it) }.getOrNull() }
        // Anche un nulla si ricorda, per un quinto del tempo: un archivio senza
        // quella riga non va richiesto a ogni giro.
        mem[path] = Kept(if (body == null) now - keepMs + keepMs / 5 else now, body)
        return body
    }

    /**
     * Le costanti della catena che il worker pubblica ogni dieci minuti:
     * epoca, slot, inflazione e la commissione di priorita' mediana. Vale se
     * non e' piu' vecchia di [maxAgeMs], letta dal suo `at`.
     */
    fun chain(maxAgeMs: Long): JSONObject? {
        val o = read("chain", 10 * 60_000L) ?: return null
        val at = o.optLong("at", 0L)
        return o.takeIf { at > 0 && System.currentTimeMillis() - at < maxAgeMs }
    }
}
