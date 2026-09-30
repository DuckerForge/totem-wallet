package com.clearsign.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Restarts the trader. The loop's foreground service can die (memory pressure, reboot), leaving
 * a stop-loss unwatched. Two guards: [Keeper], a 15-minute worker checking it runs, and
 * [BootReceiver] at first unlock (not direct-boot aware: settings are encrypted until then).
 * Both only start the service; whether to trade is [TraderLoop.config].
 */
object TraderKeeper {
    private const val WORK = "apex-trader-keeper"

    fun ensure(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<Keeper>(15, TimeUnit.MINUTES, 5, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
    }

    /** Called wherever trading is switched on or off, so the guard follows the setting. */
    fun sync(ctx: Context) {
        // Alive while the loop is on, and also while a budget exists: its expiry
        // must be honoured even with the loop off.
        if (TraderLoop.config(ctx).on) { ensure(ctx); AgentLinkService.start(ctx) }
        else if (SessionWallet.current(ctx) != null) ensure(ctx)
        else cancel(ctx)
    }

    class Keeper(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val ctx = AppLocale.localized(applicationContext)
            // An expired budget closes itself: sell, close accounts, sweep home, log it.
            SessionWallet.current(ctx)?.takeIf { it.expired }?.let {
                val owner = Settings.watchWallet(ctx)
                if (owner != null) {
                    val (ok, said) = runCatching { SessionActions.closeBudget(ctx, owner) }.getOrDefault(false to "")
                    AgentBroker.warn(ctx, ctx.getString(if (ok) R.string.env_expired_closed else R.string.env_expired_open), said, rhythm = AgentBroker.Rhythm.STOP)
                }
            }
            if (!TraderLoop.config(ctx).on) {
                if (SessionWallet.current(ctx) == null) cancel(ctx)
                return Result.success()
            }
            // Starting a service that is already running is a no-op, so there is
            // nothing to check first and nothing to race against.
            runCatching { AgentLinkService.start(ctx) }
            return Result.success()
        }
    }

    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
            if (!TraderLoop.config(ctx).on) return
            runCatching { ensure(ctx) }
            runCatching { AgentLinkService.start(ctx) }
        }
    }
}
