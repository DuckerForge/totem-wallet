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
 * RocketX, the bridge: one API over 200 chains, DEX and exchange routes, no account.
 * Verified 16 Sep 2026 with the partner key. A quote picks a route, `/swap` opens the
 * order and answers with a deposit address, the money goes there through the ordinary
 * Send (receipt, fingerprint), `/status` follows it. Routes needing a memo are skipped,
 * Send writes none. Privacy: no identity asked and the on-chain thread breaks at the
 * exchange; not a mixer, not anonymity, one less form.
 */
object RocketX {
    private const val TAG = "Apex-RocketX"
    private const val HOST = "https://api.rocketx.exchange/v1"
    private const val KEY = BuildConfig.ROCKETX_KEY

    /**
     * With the key we go direct, without it through the service. A key inside the APK is
     * anyone's to extract and spend or get revoked. The trade, said whole: the service then
     * sits in the middle of the answer that carries the deposit address, a surface on the
     * money instead of one on the quota. So the choice is a line in `local.properties`.
     */
    private val viaWorker: String? get() =
        BuildConfig.CROWD_URL.takeIf { KEY.isBlank() && it.isNotBlank() }?.trimEnd('/')

    private fun endpoint(path: String): String =
        viaWorker?.let { it + "/?rx=" + URLEncoder.encode(path, "UTF-8") } ?: (HOST + path)

    val enabled: Boolean get() = KEY.isNotBlank() || viaWorker != null

    data class Network(
        val id: String, val name: String, val chainId: String, val native: String,
        /** Where to look something up on that chain. RocketX says it, chain by chain. */
        val explorer: String = "",
        /** The short, stable name RocketX sends: ETHEREUM, BASE, AVAXC. See [POPULAR]. */
        val short: String = "",
    )

    /**
     * The eight in use, in this order; everything else sits behind the search. RocketX's own
     * order puts a chain nobody here would use third, and two hundred pills are a wall, not a
     * choice. Hooked on the readable `shorthand`, not the id, and if one disappears the chain
     * only leaves the front row: the old id list silently dropped chains from the screen.
     */
    val POPULAR = listOf("ETHEREUM", "BASE", "ARBITRUM", "BNB", "POLYGON", "OPTIMISM", "AVAXC", "BITCOIN")

    /** The front row, in [POPULAR] order: only the ones that actually exist. */
    fun popular(all: List<Network>): List<Network> {
        val byShort = all.associateBy { it.short.uppercase() }
        return POPULAR.mapNotNull { byShort[it] }
    }

    /**
     * The deal, once struck. When `/swap` answers the numbers are no longer negotiable: that
     * order waits for that amount at that deposit address and sends that amount to the other
     * side. From here the screen only says what was agreed and has it signed.
     */
    data class Deal(
        val requestId: String,
        val fromText: String,
        val toAmount: Double,
        val toSymbol: String,
        val network: String,
        val toAddress: String,
        val exchange: String,
        val minutes: Int?,
        val explorer: String,
    )

    /** Where to look at an order when something does not add up. */
    const val ORDERS_URL = "https://app.rocketx.exchange/transaction-history"

    /**
     * The explorer link for an address on its chain. Works for the ones behind a hash too,
     * like Tronscan: "https://tronscan.org/#/" becomes ".../#/address/T…" with no special case.
     */
    fun explorerAddress(explorer: String, address: String): String? =
        explorer.takeIf { it.isNotBlank() && address.isNotBlank() }
            ?.let { it.trimEnd('/') + "/address/" + address }
    data class Token(val id: Int, val symbol: String, val name: String, val contract: String, val decimals: Int, val networkId: String, val icon: String?, val isNative: Boolean)
    data class Quote(
        val exchange: String, val keyword: String, val type: String, val walletLess: Boolean, val memoRequired: Boolean,
        val fromAmount: Double, val toAmount: Double, val feeUsd: Double, val gasUsd: Double, val minutes: Int?,
        val fromId: Int, val toId: Int, val allowed: Boolean, val priceImpact: Double?,
        /**
         * Fee plus gas in the coin you send, as they declare it. Not all you pay: on one SOL these
         * two are 0.0079 and what arrives is 0.0102 short; the rest is the round-trip exchange
         * inside the route, seen only by subtracting. So the screen shows the subtraction and
         * these two underneath, as detail.
         */
        val feeCoin: Double?,
        /** The coin's price, implicit in the same answer: the fee in dollars over the fee in coin. */
        val usdPerUnit: Double?,
    )
    data class Order(val requestId: String, val txId: Long, val depositAddress: String?, val memo: String?, val toAmount: Double, val exchange: String)

