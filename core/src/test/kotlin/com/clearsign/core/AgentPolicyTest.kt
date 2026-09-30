package com.clearsign.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentPolicyTest {
    private val owner = "DEM0ownerWa11etF0rTests0n1yNotARea1Key111jQ"
    private val env = "EnvXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX1"
    private val luca = "8ncUqW9x4kXn2v1JZp7m3Qe6TtLbR5sYd2FhGkMuz8z"
    private val stranger = "AttackerXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
    private val bonk = "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263"
    private val usdc = AgentPolicy.USDC
    private val cap = 100_000_000L // 0.1 SOL
    private val now = 1_700_000_000_000L
    private val policy = AgentPolicy.prudent(cap, owner, env, listOf(luca), now + 86_400_000L)
    private val ok = Risk(RiskFlag.AGENT_INTENT_OK, Severity.INFO, "ok")
    private val lie = Risk(RiskFlag.AGENT_INTENT_MISMATCH, Severity.DANGER, "the agent lied")
    private val quiet = SpendHistory(0L, 0)

    /** SOL at face value, USDC at 1 USDC = 0.01 SOL, everything else unknown. */
    private val price: (BalanceDelta) -> Long? = { d ->
        when (d.mint) {
            NATIVE_SOL_MINT, AgentPolicy.WSOL -> Math.abs(d.rawAmount)
            usdc -> Math.abs(d.rawAmount) * 10 // 1e6 raw USDC → 1e7 lamports
            else -> null
        }
    }

    private fun d(owner: String, mint: String, sym: String, dec: Int, ui: Double) =
        BalanceDelta(owner, mint, sym, dec, Math.round(ui * Math.pow(10.0, dec.toDouble())))

    private fun stats(vararg programs: String) = TxStats(
        version = 0, instructionCount = programs.size, accountsTotal = 8, writableAccounts = 4, signerAccounts = 1,
        programs = programs.toList(), computeUnits = 1000, computeUnitLimit = null, computeUnitPriceMicroLamports = null,
        baseFeeLamports = 5_000, priorityFeeLamports = null, logCount = 3, destinationsCount = 1,
    )

    private fun transferTo(to: String, sol: Double) = Receipt(
        primaryRecipient = to, recipientLabel = null, recipientTrust = TrustLevel.NEW,
        outflows = listOf(d(env, NATIVE_SOL_MINT, "SOL", 9, -sol)), inflows = emptyList(), feeLamports = 5_000, risks = emptyList(),
        distributions = listOf(RecipientShare(to, null, TrustLevel.NEW, d(to, NATIVE_SOL_MINT, "SOL", 9, sol), 1.0)),
        stats = stats(AgentPolicy.SYSTEM),
    )

    private fun swap(solOut: Double, inMint: String, inSym: String, inUi: Double, vararg programs: String = arrayOf(AgentPolicy.COMPUTE_BUDGET, AgentPolicy.JUPITER_V6)) = Receipt(
        primaryRecipient = "PoolAuthorityXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", recipientLabel = null, recipientTrust = TrustLevel.NEW,
        outflows = listOf(d(env, NATIVE_SOL_MINT, "SOL", 9, -solOut)), inflows = listOf(d(env, inMint, inSym, 6, inUi)),
        feeLamports = 5_000, risks = emptyList(),
        distributions = listOf(RecipientShare("PoolAuthorityXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", null, TrustLevel.NEW, d("PoolAuthorityXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", NATIVE_SOL_MINT, "SOL", 9, solOut), 1.0)),
        stats = stats(*programs),
    )

    private fun decide(
        r: Receipt, p: AgentPolicy = policy, g: Risk = ok, h: SpendHistory = quiet,
        vault: String? = null, writable: Set<String> = emptySet(), ours: Boolean = false,
    ) = PolicyEngine.decide(p, r, g, h, price, now, "en", vault, writable, ours)

    @Test fun smallTransferToContactIsSilent() {
        assertEquals(Decision.Auto, decide(transferTo(luca, 0.01)))
    }

    @Test fun overPerTxCapAsks() {
        val d = decide(transferTo(luca, 0.05)) // cap/5 = 0.02
        assertTrue(d is Decision.Ask && d.code == "per_tx", d.toString())
    }

    @Test fun dailyCapAccumulates() {
        val d = decide(transferTo(luca, 0.015), h = SpendHistory(spentLast24hLamports = 40_000_000L, txLastHour = 2)) // 0.04 + 0.015 > 0.05
        assertTrue(d is Decision.Ask && d.code == "daily", d.toString())
    }

    @Test fun newAddressIsRefusedNotAsked() {
        val d = decide(transferTo(stranger, 0.001))
        assertTrue(d is Decision.Refuse && d.code == "destination", d.toString())
    }

    // The closed mint list is opt-in; tests that need it pass this.
    private val closed = policy.copy(allowAnyMint = false)

    /**
     * Spending most of a small budget on a swap is a trade, not a drain. Measured on the phone
     * 15 Sep 2026: a 0.08 SOL budget put a 0.033 SOL slice, 93% of its SOL left, into a Jupiter
     * swap, and DRAINS_BALANCE fired as DANGER and stopped the loop.
     */
    @Test fun aSwapThatSpendsMostOfTheBudgetIsNotADrain() {
        val drain = Risk(RiskFlag.DRAINS_BALANCE, Severity.DANGER, "sends out 93% of your SOL balance")
        val buy = swap(0.01, usdc, "USDC", 1.05).copy(risks = listOf(drain))
        assertEquals(Decision.Auto, decide(buy))
    }

    /** The same flag on a plain transfer is still fatal: that one really is a drain. */
    @Test fun aTransferThatEmptiesTheWalletIsStillRefused() {
        val drain = Risk(RiskFlag.DRAINS_BALANCE, Severity.DANGER, "sends out 93% of your SOL balance")
        val out = transferTo(luca, 0.01).copy(risks = listOf(drain))
        val d = decide(out)
        assertTrue(d is Decision.Refuse && d.code == "danger", d.toString())
    }

    /** A swap that returns nothing is caught by the rate rule. */
    @Test fun aSwapThatReturnsNothingIsStillRefused() {
        val drain = Risk(RiskFlag.DRAINS_BALANCE, Severity.DANGER, "sends out 93% of your SOL balance")
        val dust = swap(0.01, usdc, "USDC", 0.0001).copy(risks = listOf(drain))
        val d = decide(dust)
        assertTrue(d is Decision.Refuse && d.code == "rate_quality", d.toString())
    }

    /** The two refusals a loop must tell apart: try again later, and skip this one. */
    @Test fun networkDownAndSimulationFailedHaveDifferentCodes() {
        val base = transferTo(luca, 0.01).copy(outflows = emptyList(), inflows = emptyList())
        val down = Risk(RiskFlag.SIMULATION_UNAVAILABLE, Severity.DANGER, "did not answer")
        val failed = Risk(RiskFlag.SIMULATION_FAILED, Severity.DANGER, "insufficient funds")
        val a = decide(base.copy(risks = listOf(down)), g = down)
        assertTrue(a is Decision.Refuse && a.code == "no_simulation", a.toString())
        val b = decide(base.copy(risks = listOf(failed)), g = failed)
        assertTrue(b is Decision.Refuse && b.code == "sim_failed" && b.reason == "insufficient funds", b.toString())
    }

    @Test fun unlistedOutgoingAssetIsRefused() {
        val r = transferTo(luca, 0.0).copy(outflows = listOf(d(env, bonk, "BONK", 5, -1000.0)))
        val d = decide(r, p = closed)
        assertTrue(d is Decision.Refuse && d.code == "asset_out", d.toString())
    }

    @Test fun buyingAnUnlistedTokenAsks() {
        val d = decide(swap(0.01, bonk, "BONK", 50_000.0), p = closed)
        assertTrue(d is Decision.Ask && d.code == "asset_in", d.toString())
    }

    @Test fun unknownProgramAsks() {
        val d = decide(swap(0.01, usdc, "USDC", 1.0, AgentPolicy.COMPUTE_BUDGET, AgentPolicy.JUPITER_V6, "SomeNewDexXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"))
        assertTrue(d is Decision.Ask && d.code == "program", d.toString())
    }

    @Test fun aLieIsRefusedEvenUnderCap() {
        val d = decide(transferTo(luca, 0.001), g = lie)
        assertTrue(d is Decision.Refuse && d.code == "intent_mismatch", d.toString())
    }

    @Test fun rateLimitRefuses() {
        val d = decide(transferTo(luca, 0.001), h = SpendHistory(0L, 20))
        assertTrue(d is Decision.Refuse && d.code == "rate", d.toString())
    }

    @Test fun pausedAndExpiredRefuse() {
        assertTrue(decide(transferTo(luca, 0.001), p = policy.copy(mode = AgentMode.OFF)) is Decision.Refuse)
        assertTrue(decide(transferTo(luca, 0.001), p = policy.copy(expiresAt = now - 1)) is Decision.Refuse)
        assertTrue(decide(transferTo(luca, 0.001), p = policy.copy(mode = AgentMode.READ_ONLY)) is Decision.Refuse)
    }

    /** Open mode: unlisted coins are tradeable, still bound by the money rules. */
    @Test fun anyMintLetsAnUnlistedCoinThrough() {
        val buy = swap(0.01, bonk, "BONK", 50_000.0)
        // Closed: the mint asks. Open: only the missing price asks.
        assertEquals("asset_in", (decide(buy, p = closed) as Decision.Ask).code)
        val d = decide(buy)
        assertTrue(d is Decision.Ask && d.code == "unknown_value", d.toString())
    }

    @Test fun anyMintSellsBackWithoutAsking() {
        // Sales must not wait for a fingerprint: a stop-loss fires unattended.
        val out = transferTo(luca, 0.0).copy(outflows = listOf(d(env, bonk, "BONK", 5, -1000.0)))
        assertTrue(decide(out) !is Decision.Refuse, decide(out).toString())
    }

    @Test fun anyMintDoesNotLiftTheCaps() {
        val over = decide(transferTo(luca, 0.05), p = policy.copy(allowAnyMint = true))
        assertTrue(over is Decision.Ask && over.code == "per_tx", over.toString())
    }

    @Test fun unknownPriceAsks() {
        val loose = policy.copy(allowedMints = policy.allowedMints + bonk)
        val r = transferTo(luca, 0.0).copy(outflows = listOf(d(env, bonk, "BONK", 5, -1000.0)))
        val d = decide(r, p = loose)
        assertTrue(d is Decision.Ask && d.code == "unknown_value", d.toString())
    }

    @Test fun askAlwaysAsksForOneLamport() {
        val d = decide(transferTo(luca, 0.000000001), p = policy.copy(mode = AgentMode.ASK_ALWAYS))
        assertTrue(d is Decision.Ask, d.toString())
    }

    /**
     * Numbers from the phone: 0.031225 SOL into the swap, about the same back in coins, two new
     * accounts at 0.00204 each plus the fee. Counting rent as part of the exchange made a 1% trade
     * read as 84% and ask for a fingerprint.
     */
    @Test fun rentIsNotPartOfTheExchange() {
        val rentAcct = "NewAcct1111111111111111111111111111111111111"
        val r = Receipt(
            primaryRecipient = "PoolAuthorityXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", recipientLabel = null, recipientTrust = TrustLevel.NEW,
            outflows = listOf(d(env, NATIVE_SOL_MINT, "SOL", 9, -0.035853)),
            inflows = listOf(d(env, usdc, "USDC", 6, 3.11)),   // priced at 0.01 SOL each = 0.0311 SOL
            feeLamports = 550_000, risks = emptyList(),
            distributions = listOf(
                RecipientShare("PoolAuthorityXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", null, TrustLevel.NEW, d("PoolAuthorityXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", NATIVE_SOL_MINT, "SOL", 9, 0.031225), 1.0),
                RecipientShare(rentAcct, null, TrustLevel.NEW, d(rentAcct, NATIVE_SOL_MINT, "SOL", 9, 0.00204), 0.0, isNewAccount = true),
                RecipientShare(rentAcct + "b", null, TrustLevel.NEW, d(rentAcct + "b", NATIVE_SOL_MINT, "SOL", 9, 0.00204), 0.0, isNewAccount = true),
            ),
            stats = stats(AgentPolicy.COMPUTE_BUDGET, AgentPolicy.JUPITER_V6),
        )
        // Caps raised out of the way: this test is about the rate rule, and the
        // lane's own 0.02 SOL per-move cap would answer first.
        val room = policy.copy(perTxLamports = 100_000_000L, dailyLamports = 200_000_000L, askAboveLamports = 100_000_000L)
        assertEquals(Decision.Auto, decide(r, p = room), "0.0312 swapped for 0.0311 of coins is a 1% trade, not a 16% one")
    }

    @Test fun fairSwapIsSilent() {
        // 0.01 SOL out, 0.99 USDC back = 0.0099 SOL-equivalent: within 10%.
        assertEquals(Decision.Auto, decide(swap(0.01, usdc, "USDC", 0.99)))
    }

    @Test fun poorRateAsks() {
        // 0.01 SOL out, 0.7 USDC back = 0.007 SOL-equivalent: 70%, worth a question.
        val d = decide(swap(0.01, usdc, "USDC", 0.70))
        assertTrue(d is Decision.Ask && d.code == "rate", d.toString())
    }

    @Test fun absurdRateIsRefusedAsNotAnExchange() {
        // A tenth back is under the rate_quality floor: treated as a transfer.
        val d = decide(swap(0.01, usdc, "USDC", 0.10))
        assertTrue(d is Decision.Refuse && d.code == "rate_quality", d.toString())
    }

    @Test fun spendingFromTheVaultIsRefused() {
        val vault = owner
        val r = transferTo(luca, 0.001).let { base ->
            base.copy(deltas = listOf(d(vault, NATIVE_SOL_MINT, "SOL", 9, -0.001), d(luca, NATIVE_SOL_MINT, "SOL", 9, 0.001)))
        }
        val out = decide(r, vault = vault, writable = setOf(vault, luca))
        assertTrue(out is Decision.Refuse && out.code == "vault_touched", out.toString())
    }

    @Test fun payingTheVaultIsAllowed() {
        // Sending the winnings home must stay silent: the vault only receives.
        val vault = owner
        val r = transferTo(vault, 0.001).let { base ->
            base.copy(deltas = listOf(d(env, NATIVE_SOL_MINT, "SOL", 9, -0.001), d(vault, NATIVE_SOL_MINT, "SOL", 9, 0.001)))
        }
        assertEquals(Decision.Auto, decide(r, vault = vault, writable = setOf(env, vault)))
    }

    @Test fun vaultWritableWithoutReceivingIsRefused() {
        val vault = owner
        val out = decide(transferTo(luca, 0.001), vault = vault, writable = setOf(vault, luca))
        assertTrue(out is Decision.Refuse && out.code == "vault_touched", out.toString())
    }

    @Test fun theEffectSummaryReadsTheSimulationNotTheClaim() {
        val line = Effects.summary(transferTo(luca, 0.25), "en")
        assertTrue(line.contains("0.25") && line.contains("SOL") && line.contains("8ncU"), line)
    }

    // ---- round trips and the daily cap ----------------------------------------
    // The daily cap limits what leaves. A buy and a sale back leave nothing, so they don't count;
    // otherwise a small budget makes one trip and then asks for the fingerprint every time.

    @Test fun aRealSwapDoesNotSpendTheDay() {
        assertTrue(staysInPocket(swap(0.01, usdc, "USDC", 1.0), policy))
    }

    @Test fun aRoundTripStillCountsAsAMove() {
        // A round trip doesn't spend the daily cap, but the hourly cap counts moves: churning
        // costs fee and spread every time.
        val full = SpendHistory(spentLast24hLamports = 0L, txLastHour = policy.maxTxPerHour)
        val d = decide(swap(0.01, usdc, "USDC", 1.0), h = full)
        assertTrue(d is Decision.Refuse && d.code == "rate", d.toString())
    }

    @Test fun aTransferOutSpendsTheDay() {
        assertFalse(staysInPocket(transferTo(luca, 0.01), policy))
    }

    @Test fun aSwapWithoutAnAllowedExchangeSpendsTheDay() {
        // No allowed exchange program, no exemption: the same check that stops a disguised transfer.
        assertFalse(staysInPocket(swap(0.01, usdc, "USDC", 1.0, AgentPolicy.SYSTEM, AgentPolicy.TOKEN), policy))
    }

    @Test fun sellingBackIntoSolDoesNotSpendTheDay() {
        // A coin out, SOL back: nothing leaves the budget.
        val sale = Receipt(
            primaryRecipient = "PoolAuthorityXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", recipientLabel = null, recipientTrust = TrustLevel.NEW,
            outflows = listOf(d(env, usdc, "USDC", 6, -1.0)),
            inflows = listOf(d(env, NATIVE_SOL_MINT, "SOL", 9, 0.01)),
            feeLamports = 5_000, risks = emptyList(), distributions = emptyList(),
            stats = stats(AgentPolicy.COMPUTE_BUDGET, AgentPolicy.JUPITER_V6),
        )
        assertTrue(staysInPocket(sale, policy))
    }

    @Test fun transferDisguisedAsSwapIsRefused() {
        // No exchange program: the destination list applies, and the pool "authority" is a stranger.
        val d = decide(swap(0.01, usdc, "USDC", 0.0001, AgentPolicy.SYSTEM, AgentPolicy.TOKEN))
        assertTrue(d is Decision.Refuse && d.code == "destination", d.toString())
    }

    // ---- our own route ----------------------------------------------------------
    // Ultra sometimes fills a sale via a market maker: no aggregator, just token transfers to an
    // address no destination list holds. From an agent that is a transfer and is refused; from
    // our own route it is a sale and is signed, as a stop-loss needs.

    /** A sale filled by a market maker: no aggregator among the programs. */
    private val maker = "MakerXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
    private val unknownProgram = "RfqProgramXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
    private fun rfqSale(solBack: Double, usdcOut: Double = 1.0) = Receipt(
        primaryRecipient = maker, recipientLabel = null, recipientTrust = TrustLevel.NEW,
        outflows = listOf(d(env, usdc, "USDC", 6, -usdcOut)),
        inflows = listOf(d(env, NATIVE_SOL_MINT, "SOL", 9, solBack)),
        feeLamports = 5_000, risks = emptyList(),
        distributions = listOf(RecipientShare(maker, null, TrustLevel.NEW, d(maker, usdc, "USDC", 6, usdcOut), 1.0)),
        stats = stats(AgentPolicy.COMPUTE_BUDGET, AgentPolicy.TOKEN, unknownProgram),
    )

    @Test fun aSaleWeRoutedOurselvesIsSigned() {
        assertEquals(Decision.Auto, decide(rfqSale(0.01), ours = true))
    }

    @Test fun theSameBytesFromAnAgentAreStillRefused() {
        val d = decide(rfqSale(0.01))
        assertTrue(d is Decision.Refuse && d.code == "destination", d.toString())
    }

    @Test fun ourRouteDoesNotExcuseATransferWearingASwapsClothes() {
        // Dust back passes the shape check but fails rule ten, which compares what leaves with what returns.
        val d = decide(rfqSale(0.000001), ours = true)
        assertTrue(d is Decision.Refuse && d.code == "rate_quality", d.toString())
    }

    @Test fun ourRouteDoesNotExcuseTheRestOfTheCollar() {
        // The vault and the hard refusals still apply to our own route.
        val d = decide(rfqSale(0.01), g = lie, ours = true)
        assertTrue(d is Decision.Refuse && d.code == "intent_mismatch", d.toString())
    }

    @Test fun ourSaleDoesNotSpendTheDay() {
        assertTrue(staysInPocket(rfqSale(0.01), policy, routeIsOurs = true))
        assertFalse(staysInPocket(rfqSale(0.01), policy))
    }

    @Test fun italianReasonsReadNaturally() {
        val d = PolicyEngine.decide(policy, transferTo(stranger, 0.001), ok, quiet, price, now, "it")
        assertTrue(d is Decision.Refuse && d.code == "destination" && d.reason.contains("destinatari ammessi"), d.toString())
    }

    // ---- coming home ---------------------------------------------------------
    // Caps never block selling a coin back into SOL: a cap bounds what the budget can lose, and a
    // sale loses nothing and pays nobody. With nobody at the phone, an "ask" is a ninety-second
    // wait and an expired blockhash.

    /** A coin worth more than the per-move cap, sold back into SOL. */
    private fun comingHome(solBack: Double) = Receipt(
        primaryRecipient = "PoolAuthorityXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX", recipientLabel = null, recipientTrust = TrustLevel.NEW,
        outflows = listOf(d(env, bonk, "BONK", 5, -50_000.0)),
        inflows = listOf(d(env, NATIVE_SOL_MINT, "SOL", 9, solBack)),
        feeLamports = 5_000, risks = emptyList(),
        stats = stats(AgentPolicy.COMPUTE_BUDGET, AgentPolicy.JUPITER_V6),
    )

    /** The whole BONK holding priced at 0.09 SOL: over every cap here. */
    private val richBonk: (BalanceDelta) -> Long? = { dd ->
        when (dd.mint) {
            NATIVE_SOL_MINT, AgentPolicy.WSOL -> Math.abs(dd.rawAmount)
            bonk -> 90_000_000L
            else -> null
        }
    }

    private fun home(r: Receipt, p: AgentPolicy = policy, h: SpendHistory = quiet) =
        PolicyEngine.decide(p, r, ok, h, richBonk, now, "en")

    @Test fun sellingBackIsSilentEvenOverThePerMoveCap() {
        // 0.09 SOL of coin for 0.085 SOL, against a 0.02 SOL cap per move.
        assertEquals(Decision.Auto, home(comingHome(0.085)))
    }

    @Test fun sellingBackIsSilentWithTheDailyCapAlreadySpent() {
        val spent = SpendHistory(policy.dailyLamports, 0)
        assertEquals(Decision.Auto, home(comingHome(0.085), h = spent))
    }

    /** Exiting a thin coin pays spread and impact. Asking there would just mean holding the bag. */
    @Test fun aPoorRateOnTheWayOutIsNotAQuestion() {
        assertEquals(Decision.Auto, home(comingHome(0.05)))
    }

    /** Under half the value back is refused. */
    @Test fun givingTheCoinAwayIsStillRefused() {
        val d = home(comingHome(0.02))
        assertTrue(d is Decision.Refuse && d.code == "rate_quality", d.toString())
    }

    /** A coin nobody can price can still be sold. What comes back is SOL. */
    @Test fun anUnpriceableCoinCanStillComeHome() {
        assertEquals(Decision.Auto, decide(comingHome(0.05)))
    }

    @Test fun buyingIsStillMeasuredAgainstTheCap() {
        // The reverse, SOL out for a coin, is still capped.
        val buy = swap(0.09, bonk, "BONK", 50_000.0)
        val d = PolicyEngine.decide(policy, buy, ok, quiet, richBonk, now, "en")
        assertTrue(d is Decision.Ask && d.code == "per_tx", d.toString())
    }

    @Test fun aSwapIntoAnotherCoinIsNotComingHome() {
        val jup = "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN"
        val r = comingHome(0.0).copy(inflows = listOf(d(env, jup, "JUP", 6, 1_000.0)))
        val dd = home(r)
        assertTrue(dd is Decision.Ask && dd.code == "unknown_value", dd.toString())
    }

    /** No exchange program in the transaction: a payment, not a sale. */
    @Test fun aTransferOutIsNeverComingHome() {
        val r = comingHome(0.085).copy(stats = stats(AgentPolicy.SYSTEM, AgentPolicy.TOKEN))
        val dd = home(r)
        assertTrue(dd is Decision.Refuse && dd.code == "destination", dd.toString())
    }

    /** Base money leaving is spending, whatever comes back. */
    @Test fun solGoingOutIsNeverComingHome() {
        val r = comingHome(0.085).copy(outflows = listOf(d(env, NATIVE_SOL_MINT, "SOL", 9, -0.09)))
        val dd = home(r)
        assertTrue(dd is Decision.Ask && dd.code == "per_tx", dd.toString())
    }

    /** ASK_ALWAYS applies to sales too. */
    @Test fun askAlwaysStillAsksOnTheWayOut() {
        val dd = home(comingHome(0.085), p = policy.copy(mode = AgentMode.ASK_ALWAYS))
        assertTrue(dd is Decision.Ask && dd.code == "ask_always", dd.toString())
    }

    /** Hard refusals apply both ways. */
    @Test fun aLieOnTheWayOutIsStillRefused() {
        val dd = PolicyEngine.decide(policy, comingHome(0.085), lie, quiet, richBonk, now, "en")
        assertTrue(dd is Decision.Refuse && dd.code == "intent_mismatch", dd.toString())
    }

    @Test fun theRateLimitStillHoldsOnTheWayOut() {
        val dd = home(comingHome(0.085), h = SpendHistory(0L, 20))
        assertTrue(dd is Decision.Refuse && dd.code == "rate", dd.toString())
    }

    /** A refusal carries its key, so it can be rendered in either language. */
    @Test fun aRefusalCanBeSaidAgainInAnotherLanguage() {
        val d = decide(transferTo(stranger, 0.001))
        assertTrue(d is Decision.Refuse && d.text != null, d.toString())
        val r = d as Decision.Refuse
        assertEquals(r.reason, Refusals.say(r.text!!, "en"))
        assertEquals("Atta…XXXX non è fra i destinatari ammessi", Refusals.say(r.text!!, "it"))
    }

    /** Rows from before the key only have the Italian sentence: parse it back (real row from 19 Sep). */
    @Test fun anOldItalianRefusalIsReadBack() {
        val t = Refusals.read("FSbz…JhrT non è fra i destinatari ammessi (rotta: JUP6…TaV4)")
        assertEquals(Refusals.Text("destination_route", listOf("FSbz…JhrT", "JUP6…TaV4")), t)
        assertEquals("FSbz…JhrT is not an allowed destination (route: JUP6…TaV4)", Refusals.say(t!!, "en"))
        assertEquals(Refusals.Text("destination", listOf("FSbz…JhrT")), Refusals.read("FSbz…JhrT non è fra i destinatari ammessi"))
        assertEquals(Refusals.Text("rate_quality", listOf("0.0312", "0.0001")), Refusals.read("non è uno scambio: scambia 0.0312 SOL e riceve l'equivalente di 0.0001 SOL"))
        assertEquals(Refusals.Text("paused"), Refusals.read("l'agente è in pausa"))
        assertEquals(null, Refusals.read("the simulation failed (Custom 6025)"))
    }
}
