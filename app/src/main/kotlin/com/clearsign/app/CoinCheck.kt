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
 * numbers do not?
 *
 * Everything else the agent checks is arithmetic on market data — liquidity,
 * holders, authorities, the shape of the last hour. All of it is blind to the
 * things people write down: a team that rugged a previous coin, an exploit two
 * hours old, a "token" that is a scam with a Twitter account. That is the one
 * capability worth taking from the assistants that have a web tool.
 *
 * Three deliberate constraints:
 *
 *  * **One coin, once.** This runs on the single coin the agent has already
 *    decided to buy, after the gates and after [com.clearsign.core.assessToken],
 *    never across candidates. A search costs a cent plus the tokens its results
 *    add to the prompt; asking it about every candidate of every round would cost
 *    more per day than the budget holds.
 *  * **It can only say no.** A positive answer changes nothing about the trade;
 *    the only power this has is to stop one.
 *  * **Unknown never blocks.** No key, no network, no answer, a model that is not
 *    Anthropic: all of those return [Verdict.Unknown] and the trade proceeds on
 *    the numbers, exactly as it did before this file existed. The same rule as
 *    [Jupiter.sellableBack] and [JupiterTrigger.live].
 */
object CoinCheck {
    private const val TAG = "Apex-CoinCheck"

    sealed class Verdict {
        object Ok : Verdict()
        data class Stop(val reason: String) : Verdict()
        object Unknown : Verdict()
    }

    /**
     * Ask the model, with web search, whether there is public reason not to buy
     * [symbol] ([mint]).
     *
     * The answer format is one word then a reason, which is cheap to parse and
     * hard to get wrong. Anything unparseable is Unknown, not Ok: a verdict we
     * could not read is not a verdict.
     */
    suspend fun verdict(ctx: Context, symbol: String, mint: String): Verdict = withContext(Dispatchers.IO) {
        if (!Settings.webCheck.value) return@withContext Verdict.Unknown
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
                    // The basic variant: the configured model may be older than the
                    // family that supports dynamic filtering, and the basic one is
                    // accepted everywhere. Two searches is enough for "is this coin
                    // known to be a scam" and is the cost ceiling per purchase.
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

        return@withContext when {
            text.startsWith("STOP", true) -> Verdict.Stop(text.removePrefix("STOP").removePrefix("stop").trim().trim('—', '-', ':', ' ').ifBlank { symbol })
            text.startsWith("OK", true) -> Verdict.Ok
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
