package com.clearsign.app

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Jupiter swap (mainnet): a quote, then a ready-to-sign transaction. ClearSign
 * never signs it blind — the returned bytes go through the normal receipt +
 * Seed Vault flow. A small platform fee (bps) is routed to the fee wallet, and
 * shown honestly in the receipt as one of the destinations.
 */
object Jupiter {
    private const val TAG = "ClearSign-Jup"
    // quote-api.jup.ag/v6 was shut down (2026-09: no response at all). Swap API v1:
    // the free "lite" host first, the keyed host as a fallback (it also answers without a key, rate-limited).
    private val HOSTS = listOf("https://lite-api.jup.ag/swap/v1", "https://api.jup.ag/swap/v1")

    /** Our cut of a swap, in basis points (50 = 0.5%). */
    const val PLATFORM_FEE_BPS = 50

    val SOL_MINT = "So11111111111111111111111111111111111111112"

    data class Quote(
        val raw: JSONObject,             // the exact quoteResponse to hand back to /swap
        val inMint: String, val outMint: String,
        val inAmount: Long, val outAmount: Long,
        val priceImpactPct: Double,
        val feeBps: Int,
        val routeLabels: List<String>,   // AMMs in the route, in order
    )

    /**
     * Best route for [amount] raw units of [inputMint] → [outputMint]. Null on failure.
     *
     * [feeBps] is our cut, and asking for one is a promise to provide a token
     * account to receive it: Jupiter answers `feeAccount is required for swap
     * with platformFee` when the quote carries a fee and the build does not.
     * The agent passes 0, because it trades coins whose fee account does not
     * exist yet, and a swap that cannot be built earns nothing anyway.
     */
    fun quote(inputMint: String, outputMint: String, amount: Long, slippageBps: Int = 50, feeBps: Int = PLATFORM_FEE_BPS): Quote? {
        val q = "inputMint=$inputMint&outputMint=$outputMint&amount=$amount&slippageBps=$slippageBps" +
            (if (feeBps > 0) "&platformFeeBps=$feeBps" else "")
        val o = HOSTS.firstNotNullOfOrNull { getJson("$it/quote?$q") } ?: return null
        if (o.has("error")) { Log.w(TAG, "quote error: ${o.optString("error")}"); return null }
        val out = o.optString("outAmount").toLongOrNull() ?: return null
        val labels = o.optJSONArray("routePlan")?.let { rp ->
            (0 until rp.length()).mapNotNull { rp.optJSONObject(it)?.optJSONObject("swapInfo")?.optString("label")?.takeIf { l -> l.isNotEmpty() } }
        } ?: emptyList()
        return Quote(
            raw = o, inMint = inputMint, outMint = outputMint,
            inAmount = o.optString("inAmount").toLongOrNull() ?: amount, outAmount = out,
            priceImpactPct = o.optString("priceImpactPct").toDoubleOrNull() ?: 0.0,
            feeBps = feeBps, routeLabels = labels,
        )
    }

    /**
     * Can this token be sold back again?
     *
     * A honeypot quotes beautifully on the way in and has no route out, so the
     * only honest test is to ask for the opposite trade before buying. Three
     * answers, not two: true (a route exists), false (Jupiter says there is
     * none), null (we could not reach it — which is never an accusation).
     */
    fun sellableBack(mint: String, decimals: Int, usd: Double?): Boolean? {
        if (mint == SOL_MINT || mint == com.clearsign.core.NATIVE_SOL_MINT) return true
        // About ten dollars' worth, or one whole token when the price is unknown:
        // dust gets "no route" from every AMM and would libel an honest coin.
        val unit = Math.pow(10.0, decimals.toDouble())
        val amount = (if (usd != null && usd > 0) (10.0 / usd) * unit else unit)
            .coerceIn(1.0, 1e18).toLong()
        val q = "inputMint=$mint&outputMint=$SOL_MINT&amount=$amount&slippageBps=300"
        val o = HOSTS.firstNotNullOfOrNull { getJson("$it/quote?$q") } ?: return null
        if (o.has("error")) return false
        return o.optString("outAmount").toLongOrNull()?.let { it > 0 }
    }

    /**
     * Build the swap transaction (base64 v0). [feeAccount] is the platform-fee
     * token account (an ATA of the fee wallet for the output mint); when it
     * can't be used, the caller retries without it so the swap still works.
     */
    fun swapTransaction(quote: Quote, userPubkey: String, feeAccount: String?): ByteArray? {
        val body = JSONObject()
            .put("quoteResponse", quote.raw)
            .put("userPublicKey", userPubkey)
            .put("wrapAndUnwrapSol", true)
            .put("dynamicComputeUnitLimit", true)
        if (feeAccount != null) body.put("feeAccount", feeAccount)
        val o = HOSTS.firstNotNullOfOrNull { postJson("$it/swap", body) } ?: return null
        val b64 = o.optString("swapTransaction").takeIf { it.isNotEmpty() } ?: run { Log.w(TAG, "swap error: $o"); return null }
        return runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
    }

    /** The platform fee wallet's ATA for [outputMint], where our cut lands. Null when no fee wallet is set. */
    fun feeAccountFor(outputMint: String): String? {
        val fee = Base58.decodePubkey(BuildConfig.SKR_TREASURY) ?: return null
        val mint = Base58.decodePubkey(outputMint) ?: return null
        // Jupiter takes the fee in the output mint via the classic token program ATA.
        return Base58.encode(Pda.associatedTokenAddress(fee, mint, Base58.decode(SolanaTx.TOKEN_PROGRAM)))
    }

    private fun getJson(url: String): JSONObject? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 6000; readTimeout = 12000; setRequestProperty("Accept", "application/json") }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect()
        body?.let { if (it.trimStart().startsWith("[")) JSONObject().put("array", JSONArray(it)) else JSONObject(it) }
    } catch (e: Exception) { Log.w(TAG, "GET failed: ${e.message}"); null }

    private fun postJson(url: String, body: JSONObject): JSONObject? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 6000; readTimeout = 15000
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "application/json")
        }
        OutputStreamWriter(c.outputStream).use { it.write(body.toString()) }
        val code = c.responseCode
        val resp = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect()
        resp?.let { JSONObject(it) }
    } catch (e: Exception) { Log.w(TAG, "POST failed: ${e.message}"); null }
}
