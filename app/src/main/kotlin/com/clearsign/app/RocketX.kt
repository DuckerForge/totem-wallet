package com.clearsign.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * RocketX: the bridge. One API over 200 chains, DEX routes and exchange
 * routes, no account for the person using it. Verified on the 16th of
 * September 2026 with the partner key: SOL to ETH quotes from Relay, NEAR
 * Intents, RocketX's own pool and ChangeNow; USDC Solana to USDC Arbitrum
 * with zero fee on Relay.
 *
 * How a bridge from Solana works here: a quote picks a route; `/swap` opens
 * the order and answers with a **deposit address**; the SOL or USDC go there
 * with the ordinary Send, receipt and fingerprint included, labelled as the
 * bridge; `/status` follows the order by the chain signature. Routes that
 * need a memo are skipped: our Send does not write one, and a deposit
 * without its memo is money in limbo.
 *
 * Privacy, said straight: RocketX asks nobody who they are, and an exchange
 * route breaks the on‑chain thread between what went in and what came out.
 * It is not a mixer and it is not anonymity; it is one less form.
 */
object RocketX {
    private const val TAG = "Apex-RocketX"
    private const val HOST = "https://api.rocketx.exchange/v1"
    private const val KEY = BuildConfig.ROCKETX_KEY

    /**
     * Con la chiave si va diretti, senza si passa dal servizio.
     *
     * La chiave dentro l'APK chiunque la estrae: spende la quota di qualcun
     * altro o se la fa revocare. Tenendola sul servizio il telefono non ne ha
     * bisogno.
     *
     * **Lo scambio, detto per intero.** Il servizio finisce in mezzo alla
     * risposta che contiene l'indirizzo di deposito, quindi entra nella lista di
     * chi potrebbe sostituirlo, lista che prima conteneva solo RocketX. E'
     * infrastruttura nostra, ma e' una superficie sui soldi al posto di una sulla
     * quota. Per questo la scelta e' una riga in `local.properties` e non una
     * decisione presa qui: c'e' la chiave, si va diretti; non c'e', si passa di
     * la'.
     */
    private val viaWorker: String? get() =
        BuildConfig.CROWD_URL.takeIf { KEY.isBlank() && it.isNotBlank() }?.trimEnd('/')

    private fun endpoint(path: String): String =
        viaWorker?.let { it + "/?rx=" + URLEncoder.encode(path, "UTF-8") } ?: (HOST + path)

    val enabled: Boolean get() = KEY.isNotBlank() || viaWorker != null

    data class Network(val id: String, val name: String, val chainId: String, val native: String)
    data class Token(val id: Int, val symbol: String, val name: String, val contract: String, val decimals: Int, val networkId: String, val icon: String?, val isNative: Boolean)
    data class Quote(
        val exchange: String, val keyword: String, val type: String, val walletLess: Boolean, val memoRequired: Boolean,
        val fromAmount: Double, val toAmount: Double, val feeUsd: Double, val gasUsd: Double, val minutes: Int?,
        val fromId: Int, val toId: Int, val allowed: Boolean, val priceImpact: Double?,
    )
    data class Order(val requestId: String, val txId: Long, val depositAddress: String?, val memo: String?, val toAmount: Double, val exchange: String)

    /**
     * L'indirizzo ha la forma giusta per quella catena?
     *
     * Null vuol dire che non lo sappiamo, e non sapere non e' un no: bloccare
     * una catena di cui non conosciamo il formato vorrebbe dire rompere il ponte
     * ogni volta che RocketX ne aggiunge una. Ma dove il formato lo conosciamo,
     * e non torna, si blocca.
     *
     * Perche' e' l'unico posto dell'app dove un errore di incollaggio manda via
     * i soldi senza che niente lo dica. Ovunque altro un indirizzo sbagliato e'
     * un indirizzo sbagliato su Solana, e lo scontrino lo mostra prima della
     * firma; qui l'indirizzo che conta e' su un'altra catena, il deposito va a
     * RocketX, e quello che si vede firmare non e' la destinazione finale.
     * Incollare un indirizzo Solana mentre si fa il ponte verso Arbitrum passava
     * senza una parola.
     */
    fun addressFits(network: Network, address: String): Boolean? {
        val a = address.trim()
        if (a.isEmpty()) return null
        // Le catene EVM hanno un chainId numerico, e tutte lo stesso formato.
        if (network.chainId.toLongOrNull() != null) return Regex("^0x[0-9a-fA-F]{40}$").matches(a)
        return when (network.native.uppercase()) {
            "BTC" -> Regex("^(bc1[0-9ac-hj-np-z]{11,71}|[13][1-9A-HJ-NP-Za-km-z]{25,34})$").matches(a)
            "TRX" -> Regex("^T[1-9A-HJ-NP-Za-km-z]{33}$").matches(a)
            "SUI" -> Regex("^0x[0-9a-fA-F]{64}$").matches(a)
            "TON" -> Regex("^([A-Za-z0-9_-]{48}|-?\\d+:[0-9a-fA-F]{64})$").matches(a)
            "SOL" -> Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$").matches(a)
            else -> null
        }
    }

