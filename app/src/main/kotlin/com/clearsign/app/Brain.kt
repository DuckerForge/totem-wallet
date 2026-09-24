package com.clearsign.app

import android.content.Context
import android.util.Log
import com.clearsign.core.AgentMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * The model, talking to the wallet through five tools it cannot abuse. Two shapes: the
 * Anthropic Messages API and any endpoint speaking the OpenAI chat format (OpenRouter,
 * DeepSeek, a model on your own machine). No SDK, both are a POST with a JSON body. What
 * leaves the phone: the conversation, balances and addresses. What never leaves: any key.
 * The model proposes, [AgentBroker] decides, the Seed Vault is untouchable either way.
 */
object Brain {
    private const val TAG = "Velum-Brain"
    private const val MAX_TOOL_ROUNDS = 6

    /** One line in the transcript. [tool] rows are the receipts of what was done. */
    data class Turn(val role: String, val text: String, val tool: String? = null, val verdict: String? = null)

    sealed class Reply {
        data class Ok(val turns: List<Turn>) : Reply()
        data class Failed(val message: String) : Reply()
    }

    fun configured(ctx: Context) = Secrets.model(ctx).ready

    /**
     * Send [history] plus the new message and run the tool loop to the end; every turn comes
     * back so the screen can show tool results. The answer streams: [onText] gets the text so
     * far as each piece arrives. Tool calls are collected while streaming and run when the turn
     * ends; a provider that will not stream falls back to one whole answer through the same callback.
     */
    suspend fun ask(ctx: Context, history: List<Turn>, agentName: String, onText: (String) -> Unit = {}): Reply = withContext(Dispatchers.IO) {
        val cfg = Secrets.model(ctx)
        if (!cfg.ready) return@withContext Reply.Failed(ctx.getString(R.string.brain_no_key))
        val produced = ArrayList<Turn>()
        val messages = JSONArray()
        history.forEach { t ->
            if (t.role == "user" || t.role == "assistant") {
                messages.put(JSONObject().put("role", t.role).put("content", t.text))
            }
        }
        val system = systemPrompt(ctx)

        try {
            var rounds = 0
            while (rounds++ <= MAX_TOOL_ROUNDS) {
                val body = (if (cfg.anthropic) anthropicBody(cfg, system, messages) else openAiBody(cfg, system, messages)).put("stream", true)
                val url = if (cfg.anthropic) "https://api.anthropic.com/v1/messages" else cfg.baseUrl.trimEnd('/') + "/chat/completions"
                val turn = when (val r = stream(url, body, cfg, onText)) {
                    is Streamed.Failed -> return@withContext Reply.Failed(r.message.ifBlank { ctx.getString(R.string.brain_unreachable) })
                    is Streamed.Ok -> r
                }
                messages.put(turn.assistant)
                if (turn.text.isNotBlank()) produced += Turn("assistant", turn.text.trim())
                if (turn.calls.isEmpty()) return@withContext Reply.Ok(produced)

                // Anthropic wants every result of one assistant turn in a single user message; one message
                // per result is a 400 as soon as the model calls two tools at once. OpenAI-shaped endpoints
                // want the opposite: one `tool` message per call.
                val results = JSONArray()
                for ((id, name, args) in turn.calls) {
                    val result = runCatching { BrainTools.run(ctx, name, args, agentName) }
                        .getOrElse { e -> JSONObject().put("error", e.message ?: "tool failed") }
                    produced += Turn("tool", result.toString(), tool = name, verdict = result.optString("decision").ifBlank { null })
                    if (cfg.anthropic) {
                        results.put(JSONObject().put("type", "tool_result").put("tool_use_id", id).put("content", result.toString()))
                    } else {
                        messages.put(JSONObject().put("role", "tool").put("tool_call_id", id).put("content", result.toString()))
                    }
                }
                if (cfg.anthropic) messages.put(JSONObject().put("role", "user").put("content", results))
            }
            Reply.Ok(produced + Turn("assistant", ctx.getString(R.string.brain_too_many_steps)))
        } catch (e: Exception) {
            Log.w(TAG, "ask failed", e)
            Reply.Failed(e.message ?: ctx.getString(R.string.brain_unreachable))
        }
    }

