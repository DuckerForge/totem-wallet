package com.clearsign.app

import android.content.Context
import com.clearsign.core.ExitRule
import com.clearsign.core.PaperLeg
import com.clearsign.core.netLamports
import com.clearsign.core.step
import org.json.JSONArray
import org.json.JSONObject

/**
 * The shadow book: what every exit rule would have done, on the coins this agent
 * actually met.
 *
 * A target and a stop are two numbers somebody picked once. Nobody — not a blog,
 * not us — knows whether +30/−15 is right for the coins *this* scan finds, and
 * the only way to find out without paying for the answer is to run the other
 * rules beside the real one, on the same coins, at the same moments.
 *
 * Two kinds of row, and the second is the interesting one:
 *
 *  * **bought**: the agent really bought it. Here the shadow answers "was your
 *    exit the right exit".
 *  * **blocked**: the agent wanted it and something stopped it — the collar, the
 *    silent threshold, a scan veto, the web check. These run too, and they answer
 *    a question nobody can otherwise answer: *did that wall save me money or cost
 *    me money?* A safety rule that has never been measured is a belief.
 *
 * Nothing here signs, sends, or touches a key. It is a notebook.
 */
object Paper {
    private const val PREFS = "apex_paper"
    private const val KEY = "book"
    private const val STEP_EVERY_MS = 300_000L
    /** Above this, the quotes cost more than the answer is worth. */
    private const val MAX_OPEN = 12

    /**
     * One coin followed by every rule at once. [sizeLamports] is what the trade
     * would have put in, so the costs are the real ones; [blockedBy] is null when
     * the agent actually bought it.
     */
    data class Pos(
        val mint: String,
        val symbol: String,
        val decimals: Int,
        val sizeLamports: Long,
        val impactPct: Double,
        val openedAt: Long,
        val blockedBy: String?,
        val legs: List<PaperLeg>,
    ) {
        val open: Boolean get() = legs.any { it.open }

        /** The price the row started at. Every leg shares it. */
        fun entryOf(): Double = legs.firstOrNull()?.entryLamports ?: 0.0
    }

