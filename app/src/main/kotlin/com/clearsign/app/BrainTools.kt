package com.clearsign.app

import android.content.Context
import com.clearsign.core.AgentMode
import com.clearsign.core.AgentPolicy
import com.clearsign.core.NATIVE_SOL_MINT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the model may reach for: five tools, and not one signs. They build bytes and hand
 * them to [AgentBroker], the only place that decides and the only place holding the budget
 * key. A jailbroken prompt, a poisoned web page or a hallucination can at worst produce a
 * transaction the collar then refuses. The model is a proposer.
 */
object BrainTools {

    val schema: JSONArray = JSONArray()
        .put(tool("wallet_status", "THE BUDGET — the small separate wallet you spend from: what it holds, what it spent today, and exactly what you may and may not do. Call this before proposing anything, and say the limits in the user's own words. This is the only money you can touch.", JSONObject()))
        .put(
            tool(
                "send_sol",
                "Propose sending SOL from the budget. Velum decides: it may sign silently, ask the person for a fingerprint, or refuse. A refusal is final — explain it to the user, never retry the same thing another way.",
                JSONObject()
                    .put("to", prop("string", "Recipient address, base58. It must be one the user allowed."))
                    .put("amount", prop("number", "Amount in SOL."))
                    .put("reason", prop("string", "One sentence saying why. The phone shows it to the person verbatim.")),
                listOf("to", "amount", "reason"),
            ),
        )
        .put(
            tool(
                "swap",
                "Propose a Jupiter swap paid from the budget. Any coin on Solana, named by symbol, by name or by mint address. Same verdicts as send_sol. Check it with market_search first: a coin Jupiter cannot sell back is refused here.",
                JSONObject()
                    .put("from", prop("string", "What you pay with: a symbol like SOL or BONK, a name, or a mint address."))
                    .put("to", prop("string", "What you want: a symbol like SOL or BONK, a name, or a mint address."))
                    .put("amount", prop("number", "How much of `from` leaves the budget."))
                    .put("reason", prop("string", "One sentence saying why.")),
                listOf("from", "to", "amount", "reason"),
            ),
        )
        .put(tool("harvest", "Send the budget's gains home to the Seed Vault account. Money only ever moves towards the owner, so this needs no approval.", JSONObject()))
        // Executing a trade without being able to look at the market is not a
        // trader, it is a button. These three read and never sign.
        .put(
            tool(
                "market_search",
                "Look up a coin by name, symbol or mint address. Returns what the project actually is, its market cap rank, how it moved over 24h, a week, a month and a year, how much liquidity it has, whether Jupiter verified it, and Velum\u2019s own safety grade with the reasons. A missing \"about\" means nobody has listed it, which for a brand new coin is normal and for an old one is worth saying. Use it before proposing any swap into something the user does not already hold, and tell them what you found.",
                JSONObject().put("query", prop("string", "A name, a symbol, or a mint address.")),
                listOf("query"),
            ),
        )
        .put(
            tool(
                "quote_swap",
                "Ask Jupiter what a swap would actually return right now, without proposing anything. Returns the amount out, the rate and the price impact. Use it to check a trade is worth doing, and to tell the user the real numbers before you propose it.",
                JSONObject()
                    .put("from", prop("string", "What you would pay with: a symbol, a name, or a mint address."))
                    .put("to", prop("string", "What you would get: a symbol, a name, or a mint address."))
                    .put("amount", prop("number", "How much of `from`.")),
                listOf("from", "to", "amount"),
            ),
        )
        .put(
            tool(
                "market_scan",
                "Find coins worth looking at right now, out of everything trading on Solana. Returns a short ranked list with the reasons for each, plus what was thrown out and why. This is how you look for something new: never pick a coin from memory, always scan. Then check the winner with market_search and quote_swap before you propose anything.",
                JSONObject()
                    .put("risk", prop("string", "\"bluechip\" for coins with real depth and holders, \"degen\" for fresh micro caps. Default bluechip. Ask the user before going degen."))
                    .put("limit", prop("number", "How many to return, 1 to 8. Default 5.")),
            ),
        )
        .put(
            tool(
                "market_mood",
                "How the market feels right now: the Fear and Greed index, how the whole crypto market moved in 24h, BTC dominance, and where the US index futures are. None of it predicts a price — use it to decide whether this is a moment to look for something new at all, and say plainly when it is not.",
                JSONObject(),
            ),
        )
        .put(tool("portfolio", "THE MAIN ACCOUNT — everything the owner holds in the Seed Vault wallet, with each coin's value and 24h move. Read-only and untouchable: you can never spend a lamport of it. Use it only to give advice that fits what they already own, and always say clearly that you are talking about their main account, not the budget.", JSONObject()))
        .put(
            tool(
                "start_trading",
                "Turn on the loop that works on its own with the app closed: it checks what it holds every 90 seconds, hunts for something new every 6 minutes, sells at the target or the stop, and sends gains home. ASK THE PERSON WHICH RISK LANE FIRST and wait for their answer; never guess it. After turning it on, tell them the real numbers you got back: the slice per position, how many positions, the target and the stop, and that they can close the app.",
                JSONObject()
                    .put("risk", prop("string", "\"bluechip\" for coins with real depth, \"degen\" for fresh micro caps. The person chooses; ask them in those words."))
                    .put("positions", prop("number", "How many coins to hold at once, 1 to 5. Default 3."))
                    .put("takeProfitPct", prop("number", "Sell when a position is up this much percent. Default 30."))
                    .put("stopLossPct", prop("number", "Sell when a position is down this much percent. 0 turns the stop off, which is rarely what anyone wants. Default 15.")),
                listOf("risk"),
            ),
        )
        .put(
            tool(
                "stop_trading",
                "Turn the loop off. Positions already open stay open and stop being watched, so say that plainly and list them.",
                JSONObject(),
            ),
        )
        .put(
            tool(
                "positions",
                "What the agent is holding right now, what each one cost, what it is worth, and how far it is from its target and its stop. Use it whenever the person asks how it is going.",
                JSONObject(),
            ),
        )
        .put(tool("pause", "Stop the agent. After this nothing is signed until the person turns it back on. Use it the moment the user asks you to stop, or if you suspect something is wrong.", JSONObject()))

