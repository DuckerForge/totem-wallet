@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clearsign.core.AgentPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow

/*
 * The four things a person can ask of a coin without staying on the chart:
 * sell it in profit, buy it cheaper, buy it a slice at a time, be told when it
 * moves. The first three are orders on Jupiter and fire with the phone off;
 * the fourth costs nothing and signs nothing.
 *
 * Every order goes through the ordinary receipt and the Seed Vault, like a
 * swap: Jupiter builds the bytes, the receipt says where the money goes (into
 * Jupiter's escrow, labelled), the person holds to confirm. Nothing here is
 * signed blind, and nothing here is signed by the agent's key.
 *
 * What none of these can be is a stop loss. That needs Jupiter's keyed API,
 * and every sheet says so in one line rather than letting anybody assume it.
 */

/** The coin an order is about, the same way every sheet sees it. */
internal data class OrderCoin(
    val mint: String, val symbol: String, val decimals: Int, val image: String?,
    /** Dollars per whole coin, when somebody quotes it. */
    val priceUsd: Double?,
    /** How much the main account holds, raw units. Zero when the sheet is about buying. */
    val heldRaw: Long = 0L,
)

/** Jupiter's floor for a Trigger order, as measured. Quoted here so the sheet can say no before asking. */
private const val TRIGGER_MIN_USD = 5.0
/** Jupiter's floor per DCA round, as it answered on 2026-09-15. */
private const val DCA_MIN_USD_PER_ROUND = 50.0

private sealed interface OrderState {
    data object Form : OrderState
    data object Building : OrderState
    /** Built and looked at. [place] takes the signed bytes and finishes the job; it returns an error or null. */
    class Review(val unsigned: ByteArray, val analyzed: ReceiptEngine.Analyzed, val hold: String, val place: suspend (ByteArray) -> String?) : OrderState
    data object Signing : OrderState
    data object Done : OrderState
    class Error(val message: String) : OrderState
}

// ---- the shared tail: receipt, hold, sign, execute ----------------------------

@Composable
private fun OrderTail(state: OrderState, signer: SeedVaultSigner, owner: String, doneLabel: String, onState: (OrderState) -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    when (state) {
        OrderState.Building -> Working(stringResource(R.string.order_building))
        is OrderState.Review -> {
            val willFail = state.analyzed.receipt.risks.any {
                it.flag == com.clearsign.core.RiskFlag.SIMULATION_FAILED && it.severity == com.clearsign.core.Severity.DANGER
            }
            SignReceiptBody(state.analyzed.receipt, null, null, plain = true)
            Text(stringResource(R.string.order_truth), style = HaloType.small, color = Halo.muted)
            if (willFail || state.analyzed.receipt.blocksApproval) {
                Banner(stringResource(R.string.swap_would_fail), Halo.red, HIcon.BLOCK)
                GhostButton(stringResource(R.string.back)) { onState(OrderState.Form) }
            } else {
                HoldToConfirm(state.hold) {
                    onState(OrderState.Signing)
                    scope.launch {
                        val err = try {
                            val sig = signer.signSuspend(state.unsigned)
                            withContext(Dispatchers.IO) { state.place(JupiterTrigger.attach(state.unsigned, owner, sig)) }
                        } catch (e: Exception) { e.message ?: ctx.getString(R.string.sign_error) }
                        onState(if (err == null) OrderState.Done else OrderState.Error(err))
                    }
                }
                GhostButton(stringResource(R.string.back)) { onState(OrderState.Form) }
            }
        }
        OrderState.Signing -> Working(stringResource(R.string.order_signing))
        OrderState.Done -> {
            Banner(doneLabel, Halo.mint, HIcon.CHECK)
            PrimaryButton(stringResource(R.string.done), danger = false) { onDone() }
        }
        is OrderState.Error -> {
            Banner(state.message, Halo.red, HIcon.WARNING)
            GhostButton(stringResource(R.string.back)) { onState(OrderState.Form) }
        }
        OrderState.Form -> Unit
    }
}

/** Read the bytes Jupiter built, the way a swap is read. Null when the receipt could not be made. */
private suspend fun look(ctx: android.content.Context, unsigned: ByteArray, owner: String): ReceiptEngine.Analyzed? = withContext(Dispatchers.IO) {
    runCatching { ReceiptEngine.analyze(ctx, BlocklistScanner(ctx), unsigned, owner, null, requireSim = true) }.getOrNull()
}

