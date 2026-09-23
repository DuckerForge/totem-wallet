@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.clearsign.app

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Everything this wallet is connected to, and the way to end it. Two things it keeps saying
 * because other wallets imply the opposite: a connection means the app may ask, never sign;
 * and disconnecting does something, a revoked identity is declined on reauthorize
 * ([MobileWalletAdapterActivity] checks [Connections.allowed]) and asks again in front of you.
 * Row counts come from the ledger, which already records the dApp behind every signature.
 */
@Composable
internal fun ConnectionsSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val conns = remember(refresh) { Connections.all(ctx) }
    // The whole ledger, counted per dApp, on IO: the list shows first and
    // the counts fill in.
    val signings by androidx.compose.runtime.produceState(emptyMap<String, Int>(), refresh) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { Ledger.all(ctx) }.getOrDefault(emptyList())
                .filter { it.sent }
                .groupingBy { (it.dApp ?: "").lowercase() }.eachCount()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Halo.ground2, contentColor = Halo.ink, dragHandle = null,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl, vertical = Space.lg).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Text(stringResource(R.string.conn_title), style = HaloType.title, color = Halo.ink)
            Text(stringResource(R.string.conn_note), style = HaloType.small, color = Halo.muted)

            if (conns.isEmpty()) {
                Text(stringResource(R.string.conn_empty), style = HaloType.small, color = Halo.muted)
            } else {
                conns.forEach { c ->
                    ConnectionRow(c, signings[c.name.lowercase()] ?: 0) { refresh++ }
                }
            }
            Spacer(Modifier.size(Space.sm))
        }
    }
}

@Composable
private fun ConnectionRow(c: Connections.Conn, signed: Int, onChange: () -> Unit) {
    val ctx = LocalContext.current
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(c.account.ifEmpty { c.id }, 34.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = HaloType.body, color = Halo.ink, maxLines = 1)
                    Text(c.host ?: c.id, style = HaloType.label, color = Halo.muted, maxLines = 1)
                }
                if (c.revoked) {
                    Text(stringResource(R.string.conn_revoked), style = HaloType.label, color = Halo.amber)
                }
            }
            Text(
                stringResource(R.string.conn_meta, fmtWhen(ctx, c.lastAt), signed),
                style = HaloType.small, color = Halo.muted,
            )
            if (!c.revoked) {
                GhostButton(stringResource(R.string.conn_disconnect), Modifier.fillMaxWidth(), HIcon.BLOCK, tint = Halo.amber) {
                    Connections.revoke(ctx, c.id)
                    onChange()
                }
            } else {
                GhostButton(stringResource(R.string.conn_forget), Modifier.fillMaxWidth(), HIcon.TRASH, tint = Halo.muted) {
                    Connections.forget(ctx, c.id)
                    onChange()
                }
            }
        }
    }
}

/** "today", "yesterday", or the date. Nobody needs a timestamp to the second here. */
private fun fmtWhen(ctx: android.content.Context, at: Long): String {
    if (at <= 0L) return ctx.getString(R.string.conn_never)
    val days = ((System.currentTimeMillis() - at) / 86_400_000L).toInt()
    return when {
        days <= 0 -> ctx.getString(R.string.conn_today)
        days == 1 -> ctx.getString(R.string.conn_yesterday)
        else -> ctx.getString(R.string.conn_days_ago, days)
    }
}
