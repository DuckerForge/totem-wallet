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
    private var scoreLine: TextView? = null
    private var balanceLine: TextView? = null
    private var alertLine: TextView? = null
    private var agentLine: TextView? = null

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

        // Collapsed: the health ring.
        val ring = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(54f), dp(54f))
            setImageBitmap(ringBitmap(null, null))
        }
        container.addView(ring)

        // Expanded: a compact panel.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(p.ground.toArgb())
                setStroke(dp(1f), p.stroke.toArgb())
            }
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            layoutParams = FrameLayout.LayoutParams(dp(232f), FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        val title = TextView(this).apply {
            text = getString(R.string.widget_health)
            setTextColor(p.muted.toArgb()); textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val score = TextView(this).apply { setTextColor(p.ink.toArgb()); textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        val balance = TextView(this).apply { setTextColor(p.accent2.toArgb()); textSize = 13f }
        val alert = TextView(this).apply { setTextColor(p.amber.toArgb()); textSize = 11f; maxLines = 2 }
        val agent = TextView(this).apply { setTextColor(p.accent2.toArgb()); textSize = 11f; maxLines = 2; visibility = View.GONE }
        agentLine = agent
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10f), 0, 0)
        }
        actions.addView(button(getString(R.string.companion_open), p.accent.toArgb()) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            collapse()
        })
        actions.addView(button(getString(R.string.companion_hide), p.muted.toArgb()) { stopSelf() })

        card.addView(title); card.addView(score); card.addView(balance); card.addView(alert); card.addView(agent); card.addView(actions)
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
        scoreLine = score; balanceLine = balance; alertLine = alert
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
        render(HealthWidgetData.load(this))
        scope.launch {
            if (HealthWidgetData.isStale(this@CompanionService)) {
                withContext(Dispatchers.IO) { runCatching { HealthWidgetData.refresh(this@CompanionService, updateWidgets = false) } }
                render(HealthWidgetData.load(this@CompanionService))
            }
        }
    }

    private fun render(d: HealthWidgetData.Snapshot?) {
        val p = Halo.palette
        // While the agent is working, the bubble stops being a health badge and
        // becomes a state light: a health score you already know does not need
        // watching, and money moving on its own does.
        val trading = TraderLoop.config(this).on
        bubble?.setImageBitmap(ringBitmap(d?.score, if (trading) Positions.open(this).size else null))
        if (d == null) {
            scoreLine?.text = getString(R.string.widget_no_wallet)
            balanceLine?.text = ""
            alertLine?.text = ""
            return
        }
        scoreLine?.text = fmtSol(d.lamports, 4) + " SOL"
        balanceLine?.text = d.fiat?.let { fmtFiat(it, d.currency) } ?: ""
        alertLine?.text = d.alert ?: getString(R.string.widget_clean)
        alertLine?.setTextColor((if (d.alert == null) p.accent else p.amber).toArgb())
        // The agent's last move rides on the bubble: the "sticker" that tells you it acted.
        val last = if (trading) TraderLoop.lastNote(this) ?: getString(R.string.trader_idle)
        else (AgentLink.state.value as? AgentLink.State.On)?.lastAction
        agentLine?.text = last ?: d.agent ?: ""
        agentLine?.visibility = if (last != null || d.agent != null) View.VISIBLE else View.GONE
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
