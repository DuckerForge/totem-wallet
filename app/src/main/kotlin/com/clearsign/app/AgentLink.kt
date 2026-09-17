package com.clearsign.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The link between this phone and the bridge the agent talks through.
 *
 * The phone is always the client: it polls the bridge for work, decides, and
 * posts the verdict. Nothing ever needs to reach into the phone, so there is no
 * open port here and the same code will talk to a hosted relay one day. Only
 * unsigned transactions, declared intents and verdicts travel over this link;
 * the envelope key does not exist anywhere else.
 */
object AgentLink {
    private const val PREFS = "apex_link"

    data class Link(val host: String, val token: String, val name: String, val pairedAt: Long)

    sealed class State {
        object Off : State()
        data class On(val name: String, val lastAction: String?, val lastAt: Long, val healthy: Boolean) : State()
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> get() = _state

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(ctx: Context): Link? {
        val p = prefs(ctx)
        val host = p.getString("host", null) ?: return null
        val token = p.getString("token", null) ?: return null
        return Link(host, token, p.getString("name", "agent") ?: "agent", p.getLong("pairedAt", 0L))
    }

    /** `apex://agent/pair?host=http://192.168.1.10:8765&token=…&name=claude-code` */
    fun pair(ctx: Context, uri: Uri): Link? {
        val host = uri.getQueryParameter("host")?.trimEnd('/') ?: return null
        val token = uri.getQueryParameter("token")?.takeIf { it.length >= 16 } ?: return null
        if (!(host.startsWith("http://") || host.startsWith("https://"))) return null
        // Cleartext only towards your own network, never towards the internet.
        //
        // The app allows cleartext at all for one reason: an agent runs on a
        // machine on the same wifi, at an address like http://192.168.1.10:8765,
        // and Android has no way to permit cleartext for "the local network"
        // alone. So the permission stays open at the manifest and the narrowing
        // happens here, where we actually know the address. A pairing link that
        // sends a token in the clear across the internet is refused.
        if (host.startsWith("http://") && !isLocal(host)) return null
        val name = uri.getQueryParameter("name")?.take(40)?.ifBlank { null } ?: "agent"
        val link = Link(host, token, name, System.currentTimeMillis())
        prefs(ctx).edit().putString("host", host).putString("token", token).putString("name", name).putLong("pairedAt", link.pairedAt)
            .remove("last").remove("lastAt").apply()
        _state.value = State.On(name, null, 0L, healthy = false)
        return link
    }

    /** True for loopback and the three private ranges, plus the names wifi hands out. */
    private fun isLocal(url: String): Boolean {
        val h = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return false
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan")) return true
        val p = h.split(".").mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return when (p[0]) {
            10, 127 -> true
            192 -> p[1] == 168
            172 -> p[1] in 16..31
            169 -> p[1] == 254
            else -> false
        }
    }

    fun forget(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        _state.value = State.Off
    }

    fun restoreState(ctx: Context) {
        val l = current(ctx) ?: run { _state.value = State.Off; return }
        val p = prefs(ctx)
        _state.value = State.On(l.name, p.getString("last", null), p.getLong("lastAt", 0L), healthy = false)
    }

    fun noteAction(ctx: Context, text: String) {
        val at = System.currentTimeMillis()
        prefs(ctx).edit().putString("last", text).putLong("lastAt", at).apply()
        (_state.value as? State.On)?.let { _state.value = it.copy(lastAction = text, lastAt = at) }
    }

    fun setHealthy(ok: Boolean) { (_state.value as? State.On)?.let { if (it.healthy != ok) _state.value = it.copy(healthy = ok) } }

    // ---- wire ----------------------------------------------------------------

    /** Tell the bridge who we are and what the collar allows, so the model can be told before it builds. */
    fun hello(ctx: Context, link: Link): Boolean {
        val s = SessionWallet.current(ctx)
        val p = SessionWallet.policy(ctx)
        val body = JSONObject()
            .put("envelope", s?.pubkey)
            .put("mode", p?.mode?.name)
            .put("perTxLamports", p?.perTxLamports).put("dailyLamports", p?.dailyLamports).put("askAboveLamports", p?.askAboveLamports)
            .put("allowedMints", p?.allowedMints?.toList()).put("allowedDestinations", p?.allowedDestinations?.toList())
            .put("maxTxPerHour", p?.maxTxPerHour).put("expiresAt", p?.expiresAt)
            .put("spentLast24hLamports", SessionWallet.history(ctx).spentLast24hLamports)
        return post(link, "/link/hello", body) != null
    }

    /** Long-poll for the next job. Null when there is none (or the bridge is unreachable). */
    fun next(link: Link): AgentBroker.Job? {
        val o = request(link, "GET", "/link/next", null, readTimeoutMs = 35_000) ?: return null
        val id = o.optString("id").ifBlank { return null }
        val txB64 = o.optString("tx").ifBlank { return null }
        val tx = runCatching { Base64.decode(txB64, Base64.URL_SAFE or Base64.NO_WRAP) }.getOrNull()
            ?: runCatching { Base64.decode(txB64, Base64.DEFAULT) }.getOrNull() ?: return null
        return AgentBroker.Job(id, tx, o.optString("intent", "{}"), o.optString("cluster").ifBlank { null }, o.optString("agent").ifBlank { link.name })
    }

    fun report(link: Link, jobId: String, verdict: AgentBroker.Verdict): Boolean {
        val body = verdict.toJson().put("id", jobId)
        return post(link, "/link/result", body) != null
    }

    private fun post(link: Link, path: String, body: JSONObject): JSONObject? = request(link, "POST", path, body, 15_000)

    private fun request(link: Link, method: String, path: String, body: JSONObject?, readTimeoutMs: Int): JSONObject? = runCatching {
        val c = (URL(link.host + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000; readTimeout = readTimeoutMs
            setRequestProperty("Authorization", "Bearer " + link.token)
            setRequestProperty("Accept", "application/json")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        try {
            if (body != null) c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            setHealthy(code in 200..299)
            if (code == 204) return@runCatching JSONObject()
            if (code !in 200..299) return@runCatching null
            val bytes = c.inputStream.use { ins -> ByteArrayOutputStream().also { ins.copyTo(it) }.toByteArray() }
            if (bytes.isEmpty()) JSONObject() else JSONObject(String(bytes))
        } finally { c.disconnect() }
    }.getOrElse { setHealthy(false); null }
}
