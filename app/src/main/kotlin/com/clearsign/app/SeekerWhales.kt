package com.clearsign.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.pow

/**
 * The richest wallets holding this same phone.
 *
 * Measured, not guessed: every Seeker Genesis Token on chain, resolved to its
 * wallet, then priced. 1,252 of the 120,520 hold ten SOL or more, and between
 * them they hold fifteen million dollars. The top one holds two.
 *
 * Addresses and nothing else. This says what the crowd is worth, never who
 * anybody is, and the value shown is a census snapshot rather than a live feed:
 * refreshing sixty balances every time somebody opens a card is other people's
 * money spent on decoration.
 */
private class Whale(val address: String, val usd: Long, val sol: Double)

private fun whales(ctx: Context): Triple<List<Whale>, Int, Long> = runCatching {
    val o = JSONObject(ctx.assets.open("seeker_whales.json").bufferedReader().use { it.readText() })
    val a = o.getJSONArray("rows")
    Triple(
        (0 until a.length()).map { i ->
            val r = a.getJSONObject(i)
            Whale(r.getString("a"), r.optLong("u"), r.optDouble("s"))
        },
        o.optInt("whales"), o.optLong("totalUsd"),
    )
}.getOrElse { Triple(emptyList(), 0, 0L) }

@Composable
internal fun SeekerWhalesCard(limit: Int = 8) {
    val ctx = LocalContext.current
    val (rows, count, total) = remember { whales(ctx) }
    if (rows.isEmpty()) { GlassCard { NothingHere(stringResource(R.string.crowd_no_data)) }; return }

    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HaloIcon(HIcon.GEM, Halo.amber, 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.whales_title),
                    fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = Halo.amber, modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.whales_count, count),
                    fontFamily = Mono, fontSize = 11.sp, color = Halo.muted, style = Tabular,
                )
            }
            Text(
                stringResource(R.string.whales_total, money(total)),
                fontFamily = Inter, fontSize = 12.sp, color = Halo.muted, lineHeight = 17.sp,
            )
            // One open at a time: two expanded rows is a list that has stopped
            // being a ranking.
            // Survives the switch to another Scout tab: reopening a whale you
            // had already opened should not cost the same three calls again.
            var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
            rows.take(limit).forEachIndexed { i, w ->
                WhaleRow(i + 1, w, open == w.address) { open = if (open == w.address) null else w.address }
            }
        }
    }
}

/* One coin a whale is sitting on, and what that pile is worth. */
internal class Held(val mint: String, val symbol: String, val amount: Double, val usd: Double)

/* The three that hold the money, and how many things were left out for having no price. */
internal class Holdings(val top: List<Held>, val unpriced: Int)

/*
 * What one wallet actually holds, read when somebody asks and not before.
 *
 * The census stores a total in dollars and nothing else, so the fourth whale read
 * "$245,846 · 13 SOL" and left out the only interesting part: nine tenths of it is
 * one memecoin. Finding that out meant leaving for Solscan.
 *
 * **Only what somebody quotes.** The unpriced tail is not a shy version of the
 * same thing, it is where the counterfeits live: this wallet holds three separate
 * mints that the registry calls "USDC", and two of them are fakes with no price
 * and a couple of dollars of liquidity. Listed by name they would have read as
 * three USDC holdings. A coin nobody quotes also answers nothing about where a
 * whale's money is, which is the only question this card asks.
 *
 * On the scanner's key, never the agent's, and never for all sixty at once: the
 * card still refuses to reprice a list nobody is looking at.
 */
/**
 * What one wallet is holding right now, priced, read by the service and shared.
 *
 * It used to be read by the phone, with the scanner key compiled into the APK.
 * Two mistakes in one: the quota was spent by users one at a time, and the key
 * travelled inside the application where anybody can pull it out. The crowd scan
 * has not made that mistake for months and this is the same shape: one reads,
 * everybody gets the same answer, and a thousand people opening the same whale
 * is one read. The phone falls back to its own key only when no service is
 * configured, which is a build for one person.
 */