    /** The chains worth a chip, in this order, when RocketX lists them. */
    private val PREFERRED = listOf("ethereum", "arbitrum", "Base Chain", "binance", "polygon", "optimism", "BTC", "TRON", "avalanche", "sui", "ton")

    @Volatile private var networks: List<Network> = emptyList()

    fun networks(): List<Network> {
        networks.takeIf { it.isNotEmpty() }?.let { return it }
        val o = get(endpoint("/configs")) ?: return emptyList()
        val arr = o.optJSONArray("supported_network") ?: return emptyList()
        val all = (0 until arr.length()).mapNotNull { i ->
            val n = arr.optJSONObject(i) ?: return@mapNotNull null
            Network(n.optString("id"), n.optString("name"), n.optString("chainId"), n.optString("native_token"))
        }
        val byId = all.associateBy { it.id }
        networks = PREFERRED.mapNotNull { byId[it] }
        return networks
    }

    fun tokens(chainId: String, keyword: String = "All", networkId: String? = null): List<Token> {
        val o = getArray(endpoint("/tokens?chainId=${enc(chainId)}&page=1&perPage=100&keyword=${enc(keyword)}")) ?: return emptyList()
        return (0 until o.length()).mapNotNull { i ->
            val t = o.optJSONObject(i) ?: return@mapNotNull null
            if (t.optInt("enabled", 1) != 1) return@mapNotNull null
            if (networkId != null && t.optString("network_id") != networkId) return@mapNotNull null
            Token(
                t.optInt("id"), t.optString("token_symbol"), t.optString("token_name"), t.optString("contract_address"), t.optInt("token_decimals"),
                t.optString("network_id"), t.optString("icon_url").takeIf { it.isNotEmpty() }, t.optInt("is_native_token") == 1,
            )
        }
    }

    /** [fromToken]/[toToken] are contract addresses, or null for the chain's native coin. Amount in whole coins. */
    fun quote(fromToken: String?, fromNetwork: String, toToken: String?, toNetwork: String, amount: Double, slippage: Double = 1.0): List<Quote> {
        val q = "fromToken=${fromToken ?: "null"}&fromNetwork=${enc(fromNetwork)}&toToken=${toToken ?: "null"}&toNetwork=${enc(toNetwork)}&amount=$amount&slippage=$slippage"
        val o = get(endpoint("/quotation?$q")) ?: return emptyList()
        val arr = o.optJSONArray("quotes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val x = arr.optJSONObject(i) ?: return@mapNotNull null
            val e = x.optJSONObject("exchangeInfo") ?: JSONObject()
            Quote(
                exchange = e.optString("title"), keyword = e.optString("keyword"), type = x.optString("type"),
                walletLess = e.optBoolean("walletLess", false), memoRequired = e.optBoolean("memoRequired", false),
                fromAmount = x.optDouble("fromAmount", amount), toAmount = x.optDouble("toAmount", 0.0),
                feeUsd = x.optDouble("platformFeeUsd", 0.0), gasUsd = x.optDouble("gasFeeUsd", 0.0),
                minutes = x.optJSONObject("estTimeInSeconds")?.optInt("avg")?.takeIf { it > 0 }?.let { it / 60 },
                fromId = x.optJSONObject("fromTokenInfo")?.optInt("id") ?: 0, toId = x.optJSONObject("toTokenInfo")?.optInt("id") ?: 0,
                allowed = x.optBoolean("isTxnAllowed", true),
                priceImpact = x.optJSONObject("additionalInfo")?.optDouble("priceImpact")?.takeIf { !it.isNaN() },
            )
        }.filter { it.allowed && it.toAmount > 0 }.sortedByDescending { it.toAmount }
    }

