package com.clearsign.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeeSanityTest {
    private val engine = RiskEngine("en")
    private val me = "MEwa11etxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx1"
    private val trust = AddressTrust()

    private fun ctx(price: Long?, median: Long?, fee: Long?) =
        EffectContext(myWallet = me, priorityPriceMicroLamports = price, medianPriorityPriceMicroLamports = median, priorityFeeLamports = fee)

    @Test fun flagsWhenFarAboveMedianAndPainful() {
        val risks = engine.assessEffects(emptyList(), ctx(price = 5_000_000, median = 10_000, fee = 1_000_000), trust)
        val r = risks.single { it.flag == RiskFlag.FEE_EXCESSIVE }
        assertEquals(Severity.WARN, r.severity)
        assertTrue(r.detail.contains("500×"), r.detail)
        assertTrue(r.detail.contains("0.001 SOL"), r.detail)
    }

    @Test fun silentWhenCheapEvenIfHighMultiple() {
        // 100× the median but only 0.0001 SOL: nobody cares.
        val risks = engine.assessEffects(emptyList(), ctx(price = 1_000_000, median = 10_000, fee = 100_000), trust)
        assertTrue(risks.none { it.flag == RiskFlag.FEE_EXCESSIVE })
    }

    @Test fun silentWhenNearMedianOrUnknown() {
        assertTrue(engine.assessEffects(emptyList(), ctx(price = 50_000, median = 10_000, fee = 2_000_000), trust).none { it.flag == RiskFlag.FEE_EXCESSIVE })
        assertTrue(engine.assessEffects(emptyList(), ctx(price = 5_000_000, median = null, fee = 2_000_000), trust).none { it.flag == RiskFlag.FEE_EXCESSIVE })
        assertTrue(engine.assessEffects(emptyList(), ctx(price = null, median = 10, fee = 2_000_000), trust).none { it.flag == RiskFlag.FEE_EXCESSIVE })
    }

    @Test fun quietNetworkMeasuresAgainstOneMicroLamport() {
        val risks = engine.assessEffects(emptyList(), ctx(price = 1_000, median = 0, fee = 2_000_000), trust)
        assertTrue(risks.any { it.flag == RiskFlag.FEE_EXCESSIVE && it.detail.contains("1000×") })
    }

    @Test fun localizedItalian() {
        val r = RiskEngine("it").assessEffects(emptyList(), ctx(price = 5_000_000, median = 10_000, fee = 1_000_000), trust).single()
        assertTrue(r.detail.startsWith("Paga 500×"), r.detail)
    }
}

class ExtraSignersTest {
    private val me = "MEwa11etxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx1"
    private val other = "C0S1GNERxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
    private val eng = RiskEngine("en")

    @Test fun onlyMeIsFine() {
        assertTrue(eng.assess(emptyList(), AddressTrust(), myWallet = me, otherSigners = listOf(me)).none { it.flag == RiskFlag.EXTRA_SIGNERS })
    }

    @Test fun aSecondSignerWarns() {
        val r = eng.assess(emptyList(), AddressTrust(), myWallet = me, otherSigners = listOf(me, other)).single { it.flag == RiskFlag.EXTRA_SIGNERS }
        assertEquals(Severity.WARN, r.severity)
        assertTrue(r.detail.contains("C0S1"), r.detail)
    }
}
