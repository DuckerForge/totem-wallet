@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The market, the way everyone already knows how to read one.
 *
 * Ranked by market capitalisation, every chain in it, a star to follow, a search
 * that reaches past the first hundred. The wallet's own lists answer what this
 * wallet can trade; this one answers what the market is doing, which is a
 * different question and deserves a different screen.
 *
 * Two things it does that a price site cannot. It knows which coins have a Solana
 * mint, so a followed coin that can be bought says so and the rest do not pretend.
 * And it takes **your own amount** for anything you hold somewhere else, so the
 * followed list is a portfolio and not a list of prices belonging to nobody.
 */
@Composable
internal fun MarketScreen(owner: String? = null, signer: SeedVaultSigner? = null, onBuy: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var ranked by remember { mutableStateOf<List<Market.Coin>>(emptyList()) }
    var found by remember { mutableStateOf<List<Market.Coin>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var followed by remember { mutableStateOf<List<Market.Coin>>(emptyList()) }
    var editing by remember { mutableStateOf<Market.Coin?>(null) }

    val keys = remember(refresh) { Watchlist.reconcile(ctx); Watchlist.all(ctx) }
    var unfollow by remember { mutableStateOf<Market.Coin?>(null) }
    val currency by Settings.currency
    // Il mercato risponde in dollari; qui si legge nella valuta scelta.
    val fx = rememberFx()

    LaunchedEffect(refresh) {
        loading = true
        ranked = withContext(Dispatchers.IO) { runCatching { Market.top() }.getOrDefault(emptyList()) }
        loading = false
    }

    // The followed list is priced on its own: it can hold coins that are nowhere
    // near the first hundred by market cap.
    LaunchedEffect(keys, ranked) {
        if (keys.isEmpty()) { followed = emptyList(); return@LaunchedEffect }
        val ids = keys.filter { it.startsWith("cg:") }.map { it.removePrefix("cg:") }
        val byId = if (ids.isEmpty()) emptyMap() else withContext(Dispatchers.IO) {
            runCatching { Market.pricesFor(ids) }.getOrDefault(emptyMap())
        }
        // A followed coin never disappears from its own list. When nobody can
        // price it right now it is shown thin, with its name, and priced later.
        val mintsToAsk = keys.filter { !it.startsWith("cg:") && ranked.none { r -> r.mint == it } }
        val jup = if (mintsToAsk.isEmpty()) emptyMap() else withContext(Dispatchers.IO) { runCatching { JupiterTokens.byMints(mintsToAsk) }.getOrDefault(emptyMap()) }
        followed = keys.map { key ->
            val fresh: Market.Coin? = if (key.startsWith("cg:")) {
                byId[key.removePrefix("cg:")] ?: ranked.firstOrNull { it.key == key }
            } else {
                ranked.firstOrNull { it.mint == key } ?: jup[key]?.let { t ->
                    Market.Coin(id = key, symbol = t.symbol, name = t.name, image = t.icon, priceUsd = t.usd, marketCap = null, rank = null, change24h = t.change24h, mint = key)
                } ?: solanaCoin(key)
            }
            // Priced now: remember it. Not priced now: show it as it was last time.
            if (fresh?.priceUsd != null) Watchlist.rememberCoin(ctx, key, Market.toJson(fresh))
            fresh ?: Watchlist.recallCoin(ctx, key)?.let { Market.fromJson(it) } ?: run {
                val id = key.removePrefix("cg:")
                Market.Coin(id = id, symbol = if (key.startsWith("cg:")) id.uppercase().take(10) else shorten(key, 4), name = id.replaceFirstChar { it.uppercase() }, image = null, priceUsd = null, marketCap = null, rank = null, change24h = null, mint = key.takeIf { !it.startsWith("cg:") })
            }
        }.let { coins ->
            // One coin, one key. A coin followed by its CoinGecko name that turns
            // out to live on Solana is the same coin as the one followed by mint,
            // and two rows with one key crashed the list. The mint wins: the
            // amount moves over and the name key goes.
            var moved = false
            keys.zip(coins).forEach { (stored, c) ->
                // The markets list rarely carries the mint; the coin page does.
                // Asked once per coin and cached inside Market.
                val mint = c.mint ?: if (stored.startsWith("cg:")) withContext(Dispatchers.IO) { runCatching { Market.mintOf(stored.removePrefix("cg:")) }.getOrNull() } else null
                if (stored.startsWith("cg:") && mint != null) {
                    val amt = Watchlist.amount(ctx, stored)
                    if (amt > 0 && Watchlist.amount(ctx, mint) <= 0) Watchlist.setAmount(ctx, mint, amt)
                    if (Watchlist.moves(ctx, stored)) Watchlist.setMoves(ctx, mint, true)
                    Watchlist.add(ctx, mint); Watchlist.remove(ctx, stored)
                    moved = true
                }
            }
            if (moved) refresh++
            coins.distinctBy { it.key }
        }
    }

    val q = query.trim()
    LaunchedEffect(q) {
        found = emptyList()
        if (q.length < 2) { searching = false; return@LaunchedEffect }
        delay(350)
        searching = true
        val hits = withContext(Dispatchers.IO) { runCatching { Market.search(q) }.getOrDefault(emptyList()) }
        found = hits
        // The search knows names, not prices. One more call fills the rows that
        // came back thin, so a coin with a price is never shown as "no price".
        val thin = hits.filter { it.priceUsd == null }.map { it.id }.take(25)
        if (thin.isNotEmpty()) {
            val px = withContext(Dispatchers.IO) { runCatching { Market.pricesFor(thin) }.getOrDefault(emptyMap()) }
            if (px.isNotEmpty()) found = hits.map { c -> px[c.id]?.let { it.copy(mint = c.mint ?: it.mint) } ?: c }
        }
        searching = false
    }

    val shown = if (q.length >= 2) found else ranked.filter { c -> keys.none { it == c.key } }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Box(Modifier.padding(top = 14.dp, bottom = 10.dp)) {
                PageHeader(stringResource(R.string.tab_market), stringResource(R.string.market_sub), HIcon.STAR_FILLED, tint = Halo.amber) {
                    RoundIconButton(HIcon.REFRESH, spinning = loading, description = stringResource(R.string.a11y_refresh)) { refresh++ }
                }
            }
        }
        // Chi non segue niente lo legge qui, dove starebbe la lista, con cosa fare.
        if (keys.isEmpty() && q.length < 2) item { EmptyLine(HIcon.STAR, stringResource(R.string.market_empty)) }

        // What is standing on Jupiter in your name, before the watchlist: an order
        // is a decision already made, and it is the first thing worth checking.
        item { OrdersSection(signer, owner, refresh) { refresh++ } }

        if (followed.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.market_watching)) }
            items(followed, key = { "w-" + it.key }) { c ->
                // Following is one tap; unfollowing asks, because it takes the amount with it.
                val amount = remember(refresh, c.key) { Watchlist.amount(ctx, c.key) }
                CoinRow(c, fx, followed = true, amount = amount, onOpen = { editing = c }) { unfollow = c }
            }
            item {
                // What the favourites are worth, what the wallet is worth, and the two together.
                val favUsd = followed.sumOf { c -> Watchlist.amount(ctx, c.key) * (c.priceUsd ?: 0.0) }
                val wallet = remember(refresh, currency) { Portfolio.cached(owner, currency)?.total }
                if (favUsd > 0 || (wallet ?: 0.0) > 0) {
                    // Un pannello che si legge e basta: niente chevron, niente pressione.
                    SoftPanel {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatRow(stringResource(R.string.market_total_followed), fx.fiat(favUsd))
                            wallet?.let { w -> StatRow(stringResource(R.string.market_total_wallet), fmtFiat(w, currency)) }
                            // La somma si fa solo quando le due meta' sono nella stessa unita'.
                            val r = fx.rate.takeIf { fx.cur == currency }
                            if (r != null) {
                                val all = favUsd * r + (wallet ?: 0.0)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.market_total_all), style = HaloType.body, color = Halo.ink, modifier = Modifier.weight(1f))
                                    Text(fmtFiat(all, currency), style = HaloType.title, color = Halo.mint)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text(stringResource(R.string.market_search), style = HaloType.small, color = Halo.muted) },
                leadingIcon = { HaloIcon(HIcon.SEARCH, Halo.muted, 18.dp) },
                trailingIcon = {
                    if (query.isNotEmpty()) Box(Modifier.clip(rs(999)).clickable { query = "" }.padding(6.dp)) { HaloIcon(HIcon.CLOSE, Halo.muted, 16.dp, description = stringResource(R.string.a11y_clear_search)) }
                },
                textStyle = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = Halo.ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = pickerField(), shape = rs(14),
            )
        }

        item {
            SectionLabel(
                stringResource(
                    when {
                        q.length < 2 -> R.string.market_by_cap
                        searching -> R.string.tok_searching
                        else -> R.string.tok_results
                    },
                ),
            )
        }
        when (marketState(loading, ranked.isEmpty(), q, searching, shown.isEmpty())) {
            // Sei righe segnaposto: la pagina ha gia' la sua forma mentre il listino arriva.
            MarketState.LOADING -> items(6) { PlaceholderRow() }
            // Il listino non e' arrivato: prima restava un'etichetta sopra il nulla.
            MarketState.DOWN -> item {
                EmptyState(HIcon.CHART_DOWN, stringResource(R.string.market_down_title), stringResource(R.string.market_down_body), stringResource(R.string.market_retry) to { refresh++ })
            }
            MarketState.NO_RESULTS -> item { EmptyLine(HIcon.SEARCH, stringResource(R.string.tok_none)) }
            else -> items(shown, key = { "r-" + it.id }) { c ->
                // The star says the truth here too: lit when the coin is already followed.
                val isFollowed = c.key in keys
                val amount = remember(refresh, c.key) { Watchlist.amount(ctx, c.key) }
                CoinRow(c, fx, followed = isFollowed, amount = amount, onOpen = { editing = c }) {
                    if (isFollowed) unfollow = c else { Watchlist.add(ctx, c.key); Haptics.tick(ctx); refresh++ }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }

    unfollow?.let { c ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { unfollow = null },
            containerColor = Halo.ground2, titleContentColor = Halo.ink, textContentColor = Halo.muted,
            title = { Text(stringResource(R.string.market_confirm_unfollow, c.name), style = HaloType.title, color = Halo.ink) },
            text = { Text(stringResource(R.string.market_confirm_unfollow_body), style = HaloType.small, color = Halo.muted) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { Watchlist.remove(ctx, c.key); unfollow = null; refresh++ }) { Text(stringResource(R.string.market_unfollow), color = Halo.red) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { unfollow = null }) { Text(stringResource(R.string.cancel), color = Halo.muted) } },
        )
    }

    editing?.let { coin ->
        CoinSheet(
            coin = coin, signer = signer, owner = owner,
            onBuy = { mint -> editing = null; onBuy(mint) },
            onSaved = { refresh++ },
        ) { editing = null }
    }
}