    /**
     * The candidate list, gated and ranked. The model reads a shortlist well and picks a coin
     * from memory terribly: its training data is a year stale and every ticker it remembers has
     * since been minted by somebody else. The shortlist is built here from live data, by rules
     * tuned against real trades; the model's job starts after.
     */
    private suspend fun marketScan(ctx: Context, risk: String, limit: Int): JSONObject {
        val r = risk.trim().lowercase().replace(" ", "")
        val gate = com.clearsign.core.ScanGate.CAREFUL
        val pool = withContext(Dispatchers.IO) { runCatching { JupiterTokens.pool() }.getOrDefault(emptyList()) }
        if (pool.isEmpty()) return JSONObject().put("error", "Could not read the market right now.")
        val res = com.clearsign.core.scanMarket(pool, gate, limit.coerceIn(1, 8))
        val picks = JSONArray()
        res.picks.forEach { p ->
            picks.put(
                JSONObject()
                    .put("symbol", p.c.symbol).put("name", p.c.name).put("mint", p.c.mint)
                    .put("usd", p.c.usd ?: JSONObject.NULL)
                    .put("change1hPct", p.c.s1h?.priceChange ?: JSONObject.NULL)
                    .put("change24hPct", p.c.s24h?.priceChange ?: JSONObject.NULL)
                    .put("liquidityUsd", p.c.liquidity.toLong())
                    .put("marketCapUsd", (p.c.mcap ?: p.c.fdv)?.toLong() ?: JSONObject.NULL)
                    .put("holders", p.c.holders ?: JSONObject.NULL)
                    .put("verified", p.c.verified)
                    .put("rank", Math.round(p.score * 1000) / 1000.0)
                    .put("notes", JSONArray(p.notes)),
            )
        }
        // What was thrown out is half the answer: it is the difference between
        // "nothing found" and "forty coins looked at, most of them freezable".
        val out = JSONObject().put("risk", gate.name).put("looked_at", res.looked).put("picks", picks)
            .put("rejected", JSONObject().apply { res.rejected.forEach { (why, n) -> put(why, n) } })
        if (res.picks.isEmpty()) {
            out.put("note", "Nothing passed the checks. Say so plainly and do not lower the bar to find something.")
        } else {
            out.put(
                "note",
                "rank orders this scan against itself and means nothing on its own. Do not present it as a probability. " +
                    "Check the one you like with market_search and quote_swap before proposing a swap.",
            )
        }
        return out
    }

