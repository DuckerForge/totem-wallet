package com.clearsign.app

import android.content.Context
import com.clearsign.core.AddressTrust
import org.json.JSONArray
import org.json.JSONObject

/**
 * The on-device address book: saved contacts (address to label) and the addresses paid
 * before. It powers the trust badges (trusted, known, new) and feeds [AddressTrust.lookalikeOf],
 * so poisoning look-alikes are caught against addresses the person knows. Plain
 * SharedPreferences: public addresses and labels, never keys.
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

    /**
     * The trust model from the local address book (empty when nothing saved, so everything reads
     * NEW, which the receipt treats as noise). Keys that are yours count as yours: the engine
     * compared with the main account only, and the agent's budget is another key we generated ten
     * seconds earlier, so funding it said "never-seen recipient, brand-new wallet, check carefully",
     * true to the letter and misleading. Crying wolf on your own wallet teaches people to ignore warnings.
     */
    private fun mine(ctx: Context): Map<String, String> = buildMap {
        runCatching { SessionWallet.current(ctx)?.pubkey }.getOrNull()?.let {
            put(it, ctx.getString(R.string.trust_my_budget))
        }
        // Between the quote and the signature the new budget is not on disk yet.
        runCatching { SessionWallet.preparedPubkey }.getOrNull()?.let {
            put(it, ctx.getString(R.string.trust_my_budget))
        }
        runCatching { Settings.watchWallet(ctx) }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
            put(it, ctx.getString(R.string.trust_my_wallet))
        }
    }

    fun addressTrust(ctx: Context): AddressTrust =
        AddressTrust(allowlist = allowlist(ctx) + mine(ctx), history = history(ctx))

    private fun shorten(a: String, ends: Int = 4) =
        if (a.length <= ends * 2) a else "${a.take(ends)}…${a.takeLast(ends)}"
}
