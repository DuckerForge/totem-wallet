package com.clearsign.app

import com.clearsign.core.Candidate
import com.clearsign.core.ScanWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserRulesTest {
    /** The one word first, then the reason; anything else is not a verdict. */
    @Test fun oneWordThenTheReason() {
        assertTrue(UserRules.parse("OK") is UserRules.Verdict.Ok)
        assertTrue(UserRules.parse("ok, nothing forbids it") is UserRules.Verdict.Ok)
        assertEquals("under 500 holders", (UserRules.parse("STOP under 500 holders") as UserRules.Verdict.Stop).reason)
        assertEquals("troppo giovane", (UserRules.parse("Stop: troppo giovane") as UserRules.Verdict.Stop).reason)
        assertEquals("meno di un giorno", (UserRules.parse("STOP — meno di un giorno.") as UserRules.Verdict.Stop).reason)
        assertEquals("WIF", (UserRules.parse("STOP", fallbackReason = "WIF") as UserRules.Verdict.Stop).reason)
        assertTrue(UserRules.parse("I think this coin is fine") is UserRules.Verdict.Unknown)
        assertTrue(UserRules.parse(null) is UserRules.Verdict.Unknown)
        assertTrue(UserRules.parse("") is UserRules.Verdict.Unknown)
    }

    /** What leaves the phone about a coin: numbers a person could check, nothing invented. */
    @Test fun theFactsAreTheCoinsOwnNumbers() {
        val c = Candidate(
            mint = "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263", symbol = "Bonk", name = "Bonk", decimals = 5,
            usd = 0.0000123, liquidity = 1_250_000.0, mcap = 900_000_000.0, holders = 812_000,
            verified = true, canMint = false, canFreeze = false, topHoldersPct = 18.0, ageMinutes = 1200.0 * 1440,
            s1h = ScanWindow(priceChange = -1.2), s24h = ScanWindow(priceChange = 4.5, volume = 22_000_000.0),
        )
        val f = UserRules.facts(c, listOf("organic buyers up"), "degen", 47_900_000L)
        assertTrue(f.contains("coin: Bonk\n"), f)
        assertTrue(f.contains("holders: 812000"), f)
        assertTrue(f.contains("liquidity: $1,250,000"), f)
        assertTrue(f.contains("age since first pool: 1200 days"), f)
        assertTrue(f.contains("price 1h: -1.2%, 24h: +4.5%"), f)
        assertTrue(f.contains("size of this buy: 0.0479 SOL"), f)
        assertTrue(f.contains("lane: degen"), f)
        assertTrue(f.contains("organic buyers up"), f)
    }

    @Test fun anUnknownNumberIsSaidToBeUnknown() {
        val f = UserRules.facts(Candidate(mint = "M", symbol = "X"), emptyList(), "careful", 1_000_000L)
        assertTrue(f.contains("holders: unknown"), f)
        assertTrue(f.contains("age since first pool: unknown"), f)
        assertTrue(f.contains("price 1h: unknown, 24h: unknown"), f)
    }

    /** The chat block and the judge brief both carry the rules and the one promise: they only forbid. */
    @Test fun bothBriefsCarryTheRulesAndThePromise() {
        val rules = "Mai monete sotto 500 detentori."
        val chat = UserRules.chatBlock(rules, italian = true)
        assertTrue(chat.contains(rules) && chat.contains("possono solo vietare"), chat)
        val judge = UserRules.judgeSystem(rules, italian = true)
        assertTrue(judge.contains(rules) && judge.contains("can only forbid") && judge.contains("in Italian"), judge)
    }
}
