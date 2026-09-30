package com.clearsign.app

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Private keys at rest: blobs already sealed with a non-exportable AndroidKeyStore AES-GCM key,
 * one file each under noBackupFilesDir. Never SharedPreferences, which backup tools and prefs
 * dumps can reach. Callers seal before writing and open after reading.
 */
object SealedStore {
    private fun dir(ctx: Context) = File(ctx.noBackupFilesDir, "sealed").apply { mkdirs() }
    private fun file(ctx: Context, name: String) = File(dir(ctx), "$name.bin")

    fun read(ctx: Context, name: String): String? =
        runCatching { file(ctx, name).takeIf { it.exists() }?.readText() }.getOrNull()

    /** Temp file then rename, so a crash never leaves half a key. */
    fun write(ctx: Context, name: String, sealed: String) {
        val tmp = File(dir(ctx), "$name.tmp")
        tmp.writeText(sealed)
        if (!tmp.renameTo(file(ctx, name))) {
            tmp.delete()
            error("could not store $name")
        }
    }

    fun delete(ctx: Context, name: String) {
        file(ctx, name).delete()
    }

    /**
     * Moves a sealed value out of SharedPreferences, once. The old copy is removed only after the
     * file reads back identical, so a failure leaves the key where it was.
     */
    fun migrate(ctx: Context, prefs: SharedPreferences, prefKey: String, name: String): String? {
        read(ctx, name)?.let { return it }
        val old = prefs.getString(prefKey, null) ?: return null
        runCatching { write(ctx, name, old) }
        if (read(ctx, name) == old) prefs.edit().remove(prefKey).commit()
        return old
    }
}
