package com.clearsign.app

import android.content.Context
import com.clearsign.core.NATIVE_SOL_MINT
import com.clearsign.core.Receipt
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the agent is currently holding, and what it paid.
 *
 * Nothing in the app remembered this before. [AnalyticsEngine] works out a FIFO
 * profit and loss from the ledger, but it does it **in euros**, and the euro
 * snapshot is written by `FiatRates.fillAsync` on a process-lifetime scope with
 * nobody waiting for it. A trade signed inside a short-lived worker can reach
 * the ledger with no price attached, and a position with no cost is a position
 * no stop-loss can protect.
 *
 * So this store keeps its own copy, and keeps the cost **in lamports**. SOL is
 * what we paid with and what we will sell back into, so the comparison the loop
 * actually makes needs no exchange rate, no network call, and nothing that can
 * arrive late.
 */
object Positions {
    private const val PREFS = "apex_positions"
    private const val KEY = "open"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * One holding the loop is watching.
     *
     * [units] is in whole tokens, not raw: the raw amount depends on decimals we
     * would have to carry everywhere, and the sell side needs whole units anyway.
     */
    data class Position(
        val mint: String,
        val symbol: String,
        val decimals: Int,
        val units: Double,
        /** What left the budget to buy it, fees included. The denominator of every exit. */
        val costLamports: Long,
        val openedAt: Long,
        /** Sell when it is worth this much more than it cost. */
        val takeProfitPct: Int,
        /** Sell when it is worth this much less. Zero means no stop. */
        val stopLossPct: Int,
        /** The Jupiter Trigger order account, when the take-profit is on chain. */
        val triggerOrder: String? = null,
        /** Set once the loop has sold it, so a slow confirmation cannot sell twice. */
        val closing: Boolean = false,
    ) {
        /** Price per whole token, in lamports. Null when the position is empty. */
        val entryLamports: Double? get() = if (units > 0) costLamports / units else null

        /**
         * What to do at [nowLamports] per token: 1 to sell, 0 to hold, null when
         * we cannot tell. Never a guess: an unknown price holds.
         */
        fun verdict(nowLamports: Double?): Exit? {
            val entry = entryLamports ?: return null
            if (nowLamports == null || nowLamports <= 0 || entry <= 0) return null
            val movePct = (nowLamports - entry) / entry * 100.0
            return when {
                takeProfitPct > 0 && movePct >= takeProfitPct -> Exit(Exit.Why.TARGET, movePct)
                stopLossPct > 0 && movePct <= -stopLossPct -> Exit(Exit.Why.STOP, movePct)
                else -> null
            }
        }
    }

    /** Why the loop is selling, and by how much it moved. */
    data class Exit(val why: Why, val movePct: Double) {
        enum class Why { TARGET, STOP }
    }