    /** One streamed assistant turn: its text, its tool calls, and the message to echo back. */
    private sealed class Streamed {
        class Ok(val text: String, val calls: List<Triple<String, String, JSONObject>>, val assistant: JSONObject) : Streamed()
        class Failed(val message: String) : Streamed()
    }

    /**
     * POST with `stream: true` and read server-sent events until the turn ends. Two dialects,
     * one reader: Anthropic sends typed blocks (`content_block_start` / `_delta` / `_stop`) and
     * tool arguments as partial JSON; OpenAI-shaped endpoints send `choices[0].delta` with
     * `content` and `tool_calls[]` by index, then `[DONE]`. Both reassemble into the message
     * the non-streaming answer would have carried, so the rest of the loop cannot tell.
     */
    private fun stream(url: String, body: JSONObject, cfg: Secrets.Model, onText: (String) -> Unit): Streamed {
        val c = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 120_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                if (cfg.anthropic) {
                    setRequestProperty("x-api-key", cfg.key)
                    setRequestProperty("anthropic-version", "2023-06-01")
                } else {
                    setRequestProperty("Authorization", "Bearer " + cfg.key)
                }
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "stream open failed: ${e.javaClass.simpleName}")
            return Streamed.Failed("")
        }
        try {
            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val msg = runCatching { JSONObject(err).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                return Streamed.Failed(msg.ifBlank { "HTTP $code" })
            }
            val text = StringBuilder()
            // Tool calls by block index (Anthropic) or by tool_calls index (OpenAI).
            val ids = HashMap<Int, String>()
            val names = HashMap<Int, String>()
            val args = HashMap<Int, StringBuilder>()
            val order = ArrayList<Int>()
            var failed: String? = null
            c.inputStream.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    if (!raw.startsWith("data:")) continue
                    val data = raw.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val o = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    if (cfg.anthropic) {
                        when (o.optString("type")) {
                            "content_block_start" -> {
                                val i = o.optInt("index")
                                val b = o.optJSONObject("content_block") ?: continue
                                if (b.optString("type") == "tool_use") {
                                    ids[i] = b.optString("id"); names[i] = b.optString("name"); args[i] = StringBuilder(); order += i
                                }
                            }
                            "content_block_delta" -> {
                                val i = o.optInt("index")
                                val d = o.optJSONObject("delta") ?: continue
                                when (d.optString("type")) {
                                    "text_delta" -> { text.append(d.optString("text")); onText(text.toString()) }
                                    "input_json_delta" -> args[i]?.append(d.optString("partial_json"))
                                }
                            }
                            "error" -> failed = o.optJSONObject("error")?.optString("message") ?: "error"
                        }
                    } else {
                        o.optJSONObject("error")?.let { failed = it.optString("message").ifBlank { "error" } }
                        val d = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                        d.optString("content").takeIf { it.isNotEmpty() && it != "null" }?.let { text.append(it); onText(text.toString()) }
                        val tc = d.optJSONArray("tool_calls") ?: continue
                        for (k in 0 until tc.length()) {
                            val t = tc.optJSONObject(k) ?: continue
                            val i = t.optInt("index", k)
                            if (i !in args) { args[i] = StringBuilder(); order += i }
                            t.optString("id").takeIf { it.isNotEmpty() }?.let { ids[i] = it }
                            t.optJSONObject("function")?.let { f ->
                                f.optString("name").takeIf { it.isNotEmpty() }?.let { names[i] = it }
                                args[i]?.append(f.optString("arguments"))
                            }
                        }
                    }
                }
            }
            failed?.let { return Streamed.Failed(it) }

            val calls = order.map { i ->
                val parsed = runCatching { JSONObject(args[i].toString().ifBlank { "{}" }) }.getOrDefault(JSONObject())
                Triple(ids[i].orEmpty(), names[i].orEmpty(), parsed)
            }
            val assistant = if (cfg.anthropic) {
                val content = JSONArray()
                if (text.isNotEmpty()) content.put(JSONObject().put("type", "text").put("text", text.toString()))
                calls.forEach { (id, name, input) -> content.put(JSONObject().put("type", "tool_use").put("id", id).put("name", name).put("input", input)) }
                JSONObject().put("role", "assistant").put("content", content)
            } else {
                val m = JSONObject().put("role", "assistant").put("content", if (text.isEmpty()) JSONObject.NULL else text.toString())
                if (calls.isNotEmpty()) {
                    val tc = JSONArray()
                    calls.forEach { (id, name, input) ->
                        tc.put(JSONObject().put("id", id).put("type", "function").put("function", JSONObject().put("name", name).put("arguments", input.toString())))
                    }
                    m.put("tool_calls", tc)
                }
                m
            }
            return Streamed.Ok(text.toString(), calls, assistant)
        } catch (e: Exception) {
            Log.w(TAG, "stream failed: ${e.javaClass.simpleName}")
            return Streamed.Failed("")
        } finally {
            c.disconnect()
        }
    }

    /**
     * One question, one answer, no tools, on the configured provider: for checks that want a
     * verdict. Null when nobody answered or the provider complained.
     */
    suspend fun complete(ctx: Context, system: String, user: String, maxTokens: Int = 256): String? = withContext(Dispatchers.IO) {
        val cfg = Secrets.model(ctx)
        if (!cfg.ready) return@withContext null
        val ask = JSONObject().put("role", "user").put("content", user)
        val body = if (cfg.anthropic) {
            JSONObject().put("model", cfg.model).put("max_tokens", maxTokens).put("system", system).put("messages", JSONArray().put(ask))
        } else {
            JSONObject().put("model", cfg.model).put("max_tokens", maxTokens)
                .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(ask))
        }
        val url = if (cfg.anthropic) "https://api.anthropic.com/v1/messages" else cfg.baseUrl.trimEnd('/') + "/chat/completions"
        val r = post(url, body, cfg) ?: return@withContext null
        if (r.has("error")) { Log.w(TAG, "complete: " + r.optJSONObject("error")?.optString("type")); return@withContext null }
        if (cfg.anthropic) {
            val content = r.optJSONArray("content") ?: return@withContext null
            buildString { for (i in 0 until content.length()) content.optJSONObject(i)?.let { if (it.optString("type") == "text") append(it.optString("text")) } }
        } else {
            r.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.takeIf { it != "null" }
        }
    }

    // ---- the prompt ------------------------------------------------------------

    /**
     * Built from the live policy, so there is one knob: change the rules and the model's brief
     * changes with them. It is told the truth: it cannot sign, and a refusal is final.
     */
    fun systemPrompt(ctx: Context): String {
        val s = SessionWallet.current(ctx)
        val p = SessionWallet.policy(ctx)
        val h = SessionWallet.history(ctx)
        val contacts = Contacts.allowlist(ctx)
        val italian = deviceLocaleTag() == "it"
        val sb = StringBuilder()
        sb.append(
            if (italian) {
                "Sei l'assistente dentro Totem, un portafoglio Solana sul telefono Seeker. Parli italiano, in modo diretto e breve.\n\n"
            } else {
                "You are the assistant inside Totem, a Solana wallet on the Seeker phone. Be direct and brief.\n\n"
            },
        )
        sb.append(
            if (italian) {
                "Tu non firmi niente. Proponi, e Totem decide sul telefono: può firmare in silenzio, chiedere l'impronta alla persona, o rifiutare. Un rifiuto è definitivo: spiegalo con parole semplici e non cercare un'altra strada per fare la stessa cosa.\n\n"
            } else {
                "You never sign anything. You propose, and Totem decides on the phone: it may sign silently, ask the person for a fingerprint, or refuse. A refusal is final: explain it plainly and do not look for another route to the same thing.\n\n"
            },
        )
        if (s == null || p == null) {
            sb.append(if (italian) "Non hai ancora una paghetta, quindi non puoi spendere niente. Se serve, di' alla persona di creartene una dalla linguetta Agente." else "You have no budget yet, so you cannot spend anything. If needed, tell the person to give you one in the Agent tab.")
            return sb.append(ownRules(ctx, italian)).toString()
        }
        val mode = when (p.mode) {
            AgentMode.OFF -> if (italian) "spento: non passerà niente" else "off: nothing will go through"
            AgentMode.READ_ONLY -> if (italian) "sola lettura" else "read-only"
            AgentMode.ASK_ALWAYS -> if (italian) "chiede sempre conferma" else "always asks for confirmation"
            AgentMode.AUTONOMOUS -> if (italian) "autonomo sotto le soglie" else "autonomous below the thresholds"
        }
        sb.append(if (italian) "Stato: $mode.\n" else "State: $mode.\n")
        sb.append(
            if (italian) {
                "Puoi spendere solo dalla paghetta (${fmtSol(p.perTxLamports, 4)} SOL per operazione, ${fmtSol(p.dailyLamports, 4)} SOL al giorno, già spesi oggi ${fmtSol(h.spentLast24hLamports, 4)} SOL). Sotto ${fmtSol(p.askAboveLamports, 4)} SOL firma da sola; sopra, chiede alla persona.\n"
            } else {
                "You may only spend from the budget (${fmtSol(p.perTxLamports, 4)} SOL per move, ${fmtSol(p.dailyLamports, 4)} SOL a day, ${fmtSol(h.spentLast24hLamports, 4)} SOL spent today). Below ${fmtSol(p.askAboveLamports, 4)} SOL it signs on its own; above that it asks.\n"
            },
        )
        val whom = p.allowedDestinations.mapNotNull { a -> contacts[a]?.let { "$it ($a)" } }
        sb.append(
            if (whom.isEmpty()) {
                if (italian) "Nessun contatto è ammesso come destinatario: puoi solo mandare al conto principale della persona.\n" else "No contact is an allowed recipient: you can only send to the person's own account.\n"
            } else {
                (if (italian) "Destinatari ammessi: " else "Allowed recipients: ") + whom.joinToString("; ") + "\n"
            },
        )
        sb.append(
            if (italian) {
                "Il conto principale è nel Seed Vault e non lo tocchi mai: puoi solo mandarci i guadagni con harvest.\nCi sono due borselli e non vanno mai confusi: la paghetta, che è l'unica cosa che puoi spendere, e il conto principale, che puoi solo guardare. Quando rispondi di' sempre di quale dei due stai parlando. Prima di proporre una spesa chiama wallet_status. Prima di proporre uno scambio guarda il mercato: market_scan per trovare cosa vale la pena guardare adesso, market_search per sapere cos'è una moneta, quanto vale e se è una trappola, quote_swap per sapere quanto ti darebbe davvero, portfolio per sapere cosa ha in mano. Non scegliere mai una moneta a memoria: quello che ricordi è vecchio di un anno e quel simbolo oggi può essere di un altro. Se cerchi qualcosa di nuovo, parti sempre da market_scan e di' anche cosa è stato scartato e perché.\nSe la persona ti chiede di lavorare da sola, di cercare occasioni o di operare mentre chiude l'app, quello è start_trading: accendilo senza fare altre domande. Quando l'hai acceso, dille i numeri veri che ti ha restituito lo strumento, quanto mette per posizione, quante posizioni, a che punto vende in guadagno e a che punto in perdita, e che può chiudere l'app. Di' anche i limiti che lo strumento ti riporta, senza addolcirli. Per sapere come sta andando usa positions, non la memoria. Di' i numeri veri che hai trovato, non impressioni. Dopo ogni operazione di' com'è andata, con l'importo vero.\nScrivi in frasi brevi, come parleresti. Niente tabelle, niente barre verticali, niente asterischi e niente markdown: il telefono mostra il testo grezzo e una tabella diventa illeggibile. Per un elenco usa una riga per voce, con il trattino."
            } else {
                "The main account lives in the Seed Vault and you never touch it: you can only send gains to it with harvest.\nThere are two pots and you must never blur them: the budget, which is the only money you can spend, and the main account, which you can only look at. Always say which one you are talking about. Call wallet_status before proposing a spend. Before proposing a swap, look at the market: market_scan to find what is worth looking at right now, market_search to learn what a coin is, what it costs and whether it is a trap, quote_swap to learn what it would really return, portfolio to know what they hold. Never pick a coin from memory: what you remember is a year old and that ticker may belong to somebody else today. When you are looking for something new, always start from market_scan, and say what it threw out as well as what it kept.\nIf the person asks you to work on your own, to look for opportunities, or to keep going while they close the app, that is start_trading: switch it on without asking anything else. Once it is on, tell them the real numbers the tool gave back, the slice per position, how many positions, where it sells in profit and where in loss, and that they can close the app. Say the limits the tool reports too, without softening them. To know how it is going use positions, not memory. Quote the real numbers you found, not impressions. After each operation say how it went, with the real amount.\nWrite in short sentences, the way you would say it out loud. No tables, no pipes, no asterisks, no markdown: the phone shows raw text and a table turns into noise. For a list, one line per item with a dash."
            },
        )
        return sb.append(ownRules(ctx, italian)).toString()
    }

    /** The person's own rules, last, after the truths they cannot override. Empty when there is no file. */
    private fun ownRules(ctx: Context, italian: Boolean): String =
        UserRules.get(ctx)?.let { UserRules.chatBlock(it, italian) } ?: ""

    // ---- wire ------------------------------------------------------------------

    private fun anthropicBody(cfg: Secrets.Model, system: String, messages: JSONArray) = JSONObject()
        .put("model", cfg.model).put("max_tokens", 1024).put("system", system)
        .put("messages", messages).put("tools", BrainTools.schema)

    private fun openAiBody(cfg: Secrets.Model, system: String, messages: JSONArray): JSONObject {
        val withSystem = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (i in 0 until messages.length()) withSystem.put(messages.get(i))
        val tools = JSONArray()
        for (i in 0 until BrainTools.schema.length()) {
            val t = BrainTools.schema.getJSONObject(i)
            tools.put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject().put("name", t.getString("name")).put("description", t.getString("description"))
                        .put("parameters", t.getJSONObject("input_schema")),
                ),
            )
        }
        return JSONObject().put("model", cfg.model).put("messages", withSystem).put("tools", tools).put("max_tokens", 1024)
    }

    /**
     * One tiny real call, so a wrong key is caught here and not mid-conversation. Null when it
     * worked, else the provider's own complaint, more useful than anything we could invent.
     */
    suspend fun test(ctx: Context): String? = withContext(Dispatchers.IO) {
        val cfg = Secrets.model(ctx)
        if (!cfg.ready) return@withContext ctx.getString(R.string.brain_no_key)
        val body = if (cfg.anthropic) {
            JSONObject().put("model", cfg.model).put("max_tokens", 8)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
        } else {
            JSONObject().put("model", cfg.model).put("max_tokens", 8)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
        }
        val url = if (cfg.anthropic) "https://api.anthropic.com/v1/messages" else cfg.baseUrl.trimEnd('/') + "/chat/completions"
        val response = post(url, body, cfg) ?: return@withContext ctx.getString(R.string.brain_unreachable)
        response.optJSONObject("error")?.let { e ->
            return@withContext e.optString("message").ifBlank { ctx.getString(R.string.brain_unreachable) }
        }
        val ok = response.has("content") || response.optJSONArray("choices") != null
        if (ok) null else ctx.getString(R.string.brain_unreachable)
    }

    private fun post(url: String, body: JSONObject, cfg: Secrets.Model): JSONObject? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 90_000
            setRequestProperty("Content-Type", "application/json")
            if (cfg.anthropic) {
                setRequestProperty("x-api-key", cfg.key)
                setRequestProperty("anthropic-version", "2023-06-01")
            } else {
                setRequestProperty("Authorization", "Bearer " + cfg.key)
            }
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect()
        text?.let { JSONObject(it) }
    } catch (e: Exception) {
        // Never log the body: it carries the key's neighbourhood and the user's words.
        Log.w(TAG, "POST failed: ${e.javaClass.simpleName}")
        null
    }
}
