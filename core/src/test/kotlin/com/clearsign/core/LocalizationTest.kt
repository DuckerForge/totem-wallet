package com.clearsign.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every language explains every risk, with the same holes for the same arguments. */
class LocalizationTest {
    private fun holes(s: String) = Regex("%[sd]").findAll(s.replace("%%", "")).count()

    @Test fun everyRiskReadsInEveryLanguage() {
        for (flag in RiskFlag.values()) {
            val en = Localization.riskDetail(flag, "en")
            for (loc in listOf("it", "es")) {
                val t = Localization.riskDetail(flag, loc)
                assertTrue(t != flag.name && t != en, "$flag has no $loc text")
                assertEquals(holes(en), holes(t), "$flag: $loc has different arguments than en")
            }
        }
    }

    @Test fun unknownLocaleFallsBackToEnglish() {
        assertEquals(Localization.riskDetail(RiskFlag.WAGER, "en", "0.03"), Localization.riskDetail(RiskFlag.WAGER, "de", "0.03"))
    }

    @Test fun spanishFormatsItsArguments() {
        assertEquals("Saca el 95% de tu saldo en SOL.", Localization.riskDetail(RiskFlag.DRAINS_BALANCE, "es", "95", "SOL"))
    }
}