    fun all(ctx: Context): List<Position> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }
        }.getOrDefault(emptyList())
    }

    fun open(ctx: Context): List<Position> = all(ctx).filter { !it.closing }

    private fun save(ctx: Context, list: List<Position>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /**
     * Record a buy. Adding to a mint we already hold merges the two into one
     * average, rather than keeping two lots: the loop sells a whole holding at
     * once, so two lots with two entry prices would only be a way to disagree
     * with ourselves about what we paid.
     */
    fun add(ctx: Context, p: Position) {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.mint == p.mint && !it.closing }
        if (i >= 0) {
            val old = list[i]
            list[i] = old.copy(
                units = old.units + p.units,
                costLamports = old.costLamports + p.costLamports,
                takeProfitPct = p.takeProfitPct, stopLossPct = p.stopLossPct,
                triggerOrder = p.triggerOrder ?: old.triggerOrder,
            )
        } else {
            list += p
        }
        save(ctx, list)
    }

    /** Mark it as on its way out, so a second tick cannot sell it again. */
    fun markClosing(ctx: Context, mint: String) {
        save(ctx, all(ctx).map { if (it.mint == mint) it.copy(closing = true) else it })
    }

    /** The sale failed: put it back in play rather than stranding it half closed. */
    fun reopen(ctx: Context, mint: String) {
        save(ctx, all(ctx).map { if (it.mint == mint) it.copy(closing = false) else it })
    }

    fun remove(ctx: Context, mint: String) = save(ctx, all(ctx).filter { it.mint != mint })

    fun setTrigger(ctx: Context, mint: String, order: String?) {
        save(ctx, all(ctx).map { if (it.mint == mint) it.copy(triggerOrder = order) else it })
    }

    fun clear(ctx: Context) = prefs(ctx).edit().remove(KEY).apply()

    /**
     * Read a buy out of the receipt that was just signed.
     *
     * The receipt is the honest source: it is what the simulation said would
     * really happen, not what the model claimed it was doing. A swap that paid
     * SOL for exactly one other token is a buy; anything else is not something
     * this store can describe, and is left alone.
     */
    fun fromReceipt(receipt: Receipt, owner: String, takeProfitPct: Int, stopLossPct: Int, at: Long = System.currentTimeMillis()): Position? {
        fun isSol(m: String) = m == NATIVE_SOL_MINT || m == com.clearsign.core.AgentPolicy.WSOL
        val paid = receipt.outflows.filter { it.owner == owner && isSol(it.mint) }.sumOf { -it.rawAmount }
        if (paid <= 0) return null
        val got = receipt.inflows.filter { it.owner == owner && !isSol(it.mint) }
        val leg = got.singleOrNull() ?: return null
        val units = leg.rawAmount / Math.pow(10.0, leg.decimals.toDouble())
        if (units <= 0) return null
        return Position(
            mint = leg.mint, symbol = leg.symbol, decimals = leg.decimals, units = units,
            // The network fee came out of the budget too, so it is part of what
            // this position has to earn back before it is actually in profit.
            costLamports = paid + receipt.feeLamports,
            openedAt = at, takeProfitPct = takeProfitPct, stopLossPct = stopLossPct,
        )
    }

    /**
     * Keep the book in step with what actually happened on chain.
     *
     * Called for every move the collar signed, whoever proposed it. A buy opens
     * or grows a position; a sale of something we hold shrinks it, and closes it
     * when the holding is gone. Doing it here rather than in the loop means a
     * coin sold by hand from the chat does not leave a ghost position behind for
     * the stop-loss to keep watching.
     */
    fun applyReceipt(ctx: Context, receipt: Receipt, owner: String, takeProfitPct: Int, stopLossPct: Int) {
        fromReceipt(receipt, owner, takeProfitPct, stopLossPct)?.let { add(ctx, it); return }
        fun isSol(m: String) = m == NATIVE_SOL_MINT || m == com.clearsign.core.AgentPolicy.WSOL
        val held = all(ctx).associateBy { it.mint }
        for (leg in receipt.outflows) {
            if (leg.owner != owner || isSol(leg.mint)) continue
            val pos = held[leg.mint] ?: continue
            val sold = -leg.rawAmount / Math.pow(10.0, leg.decimals.toDouble())
            val left = pos.units - sold
            // A hair left over is dust from rounding, not a position.
            if (left <= pos.units * 0.02) { remove(ctx, leg.mint); continue }
            val share = left / pos.units
            save(
                ctx,
                all(ctx).map {
                    if (it.mint == leg.mint) it.copy(units = left, costLamports = (it.costLamports * share).toLong(), closing = false) else it
                },
            )
        }
    }

    private fun toJson(p: Position) = JSONObject()
        .put("mint", p.mint).put("symbol", p.symbol).put("decimals", p.decimals)
        .put("units", p.units).put("cost", p.costLamports).put("at", p.openedAt)
        .put("tp", p.takeProfitPct).put("sl", p.stopLossPct)
        .put("order", p.triggerOrder ?: JSONObject.NULL).put("closing", p.closing)

    private fun fromJson(o: JSONObject): Position? {
        val mint = o.optString("mint").takeIf { it.isNotEmpty() } ?: return null
        return Position(
            mint = mint,
            symbol = o.optString("symbol").takeIf { it.isNotEmpty() } ?: mint.take(4),
            decimals = o.optInt("decimals", 6),
            units = o.optDouble("units", 0.0),
            costLamports = o.optLong("cost", 0L),
            openedAt = o.optLong("at", 0L),
            takeProfitPct = o.optInt("tp", 0),
            stopLossPct = o.optInt("sl", 0),
            triggerOrder = o.optString("order").takeIf { it.isNotEmpty() && it != "null" },
            closing = o.optBoolean("closing", false),
        )
    }
}