    /**
     * The quotes, and why the missing ones are missing. A refused route comes back in
     * `quotes` without `toAmount`, with the reason in `err` ("Min. Amount: 0.462745 SOL").
     * It used to be dropped and the screen said "no route, try another amount".
     * [minAmount] is the lowest refused minimum, valid only when nothing usable is left.
     * [minUsd] stands still: measured 20 Sep 2026, private routes want a round 50 $ in SOL
     * as in USDC, so the SOL figure changes by the hour and is written nowhere here.
     */
    data class Quotes(val list: List<Quote>, val minAmount: Double?, val minUsd: Double?)

    /**
     * A deliberately silly amount, to be told no and read the minimum: the number is in no
     * list, only a refused quote says it. A thousandth of a SOL is under anybody's floor.
     */
    const val PROBE = 0.001

    /** "Min. Amount: 0.462745 SOL" -> 0.462745. Il simbolo lo sappiamo gia' noi. */
    private val MIN_NUM = Regex("([0-9]+(?:\\.[0-9]+)?)")

    /**
     * The private route's numbers, before the bridge is opened. The Send tag said "costs
     * 1 or 2%", written by hand long ago, and not that nothing moves under fifty dollars.
     * One call when the amount is fine, two when refused (the second says by how much).
     * Blocking, IO. [fromToken] null is SOL, else the mint.
     */
    data class Privately(
        val minAmount: Double?,
        val minUsd: Double?,
        val costCoin: Double?,
        val costUsd: Double?,
        val costPct: Double?,
        val minutes: Int?,
    )

    /**
     * Yesterday's minimum, so the line is not empty while today's arrives. Knowing it costs
     * two calls and three seconds, and a line that arrives after you stopped looking never
     * arrived. The dollar floor does not move (fifty, measured ten minutes straight), so the
     * last seen is almost always right; written at once, corrected a moment later.
     */
    private fun floorPrefs(ctx: Context) = ctx.getSharedPreferences("apex_rocketx", Context.MODE_PRIVATE)

    fun rememberFloor(ctx: Context, symbol: String, coin: Double, usd: Double?) {
        floorPrefs(ctx).edit().putString("floor_$symbol", coin.toString())
            .putString("floorusd_$symbol", usd?.toString() ?: "").apply()
    }

    fun recallFloor(ctx: Context, symbol: String): Privately? {
        val p = floorPrefs(ctx)
        val coin = p.getString("floor_$symbol", null)?.toDoubleOrNull() ?: return null
        return Privately(coin, p.getString("floorusd_$symbol", null)?.toDoubleOrNull(), null, null, null, null)
    }

    fun privately(fromToken: String?, amount: Double?): Privately? {
        // Without going through [home]. It only read an id that is "solana" and is already
        // hard-coded as the source chain in every call here, at the price of loading the
        // chains: 170 KB and two hundred entries before asking the one thing needed, while
        // the Send tag stayed mute.
        if (amount != null && amount > 0) {
            val q = quote(fromToken, "solana", fromToken, "solana", amount)
            val best = q.list.firstOrNull { it.walletLess }
            if (best != null && best.toAmount > 0 && best.toAmount < amount) {
                val cost = amount - best.toAmount
                return Privately(null, null, cost, best.usdPerUnit?.let { cost * it }, cost / amount * 100.0, best.minutes)
            }
            q.minAmount?.let { return Privately(it, q.minUsd, null, null, null, null) }
        }
        val probe = quote(fromToken, "solana", fromToken, "solana", PROBE)
        if (probe.minAmount == null) Log.w(TAG, "privately: preventivo senza minimo (rotte ${probe.list.size})")
        return Privately(probe.minAmount, probe.minUsd, null, null, null, null)
    }

