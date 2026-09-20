@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.clearsign.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode

/*
 * Send SOL or a token from the wallet itself — through the very same receipt a
 * dApp request gets (simulation, split map, risks, look-alike detection), then
 * hold-to-sign. Recipient from the clipboard, the address book, or a QR scan.
 */

/** What the user picked to send. */
private sealed interface Asset {
    val symbol: String
    val decimals: Int
    val available: Long
    /** Needed to show the coin's own logo, not just its name. */
    val mint: String
    val name: String?
    data class Sol(override val available: Long) : Asset {
        override val symbol = "SOL"
        override val decimals = 9
        override val mint = com.clearsign.core.NATIVE_SOL_MINT
        override val name = "Solana"
    }
    data class Token(val acct: SolanaRpc.TokenAccountInfo) : Asset {
        override val symbol = TokenSymbols.symbol(acct.mint)
        override val decimals = acct.decimals
        override val available = acct.amount
        override val mint = acct.mint
        override val name = TokenSymbols.name(acct.mint)
    }
}

/** Il saldo in unita' intere, per contarlo e per moltiplicarlo per un prezzo. */
private fun Asset.units(): Double = BigDecimal.valueOf(available).movePointLeft(decimals).toDouble()

private sealed interface SendState {
    data object Form : SendState
    data object Analyzing : SendState
    data class Review(val analyzed: ReceiptEngine.Analyzed, val ixs: List<WalletTx.Instruction>, val dest: String, val amountText: String) : SendState
    data object Signing : SendState
    data class Done(val signature: String) : SendState
    data class Error(val message: String) : SendState
}

