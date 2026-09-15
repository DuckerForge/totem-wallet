package com.clearsign.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Jupiter's shield: what Jupiter itself says about a mint before it lets
 * you trade it. Not verified, freeze authority, transfer fees, a copy of a
 * known symbol. One call, no key, a few warnings with a severity.
 *
 * It sits under our own gates, never in their place: the collar and the
 * safety grade decide; this adds what a second pair of eyes saw. Unknown
 * never blocks.
 */
object TokenShield {
    data class Warning(val type: String, val message: String, val severity: String) {
        val critical: Boolean get() = severity.equals("critical", true)
        val warning: Boolean get() = severity.equals("warning", true)
    }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Warning>>>()
    private const val TTL = 10 * 60_000L

    fun warnings(mint: String): List<Warning>? {
        cache[mint]?.let { (at, w) -> if (System.currentTimeMillis() - at < TTL) return w }
        val o = get("https://lite-api.jup.ag/ultra/v1/shield?mints=$mint") ?: return null
        val arr = o.optJSONObject("warnings")?.optJSONArray(mint) ?: return emptyList<Warning>().also { cache[mint] = System.currentTimeMillis() to it }
        val out = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Warning(it.optString("type"), it.optString("message"), it.optString("severity", "info")) }
        }
        cache[mint] = System.currentTimeMillis() to out
        return out
    }

    private fun get(url: String): JSONObject? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 5_000; readTimeout = 8_000; setRequestProperty("Accept", "application/json") }
        if (c.responseCode in 200..299) JSONObject(c.inputStream.bufferedReader().readText()) else null
    } catch (e: Exception) { null }
}

/** Jupiter's warnings for [mint], as a small card. Nothing is drawn for a clean coin or an unreachable shield. */
@Composable
internal fun ShieldCard(mint: String, symbol: String) {
    var warnings by remember(mint) { mutableStateOf<List<TokenShield.Warning>>(emptyList()) }
    LaunchedEffect(mint) { warnings = withContext(Dispatchers.IO) { runCatching { TokenShield.warnings(mint) }.getOrNull().orEmpty() } }
    if (warnings.isEmpty()) return
    val worst = when { warnings.any { it.critical } -> Halo.red; warnings.any { it.warning } -> Halo.amber; else -> Halo.muted }
    Column(
        Modifier.fillMaxWidth().clip(rs(16)).background(worst.copy(alpha = 0.08f)).border(1.dp, worst.copy(alpha = 0.35f), rs(16)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HaloIcon(HIcon.SHIELD_LOCK, worst, 18.dp)
            Spacer(Modifier.width(9.dp))
            Text(stringResource(R.string.shield_title, symbol), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Halo.ink)
        }
        warnings.forEach { w ->
            Text("• " + w.message, fontFamily = Inter, fontSize = 12.sp, color = Halo.ink, lineHeight = 17.sp)
        }
        Text(stringResource(R.string.shield_note), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, lineHeight = 15.sp)
    }
}
