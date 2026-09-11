package com.clearsign.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/** Files the user takes away: written to cache, shared through the FileProvider, or saved via SAF. */
object Exports {
    private fun dir(ctx: Context) = File(ctx.cacheDir, "exports").apply { mkdirs() }

    /** Drop leftovers older than a day. */
    fun clean(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 24 * 3600_000L
        dir(ctx).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    fun write(ctx: Context, name: String, bytes: ByteArray): File = File(dir(ctx), name).apply { writeBytes(bytes) }

    fun uriFor(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

    fun share(ctx: Context, files: List<File>, mime: String, subject: String) {
        val uris = ArrayList(files.map { uriFor(ctx, it) })
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        i.type = mime; i.putExtra(Intent.EXTRA_SUBJECT, subject); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(i, null)) }
    }
}

/** "Save to…": a launcher that asks where, then writes the bytes you hand it. */
@Composable
fun rememberSaveToLauncher(mime: String): (suggestedName: String, bytes: ByteArray) -> Unit {
    val ctx = LocalContext.current
    val pending = remember { androidx.compose.runtime.mutableStateOf<ByteArray?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        val b = pending.value; pending.value = null
        if (uri != null && b != null) runCatching { ctx.contentResolver.openOutputStream(uri)?.use { out -> out.write(b) } }
    }
    return { name, bytes -> pending.value = bytes; launcher.launch(name) }
}
