package com.clearsign.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Scanning an Agent Gate request off a screen.
 *
 * An agent running on a computer has no way to reach the phone: there is no
 * server in this design and there never will be. So it renders the request as a
 * QR and the phone reads it. The request is routed to [AgentGateActivity]
 * *explicitly*, so no other app can claim the `apex://` scheme (and the legacy `omni://`), and tagged
 * `via=qr` so the receipt can say how it arrived.
 *
 * Returns a launcher: call it to open the camera. zxing asks for the camera
 * permission itself, which is why there is none of that here.
 */
@Composable
internal fun rememberAgentScan(onError: (String) -> Unit = {}): () -> Unit {
    val ctx = LocalContext.current
    val notARequest = stringResource(R.string.gate_not_a_request)
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents?.trim() ?: return@rememberLauncherForActivityResult
        // A proof of payment held up by somebody: not an agent request, but ours to read.
        if (Proof.looksLike(text)) { Proof.incoming.value = text; return@rememberLauncherForActivityResult }
        if (Blinks.looksLike(text)) { Blinks.incoming.value = text; return@rememberLauncherForActivityResult }
        if (!text.startsWith("apex://agent", ignoreCase = true) && !text.startsWith("omni://agent", ignoreCase = true)) { onError(notARequest); return@rememberLauncherForActivityResult }
        val uri = runCatching { Uri.parse(text) }.getOrNull()
            ?: return@rememberLauncherForActivityResult onError(notARequest)
        // Pairing with a bridge: no transaction, just where to poll and the token to use.
        if (uri.path == "/pair") {
            if (AgentLink.pair(ctx, uri) == null) { onError(notARequest); return@rememberLauncherForActivityResult }
            AgentLinkService.start(ctx)
            return@rememberLauncherForActivityResult
        }
        runCatching {
            ctx.startActivity(
                Intent(ctx, AgentGateActivity::class.java)
                    .setData(uri)
                    .putExtra("via", "qr")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { onError(it.message ?: notARequest) }
    }
    val options = remember {
        ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(true).setCaptureActivity(ScanPortraitActivity::class.java).setPrompt("")
    }
    return { Door.hold(); launcher.launch(options) }
}
