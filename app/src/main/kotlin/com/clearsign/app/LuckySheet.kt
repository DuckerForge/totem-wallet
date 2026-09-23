@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The safest two or three coins on Solana today, bought in one gesture. The loop hunts
 * runners; this is the other kind of buy, a few coins picked because they are solid, the
 * money split evenly, no stop-loss watching minute by minute ([com.clearsign.core.safestPicks]
 * ranks them without looking at the price). It spends the budget, never the Seed Vault, and
 * every purchase goes through [AgentBroker] like the loop's: same collar, same simulation.
 */
@Composable
internal fun LuckySheet(onDone: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { SessionWallet.current(ctx) }
    val policy = remember { SessionWallet.policy(ctx) }

    var free by remember { mutableStateOf<Long?>(null) }
    var picks by remember { mutableStateOf<List<com.clearsign.core.Scored>?>(null) }
    var count by remember { mutableFloatStateOf(3f) }
    var each by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }
    var bold by remember { mutableStateOf(false) }

    LaunchedEffect(session?.pubkey) {
        val pub = session?.pubkey ?: return@LaunchedEffect
        free = withContext(Dispatchers.IO) { runCatching { SolanaRpc.getBalance(SolanaRpc.urlFor(null), pub) }.getOrNull() }
        // A quarter of what is free, per coin, as a starting point that cannot
        // empty the budget on the first tap.
        each = ((free ?: 0L) / 4).toFloat()
    }

    LaunchedEffect(bold) {
        picks = null
        val gate = com.clearsign.core.ScanGate.CAREFUL
        val pool = withContext(Dispatchers.IO) { runCatching { JupiterTokens.pool() }.getOrDefault(emptyList()) }
        picks = com.clearsign.core.safestPicks(pool, gate, limit = 3)
    }

    val n = count.toInt().coerceIn(1, 3)
    val slice = each.toLong()
    // The collar's silent threshold is the real ceiling here: above it every buy
    // would stop and wait for a fingerprint, which is not what a one-tap button is.
    val ceiling = policy?.let { minOf(it.perTxLamports, it.askAboveLamports.takeIf { v -> v > 0 } ?: it.perTxLamports) } ?: 0L
    val tooBig = slice > ceiling
    val enough = (free ?: 0L) >= slice * n + 6_000_000L

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.lucky_title), style = HaloType.title, color = Halo.ink)
            Text(stringResource(R.string.lucky_sub), style = HaloType.small, color = Halo.muted, lineHeight = 17.sp)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // One lane now; the chips that chose between two are gone.
            }

            when (val p = picks) {
                null -> Text(stringResource(R.string.lucky_looking), style = HaloType.small, color = Halo.muted)
                else -> {
                    if (p.isEmpty()) {
                        Text(stringResource(R.string.lucky_none), style = HaloType.small, color = Halo.amber, lineHeight = 17.sp)
                    }
                    p.take(n).forEach { s -> PickRow(s) }
                }
            }

            val pickCount = picks?.size ?: 0
            if (pickCount > 1) {
                SliderRow(stringResource(R.string.lucky_how_many), n.toString(), count, 1f..minOf(3, pickCount).toFloat(), Halo.cyan, steps = maxOf(0, minOf(3, pickCount) - 2)) { count = it }
            }
            free?.let { f ->
                SliderRow(
                    stringResource(R.string.lucky_each), fmtSol(slice, 4) + " SOL",
                    each, 0f..(f / 2).toFloat(), if (tooBig) Halo.amber else Halo.mint,
                ) { each = it }
                Text(
                    stringResource(R.string.lucky_total, fmtSol(slice * n, 4), fmtSol(f, 4)),
                    style = HaloType.small, color = Halo.muted,
                )
            }
            if (tooBig) {
                Text(stringResource(R.string.lucky_too_big, fmtSol(ceiling, 4)), style = HaloType.small, color = Halo.amber, lineHeight = 16.sp)
            }

            said?.let { Text(it, style = HaloType.body, color = Halo.mint, lineHeight = 18.sp) }

            PrimaryButton(
                if (busy) stringResource(R.string.lucky_buying) else stringResource(R.string.lucky_go, n),
                danger = false,
                enabled = !busy && !tooBig && enough && slice > 0 && (picks?.isNotEmpty() == true),
            ) {
                val chosen = picks?.take(n).orEmpty()
                val pub = session?.pubkey ?: return@PrimaryButton
                busy = true; said = null
                scope.launch {
                    val bought = ArrayList<String>()
                    val failed = ArrayList<String>()
                    for (s in chosen) {
                        val ok = buyOne(ctx, pub, s.c, slice)
                        if (ok) bought += s.c.symbol else failed += s.c.symbol
                        // The same breath the sell-everything loop needs: Jupiter
                        // rate-limits a burst from one client.
                        kotlinx.coroutines.delay(900)
                    }
                    busy = false
                    said = when {
                        bought.isEmpty() -> ctx.getString(R.string.lucky_none_bought, failed.joinToString(", "))
                        failed.isEmpty() -> ctx.getString(R.string.lucky_done, bought.joinToString(", "))
                        else -> ctx.getString(R.string.lucky_partial, bought.joinToString(", "), failed.joinToString(", "))
                    }
                    onDone()
                }
            }
            Text(stringResource(R.string.lucky_note), style = HaloType.small, color = Halo.muted, lineHeight = 16.sp)
        }
    }
}

