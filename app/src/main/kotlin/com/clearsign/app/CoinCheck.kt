package com.clearsign.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Last check before buying: does the web know something the numbers don't (a team that rugged
 * before, a fresh exploit). Runs once on the chosen coin, after the gates, and the verdict goes
 * to the shared archive ([Shared]) so other phones skip the search. Veto only. No key, no
 * network, no answer or a non-Anthropic model all give [Verdict.Unknown], which never blocks.
 */
object CoinCheck {
    private const val TAG = "Apex-CoinCheck"

    sealed class Verdict {
        object Ok : Verdict()
        data class Stop(val reason: String) : Verdict()
        object Unknown : Verdict()
    }

    /**
     * Shared verdicts in the archive (`/clearsign/coin/<mint>`), readable without a key. Any phone
     * or modified APK can write, and a false "clean" costs more than a false STOP, so a STOP counts
     * from anyone and "clean" only from [MIN_CLEAN] installs; the other gates still run. Keyed by
     * a random per-install id, never the wallet, which would announce the buy.
     */
    object Shared {
        private const val PREFS = "apex_coincheck"

        /** Distinct installs needed before a "clean" is trusted. */
        const val MIN_CLEAN = 2

        /** A "clean" expires fast: a coin can turn bad later. */
        const val CLEAN_TTL_MS = 6L * 3600_000

        /** A scam stays a scam. */
        const val STOP_TTL_MS = 7L * 24 * 3600_000

        /** What the archive says, before spending a search. */
        sealed class Say {
            data class Stop(val reason: String) : Say()
            object Clean : Say()
            /** No verdict yet, or too few: search ourselves. */
            object Ask : Say()
        }

        /**
         * Parse an archive row; pure, so tests can feed it real rows.
         * Shape: `{"v": {"<id>": {"s": 0|1, "w": "reason", "at": 123}}}`, `s` 1 for a stop.
         * Unreadable entries are ignored.
         */
        fun read(row: JSONObject?, now: Long): Say {
            val v = row?.optJSONObject("v") ?: return Say.Ask
            var clean = 0
            for (id in v.keys()) {
                val e = v.optJSONObject(id) ?: continue
                val at = e.optLong("at", 0L)
                val age = now - at
                if (age < 0) continue
                if (e.optInt("s", 0) == 1) {
                    if (age < STOP_TTL_MS) return Say.Stop(e.optString("w").ifBlank { "segnalata" })
                } else if (age < CLEAN_TTL_MS) {
                    clean++
                }
            }
            return if (clean >= MIN_CLEAN) Say.Clean else Say.Ask
        }

        /** Random per-install id, not linked to the wallet. */
        fun id(ctx: Context): String {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.getString("id", null)?.let { return it }
            val fresh = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            p.edit().putString("id", fresh).apply()
            return fresh
        }

        /**
         * In-memory cache of archive answers. Without it a top-listed coin was reread every hunt:
         * 1,200 reads a day per phone, 2 GB a day at 10k users on an archive with 10 GB a month.
         */
        private const val MEM_TTL_MS = 30L * 60_000
        /** A "nobody knows" is rechecked sooner: somebody is about to write it. */
        private const val MEM_ASK_TTL_MS = 5L * 60_000
        private val mem = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Say>>()

        /** What the archive says about [mint], remembering the answer. */
        fun say(mint: String, now: Long = System.currentTimeMillis()): Say {
            mem[mint]?.let { (at, said) ->
                val ttl = if (said is Say.Ask) MEM_ASK_TTL_MS else MEM_TTL_MS
                if (now - at < ttl) return said
            }
            val said = read(fetch(mint), now)
            mem[mint] = now to said
            return said
        }

        /** After publishing our own verdict, the memory is stale. */
        internal fun forget(mint: String) { mem.remove(mint) }

