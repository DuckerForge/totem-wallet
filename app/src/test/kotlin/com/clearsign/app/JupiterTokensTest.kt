package com.clearsign.app

import org.json.JSONArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Jupiter's registry is what lets the swap show a name, a logo and — the part
 * nothing else could give us — the decimals of a coin the wallet does not hold.
 */
class JupiterTokensTest {
    private val sample = """
        [
          {"id":"EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v","name":"USD Coin","symbol":"USDC",
           "icon":"https://example.com/usdc.png","decimals":6,"usdPrice":0.9998,"isVerified":true,"liquidity":414310043.3},
          {"id":"noDecimalsHere","name":"Broken","symbol":"BRK"},
          {"name":"No mint","symbol":"NOP","decimals":9}
        ]
    """.trimIndent()

    @Test
    fun `rows without a mint or decimals are dropped`() {
        val out = JupiterTokens.parse(JSONArray(sample))
        assertEquals(1, out.size)
        val t = out.first()
        assertEquals("USDC", t.symbol)
        assertEquals("USD Coin", t.name)
        assertEquals(6, t.decimals)
        assertEquals("https://example.com/usdc.png", t.icon)
        assertTrue(t.verified)
        assertEquals(0.9998, t.usd)
    }

    @Test
    fun `what it parses is cached and taught to TokenSymbols`() {
        val mint = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
        JupiterTokens.parse(JSONArray("""[{"id":"$mint","name":"Seeker","symbol":"SKR","icon":"https://example.com/skr.png","decimals":6}]"""))
        assertEquals(6, JupiterTokens.cached(mint)?.decimals)
        assertEquals("Seeker", TokenSymbols.name(mint))
        assertEquals("https://example.com/skr.png", TokenSymbols.image(mint))
    }

    @Test
    fun `the audit block becomes the facts the grader needs`() {
        // Shape taken from a live answer for USDC.
        val out = JupiterTokens.parse(
            org.json.JSONArray(
                """[{"id":"EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v","symbol":"USDC","name":"USD Coin","decimals":6,
                     "isVerified":true,"organicScoreLabel":"high","holderCount":5248202,"liquidity":410750782.0,
                     "mintAuthority":"BJE5MMbqXjVwjAF7oxwPYXnTXDyspzZyt4vwenNw5ruG","freezeAuthority":"7dGbd2QZcCKcTndnHcTL8q7SMVXAkp688NTQYwrRCrar",
                     "tokenProgram":"TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA",
                     "audit":{"mintAuthorityDisabled":false,"freezeAuthorityDisabled":false,"topHoldersPercentage":25.03,"devMints":1}}]""",
            ),
        )
        val t = out.single()
        assertTrue(t.canMint && t.canFreeze)
        assertTrue(!t.token2022)
        val f = t.facts(sellable = true)
        assertEquals(25.03, f.topHoldersPct)
        assertEquals(com.clearsign.core.SafetyBand.GOOD, com.clearsign.core.assessToken(f).band)
    }

    @Test
    fun `a revoked authority reads as revoked`() {
        val out = JupiterTokens.parse(
            org.json.JSONArray(
                """[{"id":"DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263","symbol":"Bonk","name":"Bonk","decimals":5,
                     "isVerified":true,"mintAuthority":null,"freezeAuthority":null,
                     "audit":{"mintAuthorityDisabled":true,"freezeAuthorityDisabled":true}}]""",
            ),
        )
        val t = out.single()
        assertTrue(!t.canMint && !t.canFreeze)
    }

    @Test
    fun `an unknown mint is simply not there`() {
        assertNull(JupiterTokens.cached("NeverSeenThisMintBefore11111111111111111111"))
    }

    @Test fun `il disco tiene le monete usate piu' di recente`() {
        fun tok(m: String) = JupiterTokens.Tok(mint = m, symbol = m, name = m, icon = null, decimals = 6)
        val all = listOf(tok("a"), tok("b"), tok("c"), tok("d"))
        val touched = mapOf("a" to 10L, "b" to 40L, "c" to 30L)
        assertEquals(listOf("b", "c"), JupiterTokens.keepNewest(all, touched, 2).map { it.mint })
        assertEquals(4, JupiterTokens.keepNewest(all, touched, 10).size)
        // Mai toccata vale zero: e' la prima a uscire.
        assertEquals("d", JupiterTokens.keepNewest(all, touched, 4).last().mint)
    }
}