    // ---- the loop, switched on from the conversation -------------------------

    /**
     * Turn the loop on and hand back the numbers it will actually use. The slice is not what the
     * model asked for: it derives from the collar, since a move above the silent threshold needs
     * a fingerprint and nobody is there. Returning the derived figure stops the model promising a
     * size the phone will never sign.
     */
    private fun startTrading(ctx: Context, args: JSONObject): JSONObject {
        val s = SessionWallet.current(ctx) ?: return JSONObject().put("error", "There is no budget to trade with. The person has to create one in the Agent tab first.")
        val p = SessionWallet.policy(ctx) ?: return JSONObject().put("error", "There is no budget to trade with.")
        if (s.expired) return JSONObject().put("error", "The budget has expired. Tell the person to open a new one.")
        // Both vocabularies accepted: the model may echo the word the person used.
        val risk = args.optString("risk").trim().lowercase().replace(" ", "")
        val degen = risk == "degen" || risk == "bold"
        val blue = risk == "bluechip" || risk == "careful" || risk == "blue"
        if (!degen && !blue) {
            return JSONObject().put("error", "Ask the person which lane first, in their words: blue chip or degen. Do not choose for them.")
        }
        TraderLoop.cannotStart(ctx)?.let { return JSONObject().put("error", it).put("tell_the_user", "Say this in their own words and do not turn it on.") }
        val cfg = TraderLoop.Config(
            on = true,
            bold = degen,
            maxPositions = args.optInt("positions", 3).coerceIn(1, 5),
            takeProfitPct = args.optInt("takeProfitPct", 30).coerceIn(2, 500),
            stopLossPct = if (args.has("stopLossPct")) args.optInt("stopLossPct").coerceIn(0, 90) else 15,
        )
        TraderLoop.start(ctx, cfg)
        TraderKeeper.sync(ctx)
        val ceiling = minOf(p.perTxLamports, p.askAboveLamports.takeIf { it > 0 } ?: p.perTxLamports)
        val slice = ceiling * cfg.slicePercent / 100
        val out = JSONObject()
            .put("running", true).put("risk", cfg.gate.name)
            .put("slice_sol", slice / 1e9)
            .put("positions_max", cfg.maxPositions)
            .put("take_profit_pct", cfg.takeProfitPct)
            .put("stop_loss_pct", cfg.stopLossPct)
            .put("checks_holdings_every_seconds", TraderLoop.EXIT_EVERY_MS / 1000)
            .put("hunts_every_seconds", TraderLoop.HUNT_EVERY_MS / 1000)
            .put("app_can_be_closed", true)
        // Two honest limits, both of which the person will otherwise discover the
        // expensive way. The model is told to say them, not to bury them.
        val warn = ArrayList<String>()
        if (slice <= 40_000L) warn += "The slice is tiny; network fees will be a large share of every move."
        warn += "The slice is set by the silent threshold, not by you: anything bigger needs their fingerprint, and nobody is there to give it."
        warn += "The stop-loss only works while the loop is running. If Android kills the service the stop is not watched."
        return out.put("tell_the_user", JSONArray(warn))
    }

