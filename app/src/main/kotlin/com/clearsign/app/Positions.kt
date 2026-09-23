package com.clearsign.app

import android.content.Context
import com.clearsign.core.NATIVE_SOL_MINT
import com.clearsign.core.Receipt
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the agent holds and what it paid, in lamports. [AnalyticsEngine] works out a
 * FIFO P&L from the ledger in euros, and the euro snapshot can arrive late or not at
 * all for a trade signed inside a short-lived worker; a position with no cost is one no
 * stop-loss can protect. SOL is what we paid with and sell back into, so this needs no
 * exchange rate and no network call.
 */
object Positions {
    private const val PREFS = "apex_positions"
    private const val KEY = "open"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * One holding the loop watches. [units] is whole tokens, not raw: raw depends on
     * decimals we would have to carry everywhere, and the sell side needs whole units.
     */
    @androidx.compose.runtime.Immutable
    data class Position(
        val mint: String,
        val symbol: String,
        val decimals: Int,
        val units: Double,
        /**
         * The budget that bought it. Without this the book outlived the key: a budget was
         * closed with coins inside, a new one made, and its first tick tried to sell 1214
         * LEVERCAT the new key never held. The collar read the empty simulation as lying and
         * the loop repeated the refusal eighty-four times over sixteen hours, buying nothing
         * either. A holding belongs to the key that can sign for it.
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
         * The coins sit in a Trigger order on chain, not in the wallet: Jupiter escrows them,
         * so the loop must take the order back before it can sell anything itself.
         */
        val parked: Boolean = false,
        /** Sales attempted and failed in a row. Reset by any sale that lands. */
        val fails: Int = 0,
        val lastTryAt: Long = 0L,
        /** Why the last attempt failed, in the words the person will read. */
        val lastError: String? = null,
    ) {
        /**
         * When it is worth trying again: three tries at the normal pace, then a doubling wait up
         * to six hours. A coin that cannot be sold usually stays so for a while, and retrying
         * every ninety seconds only buries the reason.
         */
        val readyAt: Long get() = if (fails < 3) 0L else lastTryAt + retryDelay(fails)
        /** Price per whole token, in lamports. Null when the position is empty. */
        val entryLamports: Double? get() = if (units > 0) costLamports / units else null

        /** What to do at [nowLamports] per token: 1 sell, 0 hold, null when we cannot tell. An unknown price holds, never a guess. */
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
     * A row is this agent's business when the budget that bought it is the one that exists
     * now. A row from before positions carried an owner has no claim either way: shown, and
     * left for [reconcile] to adopt or drop against the chain.
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
     * Record a buy. Adding to a mint we already hold merges into one average rather than
     * two lots: the loop sells a whole holding at once, so two entry prices would only be a
     * way to disagree with ourselves about what we paid.
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

    /**
     * Say something about a position without calling it a failed sale. [noteFailure] bumps
     * the retry counter and pushes the row into the slow lane, right for a sale that did not
     * work and wrong for "Jupiter will not take an order this small", which is a fact to
     * read, not something to retry. [noteFailure] returns the run of failures, so the caller
     * can speak once instead of every ninety seconds.
     */
    fun note(ctx: Context, mint: String, why: String?) = edit(ctx, mint) { it.copy(lastError = why) }

    fun noteFailure(ctx: Context, mint: String, why: String?): Int {
        var n = 0
        edit(ctx, mint) { n = it.fails + 1; it.copy(fails = n, lastTryAt = System.currentTimeMillis(), lastError = why) }
        return n
    }

    fun clear(ctx: Context) = prefs(ctx).edit().remove(KEY).apply()

    /**
     * Read a buy out of the receipt just signed. The receipt is what the simulation said
     * would happen, not what the model claimed. A swap that paid SOL for exactly one other
     * token is a buy; anything else this store cannot describe, and leaves alone.
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
     * Keep the book in step with the chain, for every move the collar signed whoever
     * proposed it: a buy opens or grows a position, a sale shrinks or closes it. Done here
     * rather than in the loop, so a coin sold by hand from the chat leaves no ghost for the
     * stop-loss to watch.
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
     * Put the book next to the chain and believe the chain. A simulation is a promise about one
     * moment; since then a budget can close, an order fill, a coin be sold from the chat, and a stale
     * row is an agent trying to sell what it does not have. [onChain] is raw units held by [owner],
     * [parkedMints] the coins inside a live Trigger order, [liveByMint] Jupiter's active orders (null
     * when not asked). Four outcomes: not our budget, ignore; coins on chain, adopt at the chain's amount; no coins but a live order, parked; neither, off the list with the reason.
     */
    fun reconcile(book: List<Position>, owner: String, onChain: Map<String, Long>, parkedMints: Set<String>, liveByMint: Map<String, String>? = null): Reconciled {
        val keep = ArrayList<Position>()
        val gone = ArrayList<Position>()
        for (p in book) {
            if (p.owner.isNotEmpty() && p.owner != owner) { gone += p; continue }
            val raw = onChain[p.mint] ?: 0L
            if (raw > 0L) {
                val held = raw / Math.pow(10.0, p.decimals.toDouble())
                // Never grow a position from a balance: more units at the same cost reads as a lower
                // entry and would sell at a target never reached. Only shrink, cost down with it.
                val units = minOf(p.units, held)
                val shrunk = units < p.units * 0.999
                // The coins are in the wallet, so no order holds them. The key goes only once Jupiter
                // confirms the order is gone: an accepted cancel that never landed once left coins in
                // escrow and the row keyless, unreachable.
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