private fun recordOrder(ctx: android.content.Context, owner: String, analyzed: ReceiptEngine.Analyzed, signature: String?, note: String) {
    val at = System.currentTimeMillis()
    val e = LedgerRecorder.fromReceipt(
        at = at, kind = "order", dApp = "Jupiter", host = null, pkg = null, cluster = null, wallet = owner,
        r = analyzed.receipt, signature = signature, sent = true, txIndex = 0, txCount = 1, groupId = LedgerRecorder.newId(),
        attestation = null, attestationSig = null,
    )
    LedgerRecorder.record(ctx, e.copy(note = note))
}

private suspend fun solUsd(): Double? = withContext(Dispatchers.IO) {
    runCatching { Prices.usd(listOf(Jupiter.SOL_MINT))[Jupiter.SOL_MINT] }.getOrNull()
}

@Composable
private fun sheetField(): androidx.compose.material3.TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Halo.mint, unfocusedBorderColor = Halo.stroke,
    focusedContainerColor = Halo.cardSoft, unfocusedContainerColor = Halo.cardSoft, cursorColor = Halo.mint,
)

@Composable
private fun NumberField(label: String, value: String, unit: String, onChange: (String) -> Unit) {
    Column {
        Text(label, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Halo.muted)
        OutlinedTextField(
            value = value, onValueChange = { s -> onChange(s.filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.')) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            textStyle = TextStyle(fontFamily = Mono, fontSize = 16.sp, color = Halo.ink),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            trailingIcon = { Text(unit, fontFamily = Mono, fontSize = 12.sp, color = Halo.muted, modifier = Modifier.padding(end = 12.dp)) },
            colors = sheetField(), shape = rs(12),
        )
    }
}

@Composable
private fun OrderSheetFrame(title: String, body: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = HaloType.title, color = Halo.ink)
            Text(body, style = HaloType.small, color = Halo.muted)
            content()
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ---- 1. sell in profit --------------------------------------------------------

@Composable
internal fun TakeProfitSheet(coin: OrderCoin, signer: SeedVaultSigner, owner: String, onDone: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<OrderState>(OrderState.Form) }
    var pct by remember { mutableIntStateOf(30) }
    val price = coin.priceUsd
    val units = coin.heldRaw / 10.0.pow(coin.decimals)
    val target = price?.let { it * (100 + pct) / 100.0 }
    val valueUsd = price?.let { it * units }
    val tooSmall = valueUsd != null && valueUsd < TRIGGER_MIN_USD

    OrderSheetFrame(stringResource(R.string.order_tp_title), stringResource(R.string.order_tp_body, coin.symbol), onDismiss) {
        if (state == OrderState.Form) {
            if (price == null) {
                Banner(stringResource(R.string.order_no_price), Halo.amber, HIcon.WARNING)
            } else {
                PriceChart(coin.mint, coin.symbol, targets = listOf(ChartTarget(target!!, fmtPrice(target, "USD"), Halo.mint, key = pct)))
                SliderRow(stringResource(R.string.order_tp), "+$pct%", pct.toFloat(), 10f..300f, Halo.mint) { pct = it.toInt() }
                StatRow(stringResource(R.string.order_target), fmtPrice(target, "USD"), accent = true)
                StatRow(stringResource(R.string.token_amount), fmtUi(units) + " " + coin.symbol)
                StatRow(stringResource(R.string.order_expires, 30), "")
                if (tooSmall) Banner(stringResource(R.string.order_too_small, fmtFiat(TRIGGER_MIN_USD, "USD")), Halo.amber, HIcon.WARNING)
                Text(stringResource(R.string.order_truth), style = HaloType.small, color = Halo.muted)
                PrimaryButton(stringResource(R.string.order_review), danger = false, enabled = !tooSmall && coin.heldRaw > 0, icon = HIcon.HOURGLASS) {
                    state = OrderState.Building
                    scope.launch {
                        val sol = solUsd()
                        if (sol == null || sol <= 0) { state = OrderState.Error(ctx.getString(R.string.order_no_price)); return@launch }
                        val taking = (units * target / sol * 1e9).toLong()
                        val b = withContext(Dispatchers.IO) { JupiterTrigger.build(owner, coin.mint, Jupiter.SOL_MINT, coin.heldRaw, taking) }
                        state = when (b) {
                            is JupiterTrigger.Build.Failed -> OrderState.Error(b.reason)
                            JupiterTrigger.Build.TooSmall -> OrderState.Error(ctx.getString(R.string.order_too_small, fmtFiat(TRIGGER_MIN_USD, "USD")))
                            is JupiterTrigger.Build.Ok -> {
                                val a = look(ctx, b.built.unsigned, owner) ?: return@launch run { state = OrderState.Error(ctx.getString(R.string.brain_unreachable)) }
                                OrderState.Review(b.built.unsigned, a, ctx.getString(R.string.order_hold)) { signed ->
                                    val done = JupiterTrigger.execute(b.built, signed) ?: return@Review ctx.getString(R.string.brain_unreachable)
                                    done.optString("error").takeIf { it.isNotEmpty() }?.let { return@Review it }
                                    val sig = done.optString("signature").takeIf { it.isNotEmpty() }
                                    Orders.add(
                                        ctx,
                                        Orders.Order(
                                            Orders.Kind.TP, coin.mint, coin.symbol, coin.decimals, b.built.order, coin.heldRaw, target,
                                            System.currentTimeMillis(), System.currentTimeMillis() + 30L * 86_400_000L,
                                        ),
                                    )
                                    recordOrder(ctx, owner, a, sig, ctx.getString(R.string.order_tp) + " +$pct% " + coin.symbol)
                                    OrdersKeeper.sync(ctx)
                                    null
                                }
                            }
                        }
                    }
                }
            }
        }
        OrderTail(state, signer, owner, stringResource(R.string.order_placed), { state = it }, onDone)
    }
}

// ---- 2. buy below a price -----------------------------------------------------

@Composable
internal fun LimitBuySheet(coin: OrderCoin, signer: SeedVaultSigner, owner: String, onDone: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<OrderState>(OrderState.Form) }
    val price = coin.priceUsd
    var targetText by remember { mutableStateOf(price?.let { fmtPlain(it * 0.9) } ?: "") }
    var amountText by remember { mutableStateOf("0.1") }
    val target = targetText.toDoubleOrNull()
    val amountSol = amountText.toDoubleOrNull() ?: 0.0
    var sol by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) { sol = solUsd() }
    val valueUsd = sol?.let { it * amountSol }
    val mustBeBelow = price != null && target != null && target >= price
    val tooSmall = valueUsd != null && valueUsd < TRIGGER_MIN_USD

    OrderSheetFrame(stringResource(R.string.order_limit), stringResource(R.string.order_limit_body, coin.symbol), onDismiss) {
        if (state == OrderState.Form) {
            if (price == null) Banner(stringResource(R.string.order_no_price), Halo.amber, HIcon.WARNING)
            else PriceChart(coin.mint, coin.symbol, targets = listOfNotNull(target?.let { ChartTarget(it, fmtPrice(it, "USD"), Halo.cyan, key = targetText) }))
            NumberField(stringResource(R.string.order_price), targetText, "USD") { targetText = it }
            NumberField(stringResource(R.string.order_amount_sol), amountText, "SOL") { amountText = it }
            valueUsd?.let { StatRow(stringResource(R.string.order_total_now), fmtFiat(it, "USD"), accent = true) }
            if (target != null && target > 0 && sol != null) {
                StatRow(stringResource(R.string.order_you_get), fmtUi(amountSol * sol!! / target) + " " + coin.symbol)
            }
            if (mustBeBelow) Banner(stringResource(R.string.order_must_below), Halo.amber, HIcon.WARNING)
            if (tooSmall) Banner(stringResource(R.string.order_too_small, fmtFiat(TRIGGER_MIN_USD, "USD")), Halo.amber, HIcon.WARNING)
            Text(stringResource(R.string.order_truth), style = HaloType.small, color = Halo.muted)
            PrimaryButton(
                stringResource(R.string.order_review), danger = false, icon = HIcon.HOURGLASS,
                enabled = price != null && target != null && target > 0 && !mustBeBelow && !tooSmall && amountSol > 0 && sol != null,
            ) {
                state = OrderState.Building
                scope.launch {
                    val s = sol ?: return@launch
                    val making = (amountSol * 1e9).toLong()
                    val taking = (amountSol * s / target!! * 10.0.pow(coin.decimals)).toLong()
                    val b = withContext(Dispatchers.IO) { JupiterTrigger.build(owner, Jupiter.SOL_MINT, coin.mint, making, taking) }
                    state = when (b) {
                        is JupiterTrigger.Build.Failed -> OrderState.Error(b.reason)
                        JupiterTrigger.Build.TooSmall -> OrderState.Error(ctx.getString(R.string.order_too_small, fmtFiat(TRIGGER_MIN_USD, "USD")))
                        is JupiterTrigger.Build.Ok -> {
                            val a = look(ctx, b.built.unsigned, owner) ?: return@launch run { state = OrderState.Error(ctx.getString(R.string.brain_unreachable)) }
                            OrderState.Review(b.built.unsigned, a, ctx.getString(R.string.order_hold)) { signed ->
                                val done = JupiterTrigger.execute(b.built, signed) ?: return@Review ctx.getString(R.string.brain_unreachable)
                                done.optString("error").takeIf { it.isNotEmpty() }?.let { return@Review it }
                                Orders.add(
                                    ctx,
                                    Orders.Order(
                                        Orders.Kind.LIMIT, coin.mint, coin.symbol, coin.decimals, b.built.order, making, target,
                                        System.currentTimeMillis(), System.currentTimeMillis() + 30L * 86_400_000L,
                                    ),
                                )
                                recordOrder(ctx, owner, a, done.optString("signature").takeIf { it.isNotEmpty() }, ctx.getString(R.string.order_limit) + " " + coin.symbol)
                                OrdersKeeper.sync(ctx)
                                null
                            }
                        }
                    }
                }
            }
        }
        OrderTail(state, signer, owner, stringResource(R.string.order_placed), { state = it }, onDone)
    }
}