    private fun stopTrading(ctx: Context): JSONObject {
        TraderLoop.stop(ctx)
        TraderKeeper.sync(ctx)
        val open = Positions.open(ctx)
        return JSONObject()
            .put("running", false)
            .put("still_open", JSONArray(open.map { it.symbol }))
            .put(
                "tell_the_user",
                if (open.isEmpty()) "Stopped, and nothing is open." else "Stopped. These are still held and nothing is watching them now; ask whether to sell them.",
            )
    }

    /** What is held, what it cost, what it is worth. Reads only. */
    private suspend fun positions(ctx: Context): JSONObject {
        val open = Positions.open(ctx)
        if (open.isEmpty()) return JSONObject().put("result", "Nothing is open.")
        val arr = JSONArray()
        open.forEach { pos ->
            val raw = (pos.units * Math.pow(10.0, pos.decimals.toDouble())).toLong()
            val q = if (raw > 0) withContext(Dispatchers.IO) { runCatching { Jupiter.quote(pos.mint, Jupiter.SOL_MINT, raw, feeBps = 0) }.getOrNull() } else null
            val worth = q?.outAmount
            val movePct = if (worth != null && pos.costLamports > 0) (worth - pos.costLamports) * 100.0 / pos.costLamports else null
            arr.put(
                JSONObject()
                    .put("symbol", pos.symbol).put("mint", pos.mint)
                    .put("units", pos.units)
                    .put("cost_sol", pos.costLamports / 1e9)
                    .put("worth_sol", worth?.let { it / 1e9 } ?: JSONObject.NULL)
                    .put("move_pct", movePct?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL)
                    .put("target_pct", pos.takeProfitPct).put("stop_pct", pos.stopLossPct)
                    .put("on_chain_exit", pos.triggerOrder != null),
            )
        }
        return JSONObject().put("pot", "the budget").put("open", arr)
            .put("note", "move_pct is against what it cost including fees. A null worth means Jupiter could not price it just now, which is not the same as zero.")
    }

    /** Sentiment and momentum, never a forecast. */
    private suspend fun marketMood(): JSONObject {
        val m = withContext(Dispatchers.IO) { runCatching { MarketMood.read() }.getOrNull() }
            ?: return JSONObject().put("error", "Could not read the market right now.")
        return JSONObject()
            .put("fearGreed", m.fearGreed ?: JSONObject.NULL)
            .put("fearGreedLabel", m.fearGreedLabel ?: JSONObject.NULL)
            .put("cryptoChange24hPct", m.cryptoChange24h ?: JSONObject.NULL)
            .put("btcDominancePct", m.btcDominance ?: JSONObject.NULL)
            .put("usFuturesPct", m.futuresPct ?: JSONObject.NULL)
            .put("cross", m.cross.name)
            .put("riskOn", m.riskOn)
            .put("note", "Sentiment and momentum only. This does not predict any price.")
    }