/**
 * "If it were as big as Solana, one of these would cost…"
 *
 * The same arithmetic as marketcapof.com, with the one thing a website cannot
 * have: **your** amount. Holding the supply fixed, a coin at another coin's market
 * cap is worth `target ÷ current` times more, so the multiplier and the price come
 * straight from the two capitalisations.
 *
 * Which coins to compare against is chosen rather than listed: the one directly
 * above it in the ranking, because that is the next real step; Solana, because
 * that is the chain it lives on; and Bitcoin, because it is the ceiling everybody
 * measures against. A page of thirty comparisons is a toy — three is a thought.
 *
 * It says what it is, once, underneath: supply does not stay still while a coin
 * grows a hundredfold, and this is not a forecast.
 */
@Composable
private fun WhatIf(coin: Market.Coin, amount: Double, fx: Fx) {
    val mine = coin.marketCap ?: return
    if (mine <= 0) return
    val price = coin.priceUsd ?: return
    val ranked = remember { Market.cachedTop() }
    if (ranked.isEmpty()) return

    // The three fixed thoughts, then the ladder: the ranked coin nearest to
    // twice, ten times and a hundred times this one. "To do a ×10 it has to
    // become as big as X" is the sentence people actually think in.
    val fixed = remember(coin.id, ranked) {
        val above = coin.rank?.let { r -> ranked.filter { (it.rank ?: 0) < r && it.id != coin.id }.minByOrNull { it.rank ?: 0 } }
        listOfNotNull(above, ranked.firstOrNull { it.symbol == "SOL" }, ranked.firstOrNull { it.symbol == "BTC" })
            .distinctBy { it.id }.filter { (it.marketCap ?: 0.0) > mine }
    }
    val ladder = remember(coin.id, ranked) {
        listOf(2.0, 10.0, 100.0).mapNotNull { m ->
            ranked.filter { it.id != coin.id && (it.marketCap ?: 0.0) > mine }
                .minByOrNull { kotlin.math.abs(kotlin.math.ln((it.marketCap ?: 1.0) / (mine * m))) }
                ?.let { m to it }
        }.distinctBy { it.second.id }
    }

    // Your own choice, searched by name. The search gives names only; the cap
    // comes with one more call when a name is picked.
    var picking by remember(coin.id) { mutableStateOf(false) }
    var query by remember(coin.id) { mutableStateOf("") }
    var found by remember(coin.id) { mutableStateOf<List<Market.Coin>>(emptyList()) }
    var picked by remember(coin.id) { mutableStateOf<List<Market.Coin>>(emptyList()) }
    var fetching by remember(coin.id) { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.trim().length < 2) { found = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(350)
        found = withContext(Dispatchers.IO) { runCatching { Market.search(query) }.getOrDefault(emptyList()) }.filter { it.id != coin.id }.take(6)
    }
    val scope = rememberCoroutineScope()

    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.whatif_title), style = HaloType.label, color = Halo.muted, modifier = Modifier.weight(1f))
        SmallChip(stringResource(R.string.whatif_pick), HIcon.SEARCH, tint = Halo.cyan) { picking = !picking }
    }
    Text(stringResource(R.string.whatif_mine, fx.cap(mine)), style = HaloType.small, color = Halo.muted)

    if (picking) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text(stringResource(R.string.whatif_search_hint), fontFamily = Inter, fontSize = 13.sp, color = Halo.muted) },
            textStyle = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = Halo.ink),
            colors = pickerField(), shape = rs(12),
        )
        if (fetching) Text(stringResource(R.string.w_analyzing), style = HaloType.small, color = Halo.muted)
        found.forEach { f ->
            Row(
                Modifier.fillMaxWidth().clip(rs(10)).clickable {
                    fetching = true
                    scope.launch {
                        val full = withContext(Dispatchers.IO) { runCatching { Market.byId(f.id) }.getOrNull() }
                        fetching = false
                        if (full?.marketCap != null && full.marketCap > 0) {
                            picked = (picked.filter { it.id != full.id } + full).takeLast(4)
                            picking = false; query = ""
                        }
                    }
                }.padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TokenLogo(f.mint ?: f.id, f.symbol, f.image, 22.dp)
                Spacer(Modifier.width(8.dp))
                Text(f.name, fontFamily = Inter, fontSize = 12.5.sp, color = Halo.ink, modifier = Modifier.weight(1f), maxLines = 1)
                Text(f.symbol, fontFamily = Mono, fontSize = 11.sp, color = Halo.muted)
            }
        }
    }

    // The row you touch opens and says the price big: that is the number the
    // whole comparison exists for.
    var openId by remember(coin.id) { mutableStateOf<String?>(null) }

    @Composable
    fun row(t: Market.Coin, label: String?, removable: Boolean) {
        val cap = t.marketCap ?: return
        val mult = cap / mine
        val open = openId == t.id
        val multText = "×" + (if (mult >= 100) mult.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", mult))
        Column(
            Modifier.fillMaxWidth().clip(rs(12)).background(if (open) Halo.cardSoft else androidx.compose.ui.graphics.Color.Transparent)
                .clickable { openId = if (open) null else t.id }.padding(horizontal = if (open) 10.dp else 0.dp, vertical = 6.dp),
        ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TokenLogo(t.mint ?: t.id, t.symbol, t.image, 22.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                label?.let { Text(it, fontFamily = Mono, fontSize = 10.sp, color = Halo.cyan) }
                Text(
                    if (open) stringResource(R.string.whatif_row_open, t.symbol, fx.cap(cap))
                    else stringResource(R.string.whatif_row_mc, t.symbol, fx.cap(cap), fx.price(price * mult)),
                    fontFamily = Inter, fontSize = 12.5.sp, color = Halo.ink, maxLines = 2,
                )
                if (amount > 0 && !open) {
                    Text(stringResource(R.string.whatif_yours, fx.fiat(amount * price * mult)), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.mint)
                }
            }
            Text(multText, fontFamily = Mono, fontSize = 13.sp, color = if (mult >= 1) Halo.cyan else Halo.red)
            if (removable) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(26.dp).clip(rs(999)).clickable { picked = picked.filter { it.id != t.id } }, contentAlignment = Alignment.Center) {
                    HaloIcon(HIcon.CLOSE, Halo.muted, 13.dp, description = stringResource(R.string.a11y_remove))
                }
            }
        }
        if (open) {
            Spacer(Modifier.height(6.dp))
            Text(
                fx.price(price * mult),
                fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Halo.ink,
            )
            Text(
                stringResource(R.string.whatif_open_sub, fx.price(price), multText),
                fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted,
            )
            if (amount > 0) {
                Text(
                    stringResource(R.string.whatif_yours, fx.fiat(amount * price * mult)),
                    fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Halo.mint,
                )
            }
        }
        }
    }

    picked.forEach { row(it, stringResource(R.string.whatif_yours_pick), removable = true) }
    ladder.forEach { (m, t) -> row(t, stringResource(R.string.whatif_step, if (m >= 10) m.toInt().toString() else "2"), removable = false) }
    fixed.filter { f -> ladder.none { it.second.id == f.id } && picked.none { it.id == f.id } }.forEach { row(it, null, removable = false) }
    Text(stringResource(R.string.whatif_note), style = HaloType.small, color = Halo.muted, lineHeight = 15.sp)
}