// ---- 3. a slice at a time ------------------------------------------------------

@Composable
internal fun DcaSheet(coin: OrderCoin, signer: SeedVaultSigner, owner: String, onDone: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<OrderState>(OrderState.Form) }
    var perText by remember { mutableStateOf("50") }
    var rounds by remember { mutableIntStateOf(4) }
    var weekly by remember { mutableStateOf(true) }
    val per = perText.toDoubleOrNull() ?: 0.0
    val total = per * rounds
    val tooSmall = per < DCA_MIN_USD_PER_ROUND
    val interval = if (weekly) 7L * 86_400 else 86_400L

    OrderSheetFrame(stringResource(R.string.order_dca), stringResource(R.string.order_dca_body, coin.symbol), onDismiss) {
        if (state == OrderState.Form) {
            NumberField(stringResource(R.string.order_per_round), perText, "USDC") { perText = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(stringResource(R.string.order_every_day), !weekly, Halo.cyan, Modifier.weight(1f)) { weekly = false }
                ModeChip(stringResource(R.string.order_every_week), weekly, Halo.cyan, Modifier.weight(1f)) { weekly = true }
            }
            SliderRow(stringResource(R.string.order_rounds_label), stringResource(R.string.order_rounds, rounds), rounds.toFloat(), 2f..30f, Halo.cyan, steps = 27) { rounds = it.toInt() }
            StatRow(stringResource(R.string.order_total_now), fmtFiat(total, "USD"), accent = true)
            if (tooSmall) Banner(stringResource(R.string.order_too_small_round, fmtFiat(DCA_MIN_USD_PER_ROUND, "USD")), Halo.amber, HIcon.WARNING)
            Text(stringResource(R.string.order_truth), style = HaloType.small, color = Halo.muted)
            PrimaryButton(stringResource(R.string.order_review), danger = false, enabled = !tooSmall && per > 0, icon = HIcon.HOURGLASS) {
                state = OrderState.Building
                scope.launch {
                    val raw = (total * 1e6).toLong()
                    val before = withContext(Dispatchers.IO) { runCatching { JupiterRecurring.live(owner) }.getOrNull().orEmpty() }
                    val b = withContext(Dispatchers.IO) { JupiterRecurring.build(owner, AgentPolicy.USDC, coin.mint, raw, rounds, interval) }
                    state = when (b) {
                        is JupiterRecurring.Build.Failed -> OrderState.Error(b.reason)
                        is JupiterRecurring.Build.TooSmall -> OrderState.Error(b.said)
                        is JupiterRecurring.Build.Ok -> {
                            val a = look(ctx, b.built.unsigned, owner) ?: return@launch run { state = OrderState.Error(ctx.getString(R.string.brain_unreachable)) }
                            OrderState.Review(b.built.unsigned, a, ctx.getString(R.string.order_hold)) { signed ->
                                val done = JupiterRecurring.execute(b.built, signed) ?: return@Review ctx.getString(R.string.brain_unreachable)
                                done.optString("error").takeIf { it.isNotEmpty() }?.let { return@Review it }
                                val sig = done.optString("signature").takeIf { it.isNotEmpty() }
                                // Jupiter's answer has no order key. The list does, a
                                // moment later: the new one is the one we did not know.
                                var key: String? = null
                                repeat(3) {
                                    if (key == null) {
                                        delay(1_500)
                                        key = runCatching { JupiterRecurring.live(owner) }.getOrNull()?.firstOrNull { it !in before }
                                    }
                                }
                                Orders.add(
                                    ctx,
                                    Orders.Order(
                                        Orders.Kind.DCA, coin.mint, coin.symbol, coin.decimals, key ?: ("sig:" + (sig ?: LedgerRecorder.newId())), raw, 0.0,
                                        System.currentTimeMillis(), System.currentTimeMillis() + rounds * interval * 1000L,
                                        rounds = rounds, intervalSec = interval,
                                    ),
                                )
                                recordOrder(ctx, owner, a, sig, ctx.getString(R.string.order_dca) + " " + coin.symbol)
                                OrdersKeeper.sync(ctx)
                                null
                            }
                        }
                    }
                }
            }
        }
        OrderTail(state, signer, owner, stringResource(R.string.order_placed), { state = it }, onDone)
    }
}