internal fun holdingsOf(address: String, take: Int = 3): Holdings? {
    shared(address, take)?.let { return it }
    val rpc = BuildConfig.SCAN_RPC_URL
    if (rpc.isBlank()) return null
    val accounts = runCatching { SolanaRpc.tokensOf(rpc, address) }.getOrNull() ?: return null
    // One coin can sit in more than one account, and two halves of a holding shown
    // as two rows would read as two different coins.
    val amounts = HashMap<String, Double>()
    for (a in accounts) {
        if (a.amount <= 0L) continue
        amounts[a.mint] = (amounts[a.mint] ?: 0.0) + a.amount / 10.0.pow(a.decimals)
    }
    if (amounts.isEmpty()) return Holdings(emptyList(), 0)
    runCatching { JupiterTokens.warm(amounts.keys) }
    val px = runCatching { Prices.usd(amounts.keys) }.getOrDefault(emptyMap())
    val priced = amounts.mapNotNull { (mint, amount) ->
        val usd = px[mint]?.times(amount)?.takeIf { it >= 1 } ?: return@mapNotNull null
        Held(mint, JupiterTokens.cached(mint)?.symbol ?: mint.take(4), amount, usd)
    }.sortedByDescending { it.usd }
    return Holdings(priced.take(take), amounts.size - priced.size)
}

@Composable
private fun WhaleRow(rank: Int, w: Whale, open: Boolean, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    var held by remember(w.address) { mutableStateOf(heldMemo[w.address]) }
    var failed by remember(w.address) { mutableStateOf(false) }

    // Read once per wallet, on the first open. Closing and opening again costs
    // nothing, and a list of sixty costs nothing until one of them is touched.
    LaunchedEffect(open, w.address) {
        if (!open || held != null || failed) return@LaunchedEffect
        val got = withContext(Dispatchers.IO) { holdingsOf(w.address) }
        if (got == null) failed = true else { held = got; heldMemo[w.address] = got }
    }

    Column(
        Modifier.fillMaxWidth().clip(rs(Radius.row)).background(Halo.cardSoft)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$rank", fontFamily = Mono, fontSize = 12.sp, style = Tabular,
                color = if (rank <= 3) Halo.amber else Halo.muted, modifier = Modifier.width(22.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    "${w.address.take(4)}…${w.address.takeLast(4)}",
                    fontFamily = Mono, fontSize = 12.5.sp, color = Halo.ink, style = Tabular,
                )
                Text(
                    stringResource(R.string.whales_sol, fmt(w.sol)),
                    fontFamily = Inter, fontSize = 11.sp, color = Halo.muted,
                )
            }
            Text(
                money(w.usd), fontFamily = Mono, fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold, color = Halo.mint, style = Tabular,
            )
            Spacer(Modifier.width(6.dp))
            HaloIcon(
                HIcon.CHEVRON_DOWN, Halo.muted, 14.dp,
                Modifier.rotate(if (open) 180f else 0f),
            )
        }

        if (open) {
            val h = held
            when {
                failed -> Note(stringResource(R.string.whale_failed))
                h == null -> Note(stringResource(R.string.whale_loading))
                h.top.isNotEmpty() -> {
                    Text(
                        stringResource(R.string.whale_holds),
                        fontFamily = Inter, fontSize = 10.5.sp, color = Halo.muted,
                    )
                    h.top.forEach { HeldRow(it, w.usd) }
                }
                // Nothing quoted is a different answer from nothing held, and the
                // difference is the one worth saying out loud.
                h.unpriced > 0 -> Note(stringResource(R.string.whale_unpriced, h.unpriced))
                else -> Note(stringResource(R.string.whale_empty))
            }
            // Follow: what this wallet buys becomes a candidate for the agent.
            var followed by remember(w.address) { mutableStateOf(Follows.has(ctx, w.address)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallChip(
                    stringResource(if (followed) R.string.follow_on else R.string.follow_btn),
                    if (followed) HIcon.STAR_FILLED else HIcon.STAR, tint = Halo.amber,
                ) { followed = Follows.toggle(ctx, w.address); Haptics.tick(ctx) }
            }
            // Solscan keeps its place, on a target of its own. The row itself now
            // has a job, and one tap cannot do two things.
            Row(
                Modifier.fillMaxWidth().clip(rs(Radius.row))
                    .clickable {
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://solscan.io/account/${w.address}")),
                            )
                        }
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.whale_open),
                    fontFamily = Inter, fontSize = 11.sp, color = Halo.cyan,
                )
                Spacer(Modifier.width(5.dp))
                HaloIcon(HIcon.EXTERNAL, Halo.cyan, 12.dp)
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, lineHeight = 15.sp)
}

