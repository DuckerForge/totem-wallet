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
 * Per-app language. Android 13+ stores it in the system (LocaleManager), so it survives
 * reinstalls and shows under Settings > App languages; older builds go to the app's settings page.
 */
object AppLocale {
    /** "en", "it", ... or null = follow the system. */
    fun current(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 33) return null
        val list = ctx.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return null
        return if (list.isEmpty) null else list.get(0).language
    }

    /**
     * The language the app is actually being read in, cached for the code that has no Context.
     * Taken from the resources, which is the only answer that cannot disagree with the screen:
     * the system list says what the phone is set to, and an app told to use English on an
     * Italian phone is English on screen and Italian in that list.
     */
    @Volatile
    private var appliedTag: String? = null

    /** Called from [Themes.load], which every entry point already calls before drawing. */
    fun remember(ctx: Context) {
        appliedTag = runCatching {
            ctx.resources.configuration.locales[0]?.language
        }.getOrNull()
    }

    /**
     * [ctx] speaking the app's language. Android applies the per-app choice to activities, but
     * a Service, a worker and the widget read the application's resources, which stay in the
     * phone's language: the bubble and the widget were Italian inside an English app.
     */
    fun localized(ctx: Context): Context {
        val tag = current(ctx) ?: return ctx
        if (ctx.resources.configuration.locales[0]?.language == tag) return ctx
        val conf = android.content.res.Configuration(ctx.resources.configuration)
        conf.setLocales(LocaleList.forLanguageTags(tag))
        return ctx.createConfigurationContext(conf)
    }

    fun applied(): java.util.Locale? = appliedTag?.let { java.util.Locale.forLanguageTag(it) }

    fun set(ctx: Context, tag: String?) {
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            (ctx as? Activity)?.recreate()
            // The surfaces outside the activity keep the old language until told.
            HealthWidgetData.enqueue(ctx)
            CompanionService.relaunch(ctx)
        } else {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) }
        }
    }
}