// ---- 4. tell me ---------------------------------------------------------------

@Composable
internal fun AlertSheet(coin: OrderCoin, onDone: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val price = coin.priceUsd
    var above by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf(price?.let { fmtPlain(it) } ?: "") }
    val target = text.toDoubleOrNull()
    var granted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    OrderSheetFrame(stringResource(R.string.order_alert), stringResource(R.string.order_alert_body, coin.symbol), onDismiss) {
        if (price != null) PriceChart(coin.mint, coin.symbol, targets = listOfNotNull(target?.let { ChartTarget(it, fmtPrice(it, "USD"), Halo.amber, key = text) }))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip(stringResource(R.string.order_above), above, Halo.amber, Modifier.weight(1f)) { above = true }
            ModeChip(stringResource(R.string.order_below), !above, Halo.amber, Modifier.weight(1f)) { above = false }
        }
        NumberField(stringResource(R.string.order_price), text, "USD") { text = it }
        if (!granted) {
            Banner(stringResource(R.string.order_notif_perm), Halo.amber, HIcon.WARNING)
            GhostButton(stringResource(R.string.order_notif_ask), Modifier.fillMaxWidth(), HIcon.INFO, tint = Halo.cyan) {
                if (Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        PrimaryButton(stringResource(R.string.order_alert_save), danger = false, enabled = target != null && target > 0, icon = HIcon.CHECK) {
            Orders.addAlert(ctx, Orders.Alert(coin.mint, coin.symbol, above, target!!, System.currentTimeMillis()))
            OrdersKeeper.sync(ctx)
            Haptics.tick(ctx)
            onDone()
        }
    }
}

// ---- the list ---------------------------------------------------------------------

/**
 * Every order and alert, one row each, with the distance from the price of the
 * moment and a way to take it back. Cancelling an order is a transaction, so it
 * gets the same receipt and the same hold as placing one.
 */
@Composable
internal fun OrdersSection(signer: SeedVaultSigner?, owner: String?, refresh: Int, onChange: () -> Unit) {
    val ctx = LocalContext.current
    val orders = remember(refresh) { Orders.all(ctx) }
    val alerts = remember(refresh) { Orders.alerts(ctx) }
    if (orders.isEmpty() && alerts.isEmpty()) return
    var prices by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    LaunchedEffect(orders.size, alerts.size) {
        prices = withContext(Dispatchers.IO) { runCatching { Prices.usd((orders.map { it.mint } + alerts.map { it.mint }).distinct()) }.getOrDefault(emptyMap()) }
    }
    var cancelling by remember { mutableStateOf<Orders.Order?>(null) }

    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.orders_title).uppercase(), style = HaloType.label, color = Halo.muted)
            orders.forEach { o ->
                val now = prices[o.mint]
                val tint = when (o.kind) { Orders.Kind.TP -> Halo.mint; Orders.Kind.LIMIT -> Halo.cyan; Orders.Kind.DCA -> Halo.cyan }
                Row(
                    Modifier.fillMaxWidth().clip(rs(Radius.row)).background(Halo.cardSoft).padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TokenLogo(o.mint, o.symbol, JupiterTokens.cached(o.mint)?.icon, 26.dp)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            o.symbol + " · " + stringResource(
                                when (o.kind) { Orders.Kind.TP -> R.string.order_kind_tp; Orders.Kind.LIMIT -> R.string.order_kind_limit; Orders.Kind.DCA -> R.string.order_kind_dca },
                            ) + (if (o.kind != Orders.Kind.DCA) " " + fmtPrice(o.targetUsd, "USD") else ""),
                            fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Halo.ink, maxLines = 1,
                        )
                        Text(
                            when {
                                o.kind == Orders.Kind.DCA -> stringResource(R.string.order_dca_line, o.rounds, stringResource(if (o.intervalSec >= 7 * 86_400) R.string.order_every_week else R.string.order_every_day))
                                now != null && now > 0 -> stringResource(R.string.order_distance, String.format("%+.1f%%", (o.targetUsd - now) / now * 100))
                                else -> stringResource(R.string.order_expires, ((o.expiresAt - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0L).toInt())
                            },
                            fontFamily = Mono, fontSize = 10.5.sp, color = tint, style = Tabular,
                        )
                    }
                    if (signer != null && owner != null) {
                        SmallChip(stringResource(R.string.order_cancel), null, tint = Halo.muted) { cancelling = o }
                    }
                }
            }
            alerts.forEach { a ->
                Row(
                    Modifier.fillMaxWidth().clip(rs(Radius.row)).background(Halo.cardSoft).padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HaloIcon(HIcon.WARNING, Halo.amber, 16.dp)
                    Spacer(Modifier.width(9.dp))
                    Text(
                        a.symbol + " " + stringResource(if (a.above) R.string.order_above else R.string.order_below).lowercase() + " " + fmtPrice(a.priceUsd, "USD"),
                        fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Halo.ink, modifier = Modifier.weight(1f), maxLines = 1,
                    )
                    SmallChip(stringResource(R.string.order_cancel), null, tint = Halo.muted) { Orders.removeAlert(ctx, a.id); OrdersKeeper.sync(ctx); onChange() }
                }
            }
        }
    }

    cancelling?.let { o ->
        if (signer != null && owner != null) CancelOrderSheet(o, signer, owner, onDone = { cancelling = null; onChange() }) { cancelling = null }
    }
}