/** A market cap in three characters and a unit: 1,2 Mld$, 340 M$, 52 k$. */
internal fun fmtCap(v: Double, cur: String = "USD"): String {
    // Il simbolo sta attaccato alla scala quando e' un segno ("128 k$", "128 k€")
    // e staccato quando e' una parola, perche' "128 kSOL" non si legge.
    val sym = runCatching { java.util.Currency.getInstance(cur).symbol }.getOrDefault(cur)
    fun s(scale: String) = if (sym.length > 1) "$scale $sym" else "$scale$sym"
    val l = java.util.Locale.getDefault()
    return when {
        v >= 1e12 -> String.format(l, "%.2f " + s("T"), v / 1e12)
        v >= 1e9 -> String.format(l, "%.1f " + s("Mld"), v / 1e9)
        v >= 1e6 -> String.format(l, "%.0f " + s("M"), v / 1e6)
        v >= 1e3 -> String.format(l, "%.0f " + s("k"), v / 1e3)
        else -> String.format(l, "%.0f " + sym, v)
    }
}

/** A coin we follow by mint but that the ranked page does not carry. */
private fun solanaCoin(mint: String): Market.Coin? {
    val t = JupiterTokens.cached(mint) ?: return null
    return Market.Coin(
        id = mint, symbol = t.symbol, name = t.name, image = t.icon,
        priceUsd = t.usd, marketCap = null, rank = null, change24h = t.change24h, mint = mint,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = HaloType.label, color = Halo.muted, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

/**
 * One row: rank, logo, name, what it costs, how the day went, and the star.
 *
 * The star is the only thing that follows or unfollows. Tapping the row opens the
 * coin, which is where the amount and the buy button live — a row that both
 * navigates and mutates on the same tap is how a list adds things you did not ask
 * for.
 */
@Composable
private fun CoinRow(c: Market.Coin, fx: Fx, followed: Boolean, amount: Double, onOpen: () -> Unit, onStar: () -> Unit) {
    // Contenuta e con la pressione, senza chevron: la stella in coda e' il
    // controllo, e un chevron accanto farebbe a pugni. La riga si tocca lo stesso.
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().tappable(src, rs(Radius.panel), fill = Halo.card, onClick = onOpen).padding(vertical = 8.dp, horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        c.rank?.let {
            Text(it.toString(), style = HaloType.mono, color = Halo.muted, modifier = Modifier.width(26.dp))
        } ?: Spacer(Modifier.width(26.dp))
        TokenLogo(c.mint ?: c.id, c.symbol, c.image, 34.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = HaloType.small.copy(fontWeight = FontWeight.SemiBold), color = Halo.ink, maxLines = 1)
            // Quanto ne hai, e quanto vale adesso. La quantita' da sola non e'
            // la risposta alla domanda per cui uno la scrive: chi digita
            // "millecinquecento" vuole sapere quanto fanno, e il prezzo a
            // destra e' il prezzo di una moneta, non della sua parte. La
            // moltiplicazione e' qui, sulla riga, non solo nel totale in fondo.
            val mine = amount.takeIf { it > 0 }?.let { amt ->
                fmtUi(amt) + " " + c.symbol + (c.priceUsd?.let { " · " + fx.fiat(amt * it) } ?: "")
            }
            val sub = mine ?: (c.symbol + (c.marketCap?.takeIf { it > 0 }?.let { " · " + fx.cap(it) } ?: ""))
            Text(sub, style = HaloType.label.copy(fontWeight = FontWeight.Medium), color = if (mine != null) Halo.mint else Halo.muted, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                c.priceUsd?.let { fx.price(it) } ?: stringResource(R.string.market_no_price),
                style = HaloType.small.copy(fontFamily = Sora, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                color = if (c.priceUsd != null) Halo.ink else Halo.muted,
            )
            c.change24h?.let { ch ->
                Text((if (ch >= 0) "+" else "−") + "%.1f%%".format(kotlin.math.abs(ch)), style = HaloType.mono, color = if (ch >= 0) Halo.mint else Halo.red)
            }
        }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(34.dp).clip(rs(999)).clickable(onClick = onStar), contentAlignment = Alignment.Center) {
            HaloIcon(if (followed) HIcon.STAR_FILLED else HIcon.STAR, if (followed) Halo.amber else Halo.muted, 17.dp, description = stringResource(if (followed) R.string.a11y_unfollow else R.string.a11y_follow))
        }
    }
}

/**
 * One coin, opened: what it costs, how much of it you have, and — only when the
 * coin actually exists on Solana — the way to buy some.
 */
@Composable
private fun CoinSheet(coin: Market.Coin, signer: SeedVaultSigner?, owner: String?, onBuy: (String) -> Unit, onSaved: () -> Unit, onDismiss: () -> Unit) {

    val ctx = LocalContext.current
    val fx = rememberFx()
    var qty by remember(coin.key) { mutableStateOf(Watchlist.amount(ctx, coin.key).takeIf { it > 0 }?.let { fmtUi(it) } ?: "") }
    var mint by remember(coin.key) { mutableStateOf(coin.mint) }
    var looking by remember(coin.key) { mutableStateOf(coin.mint == null) }

    // Only asked when a coin is actually opened, and remembered afterwards.
    LaunchedEffect(coin.id) {
        if (coin.mint != null) return@LaunchedEffect
        mint = withContext(Dispatchers.IO) { runCatching { Market.mintOf(coin.id) }.getOrNull() }
        looking = false
    }

    // Il prezzo, quando la lista non ce l'aveva.
    //
    // La riga arriva senza prezzo ogni volta che il listino e Jupiter tacciono
    // insieme, e da li' in poi la scheda non poteva piu' fare la moltiplicazione:
    // scrivevi quanto ne hai e sotto non compariva niente, che e' l'unica cosa
    // per cui uno quel numero lo scrive. Aperta la scheda, una domanda sola alla
    // fonte giusta - il listino per una moneta di un'altra catena, Jupiter per un
    // mint - e la cifra torna.
    var price by remember(coin.key) { mutableStateOf(coin.priceUsd) }
    LaunchedEffect(coin.key, mint) {
        if (price != null) return@LaunchedEffect
        val m = mint
        price = withContext(Dispatchers.IO) {
            runCatching {
                if (m != null) JupiterTokens.byMints(listOf(m))[m]?.usd else Market.byId(coin.id)?.priceUsd
            }.getOrNull()
        }
    }

    // La stessa moneta col prezzo ritrovato, per tutto quello che sotto ci fa i conti.
    val priced = if (price != null && coin.priceUsd == null) coin.copy(priceUsd = price) else coin

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TokenLogo(coin.mint ?: coin.id, coin.symbol, coin.image, 42.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(coin.name, style = HaloType.title, color = Halo.ink)
                    Text(
                        coin.symbol + (coin.rank?.let { " · #$it" } ?: ""),
                        style = HaloType.small, color = Halo.muted,
                    )
                }
                price?.let {
                    Text(fx.price(it), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Halo.ink)
                }
                Spacer(Modifier.width(10.dp))
                // The star was missing here, so opening a coin you already follow
                // showed nothing that said so: the sheet looked like it had
                // forgotten. It is the same star as the row behind it, and it
                // means the same thing.
                var starred by remember(coin.key) { mutableStateOf(coin.key in Watchlist.all(ctx)) }
                Box(
                    Modifier.size(38.dp).clip(rs(999))
                        .background(if (starred) Halo.amber.copy(alpha = 0.16f) else Halo.cardSoft)
                        .clickable {
                            if (starred) Watchlist.remove(ctx, coin.key) else Watchlist.add(ctx, coin.key)
                            starred = !starred
                            Haptics.tick(ctx); onSaved()
                        },
                    contentAlignment = Alignment.Center,
                ) { HaloIcon(if (starred) HIcon.STAR_FILLED else HIcon.STAR, if (starred) Halo.amber else Halo.muted, 19.dp) }
            }

            Text(stringResource(R.string.market_qty_title), style = HaloType.small, color = Halo.muted, lineHeight = 16.sp)
            OutlinedTextField(
                value = qty,
                onValueChange = { s -> qty = s.filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.') },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("0", fontFamily = Mono, fontSize = 14.sp, color = Halo.muted) },
                textStyle = TextStyle(fontFamily = Mono, fontSize = 16.sp, color = Halo.ink),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                colors = pickerField(), shape = rs(12),
            )
            val worth = (qty.toDoubleOrNull() ?: 0.0) * (price ?: 0.0)
            if (worth > 0) {
                Text(stringResource(R.string.market_qty_worth, fx.fiat(worth)), style = HaloType.body, color = Halo.mint)
            }

            PrimaryButton(stringResource(R.string.market_qty_save), danger = false) {
                Watchlist.add(ctx, coin.key)
                Watchlist.setAmount(ctx, coin.key, qty.toDoubleOrNull() ?: 0.0)
                onSaved(); onDismiss()
            }

            // The bell: a notification when it moves five percent, either way,
            // measured from the last time it rang. Like CoinGecko's, without the account.
            var moves by remember(coin.key) { mutableStateOf(Watchlist.moves(ctx, coin.key)) }
            Row(
                Modifier.fillMaxWidth().clip(rs(12)).clickable {
                    moves = !moves; Watchlist.setMoves(ctx, coin.key, moves); OrdersKeeper.sync(ctx); Haptics.tick(ctx); onSaved()
                }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HaloIcon(HIcon.WARNING, if (moves) Halo.amber else Halo.muted, 16.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.market_moves_title), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Halo.ink)
                    Text(stringResource(if (moves) R.string.market_moves_on else R.string.market_moves_off), style = HaloType.small, color = Halo.muted)
                }
                androidx.compose.material3.Switch(checked = moves, onCheckedChange = { on -> moves = on; Watchlist.setMoves(ctx, coin.key, on); OrdersKeeper.sync(ctx); onSaved() })
            }

            // The shape of the price. A coin that lives on Solana is drawn from
            // its busiest pool, with the depth under it; everything else is drawn
            // from the market, so bitcoin has a chart here too instead of a gap.
            CoinChart(priced, mint)
            // Who can do what to the coin. Only a mint has an authority to check.
            mint?.let { ShieldCard(it, coin.symbol) }

            // What if it were as big as something else. Arithmetic, not a forecast.
            WhatIf(priced, qty.toDoubleOrNull() ?: 0.0, fx)

            // Buy it cheaper, buy it a slice at a time, or be told: all three need
            // the mint and a price, and the first two need the Seed Vault.
            val m = mint
            if (m != null && price != null) {
                var limit by remember { mutableStateOf(false) }
                var dca by remember { mutableStateOf(false) }
                var alert by remember { mutableStateOf(false) }
                var decimals by remember(m) { mutableStateOf<Int?>(null) }
                LaunchedEffect(m) { decimals = withContext(Dispatchers.IO) { runCatching { JupiterTokens.byMints(listOf(m))[m]?.decimals }.getOrNull() } }
                val oc = OrderCoin(m, coin.symbol, decimals ?: 6, coin.image, price)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (signer != null && owner != null) {
                        SmallChip(stringResource(R.string.order_limit), HIcon.HOURGLASS, tint = Halo.cyan) { limit = true }
                        SmallChip(stringResource(R.string.order_dca), HIcon.HISTORY, tint = Halo.cyan) { dca = true }
                    }
                    SmallChip(stringResource(R.string.order_alert), HIcon.WARNING, tint = Halo.amber) { alert = true }
                }
                if (limit && signer != null && owner != null) LimitBuySheet(oc, signer, owner, onDone = { limit = false; onSaved() }) { limit = false }
                if (dca && signer != null && owner != null) DcaSheet(oc, signer, owner, onDone = { dca = false; onSaved() }) { dca = false }
                if (alert) AlertSheet(oc, onDone = { alert = false; onSaved() }) { alert = false }
            }

            when {
                looking -> Text(stringResource(R.string.market_checking_chain), style = HaloType.small, color = Halo.muted)
                mint != null -> GhostButton(stringResource(R.string.market_buy, coin.symbol), Modifier.fillMaxWidth(), HIcon.SWAP, tint = Halo.mint) {
                    onBuy(mint!!)
                }
                // Not on Solana as itself, but here as an official bridged coin:
                // the same asset, and a way to buy it, said which.
                Market.bridged[coin.id] != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.market_bridged_title, coin.symbol), style = HaloType.label, color = Halo.muted)
                    Market.bridged[coin.id]!!.forEach { b ->
                        GhostButton(stringResource(R.string.market_buy, b.label), Modifier.fillMaxWidth(), HIcon.SWAP, tint = Halo.mint) { onBuy(b.mint) }
                    }
                    Text(stringResource(R.string.market_bridged_note), style = HaloType.small, color = Halo.muted, lineHeight = 16.sp)
                }
                // Said once, plainly, instead of a button that cannot work.
                else -> Text(stringResource(R.string.market_not_on_solana), style = HaloType.small, color = Halo.muted, lineHeight = 16.sp)
            }
        }
    }
}

/** In che stato e' la lista del mercato. Pura, per il test. */
internal enum class MarketState { LOADING, DOWN, LIST, SEARCHING, NO_RESULTS }

internal fun marketState(loading: Boolean, rankedEmpty: Boolean, query: String, searching: Boolean, shownEmpty: Boolean): MarketState = when {
    query.length >= 2 && searching -> MarketState.SEARCHING
    query.length >= 2 && shownEmpty -> MarketState.NO_RESULTS
    query.length >= 2 -> MarketState.LIST
    loading && rankedEmpty -> MarketState.LOADING
    rankedEmpty -> MarketState.DOWN
    else -> MarketState.LIST
}

/** Una riga vuota con la forma di una riga: la pagina ha la sua altezza mentre il listino arriva. */
@Composable
private fun PlaceholderRow() {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(26.dp))
        Box(Modifier.size(34.dp).clip(rs(999)).background(Halo.cardSoft))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth(0.45f).height(12.dp).clip(rs(6)).background(Halo.cardSoft))
            Box(Modifier.fillMaxWidth(0.25f).height(9.dp).clip(rs(6)).background(Halo.cardSoft))
        }
        Box(Modifier.width(56.dp).height(12.dp).clip(rs(6)).background(Halo.cardSoft))
    }
}
