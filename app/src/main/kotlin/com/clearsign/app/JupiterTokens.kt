package com.clearsign.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Jupiter's token registry (Token API v2): the same list the Jupiter app itself
 * shows, so a swap can offer every coin instead of six hardcoded ones.
 *
 * It answers the three things a picker needs and that nothing else in the app
 * could provide: the **name and logo** of a mint we do not hold, and — the real
 * blocker — its **decimals**, which the DAS path in [SolanaRpc.dasAssets] drops.
 *
 * Everything learned here is pushed into [TokenSymbols] too, so receipts and the
 * portfolio get better names and logos for free.
 */
object JupiterTokens {
    private const val TAG = "ClearSign-JupTok"
    private val HOSTS = listOf("https://lite-api.jup.ag/tokens/v2", "https://api.jup.ag/tokens/v2")
    private const val TOP_FILE = "jup_top_tokens.json"
    private const val TOP_TTL_MS = 24L * 60 * 60 * 1000

    data class Tok(
        val mint: String,
        val symbol: String,
        val name: String,
        val icon: String?,
        val decimals: Int,
        val usd: Double? = null,
        /** The 24h move in percent, when the registry has one. */
        val change24h: Double? = null,
        val verified: Boolean = false,
        val liquidity: Double = 0.0,
        // What the registry knows about the coin itself, for TokenSafety. All of it
        // rides along in the same answer, so grading a coin costs no extra call.
        val organic: String? = null,
        val canMint: Boolean = false,
        val canFreeze: Boolean = false,
        val token2022: Boolean = false,
        val topHoldersPct: Double? = null,
        val devPct: Double? = null,
        val devMints: Int = 0,
        val holders: Int = 0,
    ) {
        /**
         * [sellable] comes from an actual quote back to SOL; null when nobody asked.
         * [ext] is what the mint account says it can still do to you after the
         * purchase; left out, it comes from whatever has already been read, so a
         * list can show the flag without every row making its own call.
         */
        fun facts(sellable: Boolean? = null, ext: com.clearsign.core.MintExtensions? = null) = com.clearsign.core.TokenFacts(
            verified = verified, organic = organic, canMint = canMint, canFreeze = canFreeze,
            token2022 = token2022, topHoldersPct = topHoldersPct, devPct = devPct, devMints = devMints,
            holders = holders, liquidityUsd = liquidity, sellable = sellable,
            ext = ext ?: TokenExtensions.cached(mint) ?: com.clearsign.core.MintExtensions.NONE,
        )
    }

    /** Everything we have ever seen, so the UI can name a mint without a round trip. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Tok>()
    @Volatile private var top: List<Tok> = emptyList()
    @Volatile private var topAt = 0L

    fun cached(mint: String): Tok? = cache[mint]?.also { touch(mint) }

    /** Quando una moneta e' stata usata l'ultima volta: decide chi resta sul disco. */
    private val touchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun touch(mint: String) { touchedAt[mint] = System.currentTimeMillis() }

    /** Quante monete tiene il disco, al massimo. Oltre, vanno via quelle usate da piu' tempo. */
    const val DISK_MAX = 2000
    /** Un file piu' grande di cosi' non si legge: e' un file cresciuto prima del tetto. */
    const val DISK_MAX_BYTES = 1_000_000L

    /** Le [max] monete usate piu' di recente, per il disco. Pura, per il test. */
    internal fun keepNewest(all: Collection<Tok>, touched: Map<String, Long>, max: Int): List<Tok> =
        all.sortedByDescending { touched[it.mint] ?: 0L }.take(max)

    // ---- l'archivio e il disco -------------------------------------------
    //
    // Nome, simbolo, decimali e icona di una moneta non cambiano mai, e finora
    // questa mappa viveva solo in memoria: ogni riavvio dell'app ricominciava da
    // zero e richiedeva tutto. Nessuno paga in denaro, si paga in limiti di
    // frequenza, ed e' la stessa quota che serve a mostrare un prezzo o un
    // grafico mentre qualcuno sta guardando.
    //
    // Due passi, dal piu' economico. Il disco: quello che questo telefono ha
    // gia' visto non si richiede mai piu'. L'archivio condiviso: quello che
    // questo telefono non ha mai visto ma qualcun altro si'. Solo se manca da
    // tutte e due si disturba Jupiter.
    @Volatile private var diskFile: File? = null

