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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * The chains: eight in front, the rest behind a search. The first version put all two
 * hundred and seven pills on the page: not a choice, a wall, and you end up picking whatever
 * is under your thumb (live: EVMOS EVM NETWORK, which nobody wanted), in RocketX's order,
 * Robinhood Chain third. Here [RocketX.POPULAR] in front, the rest alphabetical with a
 * search: alphabetical is the one order where you already know where to look.
 */
@Composable
internal fun ChainPickerSheet(
    all: List<RocketX.Network>,
    selected: RocketX.Network?,
    onPick: (RocketX.Network) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    val popular = remember(all) { RocketX.popular(all) }
    val rest = remember(all, popular) { (all - popular.toSet()).sortedBy { it.name.lowercase(Locale.ROOT) } }
    val q = query.trim().lowercase(Locale.ROOT)
    // While searching, the split between the usual and the rest is gone: whoever
    // types three letters wants one list of things containing them.
    val hits = remember(q, all) {
        if (q.isEmpty()) emptyList()
        else (popular + rest).filter { it.name.lowercase(Locale.ROOT).contains(q) || it.native.lowercase(Locale.ROOT).contains(q) || it.short.lowercase(Locale.ROOT).contains(q) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetHeader(stringResource(R.string.chain_pick_title), stringResource(R.string.chain_pick_sub, all.size), HIcon.BRIDGE, onClose = onDismiss)
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text(stringResource(R.string.chain_pick_search), fontFamily = Inter, fontSize = 13.sp, color = Halo.muted) },
                    leadingIcon = { HaloIcon(HIcon.SEARCH, Halo.muted, 18.dp) },
                    trailingIcon = {
                        if (query.isNotEmpty()) Box(Modifier.clip(rs(999)).clickable { query = "" }.padding(6.dp)) { HaloIcon(HIcon.CLOSE, Halo.muted, 16.dp, description = stringResource(R.string.a11y_clear_search)) }
                    },
                    textStyle = TextStyle(fontFamily = Inter, fontSize = 14.sp, color = Halo.ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = pickerField(), shape = rs(14),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
                if (q.isNotEmpty()) {
                    if (hits.isEmpty()) item { Text(stringResource(R.string.chain_pick_none), style = HaloType.small, color = Halo.muted, modifier = Modifier.padding(vertical = 12.dp)) }
                    items(hits) { n -> ChainRow(n, n.id == selected?.id) { onPick(n); onDismiss() } }
                } else {
                    item { SectionLabel(stringResource(R.string.chain_pick_popular)) }
                    items(popular) { n -> ChainRow(n, n.id == selected?.id) { onPick(n); onDismiss() } }
                    item { SectionLabel(stringResource(R.string.chain_pick_rest)) }
                    items(rest) { n -> ChainRow(n, n.id == selected?.id) { onPick(n); onDismiss() } }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = HaloType.label, color = Halo.muted, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

/** One row: the name as the people living there call it, and the coin you pay with up there. */
@Composable
private fun ChainRow(n: RocketX.Network, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(rs(12)).background(if (on) Halo.mint.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (on) Halo.mint else androidx.compose.ui.graphics.Color.Transparent, rs(12))
            .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(chainLabel(n), fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (on) Halo.mint else Halo.ink, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(n.native, fontFamily = Mono, fontSize = 11.5.sp, color = Halo.muted)
    }
}

/**
 * "BITCOIN Network" and "APTOS MAINNET" are database row names: all caps on a long list
 * shouts, and "Network", "Chain" and "Mainnet" repeat on half the entries and distinguish nothing.
 */
internal fun chainLabel(n: RocketX.Network): String {
    val bare = n.name
        .replace(Regex("(?i)\\s+(network|chain|mainnet)$"), "")
        .trim()
        .ifEmpty { n.name }
    return if (bare == bare.uppercase()) {
        bare.split(' ').joinToString(" ") { w -> w.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() } }
    } else {
        bare
    }
}
