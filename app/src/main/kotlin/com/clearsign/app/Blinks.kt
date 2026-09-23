package com.clearsign.app

import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Solana Actions, the thing behind a Blink. A link points at an API: GET says what it is
 * (icon, title, description, buttons with parameters), POST with the account gives back a
 * transaction, which goes where every transaction goes, the receipt, then the fingerprint.
 * Spec read 16 Sep 2026 (solana.com/docs/advanced/actions).
 */
object Blinks {
    const val MAINNET = "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp"

    /** A link somebody opened or pasted, waiting for its sheet. */
    val incoming = mutableStateOf<String?>(null)

    data class Param(val name: String, val label: String, val type: String, val required: Boolean, val options: List<Pair<String, String>>)
    data class Button(val label: String, val href: String, val params: List<Param>)
    data class Action(val apiUrl: String, val icon: String?, val title: String, val description: String, val label: String, val disabled: Boolean, val buttons: List<Button>)
    data class Built(val tx: ByteArray, val message: String?)

    fun looksLike(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("solana-action:", true) || t.contains("?action=") || t.contains("&action=") || t.contains("://dial.to/", true)
    }

    /**
     * From what was pasted to the API URL, pure: `?action=<encoded>`, then
     * `solana-action:<url>`, else the URL as it is (an actions.json may still
     * map it; see [resolveWithRules]).
     */
    fun actionUrl(text: String): String? {
        val t = text.trim()
        if (t.startsWith("solana-action:", true)) return t.substring("solana-action:".length).let { dec(it) }.takeIf { it.startsWith("https://") }
        val q = runCatching { URL(t).query }.getOrNull()
        if (q != null) {
            val p = q.split('&').firstOrNull { it.startsWith("action=") }?.substringAfter('=')
            if (p != null) {
                val a = dec(p)
                return if (a.startsWith("solana-action:", true)) a.substring("solana-action:".length) else a
            }
        }
        return t.takeIf { it.startsWith("https://") }
    }

    /** The rules of an `actions.json`: a site path pattern (with stars) mapped to an API path. Pure. */
    fun applyRules(rulesJson: String, path: String): String? {
        val rules = JSONObject(rulesJson).optJSONArray("rules") ?: return null
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            val pat = r.optString("pathPattern"); val api = r.optString("apiPath")
            // A star is the only special thing in a pattern: one star inside a
            // segment, two stars across segments. Everything else is literal.
            val sb = StringBuilder("^")
            var j = 0
            while (j < pat.length) {
                when {
                    pat.startsWith("**", j) -> { sb.append("(.*)"); j += 2 }
                    pat[j] == '*' -> { sb.append("([^/]*)"); j++ }
                    else -> { sb.append(Regex.escape(pat[j].toString())); j++ }
                }
            }
            sb.append("$")
            val m = Regex(sb.toString()).matchEntire(path) ?: continue
            val captured = m.groupValues.drop(1).firstOrNull() ?: ""
            return if (api.contains('*')) api.replace("**", captured).replace("*", captured) else api
        }
        return null
    }

    fun fetch(apiUrl: String): Action? {
        val o = get(apiUrl) ?: return null
        return parseAction(apiUrl, o)
    }

    /** Pure: the GET body to a card. */
    fun parseAction(apiUrl: String, o: JSONObject): Action? {
        val title = o.optString("title").takeIf { it.isNotEmpty() } ?: return null
        val links = o.optJSONObject("links")?.optJSONArray("actions")
        val base = URL(apiUrl)
        fun abs(href: String) = if (href.startsWith("http")) href else URL(base, href).toString()
        val buttons = ArrayList<Button>()
        if (links != null) for (i in 0 until links.length()) {
            val b = links.optJSONObject(i) ?: continue
            val ps = b.optJSONArray("parameters")?.let { arr ->
                (0 until arr.length()).mapNotNull { j ->
                    val p = arr.optJSONObject(j) ?: return@mapNotNull null
                    val opts = p.optJSONArray("options")?.let { oa -> (0 until oa.length()).mapNotNull { k -> oa.optJSONObject(k)?.let { it.optString("label") to it.optString("value") } } } ?: emptyList()
                    Param(p.optString("name"), p.optString("label").ifEmpty { p.optString("name") }, p.optString("type", "text"), p.optBoolean("required", false), opts)
                }
            } ?: emptyList()
            buttons += Button(b.optString("label"), abs(b.optString("href")), ps)
        }
        if (buttons.isEmpty()) buttons += Button(o.optString("label").ifEmpty { title }, apiUrl, emptyList())
        return Action(apiUrl, o.optString("icon").takeIf { it.isNotEmpty() }, title, o.optString("description"), o.optString("label"), o.optBoolean("disabled", false), buttons)
    }

    /** `{amount}` in the href becomes the value typed. Pure. */
    fun fill(href: String, values: Map<String, String>): String {
        var h = href
        values.forEach { (k, v) -> h = h.replace("{$k}", URLEncoder.encode(v, "UTF-8")) }
        return h
    }

    fun build(href: String, account: String, extra: Map<String, String> = emptyMap()): Built? {
        val body = JSONObject().put("account", account)
        if (extra.isNotEmpty()) body.put("data", JSONObject(extra))
        val o = post(href, body) ?: return null
        val b64 = o.optString("transaction").takeIf { it.isNotEmpty() } ?: return null
        val tx = runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull() ?: return null
        return Built(tx, o.optString("message").takeIf { it.isNotEmpty() })
    }

    // ---- the registry: hosts Dialect has looked at ---------------------------

    enum class Standing { TRUSTED, BLOCKED, UNKNOWN }
    @Volatile private var registry: Pair<Long, Map<String, String>>? = null

    fun standing(apiUrl: String): Standing {
        val host = runCatching { URL(apiUrl).host.lowercase() }.getOrNull() ?: return Standing.UNKNOWN
        val map = registry?.takeIf { System.currentTimeMillis() - it.first < 86_400_000L }?.second ?: run {
            val o = get("https://actions-registry.dial.to/all") ?: return Standing.UNKNOWN
            val out = HashMap<String, String>()
            val arr = o.optJSONArray("actions") ?: o.optJSONArray("websites") ?: JSONArray()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out[it.optString("host").lowercase()] = it.optString("state").lowercase() }
            registry = System.currentTimeMillis() to out
            out
        }
        return when (map[host] ?: map.entries.firstOrNull { host.endsWith("." + it.key) }?.value) { "trusted" -> Standing.TRUSTED; "blocked", "malicious" -> Standing.BLOCKED; else -> Standing.UNKNOWN }
    }

    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun headers(c: HttpURLConnection) {
        c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("X-Action-Version", "2.4"); c.setRequestProperty("X-Blockchain-Ids", MAINNET)
    }
    private fun get(url: String): JSONObject? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 15_000; headers(this) }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect(); body?.let { JSONObject(it) }
    } catch (e: Exception) { null }
    private fun post(url: String, body: JSONObject): JSONObject? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = "POST"; doOutput = true; connectTimeout = 8_000; readTimeout = 20_000; headers(this); setRequestProperty("Content-Type", "application/json") }
        OutputStreamWriter(c.outputStream).use { it.write(body.toString()) }
        val code = c.responseCode
        val resp = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect(); resp?.let { JSONObject(it) }
    } catch (e: Exception) { null }
}
