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
 * The floating companion: the wallet as a bubble over every other app. Closed, a sphere with
 * two alternating faces (the agent's coin, the budget); open, the panel with the chart, the two
 * numbers that count and the two buttons that decide. Learned on the phone: Hide next to Open
 * killed the service, now it sits under the gear; the position was not saved; the panel was
 * titled "APEX". Classic Views, not Compose: an overlay has no Activity to host a Composition.
 */
class CompanionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var expanded = false
    private var settingsOpen = false
    private var hidden = false

    private var bubble: ImageView? = null
    private var panel: LinearLayout? = null
    private var mainBox: LinearLayout? = null
    private var setBox: LinearLayout? = null

    private var symbolLine: TextView? = null
    private var pctLine: TextView? = null
    private var targetLine: TextView? = null
    private var barFill: View? = null
    private var barTrack: View? = null
    private var putLine: TextView? = null
    private var nowLine: TextView? = null
    private var nowPct: TextView? = null
    private var agentLine: TextView? = null
    private var healthLine: TextView? = null
    private var moneyBox: LinearLayout? = null
    private var sellButton: TextView? = null
    private var stopButton: TextView? = null

    private var ticker: kotlinx.coroutines.Job? = null
    private var spinner: kotlinx.coroutines.Job? = null
    private var flip = false
    private var last: Shot = Shot()

    /** Everything the two faces and the panel know right now. */
    private data class Shot(
        val health: HealthWidgetData.Snapshot? = null,
        val trading: Boolean = false,
        val open: Int? = null,
        val pos: Positions.Position? = null,
        val posValue: Long? = null,
        val fundedLamports: Long = 0,
        val totalLamports: Long? = null,
        val diffLamports: Long? = null,
        val walletTotal: String? = null,
        val coinSymbol: String? = null,
        val coinChange: Double? = null,
    ) {
        val pct: Double? get() = if (fundedLamports > 0 && diffLamports != null) diffLamports * 100.0 / fundedLamports else null
        val posPct: Double? get() {
            val p = pos ?: return null
            val v = posValue ?: return null
            if (p.costLamports <= 0) return null
            return (v - p.costLamports) * 100.0 / p.costLamports
        }
    }

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
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_HIDE -> hide()
            ACTION_SHOW -> show()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    // ---- nascosta, ma viva -------------------------------------------------

    /**
     * Off the screen, not out of memory. Hiding did `stopSelf()`, and the bubble came back
     * only by finding the switch in the app. What hides with a finger must return with a
     * finger, and that finger is already on the service notification Android makes us keep.
     */
    private fun hide() {
        if (hidden) return
        hidden = true
        collapse()
        root?.let { runCatching { wm.removeView(it) } }
        root = null; bubble = null; panel = null
        ticker?.cancel(); spinner?.cancel()
        repost()
    }

    private fun show() {
        if (!hidden && root != null) return
        hidden = false
        attach()
        refresh()
        repost()
    }

    private fun repost() {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification()) }
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

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(22f).toFloat()
                setColor(p.ground2.toArgb())
                setStroke(dp(1f), p.stroke.toArgb())
            }
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            layoutParams = FrameLayout.LayoutParams(dp(PANEL_DP), FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        // ---- il pannello ---------------------------------------------------
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val symbol = TextView(this).apply {
            setTextColor(p.ink.toArgb()); textSize = 15f; maxLines = 1
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val pct = TextView(this).apply {
            setTextColor(p.accent.toArgb()); textSize = 15f; maxLines = 1
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, dp(8f), 0)
        }
        val gear = glyph(Glyph.GEAR, p.muted.toArgb()) { openSettings() }
        val close = glyph(Glyph.CLOSE, p.muted.toArgb()) { collapse() }
        head.addView(symbol); head.addView(pct); head.addView(gear); head.addView(close)

        val targets = TextView(this).apply {
            setTextColor(p.muted.toArgb()); textSize = 11f; maxLines = 1
            setPadding(0, dp(2f), 0, dp(6f))
        }

        // The bar: from minus the stop to plus the target, with the dot where you are.
        val track = View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(3f).toFloat(); setColor(p.stroke.toArgb()) }
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(6f))
        }
        val fill = View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(3f).toFloat(); setColor(p.accent.toArgb()) }
            layoutParams = FrameLayout.LayoutParams(0, dp(6f))
        }
        val bar = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6f))
            addView(track); addView(fill)
        }

        val money = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10f), 0, 0) }
        val putRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val putLabel = small(getString(R.string.env_put_in), p.muted.toArgb(), dp(96f))
        val put = mono(p.ink.toArgb())
        putRow.addView(putLabel); putRow.addView(put)
        val nowRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val nowLabel = small(getString(R.string.env_now), p.muted.toArgb(), dp(96f))
        val now = mono(p.ink.toArgb()).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        val nowP = TextView(this).apply { textSize = 11.5f; setPadding(dp(8f), 0, 0, 0); setTextColor(p.accent.toArgb()) }
        nowRow.addView(nowLabel); nowRow.addView(now); nowRow.addView(nowP)
        money.addView(putRow); money.addView(nowRow)

        val agent = TextView(this).apply { setTextColor(p.accent.toArgb()); textSize = 11.5f; maxLines = 2; setPadding(0, dp(8f), 0, 0) }
        val health = TextView(this).apply { setTextColor(p.amber.toArgb()); textSize = 11.5f; maxLines = 2; setPadding(0, dp(3f), 0, 0) }

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12f), 0, 0) }
        val sell = button(getString(R.string.notif_sell_now), p.accent.toArgb()) { sellNow() }
        val stop = button(getString(R.string.notif_stop), p.amber.toArgb()) { toggleAgent() }
        actions.addView(sell); actions.addView(stop)

        main.addView(head); main.addView(targets); main.addView(bar)
        main.addView(money); main.addView(agent); main.addView(health); main.addView(actions)

        // ---- la rotellina --------------------------------------------------
        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val shead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val stitle = TextView(this).apply {
            text = getString(R.string.comp_settings); setTextColor(p.ink.toArgb()); textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        shead.addView(stitle); shead.addView(glyph(Glyph.BACK, p.muted.toArgb()) { closeSettings() })
        settings.addView(shead)
        settings.addView(wide(getString(R.string.companion_hide), p.muted.toArgb()) { hide() })
        settings.addView(wide(getString(R.string.companion_open), p.accent.toArgb()) { open(null) })
        settings.addView(wide(getString(R.string.companion_agent), p.accent2.toArgb()) { open("agent") })
        settings.addView(wide(getString(R.string.comp_page_title), p.muted.toArgb()) { open("companion") })

        card.addView(main); card.addView(settings)
        container.addView(card)

        // Dove l'hai lasciata, o il primo posto ragionevole.
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = CompanionPrefs.spotX(this@CompanionService).takeIf { it >= 0 } ?: dp(12f)
            y = CompanionPrefs.spotY(this@CompanionService).takeIf { it >= 0 } ?: dp(220f)
        }

        container.setOnTouchListener(dragAndTap(lp))
        runCatching { wm.addView(container, lp) }.onFailure { stopSelf(); return }

        root = container; params = lp
        bubble = ring; panel = card; mainBox = main; setBox = settings
        symbolLine = symbol; pctLine = pct; targetLine = targets; barFill = fill; barTrack = track
        putLine = put; nowLine = now; nowPct = nowP; agentLine = agent; healthLine = health; moneyBox = money
        sellButton = sell; stopButton = stop

        // Alive on its own: every minute the numbers redraw. The beat stays a minute because
        // what really moves is the value of what we hold, asked of Jupiter, not the chain. The
        // balance is no longer asked every beat, see [chainEvery]: a bubble asked it 1,440 times
        // a day, more than a working agent, for a number that changes only on a buy or a sale.
        ticker?.cancel()
        ticker = scope.launch { while (true) { kotlinx.coroutines.delay(60_000); refresh() } }
        startSpinner()
    }

    /**
     * How often the chain is bothered for the balance; the beat is one minute. It was five
     * minutes, 288 reads a day per bubble, three million a day at ten thousand bubbles on keys
     * that give eight a month in all. The number changes only on a buy or a sale, and whoever
     * moves it redraws the screen anyway: half an hour loses nothing.
     */
    private val chainEvery = 30 * 60_000L
    private var freeAt = 0L
    private var freeCached: Long? = null

    /**
     * The budget's free SOL, asked of the chain at most every [chainEvery]. Between reads the
     * last known number is redrawn, and it is still true: a budget's balance moves only when
     * the agent buys or sells, and whoever moved it updates the screen. A failed read keeps the
     * previous one, for the reason in HealthWidgetData.refresh: a silent node is not an empty wallet.
     */
    private suspend fun freeLamports(pubkey: String): Long? {
        val now = System.currentTimeMillis()
        if (freeCached != null && now - freeAt < chainEvery) return freeCached
        val read = withContext(Dispatchers.IO) { runCatching { SolanaRpc.getBalance(SolanaRpc.urlFor(null), pubkey) }.getOrNull() }
        if (read != null) { freeCached = read; freeAt = now }
        return read ?: freeCached
    }

    /**
     * The two faces in turn, asking the network nothing. Turns every four seconds but does
     * not reload: it redraws the same snapshot with the other face; the sixty-second tick
     * brings the data. A bubble polling every four seconds would drain the phone to show the same number.
     */
    private fun startSpinner() {
        spinner?.cancel()
        if (CompanionPrefs.face(this) != CompanionPrefs.Face.ROTATE) return
        spinner = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(4_000)
                flip = !flip
                if (!expanded) paintFace()
            }
        }
    }

    // ---- i pezzi di interfaccia -------------------------------------------

    private fun small(label: String, tint: Int, width: Int) = TextView(this).apply {
        text = label; setTextColor(tint); textSize = 11.5f
        layoutParams = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun mono(tint: Int) = TextView(this).apply {
        setTextColor(tint); textSize = 12f
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private fun button(label: String, tint: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(tint); textSize = 12f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
        background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(AColor.argb(34, AColor.red(tint), AColor.green(tint), AColor.blue(tint)))
        }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8f) }
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    /** One gear-menu entry: the whole row, stacked. */
    private fun wide(label: String, tint: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(tint); textSize = 12.5f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(AColor.argb(30, AColor.red(tint), AColor.green(tint), AColor.blue(tint)))
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8f) }
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private enum class Glyph { GEAR, CLOSE, BACK }

    /**
     * The three glyphs, drawn by hand. The rest of the app uses its own icon set, never emoji
     * or system icons; outside Compose that set cannot be called, so the same two Canvas lines
     * draw them, rather than a typographic character that changes face on every phone.
     */
    private fun glyph(kind: Glyph, tint: Int, onClick: () -> Unit) = ImageView(this).apply {
        val px = dp(22f)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tint; style = Paint.Style.STROKE; strokeWidth = px * 0.09f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val m = px / 2f
        when (kind) {
            Glyph.CLOSE -> {
                c.drawLine(px * 0.3f, px * 0.3f, px * 0.7f, px * 0.7f, s)
                c.drawLine(px * 0.7f, px * 0.3f, px * 0.3f, px * 0.7f, s)
            }
            Glyph.BACK -> {
                c.drawLine(px * 0.68f, px * 0.5f, px * 0.32f, px * 0.5f, s)
                c.drawLine(px * 0.32f, px * 0.5f, px * 0.46f, px * 0.36f, s)
                c.drawLine(px * 0.32f, px * 0.5f, px * 0.46f, px * 0.64f, s)
            }
            Glyph.GEAR -> {
                c.drawCircle(m, m, px * 0.17f, s)
                val teeth = Paint(s).apply { strokeWidth = px * 0.11f }
                repeat(8) { i ->
                    val a = Math.toRadians(i * 45.0)
                    val x0 = m + (px * 0.27f) * kotlin.math.cos(a).toFloat()
                    val y0 = m + (px * 0.27f) * kotlin.math.sin(a).toFloat()
                    val x1 = m + (px * 0.40f) * kotlin.math.cos(a).toFloat()
                    val y1 = m + (px * 0.40f) * kotlin.math.sin(a).toFloat()
                    c.drawLine(x0, y0, x1, y1, teeth)
                }
            }
        }
        setImageBitmap(bmp)
        layoutParams = LinearLayout.LayoutParams(px, px).apply { marginStart = dp(6f) }
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
                    if (moved) {
                        // It remembers where you left it, even after the service restarts.
                        CompanionPrefs.setSpot(this@CompanionService, lp.x, lp.y)
                    } else if (System.currentTimeMillis() - downAt < 400) {
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
        settingsOpen = false
        setBox?.visibility = View.GONE
        mainBox?.visibility = View.VISIBLE
        bubble?.visibility = View.GONE
        panel?.visibility = View.VISIBLE
        // The panel is ten times the bubble's width: docked right or low, open it would
        // leave the screen. The anchor shifts just enough to stay inside, and returns
        // when it closes.
        params?.let { lp ->
            val m = resources.displayMetrics
            val w = dp(PANEL_DP)
            if (lp.x + w > m.widthPixels) { lp.x = (m.widthPixels - w).coerceAtLeast(0); runCatching { wm.updateViewLayout(root, lp) } }
        }
        refresh()
    }

    private fun collapse() {
        expanded = false
        settingsOpen = false
        setBox?.visibility = View.GONE
        mainBox?.visibility = View.VISIBLE
        panel?.visibility = View.GONE
        bubble?.visibility = View.VISIBLE
        params?.let { lp ->
            val x = CompanionPrefs.spotX(this)
            if (x >= 0 && x != lp.x) { lp.x = x; runCatching { wm.updateViewLayout(root, lp) } }
        }
    }

    private fun openSettings() {
        settingsOpen = true
        mainBox?.visibility = View.GONE
        setBox?.visibility = View.VISIBLE
    }

    private fun closeSettings() {
        settingsOpen = false
        setBox?.visibility = View.GONE
        mainBox?.visibility = View.VISIBLE
    }

    private fun open(where: String?) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .apply { if (where != null) putExtra("open", where) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        collapse()
    }

    // ---- i due tasti che decidono -----------------------------------------

    /**
     * Selling from here is selling from inside the app: the same door as the notification and
     * the in-app row, `SessionActions.sellSaid`, the collar. Below the silent threshold it
     * signs alone, above it opens the receipt and asks for the print. The bubble decides nothing.
     */
    private fun sellNow() {
        val pos = last.pos ?: return
        sellButton?.isEnabled = false
        scope.launch {
            val said = runCatching { SessionActions.sellSaid(this@CompanionService, pos, AgentBroker.Job.Source.LINK) }.getOrNull()
            sellButton?.isEnabled = true
            said?.let { AgentBroker.warn(this@CompanionService, pos.symbol, it.text, rhythm = AgentBroker.Rhythm.STOP) }
            refresh()
        }
    }

    /**
     * The one button: stop when it runs, start when it does not. It used to stop in both
     * cases, writing "stopped from the notification" as the last word of a loop never started.
     * When starting is impossible the reason is the loop's own, said here.
     */
    private fun toggleAgent() {
        val ctx = this
        if (TraderLoop.config(ctx).on) {
            TraderLoop.stopSelf(ctx, getString(R.string.comp_stopped_by_you))
            refresh()
            return
        }
        val why = TraderLoop.cannotStart(ctx)
        if (why != null) {
            agentLine?.text = why
            agentLine?.visibility = View.VISIBLE
            agentLine?.setTextColor(Halo.palette.amber.toArgb())
            return
        }
        TraderLoop.start(ctx, TraderLoop.config(ctx))
        TraderKeeper.sync(ctx)
        refresh()
    }

    // ---- data --------------------------------------------------------------

    private fun refresh() {
        if (root == null) return
        render()
        scope.launch {
            val ctx = this@CompanionService
            val trading = TraderLoop.config(ctx).on
            val opens = Positions.open(ctx)
            val pos = opens.firstOrNull()
            val health = withContext(Dispatchers.IO) {
                if (HealthWidgetData.isStale(ctx)) runCatching { HealthWidgetData.refresh(ctx, updateWidgets = false) }
                HealthWidgetData.load(ctx)
            }
            // The budget: what you put in, and what it is worth now between free SOL and
            // coins in hand. Same arithmetic as the Agent card.
            val s = SessionWallet.current(ctx)
            var funded = 0L; var total: Long? = null; var diff: Long? = null; var posValue: Long? = null
            if (s != null) {
                funded = s.fundedLamports
                val free = freeLamports(s.pubkey)
                val inCoins = opens.sumOf { runCatching { quote(it) }.getOrNull() ?: 0L }
                posValue = pos?.let { runCatching { quote(it) }.getOrNull() }
                if (free != null) {
                    total = free + inCoins
                    diff = total - funded + s.harvestedLamports
                }
            }
            val owner = Settings.watchWallet(ctx)
            val currency = Settings.currency.value
            val wallet = if (owner != null && CompanionPrefs.face(ctx) == CompanionPrefs.Face.TOTAL) withContext(Dispatchers.IO) {
                Portfolio.cached(owner, currency) ?: runCatching { Portfolio.load(ctx, owner, currency) }.getOrNull()
            } else null
            val mint = CompanionPrefs.coin(ctx)
            val watched = if (mint != null && CompanionPrefs.face(ctx) == CompanionPrefs.Face.COIN) withContext(Dispatchers.IO) {
                runCatching { Prices.quotes(listOf(mint))[mint] }.getOrNull()?.let { q -> TokenSymbols.symbol(mint) to q.change24h }
            } else null

            last = Shot(
                health = health, trading = trading, open = if (trading) opens.size else null,
                pos = pos, posValue = posValue,
                fundedLamports = funded, totalLamports = total, diffLamports = diff,
                walletTotal = wallet?.let { compact(it.total, it.currency) },
                coinSymbol = watched?.first, coinChange = watched?.second,
            )
            render()
        }
    }

    private suspend fun quote(pos: Positions.Position): Long? = SessionActions.quoteValue(this, pos)

    /** The face alone: the one that changes every four seconds. */
    private fun paintFace() {
        val chosen = CompanionPrefs.face(this)
        val face = if (chosen != CompanionPrefs.Face.ROTATE) chosen else {
            // The coin and the budget in turn. With nothing open the coin has nothing to
            // say, and the agent's dot takes its place.
            if (flip || last.pos == null) CompanionPrefs.Face.BUDGET else CompanionPrefs.Face.COIN
        }
        val sym = last.pos?.symbol ?: last.coinSymbol
        val change = last.posPct ?: last.coinChange
        bubble?.setImageBitmap(
            CompanionPrefs.faceBitmap(
                dp(CompanionPrefs.size(this).toFloat()), face,
                CompanionPrefs.FaceData(
                    last.health?.score, last.open, last.walletTotal, sym, change, last.trading,
                    budgetText = last.totalLamports?.let { fmtSol(it, 3) + " SOL" },
                    budgetChange = last.pct,
                ),
            ),
        )
    }

    private fun render() {
        val ctx = this
        val p = Halo.palette
        paintFace()
        // The notification line follows the bubble: same numbers, no second truth.
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification()) }

        val pos = last.pos
        val move = last.posPct
        // The title is the coin, or what the loop is doing, or the app's name:
        // "agent off" as a title and again as a line under it was one sentence
        // said twice.
        symbolLine?.text = pos?.symbol ?: getString(if (last.trading) R.string.trader_idle else R.string.app_name)
        pctLine?.text = move?.let { String.format(java.util.Locale.ROOT, "%+.1f%%", it) } ?: ""
        pctLine?.setTextColor((if ((move ?: 0.0) >= 0) p.accent else p.red).toArgb())
        targetLine?.visibility = if (pos == null) View.GONE else View.VISIBLE
        targetLine?.text = pos?.let { getString(R.string.comp_targets, it.takeProfitPct, it.stopLossPct) }

        // The bar runs from −stop to +target, and the fill is where you are now.
        barTrack?.visibility = if (pos == null) View.GONE else View.VISIBLE
        barFill?.visibility = if (pos == null) View.GONE else View.VISIBLE
        if (pos != null) {
            val lo = -pos.stopLossPct.toDouble()
            val hi = pos.takeProfitPct.toDouble()
            val k = (((move ?: 0.0) - lo) / (hi - lo)).coerceIn(0.0, 1.0)
            val full = panel?.width?.takeIf { it > 0 }?.minus(dp(28f)) ?: dp(PANEL_DP - 28f)
            barFill?.layoutParams = FrameLayout.LayoutParams((full * k).toInt().coerceAtLeast(dp(4f)), dp(6f))
            barFill?.requestLayout()
            (barFill?.background as? GradientDrawable)?.setColor((if ((move ?: 0.0) >= 0) p.accent else p.red).toArgb())
        }

        // No budget, no money rows: "you put in 0 SOL" is not information.
        moneyBox?.visibility = if (last.fundedLamports > 0L || last.totalLamports != null) View.VISIBLE else View.GONE
        putLine?.text = fmtSol(last.fundedLamports, 4) + " SOL"
        nowLine?.text = last.totalLamports?.let { fmtSol(it, 4) + " SOL" } ?: "…"
        nowLine?.setTextColor((if ((last.diffLamports ?: 0L) >= 0) p.accent else p.red).toArgb())
        nowPct?.text = last.pct?.let { String.format(java.util.Locale.ROOT, "%+.1f%%", it) } ?: ""
        nowPct?.setTextColor((if ((last.diffLamports ?: 0L) >= 0) p.accent else p.red).toArgb())

        fun show(v: TextView?, on: Boolean, text: String?) { v?.text = text ?: ""; v?.visibility = if (on && !text.isNullOrEmpty()) View.VISIBLE else View.GONE }
        val note = if (last.trading) TraderLoop.lastNote(ctx) ?: getString(R.string.trader_idle) else getString(R.string.companion_agent_off)
        show(agentLine, CompanionPrefs.show(ctx, "agent"), note)
        val alert = last.health?.alert?.takeIf { it.isNotBlank() }
        show(healthLine, CompanionPrefs.show(ctx, "health"), last.health?.let { getString(R.string.companion_health_line, it.score) + " · " + (alert ?: getString(R.string.widget_clean)) })
        healthLine?.setTextColor((if (alert == null) p.accent else p.amber).toArgb())

        sellButton?.visibility = if (pos == null) View.GONE else View.VISIBLE
        // Start is the one full button here; stop is the quiet amber one.
        stopButton?.let { b ->
            val on = last.trading
            b.text = if (on) getString(R.string.notif_stop) else getString(R.string.agent_start)
            b.setTextColor((if (on) p.amber else p.ground).toArgb())
            val am = p.amber.toArgb()
            (b.background as? GradientDrawable)?.setColor(if (on) AColor.argb(34, AColor.red(am), AColor.green(am), AColor.blue(am)) else p.accent.toArgb())
        }
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

    // ---- foreground notification ------------------------------------------

    /**
     * The notification a foreground service must have, as small as Android allows: lowest
     * importance (no sound, no status-bar icon, a collapsed line at the bottom of the shade),
     * secret on the lock screen, deferred until the bubble has been up a while, and its one
     * line carries the bubble's numbers, a status rather than a nag. Swipeable on Android 14+.
     */
    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        // The old channel may exist at a louder importance from an earlier
        // version, and importance cannot be lowered on a channel that exists.
        runCatching { nm.deleteNotificationChannel(OLD_CHANNEL) }
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.companion_title), NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false); enableLights(false); enableVibration(false); setSound(null, null); lockscreenVisibility = Notification.VISIBILITY_SECRET },
            )
        }
        fun pi(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, CompanionService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (hidden) getString(R.string.comp_hidden) else statusLine())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_SECRET)
        if (android.os.Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
        // Hidden, you come back from here, where the finger already is.
        if (hidden) {
            b.addAction(Notification.Action.Builder(null, getString(R.string.comp_show), pi(ACTION_SHOW, 2)).build())
        } else {
            b.addAction(Notification.Action.Builder(null, getString(R.string.companion_hide), pi(ACTION_HIDE, 3)).build())
        }
        b.addAction(Notification.Action.Builder(null, getString(R.string.watch_disable), pi(ACTION_STOP, 1)).build())
        return b.build()
    }

    /** What the bubble knows, in one line: the health and the loop. */
    private fun statusLine(): String {
        val parts = ArrayList<String>()
        last.health?.let { parts += getString(R.string.companion_health_line, it.score) }
        parts += if (last.trading) getString(R.string.trader_idle) else getString(R.string.companion_agent_off)
        return parts.joinToString(" · ")
    }

    companion object {
        private const val OLD_CHANNEL = "companion"
        private const val CHANNEL = "companion_quiet"
        private const val NOTIF_ID = 4711
        private const val PANEL_DP = 300f
        const val ACTION_STOP = "com.clearsign.app.COMPANION_STOP"
        const val ACTION_HIDE = "com.clearsign.app.COMPANION_HIDE"
        const val ACTION_SHOW = "com.clearsign.app.COMPANION_SHOW"

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
