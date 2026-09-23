package com.clearsign.app

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * dApp Store reputation for the app that opened us, from the free Seeker Tracker catalog (no
 * key): rating, review count, publisher and whether it is verified, version, last update. Or
 * "not listed", itself a caution for a sideloaded app.
 */
data class StoreRep(
    val listed: Boolean,
    val name: String?,
    val rating: Double?,
    val reviews: Int?,
    val publisher: String?,
    val verified: Boolean,
    val version: String?,
    val updatedOn: Long?,
) {
    companion object { val NOT_LISTED = StoreRep(false, null, null, null, null, false, null, null) }
}

object StoreReputation {
    private const val TAG = "ClearSign-Store"
    private val cache = ConcurrentHashMap<String, StoreRep>()

    /** Blocking; never on the main thread. */
    fun fetch(pkg: String): StoreRep? {
        cache[pkg]?.let { return it }
        val text = try {
            val c = (URL("https://seekertracker.com/api/dappstore?package=$pkg").openConnection() as HttpURLConnection)
                .apply { connectTimeout = 5000; readTimeout = 8000; setRequestProperty("Accept", "application/json") }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
            c.disconnect(); body
        } catch (e: Exception) { Log.w(TAG, "store fetch failed: ${e.message}"); return null }
        val o = runCatching { JSONObject(text ?: return null) }.getOrNull() ?: return null
        if (o.has("error")) { cache[pkg] = StoreRep.NOT_LISTED; return StoreRep.NOT_LISTED }
        val app = o.optJSONObject("app") ?: return null
        val lr = app.optJSONObject("lastRelease")
        val rating = app.optJSONObject("rating")
        val reviews = rating?.optJSONArray("reviewsByRating")?.let { a -> (0 until a.length()).sumOf { a.optInt(it) } }
        val pub = lr?.optJSONObject("publisherDetails")
        val rep = StoreRep(
            listed = app.optString("status") == "active",
            name = lr?.optString("displayName")?.takeIf { it.isNotEmpty() },
            rating = rating?.optDouble("rating")?.takeIf { !it.isNaN() },
            reviews = reviews,
            publisher = pub?.optString("name")?.takeIf { it.isNotEmpty() },
            verified = lr?.optBoolean("claimed") == true,
            version = lr?.optJSONObject("androidDetails")?.optString("version")?.takeIf { it.isNotEmpty() },
            updatedOn = lr?.optString("updatedOn")?.let { parseIso(it) },
        )
        cache[pkg] = rep
        return rep
    }

    private fun parseIso(s: String): Long? = runCatching {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        f.parse(s.substringBefore('.'))?.time
    }.getOrNull()
}
