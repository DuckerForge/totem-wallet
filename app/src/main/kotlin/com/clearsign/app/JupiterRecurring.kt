package com.clearsign.app

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Buying a little at a time, without the phone. A Recurring order deposits the whole amount
 * with Jupiter today and its keeper swaps one slice per interval; same virtue as [JupiterTrigger],
 * same discipline: receipt and Seed Vault before anything is signed. Verified keyless 2026-09-15:
 * `createOrder` answers and refuses a round under fifty USDC ("minimum is 50.00 USDC"), quoted back as Jupiter says it. `inAmount` is the whole deposit; each round is `inAmount / numberOfOrders`.
 */
object JupiterRecurring {
    private const val TAG = "Apex-Recurring"
    private val HOSTS = listOf("https://lite-api.jup.ag/recurring/v1", "https://api.jup.ag/recurring/v1")

    /** Built by Jupiter, signed by nobody yet. */
    class Built(val requestId: String, val unsigned: ByteArray)

    sealed class Build {
        class Ok(val built: Built) : Build()
        /** Below Jupiter's floor. [said] is Jupiter's own sentence, with the number in it. */
        class TooSmall(val said: String) : Build()
        class Failed(val reason: String) : Build()
    }

    /**
     * [inAmountRaw] is the whole deposit in [inputMint] units, split into
     * [numberOfOrders] rounds one [intervalSec] apart.
     */
    fun build(user: String, inputMint: String, outputMint: String, inAmountRaw: Long, numberOfOrders: Int, intervalSec: Long): Build {
        val body = JSONObject()
            .put("user", user).put("inputMint", inputMint).put("outputMint", outputMint)
            .put(
                "params",
                JSONObject().put(
                    "time",
                    JSONObject().put("inAmount", inAmountRaw).put("numberOfOrders", numberOfOrders).put("interval", intervalSec),
                ),
            )
        val built = post("/createOrder", body) ?: return Build.Failed("unreachable")
        built.optString("error").takeIf { it.isNotEmpty() }?.let { e ->
            return if (e.contains("minimum", true)) Build.TooSmall(e) else Build.Failed(e)
        }
        val requestId = built.optString("requestId").takeIf { it.isNotEmpty() } ?: return Build.Failed("no request id")
        val unsigned = built.optString("transaction").takeIf { it.isNotEmpty() }
            ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
            ?: return Build.Failed("no transaction")
        return Build.Ok(Built(requestId, unsigned))
    }

    fun buildCancel(user: String, order: String): Built? {
        val built = post("/cancelOrder", JSONObject().put("order", order).put("user", user).put("recurringType", "time")) ?: return null
        if (built.optString("error").isNotEmpty()) return null
        val requestId = built.optString("requestId").takeIf { it.isNotEmpty() } ?: return null
        val unsigned = built.optString("transaction").takeIf { it.isNotEmpty() }
            ?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() } ?: return null
        return Built(requestId, unsigned)
    }

    fun execute(built: Built, signed: ByteArray): JSONObject? =
        post("/execute", JSONObject().put("requestId", built.requestId).put("signedTransaction", Base64.encodeToString(signed, Base64.NO_WRAP)))

    /** The active time-based orders for [user], as their account keys, or null when Jupiter could not be asked. */
    fun live(user: String): Set<String>? {
        val o = get("/getRecurringOrders?user=$user&recurringType=time&orderStatus=active&includeFailedTx=false") ?: return null
        if (o.optString("error").isNotEmpty()) return null
        val arr = o.optJSONArray("time") ?: return emptySet()
        val out = HashSet<String>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            (e.optString("orderKey").takeIf { it.isNotEmpty() } ?: e.optString("order").takeIf { it.isNotEmpty() })?.let { out += it }
        }
        return out
    }

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
            body?.let { JSONObject(it) }
        } catch (e: Exception) {
            Log.w(TAG, "GET $path failed: ${e.message}"); null
        }
    }
}