    fun warmDisk(ctx: Context) {
        if (diskFile != null) return
        val f = File(ctx.filesDir, "tokens.json")
        diskFile = f
        runCatching {
            if (!f.exists()) return
            // Un file cresciuto senza tetto si butta invece di leggerlo: il tetto
            // di adesso lo riscrive piccolo alla prima occasione.
            if (f.length() > DISK_MAX_BYTES) { f.delete(); return }
            // `putIfAbsent`: adesso questo gira dopo il primo fotogramma, e una
            // moneta chiesta nel frattempo, col prezzo, non va sovrascritta dal disco.
            parse(JSONArray(f.readText())).forEach { cache.putIfAbsent(it.mint, it) }
        }
    }

    /**
     * Il disco ricorda come si chiama una moneta, mai quanto vale.
     *
     * Un nome non invecchia. Un prezzo si': riletto domani da un file sarebbe un
     * numero vecchio con l'aria di essere quello di adesso, ed e' peggio di
     * nessun numero. Quindi qui `usd` e la variazione escono azzerati, e chi ha
     * bisogno di un prezzo lo va a prendere.
     */
    private fun saveDisk() {
        val f = diskFile ?: return
        runCatching { f.writeText(rawOf(keepNewest(cache.values, touchedAt, DISK_MAX).map { it.copy(usd = null, change24h = null) })) }
    }