@Composable
private fun CancelOrderSheet(o: Orders.Order, signer: SeedVaultSigner, owner: String, onDone: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<OrderState>(OrderState.Building) }
    LaunchedEffect(o.key) {
        // An order we never learned the key of cannot be cancelled from here.
        if (o.key.startsWith("sig:")) { state = OrderState.Error(ctx.getString(R.string.order_no_key)); return@LaunchedEffect }
        val built = withContext(Dispatchers.IO) {
            if (o.kind == Orders.Kind.DCA) JupiterRecurring.buildCancel(owner, o.key)?.let { JupiterTrigger.Built("", it.requestId, it.unsigned) }
            else JupiterTrigger.buildCancel(owner, o.key)
        }
        if (built == null) { state = OrderState.Error(ctx.getString(R.string.brain_unreachable)); return@LaunchedEffect }
        val a = look(ctx, built.unsigned, owner)
        if (a == null) { state = OrderState.Error(ctx.getString(R.string.brain_unreachable)); return@LaunchedEffect }
        state = OrderState.Review(built.unsigned, a, ctx.getString(R.string.order_cancel_hold)) { signed ->
            val done = (if (o.kind == Orders.Kind.DCA) JupiterRecurring.execute(JupiterRecurring.Built(built.requestId, built.unsigned), signed) else JupiterTrigger.execute(built, signed))
                ?: return@Review ctx.getString(R.string.brain_unreachable)
            done.optString("error").takeIf { it.isNotEmpty() }?.let { return@Review it }
            Orders.remove(ctx, o.key)
            recordOrder(ctx, owner, a, done.optString("signature").takeIf { it.isNotEmpty() }, ctx.getString(R.string.order_cancel) + " " + o.symbol)
            OrdersKeeper.sync(ctx)
            null
        }
    }
    OrderSheetFrame(stringResource(R.string.order_cancel_title, o.symbol), stringResource(R.string.order_cancel_body), onDismiss) {
        if (state is OrderState.Error) GhostButton(stringResource(R.string.close)) { onDismiss() }
        OrderTail(state, signer, owner, stringResource(R.string.order_cancelled), { state = it }, onDone)
    }
}

/** A number for a text field: enough digits to be exact, no thousands separators. */
private fun fmtPlain(v: Double): String = when {
    v >= 1 -> String.format(java.util.Locale.ROOT, "%.4f", v)
    v >= 0.0001 -> String.format(java.util.Locale.ROOT, "%.6f", v)
    else -> String.format(java.util.Locale.ROOT, "%.10f", v)
}.trimEnd('0').trimEnd('.')
