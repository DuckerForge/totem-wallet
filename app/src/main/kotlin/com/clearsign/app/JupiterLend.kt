package com.clearsign.app

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Deposits in Jupiter Lend, read from the same endpoint Jupiter's own wallet
 * uses, without a key. A deposit is a token you no longer hold and money you
 * still have; the token list cannot see it, so the portfolio asks here.
 */
object JupiterLend {
    data class Deposit(val symbol: String, val asset: String, val decimals: Int, val raw: Long, val priceUsd: Double?, val logo: String?)

    fun deposits(owner: String): List<Deposit> {
        val arr = get("https://lite-api.jup.ag/lend/v1/earn/positions?users=$owner") ?: return emptyList()
        val out = ArrayList<Deposit>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val raw = p.optString("underlyingAssets").toLongOrNull() ?: 0L
            if (raw <= 0L) continue
            val asset = p.optJSONObject("token")?.optJSONObject("asset") ?: continue
            out += Deposit(
                symbol = asset.optString("symbol"), asset = asset.optString("address"), decimals = asset.optInt("decimals", 6),
                raw = raw, priceUsd = asset.optString("price").toDoubleOrNull(), logo = asset.optString("logoUrl").takeIf { it.isNotEmpty() },
            )
        }
        return out
    }

    private fun get(url: String): JSONArray? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 6_000; readTimeout = 8_000; setRequestProperty("Accept", "application/json") }
        if (c.responseCode in 200..299) JSONArray(c.inputStream.bufferedReader().readText()) else null
    } catch (e: Exception) { null }
}
