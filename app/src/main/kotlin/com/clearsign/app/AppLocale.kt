package com.clearsign.app

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.Settings

/**
 * Per-app language. Android 13+ stores it in the system (LocaleManager) so it
 * survives reinstalls and shows in Settings -> App languages; older builds are
 * sent to the app's system settings page.
 */
object AppLocale {
    /** "en", "it", ... or null = follow the system. */
    fun current(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 33) return null
        val list = ctx.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return null
        return if (list.isEmpty) null else list.get(0).language
    }

    fun set(ctx: Context, tag: String?) {
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            (ctx as? Activity)?.recreate()
        } else {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) }
        }
    }
}
