package com.clearsign.app

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Physical feedback on the Seeker: a sharp double-buzz when a receipt carries a
 * DANGER risk (you feel the warning even before reading it), a soft tick on a
 * successful signature. minSdk 31 → VibratorManager is always available.
 */
object Haptics {

    private fun vibrator(ctx: Context): Vibrator? =
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator

    /** Insistent double-buzz for a blocking risk. */
    fun warn(ctx: Context) {
        val v = vibrator(ctx) ?: return
        runCatching { v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 70, 90, 70, 90, 160), -1)) }
    }

    /** The faintest click: a checkpoint in a hold, a chip toggled. */
    fun tick(ctx: Context) {
        val v = vibrator(ctx) ?: return
        runCatching { v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)) }
    }

    /** Short confirmation tick after signing. */
    fun success(ctx: Context) {
        val v = vibrator(ctx) ?: return
        runCatching { v.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }
}