    /** What a coin is, what it costs, and whether it is a trap. Reads only. */
    private suspend fun marketSearch(ctx: Context, query: String): JSONObject {
        if (query.isBlank()) return JSONObject().put("error", "Say what to look for.")
        val hits = withContext(Dispatchers.IO) { runCatching { JupiterTokens.search(query) }.getOrDefault(emptyList()) }
        if (hits.isEmpty()) return JSONObject().put("result", "Nothing found for \"" + query + "\".")
        val out = JSONArray()
        hits.take(5).forEach { t ->
            val sellable = withContext(Dispatchers.IO) { runCatching { Jupiter.sellableBack(t.mint, t.decimals, t.usd) }.getOrNull() }
            val safety = com.clearsign.core.assessToken(t.facts(sellable))
            // Jupiter says whether it is tradeable and whether it is a trap. It
            // does not say what it is, and proposing a purchase without that is
            // guessing with a straight face.
            val gecko = withContext(Dispatchers.IO) { runCatching { CoinGecko.byMint(t.mint) }.getOrNull() }
            out.put(
                JSONObject()
                    .put("symbol", t.symbol).put("name", t.name).put("mint", t.mint)
                    .put("about", gecko?.about ?: JSONObject.NULL)
                    .put("marketCapRank", gecko?.rank ?: JSONObject.NULL)
                    .put("marketCapUsd", gecko?.marketCapUsd?.toLong() ?: JSONObject.NULL)
                    .put("categories", JSONArray(gecko?.categories ?: emptyList<String>()))
                    .put("changePct", JSONObject(gecko?.changePct ?: emptyMap<String, Double>()))
                    .put("usd", t.usd ?: JSONObject.NULL)
                    .put("liquidityUsd", t.liquidity.toLong())
                    .put("holders", t.holders)
                    .put("verified", t.verified)
                    .put("safety", safety.score)
                    .put("safetyBand", safety.band.name)
                    .put("warnings", JSONArray(safety.flags.map { it.name })),
            )
        }
        return JSONObject().put("result", out)
    }

    /** What the trade would really return, before anyone proposes it. */
    private suspend fun quoteSwap(ctx: Context, from: String, to: String, amount: Double): JSONObject {
        val src = resolve(from) ?: return JSONObject().put("error", "I do not know the coin \"" + from + "\".")
        val dst = resolve(to) ?: return JSONObject().put("error", "I do not know the coin \"" + to + "\".")
        val inMint = src.first
        val outMint = dst.first
        if (!(amount > 0)) return JSONObject().put("error", "The amount has to be above zero.")
        val raw = (amount * Math.pow(10.0, src.second.toDouble())).toLong()
        val q = withContext(Dispatchers.IO) { runCatching { Jupiter.quote(inMint, outMint, raw, feeBps = 0) }.getOrNull() }
            ?: return JSONObject().put("error", "Jupiter has no route for that right now.")
        val outUi = q.outAmount / Math.pow(10.0, dst.second.toDouble())
        return JSONObject()
            .put("out", outUi)
            .put("rate", if (amount > 0) outUi / amount else 0.0)
            .put("priceImpactPct", q.priceImpactPct * 100)
            .put("route", JSONArray(q.routeLabels.distinct()))
    }

    /** What the owner holds. The agent cannot touch it; it can reason about it. */
    private suspend fun portfolio(ctx: Context): JSONObject {
        val owner = Settings.watchWallet(ctx) ?: return JSONObject().put("error", "No main account connected.")
        val currency = Settings.currency.value
        val view = runCatching { Portfolio.load(ctx, owner, currency) }.getOrNull()
            ?: return JSONObject().put("error", "Could not read the account right now.")
        val coins = JSONArray()
        // Eight, by value. The free model tiers meter tokens per minute, and a
        // full holdings dump was spending most of a minute's budget on dust.
        view.main.filter { it.raw > 0 }.sortedByDescending { it.fiat ?: 0.0 }.take(8).forEach { h ->
            coins.put(
                JSONObject().put("symbol", h.symbol).put("amount", h.ui)
                    .put("value", h.fiat ?: JSONObject.NULL)
                    .put("change24hPct", h.change24h ?: JSONObject.NULL),
            )
        }
        return JSONObject().put("pot", "main account (you cannot spend from this)")
            .put("currency", currency).put("total", view.total).put("coins", coins)
    }

