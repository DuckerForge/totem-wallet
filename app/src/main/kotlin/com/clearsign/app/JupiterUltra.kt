package com.clearsign.app

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Jupiter Ultra: the swap engine the pros use, without a key.
 *
 * One call gives the transaction with the route already chosen, the slippage
 * already estimated from the coin's own volatility, the priority fee already
 * set, and sometimes nobody to pay gas at all. After the signature the bytes
 * go back to Jupiter, which lands them itself, in private, out of the reach
 * of the sandwich bots that read the public mempool. Verified on the 15th of
 * September 2026: `lite-api.jup.ag/ultra/v1/order` answers with no key.
 *
 * What does not change: the bytes are read by the receipt and simulated
 * before anybody signs, exactly as with swap v1. Ultra decides the route; the
 * receipt decides whether it is signed. When Ultra does not answer the caller
 * falls back to swap v1 and says so once in the ledger.
 */
object JupiterUltra {
    private const val TAG = "Apex-Ultra"
    private val HOSTS = listOf("https://lite-api.jup.ag/ultra/v1", "https://api.jup.ag/ultra/v1")

    data class Order(
        val raw: JSONObject,
        val tx: ByteArray,
        val requestId: String,
        val inMint: String, val outMint: String,
        val inAmount: Long, val outAmount: Long,
        val slippageBps: Int,
        val priceImpactPct: Double,
        val gasless: Boolean,
        val prioritizationFeeLamports: Long,
        val router: String,
        val feeBps: Int,
        val routeLabels: List<String>,
    ) {
        /** The same shape the swap sheet and the loop already read. */
        fun asQuote(): Jupiter.Quote = Jupiter.Quote(raw, inMint, outMint, inAmount, outAmount, priceImpactPct, feeBps, routeLabels)
    }

    data class Exec(val signature: String?, val status: String, val error: String?)

    fun order(inputMint: String, outputMint: String, amount: Long, taker: String, slippageBps: Int? = null): Order? {
        val q = "inputMint=$inputMint&outputMint=$outputMint&amount=$amount&taker=$taker" + (slippageBps?.let { "&slippageBps=$it" } ?: "")
        val o = HOSTS.firstNotNullOfOrNull { getJson("$it/order?$q") } ?: return null
        return parse(o)
    }

    /** Pure, so a test can hand it the answer Jupiter really gave. Null when there is no transaction in it. */
    fun parse(o: JSONObject): Order? {
        if (o.has("errorMessage") || o.has("error")) { Log.w(TAG, "order: " + (o.optString("errorMessage").ifEmpty { o.optString("error") })); return null }
        val b64 = o.optString("transaction").takeIf { it.isNotEmpty() } ?: return null
        val tx = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return null
        val requestId = o.optString("requestId").takeIf { it.isNotEmpty() } ?: return null
        val labels = o.optJSONArray("routePlan")?.let { rp ->
            (0 until rp.length()).mapNotNull { rp.optJSONObject(it)?.optJSONObject("swapInfo")?.optString("label")?.takeIf { l -> l.isNotEmpty() } }
        } ?: emptyList()
        return Order(
            raw = o, tx = tx, requestId = requestId,
            inMint = o.optString("inputMint"), outMint = o.optString("outputMint"),
            inAmount = o.optString("inAmount").toLongOrNull() ?: 0L, outAmount = o.optString("outAmount").toLongOrNull() ?: return null,
            slippageBps = o.optInt("slippageBps", 0),
            priceImpactPct = o.optString("priceImpactPct").toDoubleOrNull() ?: 0.0,
            gasless = o.optBoolean("gasless", false),
            prioritizationFeeLamports = o.optString("prioritizationFeeLamports").toLongOrNull() ?: 0L,
            router = o.optString("router"),
            feeBps = o.optInt("feeBps", 0),
            routeLabels = labels,
        )
    }

    /**
     * Hand the signed bytes back to Jupiter, which lands them and waits for the
     * chain. "Success" here means confirmed; anything else is not landed.
     */
    fun execute(signedTx: ByteArray, requestId: String): Exec {
        val body = JSONObject().put("signedTransaction", Base64.encodeToString(signedTx, Base64.NO_WRAP)).put("requestId", requestId)
        val o = HOSTS.firstNotNullOfOrNull { postJson("$it/execute", body) } ?: return Exec(null, "unreachable", "Jupiter non raggiungibile")
        val status = o.optString("status")
        val sig = o.optString("signature").takeIf { it.isNotEmpty() }
        val err = o.optString("error").takeIf { it.isNotEmpty() } ?: o.optString("errorMessage").takeIf { it.isNotEmpty() }
        return if (status.equals("Success", true) && sig != null) Exec(sig, status, null)
        else Exec(sig, status.ifEmpty { "failed" }, err ?: status)
    }

    private fun getJson(url: String): JSONObject? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 6000; readTimeout = 12000; setRequestProperty("Accept", "application/json") }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect()
        body?.let { JSONObject(it) }
    } catch (e: Exception) { Log.w(TAG, "GET failed: ${e.message}"); null }

    private fun postJson(url: String, body: JSONObject): JSONObject? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 6000; readTimeout = 45000
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "application/json")
        }
        OutputStreamWriter(c.outputStream).use { it.write(body.toString()) }
        val code = c.responseCode
        val resp = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect()
        resp?.let { JSONObject(it) }
    } catch (e: Exception) { Log.w(TAG, "POST failed: ${e.message}"); null }
}
