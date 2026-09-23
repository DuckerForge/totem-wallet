package com.clearsign.app

import android.util.Log
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * What an address actually does: one call to Helius Enhanced Transactions for its recent
 * activity, the kinds of operations, programs, counterparties, whether an exchange funded it,
 * and the patterns that matter (a payout fan-out, a collector of many inflows). Mainnet with a Helius key only.
 */
object AddressTrace {
    private const val TAG = "ClearSign-Trace"
    private const val LIMIT = 40

    /** Well-known custodial hot wallets: money coming *from* these means a KYC'd origin. */
    private val EXCHANGES = mapOf(
        "5tzFkiKscXHK5ZXCGbXZxdw7gTjjD1mBwuoFbhUvuAi9" to "Binance", "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM" to "Binance",
        "H8sMJSCQxfKiFTCfDR3DUMLPwcRbM61LGFJ8N4dK3WjS" to "Coinbase", "GJRs4FwHtemZ5ZE9x3FNvJ8TMwitKTh21yxdRPqn7npE" to "Coinbase",
        "5VCwKtCXgCJ6kit5FybXjvriW3xELsFxY5XUrGUM6Y9n" to "OKX", "AC5RDfQFmDS1deWZos921JfqscXdByf8BKHs5ACWjtW2" to "Bybit",
        "FWznbcNXWQuHTawe9RxvQ2LdCENssh12dsznf4RiouN5" to "Kraken", "ASTyfSima4LLAdDgoFGkgqoKowG1LZFDr9fAQrg7iaJZ" to "MEXC",
        "u6PJ8DtQuPFnfmwHbGFULQ4u4EgjDiyYKjVEsynXq2w" to "Gate.io", "2AQdpHJ2JpcEgPiATUXjQxA8QmafFegfQwSLWSprPicm" to "Bitget",
    )

    data class Trace(
        val txCount: Int,                       // in the window (≤ LIMIT)
        val kinds: List<Pair<String, Int>>,     // TRANSFER 30, BURN 6 …
        val programs: List<String>,             // human names of sources: Jupiter, Raydium …
        val counterparties: Int,
        val senders: Int,                       // distinct wallets that paid this address
        val receivers: Int,                     // distinct wallets this address paid
        val exchangeFunders: List<String>,      // "Binance", …
        val fanOut: Boolean,                    // pays ≥4 wallets in single transactions, repeatedly (payout bot)
        val collector: Boolean,                 // receives from many wallets, sends to few (funnel)
        val oldest: Long?, val newest: Long?,   // epoch seconds in the window
    )

    private fun apiKey(): String? = Regex("api-key=([A-Za-z0-9-]+)").find(BuildConfig.HELIUS_RPC_URL)?.groupValues?.get(1)

    val available: Boolean get() = apiKey() != null

    /** Blocking; never on the main thread. Null when unavailable. */
    fun scan(address: String): Trace? {
        val key = apiKey() ?: return null
        val text = try {
            val c = (URL("https://api.helius.xyz/v0/addresses/$address/transactions?api-key=$key&limit=$LIMIT").openConnection() as HttpURLConnection)
                .apply { connectTimeout = 6000; readTimeout = 10000; setRequestProperty("Accept", "application/json") }
            if (c.responseCode != 200) { c.disconnect(); return null }
            c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
        } catch (e: Exception) { Log.w(TAG, "trace failed: ${e.message}"); return null }
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return null
        val kinds = HashMap<String, Int>(); val sources = LinkedHashMap<String, Int>()
        val senders = HashSet<String>(); val receivers = HashSet<String>(); val funders = LinkedHashSet<String>()
        var fanOutTx = 0; var oldest: Long? = null; var newest: Long? = null
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            kinds.merge(t.optString("type", "UNKNOWN"), 1, Int::plus)
            t.optString("source").takeIf { it.isNotEmpty() && it != "UNKNOWN" }?.let { sources.merge(it, 1, Int::plus) }
            val ts = t.optLong("timestamp"); if (ts > 0) { oldest = minOf(oldest ?: ts, ts); newest = maxOf(newest ?: ts, ts) }
            val outs = HashSet<String>()
            fun edge(from: String, to: String) {
                if (from == address && to.isNotEmpty() && to != address) { receivers.add(to); outs.add(to) }
                if (to == address && from.isNotEmpty() && from != address) { senders.add(from); EXCHANGES[from]?.let { funders.add(it) } }
            }
            t.optJSONArray("nativeTransfers")?.let { a -> for (k in 0 until a.length()) a.optJSONObject(k)?.let { edge(it.optString("fromUserAccount"), it.optString("toUserAccount")) } }
            t.optJSONArray("tokenTransfers")?.let { a -> for (k in 0 until a.length()) a.optJSONObject(k)?.let { edge(it.optString("fromUserAccount"), it.optString("toUserAccount")) } }
            if (outs.size >= 4) fanOutTx++
        }
        val n = arr.length()
        return Trace(
            txCount = n,
            kinds = kinds.entries.sortedByDescending { it.value }.map { it.key to it.value },
            programs = sources.entries.sortedByDescending { it.value }.map { pretty(it.key) }.distinct().take(5),
            counterparties = (senders + receivers).size, senders = senders.size, receivers = receivers.size,
            exchangeFunders = funders.toList(),
            fanOut = n > 0 && fanOutTx >= maxOf(3, n / 3),
            collector = senders.size >= 8 && receivers.size <= 2,
            oldest = oldest, newest = newest,
        )
    }

    /** "SYSTEM_PROGRAM" → "System", "JUPITER" → "Jupiter", "SOLANA_PROGRAM_LIBRARY" → "SPL Token". */
    fun pretty(source: String): String = when (source) {
        "SYSTEM_PROGRAM" -> "System"; "SOLANA_PROGRAM_LIBRARY" -> "SPL Token"; "COMPUTE_BUDGET" -> "Compute Budget"
        else -> source.lowercase().split('_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }
}