@Composable
internal fun SendSheet(
    signer: SeedVaultSigner,
    owner: String,
    /** Filled in by a tap or a payment link: the form opens ready to review. */
    prefillTo: String? = null,
    prefillAmount: String? = null,
    /** The coin the request asks for, when it asks for one. Null means SOL. */
    prefillMint: String? = null,
    /** A memo the recipient needs (an exchange deposit, a bridge). Written into the transaction, shown on the receipt. */
    prefillMemo: String? = null,
    /** L'ordine gia' aperto dall'altra parte, quando si arriva dal ponte. Vedi [BridgeDealCard]. */
    deal: RocketX.Deal? = null,
    onGift: () -> Unit = {},
    /**
     * La porta dell'invio privato, con quello che hai gia' scritto.
     *
     * La macchina sta nel ponte — preventivo, ordine, indirizzo di deposito —
     * e li' resta: due copie dello stesso flusso divergono alla prima
     * correzione. Ma uno che vuole mandare soldi apre **questa** pagina, non il
     * ponte, perche' "privato" e' un aggettivo su un invio e non un tipo di
     * ponte. Quindi la porta sta qui e porta di la' con l'indirizzo e la cifra
     * gia' dentro.
     */
    onPrivate: (String, String) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var state by remember { mutableStateOf<SendState>(SendState.Form) }

    var to by remember { mutableStateOf(prefillTo.orEmpty()) }
    var amount by remember { mutableStateOf(prefillAmount.orEmpty()) }
    var assets by remember { mutableStateOf<List<Asset>>(emptyList()) }
    /**
     * Quanto vale una unita' di ogni moneta, nella valuta di chi guarda.
     *
     * Una chiamata sola quando la lista arriva. Serve ai tondini delle monete:
     * un saldo senza il suo controvalore non risponde alla domanda che uno si
     * fa li', che e' "quale di queste mando".
     */
    var unitFiat by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var asset by remember { mutableStateOf<Asset?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    val currency by Settings.currency
    var tapping by remember { mutableStateOf(false) }
    val contacts = remember { Contacts.allowlist(ctx) }

    /** A coin a request asked for, applied once the wallet's assets are known. */
    var wantedMint by remember { mutableStateOf(prefillMint) }

    // A tap can land while this screen is already open: `to` was only seeded once,
    // so without this the form sat there empty with the request already read.
    LaunchedEffect(prefillTo, prefillAmount, prefillMint) {
        prefillTo?.takeIf { it.isNotBlank() }?.let { to = it; tapping = false; Haptics.tick(ctx) }
        prefillAmount?.takeIf { it.isNotBlank() }?.let { amount = it }
        wantedMint = prefillMint
    }

    // The coin the request named, and not SOL by default. A request for ten
    // USDC used to open the form on SOL with "10" in the amount: the number was
    // right and the coin was wrong, which is the one mistake a payment form must
    // never make on the person's behalf. If the coin is not in this wallet, say
    // so and leave the amount empty rather than let it mean something else.
    LaunchedEffect(assets, wantedMint) {
        val m = wantedMint ?: return@LaunchedEffect
        if (assets.isEmpty()) return@LaunchedEffect
        val match = assets.firstOrNull { it.mint == m }
        if (match != null) asset = match
        else { amount = ""; formError = ctx.getString(R.string.send_wanted_missing, TokenSymbols.symbol(m)) }
        wantedMint = null
    }

    /**
     * Dal ponte si arriva a cose fatte: niente modulo, si va allo scontrino.
     *
     * Il ponte chiedeva catena, indirizzo e cifra, apriva l'ordine, e poi
     * passava la mano a questa pagina, che ricominciava da capo: di nuovo
     * quanto, di nuovo quale moneta, e con la possibilita' di cambiarle. Solo
     * che quei tre numeri l'ordine li ha gia' fissati con RocketX: quel
     * deposito aspetta **quella** cifra in **quella** moneta, e mandargliene
     * un'altra vuol dire un ordine che non torna, con i soldi gia' partiti.
     * Chiedere due volte la stessa cosa e' fastidioso; chiederla in un modo che
     * lascia cambiare quello che non si puo' piu' cambiare e' una trappola.
     *
     * Una volta sola: se lo scontrino blocca, si torna indietro e non si
     * rientra qui dentro in tondo.
     */
    var dealStarted by remember { mutableStateOf(false) }
    LaunchedEffect(deal, asset, wantedMint) {
        if (deal == null) return@LaunchedEffect
        val a = asset ?: return@LaunchedEffect
        if (dealStarted || wantedMint != null || state != SendState.Form) return@LaunchedEffect
        val raw = parseAmount(amount, a.decimals) ?: return@LaunchedEffect
        if (raw <= 0L) return@LaunchedEffect
        dealStarted = true
        state = SendState.Analyzing
        state = try {
            val (ixs, analyzed) = buildAndAnalyze(ctx, owner, to.trim(), a, raw, prefillMemo)
            SendState.Review(analyzed, ixs, to.trim(), fmtUnits(raw, a.decimals) + " " + a.symbol)
        } catch (e: Exception) { SendState.Error(e.message ?: ctx.getString(R.string.wa_no_blockhash)) }
    }

    LaunchedEffect(owner) {
        val rpc = SolanaRpc.urlFor(null)
        val (lam, toks) = withContext(Dispatchers.IO) {
            val l = runCatching { SolanaRpc.getBalance(rpc, owner) }.getOrNull() ?: 0L
            val t = runCatching { SolanaRpc.tokenAccountsOf(rpc, owner) }.getOrDefault(emptyList()).filter { it.amount > 0 && !it.isFrozen }
            l to t
        }
        // Jupiter's registry names and pictures the coins the metadata pass could
        // not, so the chips below carry a real logo instead of four letters.
        withContext(Dispatchers.IO) { runCatching { JupiterTokens.byMints(toks.map { it.mint }) } }
        assets = listOf(Asset.Sol(lam)) + toks.sortedByDescending { it.amount }.map { Asset.Token(it) }
        if (asset == null) asset = assets.first()
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.let { text ->
            val req = parseScanned(text)
            to = req.recipient
            // What the code asked for, not just where: the amount and the coin
            // used to be thrown away, and a merchant's QR became a bare address.
            req.amount?.let { amount = fmtUi(it) }
            wantedMint = req.mint ?: com.clearsign.core.NATIVE_SOL_MINT
        }
    }
    val destValid = Base58.decodePubkey(to.trim()) != null
    // Address poisoning: the same ends as somebody you know, a different middle.
    // Known is what this wallet has a reason to trust: contacts, its own
    // accounts, the budget, and everyone it has already paid.
    val known = remember(contacts) {
        buildSet {
            addAll(contacts.keys); add(owner)
            SessionWallet.current(ctx)?.pubkey?.let { add(it) }
            runCatching { Ledger.all(ctx) }.getOrDefault(emptyList()).filter { it.sent }.mapNotNull { it.primaryRecipient }.forEach { add(it) }
        }
    }
    val lookalike = remember(to, known) { com.clearsign.core.AddressPoison.lookalike(to, known) }
    var poisonAck by remember(to) { mutableStateOf(false) }

    LaunchedEffect(assets, currency) {
        if (assets.isEmpty()) return@LaunchedEffect
        unitFiat = withContext(Dispatchers.IO) {
            val usd = runCatching { Prices.usd(assets.map { it.mint }) }.getOrDefault(emptyMap())
            val rate = runCatching { Prices.usdTo(currency) }.getOrNull() ?: 1.0
            usd.mapValues { it.value * rate }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding()) {
            SheetHeader(
                stringResource(R.string.send_title),
                when (state) { is SendState.Review -> stringResource(R.string.send_review_sub); else -> stringResource(R.string.send_sub) },
                HIcon.SEND,
                onClose = onDismiss,
            )

            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (val s = state) {
                    SendState.Form, SendState.Analyzing -> {
                        // ---- recipient ------------------------------------------
                        FieldLabel(stringResource(R.string.send_to))
                        OutlinedTextField(
                            value = to, onValueChange = { to = it; formError = null },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text(stringResource(R.string.send_to_hint), fontFamily = Mono, fontSize = 13.sp, color = Halo.muted) },
                            textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = Halo.ink),
                            colors = fieldColors(if (to.isBlank()) Halo.stroke else if (destValid) Halo.mint else Halo.red),
                            shape = rs(14),
                        )
                        prefillMemo?.takeIf { it.isNotBlank() }?.let { m -> Text(stringResource(R.string.send_memo_line, m), style = HaloType.small, color = Halo.cyan) }
                        lookalike?.let { l ->
                            Column(
                                Modifier.fillMaxWidth().clip(rs(14)).background(Halo.red.copy(alpha = 0.10f)).border(1.dp, Halo.red.copy(alpha = 0.45f), rs(14)).padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    HaloIcon(HIcon.WARNING, Halo.red, 18.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.send_poison_title, contacts[l.of] ?: shorten(l.of)), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Halo.red)
                                }
                                Text(stringResource(R.string.send_poison_body), style = HaloType.small, color = Halo.ink, lineHeight = 16.sp)
                                Text(stringResource(R.string.send_poison_known) + " " + l.of, fontFamily = Mono, fontSize = 10.5.sp, color = Halo.mint)
                                Text(stringResource(R.string.send_poison_this) + " " + l.candidate, fontFamily = Mono, fontSize = 10.5.sp, color = Halo.red)
                                if (!poisonAck) GhostButton(stringResource(R.string.send_poison_ack), Modifier.fillMaxWidth(), HIcon.CHECK, tint = Halo.muted) { poisonAck = true }
                                else Text(stringResource(R.string.send_poison_acked), style = HaloType.small, color = Halo.amber)
                            }
                        }
                        // Four ways to fill the address in, all the same size, all one tap.
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallChip(stringResource(R.string.send_paste), HIcon.PASTE) { to = clipboardText(ctx)?.trim().orEmpty() }
                            SmallChip(stringResource(R.string.send_scan), HIcon.SCAN, tint = Halo.cyan) {
                                scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(true).setCaptureActivity(ScanPortraitActivity::class.java).setPrompt(""))
                            }
                            // The radio is already listening while the app is in front. The
                            // button does not switch it on: it gives the person a moment
                            // where they can see that it is, and somewhere to read how.
                            if (TapService.isSupported(ctx)) {
                                SmallChip(stringResource(R.string.send_tap), HIcon.NFC, tint = Halo.cyan) { tapping = !tapping }
                            }
                            // No address? Then the link is the address.
                            SmallChip(stringResource(R.string.gift_chip), HIcon.GIFT, tint = Halo.mint) { onGift() }
                        }
                        if (tapping) {
                            SheetBlock(stringResource(R.string.send_tap), stringResource(R.string.send_block_nfc_sub), HIcon.NFC) {
                                TapAnimation(active = true, height = 126.dp)
                                Text(
                                    stringResource(R.string.send_tap_listening),
                                    fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 16.sp,
                                )
                            }
                        }
                        if (contacts.isNotEmpty()) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                contacts.entries.take(12).forEach { (addr, label) ->
                                    Row(
                                        Modifier.clip(rs(999)).background(if (to.trim() == addr) Halo.mint.copy(alpha = 0.14f) else Halo.cardSoft)
                                            .border(1.dp, if (to.trim() == addr) Halo.mint else Halo.stroke, rs(999))
                                            .clickable { to = addr }.padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) { Avatar(addr, 20.dp); Spacer(Modifier.width(6.dp)); Text(label, fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = Halo.ink) }
                                }
                            }
                        }

                        // ---- asset ----------------------------------------------
                        FieldLabel(stringResource(R.string.send_asset))
                        // La fila delle monete, leggibile.
                        //
                        // Sotto ogni simbolo c'era il saldo grezzo, con i
                        // decimali di quella moneta: 1,0415 accanto a 1000
                        // accanto a 0,937878. Tre numeri senza unita' di misura
                        // in comune, e la domanda "quale mando" si fa in soldi,
                        // non in unita'. Ora i decimali sono gli stessi per
                        // tutte e sotto c'e' quanto vale.
                        //
                        // E la fila scorre: l'ultimo tondino veniva tagliato dal
                        // bordo senza che niente dicesse che ce n'erano altri.
                        // La sfumatura lo dice, e c'e' solo quando c'e' altro.
                        val assetScroll = rememberScrollState()
                        Box {
                            Row(Modifier.horizontalScroll(assetScroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                assets.forEach { a ->
                                    val active = a == asset
                                    Box(
                                        Modifier.clip(rs(12)).background(if (active) Halo.mint.copy(alpha = 0.14f) else Color.Transparent)
                                            .border(1.dp, if (active) Halo.mint else Halo.stroke, rs(12))
                                            .clickable { asset = a; amount = ""; Haptics.tick(ctx) }.padding(horizontal = 12.dp, vertical = 8.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TokenLogo(a.mint, a.symbol, TokenSymbols.image(a.mint), 24.dp)
                                            Spacer(Modifier.width(8.dp))
                                            Column {
                                                Text(a.symbol, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (active) Halo.mint else Halo.ink)
                                                Text(fmtUi(a.units()), fontFamily = Inter, fontSize = 10.5.sp, color = Halo.muted, style = Tabular)
                                                Text(
                                                    unitFiat[a.mint]?.let { fmtFiat(it * a.units(), currency) } ?: "—",
                                                    fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp,
                                                    color = if (active) Halo.mint else Halo.ink, style = Tabular,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (assetScroll.canScrollForward) {
                                Box(
                                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(28.dp)
                                        .background(Brush.horizontalGradient(listOf(Color.Transparent, Halo.ground2))),
                                )
                            }
                        }

                        // ---- amount ---------------------------------------------
                        FieldLabel(stringResource(R.string.send_amount))
                        OutlinedTextField(
                            value = amount, onValueChange = { v -> if (v.isEmpty() || v.matches(Regex("^\\d*[.,]?\\d*$"))) { amount = v; formError = null } },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text("0.00", fontFamily = Sora, fontSize = 24.sp, color = Halo.muted) },
                            textStyle = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Halo.ink, fontFeatureSettings = "tnum"),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(asset?.symbol ?: "", fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.muted)
                                    Spacer(Modifier.width(8.dp))
                                    SmallChip(stringResource(R.string.send_max), null) {
                                        asset?.let { a ->
                                            val raw = if (a is Asset.Sol) (a.available - SOL_RESERVE).coerceAtLeast(0L) else a.available
                                            amount = fmtUnits(raw, a.decimals)
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                }
                            },
                            colors = fieldColors(Halo.stroke), shape = rs(14),
                        )
                        asset?.let { a -> Text(stringResource(R.string.send_available, fmtUnits(a.available, a.decimals) + " " + a.symbol), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted, style = Tabular) }

                        // Privato: una riga sua, larga, sotto la cifra.
                        //
                        // Era il quinto tondino della fila qui sopra, che scorre
                        // in orizzontale: stava fuori dallo schermo e non lo
                        // vedeva nessuno. Ma il posto era sbagliato comunque.
                        // Incolla, Scansiona e Tocca **riempiono l'indirizzo**;
                        // privato non dice dove mandare, dice **come**, e si
                        // decide dopo aver scritto quanto.
                        if (RocketX.enabled && deal == null) {
                            // La strada privata parte da SOL o da USDC, e da
                            // nient'altro. Con una moneta diversa il cartellino
                            // resta e lo dice: e' qui che si scopre che esiste,
                            // e sparire sarebbe il modo piu' sicuro di non farlo
                            // sapere a nessuno. Toccandolo si passa a SOL, senza
                            // portarsi dietro una cifra contata in un'altra
                            // moneta, che di la' vorrebbe dire un'altra cosa.
                            val mint = asset?.mint
                            val switches = mint != null && mint != com.clearsign.core.NATIVE_SOL_MINT && mint != USDC_MINT
                            // Null e' SOL: e' cosi' che RocketX chiama la moneta nativa di una catena.
                            val privCoin = USDC_MINT.takeIf { mint == USDC_MINT }
                            val privSym = if (switches) "SOL" else asset?.symbol.orEmpty()
                            // Si parte dall'ultimo minimo visto, non dal vuoto.
                            var priv by remember(privSym) { mutableStateOf(RocketX.recallFloor(ctx, privSym)) }
                            LaunchedEffect(switches, privCoin, amount) {
                                // Aspettare mezzo secondo ha senso mentre uno
                                // scrive la cifra. All'apertura non c'e' niente
                                // da aspettare, e sono mezzo secondo di riga muta.
                                if (amount.isNotEmpty()) kotlinx.coroutines.delay(600)
                                // Con una moneta che questa strada non porta, la
                                // cifra scritta non c'entra: si chiede solo il
                                // minimo, che e' l'unica cosa vera da dire.
                                val a = if (switches) null else amount.replace(',', '.').toDoubleOrNull()
                                val fresh = withContext(Dispatchers.IO) {
                                    runCatching { RocketX.privately(privCoin, a) }.getOrNull()
                                }
                                if (fresh != null) priv = fresh
                                fresh?.minAmount?.let { RocketX.rememberFloor(ctx, privSym, it, fresh.minUsd) }
                            }
                            Row(
                                Modifier.fillMaxWidth().clip(rs(14)).background(Halo.cyan.copy(alpha = 0.08f))
                                    .border(1.dp, Halo.cyan.copy(alpha = 0.35f), rs(14))
                                    .clickable { onPrivate(to.trim(), if (switches) "" else amount) }
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                HaloIcon(HIcon.SWAP, Halo.cyan, 17.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.send_private), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.cyan)
                                    Text(
                                        stringResource(R.string.send_private_sub) +
                                            (if (switches) " " + stringResource(R.string.send_private_switch) else ""),
                                        style = HaloType.small, color = Halo.muted, lineHeight = 15.sp,
                                    )
                                    // Quanto costa questa cifra su questa strada,
                                    // adesso, oppure quanto ci vuole come minimo.
                                    // Finche' non si sa non si scrive niente: una
                                    // percentuale inventata sta sullo schermo con
                                    // la faccia di un dato.
                                    val p = priv
                                    val pct = p?.costPct ?: 0.0
                                    when {
                                        p?.costCoin != null -> Text(
                                            stringResource(
                                                R.string.bridge_route_cost2,
                                                coinText(p.costCoin), privSym,
                                                p.costUsd?.let { fmtPrice(it, "USD") } ?: "—",
                                                String.format(java.util.Locale.ROOT, "%.1f", pct),
                                            ),
                                            fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.5.sp,
                                            color = if (pct > 5) Halo.red else if (pct > 2) Halo.amber else Halo.mint,
                                        )
                                        // Il minimo si dice solo quando c'entra:
                                        // con una cifra che lo supera gia', e'
                                        // un numero vecchio che sta li' a fare
                                        // scena mentre arriva il costo vero.
                                        p?.minAmount != null && (switches || (amount.replace(',', '.').toDoubleOrNull() ?: 0.0) < p.minAmount) -> Text(
                                            p.minUsd?.let { u ->
                                                stringResource(R.string.bridge_floor_usd, minText(p.minAmount), privSym, fmtPrice(u, "USD"))
                                            } ?: stringResource(R.string.bridge_floor, minText(p.minAmount), privSym),
                                            fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, color = Halo.amber,
                                        )
                                    }
                                }
                            }
                        }
                        formError?.let { Banner(it, Halo.red, HIcon.WARNING) }
                        if (s is SendState.Analyzing) Working(stringResource(R.string.send_analyzing))
                    }
                    is SendState.Review -> {
                        // Un ponte e' uno scambio, solo che la meta' che torna
                        // indietro e' su un'altra catena e la simulazione non la
                        // vede. Passandola come `pair`, lo scontrino disegna le
                        // due gambe come per uno swap: prima si vedeva solo la
                        // meta' in uscita, cioe' uno che manda via dei soldi.
                        deal?.let { BridgeDealCard(it) }
                        SignReceiptBody(
                            s.analyzed.receipt, null,
                            pair = deal?.let { d ->
                                SwapPair(
                                    asset?.mint ?: com.clearsign.core.NATIVE_SOL_MINT, asset?.symbol ?: "SOL", amount,
                                    "", d.toSymbol, fmtUi(d.toAmount),
                                )
                            },
                        )
                    }
                    SendState.Signing -> Working(stringResource(R.string.theme_unlock_signing))
                    is SendState.Done -> {
                        Banner(stringResource(R.string.send_done), Halo.mint, HIcon.CHECK)
                        Text(s.signature, fontFamily = Mono, fontSize = 11.sp, color = Halo.muted, maxLines = 2)
                        // A bridge deposit: the order learns its chain signature, so its status can be asked.
                        LaunchedEffect(s.signature) { prefillTo?.let { RocketX.attachSignature(ctx, it, s.signature) } }
                        // The proof: the receipt this phone just signed, as a QR for the person paid.
                        var showProof by remember { mutableStateOf(false) }
                        val entry = remember(s.signature) { runCatching { Ledger.all(ctx).firstOrNull { it.signature == s.signature } }.getOrNull() }
                        if (entry?.attestationSig != null) {
                            GhostButton(stringResource(R.string.proof_show), Modifier.fillMaxWidth(), HIcon.SHIELD_LOCK, tint = Halo.mint) { showProof = true }
                            if (showProof) ProofSheet(entry) { showProof = false }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GhostButton(stringResource(R.string.copy), Modifier.weight(1f), HIcon.COPY) { copyText(ctx, s.signature) }
                            GhostButton("Solscan", Modifier.weight(1f), HIcon.EXTERNAL, tint = Halo.cyan) {
                                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(solscanTxUrl(s.signature, null)))) }
                            }
                        }
                    }
                    is SendState.Error -> Banner(s.message, Halo.red, HIcon.WARNING)
                }
                Spacer(Modifier.height(8.dp))
            }

            // ---- fixed action bar --------------------------------------------
            Column(Modifier.fillMaxWidth().background(Halo.ground2).padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding()) {
                when (val s = state) {
                    SendState.Form -> PrimaryButton(stringResource(R.string.send_continue), danger = false, enabled = destValid && amount.isNotBlank() && asset != null && (lookalike == null || poisonAck)) {
                        val a = asset ?: return@PrimaryButton
                        val raw = parseAmount(amount, a.decimals)
                        val dest = to.trim()
                        when {
                            raw == null || raw <= 0L -> formError = ctx.getString(R.string.send_invalid_amount)
                            dest == owner -> formError = ctx.getString(R.string.send_self)
                            raw > a.available -> formError = ctx.getString(R.string.send_insufficient, a.symbol)
                            else -> {
                                state = SendState.Analyzing
                                scope.launch {
                                    state = try {
                                        val (ixs, analyzed) = buildAndAnalyze(ctx, owner, dest, a, raw, prefillMemo)
                                        SendState.Review(analyzed, ixs, dest, fmtUnits(raw, a.decimals) + " " + a.symbol)
                                    } catch (e: Exception) { SendState.Error(e.message ?: ctx.getString(R.string.wa_no_blockhash)) }
                                }
                            }
                        }
                    }
                    is SendState.Review -> {
                        if (s.analyzed.receipt.blocksApproval) {
                            // The one block a person can lift themselves: sending most
                            // of a balance to an address this phone has never seen is
                            // the shape of a drainer, and the way to say "no, that is
                            // my new wallet" is to save it as a contact. Said here,
                            // with the chip that does it, instead of a red line that
                            // named a severity and left the person to guess.
                            val onlyDrain = s.analyzed.receipt.risks.filter { it.severity == com.clearsign.core.Severity.DANGER }
                                .all { it.flag == com.clearsign.core.RiskFlag.DRAINS_BALANCE }
                            if (onlyDrain) {
                                Banner(stringResource(R.string.send_blocked_drain), Halo.amber, HIcon.WARNING)
                                Spacer(Modifier.height(8.dp))
                                GhostButton(stringResource(R.string.send_save_contact), Modifier.fillMaxWidth(), HIcon.CONTACTS, tint = Halo.mint) {
                                    Contacts.saveContact(ctx, s.dest, shorten(s.dest))
                                    Haptics.tick(ctx)
                                    state = SendState.Form
                                }
                            } else {
                                Banner(stringResource(R.string.send_blocked), Halo.red, HIcon.BLOCK)
                            }
                            Spacer(Modifier.height(8.dp))
                            // Dal ponte non c'e' un modulo dietro a cui tornare:
                            // indietro vuol dire lasciar perdere.
                            GhostButton(stringResource(R.string.back)) { if (deal != null) onDismiss() else state = SendState.Form }
                        } else {
                            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.pay), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
                                    Text("−" + s.amountText, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.mint, style = Tabular)
                                }
                                Text(contacts[s.dest] ?: shorten(s.dest), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.ink)
                            }
                            HoldToConfirm(stringResource(R.string.send_hold)) {
                                state = SendState.Signing
                                scope.launch {
                                    val r = s.analyzed.receipt
                                    val log = WalletActions.LogInfo(
                                        kind = "send", outflows = r.outflows.map { "−" + fmtAmt(it) }, recipient = s.dest, receipt = r,
                                        recipientLabel = contacts[s.dest] ?: ctx.getString(R.string.wa_log_send, shorten(s.dest)),
                                    )
                                    state = when (val res = WalletActions.signAndSend(ctx, signer, owner, s.ixs, log)) {
                                        is WalletActions.Result.Sent -> SendState.Done(res.signature)
                                        is WalletActions.Result.Failed -> SendState.Error(res.message)
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            // Dal ponte non c'e' un modulo dietro a cui tornare:
                            // indietro vuol dire lasciar perdere.
                            GhostButton(stringResource(R.string.back)) { if (deal != null) onDismiss() else state = SendState.Form }
                        }
                    }
                    is SendState.Done -> PrimaryButton(stringResource(R.string.done), danger = false) { onDismiss() }
                    is SendState.Error -> GhostButton(stringResource(R.string.back)) { state = SendState.Form }
                    else -> {}
                }
            }
        }
    }
}