    /**
     * Does the address fit that chain? Null means we do not know, and not knowing is not a
     * no: blocking an unknown format would break the bridge every time RocketX adds a chain.
     * Where the format is known and does not match, block. This is the one place a paste
     * error sends money away silently: the deposit goes to RocketX, what you sign is not the
     * final destination, and a Solana address pasted while bridging to Arbitrum went through.
     */
    fun addressFits(network: Network, address: String): Boolean? {
        val a = address.trim()
        if (a.isEmpty()) return null
        // Native coin first, numeric chainId second. If RocketX gave Bitcoin or Tron a numeric
        // chainId too (not verified live), checking the number first would apply the EVM rule
        // to a bitcoin address and refuse every valid one. The native coin leaves no doubt.
        when (network.native.uppercase()) {
            "BTC" -> return Regex("^(bc1[0-9ac-hj-np-z]{11,71}|[13][1-9A-HJ-NP-Za-km-z]{25,34})$").matches(a)
            "TRX" -> return Regex("^T[1-9A-HJ-NP-Za-km-z]{33}$").matches(a)
            "SUI" -> return Regex("^0x[0-9a-fA-F]{64}$").matches(a)
            "TON" -> return Regex("^([A-Za-z0-9_-]{48}|-?\\d+:[0-9a-fA-F]{64})$").matches(a)
            "SOL" -> return Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$").matches(a)
        }
        // EVM chains share one address format and RocketX identifies them with a hexadecimal
        // chainId ("0x1", "0xA4B1", "0x38"), verified live 17 Sep. The first version looked
        // for a decimal, never found it, and the check on the six chains that matter never
        // fired. A check that never fires is worse than none, because it seems to be there.
        val cid = network.chainId.trim()
        val evm = (cid.startsWith("0x", ignoreCase = true) && cid.drop(2).toLongOrNull(16) != null) || cid.toLongOrNull() != null
        if (evm) return Regex("^0x[0-9a-fA-F]{40}$").matches(a)
        return null
    }

    @Volatile private var networks: List<Network> = emptyList()
    @Volatile private var homeNet: Network? = null

    /**
     * The chains, in RocketX's order. A hand-written list of eleven ids had three dead ones
     * (`avalanche` is now `avaxc-mainnet`, `sui` is `Sui Mainnet`, `ton` is `TON`), and a
     * dead id gives no error: the chain simply does not appear. RocketX sends two hundred,
     * already sorted (`sort_order`) and flagged enabled: take all the enabled ones minus
     * Solana, the shore we leave from.
     */
    fun networks(): List<Network> {
        networks.takeIf { it.isNotEmpty() }?.let { return it }
        val o = get(endpoint("/configs")) ?: return emptyList()
        val arr = o.optJSONArray("supported_network") ?: return emptyList()
        val parsed = (0 until arr.length()).mapNotNull { i ->
            val n = arr.optJSONObject(i) ?: return@mapNotNull null
            if (n.optInt("enabled", 1) != 1) return@mapNotNull null
            val id = n.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            (n.optInt("sort_order", 9999)) to Network(
                id, n.optString("name"), n.optString("chainId"), n.optString("native_token"),
                n.optString("block_explorer_url"), n.optString("shorthand"),
            )
        }.sortedBy { it.first }.map { it.second }
        // Solana leaves the destination list (it is the shore we leave from) but is kept aside:
        // the private send has Solana on both sides and needs its explorer and address format.
        homeNet = parsed.firstOrNull { it.id.equals("solana", true) }
        networks = parsed.filterNot { it.id.equals("solana", true) }
        return networks
    }

