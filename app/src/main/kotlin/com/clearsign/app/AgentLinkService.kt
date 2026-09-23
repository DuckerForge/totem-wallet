package com.clearsign.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.clearsign.core.AgentMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The agent's hands on the phone while the app is closed. Two jobs keep the service up: the
 * link, polling the bridge for work from an agent on a computer and handing each job to
 * [AgentBroker]; and the trader, [TraderLoop] ticking on its own clock. A foreground service,
 * not a periodic worker: fifteen minutes is WorkManager's floor, and a stop-loss that looks
 * every fifteen minutes is not one. The permanent notification is the right trade, money is
 * moving on its own, and it is the kill switch: Pause, Stop trading, Revoke.
 */
class AgentLinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private var trader: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Themes.load(this); Settings.load(this)
        channel()
        startForeground(NOTIF_ID, notification())
        AgentLink.restoreState(this)
        start()
        startTrader()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> { SessionWallet.setMode(this, AgentMode.OFF); refreshNotification() }
            ACTION_RESUME -> { SessionWallet.setMode(this, AgentMode.AUTONOMOUS); refreshNotification() }
            ACTION_REVOKE -> { AgentLink.forget(this); stopSelf(); return START_NOT_STICKY }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_TRADE_STOP -> {
                TraderLoop.stop(this, getString(R.string.trader_stopped_by_you))
                trader?.cancel()
                refreshNotification()
                if (AgentLink.current(this) == null) { stopSelf(); return START_NOT_STICKY }
                return START_STICKY
            }
        }
        if (loop?.isActive != true) start()
        if (trader?.isActive != true) startTrader()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        AgentLink.setHealthy(false)
    }

    private fun start() {
        loop?.cancel()
        loop = scope.launch {
            var greeted = false
            var backoff = 3_000L
            while (isActive) {
                // No bridge linked is not a reason to shut down any more: the
                // trader may be the only thing this service is holding up.
                val link = AgentLink.current(this@AgentLinkService) ?: run {
                    if (!TraderLoop.config(this@AgentLinkService).on) stopSelf()
                    return@launch
                }
                if (!greeted) greeted = withContext(Dispatchers.IO) { AgentLink.hello(this@AgentLinkService, link) }
                val job = withContext(Dispatchers.IO) { runCatching { AgentLink.next(link) }.getOrNull() }
                if (job == null) {
                    // Either an empty long-poll (fine) or the bridge is away (wait a bit longer).
                    val healthy = (AgentLink.state.value as? AgentLink.State.On)?.healthy == true
                    delay(if (healthy) 500L else backoff.also { backoff = (backoff * 2).coerceAtMost(30_000L) })
                    if (!healthy) greeted = false
                    continue
                }
                backoff = 3_000L
                Log.i(TAG, "job ${job.id} from ${job.agent}")
                val verdict = runCatching { AgentBroker.handle(this@AgentLinkService, job) }
                    .getOrElse { e -> Log.e(TAG, "broker failed", e); AgentBroker.Verdict.Refused(e.message ?: "error") }
                withContext(Dispatchers.IO) { AgentLink.report(link, job.id, verdict) }
                // The collar's summary may have moved (spend, mode): tell the bridge.
                withContext(Dispatchers.IO) { AgentLink.hello(this@AgentLinkService, link) }
                refreshNotification()
            }
        }
    }

    /**
     * The trading clock. Exits are checked often and the hunt runs rarely: pricing what we hold
     * is one quote per position, a hunt grades the whole candidate list, and a position falling
     * through its stop is the only thing here that gets worse by waiting.
     */
    private fun startTrader() {
        trader?.cancel()
        if (!TraderLoop.config(this).on) return
        trader = scope.launch {
            var lastHunt = 0L
            while (isActive) {
                if (!TraderLoop.config(this@AgentLinkService).on) break
                val now = System.currentTimeMillis()
                val hunt = now - lastHunt >= TraderLoop.HUNT_EVERY_MS
                if (hunt) lastHunt = now
                val t = runCatching { TraderLoop.tick(this@AgentLinkService, hunt) }
                    .getOrElse { e -> Log.e(TAG, "tick failed", e); TraderLoop.Tick("error", acted = false) }
                if (t.acted || t.stopped) refreshNotification()
                if (t.stopped) break
                delay(TraderLoop.breath(this@AgentLinkService))
            }
            refreshNotification()
            // Nothing left to hold the service up.
            if (AgentLink.current(this@AgentLinkService) == null) stopSelf()
        }
    }

    // ---- notification -----------------------------------------------------------

    private fun channel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.agent_link_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun refreshNotification() = getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())

    private fun notification(): Notification {
        val link = AgentLink.current(this)
        val paused = SessionWallet.policy(this)?.mode == AgentMode.OFF
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra("open", "agent"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(code: String, req: Int) = PendingIntent.getService(
            this, req, Intent(this, AgentLinkService::class.java).setAction(code), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val trading = TraderLoop.config(this).on
        val title = when {
            paused -> getString(R.string.agent_link_paused)
            trading -> getString(R.string.trader_notif_title, Positions.open(this).size)
            else -> getString(R.string.agent_link_on, link?.name ?: "agent")
        }
        val last = when {
            trading -> TraderLoop.lastNote(this) ?: getString(R.string.trader_idle)
            else -> (AgentLink.state.value as? AgentLink.State.On)?.lastAction ?: getString(R.string.agent_link_idle)
        }
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title).setContentText(last)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    null, if (paused) getString(R.string.agent_resume) else getString(R.string.agent_pause),
                    action(if (paused) ACTION_RESUME else ACTION_PAUSE, 1),
                ).build(),
            )
        // Stopping the trading is a different thing from pausing the agent, and
        // from the lock screen it has to be one tap, not a trip into the app.
        if (trading) b.addAction(Notification.Action.Builder(null, getString(R.string.trader_stop_action), action(ACTION_TRADE_STOP, 3)).build())
        else if (link != null) b.addAction(Notification.Action.Builder(null, getString(R.string.agent_revoke), action(ACTION_REVOKE, 2)).build())
        return b.build()
    }

    companion object {
        private const val TAG = "Apex-Link"
        private const val CHANNEL = "agent_link"
        private const val NOTIF_ID = 4712
        const val ACTION_PAUSE = "com.clearsign.app.AGENT_PAUSE"
        const val ACTION_RESUME = "com.clearsign.app.AGENT_RESUME"
        const val ACTION_REVOKE = "com.clearsign.app.AGENT_REVOKE"
        const val ACTION_STOP = "com.clearsign.app.AGENT_STOP"
        const val ACTION_TRADE_STOP = "com.clearsign.app.TRADE_STOP"

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, AgentLinkService::class.java)) }
        }
        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, AgentLinkService::class.java).setAction(ACTION_STOP)) }
        }
        fun revoke(ctx: Context) {
            AgentLink.forget(ctx)
            stop(ctx)
        }
    }
}
