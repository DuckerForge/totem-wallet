package com.clearsign.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Watchtower (Pro): a periodic background check on the wallet you connected and
 * the addresses you've paid. If a live unlimited approval appears, or an address
 * you signed to lands on the blocklist, it posts a local notification — so a
 * problem finds you instead of waiting for you to open the app.
 */
object Watchtower {
    private const val WORK = "clearsign-watchtower"
    private const val CHANNEL = "watchtower"

    fun enable(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<WatchWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun disable(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(WORK) }

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.watch_channel), NotificationManager.IMPORTANCE_HIGH)
            ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    fun notify(ctx: Context, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(open).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(7001, n) }
    }
}

class WatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val owner = Settings.watchWallet(ctx) ?: return@withContext Result.success()
        runCatching {
            val accounts = SolanaRpc.tokenAccountsOf(SolanaRpc.urlFor(null), owner, force = true)
            val health = WalletHealth.of(owner, accounts)
            val unlimited = health.issues.firstOrNull { it.kind == HealthIssue.Kind.UNLIMITED_APPROVAL }

            // Addresses you've signed to, checked against the local blocklist.
            val paid = Ledger.all(ctx).mapNotNull { it.primaryRecipient }.distinct().take(50)
            val scan = runCatching { BlocklistScanner(ctx).scan(ByteArray(0), paid) }.getOrNull()
            val flagged = scan?.let { it.malicious || it.sanctioned.isNotEmpty() } == true

            when {
                flagged -> Watchtower.notify(ctx, ctx.getString(R.string.watch_flag_title), ctx.getString(R.string.watch_flag_body))
                unlimited != null -> Watchtower.notify(ctx, ctx.getString(R.string.watch_appr_title), ctx.getString(R.string.watch_appr_body, unlimited.count))
            }
        }
        // The agent envelope: take the winnings home when they reach the threshold.
        runCatching {
            val took = SessionActions.harvest(ctx, owner)
            if (took != null && took > 0L) {
                Watchtower.notify(ctx, ctx.getString(R.string.env_title), ctx.getString(R.string.env_harvest_done, fmtSol(took, 5)))
            }
        }
        // Keep the home-screen widget fresh on the same schedule.
        runCatching { HealthWidgetData.refresh(ctx) }
        Result.success()
    }
}
