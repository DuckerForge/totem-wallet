package com.clearsign.app

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * The take-profit that does not need us.
 *
 * A Trigger order is a limit sell recorded on chain: sell this many tokens for
 * at least this much SOL. Jupiter's keeper fills it when the price gets there,
 * not the phone. That is the whole reason it is worth the extra code — it is
 * the only exit in Apex that survives the app being killed, the battery dying
 * and the phone being off for a week.
 *
 * What it cannot do is the other direction. Trigger v1 sells *above* a price;
 * a stop-loss sells *below* one, and that needs the v2 OCO endpoint, which is
 * not on the keyless host (`/trigger/v2/createOrder` answers 404). So the stop
 * stays in [TraderLoop], where it only works while the loop runs, and that
 * difference is stated wherever a person can see it rather than smoothed over.
 *
 * Every order is **simulated and checked before it is signed**, by
 * [checkItOnlySellsThis]. The collar cannot judge one of these — moving tokens
 * into Jupiter's escrow looks like paying a stranger, so it would refuse them
 * all — and an order placed while nobody is watching is the worst possible
 * place to sign bytes a remote server handed us unexamined.
 *
 * No API key: verified against the free host. Two quirks worth knowing before
 * changing anything here:
 *
 *  * Jupiter refuses an order worth less than about five dollars. We do not
 *    guess that threshold locally, we let the API say so and fall back.
 *  * We deliberately take **no platform fee** on these. Adding one means
 *    passing a referral-program PDA as `feeAccount`, and a plain token account
 *    there fails on chain with `ConstraintTokenOwner` — a trap already paid for
 *    once in MEGAGEN, and not worth re-paying for a cut of an automatic exit.
 */
object JupiterTrigger {
    private const val TAG = "Apex-Trigger"
    private val HOSTS = listOf("https://lite-api.jup.ag/trigger/v1", "https://api.jup.ag/trigger/v1")

    /** What came back, or why nothing did. */
    sealed class Placed {
        /** The order is live on chain. [order] is its account, [signature] the transaction. */
        data class Ok(val order: String, val signature: String?) : Placed()
        /** Jupiter will not take an order this small. Not an error, just a floor. */
        object TooSmall : Placed()
        data class Failed(val reason: String) : Placed()
    }

    /**
     * Place a sell at [targetLamports] for the whole of [position].
     *
     * The target is the position's own take-profit rule, priced: what it cost
     * plus the percentage the person asked for. So the on-chain order says the
     * same thing the loop would have said, and whichever gets there first wins.
     */
    /**
     * Mai sul filo principale.
     *
     * Era bloccante e veniva chiamata anche da li', dove Android vieta la rete:
     * l'eccezione che tira in quel caso non ha messaggio, quindi nel registro si
     * leggeva "POST /createOrder failed: null" e sembrava che fosse Jupiter a
     * rifiutare. Non era Jupiter. Visto il 17/09: un ordine da quattordici
     * dollari, ben sopra il minimo, mai arrivato in catena, e la stessa chiamata
     * con gli stessi numeri fatta da fuori tornava un ordine valido al primo
     * colpo. Il messaggio nullo era l'indizio, e il thread nel registro la prova:
     * pid e tid uguali.
     */
    suspend fun placeTakeProfit(ctx: Context, maker: String, position: Positions.Position): Placed = withContext(Dispatchers.IO) {
        placeBlocking(ctx, maker, position)
    }

    private suspend fun placeBlocking(ctx: Context, maker: String, position: Positions.Position): Placed {
        val raw = (position.units * Math.pow(10.0, position.decimals.toDouble())).toLong()
        if (raw <= 0) return Placed.Failed("nothing to sell")
        val target = position.costLamports * (100L + position.takeProfitPct) / 100L
        if (target <= 0) return Placed.Failed("no target")

        val built = when (val b = build(maker, position.mint, Jupiter.SOL_MINT, raw, target)) {
            is Build.Ok -> b.built
            Build.TooSmall -> return Placed.TooSmall
            is Build.Failed -> return Placed.Failed(b.reason)
        }
        // These bytes were built by somebody else's server, and the key that is
        // about to sign them holds real money. Apex does not sign anything it has
        // not looked at, and an automatic order placed while nobody is watching is
        // the last place to make an exception.
        checkItOnlySellsThis(ctx, built.unsigned, maker, position)?.let { return Placed.Failed(it) }

        val sig = SessionWallet.sign(ctx, SolanaTx.messageBytes(built.unsigned)) ?: return Placed.Failed("could not sign")
        val done = execute(built, attach(built.unsigned, maker, sig)) ?: return Placed.Failed("unreachable")
        done.optString("error").takeIf { it.isNotEmpty() }?.let { return Placed.Failed(it) }
        return Placed.Ok(built.order, done.optString("signature").takeIf { it.isNotEmpty() })
    }

