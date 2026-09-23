package com.clearsign.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Who is connected to this wallet, and the ability to end it. MWA hands a dApp an auth token
 * on first approval so it can return without asking, and the wallet is meant to remember:
 * this app held no record, `onReauthorizeRequest` said yes to everything forever, and no
 * screen could say to whom. This is the record, one row per identity, checked when it
 * returns; revoking declines the next reauthorize and the dApp asks again in front of you.
 * Deliberately absent: any standing permission to move money. A connection means "this app
 * may ask"; every signature is still a separate decision with your fingerprint.
 */
object Connections {
    private const val PREFS = "apex_connections"
    private const val KEY = "list"

    /**
     * [id] is the host when there is one, what a person recognizes and what phishing must get
     * past. A native app with no URI falls back to its package, then to its claimed name.
     */
    data class Conn(
        val id: String,
        val name: String,
        val host: String?,
        val icon: String?,
        val account: String,
        val firstAt: Long,
        val lastAt: Long,
        val revoked: Boolean = false,
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Conn> = runCatching {
        val a = JSONArray(prefs(ctx).getString(KEY, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::fromJson) }
    }.getOrDefault(emptyList()).sortedByDescending { it.lastAt }

    fun idOf(host: String?, pkg: String?, name: String) = host ?: pkg ?: name

    /** Approved just now. A connection that was revoked and approved again is live again. */
    fun remember(ctx: Context, id: String, name: String, host: String?, icon: String?, account: String) {
        val now = System.currentTimeMillis()
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) {
            list[i] = list[i].copy(name = name, host = host, icon = icon ?: list[i].icon, account = account, lastAt = now, revoked = false)
        } else {
            list += Conn(id, name, host, icon, account, now, now)
        }
        save(ctx, list)
    }

    /** It came back. Only moves the clock; it never creates a row on its own. */
    fun touch(ctx: Context, id: String) {
        val list = all(ctx)
        if (list.none { it.id == id }) return
        save(ctx, list.map { if (it.id == id) it.copy(lastAt = System.currentTimeMillis()) else it })
    }

    /**
     * May this identity come back without asking? Unknown is yes: an authorization can predate
     * this record, and turning every old connection into a silent failure is how a security
     * feature gets blamed for breaking a wallet. Only an explicit revoke says no.
     */
    fun allowed(ctx: Context, id: String): Boolean = all(ctx).firstOrNull { it.id == id }?.revoked != true

    fun revoke(ctx: Context, id: String) = save(ctx, all(ctx).map { if (it.id == id) it.copy(revoked = true) else it })

    fun forget(ctx: Context, id: String) = save(ctx, all(ctx).filter { it.id != id })

    private fun save(ctx: Context, list: List<Conn>) {
        val a = JSONArray()
        list.forEach { c ->
            a.put(
                JSONObject().put("id", c.id).put("name", c.name).put("host", c.host ?: JSONObject.NULL)
                    .put("icon", c.icon ?: JSONObject.NULL).put("account", c.account)
                    .put("first", c.firstAt).put("last", c.lastAt).put("revoked", c.revoked),
            )
        }
        prefs(ctx).edit().putString(KEY, a.toString()).apply()
    }

    private fun fromJson(o: JSONObject): Conn? {
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        return Conn(
            id = id,
            name = o.optString("name").takeIf { it.isNotEmpty() } ?: id,
            host = o.optString("host").takeIf { it.isNotEmpty() && it != "null" },
            icon = o.optString("icon").takeIf { it.isNotEmpty() && it != "null" },
            account = o.optString("account"),
            firstAt = o.optLong("first", 0L),
            lastAt = o.optLong("last", 0L),
            revoked = o.optBoolean("revoked", false),
        )
    }
}
