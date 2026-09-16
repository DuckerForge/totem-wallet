package com.clearsign.core

import kotlin.math.max
import kotlin.math.min

/**
 * What the Seeker crowd is buying.
 *
 * The Seeker Genesis Token is a non-transferable Token-2022 NFT minted once per
 * device, so one token is one phone and the holder set can only grow. A census of
 * the group found 120,520 wallets: the median one holds 0.032 SOL, which is why
 * only the 10,527 above one SOL are worth following at all.
 *
 * Two rules keep this honest, and both cost us signal on purpose.
 *
 * **Only what was bought, never what is held.** A holdings ranking of this crowd
 * returns SEKR, CHAPTER2, HM, PDT, GRUMPY: airdrops that shipped with the phone,
 * worth nothing, held by everyone because nobody chose them. Purchases are the
 * only evidence somebody decided.
 *
 * **Three distinct wallets or it does not exist.** On sixty wallets the top
 * "trend" was one person buying one coin ten times. Counting purchases instead of
 * buyers turns a single trader into a crowd, which is the exact lie this is for.
 */
enum class SeekerTier { WHALE, DOLPHIN }

/** A followed wallet. [centiSol] is hundredths of a SOL, as the shipped list stores it. */
data class SeekerWallet(val address: String, val tier: SeekerTier, val centiSol: Long)

/** One purchase seen on chain: [wallet] received [mint] in a swap, paying [solSpent]. */
data class CrowdBuy(
    val wallet: String,
    val tier: SeekerTier,
    val mint: String,
    val symbol: String,
    val at: Long,
    val solSpent: Double,
    /** A sale, not a buy: the coin left and money came in. */
    val sell: Boolean = false,
)

/** One coin in the ranking, with the numbers the card has to be able to show. */
data class CrowdRank(
    val mint: String,
    val symbol: String,
    val wallets: Int,      // distinct buyers — the only number that means "crowd"
    val whales: Int,       // of those, how many are whales
    val buys: Int,         // purchases, always >= wallets
    val solSpent: Double,
    val firstAt: Long,
    val lastAt: Long,
    val score: Double,
)

/** Why a coin from the live feed is worth a look: somebody you follow bought it, or the whales did. */
sealed class CrowdSignal(val mint: String, val at: Long) {
    /** A wallet the person follows bought it. */
    class Followed(mint: String, at: Long, val wallet: String, val solSpent: Double) : CrowdSignal(mint, at)
    /** Several whales bought it inside the window, independently of each other. */
    class Crowd(mint: String, at: Long, val whales: Int) : CrowdSignal(mint, at)
    /** A wallet the person follows sold it: for whoever mirrors that wallet, a reason to leave. */
    class FollowedSell(mint: String, at: Long, val wallet: String) : CrowdSignal(mint, at)
}

object SeekerCrowd {
    /**
     * What the live feed is saying right now, in the order worth acting on.
     *
     * Two signals and no third. A wallet the person chose to follow bought
     * something inside the window: that is the closest this app gets to copy
     * trading, and it is deliberately one step short of it, because the coin
     * still has to pass every gate. And two or more different whales bought the
     * same coin inside the window, which is the crowd noticing something before
     * the registry's numbers do. Money-mints never signal, and one wallet
     * buying ten times is still one wallet.
     */
    fun signals(buys: List<CrowdBuy>, follows: Set<String>, now: Long, windowMs: Long = 60 * 60_000L, minWhales: Int = 2): List<CrowdSignal> {
        val fresh = buys.filter { it.at > now - windowMs && it.mint !in MONEY && it.solSpent >= MIN_SPEND_SOL }
        val recent = fresh.filter { !it.sell }
        val out = ArrayList<CrowdSignal>()
        val seen = HashSet<String>()
        for (b in recent.sortedByDescending { it.at }) {
            if (b.wallet in follows && seen.add(b.mint)) out += CrowdSignal.Followed(b.mint, b.at, b.wallet, b.solSpent)
        }
        val sold = HashSet<String>()
        for (b in fresh.filter { it.sell }.sortedByDescending { it.at }) {
            if (b.wallet in follows && sold.add(b.mint)) out += CrowdSignal.FollowedSell(b.mint, b.at, b.wallet)
        }
        recent.filter { it.tier == SeekerTier.WHALE }.groupBy { it.mint }.forEach { (mint, list) ->
            val whales = list.map { it.wallet }.toSet().size
            if (whales >= minWhales && seen.add(mint)) out += CrowdSignal.Crowd(mint, list.maxOf { it.at }, whales)
        }
        return out
    }

    /** Buying money is not a pick. Nobody "chose" USDC. */
    val MONEY = setOf(
        NATIVE_SOL_MINT,
        AgentPolicy.WSOL,
        AgentPolicy.USDC,
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",   // USDT
        "J1toso1uCk3RLmjorhTtrVwY9HJ7X8V9yYac6Y7kGCPn",   // jitoSOL
        "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So",    // mSOL
        "bSo13r4TkiE4KumL71LsHTPpL2euBYLFx6h9HP3piy1",    // bSOL
    )

    /**
     * Below this, the outflow is explained by the network fee plus the rent for
     * the account the coin arrives in, and an airdrop claim is indistinguishable
     * from a purchase. Measured, not guessed: on one real pass over 10,527 wallets,
     * 13 of 99 apparent buys were under it, and none of them was anybody choosing
     * anything. Raising it to 0.02 instead erased both genuine crowd signals.
     */
    const val MIN_SPEND_SOL = 0.005

