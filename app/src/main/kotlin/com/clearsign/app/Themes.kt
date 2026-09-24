package com.clearsign.app

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.launch

/**
 * Which palette is on, and which premium ones this device paid for. Unlocks are stored with
 * the SKR payment signature, so the purchase is auditable on Solscan from the app.
 */
object Themes {
    private const val PREFS = "clearsign_themes"
    private const val KEY_SELECTED = "selected"
    private const val KEY_UNLOCKED = "unlocked"
    /** The dark theme the light switch comes back to. */
    private const val KEY_DARK = "last_dark"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Apply the saved selection. Call before `setContent` in every activity. */
    fun load(ctx: Context) {
        // The one place every entry point passes through before it draws anything, which
        // makes it the place to note what language the resources resolved to.
        AppLocale.remember(ctx)
        CustomTheme.palette(ctx) // prime the custom palette so byId() can return it
        val id = prefs(ctx).getString(KEY_SELECTED, Palettes.default.id)
        Halo.palette = if (isUnlocked(ctx, id)) Palettes.byId(id) else Palettes.default
    }

    fun select(ctx: Context, id: String) {
        if (id == CustomTheme.ID) CustomTheme.palette(ctx)
        val p = Palettes.byId(id)
        if (!isUnlocked(ctx, p.id)) return
        val e = prefs(ctx).edit().putString(KEY_SELECTED, p.id)
        // Any dark theme you pick is the one the switch brings back, whether you picked it
        // here or in the settings: remembering only the one you happened to leave from sent
        // people back to the default they had already moved away from.
        if (p.ground.relativeLuminance() <= 0.5) e.putString(KEY_DARK, p.id)
        e.apply()
        Halo.palette = p
        // Everything that paints outside Compose reads the palette once and keeps it: the
        // home widget in its own process, the bubble in its own service. Changing the theme
        // used to leave both on the old colours until something else happened to redraw them.
        repaintEverything(ctx)
    }

    /** The surfaces that live outside the activity and have to be told. */
    private fun repaintEverything(ctx: Context) {
        CompanionService.repaint(ctx)
        val app = ctx.applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            runCatching { HealthWidget().updateAll(app) }
        }
    }

    /** A theme is available if it's the free Halo, or ClearSign Pro is unlocked. */
    fun isUnlocked(ctx: Context, id: String?): Boolean {
        if (id == CustomTheme.ID) return Pro.isPro.value
        val p = Palettes.byId(id)
        return p.isFree || Pro.isPro.value
    }

    /** Is the light theme on now. */
    fun isLight(ctx: Context): Boolean = Halo.palette.ground.relativeLuminance() > 0.5

    /**
     * The one switch on the header: to the light theme and back to whichever dark one you
     * were using. Without remembering it, coming back landed everyone on the default and
     * the theme you had picked was gone.
     */
    fun toggleLight(ctx: Context) {
        if (isLight(ctx)) {
            // The two are a pair: with nothing remembered the light one goes to its own night,
            // not to the app default, which is a different design.
            val back = prefs(ctx).getString(KEY_DARK, Palettes.velaNight.id) ?: Palettes.velaNight.id
            select(ctx, if (Palettes.byId(back).ground.relativeLuminance() > 0.5) Palettes.default.id else back)
        } else {
            select(ctx, Palettes.vela.id)
        }
    }

    fun unlockedIds(ctx: Context): Set<String> = prefs(ctx).getStringSet(KEY_UNLOCKED, emptySet()) ?: emptySet()

    /** Record a paid unlock ([signature] = the SKR transfer). */
    fun unlock(ctx: Context, id: String, signature: String?) {
        val set = HashSet(unlockedIds(ctx)); set.add(id)
        prefs(ctx).edit().putStringSet(KEY_UNLOCKED, set).apply {
            if (signature != null) putString("sig_$id", signature)
        }.apply()
    }

    /** The payment signature that unlocked [id], if any. */
    fun unlockSignature(ctx: Context, id: String): String? = prefs(ctx).getString("sig_$id", null)
}
