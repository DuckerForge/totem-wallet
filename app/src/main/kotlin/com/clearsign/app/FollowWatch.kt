package com.clearsign.app

import android.content.Context
import androidx.compose.ui.graphics.toArgb
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
 * The phone buzzes when somebody you follow buys.
 *
 * Following used to mean one thing only, and a quiet one: the coin went to the
 * agent as a candidate, if a budget happened to be open, on the agent's own
 * clock. With no budget the star did nothing at all and never said so. Now it
 * does the obvious thing as well, the thing the star looks like it should do.
 *
 * Fifteen minutes because that is WorkManager's floor for repeating work, and
 * the scanner publishes every ten, so nothing finer would see anything new. It
 * is not instant and the screen should not pretend otherwise.
 *
 * **The button does not buy.** It opens the feed with that coin already
 * unfolded, on the receipt. A purchase signed from a notification would be a
 * purchase nobody read, which is the one thing this app refuses to build, and
 * the ten seconds a broadcast receiver gets would not be enough to quote,
 * simulate and sign anyway.
 */
object FollowWatch {
    private const val WORK = "follow-watch"
    private const val PREF = "follow_alerts"
    private const val NOTIF_BASE = 5300
    /** Three is a glance. Ten is a reason to turn notifications off. */
    private const val MAX_PER_ROUND = 3

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("clearsign", Context.MODE_PRIVATE).getBoolean(PREF, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("clearsign", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()
        sync(ctx)
    }

    /** Called when the flag changes and when a wallet is followed or dropped. */
    fun sync(ctx: Context) {
        val wanted = enabled(ctx) && Follows.all(ctx).isNotEmpty()
        if (!wanted) { WorkManager.getInstance(ctx).cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<Watch>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    class Watch(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val ctx = applicationContext
            if (!enabled(ctx)) return Result.success()
            val follows = Follows.all(ctx)
            if (follows.isEmpty()) return Result.success()

            val feed = withContext(Dispatchers.IO) {
                runCatching { SeekerFeed.refresh(ctx, SeekerFeed.SLOW_FRESH_MS) }.getOrNull()
                    ?: runCatching { SeekerFeed.cached(ctx) }.getOrNull()
            } ?: return Result.success()

            val since = Follows.lastSeenAt(ctx)
            val fresh = feed.events
                .filter { it.at > since && it.wallet in follows }
                .sortedByDescending { it.at }
            // Even with nothing to say, the clock moves on: otherwise the first
            // round after turning this on would shout about everything in the
            // window, which on a busy hour is forty notifications.
            Follows.setLastSeenAt(ctx, maxOf(since, feed.events.maxOfOrNull { it.at } ?: since))
            if (fresh.isEmpty() || since == 0L) return Result.success()

            runCatching { JupiterTokens.warm(fresh.map { it.mint }) }
            fresh.take(MAX_PER_ROUND).forEachIndexed { i, e ->
                val name = com.clearsign.core.SeekerCrowd.nickname(e.wallet)
                val sym = JupiterTokens.cached(e.mint)?.symbol ?: e.symbol
                AgentBroker.warn(
                    ctx,
                    ctx.getString(if (e.sell) R.string.watch_sold_title else R.string.watch_bought_title, name),
                    ctx.getString(
                        if (e.sell) R.string.watch_sold_body else R.string.watch_bought_body,
                        sym, fmtSol((e.solSpent * 1e9).toLong(), 3),
                    ),
                    color = (if (e.sell) Halo.red else Halo.mint).toArgb(),
                    rhythm = if (e.sell) AgentBroker.Rhythm.DOWN else AgentBroker.Rhythm.UP,
                    id = NOTIF_BASE + i,
                    largeIcon = runCatching { NotifArt.logo(ctx, TokenSymbols.image(e.mint)) }.getOrNull(),
                    openMint = e.mint,
                )
            }
            return Result.success()
        }
    }
}