    // ---- the pieces, for whoever signs ------------------------------------

    /** An order Jupiter has built and nobody has signed yet. [order] is empty for a cancel. */
    class Built(val order: String, val requestId: String, val unsigned: ByteArray)

    sealed class Build {
        class Ok(val built: Built) : Build()
        /** Jupiter will not take an order this small. Not an error, just a floor. */
        object TooSmall : Build()
        class Failed(val reason: String) : Build()
    }

    /**
     * Ask Jupiter for the order: sell [makingRaw] of [inputMint] for at least
     * [takingRaw] of [outputMint]. Two shapes of the same call: a take-profit is
     * "sell this coin for at least this much SOL", a limit buy is "sell this much
     * SOL for at least this many coins". Nothing is signed here; the bytes come
     * back for whoever holds the key, the budget or the Seed Vault, to look at
     * first. The person gets the ordinary receipt; the loop gets
     * [checkItOnlySellsThis].
     */
    fun build(maker: String, inputMint: String, outputMint: String, makingRaw: Long, takingRaw: Long, expiresInDays: Int = 30): Build {
        val body = JSONObject()
            .put("inputMint", inputMint)
            .put("outputMint", outputMint)
            .put("maker", maker).put("payer", maker)
            .put(
                "params",
                JSONObject().put("makingAmount", makingRaw.toString()).put("takingAmount", takingRaw.toString())
                    // Then the order lapses on its own rather than sitting on
                    // chain against a coin nobody remembers.
                    .put("expiredAt", ((System.currentTimeMillis() / 1000) + expiresInDays.toLong() * 86_400).toString()),
            )
            .put("computeUnitPrice", "auto")
        val built = post("/createOrder", body) ?: return Build.Failed("unreachable")
        built.optString("error").takeIf { it.isNotEmpty() }?.let { e ->
            return if (e.contains("at least", true) && e.contains("USD", true)) Build.TooSmall else Build.Failed(e)
        }
        val order = built.optString("order").takeIf { it.isNotEmpty() } ?: return Build.Failed("no order account")
        val requestId = built.optString("requestId").takeIf { it.isNotEmpty() } ?: return Build.Failed("no request id")
        val unsigned = built.optString("transaction").takeIf { it.isNotEmpty() }
            ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
            ?: return Build.Failed("no transaction")
        return Build.Ok(Built(order, requestId, unsigned))
    }

