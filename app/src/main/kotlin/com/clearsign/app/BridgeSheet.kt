@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.clearsign.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clearsign.core.PayRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The bridge: SOL or USDC from here to another chain, through RocketX.
 *
 * Pick the chain, paste the address there, type the amount; the quotes come
 * in and the best deposit route is chosen; "Continue" opens the order and
 * hands the deposit address to the ordinary Send, so the receipt and the
 * fingerprint are the same as for any payment. The line under the button
 * says what this is and what it is not.
 */
@Composable
internal fun BridgeSheet(
    owner: String,
    onSend: (PayRequest, String?, RocketX.Deal) -> Unit,
    onHistory: () -> Unit,
    /** Aperto dal Manda: arriva gia' in modo privato, con indirizzo e cifra dentro. */
    startPrivate: Boolean = false,
    startDest: String = "",
    startAmount: String = "",
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var networks by remember { mutableStateOf<List<RocketX.Network>>(emptyList()) }
    // Solana as a landing: read once on IO. [RocketX.home] the first time is a
    // network call, and it used to be made in composition, on the main thread.
    var home by remember { mutableStateOf<RocketX.Network?>(null) }
    var usdc by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<RocketX.Network?>(null) }
    /**
     * Privato vuol dire Solana da tutte e due le parti.
     *
     * E' lo stesso meccanismo del ponte — preventivo, ordine, indirizzo di
     * deposito, scontrino, firma — con la stessa catena sulle due sponde.
     * RocketX le chiama Privacy Route e Monero Rails, e sono rotte `walletLess`
     * come tutte quelle che quest'app sa gia' pagare.
     */
    var private by remember { mutableStateOf(startPrivate) }
    var sameCoin by remember { mutableStateOf(true) }
    var toToken by remember { mutableStateOf<RocketX.Token?>(null) }
    var dest by remember { mutableStateOf(startDest) }
    var amount by remember { mutableStateOf(startAmount) }
    var quotes by remember { mutableStateOf<List<RocketX.Quote>>(emptyList()) }
    var quoting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    // Quale rotta, scelta da chi paga. Si azzera quando i preventivi cambiano.
    var picked by remember { mutableStateOf(0) }
    // Il numero che l'ordine ha davvero portato, quando e' peggio del preventivo.
    var worse by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    // L'ordine gia' aperto e messo in attesa di una risposta. Senza questo, il
    // secondo tocco su "accetta" apriva un secondo ordine a RocketX e pagava
    // quello, mentre i numeri sullo schermo erano del primo.
    var pending by remember { mutableStateOf<RocketX.Order?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // La cifra sotto la quale nessuno accetta. Viene dal preventivo rifiutato,
    // non da noi: dipende dal prezzo del momento e cambia da un'ora all'altra.
    var minAmount by remember { mutableStateOf<Double?>(null) }
    // Lo stesso numero, saputo prima di provare. Vedi [RocketX.PROBE].
    var floor by remember { mutableStateOf<Pair<Double, Double?>?>(null) }
    val fromMint = if (usdc) USDC_MINT else null
    val fromSym = if (usdc) "USDC" else "SOL"

    LaunchedEffect(Unit) {
        networks = withContext(Dispatchers.IO) { runCatching { RocketX.networks() }.getOrDefault(emptyList()) }
        home = withContext(Dispatchers.IO) { runCatching { RocketX.home() }.getOrNull() }
        // Si parte da Ethereum, non dalla prima che manda RocketX, che e'
        // Bitcoin: su un ponte da Solana la prima e' quella che si usa di piu'.
        target = RocketX.popular(networks).firstOrNull() ?: networks.firstOrNull()
    }

    // The coin on the other side: the same (USDC there) or the chain's own coin.
    LaunchedEffect(target, usdc, sameCoin) {
        val t = target ?: return@LaunchedEffect
        toToken = if (!usdc || !sameCoin) null else withContext(Dispatchers.IO) {
            runCatching { RocketX.tokens(t.chainId, "USDC", t.id).firstOrNull { it.symbol.equals("USDC", true) } }.getOrNull()
        }
    }

    /**
     * Il minimo chiesto all'apertura, non dopo il rifiuto.
     *
     * Solo sull'invio privato. Li' le rotte sono tutte private e condividono la
     * stessa soglia, quindi sotto quella cifra la pagina si svuota per intero e
     * non parte niente: e' una regola della schermata, e una regola si scrive
     * prima. Su un ponte normale invece non c'e' un minimo da scrivere —
     * misurato: Relay accetta un millesimo di SOL — e annunciarne uno vorrebbe
     * dire inventare un divieto che non esiste.
     */
    LaunchedEffect(private, usdc) {
        floor = null
        if (!private) return@LaunchedEffect
        val probe = withContext(Dispatchers.IO) {
            // [RocketX.home] la prima volta e' una chiamata, non una lettura:
            // va chiesta di qua e non sul thread che sta disegnando.
            val t = RocketX.home() ?: return@withContext null
            home = t
            runCatching { RocketX.quote(fromMint, "solana", fromMint, t.id, RocketX.PROBE) }.getOrNull()
        }
        floor = probe?.minAmount?.let { it to probe.minUsd }
    }

    val amt = amount.replace(',', '.').toDoubleOrNull()
    LaunchedEffect(target, fromMint, toToken, amt, sameCoin, private) {
        quotes = emptyList(); error = null; minAmount = null; picked = 0; worse = null; pending = null
        if (!private && target == null) return@LaunchedEffect
        if (amt == null || amt <= 0) return@LaunchedEffect
        if (!private && usdc && sameCoin && toToken == null) return@LaunchedEffect
        delay(500)
        quoting = true
        val answer = withContext(Dispatchers.IO) {
            // Anche qui la sponda si risolve dentro l'IO: [RocketX.home] la
            // prima volta va in rete, e andarci di qua bloccava il disegno.
            val t = (if (private) RocketX.home() else target) ?: return@withContext null
            // Privato: stessa moneta e stessa catena da tutte e due le parti.
            val to = if (private) fromMint else if (usdc && sameCoin) toToken?.contract else null
            runCatching { RocketX.quote(fromMint, "solana", to, t.id, amt) }.getOrNull()
        } ?: RocketX.Quotes(emptyList(), null, null)
        quotes = answer.list.filter { it.walletLess }
        minAmount = answer.minAmount?.takeIf { quotes.isEmpty() }
        // Il minimo appena detto da una risposta fresca vale piu' di quello
        // chiesto all'apertura: e' lo stesso numero, mezz'ora dopo.
        minAmount?.let { floor = it to answer.minUsd }
        quoting = false
        // "Nessuna rotta, prova un altro importo" mandava a indovinare un numero
        // che la risposta conteneva gia'. Quando il no e' per la cifra, si dice
        // qual e' la cifra.
        if (quotes.isEmpty()) {
            error = minAmount?.let { ctx.getString(R.string.bridge_min, minText(it), fromSym) }
                ?: ctx.getString(R.string.bridge_no_route)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetHeader(stringResource(R.string.bridge_title), stringResource(R.string.bridge_sub), HIcon.SWAP, onClose = onDismiss)
            if (!RocketX.enabled) { Banner(stringResource(R.string.bridge_off), Halo.amber, HIcon.WARNING); return@Column }

            Text(stringResource(R.string.bridge_from), style = HaloType.label, color = Halo.muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("SOL", !usdc, Halo.mint, Modifier.weight(1f)) { usdc = false }
                ModeChip("USDC", usdc, Halo.cyan, Modifier.weight(1f)) { usdc = true }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(stringResource(R.string.bridge_mode_chain), !private, Halo.mint, Modifier.weight(1f)) { private = false }
                ModeChip(stringResource(R.string.bridge_mode_private), private, Halo.cyan, Modifier.weight(1f)) { private = true }
            }

            if (!private) {
            Text(stringResource(R.string.bridge_to), style = HaloType.label, color = Halo.muted)
            if (networks.isEmpty()) Text(stringResource(R.string.w_analyzing), style = HaloType.small, color = Halo.muted)
            // Otto catene davanti e una ricerca per il resto: [ChainPickerSheet]
            // racconta perche'. Qui c'erano tutte e duecentosette in fila.
            var pickChain by remember { mutableStateOf(false) }
            val popular = remember(networks) { RocketX.popular(networks) }
            // Quella scelta resta sempre in prima fila, anche quando non e' fra
            // le otto: una catena che sparisce dallo schermo dopo che l'hai
            // scelta e' il modo piu' rapido di non sapere piu' dove stai
            // mandando i soldi.
            val chips = remember(popular, target) { (popular + listOfNotNull(target)).distinctBy { it.id } }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.forEach { n ->
                    val on = n.id == target?.id
                    Text(
                        chainLabel(n), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                        color = if (on) Halo.ink else Halo.muted,
                        modifier = Modifier.clip(rs(999)).background(if (on) Halo.mint.copy(alpha = 0.16f) else Halo.cardSoft)
                            .border(1.dp, if (on) Halo.mint else Halo.stroke, rs(999)).clickable { target = n }.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
                if (networks.size > chips.size) {
                    Text(
                        stringResource(R.string.chain_pick_more), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Halo.cyan,
                        modifier = Modifier.clip(rs(999)).background(Halo.cyan.copy(alpha = 0.10f))
                            .border(1.dp, Halo.cyan.copy(alpha = 0.45f), rs(999))
                            .clickable { pickChain = true }.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
            if (pickChain) {
                ChainPickerSheet(networks, target, onPick = { target = it }) { pickChain = false }
            }
            target?.let { t ->
                if (usdc) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeChip(stringResource(R.string.bridge_same_coin), sameCoin, Halo.cyan, Modifier.weight(1f)) { sameCoin = true }
                        ModeChip(t.native, !sameCoin, Halo.mint, Modifier.weight(1f)) { sameCoin = false }
                    }
                } else {
                    Text(stringResource(R.string.bridge_native_note, t.native), style = HaloType.small, color = Halo.muted)
                }
            }

            } else {
                Text(stringResource(R.string.bridge_private_what), style = HaloType.small, color = Halo.muted, lineHeight = 17.sp)
            }

            OutlinedTextField(
                value = dest, onValueChange = { dest = it.trim() }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = {
                    Text(
                        stringResource(R.string.bridge_dest_hint, if (private) "Solana" else target?.name ?: ""),
                        fontFamily = Mono, fontSize = 12.sp, color = Halo.muted,
                    )
                },
                textStyle = TextStyle(fontFamily = Mono, fontSize = 12.5.sp, color = Halo.ink), colors = pickerField(), shape = rs(12),
            )
            OutlinedTextField(
                value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' || c == ',' } }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("0", fontFamily = Mono, fontSize = 16.sp, color = Halo.muted) },
                suffix = { Text(fromSym, fontFamily = Mono, fontSize = 12.sp, color = Halo.muted) },
                textStyle = TextStyle(fontFamily = Mono, fontSize = 18.sp, color = Halo.ink),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                colors = pickerField(), shape = rs(12),
            )

            // Quanto ci vuole come minimo, detto prima di scrivere la cifra.
            // In dollari accanto, perche' quello e' il numero che sta fermo: la
            // cifra in SOL cambia a ogni movimento del prezzo, e senza i
            // dollari accanto sembra che l'app non sappia decidersi.
            floor?.let { (m, usd) ->
                Text(
                    if (usd == null) stringResource(R.string.bridge_floor, minText(m), fromSym)
                    // [fmtPrice] e non [fmtFiat]: il minimo e' un cartello
                    // pubblico, non un tuo saldo, e non va nascosto in ospite.
                    else stringResource(R.string.bridge_floor_usd, minText(m), fromSym, fmtPrice(usd, "USD")),
                    style = HaloType.small, color = Halo.muted,
                )
            }

            // Il giudizio sull'indirizzo, mentre lo incolli e non dopo.
            // Dove arrivano i soldi: l'altra catena, oppure Solana stessa.
            val landing = if (private) home else target
            val fits = landing?.let { t -> dest.takeIf { it.isNotBlank() }?.let { RocketX.addressFits(t, it) } }
            if (fits == false) {
                Banner(stringResource(R.string.bridge_dest_wrong, landing?.name ?: ""), Halo.red, HIcon.BLOCK)
            } else if (fits == null && dest.length >= 20) {
                Text(stringResource(R.string.bridge_dest_unknown), style = HaloType.small, color = Halo.amber, lineHeight = 16.sp)
            }

            if (quoting) Text(stringResource(R.string.bridge_quoting), style = HaloType.small, color = Halo.muted)
            error?.let { Banner(it, Halo.amber, HIcon.WARNING) }
            // Il minimo non e' solo una notizia, e' una cifra da mettere nel
            // campo: ricopiarla a mano da un avviso e' lavoro che il telefono
            // sa fare da solo, e a mano si sbaglia una cifra e si riprova.
            minAmount?.let { m ->
                Row {
                    SmallChip(stringResource(R.string.bridge_use_min, minText(m), fromSym), HIcon.PEN, tint = Halo.mint) {
                        amount = minText(m)
                        Haptics.tick(ctx)
                    }
                }
            }
            // Tre rotte, e quella scelta e' quella che parte.
            //
            // Prima se ne mostravano tre e il bottone prendeva sempre la prima:
            // l'elenco era decorativo, e su una pagina dove ogni riga porta un
            // numero diverso di soldi in arrivo, tre scelte finte sono peggio di
            // una sola vera.
            quotes.take(3).forEachIndexed { i, q ->
                val best = i == picked
                Column(
                    Modifier.fillMaxWidth().clip(rs(14)).background(if (best) Halo.mint.copy(alpha = 0.08f) else Halo.cardSoft)
                        .border(1.dp, if (best) Halo.mint.copy(alpha = 0.4f) else Halo.stroke, rs(14))
                        .clickable { picked = i; worse = null; pending = null }.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(q.exchange + " · " + q.keyword.lowercase().replaceFirstChar { it.uppercase() }, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Halo.ink, modifier = Modifier.weight(1f))
                        Text(String.format(Locale.ROOT, "%.6f", q.toAmount).trimEnd('0').trimEnd('.') + " " + (if (private || (usdc && sameCoin)) fromSym else landing?.native ?: ""), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (best) Halo.mint else Halo.ink)
                    }
                    // Quanto ti costa la strada, per intero e in soldi.
                    //
                    // La riga sotto diceva solo le commissioni dichiarate, e
                    // quelle sono meno della meta' del conto: su un SOL valgono
                    // 0,86 $ mentre fra partenza e arrivo ne mancano 1,10. Il
                    // resto e' il cambio dentro la rotta, che non sta in nessun
                    // campo e si vede solo sottraendo. Questa riga e' quella
                    // sottrazione, ed e' la cifra che paghi davvero.
                    //
                    // Si puo' fare solo dove la moneta e' la stessa sulle due
                    // sponde: l'invio privato e USDC verso USDC. Su un ponte
                    // vero partono SOL e arriva ETH, e sottrarre due monete
                    // diverse non vuol dire niente.
                    val sameUnit = private || (usdc && sameCoin)
                    val cost = if (sameUnit && amt != null && amt > 0 && q.toAmount > 0) amt - q.toAmount else null
                    if (cost != null && cost > 0) {
                        val pctCost = cost / amt!! * 100.0
                        val costUsd = q.usdPerUnit?.let { cost * it }
                        Text(
                            stringResource(
                                R.string.bridge_route_cost2,
                                coinText(cost), fromSym,
                                costUsd?.let { fmtPrice(it, "USD") } ?: "—",
                                String.format(Locale.ROOT, "%.1f", pctCost),
                            ),
                            fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                            color = if (pctCost > 5) Halo.red else if (pctCost > 2) Halo.amber else Halo.mint,
                        )
                    }
                    // Le commissioni dichiarate: sotto il totale sono un
                    // dettaglio, da sole sono tutto quello che si sa.
                    Text(
                        stringResource(
                            if (cost != null && cost > 0) R.string.bridge_quote_of_which else R.string.bridge_quote_line,
                            String.format(Locale.ROOT, "%.2f", q.feeUsd + q.gasUsd), q.minutes?.toString() ?: "?",
                        ),
                        style = HaloType.small, color = Halo.muted,
                    )
                }
            }

            worse?.let { (quoted, real) ->
                val drop = if (quoted > 0) (quoted - real) / quoted * 100.0 else 0.0
                Banner(
                    stringResource(
                        R.string.bridge_worse,
                        String.format(Locale.ROOT, "%.6f", quoted).trimEnd('0').trimEnd('.'),
                        String.format(Locale.ROOT, "%.6f", real).trimEnd('0').trimEnd('.'),
                        String.format(Locale.ROOT, "%.1f", drop),
                    ),
                    Halo.amber, HIcon.WARNING,
                )
            }
            busy?.let { Working(it) }
            val ready = quotes.isNotEmpty() && dest.length >= 20 && amt != null && amt > 0 && busy == null && fits != false
            PrimaryButton(stringResource(R.string.bridge_go), danger = false, enabled = ready, icon = HIcon.SWAP) {
                val q = quotes.getOrNull(picked) ?: quotes.first()
                val t = (if (private) home else target) ?: return@PrimaryButton
                busy = ctx.getString(R.string.bridge_opening)
                scope.launch {
                    // Se un ordine e' gia' aperto e in attesa del tuo si', e' quello
                    // che si paga: non se ne apre un secondo.
                    val order = pending ?: withContext(Dispatchers.IO) { runCatching { RocketX.swap(q.fromId, q.toId, owner, dest, amt!!) }.getOrNull() }
                    busy = null
                    val deposit = order?.depositAddress
                    when {
                        order == null || deposit == null -> error = ctx.getString(R.string.bridge_open_failed)
                        // Il preventivo diceva che questa rotta pretende un memo, e
                        // l'ordine non ne ha portato uno. Un deposito senza memo su
                        // una catena che lo pretende arriva e non viene accreditato
                        // a nessuno: e' il preventivo stesso a dirlo, e quel campo
                        // non veniva guardato da nessuna parte.
                        q.memoRequired && order.memo.isNullOrBlank() ->
                            error = ctx.getString(R.string.bridge_memo_missing)
                        // Il preventivo non e' l'ordine.
                        //
                        // Fra il numero che hai guardato e l'ordine aperto passa
                        // del tempo e una rotta puo' muoversi. Si firmava sulla
                        // fiducia di una cifra vista prima, e l'app ha gia'
                        // questo schema fatto bene sulle vendite: se il reale e'
                        // peggio oltre una soglia, si dicono i due numeri e si
                        // aspetta una risposta. Sotto il due per cento non vale
                        // la pena fermare nessuno.
                        order.toAmount > 0 && q.toAmount > 0 && order.toAmount < q.toAmount * 0.98 && worse == null -> {
                            worse = q.toAmount to order.toAmount
                            pending = order
                        }
                        else -> {
                            // The deposit is a payment like any other: Send, receipt, print. A memo, when the route wants one, rides in the transaction.
                            val toSym = if (private || (usdc && sameCoin)) fromSym else t.native
                            // Quanto arriva lo dice l'ordine, non il preventivo: e'
                            // l'ordine il patto. Il preventivo serve solo se
                            // l'ordine non l'ha detto.
                            val lands = order.toAmount.takeIf { it > 0 } ?: q.toAmount
                            RocketX.remember(
                                ctx,
                                RocketX.Bridge(
                                    order.requestId, "", fromSym, toSym, t.name, System.currentTimeMillis(), order.exchange, deposit,
                                    toAddress = dest, toAmount = lands, explorer = t.explorer,
                                ),
                            )
                            val deal = RocketX.Deal(
                                requestId = order.requestId,
                                fromText = fmtUi(amt!!) + " " + fromSym,
                                toAmount = lands, toSymbol = toSym, network = t.name, toAddress = dest,
                                exchange = order.exchange.ifBlank { q.exchange }, minutes = q.minutes, explorer = t.explorer,
                            )
                            // La destinazione finale finisce sullo scontrino.
                            //
                            // Quello che si firma e' un pagamento a RocketX, quindi
                            // sullo scontrino compariva l'indirizzo del deposito e
                            // mai quello dove i soldi vanno a finire: l'unica cosa
                            // che conta davvero non si vedeva al momento della firma.
                            pending = null
                            val tail = if (dest.length > 10) dest.take(6) + "…" + dest.takeLast(6) else dest
                            onSend(
                                PayRequest(
                                    deposit, amt, fromMint,
                                    ctx.getString(R.string.bridge_memo_line, t.name, order.exchange) + " · " + tail,
                                    "RocketX",
                                ),
                                order.memo,
                                deal,
                            )
                        }
                    }
                }
            }
            Text(
                stringResource(if (private) R.string.bridge_truth_private else R.string.bridge_truth),
                style = HaloType.small, color = Halo.muted, lineHeight = 15.sp,
            )

            // I ponti gia' aperti stanno in una pagina loro.
            //
            // Qui sotto ce n'erano tre, con un tasto che chiedeva lo stato e
            // scriveva una parola. Non bastava: un ponte che si pianta si
            // guarda sull'esploratore della catena di arrivo o si porta a
            // RocketX col numero d'ordine in mano, e nessuna di queste due cose
            // stava da nessuna parte.
            val past = remember { RocketX.bridges(ctx) }
            if (past.isNotEmpty()) {
                GhostButton(stringResource(R.string.bridge_past_all, past.size), Modifier.fillMaxWidth(), HIcon.HISTORY, tint = Halo.cyan) { onHistory() }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * Il minimo, scritto per eccesso.
 *
 * Arrotondato per difetto rimette la cifra sotto il minimo, e la rotta dice di
 * no una seconda volta con lo stesso avviso: l'unico arrotondamento che vale
 * qui e' quello che sta dalla parte giusta della soglia.
 */
internal fun minText(v: Double): String {
    val up = kotlin.math.ceil(v * 10_000.0) / 10_000.0
    return String.format(Locale.getDefault(), "%.4f", up).trimEnd('0').trimEnd { !it.isDigit() }
}

/** Una cifra in moneta, senza zeri in coda. */
internal fun coinText(v: Double): String =
    String.format(Locale.ROOT, "%.6f", v).trimEnd('0').trimEnd('.')

/** L'unico USDC vero su Solana. Scritto una volta, letto da chi manda e da chi fa il ponte. */
internal const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