    /**
     * Quello che l'archivio sa: come si chiama e quante cifre ha, nient'altro.
     *
     * Niente prezzo, niente liquidita', niente giudizio sulla moneta. Un token
     * che arriva da qui ha zero liquidita' e non verificato, che e' esattamente
     * come appare una moneta sconosciuta: chi la deve giudicare la trattera' col
     * sospetto che merita, invece di fidarsi di un dato che non abbiamo.
     */
    private fun fromArchive(mints: List<String>): List<Tok> {
        val base = BuildConfig.CROWD_URL.takeIf { it.isNotBlank() } ?: return emptyList()
        val body = runCatching {
            val c = (java.net.URL(base.trimEnd('/') + "/?t=" + mints.take(20).joinToString(",")).openConnection() as java.net.HttpURLConnection)
                .apply { connectTimeout = 5_000; readTimeout = 10_000; setRequestProperty("Accept", "application/json") }
            if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyList()
        return runCatching {
            val o = org.json.JSONObject(body)
            o.keys().asSequence().mapNotNull { k ->
                val j = o.optJSONObject(k) ?: return@mapNotNull null
                val sym = j.optString("s").ifEmpty { return@mapNotNull null }
                Tok(k, sym, j.optString("n"), j.optString("i").ifEmpty { null }, j.optInt("d"))
            }.toList()
        }.getOrDefault(emptyList())
    }

    /**
     * The popular list, by Jupiter's 24h organic score. Served from memory, then
     * from disk (a day old at most), then from the network. Blocking: call on IO.
     */
    fun top(ctx: Context): List<Tok> {
        top.takeIf { it.isNotEmpty() && System.currentTimeMillis() - topAt < TOP_TTL_MS }?.let { return it }
        val file = File(ctx.filesDir, TOP_FILE)
        if (top.isEmpty() && file.exists()) {
            runCatching { parse(JSONArray(file.readText())) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let {
                top = it
                topAt = file.lastModified()
                if (System.currentTimeMillis() - topAt < TOP_TTL_MS) return it
            }
        }
        val fresh = fetch("/toporganicscore/24h?limit=100")
        if (fresh.isEmpty()) return top
        top = fresh
        topAt = System.currentTimeMillis()
        runCatching { file.writeText(rawOf(fresh)) }
        return fresh
    }

    /** Free-text search: a symbol, a name, or one or more mints. Blocking: call on IO. */
    fun search(query: String): List<Tok> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return fetch("/search?query=" + URLEncoder.encode(q, "UTF-8"))
    }

    /** Metadata for specific mints (up to 100 per call). Blocking: call on IO. */
    fun byMints(mints: List<String>): Map<String, Tok> {
        val todo = mints.distinct().filter { it != com.clearsign.core.NATIVE_SOL_MINT }
        if (todo.isEmpty()) return emptyMap()
        // Questa chiede a Jupiter, sempre, e non passa ne' dal disco ne'
        // dall'archivio.
        //
        // Chi la chiama vuole un prezzo, e un prezzo ha un'eta'. Il disco e
        // l'archivio sanno come si chiama una moneta, quante cifre ha e che
        // faccia: cose che non cambiano mai. Non sanno quanto vale, e per un po'
        // oggi hanno risposto lo stesso, con `usd` vuoto: il Mercato scriveva
        // "no price" su monete che un prezzo ce l'hanno eccome.
        //
        // Il risparmio vero e' in `warm`, che e' la strada del volume: i nomi
        // per la diretta, per il censimento, per i grafici. Li' una risposta
        // scritta ieri vale quanto una di adesso.
        val out = HashMap<String, Tok>()
        todo.chunked(100)
            .flatMap { chunk -> fetch("/search?query=" + chunk.joinToString(",")) }
            .forEach { out[it.mint] = it; cache[it.mint] = it; touch(it.mint) }
        saveDisk()
        return out
    }

    /**
     * The pool the agent picks from: what is traded most and what real wallets
     * are buying right now, merged and de-duplicated.
     *
     * Two lists rather than one because they disagree in a useful way: the
     * traded list is where the money is, the organic list is where the people
     * are. The freshly-launched list (`/recent`) is deliberately left out: those
     * rows come back with two or three holders, which no gate will pass and no
     * budget should buy.
     *
     * Returns [com.clearsign.core.Candidate], not [Tok], because the trading
     * windows in the answer go stale in minutes and must never be cached.
     * Blocking: call on IO.
     */
    fun pool(): List<com.clearsign.core.Candidate> {
        val seen = LinkedHashMap<String, com.clearsign.core.Candidate>()
        fun take(arr: JSONArray?) {
            if (arr == null) return
            parse(arr) // seeds the name and icon cache for everything we are about to rank
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i)?.let(::candidate) ?: continue
                seen.getOrPut(c.mint) { c }
            }
        }
        // Four of Jupiter's own lists, because they disagree in useful ways: traded
        // is where the money is, organic is where the people are, trending is what
        // is being talked about, and the same question over an hour and over a day
        // returns different coins.
        listOf(
            "/toptraded/24h?limit=100",
            "/toporganicscore/1h?limit=100",
            "/toporganicscore/24h?limit=100",
            "/toptrending/24h?limit=100",
        ).forEach { path -> take(HOSTS.firstNotNullOfOrNull { getArray(it + path) }) }

