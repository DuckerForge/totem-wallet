package com.clearsign.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The floating companion: Omni as a bubble that lives over every other app.
 *
 * Collapsed it is the wallet-health ring — you always know, from inside any dApp
 * or browser, whether your wallet is clean and what it holds. Tapped it opens a
 * small panel with the balance, the top thing to fix and a way into the app.
 * Dragged it sticks wherever you leave it.
 *
 * Deliberately built from classic Views rather than Compose: an overlay window
 * has no Activity lifecycle to host a Composition, and a wallet's always-on
 * surface must never be the fragile part.
 */
class CompanionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var expanded = false

    private var bubble: ImageView? = null
    private var panel: LinearLayout? = null
    private var totalLine: TextView? = null
    private var agentLine: TextView? = null
    private var healthLine: TextView? = null
    private var coinLine: TextView? = null
    private var ticker: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Themes.load(this); Settings.load(this)
        startForeground(NOTIF_ID, notification())
        wm = getSystemService(WindowManager::class.java)
        attach()
        refresh()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    // ---- window ------------------------------------------------------------

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun attach() {
        val p = Halo.palette
        val container = FrameLayout(this)
        val size = CompanionPrefs.size(this).toFloat()

        // Collapsed: the face the person chose.
        val ring = ImageView(this).apply { layoutParams = FrameLayout.LayoutParams(dp(size), dp(size)) }
        container.addView(ring)

        // Expanded: the rows the person switched on, then the two buttons.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(p.ground.toArgb())
                setStroke(dp(1f), p.stroke.toArgb())
            }
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            layoutParams = FrameLayout.LayoutParams(dp(248f), FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        fun line(size: Float, tint: Int, bold: Boolean = false, lines: Int = 2) = TextView(this).apply {
            setTextColor(tint); textSize = size; maxLines = lines
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(3f), 0, dp(3f))
        }
        val title = line(10f, p.muted.toArgb(), bold = true).apply { text = "APEX" }
        val total = line(20f, p.ink.toArgb(), bold = true, lines = 1)
        val agent = line(12f, p.accent.toArgb())
        val health = line(12f, p.amber.toArgb())
        val coin = line(13f, p.accent2.toArgb(), bold = true, lines = 1)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10f), 0, 0) }
        actions.addView(button(getString(R.string.companion_open), p.accent.toArgb()) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            collapse()
        })
        actions.addView(button(getString(R.string.companion_agent), p.accent2.toArgb()) {
            startActivity(Intent(this, MainActivity::class.java).putExtra("open", "agent").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            collapse()
        })
        actions.addView(button(getString(R.string.companion_hide), p.muted.toArgb()) { stopSelf() })

        card.addView(title); card.addView(total); card.addView(agent); card.addView(health); card.addView(coin); card.addView(actions)
        container.addView(card)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12f); y = dp(220f)
        }

        container.setOnTouchListener(dragAndTap(lp))
        runCatching { wm.addView(container, lp) }.onFailure { stopSelf(); return }

        root = container; params = lp
        bubble = ring; panel = card
        totalLine = total; agentLine = agent; healthLine = health; coinLine = coin

        // Alive on its own: every minute the face and the rows are read again.
        ticker?.cancel()
        ticker = scope.launch { while (true) { kotlinx.coroutines.delay(60_000); refresh() } }
    }

    private fun button(label: String, tint: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(tint); textSize = 12f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
        background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(AColor.argb(34, AColor.red(tint), AColor.green(tint), AColor.blue(tint)))
        }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8f) }
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    /** One listener for both gestures: a short, still press is a tap; anything else drags. */
    private fun dragAndTap(lp: WindowManager.LayoutParams) = object : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f
        private var startX = 0; private var startY = 0
        private var downAt = 0L; private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    downAt = System.currentTimeMillis(); moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > dp(8f) || kotlin.math.abs(dy) > dp(8f)) moved = true
                    if (moved) {
                        lp.x = (startX + dx).coerceAtLeast(0)
                        lp.y = (startY + dy).coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && System.currentTimeMillis() - downAt < 400) {
                        // A tap on the panel's buttons is handled by them; here it toggles.
                        if (expanded) collapse() else expand()
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun expand() {
        expanded = true
        bubble?.visibility = View.GONE
        panel?.visibility = View.VISIBLE
        refresh()
    }

    private fun collapse() {
        expanded = false
        panel?.visibility = View.GONE
        bubble?.visibility = View.VISIBLE
    }

    // ---- data --------------------------------------------------------------

    private fun refresh() {
        render(HealthWidgetData.load(this), null, null)
        scope.launch {
            // Health, the wallet's total, and the chosen coin, each only when asked for.
            val ctx = this@CompanionService
            val owner = Settings.watchWallet(ctx)
            val currency = Settings.currency.value
            val health = withContext(Dispatchers.IO) {
                if (HealthWidgetData.isStale(ctx)) runCatching { HealthWidgetData.refresh(ctx, updateWidgets = false) }
                HealthWidgetData.load(ctx)
            }
            val total = if (owner != null && (CompanionPrefs.show(ctx, "total") || CompanionPrefs.face(ctx) == CompanionPrefs.Face.TOTAL)) withContext(Dispatchers.IO) {
                Portfolio.cached(owner, currency) ?: runCatching { Portfolio.load(owner, currency) }.getOrNull()
            } else null
            val mint = CompanionPrefs.coin(ctx)
            val coin = if (mint != null && (CompanionPrefs.show(ctx, "coin") || CompanionPrefs.face(ctx) == CompanionPrefs.Face.COIN)) withContext(Dispatchers.IO) {
                runCatching { Prices.quotes(listOf(mint))[mint] }.getOrNull()?.let { q -> Triple(TokenSymbols.symbol(mint), q.usd, q.change24h) }
            } else null
            render(health, total, coin)
        }
    }

    private fun render(d: HealthWidgetData.Snapshot?, total: PortfolioView?, coin: Triple<String, Double?, Double?>?) {
        val ctx = this
        val p = Halo.palette
        val trading = TraderLoop.config(ctx).on
        val open = if (trading) Positions.open(ctx).size else null
        val totalText = total?.let { fmtFiat(it.total, it.currency) }
        val faceTotal = total?.let { compact(it.total, it.currency) }
        bubble?.setImageBitmap(
            CompanionPrefs.faceBitmap(
                dp(CompanionPrefs.size(ctx).toFloat()), CompanionPrefs.face(ctx),
                CompanionPrefs.FaceData(d?.score, open, faceTotal, coin?.first, coin?.third, trading),
            ),
        )
        fun show(v: TextView?, on: Boolean, text: String?) { v?.text = text ?: ""; v?.visibility = if (on && !text.isNullOrEmpty()) View.VISIBLE else View.GONE }
        show(totalLine, CompanionPrefs.show(ctx, "total"), totalText)
        val last = if (trading) TraderLoop.lastNote(ctx) ?: getString(R.string.trader_idle) else getString(R.string.companion_agent_off)
        show(agentLine, CompanionPrefs.show(ctx, "agent"), (open?.let { getString(R.string.companion_agent_line, it) + " · " } ?: "") + last)
        show(healthLine, CompanionPrefs.show(ctx, "health"), d?.let { getString(R.string.companion_health_line, it.score) + " · " + (it.alert ?: getString(R.string.widget_clean)) })
        healthLine?.setTextColor((if (d?.alert == null) p.accent else p.amber).toArgb())
        show(coinLine, CompanionPrefs.show(ctx, "coin"), coin?.let { (sym, usd, ch) ->
            sym + " " + (usd?.let { fmtPrice(it, "USD") } ?: "…") + (ch?.let { String.format(java.util.Locale.ROOT, "  %+.1f%%", it) } ?: "")
        })
        coinLine?.setTextColor((if ((coin?.third ?: 0.0) >= 0) p.accent else p.red).toArgb())
    }

    /** "80 €", "1,2k €": a total that fits a circle. */
    private fun compact(v: Double, cur: String): String {
        val sym = if (cur == "SOL") "SOL" else runCatching { java.util.Currency.getInstance(cur).symbol }.getOrDefault(cur)
        val n = when {
            v >= 1_000_000 -> String.format(java.util.Locale.getDefault(), "%.1fM", v / 1e6)
            v >= 10_000 -> String.format(java.util.Locale.getDefault(), "%.0fk", v / 1e3)
            v >= 1_000 -> String.format(java.util.Locale.getDefault(), "%.1fk", v / 1e3)
            else -> String.format(java.util.Locale.getDefault(), "%.0f", v)
        }
        return "$n $sym"
    }

    /**
     * Same ring as the home-screen widget, sized for the bubble.
     *
     * [openPositions] is non-null only while the agent is trading, and then it
     * takes over the face: a full ring in the accent colour with the number of
     * coins it is holding. A wallet health of 100 that has been 100 for a week
     * is not news; an agent spending money on its own is.
     */
    private fun ringBitmap(score: Int?, openPositions: Int?): Bitmap {
        val p = Halo.palette
        val px = dp(54f).coerceAtLeast(96)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val tint = when {
            openPositions != null -> p.accent
            score == null -> p.muted
            score >= 80 -> p.accent
            score >= 50 -> p.amber
            else -> p.red
        }.toArgb()
        // Opaque disc so the bubble reads on any wallpaper.
        c.drawCircle(px / 2f, px / 2f, px / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.ground.toArgb() })
        val w = px * 0.1f
        val rect = RectF(w, w, px - w, px - w)
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND }
        arc.color = p.stroke.toArgb(); c.drawArc(rect, 0f, 360f, false, arc)
        val sweep = if (openPositions != null) 360f else if (score != null) 360f * score.coerceIn(0, 100) / 100f else 0f
        if (sweep > 0f) { arc.color = tint; c.drawArc(rect, -90f, sweep, false, arc) }
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.ink.toArgb(); textAlign = Paint.Align.CENTER; textSize = px * 0.34f; isFakeBoldText = true
        }
        val face = openPositions?.toString() ?: score?.toString() ?: "\u2013"
        c.drawText(face, px / 2f, px / 2f + t.textSize * 0.35f, t)
        // A small mark so a "0" while it hunts cannot be mistaken for a health score.
        if (openPositions != null) {
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
            c.drawCircle(px * 0.82f, px * 0.18f, px * 0.09f, dot)
        }
        return bmp
    }

    // ---- foreground notification ------------------------------------------

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.companion_title), NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) },
            )
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CompanionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.companion_title))
            .setContentText(getString(R.string.companion_running))
            .addAction(Notification.Action.Builder(null, getString(R.string.companion_hide), stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "companion"
        private const val NOTIF_ID = 4711
        const val ACTION_STOP = "com.clearsign.app.COMPANION_STOP"

        /** True when Android will let us draw over other apps. */
        fun canRun(ctx: Context): Boolean = android.provider.Settings.canDrawOverlays(ctx)

        fun start(ctx: Context) {
            if (!canRun(ctx)) return
            ctx.startForegroundService(Intent(ctx, CompanionService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CompanionService::class.java).setAction(ACTION_STOP))
        }
    }
}
