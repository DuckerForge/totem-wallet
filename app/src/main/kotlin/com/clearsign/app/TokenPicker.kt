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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.pow

/**
 * One coin as the picker shows it: what it is called, what it looks like, and —
 * when the wallet holds it — how much of it there is.
 */
internal data class PickToken(
    val mint: String,
    val symbol: String,
    val name: String,
    val icon: String?,
    val decimals: Int,
    val balance: Long? = null,
    val fiat: Double? = null,
    val usd: Double? = null,
    val verified: Boolean = false,
) {
    companion object {
        fun of(t: JupiterTokens.Tok, balance: Long? = null, fiat: Double? = null) =
            PickToken(t.mint, t.symbol, t.name, t.icon, t.decimals, balance, fiat, t.usd, t.verified)

        fun of(h: Holding) = PickToken(
            h.mint, h.symbol, h.name ?: h.symbol, h.image, h.decimals, h.raw, h.fiat,
            JupiterTokens.cached(h.mint)?.usd, JupiterTokens.cached(h.mint)?.verified ?: TokenSymbols.isKnown(h.mint),
        )
    }
}

/**
 * The coin list, the way a wallet is expected to do it: what you hold first, then
 * what is popular, then live search over Jupiter's whole registry — by name, by
 * symbol, or by pasting a mint address.
 *
 * It fills whatever space the caller gives it, so it can live inside another
 * sheet instead of stacking a second modal on top of one.
 */
@Composable
internal fun TokenPicker(
    title: String,
    owned: List<PickToken>,
    currency: String,
    onPick: (PickToken) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var popular by remember { mutableStateOf<List<PickToken>>(emptyList()) }
    var found by remember { mutableStateOf<List<PickToken>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        popular = withContext(Dispatchers.IO) { runCatching { JupiterTokens.top(ctx) }.getOrDefault(emptyList()) }
            .map { PickToken.of(it) }
    }

    val q = query.trim()
    LaunchedEffect(q) {
        found = emptyList()
        if (q.length < 2) { searching = false; return@LaunchedEffect }
        delay(300)
        searching = true
        found = withContext(Dispatchers.IO) { runCatching { JupiterTokens.search(q) }.getOrDefault(emptyList()) }
            .map { t -> PickToken.of(t, owned.firstOrNull { it.mint == t.mint }?.balance) }
        searching = false
    }

    val mine = if (q.isEmpty()) owned else owned.filter { it.matches(q) }
    val hot = (if (q.isEmpty()) popular else emptyList()).filter { p -> mine.none { it.mint == p.mint } }
    val hits = found.filter { h -> mine.none { it.mint == h.mint } }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(rs(999)).background(Halo.card).border(cardBorder(), rs(999)).clickable { onClose() }, contentAlignment = Alignment.Center) {
                HaloIcon(HIcon.CHEVRON_LEFT, Halo.ink, 18.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Halo.ink)
                Text(stringResource(R.string.tok_title), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted)
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), singleLine = true,
            placeholder = { Text(stringResource(R.string.tok_search), fontFamily = Inter, fontSize = 13.sp, color = Halo.muted) },
            leadingIcon = { HaloIcon(HIcon.SEARCH, Halo.muted, 18.dp) },
            trailingIcon = {
                if (query.isNotEmpty()) Box(Modifier.clip(rs(999)).clickable { query = "" }.padding(6.dp)) { HaloIcon(HIcon.CLOSE, Halo.muted, 16.dp) }
            },
            textStyle = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = Halo.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = pickerField(), shape = rs(14),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (mine.isNotEmpty()) {
                item { GroupLabel(stringResource(R.string.tok_yours)) }
                items(mine, key = { "own-" + it.mint }) { t -> TokenLine(t, currency, onPick) }
            }
            if (hot.isNotEmpty()) {
                item { GroupLabel(stringResource(R.string.tok_popular)) }
                items(hot, key = { "hot-" + it.mint }) { t -> TokenLine(t, currency, onPick) }
            }
            if (q.length >= 2) {
                item { GroupLabel(stringResource(if (searching) R.string.tok_searching else R.string.tok_results)) }
                items(hits, key = { "hit-" + it.mint }) { t -> TokenLine(t, currency, onPick) }
                if (!searching && hits.isEmpty() && mine.isEmpty()) {
                    item { Text(stringResource(R.string.tok_none), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted, modifier = Modifier.padding(vertical = 10.dp)) }
                }
            } else if (popular.isEmpty() && mine.isEmpty()) {
                item { Text(stringResource(R.string.tok_loading), fontFamily = Inter, fontSize = 12.sp, color = Halo.muted, modifier = Modifier.padding(vertical = 10.dp)) }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

private fun PickToken.matches(q: String): Boolean {
    val n = q.lowercase()
    return symbol.lowercase().contains(n) || name.lowercase().contains(n) || mint.lowercase().startsWith(n)
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(), fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 10.sp,
        color = Halo.muted, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun TokenLine(t: PickToken, currency: String, onPick: (PickToken) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(rs(12)).clickable { onPick(t) }.padding(vertical = 7.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TokenLogo(t.mint, t.symbol, t.icon, 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.name, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = Halo.ink, maxLines = 1)
                if (t.verified) {
                    Spacer(Modifier.width(5.dp))
                    HaloIcon(HIcon.CHECK, Halo.mint, 13.dp)
                }
                // What the registry already told us about the coin, as one dot. The
                // expensive half of the grade (is there a way back out?) waits until
                // the coin is actually chosen.
                val safety = remember(t.mint) { JupiterTokens.cached(t.mint)?.facts()?.let { com.clearsign.core.assessToken(it) } }
                if (safety != null && safety.band != com.clearsign.core.SafetyBand.GOOD) {
                    Spacer(Modifier.width(6.dp))
                    SafetyDot(safety)
                }
            }
            Text(t.symbol, fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            val bal = t.balance
            if (bal != null && bal > 0) {
                Text(fmtUi(bal / 10.0.pow(t.decimals)), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Halo.ink, style = Tabular)
                Text(t.fiat?.let { fmtFiat(it, currency) } ?: t.usd?.let { "$" + fmtUi(it) } ?: "", fontFamily = Inter, fontSize = 10.5.sp, color = Halo.muted, style = Tabular)
            } else {
                t.usd?.let { Text("$" + fmtUi(it), fontFamily = Inter, fontSize = 11.5.sp, color = Halo.muted, style = Tabular) }
            }
        }
    }
}

@Composable
internal fun pickerField() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Halo.stroke, unfocusedBorderColor = Halo.stroke.copy(alpha = 0.7f),
    focusedContainerColor = Halo.cardSoft, unfocusedContainerColor = Halo.cardSoft,
    cursorColor = Halo.mint, focusedTextColor = Halo.ink, unfocusedTextColor = Halo.ink,
    focusedPlaceholderColor = Halo.muted, unfocusedPlaceholderColor = Halo.muted,
    focusedLeadingIconColor = Color.Unspecified, unfocusedLeadingIconColor = Color.Unspecified,
)
