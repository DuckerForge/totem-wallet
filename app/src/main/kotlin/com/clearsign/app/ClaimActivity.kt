package com.clearsign.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * The other end of a gift link: how much is behind it, and moving it into your wallet. Nothing
 * to approve on chain, the key in the link is the authority, which is why the sending side
 * warns that whoever holds the link holds the money.
 */
class ClaimActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Themes.load(this); Settings.load(this); Pro.load(this)
        enableEdgeToEdge()
        val seed = intent?.data?.let { MoneyLinks.seedFrom(it) }
        val note = intent?.data?.let { MoneyLinks.noteFrom(it) }
        setContent { ScaledText { Box(Modifier.fillMaxSize().haloSurface()) { ClaimScreen(seed, note) { finishAndRemoveTask() } } } }
    }
}

@Composable
private fun ClaimScreen(seed: ByteArray?, note: String?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lamports by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val owner = remember { Settings.watchWallet(ctx) }

    LaunchedEffect(seed) { if (seed != null) lamports = MoneyLinks.peek(seed) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).clip(rs(18)).background(Halo.mint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            HaloIcon(HIcon.GIFT, Halo.mint, 30.dp)
        }
        Text(stringResource(R.string.claim_title), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Halo.ink)
        note?.takeIf { it.isNotBlank() }?.let {
            Text("“" + it + "”", fontFamily = Inter, fontSize = 14.sp, color = Halo.muted, lineHeight = 20.sp)
        }

        when {
            seed == null -> Banner(stringResource(R.string.claim_bad_link), Halo.red, HIcon.WARNING)
            done != null -> {
                Text(stringResource(R.string.claim_done), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Halo.mint)
                Text(shorten(done!!, 10), fontFamily = Mono, fontSize = 11.sp, color = Halo.muted)
            }
            lamports == null -> Working(stringResource(R.string.claim_checking))
            lamports == 0L -> Banner(stringResource(R.string.gift_already_taken), Halo.amber, HIcon.WARNING)
            else -> {
                Text(fmtSol(lamports!!, 5) + " SOL", fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = Halo.ink, style = Tabular)
                if (owner == null) {
                    Banner(stringResource(R.string.claim_no_wallet), Halo.amber, HIcon.WARNING)
                } else {
                    Text(stringResource(R.string.claim_into, shorten(owner, 4)), fontFamily = Inter, fontSize = 12.5.sp, color = Halo.muted)
                    if (busy) {
                        Working(stringResource(R.string.claim_taking))
                    } else {
                        HoldToConfirm(stringResource(R.string.claim_take)) {
                            busy = true
                            scope.launch {
                                val (sig, err) = MoneyLinks.claim(ctx, seed, owner)
                                busy = false
                                if (sig != null) done = sig else error = err ?: ctx.getString(R.string.claim_failed)
                            }
                        }
                    }
                }
            }
        }
        error?.let { Banner(it, Halo.red, HIcon.WARNING) }
        Text(stringResource(R.string.claim_truth), fontFamily = Inter, fontSize = 11.sp, color = Halo.muted, lineHeight = 16.sp)
        GhostButton(stringResource(R.string.close), Modifier.fillMaxWidth()) { onClose() }
    }
}
