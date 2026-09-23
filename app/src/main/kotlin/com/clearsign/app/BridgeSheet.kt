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
 * The bridge: SOL or USDC to another chain through RocketX. Pick the chain, paste the
 * address there, type the amount; quotes come in and the best deposit route is chosen;
 * Continue opens the order and hands the deposit address to the ordinary Send, same
 * receipt and fingerprint as any payment. The line under the button says what this is not.
 */
@Composable
internal fun BridgeSheet(
    owner: String,
    onSend: (PayRequest, String?, RocketX.Deal) -> Unit,
    onHistory: () -> Unit,
    /** Opened from Send: arrives already in private mode, address and amount inside. */
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
     * Private means Solana on both sides: the bridge's own machinery (quote, order, deposit,
     * receipt, signature) with the same chain on both shores. RocketX calls them Privacy Route
     * and Monero Rails, `walletLess` routes like every one this app already pays.
     */
    var private by remember { mutableStateOf(startPrivate) }
    var sameCoin by remember { mutableStateOf(true) }
    var toToken by remember { mutableStateOf<RocketX.Token?>(null) }
    var dest by remember { mutableStateOf(startDest) }
    var amount by remember { mutableStateOf(startAmount) }
    var quotes by remember { mutableStateOf<List<RocketX.Quote>>(emptyList()) }
    var quoting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    // Which route, chosen by the payer. Reset when the quotes change.
    var picked by remember { mutableStateOf(0) }
    // The number the order really brought, when worse than the quote.
    var worse by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    // The order already open and waiting for an answer. Without this a second tap
    // on "accept" opened a second order at RocketX and paid that one while the
    // screen showed the first.
    var pending by remember { mutableStateOf<RocketX.Order?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // The amount below which nobody accepts. It comes from the refused quote, not
    // from us: it depends on the price of the moment and changes hour to hour.
    var minAmount by remember { mutableStateOf<Double?>(null) }
    // The same number, known before trying. See [RocketX.PROBE].
    var floor by remember { mutableStateOf<Pair<Double, Double?>?>(null) }
    val fromMint = if (usdc) USDC_MINT else null
    val fromSym = if (usdc) "USDC" else "SOL"

    LaunchedEffect(Unit) {
        networks = withContext(Dispatchers.IO) { runCatching { RocketX.networks() }.getOrDefault(emptyList()) }
        home = withContext(Dispatchers.IO) { runCatching { RocketX.home() }.getOrNull() }
        // Start from Ethereum, not from RocketX's first, which is Bitcoin: on a
        // bridge from Solana the first is the one used most.
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
     * The minimum asked at opening, not after a refusal, and only for the private send: there
     * every route is private and shares one floor, so under it the page empties and nothing
     * moves. On a normal bridge there is no minimum to write (measured: Relay takes a
     * thousandth of a SOL) and announcing one would invent a ban.
     */
    LaunchedEffect(private, usdc) {
        floor = null
        if (!private) return@LaunchedEffect
        val probe = withContext(Dispatchers.IO) {
            // [RocketX.home] the first time is a call, not a read: ask it here, not
            // on the drawing thread.
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
            // The shore resolves inside IO here too: [RocketX.home] goes to the
            // network the first time, and blocked the drawing from here.
            val t = (if (private) RocketX.home() else target) ?: return@withContext null
            // Private: same coin and same chain on both sides.
            val to = if (private) fromMint else if (usdc && sameCoin) toToken?.contract else null
            runCatching { RocketX.quote(fromMint, "solana", to, t.id, amt) }.getOrNull()
        } ?: RocketX.Quotes(emptyList(), null, null)
        quotes = answer.list.filter { it.walletLess }
        minAmount = answer.minAmount?.takeIf { quotes.isEmpty() }
        // The minimum a fresh answer just said beats the one asked at opening: the
        // same number, half an hour later.
        minAmount?.let { floor = it to answer.minUsd }
        quoting = false
        // "No route, try another amount" sent people guessing a number the answer
        // already held. When the no is about the amount, say the amount.
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
            // Eight chains in front and a search for the rest: [ChainPickerSheet] says
            // why. All two hundred and seven used to sit here in a row.
            var pickChain by remember { mutableStateOf(false) }
            val popular = remember(networks) { RocketX.popular(networks) }
            // The chosen chain stays in the front row even when it is not among the eight: a chain that
            // vanishes from the screen after you picked it is the fastest way to lose track of where the
            // money is going.
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

            // The minimum, said before the amount is typed, with dollars next to it: that is the number
            // that stands still. The SOL figure moves with the price, and without the dollars the app
            // seems unable to make up its mind.
            floor?.let { (m, usd) ->
                Text(
                    if (usd == null) stringResource(R.string.bridge_floor, minText(m), fromSym)
                    // [fmtPrice] and not [fmtFiat]: the minimum is a public sign, not
                    // your balance, and is not hidden in guest mode.
                    else stringResource(R.string.bridge_floor_usd, minText(m), fromSym, fmtPrice(usd, "USD")),
                    style = HaloType.small, color = Halo.muted,
                )
            }

            // The verdict on the address while you paste it, not after.
            // Where the money lands: the other chain, or Solana itself.
            val landing = if (private) home else target
            val fits = landing?.let { t -> dest.takeIf { it.isNotBlank() }?.let { RocketX.addressFits(t, it) } }
            if (fits == false) {
                Banner(stringResource(R.string.bridge_dest_wrong, landing?.name ?: ""), Halo.red, HIcon.BLOCK)
            } else if (fits == null && dest.length >= 20) {
                Text(stringResource(R.string.bridge_dest_unknown), style = HaloType.small, color = Halo.amber, lineHeight = 16.sp)
            }

            if (quoting) Text(stringResource(R.string.bridge_quoting), style = HaloType.small, color = Halo.muted)
            error?.let { Banner(it, Halo.amber, HIcon.WARNING) }
            // The minimum is not just news, it is a figure to put in the field: copying
            // it by hand from a notice is work the phone can do, and by hand a digit goes wrong.
            minAmount?.let { m ->
                Row {
                    SmallChip(stringResource(R.string.bridge_use_min, minText(m), fromSym), HIcon.PEN, tint = Halo.mint) {
                        amount = minText(m)
                        Haptics.tick(ctx)
                    }
                }
            }
            // Three routes, and the chosen one is the one that goes. Three were shown and the button
            // always took the first: a decorative list, and on a page where every row means a different
            // amount arriving, three fake choices are worse than one real one.
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
                    // What the road costs you, whole and in money. The line below said only the declared fees,
                    // less than half the bill: on one SOL they are 0.86 $ while 1.10 go missing between
                    // departure and arrival, the rest being the exchange inside the route, seen only by
                    // subtracting. This line is that subtraction. Only where the coin is the same on both
                    // shores (private send, USDC to USDC): subtracting SOL from ETH means nothing.
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
                    // The declared fees: under the total they are a detail, alone
                    // they are all that is known.
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
                    // If an order is already open and waiting for your yes, that is the one
                    // paid: no second one is opened.
                    val order = pending ?: withContext(Dispatchers.IO) { runCatching { RocketX.swap(q.fromId, q.toId, owner, dest, amt!!) }.getOrNull() }
                    busy = null
                    val deposit = order?.depositAddress
                    when {
                        order == null || deposit == null -> error = ctx.getString(R.string.bridge_open_failed)
                        // The quote said this route needs a memo and the order carried none. A deposit without its
                        // memo on a chain that needs one arrives credited to nobody; the quote itself says so, and
                        // that field was never read.
                        q.memoRequired && order.memo.isNullOrBlank() ->
                            error = ctx.getString(R.string.bridge_memo_missing)
                        // The quote is not the order. Time passes between the number you saw and the order, and a
                        // route can move; we signed on a figure seen earlier. The app already does this right on
                        // sales: if the real one is worse beyond a threshold, both numbers are said and an answer
                        // awaited. Under two percent nobody is stopped.
                        order.toAmount > 0 && q.toAmount > 0 && order.toAmount < q.toAmount * 0.98 && worse == null -> {
                            worse = q.toAmount to order.toAmount
                            pending = order
                        }
                        else -> {
                            // The deposit is a payment like any other: Send, receipt, print. A memo, when the route wants one, rides in the transaction.
                            val toSym = if (private || (usdc && sameCoin)) fromSym else t.native
                            // What arrives is what the order says, not the quote: the
                            // order is the deal. The quote fills in only when the order did not say.
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
                            // The final destination goes on the receipt. What is signed is a payment to RocketX, so the
                            // receipt showed the deposit address and never where the money ends up: the one thing that
                            // counts was invisible at signing.
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

            // The bridges already opened have a page of their own. Three sat here with a button that
            // asked the status and wrote one word: a stuck bridge is checked on the destination chain's
            // explorer or taken to RocketX with the order number, and neither was anywhere.
            val past = remember { RocketX.bridges(ctx) }
            if (past.isNotEmpty()) {
                GhostButton(stringResource(R.string.bridge_past_all, past.size), Modifier.fillMaxWidth(), HIcon.HISTORY, tint = Halo.cyan) { onHistory() }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * The minimum, rounded up. Rounded down it lands back under the minimum and the route says
 * no a second time with the same notice: the only rounding that counts here is the one on
 * the right side of the threshold.
 */
internal fun minText(v: Double): String {
    val up = kotlin.math.ceil(v * 10_000.0) / 10_000.0
    return String.format(Locale.getDefault(), "%.4f", up).trimEnd('0').trimEnd { !it.isDigit() }
}

/** An amount in coin, no trailing zeros. */
internal fun coinText(v: Double): String =
    String.format(Locale.ROOT, "%.6f", v).trimEnd('0').trimEnd('.')

/** The one real USDC on Solana. Written once, read by Send and by the bridge. */
internal const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
