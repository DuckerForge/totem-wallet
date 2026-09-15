package com.clearsign.app

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The orders the person placed on Jupiter from the main account, and the
 * price alerts they asked for. Kept on the phone so the app can show them; the
 * truth about an order is on chain, and this list is corrected against it.
 *
 * An order here is one of three: sell this coin at a price above today's (take
 * profit), buy this coin at a price below today's (limit), or buy it a slice
 * at a time (DCA). All three live on Jupiter and fire with the phone off. What
 * none of them can be is a stop loss: that needs Jupiter's keyed API, and the
 * screens say so instead of implying otherwise.
 *
 * An alert costs nothing and signs nothing: a price, a direction, and one
 * notification when it is crossed. Then it is spent, so it does not ring every
 * fifteen minutes for as long as the price stays there.
 */
object Orders {
    private const val PREFS = "apex_orders"
    private const val KEY_ORDERS = "orders"
    private const val KEY_ALERTS = "alerts"

    enum class Kind { TP, LIMIT, DCA }

    data class Order(
        val kind: Kind,
        val mint: String,
        val symbol: String,
        val decimals: Int,
        /** The order account on chain: Jupiter's handle for it, and ours. */
        val key: String,
        /** What was deposited, raw units of what leaves: the coin for a sale, SOL or USDC for a buy. */
        val amountRaw: Long,
        /** The price per whole coin, in dollars, at which it fires. Zero for a DCA. */
        val targetUsd: Double,
        val createdAt: Long,
        val expiresAt: Long,
        /** For a DCA: how many rounds and how far apart, so the card can say it. */
        val rounds: Int = 0,
        val intervalSec: Long = 0L,
    )

    data class Alert(val mint: String, val symbol: String, val above: Boolean, val priceUsd: Double, val createdAt: Long) {
        val id: String get() = mint + (if (above) "+" else "-")
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Order> = runCatching {
        val a = JSONArray(prefs(ctx).getString(KEY_ORDERS, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::orderFromJson) }
    }.getOrDefault(emptyList())

    fun add(ctx: Context, o: Order) = save(ctx, listOf(o) + all(ctx).filter { it.key != o.key })
    fun remove(ctx: Context, key: String) = save(ctx, all(ctx).filter { it.key != key })

    fun alerts(ctx: Context): List<Alert> = runCatching {
        val a = JSONArray(prefs(ctx).getString(KEY_ALERTS, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::alertFromJson) }
    }.getOrDefault(emptyList())

    /** One above and one below per coin, at most: a newer one on the same side replaces the old. */
    fun addAlert(ctx: Context, a: Alert) = saveAlerts(ctx, listOf(a) + alerts(ctx).filter { it.id != a.id })
    fun removeAlert(ctx: Context, id: String) = saveAlerts(ctx, alerts(ctx).filter { it.id != id })

    // ---- the pure parts, tested ----------------------------------------------

    /**
     * The book against the chain. An order Jupiter no longer lists is filled,
     * expired or cancelled: off the list, and worth one notification. A chain
     * that could not be asked ([liveTrigger] or [liveDca] null) changes nothing
     * on that side: not knowing is not the same as gone.
     */
    fun reconcile(book: List<Order>, liveTrigger: Set<String>?, liveDca: Set<String>?): Pair<List<Order>, List<Order>> {
        val keep = ArrayList<Order>()
        val gone = ArrayList<Order>()
        for (o in book) {
            val live = if (o.kind == Kind.DCA) liveDca else liveTrigger
            // A DCA whose key Jupiter never told us is kept for as long as any
            // DCA is live, and goes when the list is empty: the nearest thing to
            // the truth without the key.
            val unnamed = o.key.startsWith("sig:")
            if (live == null || o.key in live || (unnamed && live.isNotEmpty())) keep += o else gone += o
        }
        return keep to gone
    }

    /** Which alerts the current [prices] (dollars per whole coin) have crossed. Unknown price, nothing fires. */
    fun fired(alerts: List<Alert>, prices: Map<String, Double>): List<Alert> = alerts.filter { a ->
        val p = prices[a.mint] ?: return@filter false
        if (a.above) p >= a.priceUsd else p <= a.priceUsd
    }

    fun orderToJson(o: Order): JSONObject = JSONObject()
        .put("kind", o.kind.name).put("mint", o.mint).put("symbol", o.symbol).put("decimals", o.decimals)
        .put("key", o.key).put("amount", o.amountRaw).put("target", o.targetUsd)
        .put("at", o.createdAt).put("exp", o.expiresAt).put("rounds", o.rounds).put("interval", o.intervalSec)