/** One held coin: what it is, how much of it, what it is worth, and how much of this wallet it is. */
@Composable
private fun HeldRow(h: Held, walletUsd: Long) {
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TokenLogo(h.mint, h.symbol, JupiterTokens.cached(h.mint)?.icon, 20.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                h.symbol, fontFamily = Sora, fontWeight = FontWeight.Bold,
                fontSize = 12.sp, color = Halo.ink,
            )
            Text(
                amt(h.amount), fontFamily = Mono, fontSize = 10.5.sp,
                color = Halo.muted, style = Tabular,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                money(h.usd.toLong()), fontFamily = Mono, fontSize = 11.5.sp,
                color = Halo.mint, style = Tabular,
            )
            if (walletUsd > 0) {
                Text(
                    stringResource(R.string.whale_share, pct(h.usd / walletUsd * 100)),
                    fontFamily = Inter, fontSize = 10.sp, color = Halo.muted,
                )
            }
        }
    }
}

/** Millions stay millions: a memecoin holding written out in full is thirteen digits nobody reads. */
private fun amt(v: Double): String = when {
    v >= 1_000_000_000 -> String.format("%.1fB", v / 1_000_000_000)
    v >= 1_000_000 -> String.format("%.1fM", v / 1_000_000)
    v >= 1_000 -> String.format("%.1fk", v / 1_000)
    v >= 1 -> String.format("%.0f", v)
    else -> String.format("%.3f", v)
}

private fun pct(v: Double): String = if (v >= 10) String.format("%.0f", v) else String.format("%.1f", v)

/** Thousands separated, no decimals: at these sizes cents are noise. */
private fun money(v: Long): String =
    "$" + v.toString().reversed().chunked(3).joinToString(".").reversed()

private fun fmt(v: Double): String = when {
    v >= 1000 -> String.format("%,.0f", v)
    v >= 10 -> String.format("%.0f", v)
    else -> String.format("%.1f", v)
}

/** What each whale holds, kept for as long as the app lives: one wallet, one read. */
private val heldMemo = java.util.concurrent.ConcurrentHashMap<String, Holdings>()

/**
 * The service's answer, already priced. Null when there is no service or it did not answer.
 *
 * Two doors, and the cheap one first. The archive is a plain file on a shared
 * store, readable by anybody, with no key and no worker woken up: whoever opened
 * this wallet before today left the answer there for everyone. Only when the
 * archive has nothing, or has something half a day old, do we knock on the
 * service, which reads the chain and refills the archive for the next person.
 *
 * That ordering is the whole point. A cache only pays when people arrive
 * together, and they do not: half of them open the app in the morning and half
 * at night. An archive does not care what time it is.
 */
private fun shared(address: String, take: Int): Holdings? {
    val stored = BuildConfig.ARCHIVE_URL.takeIf { it.isNotBlank() }?.let { base ->
        get(base.trimEnd('/') + "/clearsign/holdings/" + address + ".json")
    }
    // Half a day old is still worth showing. Older than that and we would rather
    // wait for the service, which rereads the chain while it answers.
    if (stored != null && ageOf(stored) < 12 * 3600_000L) parse(stored, take)?.let { return it }

    val base = BuildConfig.CROWD_URL.takeIf { it.isNotBlank() } ?: return parse(stored ?: return null, take)
    val body = get(base.trimEnd('/') + "/?w=" + address) ?: return stored?.let { parse(it, take) }
    return parse(body, take)
}

private fun get(url: String): String? = runCatching {
    val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection)
        .apply { connectTimeout = 6_000; readTimeout = 12_000; setRequestProperty("Accept", "application/json") }
    if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
}.getOrNull()?.takeIf { it.isNotBlank() && it.trim() != "null" }

/** How old the answer is, by the clock written inside it. Forever, when it cannot be read. */
private fun ageOf(body: String): Long = runCatching {
    System.currentTimeMillis() - org.json.JSONObject(body).optLong("at")
}.getOrDefault(Long.MAX_VALUE)

private fun parse(body: String, take: Int): Holdings? = runCatching {
    val o = org.json.JSONObject(body)
    val arr = o.optJSONArray("top") ?: return null
    val list = ArrayList<Held>(arr.length())
    for (i in 0 until arr.length()) {
        val r = arr.optJSONObject(i) ?: continue
        val mint = r.optString("m").takeIf { it.isNotEmpty() } ?: continue
        list += Held(mint, JupiterTokens.cached(mint)?.symbol ?: mint.take(4), r.optDouble("q"), r.optDouble("u"))
    }
    runCatching { JupiterTokens.warm(list.map { it.mint }) }
    Holdings(list.take(take), o.optInt("unpriced"))
}.getOrNull()
