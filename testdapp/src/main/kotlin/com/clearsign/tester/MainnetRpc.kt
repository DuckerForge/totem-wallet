package com.clearsign.tester

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal mainnet RPC + Jupiter client for the test dApp. Used only to *build*
 * realistic transactions (recent blockhash, the user's real token accounts, a
 * real Jupiter swap). The transactions are signed by ClearSign but NEVER sent,
 * so nothing here spends money.
 */
object MainnetRpc {
    private const val RPC = "https://api.mainnet-beta.solana.com"

    const val SOL_MINT = "So11111111111111111111111111111111111111112"
    const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

    data class TokenAcct(val pubkey: String, val mint: String, val decimals: Int, val amount: Long)

    fun balance(owner: String): Long? = try {
        post(RPC, "getBalance", JSONArray().put(owner))?.optJSONObject("result")?.optLong("value")
    } catch (_: Exception) { null }

    fun latestBlockhash(): ByteArray? = try {
        val resp = post(RPC, "getLatestBlockhash", JSONArray().put(JSONObject().put("commitment", "finalized")))
        val hash = resp?.getJSONObject("result")?.getJSONObject("value")?.getString("blockhash")
        hash?.let { Base58.decode(it) }
    } catch (_: Exception) { null }

    /** The user's token accounts with a non-zero balance (for realistic token txs). */
    fun tokenAccounts(owner: String): List<TokenAcct> = try {
        val params = JSONArray().put(owner)
            .put(JSONObject().put("programId", "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"))
            .put(JSONObject().put("encoding", "jsonParsed"))
        val arr = post(RPC, "getTokenAccountsByOwner", params)?.getJSONObject("result")?.optJSONArray("value")
        val out = ArrayList<TokenAcct>()
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val info = o.getJSONObject("account").getJSONObject("data").getJSONObject("parsed").getJSONObject("info")
            val amt = info.getJSONObject("tokenAmount")
            val bal = amt.optString("amount").toLongOrNull() ?: 0L
            if (bal > 0) out.add(TokenAcct(o.getString("pubkey"), info.getString("mint"), amt.optInt("decimals"), bal))
        }
        out
    } catch (_: Exception) { emptyList() }

    /** Fetch a real Jupiter swap transaction (v0, serialized) for a tiny SOL→USDC
     *  swap. Returns the serialized tx bytes; never broadcast. */
    fun jupiterSwapTx(userPubkey: String, lamportsIn: Long = 2_000_000L): ByteArray? = try {
        val quote = httpGet(
            "https://quote-api.jup.ag/v6/quote?inputMint=$SOL_MINT&outputMint=$USDC_MINT" +
                "&amount=$lamportsIn&slippageBps=50&onlyDirectRoutes=false",
        )?.let { JSONObject(it) }
        if (quote == null) null else {
            val body = JSONObject()
                .put("quoteResponse", quote)
                .put("userPublicKey", userPubkey)
                .put("wrapAndUnwrapSol", true)
            val swap = httpPost("https://quote-api.jup.ag/v6/swap", body.toString())?.let { JSONObject(it) }
            swap?.optString("swapTransaction")?.takeIf { it.isNotBlank() }?.let { Base64.decode(it, Base64.DEFAULT) }
        }
    } catch (_: Exception) { null }

    private fun post(url: String, method: String, params: JSONArray): JSONObject? =
        httpPost(url, JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params).toString())
            ?.let { JSONObject(it) }

    private fun httpPost(url: String, body: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15000; readTimeout = 20000
        }
        OutputStreamWriter(conn.outputStream).use { it.write(body) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
        conn.disconnect()
        if (code in 200..299) text else null
    } catch (_: Exception) { null }

    private fun httpGet(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15000; readTimeout = 20000
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }
        conn.disconnect()
        if (code in 200..299) text else null
    } catch (_: Exception) { null }
}
