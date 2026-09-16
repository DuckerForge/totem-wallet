package com.clearsign.app

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Money already earned and never collected.
 *
 * Whoever put two coins into a concentrated pool on Orca or Raydium earns a cut
 * of every swap that crosses their range. That cut does not arrive in the
 * wallet. It sits inside the position account until somebody presses collect,
 * and a position opened once and forgotten keeps a small pile there for years.
 * A wallet balance cannot show it: the money is not in the wallet, and the
 * position itself is an NFT with no number on it.
 *
 * Reading it needs no key and no third-party service. The wallet holds an NFT
 * for each position. The position account's address is a program address
 * derived from that NFT's mint, the same way on both venues, so one token list
 * plus one getMultipleAccounts finds every position the wallet owns. Verified
 * against the chain on the 16th of September 2026: the derivation matched real
 * positions on both programs, and the mints read out of the pool accounts
 * matched what Orca's and Raydium's own public APIs say about the same pools.
 *
 * What the numbers mean, said honestly: the amount in the account is the fee
 * **checkpointed** at the position's last touch. Anything earned since is still
 * spread across the pool's counters and is not in these bytes. So the real
 * collectable amount is this or more, never less, and the screen says "at
 * least". Collecting itself happens on the venue's own page: building those
 * instructions here would mean shipping two more programs' worth of maths for a
 * button nobody could check before signing.
 */
object LpFees {
    const val ORCA_PROGRAM = "whirLbMiicVdio4qvUfM5KAg6Ct8VwpYzGff3uctyCc"
    const val RAY_PROGRAM = "CAMMCzo5YL8w4VFF8KVHrK22GGUsp5VTaW7grrKgrWqK"
    const val METEORA_PROGRAM = "LBUZKhRxPF3XUpBCjp4YzTKgLccjZhTSDM9YuVaPwxo"

    /** Orca `Position`, 216 bytes. Raydium `PersonalPositionState`, 281. Meteora `PositionV2`, 8120. */
    const val ORCA_POSITION_SIZE = 216
    const val RAY_POSITION_SIZE = 281
    const val METEORA_POSITION_SIZE = 8120
    /** `PositionV2.owner`, the field a getProgramAccounts filter matches on. */
    const val METEORA_OWNER_OFFSET = 40

    enum class Venue(val label: String, val site: String) {
        ORCA("Orca", "https://www.orca.so/portfolio"),
        RAYDIUM("Raydium", "https://raydium.io/portfolio"),
        METEORA("Meteora", "https://app.meteora.ag/portfolio"),
    }

    /** One position with something owed in it. Amounts are raw units of the pool's two mints. */
    data class Owed(
        val venue: Venue,
        val position: String,
        val pool: String,
        val liquidity: BigInteger,
        val rawA: Long,
        val rawB: Long,
    ) {
        /** A position with no liquidity left is one somebody closed and never swept. */
        val abandoned: Boolean get() = liquidity.signum() == 0
    }

    /** The two mints a pool trades, with their decimals when the account carries them. */
    data class Pool(val mintA: String, val mintB: String, val decA: Int?, val decB: Int?)

    // ---- decoders, pure ------------------------------------------------------

    /**
     * Orca `Position`: whirlpool at 8, position mint at 40, liquidity at 72,
     * fee owed A at 112, fee owed B at 136.
     */
    fun decodeOrca(address: String, b: ByteArray): Owed? {
        if (b.size < ORCA_POSITION_SIZE) return null
        val a = u64(b, 112)
        val bb = u64(b, 136)
        if (a <= 0L && bb <= 0L) return null
        return Owed(Venue.ORCA, address, base58(b, 8), u128(b, 72), a, bb)
    }

    /**
     * Raydium `PersonalPositionState`: NFT mint at 9, pool at 41, liquidity at
     * 81, fees owed at 129 and 137.
     */
    fun decodeRaydium(address: String, b: ByteArray): Owed? {
        if (b.size < RAY_POSITION_SIZE) return null
        val a = u64(b, 129)
        val bb = u64(b, 137)
        if (a <= 0L && bb <= 0L) return null
        return Owed(Venue.RAYDIUM, address, base58(b, 41), u128(b, 81), a, bb)
    }

    /**
     * Meteora `PositionV2`: pair at 8, owner at 40, then seventy bins of fee
     * accounting from 4552, forty-eight bytes each, with the two pending
     * amounts at plus thirty-two and plus forty. Unlike the other two this
     * account names its owner, so it is found by a filter and not by deriving
     * anything from an NFT.
     */
    fun decodeMeteora(address: String, b: ByteArray): Owed? {
        if (b.size < METEORA_POSITION_SIZE) return null
        var x = 0L
        var y = 0L
        var shares = BigInteger.ZERO
        for (i in 0 until 70) {
            val at = 4552 + i * 48
            x += u64(b, at + 32)
            y += u64(b, at + 40)
            shares = shares.add(u128(b, 72 + i * 16))
        }
        if (x <= 0L && y <= 0L) return null
        return Owed(Venue.METEORA, address, base58(b, 8), shares, x, y)
    }

    /** Meteora `LbPair`: mint X at 88, mint Y at 120. Decimals are not in the account. */
    fun poolMeteora(b: ByteArray): Pool? =
        if (b.size < 152) null else Pool(base58(b, 88), base58(b, 120), null, null)

    /** Orca `Whirlpool`: mint A at 101, mint B at 181. Decimals are not in the account. */
    fun poolOrca(b: ByteArray): Pool? =
        if (b.size < 213) null else Pool(base58(b, 101), base58(b, 181), null, null)