/** Keep a little SOL back for fees + rent-exempt minimum when sending "MAX". */
private const val SOL_RESERVE = 1_500_000L

private suspend fun buildAndAnalyze(ctx: Context, owner: String, dest: String, asset: Asset, raw: Long, memo: String? = null): Pair<List<WalletTx.Instruction>, ReceiptEngine.Analyzed> {
    val rpc = SolanaRpc.urlFor(null)
    val ownerKey = Base58.decode(owner); val destKey = Base58.decode(dest)
    val base: List<WalletTx.Instruction> = when (asset) {
        is Asset.Sol -> listOf(WalletTx.systemTransfer(ownerKey, destKey, raw))
        is Asset.Token -> withContext(Dispatchers.IO) {
            val mint = Base58.decode(asset.acct.mint)
            val program = WalletTx.tokenProgramFor(asset.acct.program)
            val ata = Pda.associatedTokenAddress(destKey, mint, program)
            val exists = SolanaRpc.getAccountInfoRaw(rpc, Base58.encode(ata)) != null
            buildList {
                if (!exists) add(WalletTx.createAtaIdempotent(ownerKey, ata, destKey, mint, program))
                add(WalletTx.tokenTransferChecked(Base58.decode(asset.acct.pubkey), mint, ata, ownerKey, raw, asset.decimals, program))
            }
        }
    }
    val ixs = if (memo.isNullOrBlank()) base else base + WalletTx.memo(memo.trim().take(120))
    val bh = withContext(Dispatchers.IO) { SolanaRpc.latestBlockhash(rpc) } ?: throw IllegalStateException(ctx.getString(R.string.wa_no_blockhash))
    val payload = WalletTx.build(ownerKey, Base58.decode(bh.hash), ixs)
    val analyzed = ReceiptEngine.analyze(ctx, BlocklistScanner(ctx), payload, owner, null, requireSim = true)
    return ixs to analyzed
}