/** One candidate, with the numbers that earned it the place. */
@Composable
private fun PickRow(s: com.clearsign.core.Scored) {
    val c = s.c
    Row(
        Modifier.fillMaxWidth().clip(rs(12)).background(Halo.cardSoft).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TokenLogo(c.mint, c.symbol, JupiterTokens.cached(c.mint)?.icon, 34.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c.symbol, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = Halo.ink)
            Text(
                s.notes.take(2).joinToString(" · ").ifEmpty { c.name },
                fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, maxLines = 2,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("$" + fmtCompact(c.liquidity), fontFamily = Mono, fontSize = 11.5.sp, color = Halo.mint)
            Text(stringResource(R.string.lucky_liquidity), fontFamily = Inter, fontSize = 9.5.sp, color = Halo.muted)
        }
    }
}

private fun fmtCompact(v: Double): String = when {
    v >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fM", v / 1_000_000)
    v >= 1_000 -> String.format(java.util.Locale.ROOT, "%.0fk", v / 1_000)
    else -> v.toInt().toString()
}

/**
 * Buy one, through the same door as everything else: the collar simulates, judges, signs or
 * refuses. A button bypassing that would be a second way to spend money, and this wallet has one.
 */
private suspend fun buyOne(ctx: android.content.Context, owner: String, c: com.clearsign.core.Candidate, slice: Long): Boolean {
    val quote = withContext(Dispatchers.IO) {
        runCatching { Jupiter.quote(Jupiter.SOL_MINT, c.mint, slice, feeBps = 0) }.getOrNull()
    } ?: return false
    val tx = withContext(Dispatchers.IO) { runCatching { Jupiter.swapTransaction(quote, owner, null) }.getOrNull() } ?: return false
    val intent = JSONObject()
        .put("action", "swap").put("outMint", "SOL").put("outAmount", slice / 1e9)
        .put("inMint", c.symbol).put("inAmount", quote.outAmount / Math.pow(10.0, c.decimals.toDouble()))
        .put("expectMint", c.mint)
        .put("agent", TraderLoop.AGENT)
        .put("reason", ctx.getString(R.string.lucky_reason, c.symbol))
    val v = AgentBroker.handle(
        ctx,
        AgentBroker.Job(
            id = LedgerRecorder.newId(), tx = tx, intentJson = intent.toString(),
            cluster = null, agent = TraderLoop.AGENT, source = AgentBroker.Job.Source.IN_APP,
        ),
    )
    return v is AgentBroker.Verdict.SignedSilently || v is AgentBroker.Verdict.Confirmed
}