    /** Raydium `PoolState`: mints at 73 and 105, their decimals at 233 and 234. */
    fun poolRaydium(b: ByteArray): Pool? =
        if (b.size < 235) null else Pool(base58(b, 73), base58(b, 105), b[233].toInt() and 0xFF, b[234].toInt() and 0xFF)

    // ---- addresses -----------------------------------------------------------

    /** Both venues derive the position from the NFT mint with the same seed. */
    fun positionOf(venue: Venue, nftMint: String): String? = runCatching {
        val pid = Base58.decode(if (venue == Venue.ORCA) ORCA_PROGRAM else RAY_PROGRAM)
        val mint = Base58.decode(nftMint)
        Pda.findProgramAddress(listOf("position".toByteArray(Charsets.UTF_8), mint), pid)?.first?.let { Base58.encode(it) }
    }.getOrNull()

    /**
     * A position NFT is a token the wallet holds exactly one of, with no
     * decimals. Filtering on that first keeps the derivation off every ordinary
     * coin in the wallet, which is most of them.
     */
    fun candidateMints(accounts: List<SolanaRpc.TokenAccountInfo>): List<String> =
        accounts.filter { it.amount == 1L && it.decimals == 0 }.map { it.mint }.distinct()

    // ---- bytes ---------------------------------------------------------------

    private fun base58(b: ByteArray, at: Int) = Base58.encode(b.copyOfRange(at, at + 32))
    private fun u64(b: ByteArray, at: Int) = ByteBuffer.wrap(b, at, 8).order(ByteOrder.LITTLE_ENDIAN).long
    private fun u128(b: ByteArray, at: Int): BigInteger {
        val mask = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        val lo = BigInteger.valueOf(u64(b, at)).and(mask)
        val hi = BigInteger.valueOf(u64(b, at + 8)).and(mask)
        return hi.shiftLeft(64).or(lo)
    }

    // ---- the whole errand ----------------------------------------------------

    /** One line for the screen: the position, what it is owed, and what that is worth. */
    data class Found(
        val owed: Owed,
        val symbolA: String, val symbolB: String,
        val uiA: Double, val uiB: Double,
        val usd: Double?,
    )

    /**
     * Everything this wallet is owed on both venues. Four calls at most: the
     * token list (already cached), the position accounts, the pool accounts,
     * and one price lookup. Empty when the wallet never provided liquidity,
     * which is the common case, and it costs one getMultipleAccounts to learn.
     */
    fun of(rpcUrl: String, owner: String): List<Found> {
        val accounts = runCatching { SolanaRpc.tokenAccountsOf(rpcUrl, owner) }.getOrNull().orEmpty()
        val mints = candidateMints(accounts)

        val wanted = HashMap<String, Venue>()
        mints.forEach { m ->
            positionOf(Venue.ORCA, m)?.let { wanted[it] = Venue.ORCA }
            positionOf(Venue.RAYDIUM, m)?.let { wanted[it] = Venue.RAYDIUM }
        }
        val raw = if (wanted.isEmpty()) emptyMap() else runCatching { SolanaRpc.accountsBytes(rpcUrl, wanted.keys.toList()) }.getOrNull().orEmpty()

        val owed = raw.mapNotNull { (addr, bytes) ->
            when (wanted[addr]) {
                Venue.ORCA -> decodeOrca(addr, bytes)
                Venue.RAYDIUM -> decodeRaydium(addr, bytes)
                else -> null
            }
        } + runCatching {
            SolanaRpc.programAccountsSized(rpcUrl, METEORA_PROGRAM, METEORA_POSITION_SIZE, METEORA_OWNER_OFFSET, owner)
                .mapNotNull { (addr, bytes) -> decodeMeteora(addr, bytes) }
        }.getOrDefault(emptyList())
        if (owed.isEmpty()) return emptyList()

        val poolBytes = runCatching { SolanaRpc.accountsBytes(rpcUrl, owed.map { it.pool }) }.getOrNull() ?: emptyMap()
        val pools = owed.associate { o ->
            val b = poolBytes[o.pool]
            o.pool to b?.let {
                when (o.venue) {
                    Venue.ORCA -> poolOrca(it)
                    Venue.RAYDIUM -> poolRaydium(it)
                    Venue.METEORA -> poolMeteora(it)
                }
            }
        }

        val coins = pools.values.filterNotNull().flatMap { listOf(it.mintA, it.mintB) }.distinct()
        val meta = runCatching { JupiterTokens.byMints(coins) }.getOrNull().orEmpty()
        val px = runCatching { Prices.quotes(coins) }.getOrNull().orEmpty()

        return owed.mapNotNull { o ->
            val pool = pools[o.pool] ?: return@mapNotNull null
            val decA = pool.decA ?: meta[pool.mintA]?.decimals ?: return@mapNotNull null
            val decB = pool.decB ?: meta[pool.mintB]?.decimals ?: return@mapNotNull null
            val uiA = o.rawA / Math.pow(10.0, decA.toDouble())
            val uiB = o.rawB / Math.pow(10.0, decB.toDouble())
            val usd = px[pool.mintA]?.usd?.let { it * uiA }?.plus(px[pool.mintB]?.usd?.times(uiB) ?: 0.0)
                ?: px[pool.mintB]?.usd?.times(uiB)
            Found(
                o,
                meta[pool.mintA]?.symbol ?: TokenSymbols.symbol(pool.mintA),
                meta[pool.mintB]?.symbol ?: TokenSymbols.symbol(pool.mintB),
                uiA, uiB, usd,
            )
        }.sortedByDescending { it.usd ?: 0.0 }
    }
}
