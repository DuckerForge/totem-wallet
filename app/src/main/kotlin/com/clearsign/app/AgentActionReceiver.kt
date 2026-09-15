package com.clearsign.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The two buttons under a notification: sell this coin now, or stop the loop.
 *
 * Neither opens the app. A sale from here is exactly the sale the card makes
 * by hand: it goes through the collar, signs alone under the threshold and
 * asks for the print above it. A stop is the loop's own stop, and says how
 * many coins it leaves unwatched, the same as when the loop stops itself.
 */
class AgentActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val mint = intent.getStringExtra(EXTRA_MINT)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_SELL -> sell(ctx, mint ?: return@launch)
                    ACTION_STOP -> stop(ctx)
                }
            } finally {
                AgentBroker.dismiss(ctx, AgentBroker.MILESTONE_ID)
                pending.finish()
            }
        }
    }

    private suspend fun sell(ctx: Context, mint: String) {
        val pos = Positions.open(ctx).firstOrNull { it.mint == mint } ?: return
        val why = ctx.getString(R.string.trader_why_you, pos.symbol)
        val sale = runCatching { SessionActions.sellNow(ctx, pos, why, AgentBroker.Job.Source.LINK) }.getOrNull()
        val v = (sale as? SessionActions.Sale.Judged)?.verdict
        val said = when {
            v is AgentBroker.Verdict.SignedSilently || v is AgentBroker.Verdict.Confirmed -> {
                Positions.remove(ctx, pos.mint)
                ctx.getString(R.string.trader_sold_done)
            }
            v is AgentBroker.Verdict.Timeout -> ctx.getString(R.string.trader_needed_you)
            v != null -> v.reason ?: ctx.getString(R.string.trader_net_down)
            sale is SessionActions.Sale.Nothing -> ctx.getString(R.string.trader_no_coins)
            sale is SessionActions.Sale.NoRoute -> ctx.getString(R.string.trader_no_route)
            else -> ctx.getString(R.string.trader_net_down)
        }
        AgentBroker.warn(ctx, pos.symbol, said, rhythm = AgentBroker.Rhythm.STOP)
    }

    private fun stop(ctx: Context) {
        TraderLoop.stopSelf(ctx, ctx.getString(R.string.notif_stopped_by_you))
    }

    companion object {
        const val ACTION_SELL = "com.clearsign.app.agent.SELL"
        const val ACTION_STOP = "com.clearsign.app.agent.STOP"
        const val EXTRA_MINT = "mint"

        fun sellIntent(ctx: Context, mint: String): PendingIntent = PendingIntent.getBroadcast(
            ctx, mint.hashCode(),
            Intent(ctx, AgentActionReceiver::class.java).setAction(ACTION_SELL).putExtra(EXTRA_MINT, mint),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun stopIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 7001, Intent(ctx, AgentActionReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
