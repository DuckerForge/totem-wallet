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

    data class Network(
        val id: String, val name: String, val chainId: String, val native: String,
        /** Dove si va a vedere una cosa su quella catena. Lo dice RocketX, catena per catena. */
        val explorer: String = "",
        /** Il nome corto e stabile che manda RocketX: ETHEREUM, BASE, AVAXC. Vedi [POPULAR]. */
        val short: String = "",
    )

    /**
     * Le otto che si usano, in quest'ordine. Tutte le altre stanno dietro la ricerca.
     *
     * L'ordine di RocketX e' il suo, e mette terza una catena che nessuno qui
     * userebbe mai. Duecento pastiglie tutte insieme non sono una scelta, sono
     * un muro: si scorre col pollice e si seleziona quello che capita. Ogni
     * ponte serio (Relay, Jumper, Li.Fi) fa la stessa cosa, e la fa per questo:
     * una manciata di catene vere davanti, e una ricerca per il resto.
     *
     * Si aggancia allo `shorthand`, non all'id: e' il nome corto e leggibile che
     * manda RocketX. E soprattutto **se uno di questi sparisce non sparisce la
     * catena**: esce solo dalla prima fila e resta trovabile cercandola. E' la
     * differenza con la lista di prima, dove un id morto cancellava la catena
     * dallo schermo senza dire niente.
     */
    val POPULAR = listOf("ETHEREUM", "BASE", "ARBITRUM", "BNB", "POLYGON", "OPTIMISM", "AVAXC", "BITCOIN")

    /** Le prime, nell'ordine di [POPULAR]: quelle che ci sono davvero, e basta. */
    fun popular(all: List<Network>): List<Network> {
        val byShort = all.associateBy { it.short.uppercase() }
        return POPULAR.mapNotNull { byShort[it] }
    }

    /**
     * Il patto, una volta fatto.
     *
     * Quando `/swap` risponde, i numeri non sono piu' negoziabili: quell'ordine
     * aspetta **quella** cifra a **quell'indirizzo** di deposito, e in cambio
     * manda **quella** cifra all'indirizzo dall'altra parte. Da li' in poi lo
     * schermo non deve piu' chiedere niente, deve solo dire cosa e' stato
     * pattuito e farlo firmare.
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

    /** Dove si guarda un ordine quando qualcosa non torna. */
    const val ORDERS_URL = "https://app.rocketx.exchange/transaction-history"

    /**
     * Il link a un indirizzo sull'esploratore della sua catena.
     *
     * Vale anche per chi tiene tutto dietro il cancelletto, tipo Tronscan, che
     * lo porta gia' nel suo url di base: "https://tronscan.org/#/" diventa
     * "https://tronscan.org/#/address/T…" senza un caso a parte.
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
         * Commissione piu' gas, nella moneta che mandi. Dichiarate da loro.
         *
         * Non sono tutto quello che paghi: su un SOL, queste due valgono 0,0079
         * e quello che arriva e' 0,0102 sotto. La differenza e' il cambio di
         * andata e ritorno dentro la rotta, che non compare in nessun campo e
         * si vede solo sottraendo. Per questo lo schermo mostra la sottrazione
         * e mette queste due sotto, come dettaglio.
         */
        val feeCoin: Double?,
        /** Il prezzo della moneta, implicito nella stessa risposta: la fee in dollari diviso la fee in moneta. */
        val usdPerUnit: Double?,
    )
    data class Order(val requestId: String, val txId: Long, val depositAddress: String?, val memo: String?, val toAmount: Double, val exchange: String)

    /**
     * I preventivi, e il motivo di quelli che mancano.
     *
     * Una rotta rifiutata torna dentro `quotes` come tutte le altre, ma senza
     * `toAmount` e senza `isTxnAllowed`, con la ragione scritta in `err`:
     * "Min. Amount: 0.462745 SOL". Si buttava via insieme alla rotta, e sullo
     * schermo restava "nessuna rotta, prova un altro importo" — vero e inutile,
     * perche' la cifra giusta da provare era gia' nella risposta.
     *
     * Conta soprattutto sull'invio privato: li' le rotte sono tutte private e
     * hanno tutte lo stesso minimo, quindi sotto quella cifra la pagina si
     * svuota per intero. Su un ponte normale il minimo tocca una rotta sola e
     * le altre rispondono lo stesso, ed e' giusto che non se ne parli.
     *
     * [minAmount] e' il piu' basso fra i minimi rifiutati: e' quello che
     * sblocca la prima rotta, non quello che le sblocca tutte. Vale solo
     * quando non e' rimasto niente di usabile: se una rotta qualsiasi accetta,
     * un minimo non c'e', e dirlo sarebbe falso.
     *
     * [minUsd] e' lo stesso numero in dollari, ed e' la parte che sta ferma.
     * Misurato il 20 settembre 2026: le rotte private chiedono 50 $ tondi, in
     * SOL come in USDC, riconvertiti al prezzo del momento. Per questo la cifra
     * in SOL balla di continuo (0,462217, poi 0,462745, poi 0,462002) e per
     * questo non e' scritta da nessuna parte qui dentro: fra un'ora e' un'altra.
     */
    data class Quotes(val list: List<Quote>, val minAmount: Double?, val minUsd: Double?)

    /**
     * Una cifra volutamente ridicola, per farsi dire di no e leggere il minimo.
     *
     * E' l'unico modo di sapere il minimo **prima** che qualcuno provi a
     * mandare: il numero non sta in nessun elenco, lo dice solo un preventivo
     * rifiutato. Un millesimo di SOL e' sotto la soglia di chiunque.
     */
    const val PROBE = 0.001

    /** "Min. Amount: 0.462745 SOL" -> 0.462745. Il simbolo lo sappiamo gia' noi. */
    private val MIN_NUM = Regex("([0-9]+(?:\\.[0-9]+)?)")

    /**
     * I numeri della rotta privata, per chi non ha ancora aperto il ponte.
     *
     * Il cartellino sul Manda diceva "costa l'1 o 2%", scritto a mano molto
     * tempo fa, e non diceva che sotto i cinquanta dollari non parte niente.
     * Due cose inventate al posto di due numeri che l'API regala: quanto costa
     * davvero questa cifra su questa rotta, e qual e' il minimo adesso.
     *
     * Una chiamata sola quando la cifra va bene. Due solo quando viene
     * rifiutata, perche' allora la seconda serve a sapere di quanto.
     *
     * Blocking: chiamare su IO. [fromToken] null e' SOL, altrimenti il mint.
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
     * Il minimo di ieri, per non lasciare la riga vuota mentre arriva quello di oggi.
     *
     * Saperlo costa due chiamate in fila, la lista delle catene e il preventivo
     * rifiutato, e in quei tre secondi il cartellino non diceva niente: uno lo
     * guarda, non ci trova un numero, e va avanti. Il pavimento in dollari pero'
     * non si muove — cinquanta, misurati per dieci minuti di fila — quindi
     * l'ultimo visto e' quasi sempre ancora quello giusto. Si scrive subito e si
     * corregge da solo un istante dopo.
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
        // Senza passare da [home].
        //
        // Serviva a leggere un id che vale "solana" ed e' gia' scritto a mano
        // come catena di partenza in ogni chiamata di questo file. In cambio
        // costava il caricamento delle catene: centosettanta kilobyte e
        // duecento voci da ricucire sul telefono, prima di poter chiedere la
        // cosa sola che serve. Il cartellino sul Manda restava muto per tutto
        // quel tempo, e una riga che arriva dopo che hai smesso di guardarla
        // non e' arrivata.
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
        // Prima la moneta nativa, poi il chainId numerico.
        //
        // L'ordine conta. Se RocketX desse un chainId numerico anche a Bitcoin o
        // a Tron, e non ho potuto verificarlo dal vivo, guardare prima il numero
        // avrebbe applicato la regola EVM a un indirizzo bitcoin e rifiutato
        // ogni indirizzo valido: la falla opposta a quella che si vuole chiudere.
        // La moneta nativa non lascia dubbi.
        when (network.native.uppercase()) {
            "BTC" -> return Regex("^(bc1[0-9ac-hj-np-z]{11,71}|[13][1-9A-HJ-NP-Za-km-z]{25,34})$").matches(a)
            "TRX" -> return Regex("^T[1-9A-HJ-NP-Za-km-z]{33}$").matches(a)
            "SUI" -> return Regex("^0x[0-9a-fA-F]{64}$").matches(a)
            "TON" -> return Regex("^([A-Za-z0-9_-]{48}|-?\\d+:[0-9a-fA-F]{64})$").matches(a)
            "SOL" -> return Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$").matches(a)
        }
        // Le catene EVM hanno tutte lo stesso formato di indirizzo, e RocketX le
        // identifica con un chainId **esadecimale**: "0x1", "0xA4B1", "0x38".
        // Verificato dal vivo il 17/09. La prima versione di questa regola
        // cercava un numero decimale, non lo trovava mai, e il controllo sulle
        // sei catene che contano di piu' non scattava: ogni indirizzo passava come
        // "formato sconosciuto". Un controllo che non scatta mai e' peggio di
        // nessun controllo, perche' sembra esserci.
        val cid = network.chainId.trim()
        val evm = (cid.startsWith("0x", ignoreCase = true) && cid.drop(2).toLongOrNull(16) != null) || cid.toLongOrNull() != null
        if (evm) return Regex("^0x[0-9a-fA-F]{40}$").matches(a)
        return null
    }

    @Volatile private var networks: List<Network> = emptyList()
    @Volatile private var homeNet: Network? = null

    /**
     * Le catene, nell'ordine che dice RocketX.
     *
     * C'era una lista di undici id scritti a mano qui dentro, e tre non
     * esistevano piu' (`avalanche` adesso e' `avaxc-mainnet`, `sui` e'
     * `Sui Mainnet`, `ton` e' `TON`). Un id che non risponde non da' errore:
     * `byId[it]` torna null e quella catena semplicemente **non appare**. Di
     * undici ne restavano otto, e nessuno aveva modo di accorgersene guardando
     * lo schermo. Una lista scritta a mano di roba che vive su un server altrui
     * marcisce da sola e in silenzio.
     *
     * RocketX ne manda duecento e le manda **gia' ordinate** (`sort_order`:
     * Bitcoin, Ethereum, Solana, Sui, Base, TON, BNB, Arbitrum…), dicendo quali
     * sono accese. Quindi niente lista: si prendono tutte quelle accese, tolta
     * Solana che e' la sponda da cui si parte, nel loro ordine.
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
        // Solana esce dall'elenco delle destinazioni — e' la sponda da cui si
        // parte — ma si tiene da parte: l'invio privato ha Solana da tutte e
        // due le parti, e gli serve il suo esploratore e il suo formato di
        // indirizzo come a qualsiasi altra catena.
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
        // Il minimo di chi ha detto di no, col prezzo che ha usato per calcolarlo,
        // prima che le rotte rifiutate spariscano dall'elenco.
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
                // Un preventivo accettato non porta il prezzo della moneta, ma
                // porta la stessa commissione scritta due volte, in moneta e in
                // dollari: il rapporto fra le due e' il prezzo, e arriva senza
                // chiedere niente a nessuno.
                usdPerUnit = x.optDouble("platformFeeInSourceToken").takeIf { !it.isNaN() && it > 0 }
                    ?.let { pf -> x.optDouble("platformFeeUsd").takeIf { !it.isNaN() && it > 0 }?.div(pf) },
            )
        }.filter { it.allowed && it.toAmount > 0 }.sortedByDescending { it.toAmount }
        // Un minimo si dichiara solo se ha fermato tutto: con una rotta viva
        // dietro, la cifra e' il capriccio di un exchange e non una soglia.
        if (list.isNotEmpty() || refused == null) return Quotes(list, null, null)
        return Quotes(list, refused.first, refused.second?.times(refused.first))
    }

    /** Open the order. What comes back for a deposit route is the address to pay. */
    /**
     * Apre l'ordine.
     *
     * L'indirizzo di rimborso si manda **esplicito**. Prima non si mandava
     * affatto e si sperava che RocketX usasse `userAddress`: su una rotta
     * qualsiasi e' una scommessa piccola, ma le rotte private dichiarano
     * `isRefundAddressRequired: true`, e li' la scommessa e' su dove tornano i
     * soldi quando lo scambio non riesce. Torna dove sono partiti.
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
     * Come e' finita, e **dove andare a vedere**.
     *
     * Di questa risposta si leggeva una parola sola, `status`, e "success" da
     * solo non e' una prova di niente: dice che RocketX e' contento, non che i
     * soldi sono arrivati. Dentro c'e' molto di piu', ed e' tutto gia' pronto:
     * `destinationTransactionUrl` e' la transazione **sull'altra catena**, cioe'
     * l'unica pagina al mondo che dimostra l'arrivo, e `actualAmount` e' quanto
     * e' arrivato davvero, che non e' quello che diceva il preventivo.
     *
     * I due link li costruisce RocketX, non noi: un elenco di esploratori
     * scritto a mano qui dentro invecchierebbe come e' invecchiato quello delle
     * catene. Misurato il 18/09/2026 su un ponte vero, SOL verso ETH su Base.
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
        /** Finita, in bene o in male: non c'e' piu' niente da aspettare. */
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
     * Un ponte che questo telefono ha aperto.
     *
     * Tiene anche **dove** i soldi dovevano arrivare e **quanti**, che prima non
     * si salvavano: senza quelli la cronologia sapeva dire solo "SOL verso Base"
     * e non c'era modo, dopo, di andare a guardare se erano arrivati davvero.
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
