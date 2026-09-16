package com.clearsign.app

import android.content.Context
import com.clearsign.core.AddressTrust
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local, on-device address book: saved contacts (address → label) and the set of
 * addresses the user has paid before. This is what powers the trust badges
 * (trusted / known / new) and — crucially — feeds [AddressTrust.lookalikeOf] so
 * address-poisoning look-alikes are detected against addresses the user knows.
 *
 * Stored in plain SharedPreferences: it is per-device, non-sensitive metadata
 * (public addresses + user labels), never keys.
 */
object Contacts {
    private const val PREFS = "clearsign_contacts"
    private const val KEY_ALLOW = "allow"   // JSON object: address -> label
    private const val KEY_HIST = "hist"     // JSON array of addresses

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun allowlist(ctx: Context): Map<String, String> {
        val raw = prefs(ctx).getString(KEY_ALLOW, null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            buildMap { o.keys().forEach { put(it, o.getString(it)) } }
        } catch (_: Exception) { emptyMap() }
    }

    fun history(ctx: Context): Set<String> {
        val raw = prefs(ctx).getString(KEY_HIST, null) ?: return emptySet()
        return try {
            val a = JSONArray(raw)
            buildSet { for (i in 0 until a.length()) add(a.getString(i)) }
        } catch (_: Exception) { emptySet() }
    }

    fun saveContact(ctx: Context, address: String, label: String) {
        val map = allowlist(ctx).toMutableMap()
        map[address] = label.ifBlank { shorten(address) }
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        prefs(ctx).edit().putString(KEY_ALLOW, o.toString()).apply()
    }

    /** Contacts that came in by touch, signed by the other phone. A different kind of known. */
    fun markVerified(ctx: Context, address: String) {
        val set = verified(ctx).toMutableSet(); set.add(address)
        prefs(ctx).edit().putString("verified", JSONArray(set.toList()).toString()).apply()
    }
    fun verified(ctx: Context): Set<String> = runCatching {
        val a = JSONArray(prefs(ctx).getString("verified", null) ?: return emptySet())
        buildSet { for (i in 0 until a.length()) add(a.getString(i)) }
    }.getOrDefault(emptySet())

    fun addHistory(ctx: Context, address: String) {
        val set = history(ctx).toMutableSet()
        if (set.add(address)) {
            prefs(ctx).edit().putString(KEY_HIST, JSONArray(set.toList()).toString()).apply()
        }
    }

    /** Build the trust model from the local address book (empty allowlist/history
     *  when nothing saved — then everything reads as NEW, which the receipt
     *  layer treats as noise rather than a warning). */
    fun addressTrust(ctx: Context): AddressTrust =
        AddressTrust(allowlist = allowlist(ctx), history = history(ctx))

    private fun shorten(a: String, ends: Int = 4) =
        if (a.length <= ends * 2) a else "${a.take(ends)}…${a.takeLast(ends)}"
}
