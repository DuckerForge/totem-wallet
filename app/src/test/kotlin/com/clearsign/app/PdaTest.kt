package com.clearsign.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PdaTest {
    private val usdc = Base58.decode("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v")

    @Test fun userKeysAreOnCurveAndPdasAreNot() {
        assertTrue(Pda.isOnCurve(ByteArray(32)))                                   // system program id is a curve point
        assertTrue(Pda.isOnCurve(usdc))
        val (pda, bump) = Pda.findProgramAddress(listOf("helloWorld".toByteArray()), ByteArray(32))!!
        assertFalse(Pda.isOnCurve(pda))
        assertTrue(bump in 0..255)
        // deterministic
        assertEquals(Base58.encode(pda), Base58.encode(Pda.findProgramAddress(listOf("helloWorld".toByteArray()), ByteArray(32))!!.first))
    }

    @Test fun associatedTokenAddressMatchesMainnet() {
        // Binance hot wallet: its USDC token accounts on mainnet (fetched 2026-09-11); exactly one is the ATA.
        val owner = Base58.decode("5tzFkiKscXHK5ZXCGbXZxdw7gTjjD1mBwuoFbhUvuAi9")
        val onChain = setOf(
            "2ocS3orPq3jyszjsJ4NozKWyhdotr3csDjAizmkj65aH", "39FeRfGVCH5Bq5fzChHmL3LAsQCXAvFt7idaLipAQsCs", "3MU8CwCqv82fAvufNDY3vwvfCGqSUMbq7Qexr4hofXG1",
            "3bDHrkpPhJygn8WUhzGn6AaZudQKuYs9Wh4tbJX6mMux", "6sJZLf7jNeZbfkSshAt2TwXcPACtMfYWVuAD2UN391fg", "72MCPf2yP91J2XvpUcgTkJv84EKr81N9h9QNG3SFDZES",
            "77v3vB8KJAeQdginW7fquutn2VMthxCmkM6TXmhxfVm7", "7KJjY7rArbydeLBF7gQ5LdqXRKRYyPArT99NEctsHsgU", "7KruwqvAhkV6Dykoi8SbZCh4zyxYfLShdjxtx7GNjVWs",
            "862rbVqQGcDSW37eMqTFWpMP7N23JuZurA1fEBS64BHC", "8NBXkJzTFrhppoDwf2DPsw9wYoXRkFfrHf6c5pUGpk5d", "8NF1fRJ3msxVWxpUyvAZpSCC8hS8FBdFCLe1BiVdVjw6",
            "8VhasAewEGPrxhbX4hM8Z3sfLtNgtFybuh5u3BwCXuA6", "A2jkBxBDCv9y4fsMj678DLoiB8v4GbxgoyRxeVNtjTJb", "AsFhNxXtFpYMuCiQzUu31zwDrVF534tdNB4iSsiA4mPM",
            "D2L6m2LcbAmyC2fMnNJNFkMVDGFnnCEgk44o33urq9xB", "E7uM73FR9uPnpN5S8cxQZr7moLBt9BM2HKzH7if3sk6R", "FBXgAqaCsyrfLzBDkE1RkoEAobmAJ6cFjG3X6RBqZTCj",
            "FzbcyEZ9m8xjtergWgWDq7mfPoHEbboBF791B6cTpzbq", "G4uhj3oohgRcNxRrPWk8tNm5VVDj86FKjM73SRwNjzkr", "Gc2DkHMiLDP1KPgiU6JmrgP3RkJ1Pjv4LERBRenPZQvA",
            "H7zMeBcj5TTm5dEJEunwy9T7kunMqss2Dksx4ruUGJoq", "JBTha6yh3d2Gpky92bCvsW62ucXZ3eeLc87bqJYGHP56",
        )
        val ata = Base58.encode(Pda.associatedTokenAddress(owner, usdc, WalletTx.TOKEN_PROGRAM))
        assertTrue(ata in onChain, "derived ATA $ata not among the wallet's on-chain USDC accounts")
    }

    @Test fun base58DecodeRoundTrip() {
        val s = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        assertEquals(s, Base58.encode(Base58.decode(s)))
        assertEquals(32, Base58.decode("11111111111111111111111111111111").size)
        assertNotNull(Base58.decodePubkey(s)); assertEquals(null, Base58.decodePubkey("not-a-key!"))
    }
}