    /** What one rule has done so far, over every row it has run on. */
    data class Stat(val rule: String, val closed: Int, val wins: Int, val netLamports: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Pos> = runCatching {
        val a = JSONArray(prefs(ctx).getString(KEY, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::fromJson) }
    }.getOrDefault(emptyList())

    fun clear(ctx: Context) = prefs(ctx).edit().remove(KEY).apply()

    fun lastStepAt(ctx: Context): Long = prefs(ctx).getLong("stepAt", 0L)

    /**
     * Start following [symbol] under every rule.
     *
     * [entryLamports] is the price per whole token the agent was quoted, so the
     * shadow starts exactly where the real trade would have: no better price than
     * the one that was actually on offer.
     */
    fun open(
        ctx: Context,
        mint: String,
        symbol: String,
        decimals: Int,
        entryLamports: Double,
        sizeLamports: Long,
        impactPct: Double,
        yourTake: Int,
        yourStop: Int,
        blockedBy: String? = null,
    ) {
        if (entryLamports <= 0 || sizeLamports <= 0) return
        val book = all(ctx).toMutableList()
        // One row per coin at a time: the same coin picked twice in an hour is one
        // opinion about that coin, not two.
        if (book.any { it.mint == mint && it.open }) return
        if (book.count { it.open } >= MAX_OPEN) return
        val now = System.currentTimeMillis()
        book += Pos(
            mint = mint, symbol = symbol, decimals = decimals,
            sizeLamports = sizeLamports, impactPct = impactPct,
            openedAt = now, blockedBy = blockedBy,
            legs = ExitRule.all(yourTake, yourStop).map { PaperLeg(it.id, entryLamports, entryLamports, now) },
        )
        save(ctx, book.takeLast(200))
    }

    /**
     * Move every open row to the price it is at now.
     *
     * [priceOf] returns lamports per whole token, or null when nobody answered —
     * and a null holds everything, exactly as it does in the live loop. Paced by
     * [STEP_EVERY_MS] because each open row costs one quote.
     */
    suspend fun step(ctx: Context, yourTake: Int, yourStop: Int, priceOf: suspend (Pos) -> Double?) {
        val now = System.currentTimeMillis()
        if (now - lastStepAt(ctx) < STEP_EVERY_MS) return
        val book = all(ctx)
        if (book.none { it.open }) { prefs(ctx).edit().putLong("stepAt", now).apply(); return }
        val rules = ExitRule.all(yourTake, yourStop).associateBy { it.id }
        val next = book.map { pos ->
            if (!pos.open) pos else {
                val price = priceOf(pos)
                pos.copy(legs = pos.legs.map { leg -> rules[leg.rule]?.step(leg, price, now) ?: leg })
            }
        }
        save(ctx, next)
        prefs(ctx).edit().putLong("stepAt", now).apply()
    }

    /** Per rule, over the rows the agent actually bought. */
    fun stats(ctx: Context, bought: Boolean = true): List<Stat> {
        val rows = all(ctx).filter { (it.blockedBy == null) == bought }
        val byRule = LinkedHashMap<String, Triple<Int, Int, Long>>()
        for (p in rows) {
            for (leg in p.legs) {
                if (leg.open) continue
                val net = leg.netLamports(p.sizeLamports, impactPct = p.impactPct)
                val (c, w, s) = byRule[leg.rule] ?: Triple(0, 0, 0L)
                byRule[leg.rule] = Triple(c + 1, w + if (net > 0) 1 else 0, s + net)
            }
        }
        return byRule.map { (rule, v) -> Stat(rule, v.first, v.second, v.third) }
    }

    /**
     * What the walls did, in lamports.
     *
     * Positive means the coins the agent was stopped from buying went on to make
     * money, so the wall cost you that. Negative means it saved you that. Measured
     * on the rule you are actually running, because that is the trade you would
     * have made.
     */
    fun blockedVerdict(ctx: Context, yourRule: String = ExitRule.YOURS): Pair<Int, Long> {
        val rows = all(ctx).filter { it.blockedBy != null }
        var closed = 0
        var net = 0L
        for (p in rows) {
            val leg = p.legs.firstOrNull { it.rule == yourRule && !it.open } ?: continue
            closed++
            net += leg.netLamports(p.sizeLamports, impactPct = p.impactPct)
        }
        return closed to net
    }

    private fun save(ctx: Context, list: List<Pos>) {
        val a = JSONArray()
        list.forEach { a.put(toJson(it)) }
        prefs(ctx).edit().putString(KEY, a.toString()).apply()
    }

    private fun toJson(p: Pos) = JSONObject()
        .put("mint", p.mint).put("symbol", p.symbol).put("dec", p.decimals)
        .put("size", p.sizeLamports).put("impact", p.impactPct)
        .put("at", p.openedAt).put("blocked", p.blockedBy ?: JSONObject.NULL)
        .put(
            "legs",
            JSONArray().also { arr ->
                p.legs.forEach { l ->
                    arr.put(
                        JSONObject().put("rule", l.rule).put("entry", l.entryLamports).put("peak", l.peakLamports)
                            .put("open", l.openedAt).put("closed", l.closedAt).put("exit", l.exitLamports).put("why", l.why),
                    )
                }
            },
        )

    private fun fromJson(o: JSONObject): Pos? {
        val mint = o.optString("mint").takeIf { it.isNotEmpty() } ?: return null
        val legs = o.optJSONArray("legs") ?: JSONArray()
        return Pos(
            mint = mint,
            symbol = o.optString("symbol").ifEmpty { mint.take(4) },
            decimals = o.optInt("dec", 6),
            sizeLamports = o.optLong("size", 0L),
            impactPct = o.optDouble("impact", 0.0),
            openedAt = o.optLong("at", 0L),
            blockedBy = o.optString("blocked").takeIf { it.isNotEmpty() && it != "null" },
            legs = (0 until legs.length()).mapNotNull { i ->
                legs.optJSONObject(i)?.let { l ->
                    PaperLeg(
                        rule = l.optString("rule"), entryLamports = l.optDouble("entry", 0.0),
                        peakLamports = l.optDouble("peak", 0.0), openedAt = l.optLong("open", 0L),
                        closedAt = l.optLong("closed", 0L), exitLamports = l.optDouble("exit", 0.0),
                        why = l.optString("why"),
                    )
                }
            },
        )
    }
}
