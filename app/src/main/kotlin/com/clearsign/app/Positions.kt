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
        /**
         * The budget that bought it.
         *
         * Without this the book outlived the key. A budget was closed with coins
         * inside, a new one was made, and its first tick tried to sell 1214
         * LEVERCAT that the new key had never held: the simulation moved nothing,
         * the collar read that as the agent lying, and the loop repeated the same
         * refusal eighty-four times over sixteen hours without buying anything
         * either, because a position with an exit due stops the tick before the
         * hunt. A holding belongs to the key that can sign for it.
         */
        val owner: String = "",
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
        /**
         * The coins are inside a Trigger order on chain, not in the wallet.
         * Jupiter escrows them when the order is placed, so the loop must take
         * the order back before it can sell anything itself.
         */
        val parked: Boolean = false,
        /** Sales attempted and failed in a row. Reset by any sale that lands. */
        val fails: Int = 0,
        val lastTryAt: Long = 0L,
        /** Why the last attempt failed, in the words the person will read. */
        val lastError: String? = null,
    ) {
        /**
         * When it is worth trying again.
         *
         * Three tries at the normal pace, then a doubling wait up to six hours.
         * A coin that cannot be sold is usually a coin that cannot be sold for a
         * while, and retrying it every ninety seconds only buries the reason.
         */
        val readyAt: Long get() = if (fails < 3) 0L else lastTryAt + retryDelay(fails)
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

    /** Everything on disk, whoever it belonged to. */
    private fun stored(ctx: Context): List<Position> {
        val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }
        }.getOrDefault(emptyList())
    }

    private fun ownerNow(ctx: Context): String? = SessionWallet.current(ctx)?.pubkey

    /**
     * A row is this agent's business when the budget that bought it is the budget
     * that exists now. A row written before positions carried an owner has no
     * claim either way, so it is shown and left for [reconcile] to adopt or drop
     * against the chain.
     */
    private fun mine(p: Position, owner: String?) = p.owner.isEmpty() || p.owner == owner

    fun all(ctx: Context): List<Position> = ownerNow(ctx).let { o -> stored(ctx).filter { mine(it, o) } }

    fun open(ctx: Context): List<Position> = all(ctx).filter { !it.closing }

    /** Retry wait after [fails] failures in a row: none, then five minutes doubling to six hours. */
    fun retryDelay(fails: Int): Long =
        if (fails < 3) 0L else (5 * 60_000L shl (fails - 3).coerceAtMost(7)).coerceAtMost(6 * 3_600_000L)

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
        val list = stored(ctx).toMutableList()
        val i = list.indexOfFirst { it.mint == p.mint && it.owner == p.owner && !it.closing }
        if (i >= 0) {
            val old = list[i]
            list[i] = old.copy(
                units = old.units + p.units,
                costLamports = old.costLamports + p.costLamports,
                takeProfitPct = p.takeProfitPct, stopLossPct = p.stopLossPct,
                triggerOrder = p.triggerOrder ?: old.triggerOrder,
                // Money went in again, so whatever went wrong last time is history.
                fails = 0, lastError = null,
            )
        } else {
            list += p
        }
        save(ctx, list)
    }

    /* Change the one row for [mint] that belongs to the budget in use. */
    private fun edit(ctx: Context, mint: String, f: (Position) -> Position) {
        val o = ownerNow(ctx)
        save(ctx, stored(ctx).map { if (it.mint == mint && mine(it, o)) f(it) else it })
    }

    /* Mark it as on its way out, so a second tick cannot sell it again. */
    fun markClosing(ctx: Context, mint: String) = edit(ctx, mint) { it.copy(closing = true) }

    /* The sale failed: put it back in play rather than stranding it half closed. */
    fun reopen(ctx: Context, mint: String) = edit(ctx, mint) { it.copy(closing = false) }

    fun remove(ctx: Context, mint: String) {
        val o = ownerNow(ctx)
        save(ctx, stored(ctx).filter { !(it.mint == mint && mine(it, o)) })
    }

    fun setTrigger(ctx: Context, mint: String, order: String?) = edit(ctx, mint) { it.copy(triggerOrder = order) }

    /*
     * Write down that a sale did not happen, and why. Returns how many times in a
     * row this one has failed, so the caller can say something once instead of
     * every ninety seconds.
     */
    /**
     * Say something about a position without calling it a failed sale.
     *
     * [noteFailure] bumps the retry counter and pushes the row into the slow
     * lane, which is right for a sale that did not work and wrong for "Jupiter
     * will not take an order this small". That one is a fact about the position
     * that the person has to be able to read, and nothing to retry.
     */
    fun note(ctx: Context, mint: String, why: String?) = edit(ctx, mint) { it.copy(lastError = why) }

    fun noteFailure(ctx: Context, mint: String, why: String?): Int {
        var n = 0
        edit(ctx, mint) { n = it.fails + 1; it.copy(fails = n, lastTryAt = System.currentTimeMillis(), lastError = why) }
        return n
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
            mint = leg.mint, symbol = leg.symbol, decimals = leg.decimals, units = units, owner = owner,
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
            edit(ctx, leg.mint) {
                it.copy(units = left, costLamports = (it.costLamports * share).toLong(), closing = false, fails = 0, lastError = null)
            }
        }
    }

    // ---- keeping the book honest --------------------------------------------

    /** What [reconcile] decided: what stays, and what was never really there. */
    data class Reconciled(val keep: List<Position>, val gone: List<Position>, val changed: Boolean)

    /**
     * Put the book next to the chain and believe the chain.
     *
     * The book is written from simulations, and a simulation is a promise about
     * one moment. Between then and now a budget can be closed, an order can fill,
     * a coin can be sold from the chat or moved by hand. Everything the loop does
     * afterwards is priced off this list, so a row that is no longer true is not a
     * cosmetic problem: it is an agent trying to sell something it does not have,
     * failing, and blocking every other thing it was going to do that minute.
     *
     * [onChain] is raw units per mint held by [owner], [parkedMints] are the coins
     * sitting in a live Trigger order, which have left the wallet without being
     * sold. [liveByMint] is what Jupiter lists as active, coin to order key, or
     * null when Jupiter was not asked: an order key is only dropped when Jupiter
     * says the order is gone, never on a guess, and a parked row that lost its
     * key takes the one Jupiter has. Four outcomes and no fifth:
     *
     *  * a row from another budget is not this agent's business, at all;
     *  * coins on chain: the row is adopted, and the amount is taken from the
     *    chain rather than from what we remember;
     *  * no coins but a live order: parked, not lost, and not sellable until the
     *    order is taken back;
     *  * no coins and no order: it is not there. Off the list, with the reason.
     */
    fun reconcile(book: List<Position>, owner: String, onChain: Map<String, Long>, parkedMints: Set<String>, liveByMint: Map<String, String>? = null): Reconciled {
        val keep = ArrayList<Position>()
        val gone = ArrayList<Position>()
        for (p in book) {
            if (p.owner.isNotEmpty() && p.owner != owner) { gone += p; continue }
            val raw = onChain[p.mint] ?: 0L
            if (raw > 0L) {
                val held = raw / Math.pow(10.0, p.decimals.toDouble())
                // Never grow a position from a balance: a bigger holding with the
                // same cost reads as a lower entry price, which would sell a coin
                // at a target it never reached. Only shrink, and take the cost
                // down with it so the entry price stays what we actually paid.
                val units = minOf(p.units, held)
                val shrunk = units < p.units * 0.999
                // The coins are in the wallet, so no order is holding them. But
                // the key only goes once Jupiter confirms the order is not there:
                // a cancel that was accepted and never landed left the coins in
                // escrow and the row without a key, and nothing could reach it.
                val stale = liveByMint != null && p.triggerOrder != null && p.triggerOrder !in liveByMint.values
                val fixed = p.copy(
                    owner = owner, parked = false,
                    units = if (shrunk) units else p.units,
                    costLamports = if (shrunk && p.units > 0) (p.costLamports * (units / p.units)).toLong() else p.costLamports,
                    // The coins are still here, so nothing was sold, so a row left
                    // half closed by a tick that died is just a row.
                    closing = false,
                    triggerOrder = if (stale) null else p.triggerOrder,
                )
                keep += fixed
                continue
            }
            if (p.mint in parkedMints) {
                keep += p.copy(owner = owner, parked = true, closing = false, triggerOrder = p.triggerOrder ?: liveByMint?.get(p.mint))
                continue
            }
            gone += p
        }
        return Reconciled(keep, gone, changed = gone.isNotEmpty() || keep != book)
    }

    /** [reconcile] against the store. Returns the rows that were not real. */
    fun reconcile(ctx: Context, owner: String, onChain: Map<String, Long>, parkedMints: Set<String>, liveByMint: Map<String, String>? = null): List<Position> {
        val r = reconcile(stored(ctx), owner, onChain, parkedMints, liveByMint)
        if (r.changed) save(ctx, r.keep)
        return r.gone
    }

    private fun toJson(p: Position) = JSONObject()
        .put("mint", p.mint).put("symbol", p.symbol).put("decimals", p.decimals)
        .put("units", p.units).put("cost", p.costLamports).put("at", p.openedAt)
        .put("tp", p.takeProfitPct).put("sl", p.stopLossPct)
        .put("order", p.triggerOrder ?: JSONObject.NULL).put("closing", p.closing)
        .put("owner", p.owner).put("parked", p.parked)
        .put("fails", p.fails).put("tryAt", p.lastTryAt).put("err", p.lastError ?: JSONObject.NULL)

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
            owner = o.optString("owner"),
            parked = o.optBoolean("parked", false),
            fails = o.optInt("fails", 0),
            lastTryAt = o.optLong("tryAt", 0L),
            lastError = o.optString("err").takeIf { it.isNotEmpty() && it != "null" },
        )
    }
}