    /** La sponda di casa: Solana. Fuori dalle destinazioni, ma serve all'invio privato. */
    fun home(): Network? {
        homeNet?.let { return it }
        networks()
        return homeNet
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
    fun quote(fromToken: String?, fromNetwork: String, toToken: String?, toNetwork: String, amount: Double, slippage: Double = 1.0): Quotes {
        val q = "fromToken=${fromToken ?: "null"}&fromNetwork=${enc(fromNetwork)}&toToken=${toToken ?: "null"}&toNetwork=${enc(toNetwork)}&amount=$amount&slippage=$slippage"
        val o = get(endpoint("/quotation?$q")) ?: return Quotes(emptyList(), null, null)
        val arr = o.optJSONArray("quotes") ?: return Quotes(emptyList(), null, null)
        // The minimum of whoever said no, with the price it used to compute it, before
        // the refused routes leave the list.
        val refused = (0 until arr.length()).mapNotNull { i ->
            val x = arr.optJSONObject(i) ?: return@mapNotNull null
            val why = x.optString("err").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            val m = MIN_NUM.find(why)?.value?.toDoubleOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            m to x.optDouble("fromTokenUsdValue").takeIf { !it.isNaN() && it > 0 }
        }.minByOrNull { it.first }
        val list = (0 until arr.length()).mapNotNull { i ->
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
                feeCoin = listOfNotNull(
                    x.optDouble("platformFeeInSourceToken").takeIf { !it.isNaN() && it > 0 },
                    x.optDouble("networkFeeInSourceToken").takeIf { !it.isNaN() && it > 0 },
                ).takeIf { it.isNotEmpty() }?.sum(),
                // An accepted quote carries no coin price, but it carries the same fee twice, in coin
                // and in dollars: their ratio is the price, free.
                usdPerUnit = x.optDouble("platformFeeInSourceToken").takeIf { !it.isNaN() && it > 0 }
                    ?.let { pf -> x.optDouble("platformFeeUsd").takeIf { !it.isNaN() && it > 0 }?.div(pf) },
            )
        }.filter { it.allowed && it.toAmount > 0 }.sortedByDescending { it.toAmount }
        // A minimum is declared only if it stopped everything: with a live route behind
        // it, the figure is one exchange's whim, not a threshold.
        if (list.isNotEmpty() || refused == null) return Quotes(list, null, null)
        return Quotes(list, refused.first, refused.second?.times(refused.first))
    }