private fun parseAmount(text: String, decimals: Int): Long? = runCatching {
    BigDecimal(text.trim().replace(',', '.')).movePointRight(decimals).setScale(0, RoundingMode.DOWN).longValueExact()
}.getOrNull()

/** Raw units → "1.5" (trailing zeros trimmed). */
internal fun fmtUnits(raw: Long, decimals: Int): String {
    val s = BigDecimal.valueOf(raw).movePointLeft(decimals).toPlainString()
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
}

/**
 * A scanned QR may be a bare address, a Solana Pay URI (`solana:<addr>?…`), or the
 * web link Apex hands out — the one a phone without a wallet can also open.
 */
private fun parseScanned(text: String): com.clearsign.core.PayRequest {
    val t = text.trim()
    com.clearsign.core.SolanaPay.parse(t)?.let { return it }
    val body = if (t.startsWith("solana:", ignoreCase = true)) t.substringAfter(':').substringBefore('?') else t
    return com.clearsign.core.PayRequest(body.trim(), null, null, null, null)
}

internal fun clipboardText(ctx: Context): String? =
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()

internal fun copyText(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(ctx.getString(R.string.app_name), text))
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
}

@Composable
internal fun SmallChip(
    label: String,
    icon: HIcon?,
    tint: Color = Halo.cyan,
    onLongClick: (() -> Unit)? = null,
    /**
     * Blink three times on arrival, in blue.
     *
     * For the one chip whose second gesture is invisible: you can hold it to
     * change it, and nothing on a flat rectangle says so. Three pulses and then
     * still, because an affordance that keeps flashing is an advert.
     */
    pulse: Boolean = false,
    onClick: () -> Unit,
) {
    val beat = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(pulse) {
        if (!pulse) return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        beat.animateTo(
            1f,
            androidx.compose.animation.core.repeatable(
                6, androidx.compose.animation.core.tween(320),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
        )
        beat.snapTo(0f)
    }
    val lit = Halo.cyan.copy(alpha = 0.55f * beat.value)
    Row(
        Modifier.clip(rs(10))
            .background(tint.copy(alpha = 0.10f + 0.18f * beat.value))
            .border(1.dp, if (beat.value > 0.01f) lit else tint.copy(alpha = 0.45f), rs(10))
            .then(
                if (onLongClick == null) Modifier.clickable { onClick() }
                else Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { HaloIcon(icon, tint, 14.dp); Spacer(Modifier.width(6.dp)) }
        Text(label, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = tint)
    }
}

@Composable
private fun fieldColors(border: Color) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = border, unfocusedBorderColor = border.copy(alpha = 0.7f),
    focusedContainerColor = Halo.cardSoft, unfocusedContainerColor = Halo.cardSoft,
    cursorColor = Halo.mint, focusedTextColor = Halo.ink, unfocusedTextColor = Halo.ink,
)
