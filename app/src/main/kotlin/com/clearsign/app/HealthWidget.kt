package com.clearsign.app

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.clearsign.core.NATIVE_SOL_MINT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The home-screen "Wallet health" widget: a score ring, the SOL balance with its value and
 * the top thing to fix, from the same engine as the in-app card, in the user's theme.
 * Values are cached in prefs so it draws instantly; data refreshes when stale, from the
 * Watchtower worker, on foreground, and on the widget's own refresh tap. Two layouts:
 * narrow shows ring and balance, wide adds the reclaimable rent and the alert line.
 */
class HealthWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(TINY, SHORT, NARROW, WIDE, TALL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Themes.load(context); Settings.load(context)
        if (HealthWidgetData.isStale(context)) withTimeoutOrNull(6_000) { HealthWidgetData.refresh(context, updateWidgets = false) }
        val snapshot = HealthWidgetData.load(context)
        val palette = Halo.palette
        provideContent { GlanceTheme { Content(snapshot, palette) } }
    }

    @Composable
    private fun Content(d: HealthWidgetData.Snapshot?, p: HaloPalette) {
        val ctx = LocalContext.current
        val size = LocalSize.current
        val ringOnly = size.width < SHORT.width
        val wide = size.width >= WIDE.width
        val tall = size.height >= TALL.height
        val roomy = size.height >= NARROW.height
        val tint = when {
            d == null -> p.muted
            d.score >= 80 -> p.accent
            d.score >= 50 -> p.amber
            else -> p.red
        }
        val open = androidx.glance.appwidget.action.actionStartActivity(Intent(ctx, MainActivity::class.java))

        // On the card colour, not the page's: a launcher is dark too, and the
        // widget used to melt into it. The ring carries the brand gradient.
        Column(
            GlanceModifier.fillMaxSize().background(p.ground2).cornerRadius(22.dp)
                .padding(if (roomy) 12.dp else 8.dp).clickable(open),
        ) {
            Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    provider = ImageProvider(ring(d?.score, tint, p.stroke, p.ink, brand = d != null && d.score >= 80)),
                    contentDescription = ctx.getString(R.string.widget_health),
                    modifier = GlanceModifier.size(if (roomy) 58.dp else 42.dp),
                )
                if (!ringOnly) {
                    Spacer(GlanceModifier.width(10.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            // The eyebrow names the app and the score in one breath:
                            // the number is already in the ring, the word was not.
                            Text(
                                (ctx.getString(R.string.app_name) + "  ·  " + ctx.getString(R.string.widget_health)).uppercase(),
                                style = TextStyle(color = ColorProvider(p.muted), fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                modifier = GlanceModifier.defaultWeight(), maxLines = 1,
                            )
                            Box(
                                GlanceModifier.size(22.dp).cornerRadius(11.dp).background(p.cardSoft)
                                    .clickable(actionRunCallback<RefreshHealthAction>()),
                                contentAlignment = Alignment.Center,
                            ) { Text("\u21bb", style = TextStyle(color = ColorProvider(p.accent2), fontSize = 12.sp, fontWeight = FontWeight.Bold)) }
                        }
                        if (d == null) {
                            Text(
                                ctx.getString(R.string.widget_no_wallet),
                                style = TextStyle(color = ColorProvider(p.ink), fontSize = 12.sp, fontWeight = FontWeight.Bold), maxLines = 2,
                            )
                        } else {
                            // Wide: the whole portfolio is the headline; narrow: the SOL balance.
                            val headline = if (wide && d.totalFiat != null) fmtFiat(d.totalFiat, d.currency) else fmtSol(d.lamports, 4) + " SOL"
                            Text(
                                headline,
                                style = TextStyle(color = ColorProvider(p.ink), fontSize = if (roomy) 21.sp else 16.sp, fontWeight = FontWeight.Bold),
                                maxLines = 1,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val sub = if (wide && d.totalFiat != null) fmtSol(d.lamports, 4) + " SOL" else d.fiat?.let { fmtFiat(it, d.currency) }
                                sub?.let {
                                    Text(it, style = TextStyle(color = ColorProvider(p.accent2), fontSize = 11.sp), maxLines = 1)
                                    Spacer(GlanceModifier.width(6.dp))
                                }
                                d.change24h?.let { c ->
                                    // The day's move in its own soft pill, as on the wallet page.
                                    val tone = if (c >= 0) p.accent else p.red
                                    Box(
                                        GlanceModifier.cornerRadius(9.dp).background(ColorProvider(tone.copy(alpha = 0.14f))).padding(horizontal = 6.dp, vertical = 1.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            (if (c >= 0) "+" else "\u2212") + "%.1f%%".format(kotlin.math.abs(c)),
                                            style = TextStyle(color = ColorProvider(tone), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (d != null && roomy) {
                val alert = d.alert?.takeIf { it.isNotBlank() }
                Text(
                    alert ?: ctx.getString(R.string.widget_clean),
                    style = TextStyle(color = ColorProvider(if (alert == null) p.accent else p.amber), fontSize = 10.sp),
                    maxLines = if (tall) 2 else 1,
                )
                if (wide && d.reclaimable > 0L) {
                    Text(
                        ctx.getString(R.string.widget_reclaim, fmtSol(d.reclaimable, 4)),
                        style = TextStyle(color = ColorProvider(p.accent), fontSize = 10.sp), maxLines = 1,
                    )
                }
                if (wide && d.agent != null) {
                    Text(d.agent, style = TextStyle(color = ColorProvider(p.accent2), fontSize = 10.sp), maxLines = 1)
                }
            }

            // Quick actions: straight into the sheet you wanted, from the home screen.
            if (d != null && tall) {
                Spacer(GlanceModifier.height(8.dp))
                Row(GlanceModifier.fillMaxWidth()) {
                    QuickAction(ctx, R.string.send_btn, "send", p.accent, GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(6.dp))
                    QuickAction(ctx, R.string.receive_btn, "receive", p.accent2, GlanceModifier.defaultWeight())
                    Spacer(GlanceModifier.width(6.dp))
                    QuickAction(ctx, R.string.swap_btn, "swap", p.accent, GlanceModifier.defaultWeight())
                }
            }
        }
    }

    /** One home-screen shortcut that opens a specific sheet in the app. */
    @Composable
    private fun QuickAction(ctx: Context, labelRes: Int, target: String, tint: androidx.compose.ui.graphics.Color, modifier: GlanceModifier) {
        val intent = Intent(ctx, MainActivity::class.java).putExtra("open", target)
            .setAction("com.clearsign.app.OPEN_" + target.uppercase())
        Box(
            modifier.height(34.dp).cornerRadius(12.dp).background(ColorProvider(tint.copy(alpha = 0.14f)))
                .clickable(androidx.glance.appwidget.action.actionStartActivity(intent)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                ctx.getString(labelRes),
                style = TextStyle(color = ColorProvider(tint), fontSize = 11.sp, fontWeight = FontWeight.Bold), maxLines = 1,
            )
        }
    }

    /** The score ring, drawn as a bitmap — RemoteViews cannot draw arcs. */
    private fun ring(score: Int?, tint: Color, track: Color, ink: Color, brand: Boolean): Bitmap {
        val px = 216
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = px / 2f
        val w = px * 0.085f
        val inset = w * 1.1f
        val rect = RectF(inset, inset, px - inset, px - inset)
        // A soft disc behind the ring, so the number sits on something.
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = track.copy(alpha = 0.16f).toArgb() }
        c.drawCircle(cx, cx, cx - inset, disc)
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND
        }
        arc.color = track.copy(alpha = 0.45f).toArgb()
        c.drawArc(rect, 0f, 360f, false, arc)
        if (score != null) {
            // The brand gradient when the score is good, the warning colour when
            // it is not: the same rule as the bubble.
            if (brand) {
                arc.shader = android.graphics.SweepGradient(
                    cx, cx,
                    intArrayOf(0xFF9524F3.toInt(), 0xFF4CC9FF.toInt(), 0xFF4DFFD0.toInt(), 0xFF9524F3.toInt()),
                    floatArrayOf(0f, 0.34f, 0.67f, 1f),
                ).apply { setLocalMatrix(android.graphics.Matrix().apply { postRotate(-90f, cx, cx) }) }
            } else arc.color = tint.toArgb()
            c.drawArc(rect, -90f, 360f * score.coerceIn(0, 100) / 100f, false, arc)
        }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink.toArgb(); textAlign = Paint.Align.CENTER; textSize = px * 0.32f; isFakeBoldText = true
        }
        c.drawText(score?.toString() ?: "–", cx, cx + label.textSize * 0.35f, label)
        return bmp
    }

    companion object {
        /** 2x1: just the ring. */
        private val TINY = DpSize(110.dp, 58.dp)
        /** 3x1: ring + balance. */
        private val SHORT = DpSize(180.dp, 58.dp)
        /** 3x2: ring + balance + value + alert. */
        private val NARROW = DpSize(180.dp, 110.dp)
        /** 4x2: adds the portfolio total, the day's move and the rent to reclaim. */
        private val WIDE = DpSize(276.dp, 110.dp)
        /** 4x3: adds the quick actions. */
        private val TALL = DpSize(276.dp, 172.dp)
    }
}

/**
 * The widget's own refresh tap. The work is not done here: a widget tap arrives as a
 * broadcast with ten seconds to live, and this refresh makes four network calls in a row
 * (token accounts, balance, price, rate) that eat those seconds on a slow network. It
 * happened, 18 Sep 2026, with the person standing on the home screen: `am_anr ... Broadcast
 * of Intent { dat=glance-action:/… }`. So this only enqueues; WorkManager has the time, and
 * repaints the widgets when done.
 */
class RefreshHealthAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        HealthWidgetData.enqueue(context)
    }
}

/** The real run, outside the broadcast's ten seconds. */
class WidgetRefreshWorker(ctx: Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { HealthWidgetData.refresh(applicationContext) }
        return Result.success()
    }
}

class HealthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HealthWidget()
}

