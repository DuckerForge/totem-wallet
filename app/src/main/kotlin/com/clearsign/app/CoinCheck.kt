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
 * The last question before the money moves: does the internet know something the
 * numbers do not (a team that rugged before, an exploit two hours old). Three rules.
 * One coin, once, for everybody: it runs on the coin already chosen, after the gates,
 * and the verdict goes to the shared archive ([Shared]), so a thousand phones pay twice,
 * not a thousand times. It can only say no. Unknown never blocks: no key, no network,
 * no answer, a non-Anthropic model all mean [Verdict.Unknown] and the numbers decide.
 */
object CoinCheck {
    private const val TAG = "Apex-CoinCheck"

    sealed class Verdict {
        object Ok : Verdict()
        data class Stop(val reason: String) : Verdict()
        object Unknown : Verdict()
    }

    /**
     * Everybody's verdict, and why a no and a yes weigh differently. The question does not
     * depend on who asks, so the answer sits in the archive (`/clearsign/coin/<mint>`), read
     * without a key. Phones write those rows and a modified APK writes what it wants: a false
     * STOP costs one purchase, a false "clean" buys the scam for everyone. So a STOP counts
     * from anyone at once, a "clean" only from [MIN_CLEAN] installs, and even then it removes
     * one check while the other gates run on the phone. Signed by a random per-install id,
     * never the wallet: "budget X checked mint Y" in public would announce the buy.
     */
    object Shared {
        private const val PREFS = "apex_coincheck"

        /** Quante installazioni diverse servono per credere a un «pulita». */
        const val MIN_CLEAN = 2

        /** A "clean" ages fast: a coin can rot later. */
        const val CLEAN_TTL_MS = 6L * 3600_000

        /** A scam stays a scam. */
        const val STOP_TTL_MS = 7L * 24 * 3600_000

        /** What the archive says, before spending a search. */
        sealed class Say {
            data class Stop(val reason: String) : Say()
            object Clean : Say()
            /** Nessuno lo sa ancora, o non abbastanza: tocca a noi. */
            object Ask : Say()
        }

        /**
         * The archive row, read. Pure, so a test can hand it what the database really holds.
         * Shape: `{"v": {"<id>": {"s": 0|1, "w": "reason", "at": 123}}}`, `s` 1 for a stop.
         * Anything unreadable counts as nothing: a broken row is not a verdict.
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

        /** The number that tells this install apart, and says nothing about who it is. */
        fun id(ctx: Context): String {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.getString("id", null)?.let { return it }
            val fresh = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            p.edit().putString("id", fresh).apply()
            return fresh
        }

        /**
         * How long an archive answer holds before asking again. Without it a coin at the top of
         * the list was reread every hunt: 1,200 reads a day per phone, two gigabytes a day at
         * ten thousand people on an archive that gives ten a month. Half an hour is fresher than needed.
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

        /** Dopo aver pubblicato il proprio verdetto, la memoria e' vecchia. */
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
         * Leave the verdict for the next ones, through the worker: the key that writes to the
         * archive lives there and must not end up in the APK. If it fails nothing happens; the
         * next phone pays its own search.
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
     * Ask the model, with web search, whether there is public reason not to buy [symbol]
     * ([mint]). One word then a reason: cheap to parse, hard to get wrong. Unparseable is
     * Unknown, not Ok: a verdict we could not read is not a verdict.
     */
    suspend fun verdict(ctx: Context, symbol: String, mint: String): Verdict = withContext(Dispatchers.IO) {
        if (!Settings.webCheck.value) return@withContext Verdict.Unknown

        // Everybody's answer first: free, wakes no service, and works for whoever has no
        // key; without it a keyless phone had none of this net. See [Shared].
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

        // Paid once, left for everyone. A verdict that could not be read is not
        // published: it is not a verdict.
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
        // Never log the body: it carries the key's neighbourhood.
        Log.w(TAG, "POST failed: ${e.javaClass.simpleName}")
        null
    }
}
