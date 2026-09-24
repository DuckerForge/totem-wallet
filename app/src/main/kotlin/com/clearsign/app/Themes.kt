package com.clearsign.app

import android.content.Context

/**
 * Which palette is on, and which premium ones this device paid for. Unlocks are stored with
 * the SKR payment signature, so the purchase is auditable on Solscan from the app.
 */
object Themes {
    private const val PREFS = "clearsign_themes"
    private const val KEY_SELECTED = "selected"
    private const val KEY_UNLOCKED = "unlocked"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Apply the saved selection. Call before `setContent` in every activity. */
    fun load(ctx: Context) {
        CustomTheme.palette(ctx) // prime the custom palette so byId() can return it
        val id = prefs(ctx).getString(KEY_SELECTED, Palettes.default.id)
        Halo.palette = if (isUnlocked(ctx, id)) Palettes.byId(id) else Palettes.default
    }

    fun select(ctx: Context, id: String) {
        if (id == CustomTheme.ID) CustomTheme.palette(ctx)
        val p = Palettes.byId(id)
        if (!isUnlocked(ctx, p.id)) return
        prefs(ctx).edit().putString(KEY_SELECTED, p.id).apply()
        Halo.palette = p
    }

    /** A theme is available if it's the free Halo, or ClearSign Pro is unlocked. */
    fun isUnlocked(ctx: Context, id: String?): Boolean {
        if (id == CustomTheme.ID) return Pro.isPro.value
        val p = Palettes.byId(id)
        return p.isFree || Pro.isPro.value
    }

    /** The dark theme to come back to. Written the moment the light one is picked. */
    private const val KEY_DARK = "last_dark"

    /** Is the light theme on now. */
    fun isLight(ctx: Context): Boolean = Halo.palette.ground.relativeLuminance() > 0.5

    /**
     * The one switch on the header: to the light theme and back to whichever dark one you
     * were using. Without remembering it, coming back landed everyone on the default and
     * the theme you had picked was gone.
     */
    fun toggleLight(ctx: Context) {
        if (isLight(ctx)) {
            val back = prefs(ctx).getString(KEY_DARK, Palettes.default.id) ?: Palettes.default.id
            select(ctx, if (Palettes.byId(back).ground.relativeLuminance() > 0.5) Palettes.default.id else back)
        } else {
            prefs(ctx).edit().putString(KEY_DARK, Halo.palette.id).apply()
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
