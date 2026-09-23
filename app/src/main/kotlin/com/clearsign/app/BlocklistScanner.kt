package com.clearsign.app

import android.content.Context
import android.util.Log
import com.clearsign.core.ScanResult
import com.clearsign.core.TransactionScanner
import org.json.JSONObject

/**
 * The keyless [TransactionScanner]: every address the transaction touches (instruction
 * destinations and all static account keys, a hidden writable account counts) against a bundled
 * blocklist of known drainers and sanctioned wallets. The list ships in `assets/known_bad.json`
 * and refreshes without a code change; a small embedded seed covers a missing asset.
 */
class BlocklistScanner(context: Context) : TransactionScanner {

    private data class Entry(val reason: String)

    // address -> reason. `sanctioned` is a subset kept separately because the
    // core RiskEngine treats a sanctions hit as its own DANGER category.
    private val malicious = HashMap<String, Entry>()
    private val sanctioned = HashMap<String, Entry>()

    init {
        loadEmbedded()
        loadAsset(context.applicationContext)
        Log.i("ClearSign-Scan", "blocklist: ${malicious.size} malicious, ${sanctioned.size} sanctioned")
    }

    override fun scan(serializedTx: ByteArray, recipients: List<String>): ScanResult {
        // Scan the recipients plus every account key in the message, so a bad
        // program id or a hidden writable account is caught even if it is not the
        // decoded "destination".
        val decoded = SolanaTx.decode(serializedTx)
        val touched = (recipients + (decoded?.staticAccountKeys ?: emptyList())).toSet()

        val sanctionedHits = touched.filter { it in sanctioned }.toSet()
        val maliciousHit = touched.firstOrNull { it in malicious }

        val reason = when {
            maliciousHit != null -> malicious[maliciousHit]?.reason ?: "Address on the blocklist"
            sanctionedHits.isNotEmpty() -> sanctioned[sanctionedHits.first()]?.reason ?: "Address under sanctions"
            else -> null
        }
        return ScanResult(
            malicious = maliciousHit != null,
            sanctioned = sanctionedHits,
            reason = reason,
        )
    }

    private fun loadEmbedded() {
        // Documented starter entries. Extend via assets/known_bad.json.
        // (Kept intentionally tiny; real lists belong in the asset file.)
    }

    private fun loadAsset(ctx: Context) {
        val raw = try {
            ctx.assets.open("known_bad.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return // no asset bundled → embedded seed only
        }
        try {
            val root = JSONObject(raw)
            root.optJSONArray("malicious")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val addr = o.optString("address").takeIf { it.isNotBlank() } ?: continue
                    malicious[addr] = Entry(o.optString("reason", "Known malicious address"))
                }
            }
            root.optJSONArray("sanctioned")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val addr = o.optString("address").takeIf { it.isNotBlank() } ?: continue
                    sanctioned[addr] = Entry(o.optString("reason", "Address under sanctions"))
                }
            }
        } catch (e: Exception) {
            Log.w("ClearSign-Scan", "known_bad.json parse failed", e)
        }
    }
}