        // And one source that is not Jupiter at all. See [Gecko]: its mints come
        // back through the same lookup, so they reach the gates in the same shape
        // as everything above, with the same trading windows attached.
        val outside = runCatching { Gecko.mints() }.getOrDefault(emptyList()).filter { it !in seen }
        if (outside.isNotEmpty()) {
            outside.chunked(100).forEach { chunk ->
                take(HOSTS.firstNotNullOfOrNull { getArray(it + "/search?query=" + chunk.joinToString(",")) })
            }
        }
        return seen.values.toList()
    }

    /**
     * Put names on a pile of mints in as few calls as possible.
     *
     * The crowd feed arrives as addresses, and an address is not something anybody
     * reads: "Vesper bought 25zrhp…pump" says nothing, "Vesper bought KITTY" says
     * the whole thing. One search per fifty mints, and whatever the registry has
     * never heard of simply keeps its address, which is honest.
     *
     * Blocking: call on IO.
     */
    /**
     * Solo come si chiamano, e questa e' la strada del volume.
     *
     * Serve alla diretta, al censimento, ai grafici: posti dove di una moneta si
     * vuole il simbolo e la faccia, non il prezzo. Quindi puo' passare dal disco
     * e dall'archivio condiviso, dove un nome scritto ieri vale quanto uno di
     * adesso, e disturba Jupiter solo per quelle che non conosce nessuno.
     */
    fun warm(mints: Collection<String>) {
        val missing = mints.filter { it.isNotEmpty() && cache[it] == null }.distinct()
        if (missing.isEmpty()) return
        val still = missing.filter { m -> fromArchiveInto(m) == null }
        for (chunk in still.chunked(50)) {
            val q = URLEncoder.encode(chunk.joinToString(","), "UTF-8")
            HOSTS.firstNotNullOfOrNull { getArray("$it/search?query=$q") }?.let { parse(it) }
        }
        saveDisk()
    }

    /** Una moneta dall'archivio nella cache, se c'e'. Ritorna null se non c'era. */
    private fun fromArchiveInto(mint: String): Tok? =
        fromArchive(listOf(mint)).firstOrNull()?.also { cache[it.mint] = it; touch(it.mint) }

    /**
     * One coin, with its trading windows, so a receipt can judge the thing it is
     * about to receive and not only the transaction that brings it. Blocking.
     */
    fun candidateOf(mint: String): com.clearsign.core.Candidate? {
        val arr = HOSTS.firstNotNullOfOrNull { getArray(it + "/search?query=" + URLEncoder.encode(mint, "UTF-8")) } ?: return null
        parse(arr)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == mint) return candidate(o)
        }
        return null
    }

    /** One registry row as the scan sees it. Null when it is not a usable token. */
    private fun candidate(o: JSONObject): com.clearsign.core.Candidate? {
        val mint = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val sym = o.optString("symbol").takeIf { it.isNotEmpty() } ?: return null
        if (!o.has("decimals")) return null
        val audit = o.optJSONObject("audit")
        fun num(k: String) = o.optDouble(k).takeIf { !it.isNaN() }
        val age = o.optJSONObject("firstPool")?.optString("createdAt")?.takeIf { it.isNotEmpty() }?.let { iso ->
            runCatching {
                val ms = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(iso)!!.time
                ((System.currentTimeMillis() - ms) / 60_000.0).coerceAtLeast(0.0)
            }.getOrNull()
        }
        return com.clearsign.core.Candidate(
            mint = mint, symbol = sym,
            name = o.optString("name").takeIf { it.isNotEmpty() } ?: sym,
            decimals = o.optInt("decimals"),
            usd = num("usdPrice")?.takeIf { it > 0 },
            liquidity = num("liquidity") ?: 0.0,
            mcap = num("mcap"), fdv = num("fdv"),
            holders = o.optInt("holderCount").takeIf { it > 0 },
            organicScore = num("organicScore"),
            verified = o.optBoolean("isVerified"),
            canMint = !o.isNull("mintAuthority") || audit?.optBoolean("mintAuthorityDisabled", true) == false,
            canFreeze = !o.isNull("freezeAuthority") || audit?.optBoolean("freezeAuthorityDisabled", true) == false,
            token2022 = o.optString("tokenProgram").let { it.isNotEmpty() && it != SolanaTx.TOKEN_PROGRAM },
            topHoldersPct = audit?.optDouble("topHoldersPercentage")?.takeIf { !it.isNaN() },
            devMints = audit?.optInt("devMints") ?: 0,
            ageMinutes = age,
            s5m = window(o.optJSONObject("stats5m")), s1h = window(o.optJSONObject("stats1h")),
            s6h = window(o.optJSONObject("stats6h")), s24h = window(o.optJSONObject("stats24h")),
        )
    }

    private fun window(o: JSONObject?): com.clearsign.core.ScanWindow? {
        if (o == null) return null
        fun num(k: String) = o.optDouble(k).takeIf { !it.isNaN() }
        fun int(k: String) = if (o.has(k)) o.optInt(k) else null
        return com.clearsign.core.ScanWindow(
            priceChange = num("priceChange"), liquidityChange = num("liquidityChange"), volume = num("volume"),
            buyVolume = num("buyVolume"), sellVolume = num("sellVolume"),
            buyOrganicVolume = num("buyOrganicVolume"), sellOrganicVolume = num("sellOrganicVolume"),
            numBuys = int("numBuys"), numTraders = int("numTraders"),
            numOrganicBuyers = int("numOrganicBuyers"), numNetBuyers = int("numNetBuyers"),
            holderChange = num("holderChange"),
        )
    }

    private fun fetch(path: String): List<Tok> {
        val arr = HOSTS.firstNotNullOfOrNull { getArray(it + path) } ?: return emptyList()
        return parse(arr)
    }

    internal fun parse(arr: JSONArray): List<Tok> {
        val out = ArrayList<Tok>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val mint = o.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val sym = o.optString("symbol").takeIf { it.isNotEmpty() } ?: continue
            if (!o.has("decimals")) continue
            val audit = o.optJSONObject("audit")
            val t = Tok(
                mint = mint,
                symbol = sym,
                name = o.optString("name").takeIf { it.isNotEmpty() } ?: sym,
                icon = o.optString("icon").takeIf { it.isNotEmpty() },
                decimals = o.optInt("decimals"),
                usd = o.optDouble("usdPrice").takeIf { !it.isNaN() && it > 0 },
                change24h = o.optJSONObject("stats24h")?.optDouble("priceChange")?.takeIf { !it.isNaN() },
                verified = o.optBoolean("isVerified"),
                liquidity = o.optDouble("liquidity").takeIf { !it.isNaN() } ?: 0.0,
                organic = o.optString("organicScoreLabel").takeIf { it.isNotEmpty() },
                // The raw authority fields are the truth; `audit` restates them as
                // "disabled" booleans and adds what it knows about the holders.
                canMint = !o.isNull("mintAuthority") || audit?.optBoolean("mintAuthorityDisabled", true) == false,
                canFreeze = !o.isNull("freezeAuthority") || audit?.optBoolean("freezeAuthorityDisabled", true) == false,
                token2022 = o.optString("tokenProgram").let { it.isNotEmpty() && it != SolanaTx.TOKEN_PROGRAM },
                topHoldersPct = audit?.optDouble("topHoldersPercentage")?.takeIf { !it.isNaN() },
                devPct = audit?.optDouble("devBalancePercentage")?.takeIf { !it.isNaN() },
                devMints = audit?.optInt("devMints") ?: 0,
                holders = o.optInt("holderCount"),
            )
            cache[mint] = t
            touch(mint)
            TokenSymbols.seed(mint, t.symbol, t.name, t.icon)
            out.add(t)
        }
        return out
    }

    /** Re-serialise what we keep, so the disk copy stays small instead of storing Jupiter's full rows. */
    private fun rawOf(list: List<Tok>): String {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.mint).put("symbol", t.symbol).put("name", t.name)
                    .put("icon", t.icon ?: JSONObject.NULL).put("decimals", t.decimals)
                    .put("usdPrice", t.usd ?: JSONObject.NULL)
                    .put("isVerified", t.verified).put("liquidity", t.liquidity)
                    .put("organicScoreLabel", t.organic ?: JSONObject.NULL)
                    .put("mintAuthority", if (t.canMint) "live" else JSONObject.NULL)
                    .put("freezeAuthority", if (t.canFreeze) "live" else JSONObject.NULL)
                    .put("tokenProgram", if (t.token2022) "token2022" else SolanaTx.TOKEN_PROGRAM)
                    .put("holderCount", t.holders)
                    .put(
                        "audit",
                        JSONObject()
                            .put("topHoldersPercentage", t.topHoldersPct ?: JSONObject.NULL)
                            .put("devBalancePercentage", t.devPct ?: JSONObject.NULL)
                            .put("devMints", t.devMints),
                    ),
            )
        }
        return arr.toString()
    }

    private fun getArray(url: String): JSONArray? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 12000; setRequestProperty("Accept", "application/json")
        }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect()
        if (code !in 200..299) { Log.w(TAG, "GET $url -> $code"); null } else body?.let { JSONArray(it) }
    } catch (e: Exception) { Log.w(TAG, "GET failed: ${e.message}"); null }
}