    /** The cancel, built and not signed. Null when Jupiter did not answer or refused. */
    fun buildCancel(maker: String, order: String): Built? {
        val built = post("/cancelOrder", JSONObject().put("maker", maker).put("order", order).put("computeUnitPrice", "auto")) ?: return null
        if (built.optString("error").isNotEmpty()) return null
        val requestId = built.optString("requestId").takeIf { it.isNotEmpty() } ?: return null
        val unsigned = built.optString("transaction").takeIf { it.isNotEmpty() }
            ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() } ?: return null
        return Built(order, requestId, unsigned)
    }

    /** The signature in its slot. The maker is the fee payer and usually the first signer, but never assumed. */
    fun attach(unsigned: ByteArray, maker: String, sig: ByteArray): ByteArray {
        val idx = SolanaTx.decode(unsigned)?.let { d -> d.staticAccountKeys.indexOf(maker).takeIf { it in 0 until d.numRequiredSignatures } } ?: 0
        return SolanaTx.attachSignature(unsigned, idx, sig)
    }

    /** Hand the signed bytes back to Jupiter, which sends them. The raw answer, or null when unreachable. */
    fun execute(built: Built, signed: ByteArray): JSONObject? =
        post("/execute", JSONObject().put("requestId", built.requestId).put("signedTransaction", Base64.encodeToString(signed, Base64.NO_WRAP)))

    /**
     * Simulate the order before signing it, and refuse anything that is not the
     * trade we asked for.
     *
     * The collar cannot judge this one: putting tokens into Jupiter's escrow
     * looks exactly like sending them to a stranger, so [AgentBroker] would
     * refuse every order. This is the narrower check that replaces it, and it is
     * deliberately strict — one mint may leave, no more of it than we hold, and
     * no SOL beyond the fee. A simulation we cannot get is a refusal, not a
     * shrug: the whole point of the order is that nobody will be watching it.
     */
    private suspend fun checkItOnlySellsThis(ctx: Context, tx: ByteArray, maker: String, position: Positions.Position): String? {
        val a = runCatching { ReceiptEngine.analyze(ctx, BlocklistScanner(ctx), tx, maker, null, requireSim = true) }.getOrNull()
            ?: return "could not simulate the order"
        val mine = a.receipt.outflows.filter { it.owner == maker }
        val solOut = mine.filter { it.mint == com.clearsign.core.NATIVE_SOL_MINT }.sumOf { -it.rawAmount }
        // Rent for the escrow account is real and legitimate; a payment is not.
        if (solOut > 5_000_000L) return "the order would move SOL out"
        val tokens = mine.filter { it.mint != com.clearsign.core.NATIVE_SOL_MINT }
        if (tokens.any { it.mint != position.mint }) return "the order touches a coin we did not ask about"
        val held = (position.units * Math.pow(10.0, position.decimals.toDouble())).toLong()
        val leaving = tokens.sumOf { -it.rawAmount }
        if (leaving > held) return "the order would sell more than we hold"
        a.receipt.risks.firstOrNull { it.severity == com.clearsign.core.Severity.DANGER }?.let { return it.detail }
        return null
    }

    /** The orders standing for a user: their accounts, and the coins they hold in escrow. */
    data class Live(val orders: Set<String>, val mints: Set<String>, val byMint: Map<String, String> = emptyMap())

    /**
     * What is live on chain for [user], or **null when Jupiter could not be asked**.
     *
     * Null and empty are not the same thing here, and the difference is worth a
     * position. Empty means the coins really are not in an order, so a holding
     * the wallet does not have is a holding that is gone. Null means we do not
     * know, and dropping a position on a failed network call would throw away the
     * price we paid for it and the stop that was watching it.
     */
    fun live(user: String): Live? {
        val o = get("/getTriggerOrders?user=$user&orderStatus=active") ?: return null
        if (o.optString("error").isNotEmpty()) return null
        val arr = o.optJSONArray("orders") ?: return Live(emptySet(), emptySet())
        val orders = HashSet<String>()
        val mints = HashSet<String>()
        val byMint = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val key = e.optString("orderKey").takeIf { it.isNotEmpty() }
            val mint = e.optString("inputMint").takeIf { it.isNotEmpty() }
            key?.let { orders += it }
            mint?.let { mints += it }
            // Which order holds which coin, so a position that lost its key can
            // get it back instead of sitting "parked in an order I did not place".
            if (key != null && mint != null) byMint.putIfAbsent(mint, key)
        }
        return Live(orders, mints, byMint)
    }

    /**
     * Take an order back, so a position sold by the loop does not leave an order
     * on chain trying to sell tokens the budget no longer has.
     */
    suspend fun cancel(ctx: Context, maker: String, order: String): Boolean = withContext(Dispatchers.IO) {
        cancelBlocking(ctx, maker, order)
    }

    private suspend fun cancelBlocking(ctx: Context, maker: String, order: String): Boolean {
        val built = buildCancel(maker, order) ?: return false
        val sig = SessionWallet.sign(ctx, SolanaTx.messageBytes(built.unsigned)) ?: return false
        val done = execute(built, attach(built.unsigned, maker, sig))
        return done != null && done.optString("error").isEmpty()
    }

    // ---- wire ----------------------------------------------------------------

    /**
     * Sul filo dell'IO, sempre.
     *
     * Era bloccante e veniva chiamata anche dal filo principale, dove Android
     * vieta la rete: l'eccezione che tira in quel caso non ha messaggio, quindi
     * nel registro si leggeva "POST /createOrder failed: null" e sembrava che
     * fosse Jupiter a rifiutare. Non era Jupiter. Visto il 17/09: un ordine da
     * quattordici dollari, ben sopra il minimo, mai arrivato in catena, e la
     * stessa chiamata con gli stessi numeri fatta da fuori tornava un ordine
     * valido al primo colpo.
     */
    private fun post(path: String, body: JSONObject): JSONObject? = HOSTS.firstNotNullOfOrNull { host ->
        try {
            val c = (URL(host + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 6000; readTimeout = 15000
                setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "application/json")
            }
            OutputStreamWriter(c.outputStream).use { it.write(body.toString()) }
            val code = c.responseCode
            val resp = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
            c.disconnect()
            // A 400 carries the reason and is more useful than a null, so it is
            // parsed rather than discarded.
            resp?.let { JSONObject(it) }
        } catch (e: Exception) {
            Log.w(TAG, "POST $path failed: ${e.message}"); null
        }
    }

    private fun get(path: String): JSONObject? = HOSTS.firstNotNullOfOrNull { host ->
        try {
            val c = (URL(host + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000; readTimeout = 12000; setRequestProperty("Accept", "application/json")
            }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
            c.disconnect()
            body?.let { if (it.trimStart().startsWith("[")) JSONObject().put("orders", JSONArray(it)) else JSONObject(it) }
        } catch (e: Exception) {
            Log.w(TAG, "GET $path failed: ${e.message}"); null
        }
    }
}
