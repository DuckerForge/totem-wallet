package com.clearsign.app

/**
 * Mint → (symbol, decimals) for the tokens a Seeker user actually meets, so the
 * receipt says "USDC" instead of "token(EPjF…Dt1v)". Unknown mints fall back to
 * the shortened mint; a Jupiter token-list lookup can extend this at runtime.
 */
object TokenSymbols {
    private val known: Map<String, String> = mapOf(
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" to "USDC",
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" to "USDT",
        "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3" to "SKR",   // Seeker (verified on-chain 2026-09-11)
        "6BtYmjYbxE2LWay1TxZDkoFFRLWydp6iHY1f44bk5gte" to "SKB",   // Seeker Burner
        "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN" to "JUP",
        "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263" to "BONK",
        "EKpQGSJtjMFqKZ9KQanSqYXRcF8fBopzLHYxdM65zcjm" to "WIF",
        "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So" to "mSOL",
        "J1toso1uCk3RLmjorhTtrVwY9HJ7X8V9yYac6Y7kGCPn" to "jitoSOL",
        "bSo13r4TkiE4KumL71LsHTPpL2euBYLFx6h9HP3piy1" to "bSOL",
        "7dHbWXmci3dT8UFYWYZweBLXgycu7Y3iL6trKn1Y7ARj" to "stSOL",
        "So11111111111111111111111111111111111111112" to "wSOL",
        "HZ1JovNiVvGrGNiiYvEozEVjZ72hhkzZ2GSiNvT6bLQ8" to "PYTH",
        "jtojtomepa8beP8AuQc6eXt5FriJwfFMwQx2v2f9mCL" to "JTO",
        "rndrizKT3MK1iimdxRdWabcF7Zg7AR5T4nud4EkHBof" to "RENDER",
        "HhJpBhRRn4g56VsyLuT8DL5Bv31HkXqsrahTTUCZeZg4" to "MYRO",
        "orcaEKTdK7LKz57vaAYr9QeNsVEPfiu6QeMU1kektZE" to "ORCA",
        "4k3Dyjzvzp8eMZWUXbBCjEvwSkkk59S5iCNLY3QrkX6R" to "RAY",
        "MEW1gQWJ3nEXg2qgERiKu7FAFj79PHvQVREQUzScPP5" to "MEW",
        "2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo" to "PYUSD",
        "27G8MtK7VtTcCHkpASjSDdkWWYfoqT6ggEuKidVJidD4" to "JLP",
        "CLoUDKc4Ane7HeQcPpE3YHnznRxhMimJ4MyaUqyHFzAu" to "CLOUD",
        "hntyVP6YFm1Hg25TN9WGLqM12b8TQmcknKrdu1oxWux" to "HNT",
        "MoNKeYcTnTJWuFTeVK2oHSt5C4UxgZCpFC4LhTkRfJi" to "MONKEY",
    )

    /** Symbols learned at runtime from the token metadata (Helius DAS), process-lifetime. */
    private val learned = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun symbol(mint: String): String = known[mint] ?: learned[mint] ?: "${mint.take(4)}…${mint.takeLast(4)}"
    fun isKnown(mint: String): Boolean = known.containsKey(mint) || learned.containsKey(mint)

    /**
     * Resolve unknown mints through the RPC's DAS `getAssetBatch` (one call, best
     * effort). Call off-main before rendering a receipt or a token list; symbols
     * become available to [symbol] immediately after.
     */
    fun resolve(mints: Collection<String>) {
        val todo = mints.filter { !isKnown(it) }.distinct()
        if (todo.isEmpty()) return
        runCatching { SolanaRpc.dasSymbols(todo) }.getOrNull()?.forEach { (mint, sym) -> learned[mint] = sym }
    }
}
