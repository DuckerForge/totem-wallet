package com.clearsign.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * The one check the winning bots have that our gates did not. Our gates read the mint:
 * authorities, holders, liquidity, whether it can be sold. Rugcheck reads the story around
 * it: whether the pool's LP is burned, locked or pullable tomorrow, whether the token copies
 * a verified one, whether the creator rugged before. Free, no key, one call per candidate.
 * Same contract as [CoinCheck]: unknown never blocks, only a clear "no" stops, and the trace says which.
 */
object RugCheck {
    private const val TAG = "Apex-Rug"
    private const val BASE = "https://api.rugcheck.xyz/v1/tokens/"

    sealed class Verdict {
        /** Fine, with the two numbers a person would want to see. */
        data class Ok(val score: Int, val lpLockedPct: Double?) : Verdict()
        data class Stop(val reason: String) : Verdict()
        object Unknown : Verdict()
    }

    /**
     * Where the lines are. Rugcheck's normalized score runs 0 (clean) to 100 (run): their badge
     * turns red above 50 or so, a pump.fun coin with a burned pool sits at 1. LP under half locked
     * on a shallow pool is a pool that can be pulled.
     */
    private const val SCORE_STOP = 60
    private const val LP_LOCKED_MIN_PCT = 50.0
    private const val LIQUIDITY_DEEP_USD = 250_000.0

    suspend fun verdict(mint: String, italian: Boolean): Verdict = withContext(Dispatchers.IO) {
        val d = get(BASE + mint + "/report/summary") ?: return@withContext Verdict.Unknown
        // The full report says "rugged" and how deep the pools are. One more
        // call, only when the summary has not already decided.
        val full = if (hasDanger(d)) null else get(BASE + mint + "/report")
        judge(d, full, italian)
    }

    private fun hasDanger(d: JSONObject): Boolean {
        val risks = d.optJSONArray("risks") ?: return false
        return (0 until risks.length()).any { risks.getJSONObject(it).optString("level") == "danger" }
    }

    /** The decision, pure, so a test can hand it the answers Rugcheck really gave. */
    fun judge(summary: JSONObject, full: JSONObject?, italian: Boolean): Verdict {
        val score = summary.optInt("score_normalised", -1)
        val lp = summary.optDouble("lpLockedPct").takeIf { !it.isNaN() }
        val risks = summary.optJSONArray("risks")
        val all = (0 until (risks?.length() ?: 0)).map { risks!!.getJSONObject(it) }
        val danger = all.firstOrNull { it.optString("level") == "danger" }
        val copycat = all.firstOrNull { it.optString("name").contains("Copycat", true) }
        val rugged = full?.optBoolean("rugged", false) == true
        val liquidity = full?.optDouble("totalMarketLiquidity")?.takeIf { !it.isNaN() }

        val why = when {
            rugged -> if (italian) "già svuotata (rug)" else "already rugged"
            danger != null -> danger.optString("name").ifBlank { "danger" }.let { if (italian) italianRiskName(it) else it }
            copycat != null -> if (italian) "copia di una moneta verificata" else "copy of a verified token"
            score >= SCORE_STOP -> if (italian) "punteggio di rischio $score su 100" else "risk score $score of 100"
            lp != null && lp < LP_LOCKED_MIN_PCT && (liquidity ?: 0.0) < LIQUIDITY_DEEP_USD ->
                if (italian) String.format(Locale.ROOT, "LP bloccata solo al %.0f%%", lp) else String.format(Locale.ROOT, "LP only %.0f%% locked", lp)
            else -> null
        }
        return if (why != null) Verdict.Stop(why) else Verdict.Ok(score.coerceAtLeast(0), lp)
    }

    /**
     * Rugcheck names its risks in English and the name lands inside an Italian sentence. The
     * frequent ones read in Italian; the rest stay as they came, better than guessing.
     */
    private val italianRiskNames = mapOf(
        "freeze authority still enabled" to "autorità di freeze ancora attiva",
        "mint authority still enabled" to "autorità di mint ancora attiva",
        "mutable metadata" to "metadati modificabili",
        "low liquidity" to "poca liquidità",
        "low amount of lp providers" to "pochi fornitori di liquidità",
        "large amount of lp unlocked" to "gran parte della LP non bloccata",
        "top 10 holders high ownership" to "i primi 10 detentori hanno troppo",
        "single holder ownership" to "un solo detentore ha quasi tutto",
        "high holder concentration" to "detentori troppo concentrati",
        "high ownership" to "proprietà troppo concentrata",
        "copycat token" to "copia di una moneta verificata",
        "symbol mismatch" to "simbolo non corrispondente",
        "name mismatch" to "nome non corrispondente",
        "transfer fee" to "commissione sul trasferimento",
        "permanent delegate" to "delegato permanente",
        "creator history of rugged tokens" to "il creatore ha già fatto rug",
        "rugged" to "già svuotata (rug)",
        "danger" to "pericolo",
    )
    internal fun italianRiskName(name: String): String = italianRiskNames[name.trim().lowercase()] ?: name

    private fun get(url: String): JSONObject? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000; readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Velum/1.0")
        }
        if (c.responseCode in 200..299) JSONObject(c.inputStream.bufferedReader().readText()) else null
    } catch (e: Exception) {
        Log.w(TAG, "rugcheck: " + (e.message ?: "?")); null
    }
}
