package com.clearsign.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The answer `lite-api.jup.ag/ultra/v1/order` gave on 15 September 2026 for 0.01 SOL to USDC, and what we read from it. */
class JupiterUltraTest {
    private val sample = "{\"swapType\": \"aggregator\", \"inAmount\": \"10000000\", \"outAmount\": \"970249\", \"otherAmountThreshold\": \"967822\", \"swapMode\": \"ExactIn\", \"slippageBps\": 25, \"priceImpactPct\": \"-0.0002750840321688544\", \"routePlan\": [{\"percent\": 100, \"bps\": 10000, \"usdValue\": 0.9699189762386644, \"swapInfo\": {\"ammKey\": \"FLckHLGMJy5gEoXWwcE68Nprde1D4araK4TGLw4pQq2n\", \"label\": \"TesseraV\", \"inputMint\": \"So11111111111111111111111111111111111111112\", \"outputMint\": \"EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v\", \"inAmount\": \"10000000\", \"outAmount\": \"970442\"}}], \"feeMint\": \"So11111111111111111111111111111111111111112\", \"feeBps\": 2, \"platformFee\": {\"feeBps\": 2, \"feeMint\": \"So11111111111111111111111111111111111111112\"}, \"taker\": \"DEM0ownerWa11etF0rTests0n1yNotARea1Key111jQ\", \"gasless\": false, \"jitOptimized\": false, \"signatureFeeLamports\": 5000, \"signatureFeePayer\": \"DEM0ownerWa11etF0rTests0n1yNotARea1Key111jQ\", \"prioritizationFeeLamports\": 109529, \"prioritizationFeePayer\": \"DEM0ownerWa11etF0rTests0n1yNotARea1Key111jQ\", \"rentFeeLamports\": 1488440, \"rentFeePayer\": \"DEM0ownerWa11etF0rTests0n1yNotARea1Key111jQ\", \"transaction\": \"AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\", \"lastValidBlockHeight\": \"425385394\", \"inputMint\": \"So11111111111111111111111111111111111111112\", \"outputMint\": \"EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v\", \"router\": \"metis\", \"guaranteedPrice\": false, \"requestId\": \"01a0a6ce-2981-7408-ad28-71f6e3b9f0f4\", \"inUsdValue\": 0.9701858588766774, \"outUsdValue\": 0.9699189762386644, \"swapUsdValue\": 0.9699189762386644, \"priceImpact\": -0.02750840321688544, \"mode\": \"ultra\", \"totalTime\": 257}"

    @Test fun readsTheRealAnswer() {
        val o = JupiterUltra.parse(JSONObject(sample))
        assertNotNull(o)
        o!!
        assertEquals("01a0a6ce-2981-7408-ad28-71f6e3b9f0f4", o.requestId)
        assertEquals(10_000_000L, o.inAmount)
        assertEquals(970_249L, o.outAmount)
        assertEquals(25, o.slippageBps)
        assertEquals(2, o.feeBps)
        assertFalse(o.gasless)
        assertEquals(109_529L, o.prioritizationFeeLamports)
        assertEquals("metis", o.router)
        assertEquals("So11111111111111111111111111111111111111112", o.inMint)
        val q = o.asQuote()
        assertEquals(970_249L, q.outAmount)
        assertEquals(o.priceImpactPct, q.priceImpactPct, 1e-12)
    }

    @Test fun noTransactionMeansNoOrder() {
        assertNull(JupiterUltra.parse(JSONObject().put("requestId", "x").put("outAmount", "1")))
        assertNull(JupiterUltra.parse(JSONObject().put("errorMessage", "insufficient funds")))
    }
}