        /** The archive is read without a key and without waking the worker. */
        fun fetch(mint: String): JSONObject? {
            val base = BuildConfig.ARCHIVE_URL.takeIf { it.isNotBlank() } ?: return null
            return runCatching {
                val c = (URL(base.trimEnd('/') + "/clearsign/coin/" + mint + ".json").openConnection() as HttpURLConnection)
                    .apply { connectTimeout = 6_000; readTimeout = 8_000; setRequestProperty("Accept", "application/json") }
                val code = c.responseCode
                val t = if (code in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
                c.disconnect()
                t?.takeIf { it.isNotBlank() && it != "null" }?.let { JSONObject(it) }
            }.getOrNull()
        }

        /**
         * Publish our verdict via the worker, which holds the archive write key (never in the APK).
         * Failure is harmless: the next phone runs its own search.
         */
        fun publish(ctx: Context, mint: String, stop: Boolean, why: String) {
            forget(mint)
            val base = BuildConfig.CROWD_URL.takeIf { it.isNotBlank() } ?: return
            runCatching {
                val body = JSONObject().put("i", id(ctx)).put("s", if (stop) 1 else 0).put("w", why.take(120)).toString()
                val c = (URL(base.trimEnd('/') + "/?cc=" + mint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 6_000; readTimeout = 8_000
                    setRequestProperty("Content-Type", "application/json")
                }
                c.outputStream.use { it.write(body.toByteArray()) }
                c.responseCode
                c.disconnect()
            }
        }
    }

    /**
     * Ask the model, with web search, for a public reason not to buy [symbol] ([mint]).
     * The answer is one word then a reason, easy to parse; anything else is Unknown, not Ok.
     */
    suspend fun verdict(ctx: Context, symbol: String, mint: String): Verdict = withContext(Dispatchers.IO) {
        if (!Settings.webCheck.value) return@withContext Verdict.Unknown

        // Shared archive first: free, no worker call, and works without an API key. See [Shared].
        when (val said = Shared.say(mint)) {
            is Shared.Say.Stop -> {
                Log.i(TAG, "$symbol: stop dall'archivio")
                return@withContext Verdict.Stop(said.reason)
            }
            Shared.Say.Clean -> {
                Log.i(TAG, "$symbol: pulita per ${Shared.MIN_CLEAN} installazioni, nessuna ricerca")
                return@withContext Verdict.Ok
            }
            Shared.Say.Ask -> Unit
        }

        val cfg = Secrets.model(ctx)
        // The web search tool is Anthropic's, run on their side. An OpenAI-shaped
        // endpoint has no equivalent we can rely on, so it simply does not answer.
        if (!cfg.ready || !cfg.anthropic) return@withContext Verdict.Unknown

        val question =
            "You are the last check before an automated wallet spends real money on the Solana token " +
                "$symbol (mint $mint). Search the web for anything published about this specific token: " +
                "a rug pull, an exploit, a team with a history of abandoning tokens, a known scam, an " +
                "impersonation of another project. Ignore price predictions, hype and general market talk.\n\n" +
                "Answer in one line, starting with exactly one word:\n" +
                "STOP <short reason> — if you found a concrete published reason not to buy it.\n" +
                "OK — if you found nothing of the sort, or nothing at all.\n" +
                "Nothing found is OK. Do not guess, do not warn about volatility, do not explain."

        val body = JSONObject()
            .put("model", cfg.model)
            .put("max_tokens", 300)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", question)))
            .put(
                "tools",
                JSONArray().put(
                    // The basic variant: the configured model may predate dynamic filtering, and the basic
                    // one is accepted everywhere. Two searches answer "is this coin a known scam" and cap the cost.
                    JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 2),
                ),
            )

        val answer = post(body, cfg) ?: return@withContext Verdict.Unknown
        answer.optJSONObject("error")?.let {
            Log.w(TAG, "refused: " + it.optString("type"))
            return@withContext Verdict.Unknown
        }
        val searches = answer.optJSONObject("usage")?.optJSONObject("server_tool_use")
            ?.optInt("web_search_requests", 0) ?: 0
        val text = buildString {
            val content = answer.optJSONArray("content") ?: JSONArray()
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }.trim()
        Log.i(TAG, "$symbol: $searches searches, answer starts '" + text.take(24) + "'")

        // Share the verdict. Unparseable answers are not published.
        return@withContext when {
            text.startsWith("STOP", true) -> {
                val why = text.removePrefix("STOP").removePrefix("stop").trim().trim('—', '-', ':', ' ').ifBlank { symbol }
                Shared.publish(ctx, mint, stop = true, why = why)
                Verdict.Stop(why)
            }
            text.startsWith("OK", true) -> {
                Shared.publish(ctx, mint, stop = false, why = "")
                Verdict.Ok
            }
            else -> Verdict.Unknown
        }
    }

    private fun post(body: JSONObject, cfg: Secrets.Model): JSONObject? = try {
        val c = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", cfg.key)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect()
        text?.let { JSONObject(it) }
    } catch (e: Exception) {
        // Log the exception class only, never anything near the key.
        Log.w(TAG, "POST failed: ${e.javaClass.simpleName}")
        null
    }
}
