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
    data class Deposit(val symbol: String, val asset: String, val decimals: Int, val raw: Long, val priceUsd: Double?, val logo: String?, val aprPct: Double?)

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
                // Basis points a year, straight from the pool.
                aprPct = p.optJSONObject("token")?.optString("totalRate")?.toDoubleOrNull()?.let { it / 100.0 },
            )
        }
        return out
    }

    private fun get(url: String): JSONArray? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 6_000; readTimeout = 8_000; setRequestProperty("Accept", "application/json") }
        if (c.responseCode in 200..299) JSONArray(c.inputStream.bufferedReader().readText()) else null
    } catch (e: Exception) { null }
}

/**
 * Jupiter's own positions for a wallet (governance stake, perps, launchpad,
 * offerbook, prediction markets): the Portfolio API, no key. Only Jupiter's
 * platforms are listed there; the rest of DeFi is read from the chain.
 */
object JupiterPortfolio {
    data class Position(val platform: String, val label: String, val name: String?, val valueUsd: Double, val apy: Double?) { fun platformId() = platform }

    fun positions(owner: String): List<Position> {
        val o = runCatching {
            val c = (java.net.URL("https://api.jup.ag/portfolio/v1/positions/$owner").openConnection() as java.net.HttpURLConnection)
                .apply { connectTimeout = 6_000; readTimeout = 20_000; setRequestProperty("Accept", "application/json") }
            if (c.responseCode in 200..299) org.json.JSONObject(c.inputStream.bufferedReader().readText()) else null
        }.getOrNull() ?: return emptyList()
        val arr = o.optJSONArray("elements") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            val v = e.optDouble("value").takeIf { !it.isNaN() && it > 0 } ?: return@mapNotNull null
            Position(e.optString("platformId"), e.optString("label"), e.optString("name").takeIf { it.isNotEmpty() }, v, e.optDouble("netApy").takeIf { !it.isNaN() }?.let { it * 100 })
        }
    }
}
