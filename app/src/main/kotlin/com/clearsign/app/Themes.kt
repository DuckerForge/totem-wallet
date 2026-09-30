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
        // Every entry point passes here before drawing: record the resolved resource language.
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
        // Any dark theme picked, here or in settings, is the one the light switch returns to.
        if (p.ground.relativeLuminance() <= 0.5) e.putString(KEY_DARK, p.id)
        e.apply()
        Halo.palette = p
        // The home widget (own process) and the bubble (own service) cache the palette and
        // keep the old colors until told to redraw.
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

    /** Header switch: to the light theme, and back to the last dark one used, not the default. */
    fun toggleLight(ctx: Context) {
        if (isLight(ctx)) {
            // Nothing remembered: go to the light theme's dark pair, not the app default.
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
