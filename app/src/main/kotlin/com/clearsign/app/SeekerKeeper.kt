package com.clearsign.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The hourly look at what the Seeker crowd is buying. Its own worker on its own key,
 * unrelated to whether the agent trades: it reads other people's wallets and spends nothing.
 * Hourly because that is what one free key pays for: a measured pass over 10,527 wallets took
 * 106 calls and eighteen seconds and found 209 movers. Unmetered network only: half a megabyte an hour for a leaderboard is not a trade.
 */
object SeekerKeeper {
    private const val WORK = "apex-seeker-scan"
    private const val PREF = "seeker_scan_on"

    // With a published feed there is nothing for the phone to sweep: one scanner
    // does it for everyone. The worker only exists for the no-feed fallback.
    fun enabled(ctx: Context): Boolean =
        SeekerScan.available && !SeekerFeed.available &&
            ctx.getSharedPreferences("clearsign", Context.MODE_PRIVATE).getBoolean(PREF, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("clearsign", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()
        sync(ctx)
    }

    fun sync(ctx: Context) {
        if (!enabled(ctx)) { WorkManager.getInstance(ctx).cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<Scan>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    class Scan(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val ctx = applicationContext
            if (!enabled(ctx)) return Result.success()
            // A failed pass is never a reason to retry immediately: the next hour
            // will compare against the same balances and lose nothing.
            withContext(Dispatchers.IO) { runCatching { SeekerScan.pass(ctx) } }
            return Result.success()
        }
    }
}
