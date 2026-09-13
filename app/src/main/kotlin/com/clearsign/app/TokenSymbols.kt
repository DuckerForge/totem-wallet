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

    private const val TOKEN_LIST = "https://raw.githubusercontent.com/solana-labs/token-list/main/assets/mainnet"

    /** Logos for the mints DAS can't describe (native SOL) or that deserve a stable image. */
    private val knownImages: Map<String, String> = mapOf(
        com.clearsign.core.NATIVE_SOL_MINT to "$TOKEN_LIST/So11111111111111111111111111111111111111112/logo.png",
        "So11111111111111111111111111111111111111112" to "$TOKEN_LIST/So11111111111111111111111111111111111111112/logo.png",
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" to "$TOKEN_LIST/EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v/logo.png",
    )

    /** Symbols learned at runtime from the token metadata (Helius DAS), process-lifetime. */
    private val learned = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val names = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val images = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val nfts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Mints we already asked DAS about (so unknown ones aren't re-fetched on every refresh). */
    private val asked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun symbol(mint: String): String = known[mint] ?: learned[mint] ?: "${mint.take(4)}…${mint.takeLast(4)}"
    fun isKnown(mint: String): Boolean = known.containsKey(mint) || learned.containsKey(mint)
    fun name(mint: String): String? = names[mint]
    fun image(mint: String): String? = knownImages[mint] ?: images[mint]
    fun isNft(mint: String): Boolean = mint in nfts

    /**
     * Record metadata we learned somewhere better than DAS (today: Jupiter's token
     * registry, see [JupiterTokens]). Marking the mint as asked keeps the DAS pass
     * from spending a round trip on something we already name correctly.
     */
    fun seed(mint: String, symbol: String?, name: String?, image: String?) {
        symbol?.takeIf { it.isNotBlank() && !known.containsKey(mint) }?.let { learned[mint] = it }
        name?.takeIf { it.isNotBlank() }?.let { names[mint] = it }
        image?.takeIf { it.isNotBlank() }?.let { images[mint] = it }
        if (image != null) asked.add(mint)
    }

    /**
     * Resolve mints through the RPC's DAS `getAssetBatch` (one call, best effort):
     * symbol, name, logo and NFT-ness. Call off-main before rendering a receipt or a
     * token list; results become available to the getters immediately after.
     */
    fun resolve(mints: Collection<String>) {
        val todo = mints.filter { it != com.clearsign.core.NATIVE_SOL_MINT && (it !in asked) && (!isKnown(it) || image(it) == null) }.distinct()
        if (todo.isEmpty()) return
        val got = runCatching { SolanaRpc.dasAssets(todo) }.getOrNull() ?: return
        // An empty answer means DAS itself was unavailable (no Helius URL, or a failed
        // call) — not that these mints are nameless. Blacklisting then would keep every
        // token in the wallet shortened to "abcd…wxyz" for the rest of the process.
        if (got.isEmpty()) return
        asked.addAll(todo)
        got.forEach { (mint, a) ->
            a.symbol?.let { if (!known.containsKey(mint)) learned[mint] = it }
            a.name?.let { names[mint] = it }
            a.image?.let { images[mint] = it }
            if (a.isNft) nfts.add(mint)
        }
    }
}