    /**
     * Open the order; for a deposit route the answer is the address to pay. The refund
     * address is sent explicitly: private routes declare `isRefundAddressRequired: true`,
     * and there the bet is on where the money returns when the exchange fails. It goes
     * back where it came from.
     */
    fun swap(fromId: Int, toId: Int, userAddress: String, destinationAddress: String, amount: Double, slippage: Double = 1.0): Order? {
        val body = JSONObject().put("fromTokenId", fromId).put("toTokenId", toId).put("userAddress", userAddress)
            .put("destinationAddress", destinationAddress).put("refundAddress", userAddress)
            .put("fee", 1).put("amount", amount).put("slippage", slippage).put("disableEstimate", true)
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

    /**
     * How it ended, and where to go and look. Only `status` was read, and "success" alone
     * proves nothing: RocketX is happy, not the money arrived. `destinationTransactionUrl`
     * is the transaction on the other chain, the one page that proves arrival, and
     * `actualAmount` is what really arrived. RocketX builds the links, not us: a hand-written
     * explorer list would rot like the chain list did. Measured 18 Sep 2026, SOL to ETH on Base.
     */
    data class Status(
        val state: String,
        val subState: String,
        val actualAmount: Double,
        val expected: Double,
        val originUrl: String?,
        val destUrl: String?,
        val destAddress: String,
    ) {
        /** Over, for better or worse: nothing left to wait for. */
        val done: Boolean get() = state.equals("success", true) || state.equals("failed", true) || state.equals("refunded", true)
        val good: Boolean get() = state.equals("success", true)
    }

    fun status(txSignature: String, requestId: String): Status? {
        val o = get(endpoint("/status?txId=${enc(txSignature)}&requestId=${enc(requestId)}")) ?: return null
        val state = o.optString("status").ifEmpty { o.optJSONObject("swap")?.optString("status").orEmpty() }
        if (state.isEmpty()) return null
        fun url(k: String) = o.optString(k).takeIf { it.startsWith("http") }
        return Status(
            state = state, subState = o.optString("subState"),
            actualAmount = o.optDouble("actualAmount", 0.0), expected = o.optDouble("expectedTokenAmount", 0.0),
            originUrl = url("originTransactionUrl"), destUrl = url("destinationTransactionUrl"),
            destAddress = o.optString("destinationAddress"),
        )
    }

    // ---- bridges this phone opened, so their status can be asked later -------

    /**
     * A bridge this phone opened. It also keeps where the money was meant to land and how
     * much, which were not saved before: the history could only say "SOL to Base" with no
     * way to check afterwards whether it arrived.
     */
    data class Bridge(
        val requestId: String, val signature: String, val from: String, val to: String, val toNetwork: String,
        val at: Long, val exchange: String, val deposit: String = "",
        val toAddress: String = "", val toAmount: Double = 0.0, val explorer: String = "",
    )

    private fun json(b: Bridge) = JSONObject()
        .put("r", b.requestId).put("s", b.signature).put("f", b.from).put("t", b.to).put("n", b.toNetwork)
        .put("at", b.at).put("e", b.exchange).put("d", b.deposit)
        .put("da", b.toAddress).put("ta", b.toAmount).put("x", b.explorer)

    private fun bridge(o: JSONObject) = Bridge(
        o.optString("r"), o.optString("s"), o.optString("f"), o.optString("t"), o.optString("n"),
        o.optLong("at"), o.optString("e"), o.optString("d"),
        o.optString("da"), o.optDouble("ta", 0.0), o.optString("x"),
    )

    fun remember(ctx: Context, b: Bridge) {
        val arr = JSONArray(ctx.getSharedPreferences("apex_bridges", Context.MODE_PRIVATE).getString("all", "[]"))
        arr.put(json(b))
        ctx.getSharedPreferences("apex_bridges", Context.MODE_PRIVATE).edit().putString("all", arr.toString()).apply()
    }

    fun bridges(ctx: Context): List<Bridge> = runCatching {
        val arr = JSONArray(ctx.getSharedPreferences("apex_bridges", Context.MODE_PRIVATE).getString("all", "[]"))
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { bridge(it) } }
    }.getOrDefault(emptyList()).sortedByDescending { it.at }

    /** An order opened and never paid: out of the history, because nothing ever left. */
    fun forget(ctx: Context, requestId: String) {
        val arr = JSONArray()
        bridges(ctx).filterNot { it.requestId == requestId }.forEach { arr.put(json(it)) }
        ctx.getSharedPreferences("apex_bridges", Context.MODE_PRIVATE).edit().putString("all", arr.toString()).apply()
    }

    /** The deposit went out: keep its chain signature with the order, for `/status` later. */
    fun attachSignature(ctx: Context, deposit: String, signature: String) {
        val all = bridges(ctx)
        val hit = all.firstOrNull { it.deposit == deposit && it.signature.isEmpty() } ?: return
        val arr = JSONArray()
        all.forEach { b -> arr.put(json(if (b === hit) b.copy(signature = signature) else b)) }
        ctx.getSharedPreferences("apex_bridges", Context.MODE_PRIVATE).edit().putString("all", arr.toString()).apply()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000; readTimeout = 25_000
        setRequestProperty("Accept", "application/json")
        // Through the service the service adds the key: there is none here.
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
