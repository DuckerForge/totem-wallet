package com.clearsign.app

import org.json.JSONObject

/**
 * The shared archive, read. No key: the public branch of Firebase where the worker writes
 * facts about the world, and a read costs nobody anything but traffic. What sits here the
 * worker asks once for everyone instead of every phone for itself. Each answer is kept in
 * memory for the time the caller says; a silent archive counts as nothing and the caller
 * falls back to the chain.
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
        // Even a nothing is remembered, for a fifth of the time: an archive missing
        // that row is not asked again every round.
        mem[path] = Kept(if (body == null) now - keepMs + keepMs / 5 else now, body)
        return body
    }

    /**
     * The chain constants the worker publishes every ten minutes: epoch, slot, inflation, the
     * median priority fee. Valid when no older than [maxAgeMs], read from its `at`.
     */
    fun chain(maxAgeMs: Long): JSONObject? {
        val o = read("chain", 10 * 60_000L) ?: return null
        val at = o.optLong("at", 0L)
        return o.takeIf { at > 0 && System.currentTimeMillis() - at < maxAgeMs }
    }
}