    /** Open the order. What comes back for a deposit route is the address to pay. */
    fun swap(fromId: Int, toId: Int, userAddress: String, destinationAddress: String, amount: Double, slippage: Double = 1.0): Order? {
        val body = JSONObject().put("fromTokenId", fromId).put("toTokenId", toId).put("userAddress", userAddress)
            .put("destinationAddress", destinationAddress).put("fee", 1).put("amount", amount).put("slippage", slippage).put("disableEstimate", true)
        val o = post(endpoint("/swap"), body) ?: return null
        val sw = o.optJSONObject("swap") ?: return null
        val tx = sw.optJSONObject("tx")
        return Order(
            requestId = o.optString("requestId"), txId = o.optLong("txId"),
            depositAddress = sw.optString("depositAddress").takeIf { it.isNotEmpty() && it != "null" } ?: tx?.optString("to")?.takeIf { it.isNotEmpty() && it != "null" },
            memo = tx?.optString("memo")?.takeIf { it.isNotEmpty() && it != "null" },
            toAmount = sw.optDouble("toAmount", 0.0), exchange = o.optJSONObject("exchangeInfo")?.optString("title") ?: "",
        )
    }

    /** Where the order stands, by the chain signature of the deposit. */
    fun status(txSignature: String, requestId: String): String? =
        get(endpoint("/status?txId=${enc(txSignature)}&requestId=${enc(requestId)}"))?.let { it.optString("status").ifEmpty { it.optJSONObject("swap")?.optString("status") } }

    // ---- bridges this phone opened, so their status can be asked later -------

    data class Bridge(val requestId: String, val signature: String, val from: String, val to: String, val toNetwork: String, val at: Long, val exchange: String, val deposit: String = "")

    fun remember(ctx: Context, b: Bridge) {
        val arr = JSONArray(ctx.getSharedPreferences("apex_bridges", Context.MODE_PRIVATE).getString("all", "[]"))
        arr.put(JSONObject().put("r", b.requestId).put("s", b.signature).put("f", b.from).put("t", b.to).put("n", b.toNetwork).put("at", b.at).put("e", b.exchange).put("d", b.deposit))
        ctx.getSharedPreferences("apex_bridges", Context.MODE_PRIVATE).edit().putString("all", arr.toString()).apply()
    }

    fun bridges(ctx: Context): List<Bridge> = runCatching {
        val arr = JSONArray(ctx.getSharedPreferences("apex_bridges", Context.MODE_PRIVATE).getString("all", "[]"))
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Bridge(it.optString("r"), it.optString("s"), it.optString("f"), it.optString("t"), it.optString("n"), it.optLong("at"), it.optString("e"), it.optString("d")) } }
    }.getOrDefault(emptyList()).sortedByDescending { it.at }

    /** The deposit went out: keep its chain signature with the order, for `/status` later. */
    fun attachSignature(ctx: Context, deposit: String, signature: String) {
        val all = bridges(ctx)
        val hit = all.firstOrNull { it.deposit == deposit && it.signature.isEmpty() } ?: return
        val arr = JSONArray()
        all.forEach { b ->
            val x = if (b === hit) b.copy(signature = signature) else b
            arr.put(JSONObject().put("r", x.requestId).put("s", x.signature).put("f", x.from).put("t", x.to).put("n", x.toNetwork).put("at", x.at).put("e", x.exchange).put("d", x.deposit))
        }
        ctx.getSharedPreferences("apex_bridges", Context.MODE_PRIVATE).edit().putString("all", arr.toString()).apply()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000; readTimeout = 25_000
        setRequestProperty("Accept", "application/json")
        // Sul servizio la chiave la mette il servizio: qui non ce n'e' una.
        if (KEY.isNotBlank()) setRequestProperty("x-api-key", KEY)
    }

    private fun get(url: String): JSONObject? = try {
        val c = open(url); val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect(); body?.let { JSONObject(it) }
    } catch (e: Exception) { Log.w(TAG, "GET failed: ${e.message}"); null }

    private fun getArray(url: String): JSONArray? = try {
        val c = open(url); val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect(); body?.let { JSONArray(it) }
    } catch (e: Exception) { Log.w(TAG, "GET failed: ${e.message}"); null }

    private fun post(url: String, body: JSONObject): JSONObject? = try {
        val c = open(url).apply { requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/json") }
        OutputStreamWriter(c.outputStream).use { it.write(body.toString()) }
        val code = c.responseCode
        val resp = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect(); resp?.let { JSONObject(it) }
    } catch (e: Exception) { Log.w(TAG, "POST failed: ${e.message}"); null }
}