    private fun prop(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

    private fun tool(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()) = JSONObject()
        .put("name", name).put("description", desc)
        .put("input_schema", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)))

    /** Runs one tool. The returned JSON goes straight back to the model. */
    suspend fun run(ctx: Context, name: String, args: JSONObject, agent: String): JSONObject = when (name) {
        "wallet_status" -> status(ctx)
        "market_search" -> marketSearch(ctx, args.optString("query"))
        "quote_swap" -> quoteSwap(ctx, args.optString("from"), args.optString("to"), args.optDouble("amount"))
        "portfolio" -> portfolio(ctx)
        "market_mood" -> marketMood()
        "market_scan" -> marketScan(ctx, args.optString("risk"), args.optInt("limit", 5))
        "start_trading" -> startTrading(ctx, args)
        "stop_trading" -> stopTrading(ctx)
        "positions" -> positions(ctx)
        "send_sol" -> send(ctx, args.optString("to"), args.optDouble("amount"), args.optString("reason"), agent)
        "swap" -> swap(ctx, args.optString("from"), args.optString("to"), args.optDouble("amount"), args.optString("reason"), agent)
        "harvest" -> harvest(ctx)
        "pause" -> pause(ctx)
        else -> JSONObject().put("error", "unknown tool $name")
    }

    // ---- the tools -----------------------------------------------------------

    suspend fun status(ctx: Context): JSONObject {
        val s = SessionWallet.current(ctx) ?: return JSONObject()
            .put("budget", JSONObject.NULL)
            .put("note", "There is no budget yet. Tell the user to create one in the Agent tab; you cannot do it for them.")
        val p = SessionWallet.policy(ctx)
        val h = SessionWallet.history(ctx)
        val balance = withContext(Dispatchers.IO) { runCatching { SolanaRpc.getBalance(SolanaRpc.urlFor(null), s.pubkey) }.getOrNull() }
        val contacts = Contacts.allowlist(ctx)
        return JSONObject()
            .put("pot", "the budget (this is what you may spend)")
            .put("budget", s.pubkey)
            .put("balance_sol", balance?.let { it / 1e9 } ?: JSONObject.NULL)
            .put("cap_sol", s.capLamports / 1e9)
            .put("expires_in_days", s.daysLeft)
            .put("mode", p?.mode?.name ?: "OFF")
            .put("per_move_cap_sol", (p?.perTxLamports ?: 0L) / 1e9)
            .put("daily_cap_sol", (p?.dailyLamports ?: 0L) / 1e9)
            .put("silent_below_sol", (p?.askAboveLamports ?: 0L) / 1e9)
            .put("spent_last_24h_sol", h.spentLast24hLamports / 1e9)
            .put("moves_last_hour", h.txLastHour)
            .put(
                "allowed_assets",
                if (p?.allowAnyMint == true) "any coin on Solana that can be sold back; check it with market_search first"
                else JSONArray(p?.allowedMints?.map { symbol(it) }?.distinct() ?: emptyList<String>()),
            )
            .put(
                "allowed_recipients",
                JSONArray(
                    p?.allowedDestinations?.map { a -> JSONObject().put("address", a).put("name", contacts[a] ?: if (a == s.pubkey) "the budget itself" else "your own account") }
                        ?: emptyList<JSONObject>(),
                ),
            )
            .put("gains_cashed_in_sol", s.harvestedLamports / 1e9)
    }

    private suspend fun send(ctx: Context, to: String, amount: Double, reason: String, agent: String): JSONObject {
        val s = SessionWallet.current(ctx) ?: return JSONObject().put("error", "no budget")
        if (amount <= 0) return JSONObject().put("error", "the amount must be positive")
        val from = Base58.decodePubkey(s.pubkey) ?: return JSONObject().put("error", "bad budget key")
        val dest = Base58.decodePubkey(to) ?: return JSONObject().put("error", "that is not a Solana address")
        val lamports = (amount * 1e9).toLong()
        val rpc = SolanaRpc.urlFor(null)
        val bh = withContext(Dispatchers.IO) { SolanaRpc.latestBlockhash(rpc) } ?: return JSONObject().put("error", "network unavailable")
        val tx = WalletTx.build(from, Base58.decode(bh.hash), listOf(WalletTx.systemTransfer(from, dest, lamports)))
        val intent = JSONObject().put("action", "transfer").put("outMint", "SOL").put("outAmount", amount)
            .put("to", to).put("agent", agent).put("reason", reason)
        return judge(ctx, tx, intent, agent)
    }

    private suspend fun swap(ctx: Context, from: String, to: String, amount: Double, reason: String, agent: String): JSONObject {
        val s = SessionWallet.current(ctx) ?: return JSONObject().put("error", "no budget")
        if (amount <= 0) return JSONObject().put("error", "the amount must be positive")
        val src = resolve(from) ?: return JSONObject().put("error", "I do not know a coin called \"" + from + "\". Try market_search to find its mint address.")
        val dst = resolve(to) ?: return JSONObject().put("error", "I do not know a coin called \"" + to + "\". Try market_search to find its mint address.")
        val inMint = src.first
        val outMint = dst.first
        // The one thing checked before the money leaves, and the only reason a
        // coin is turned down here: a token Jupiter cannot route back to SOL is
        // a purchase with no exit, and no cap protects you from that.
        val grade = safetyOf(outMint)
        if (grade != null && com.clearsign.core.SafetyFlag.NO_WAY_OUT in grade.flags) {
            return JSONObject().put(
                "error",
                "Refused: Jupiter has no route to sell " + symbol(outMint) + " back. Buying it would trap the money. Tell the user exactly that, and do not look for another route.",
            )
        }
        val raw = (amount * Math.pow(10.0, src.second.toDouble())).toLong()
        val built = SessionActions.buildSwap(inMint, outMint, raw, s.pubkey)
            ?: return JSONObject().put("error", "no route for that swap right now")
        val quote = built.quote
        val tx = built.tx
        val intent = JSONObject().put("action", "swap").put("outMint", symbol(inMint)).put("outAmount", amount)
            .put("inMint", symbol(outMint)).put("inAmount", quote.outAmount / Math.pow(10.0, dst.second.toDouble()))
            .put("expectMint", outMint)
            .put("agent", agent).put("reason", reason)
        grade?.let { intent.put("safety", it.score).put("safetyBand", it.band.name) }
        val verdict = judge(ctx, tx, intent, agent, built.ultraRequestId)
        // Whatever the grade found that was not fatal travels back with the verdict,
        // so the model has to say it out loud instead of quietly buying.
        grade?.takeIf { it.flags.isNotEmpty() }?.let {
            verdict.put("coin_safety", it.score).put("coin_warnings", JSONArray(it.flags.map { f -> f.name }))
                .put("tell_the_user", "Repeat these warnings about " + symbol(outMint) + " in plain words.")
        }
        return verdict
    }

    private suspend fun harvest(ctx: Context): JSONObject {
        val owner = Settings.watchWallet(ctx) ?: return JSONObject().put("error", "no main account connected")
        val took = SessionActions.harvest(ctx, owner, force = true)
        return when {
            took == null -> JSONObject().put("error", "the transfer failed; try again shortly")
            took == 0L -> JSONObject().put("harvested_sol", 0).put("note", "there is nothing above what was funded")
            else -> JSONObject().put("harvested_sol", took / 1e9).put("note", "sent to the Seed Vault account")
        }
    }

    private fun pause(ctx: Context): JSONObject {
        SessionWallet.setMode(ctx, AgentMode.OFF)
        return JSONObject().put("mode", "OFF").put("note", "nothing will be signed until the person turns it back on")
    }

    /** The one door. Everything the model proposes goes through the judge. */
    private suspend fun judge(ctx: Context, tx: ByteArray, intent: JSONObject, agent: String, ultraRequestId: String? = null): JSONObject {
        val job = AgentBroker.Job(
            id = LedgerRecorder.newId(), tx = tx, intentJson = intent.toString(),
            cluster = null, agent = agent, source = AgentBroker.Job.Source.IN_APP, ultraRequestId = ultraRequestId,
        )
        val verdict = AgentBroker.handle(ctx, job)
        return verdict.toJson().put(
            "what_it_means",
            when (verdict.code) {
                "signed_silently" -> "Inside the rules: Velum signed and sent it. Tell the user it is done and give the signature."
                "confirmed_by_user" -> "The rules asked for a person; they approved with their fingerprint."
                "refused" -> "Outside the rules: nothing was signed. Explain the reason. Do not try another route to the same thing."
                else -> "Nobody answered in time. The request expired; ask whether to try again."
            },
        )
    }

    /**
     * A shortcut, no longer a gate: the coins whose mint and decimals we can answer without a
     * round trip. Anything else goes through [resolve].
     */
    private val KNOWN = mapOf(
        "SOL" to (AgentPolicy.WSOL to 9),
        "WSOL" to (AgentPolicy.WSOL to 9),
        "USDC" to (AgentPolicy.USDC to 6),
        "USDT" to ("Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" to 6),
        "SKR" to ("SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3" to 6),
        "JUP" to ("JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN" to 6),
        "BONK" to ("DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263" to 5),
    )

    private fun known(name: String): Pair<String, Int>? =
        KNOWN[name.uppercase()] ?: KNOWN.values.firstOrNull { it.first == name }

    /**
     * A symbol, a name or a mint, turned into (mint, decimals). Anything in Jupiter's registry
     * can be named. Where several coins share a ticker a verified one wins: a ticker is not a
     * name, and anybody can mint "BONK" this morning.
     */
    private suspend fun resolve(name: String): Pair<String, Int>? {
        val q = name.trim()
        if (q.isEmpty()) return null
        known(q)?.let { return it }
        if (Base58.decodePubkey(q) != null) {
            val t = JupiterTokens.cached(q)
                ?: withContext(Dispatchers.IO) { runCatching { JupiterTokens.byMints(listOf(q)) }.getOrNull() }?.get(q)
            return q to (t?.decimals ?: 6)
        }
        val hits = withContext(Dispatchers.IO) { runCatching { JupiterTokens.search(q) }.getOrDefault(emptyList()) }
        // Tickers are written both ways: dogwifhat trades as "$WIF" in the
        // registry and everybody calls it WIF.
        fun norm(x: String) = x.trim().removePrefix("$").lowercase()
        val want = norm(q)
        val hit = hits.firstOrNull { norm(it.symbol) == want && it.verified }
            ?: hits.firstOrNull { norm(it.symbol) == want }
            ?: hits.firstOrNull { it.name.equals(q, true) }
            ?: hits.firstOrNull { it.verified && it.name.contains(q, true) }
            ?: return null
        return hit.mint to hit.decimals
    }

    /** The coin's grade, or null when it is SOL or when Jupiter does not know it. */
    private suspend fun safetyOf(mint: String): com.clearsign.core.TokenSafety? {
        if (mint == Jupiter.SOL_MINT || mint == NATIVE_SOL_MINT) return null
        val t = JupiterTokens.cached(mint)
            ?: withContext(Dispatchers.IO) { runCatching { JupiterTokens.byMints(listOf(mint)) }.getOrNull() }?.get(mint)
            ?: return null
        val sellable = withContext(Dispatchers.IO) { runCatching { Jupiter.sellableBack(mint, t.decimals, t.usd) }.getOrNull() }
        return com.clearsign.core.assessToken(t.facts(sellable))
    }

    private fun symbol(mint: String): String = when (mint) {
        NATIVE_SOL_MINT, AgentPolicy.WSOL -> "SOL"
        AgentPolicy.USDC -> "USDC"
        else -> KNOWN.entries.firstOrNull { it.value.first == mint }?.key
            ?: JupiterTokens.cached(mint)?.symbol
            ?: TokenSymbols.symbol(mint)
    }
}