    /** A whale's pick counts for three, because it is three times harder to fake. */
    private fun weight(t: SeekerTier) = if (t == SeekerTier.WHALE) 3.0 else 1.0

    /**
     * The ranking, over the last [windowMs] before [now].
     *
     * [minWallets] is the floor below which a coin is simply not shown. Three is
     * not a tuned number, it is the smallest count that cannot be one person.
     */
    fun rank(
        buys: List<CrowdBuy>,
        now: Long,
        windowMs: Long = 24 * 3600_000L,
        minWallets: Int = 3,
    ): List<CrowdRank> {
        val cut = now - windowMs
        return buys.asSequence()
            .filter { it.at >= cut && it.mint !in MONEY && it.solSpent >= MIN_SPEND_SOL }
            .groupBy { it.mint }
            .mapNotNull { (mint, list) ->
                val byWallet = list.groupBy { it.wallet }
                if (byWallet.size < minWallets) return@mapNotNull null
                val whales = byWallet.count { (_, b) -> b.first().tier == SeekerTier.WHALE }
                val last = list.maxOf { it.at }
                // Weight by who bought, then lean towards the fresh: a coin six hours
                // cold is a fact, not a signal.
                val age = ((now - last).toDouble() / windowMs).coerceIn(0.0, 1.0)
                val crowd = byWallet.entries.sumOf { (_, b) -> weight(b.first().tier) }
                CrowdRank(
                    mint = mint,
                    symbol = list.first().symbol,
                    wallets = byWallet.size,
                    whales = whales,
                    buys = list.size,
                    solSpent = list.sumOf { it.solSpent },
                    firstAt = list.minOf { it.at },
                    lastAt = last,
                    score = crowd * (1.0 - 0.6 * age),
                )
            }
            .sortedWith(compareByDescending<CrowdRank> { it.score }.thenByDescending { it.wallets })
            .toList()
    }

    /**
     * How often the scan can run and still fit a monthly credit budget.
     *
     * Finding out *who moved* costs one call per hundred wallets, because a swap
     * always moves the balance if only by the fee. Only the movers then cost a
     * lookup each. That is the whole reason following ten thousand wallets is
     * affordable and following them one by one is not.
     */
    fun passesPerDay(wallets: Int, monthlyCredits: Long, moverRate: Double = 0.03): Int {
        if (wallets <= 0 || monthlyCredits <= 0) return 0
        val detect = (wallets + 99) / 100                       // getMultipleAccounts, 1 credit each
        val movers = max(1.0, wallets * moverRate) * 2          // signatures + the transaction
        val perPass = detect + movers
        return min(96.0, (monthlyCredits / 30.0) / perPass).toInt().coerceAtLeast(0)
    }

    /**
     * A readable handle for a wallet, derived from the address itself.
     *
     * `7rTHtV2tuc…pDRRR bought SNDK` is a string nobody reads twice. A name does
     * the same job better, and because it comes from the address it is stable
     * everywhere and invented nowhere: the same wallet is the same name on every
     * phone, forever, with no directory to keep.
     *
     * Two characters of the real address ride along, so a name is never mistaken
     * for a claim about who somebody is.
     */
    fun nickname(address: String): String {
        if (address.length < 6) return address
        var h = 0
        for (c in address) h = h * 31 + c.code
        val n = NAMES[((h ushr 8) and 0x7fffffff) % NAMES.size]
        return "$n·${address.take(2)}"
    }

    private val NAMES = listOf(
        "Vesper", "Kestrel", "Onyx", "Corvo", "Lynx", "Mistral", "Falco", "Cinder",
        "Nomad", "Talon", "Juniper", "Harbor", "Ember", "Quarry", "Solstice", "Vireo",
        "Basalt", "Dune", "Fennec", "Gale", "Halcyon", "Indigo", "Jetty", "Kite",
        "Lumen", "Marlin", "Nimbus", "Osprey", "Pike", "Quill", "Raven", "Sable",
        "Tundra", "Umber", "Vesta", "Warden", "Xenon", "Yarrow", "Zephyr", "Anvil",
        "Brine", "Cobalt", "Delta", "Echo", "Flint", "Grove", "Hollow", "Ivory",
        "Jackal", "Krait", "Lark", "Mesa", "Nettle", "Orbit", "Puma", "Quartz",
        "Reef", "Summit", "Thorn", "Ursa", "Vantage", "Willow", "Yoke", "Zenith",
    )

    /** The shipped census file: `<address> <b|d> <centiSol>`, `#` for comments. */
    fun parseRoster(lines: Sequence<String>): List<SeekerWallet> =
        lines.mapNotNull { raw ->
            val l = raw.trim()
            if (l.isEmpty() || l.startsWith("#")) return@mapNotNull null
            val p = l.split(' ')
            if (p.size < 3 || p[0].length < 32) return@mapNotNull null
            SeekerWallet(
                address = p[0],
                tier = if (p[1] == "b") SeekerTier.WHALE else SeekerTier.DOLPHIN,
                centiSol = p[2].toLongOrNull() ?: return@mapNotNull null,
            )
        }.toList()
}
