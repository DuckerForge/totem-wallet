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
 *  * **One coin, once — for everybody.** This runs on the single coin the agent
 *    has already decided to buy, after the gates and after
 *    [com.clearsign.core.assessToken], never across candidates. A search costs a
 *    cent plus the tokens its results add to the prompt; asking it about every
 *    candidate of every round would cost more per day than the budget holds.
 *    And the answer does not depend on who is asking, so it is not asked twice:
 *    the verdict goes into the shared archive and the next phone reads it. A
 *    thousand people running this loop pay for a coin **twice**, not a thousand
 *    times. See [Shared].
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
     * Il verdetto degli altri, e perché un «no» e un «sì» non pesano uguale.
     *
     * La domanda a cui questo file risponde non dipende da chi la fa: se una
     * moneta è una truffa, lo è per tutti. Quindi la risposta sta in archivio
     * (`/clearsign/coin/<mint>`), i telefoni la leggono senza chiave e senza
     * svegliare nessun servizio, e chi non trova niente paga la ricerca e la
     * lascia lì per i prossimi.
     *
     * **Il chiodo.** Quelle righe le scrivono dei telefoni, cioè della gente, e
     * un APK modificato ci scrive quello che vuole. Le due bugie possibili non
     * costano uguale:
     *
     *  * «STOP su una moneta buona» fa saltare un acquisto a tutti. È una
     *    scocciatura, e si ripaga con un'altra moneta, che ce ne sono mille.
     *  * «pulita su una truffa» fa comprare la truffa a tutti. Questa costa
     *    soldi veri.
     *
     * Per questo uno STOP vale da chiunque e subito, e un «pulita» vale solo
     * quando lo dicono [MIN_CLEAN] installazioni diverse. Il mondo paga due
     * ricerche per moneta invece di mille, e per spegnere l'ultima rete a
     * qualcun altro bisogna arrivare primo su quel mint con due installazioni.
     * E anche riuscendoci non si fa comprare niente: si toglie un controllo, e
     * sotto restano tutti gli altri cancelli, che girano sul telefono.
     *
     * **Chi firma.** Non il portafoglio. Scrivere «la paghetta X ha appena
     * controllato il mint Y» in un archivio pubblico è dire al mondo che quella
     * paghetta sta per comprare quella moneta, qualche secondo prima che lo
     * faccia. Serve solo poter distinguere due installazioni, non sapere quali:
     * quindi un numero a caso, fatto una volta e tenuto sul telefono.
     */
    object Shared {
        private const val PREFS = "apex_coincheck"

        /** Quante installazioni diverse servono per credere a un «pulita». */
        const val MIN_CLEAN = 2

        /** Un «pulita» invecchia in fretta: una moneta può marcire dopo. */
        const val CLEAN_TTL_MS = 6L * 3600_000

        /** Una truffa resta una truffa. */
        const val STOP_TTL_MS = 7L * 24 * 3600_000

        /** Cosa dice l'archivio, prima di spendere una ricerca. */
        sealed class Say {
            data class Stop(val reason: String) : Say()
            object Clean : Say()
            /** Nessuno lo sa ancora, o non abbastanza: tocca a noi. */
            object Ask : Say()
        }

        /**
         * La riga dell'archivio, letta. Pura, così un test le può passare quello
         * che il database contiene davvero.
         *
         * Forma: `{"v": {"<id>": {"s": 0|1, "w": "motivo", "at": 123}}}`, dove
         * `s` è 1 per uno stop. Tutto ciò che non si legge vale come niente: una
         * riga rotta non è un verdetto.
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

        /** Il numero che distingue questa installazione, e non dice chi è. */
        fun id(ctx: Context): String {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.getString("id", null)?.let { return it }
            val fresh = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
            p.edit().putString("id", fresh).apply()
            return fresh
        }

        /**
         * Quanto tiene una risposta dell'archivio prima di richiederla.
         *
         * Senza questo, una moneta che resta in cima alla lista si rilegge a ogni
         * caccia: cinque letture ogni sei minuti, milleduecento al giorno per
         * telefono. Sono duecento byte l'una e sembrano niente finche' non le
         * moltiplichi per diecimila persone, e allora sono due giga al giorno di
         * traffico su un archivio che ne da' dieci al mese. Il verdetto degli
         * altri non cambia ogni sei minuti: mezz'ora e' gia' piu' fresco di
         * quanto serva.
         */
        private const val MEM_TTL_MS = 30L * 60_000
        /** Un «non lo sa nessuno» si ricontrolla prima: qualcuno sta per scriverlo. */
        private const val MEM_ASK_TTL_MS = 5L * 60_000
        private val mem = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Say>>()

        /** Quello che l'archivio dice di [mint], ricordando la risposta. */
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

        /** L'archivio si legge senza chiave e senza svegliare il worker. */
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
         * Lasciare il verdetto per i prossimi. Passa dal worker, perché la chiave
         * che scrive sull'archivio sta là dentro e nell'APK non ci deve finire.
         * Se non va a buon fine non succede niente: il prossimo pagherà la sua.
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
     * Ask the model, with web search, whether there is public reason not to buy
     * [symbol] ([mint]).
     *
     * The answer format is one word then a reason, which is cheap to parse and
     * hard to get wrong. Anything unparseable is Unknown, not Ok: a verdict we
     * could not read is not a verdict.
     */
    suspend fun verdict(ctx: Context, symbol: String, mint: String): Verdict = withContext(Dispatchers.IO) {
        if (!Settings.webCheck.value) return@withContext Verdict.Unknown

        // Prima la risposta degli altri. Non costa niente, non sveglia nessun
        // servizio, e vale anche per chi non ha nessuna chiave: senza questo, un
        // telefono senza chiave non aveva questa rete per niente. Vedi [Shared].
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

        // Pagata una volta, lasciata a tutti. Un verdetto che non si è potuto
        // leggere non si pubblica: non è un verdetto.
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