/** Cached widget values and the refresh that fills them. */
object HealthWidgetData {
    private const val PREFS = "clearsign_widget"

    /**
     * When the snapshot is too old to redraw and the chain is asked. It was fifteen minutes, and
     * the widget wakes every half hour, so every wake reread everything: ten calls, forty-eight
     * times a day, more than an idle agent, for people who never touched the agent. Two hours,
     * because this is a number glanced at on the home screen, and it refreshes anyway on app
     * open, after every agent move, and on every Watchtower round.
     */
    private const val STALE_MS = 2 * 3600_000L

    data class Snapshot(
        val score: Int,
        val lamports: Long,
        val fiat: Double?,          // the SOL balance in the display currency
        val totalFiat: Double?,     // the whole portfolio, tokens included
        val change24h: Double?,     // the portfolio's move over 24h, in percent
        val currency: String,
        val alert: String?,
        val reclaimable: Long,
        val at: Long,
        /** "Agent · autonomous · today 0.012/0.05 SOL", or null without an envelope. */
        val agent: String? = null,
    )

    fun load(ctx: Context): Snapshot? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("at")) return null
        return Snapshot(
            score = p.getInt("score", 0),
            lamports = p.getLong("lamports", 0L),
            fiat = p.getString("fiat", null)?.toDoubleOrNull(),
            totalFiat = p.getString("total", null)?.toDoubleOrNull(),
            change24h = p.getString("change", null)?.toDoubleOrNull(),
            currency = p.getString("currency", "USD") ?: "USD",
            alert = p.getString("alert", null),
            reclaimable = p.getLong("reclaimable", 0L),
            at = p.getLong("at", 0L),
            agent = p.getString("agent", null),
        )
    }

    /** The agent's state in one line, for the widget and the bubble. */
    fun agentLine(ctx: Context): String? {
        val p = SessionWallet.policy(ctx) ?: return null
        val h = SessionWallet.history(ctx)
        // While the loop is running, what it is doing beats what it is allowed to
        // do: "2 open, sold BONK +31%" tells you more from a lock screen than a
        // mode and a daily cap. Both surfaces read this line, so they agree.
        if (TraderLoop.config(ctx).on) {
            val open = Positions.open(ctx).size
            val last = TraderLoop.lastNote(ctx) ?: ctx.getString(R.string.trader_idle)
            return ctx.getString(R.string.widget_trading, open, last)
        }
        val mode = when (p.mode) {
            com.clearsign.core.AgentMode.OFF -> ctx.getString(R.string.agent_mode_off)
            com.clearsign.core.AgentMode.ASK_ALWAYS -> ctx.getString(R.string.agent_mode_ask)
            else -> ctx.getString(R.string.agent_mode_auto)
        }
        return ctx.getString(R.string.widget_agent, mode.lowercase(), fmtSol(h.spentLast24hLamports, 3), fmtSol(p.dailyLamports, 3))
    }

    fun isStale(ctx: Context): Boolean = (System.currentTimeMillis() - (load(ctx)?.at ?: 0L)) > STALE_MS

    /**
     * Fetch score, balance and value for the connected wallet, then repaint the widgets, queued
     * rather than run at once. `REPLACE`: five taps on the button leave one job, the last.
     */
    fun enqueue(ctx: Context) {
        androidx.work.WorkManager.getInstance(ctx).enqueueUniqueWork(
            "widget-refresh",
            androidx.work.ExistingWorkPolicy.REPLACE,
            androidx.work.OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build(),
        )
    }

    suspend fun refresh(ctx: Context, updateWidgets: Boolean = true) = withContext(Dispatchers.IO) {
        val owner = Settings.watchWallet(ctx) ?: return@withContext
        val rpc = SolanaRpc.urlFor(null)
        // A node that did not answer is not a wallet with nothing in it: the lenient reader made a
        // failed call an empty list, the score became 100, "all clean" overwrote a true snapshot on
        // the home screen. Keep what we had and try later.
        val accounts = runCatching { SolanaRpc.tokensOf(rpc, owner) }.getOrNull() ?: return@withContext
        val health = WalletHealth.of(owner, accounts)
        val lamports = runCatching { SolanaRpc.getBalance(rpc, owner) }.getOrNull() ?: load(ctx)?.lamports ?: 0L
        val currency = Settings.currency.value
        val fiat = runCatching {
            val usd = Prices.quotes(listOf(NATIVE_SOL_MINT))[NATIVE_SOL_MINT]?.usd ?: return@runCatching null
            val fx = Prices.usdTo(currency) ?: return@runCatching null
            usd * fx * lamports / 1e9
        }.getOrNull()
        // The whole portfolio (tokens included) and its day move, from the same engine as the app.
        val pv = runCatching { Portfolio.load(ctx, owner, currency) }.getOrNull()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("score", health.score)
            .putLong("lamports", lamports)
            .putString("fiat", fiat?.toString())
            .putString("total", pv?.total?.takeIf { it > 0.0 }?.toString())
            .putString("change", pv?.change24hPct?.toString())
            .putString("currency", currency)
            .putString("alert", health.issues.firstOrNull()?.detail)
            .putLong("reclaimable", health.reclaimableLamports)
            .putString("agent", agentLine(ctx))
            .putLong("at", System.currentTimeMillis())
            .apply()
        if (updateWidgets) runCatching { HealthWidget().updateAll(ctx) }
    }
}
