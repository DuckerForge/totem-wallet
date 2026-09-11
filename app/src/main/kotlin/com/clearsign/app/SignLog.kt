package com.clearsign.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local "registro firme": every receipt the user approved, newest first. Plain
 * metadata (what was shown, when, for which dApp, the resulting signature) —
 * never keys. Feeds the home screen history and the trust model via [Contacts].
 */
object SignLog {
    private const val PREFS = "clearsign_signlog"
    private const val KEY = "entries"
    private const val MAX = 200

    data class Entry(
        val at: Long,               // epoch millis
        val dApp: String,
        val host: String?,
        val cluster: String?,
        val txCount: Int,
        val outflows: List<String>, // rendered lines, e.g. "−0.5 SOL"
        val inflows: List<String>,
        val recipient: String?,     // primary recipient of the first tx
        val recipientLabel: String?,
        val signature: String?,     // base58, when the tx was sent by ClearSign
        val sent: Boolean,
        val kind: String,           // "tx" | "message" | "signin" | "theme" | "revoke" | "close" | "send"
        val attestation: String? = null,    // the statement this app signed (see Attestation)
        val attestationSig: String? = null, // its ES256 signature
    )

    /** How often this dApp has been signed with, and since when (for "3rd signature · since Sep 10"). */
    data class DappStats(val count: Int, val firstAt: Long?)

    fun statsFor(ctx: Context, host: String?, name: String): DappStats {
        val entries = all(ctx).filter { e -> if (host != null) e.host == host else e.host == null && e.dApp == name }
        return DappStats(entries.size, entries.minOfOrNull { it.at })
    }

    fun record(ctx: Context, e: Entry) {
        val arr = readRaw(ctx)
        val o = JSONObject()
            .put("at", e.at).put("dApp", e.dApp).put("host", e.host ?: JSONObject.NULL)
            .put("cluster", e.cluster ?: JSONObject.NULL).put("txCount", e.txCount)
            .put("out", JSONArray(e.outflows)).put("in", JSONArray(e.inflows))
            .put("recipient", e.recipient ?: JSONObject.NULL).put("recipientLabel", e.recipientLabel ?: JSONObject.NULL)
            .put("sig", e.signature ?: JSONObject.NULL).put("sent", e.sent).put("kind", e.kind)
            .put("att", e.attestation ?: JSONObject.NULL).put("attSig", e.attestationSig ?: JSONObject.NULL)
        val next = JSONArray().put(o)
        for (i in 0 until minOf(arr.length(), MAX - 1)) next.put(arr.get(i))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, next.toString()).apply()
    }

    fun all(ctx: Context): List<Entry> {
        val arr = readRaw(ctx)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            fun strs(k: String) = o.optJSONArray(k)?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
            Entry(
                at = o.optLong("at"), dApp = o.optString("dApp"), host = o.optString("host").takeIf { !o.isNull("host") },
                cluster = o.optString("cluster").takeIf { !o.isNull("cluster") }, txCount = o.optInt("txCount", 1),
                outflows = strs("out"), inflows = strs("in"),
                recipient = o.optString("recipient").takeIf { !o.isNull("recipient") },
                recipientLabel = o.optString("recipientLabel").takeIf { !o.isNull("recipientLabel") },
                signature = o.optString("sig").takeIf { !o.isNull("sig") }, sent = o.optBoolean("sent"),
                kind = o.optString("kind", "tx"),
                attestation = o.optString("att").takeIf { !o.isNull("att") },
                attestationSig = o.optString("attSig").takeIf { !o.isNull("attSig") },
            )
        }
    }

    private fun readRaw(ctx: Context): JSONArray =
        runCatching { JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: "[]") }
            .getOrDefault(JSONArray())
}
