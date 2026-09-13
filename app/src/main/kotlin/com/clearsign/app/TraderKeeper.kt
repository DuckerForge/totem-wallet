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
 * What puts the trader back on its feet.
 *
 * A foreground service is the right host for the loop, but it is not immortal:
 * Android kills services under memory pressure, and a reboot ends it outright.
 * On its own that would mean a position with a stop-loss quietly stops being
 * watched, and nobody would be told. So two cheap guards:
 *
 *  * [Keeper], a periodic worker. WorkManager's floor is fifteen minutes, which
 *    is far too slow to trade on but exactly right for asking "is it still
 *    running?". WorkManager also persists its own schedule across reboots.
 *  * [BootReceiver], so the loop resumes at the first unlock after a reboot
 *    rather than waiting for the first worker window. Deliberately not
 *    direct-boot aware: the settings it needs are encrypted until then.
 *
 * Both only ever *start* the service. The decision to trade lives in
 * [TraderLoop.config], and neither of these can turn it on.
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
        if (TraderLoop.config(ctx).on) { ensure(ctx); AgentLinkService.start(ctx) } else cancel(ctx)
    }

    class Keeper(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val ctx = applicationContext
            if (!TraderLoop.config(ctx).on) {
                cancel(ctx)
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