    fun orderFromJson(j: JSONObject): Order? {
        val key = j.optString("key").takeIf { it.isNotEmpty() } ?: return null
        val kind = runCatching { Kind.valueOf(j.optString("kind")) }.getOrNull() ?: return null
        return Order(
            kind = kind, mint = j.optString("mint"), symbol = j.optString("symbol"), decimals = j.optInt("decimals", 6),
            key = key, amountRaw = j.optLong("amount"), targetUsd = j.optDouble("target", 0.0),
            createdAt = j.optLong("at"), expiresAt = j.optLong("exp"), rounds = j.optInt("rounds"), intervalSec = j.optLong("interval"),
        )
    }

    fun alertToJson(a: Alert): JSONObject = JSONObject()
        .put("mint", a.mint).put("symbol", a.symbol).put("above", a.above).put("price", a.priceUsd).put("at", a.createdAt)

    fun alertFromJson(j: JSONObject): Alert? {
        val mint = j.optString("mint").takeIf { it.isNotEmpty() } ?: return null
        return Alert(mint, j.optString("symbol"), j.optBoolean("above"), j.optDouble("price", 0.0), j.optLong("at"))
    }

    // ---- against the chain ----------------------------------------------------

    /** Correct the list against Jupiter and say what is no longer there. Returns the orders that went. */
    suspend fun sync(ctx: Context, owner: String): List<Order> = withContext(Dispatchers.IO) {
        val book = all(ctx)
        if (book.isEmpty()) return@withContext emptyList()
        val trig = if (book.any { it.kind != Kind.DCA }) runCatching { JupiterTrigger.live(owner)?.orders }.getOrNull() else emptySet()
        val dca = if (book.any { it.kind == Kind.DCA }) runCatching { JupiterRecurring.live(owner) }.getOrNull() else emptySet()
        val (keep, gone) = reconcile(book, trig, dca)
        if (gone.isNotEmpty()) save(ctx, keep)
        gone
    }

    private fun save(ctx: Context, list: List<Order>) {
        prefs(ctx).edit().putString(KEY_ORDERS, JSONArray(list.map(::orderToJson)).toString()).apply()
    }

    private fun saveAlerts(ctx: Context, list: List<Alert>) {
        prefs(ctx).edit().putString(KEY_ALERTS, JSONArray(list.map(::alertToJson)).toString()).apply()
    }
}

/**
 * The fifteen-minute look at orders and alerts.
 *
 * Runs only while there is something to look at, and cancels itself when the
 * last order and the last alert are gone. WorkManager's floor is fifteen
 * minutes, which is fine here: an order is filled by Jupiter's keeper whether
 * we look or not, and an alert a quarter of an hour late is still an alert.
 */
object OrdersKeeper {
    private const val WORK = "apex-orders-keeper"

    fun sync(ctx: Context) {
        val needed = Orders.all(ctx).isNotEmpty() || Orders.alerts(ctx).isNotEmpty()
        if (!needed) { WorkManager.getInstance(ctx).cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<Worker>(15, TimeUnit.MINUTES, 5, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val ctx = applicationContext
            val owner = Settings.watchWallet(ctx)
            if (owner != null) {
                runCatching { Orders.sync(ctx, owner) }.getOrDefault(emptyList()).forEach { o ->
                    Watchtower.notify(ctx, ctx.getString(R.string.order_filled_title), ctx.getString(R.string.order_filled_body, o.symbol))
                }
            }
            val alerts = Orders.alerts(ctx)
            if (alerts.isNotEmpty()) {
                val prices = withContext(Dispatchers.IO) { runCatching { Prices.usd(alerts.map { it.mint }) }.getOrDefault(emptyMap()) }
                Orders.fired(alerts, prices).forEach { a ->
                    Watchtower.notify(
                        ctx, ctx.getString(R.string.alert_hit_title),
                        ctx.getString(
                            R.string.alert_hit_body, a.symbol,
                            ctx.getString(if (a.above) R.string.order_above else R.string.order_below).lowercase(),
                            fmtPrice(a.priceUsd, "USD"),
                        ),
                    )
                    Orders.removeAlert(ctx, a.id)
                }
            }
            sync(ctx)
            return Result.success()
        }
    }
}
