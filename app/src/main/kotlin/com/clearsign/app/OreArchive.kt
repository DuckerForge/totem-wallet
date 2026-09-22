package com.clearsign.app

import com.clearsign.core.PastRound
import org.json.JSONObject

/**
 * I giri chiusi di ORE pubblicati dal worker in `/clearsign/ore.json`: uno al
 * minuto, gli ultimi centoventi. Vale se non e' piu' vecchio di [maxAgeMs].
 * Bloccante: chiamare su IO. Vedi `tools/seeker-worker/src/ore.js`.
 */
object OreArchive {
    fun rounds(maxAgeMs: Long = 30 * 60_000L): List<PastRound> {
        val o = Archive.read("ore", 60_000L) ?: return emptyList()
        val at = o.optLong("at", 0L)
        if (at <= 0L || System.currentTimeMillis() - at > maxAgeMs) return emptyList()
        val arr = o.optJSONArray("rounds") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parse(it) } }
    }

    fun parse(r: JSONObject): PastRound? {
        val d = r.optJSONArray("d") ?: return null
        val c = r.optJSONArray("c")
        if (d.length() < 25) return null
        return PastRound(
            id = r.optLong("id"), win = r.optInt("win", -1).takeIf { it in 0 until 25 } ?: return null,
            split = r.optBoolean("split"),
            deployed = LongArray(25) { d.optLong(it) }, count = LongArray(25) { c?.optLong(it) ?: 0L },
            miners = r.optLong("m"), top = r.optString("top").takeIf { it.isNotBlank() && it != "null" },
        )
    }
}
