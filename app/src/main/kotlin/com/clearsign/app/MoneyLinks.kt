package com.clearsign.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Two ways to move money with a link, neither needing a program on chain. Asking is free
 * of risk: a Solana Pay URI is a request, the payer signs with their own wallet. Giving is
 * a real trade-off, stated wherever it appears: the link contains a throwaway key, so
 * whoever opens it first can take the money. Right for a tip, wrong for a salary, and
 * reclaimable until someone takes it.
 */
object MoneyLinks {
    private const val PREFS = "apex_gifts"

    /**
     * The web page behind every link we hand out. A `solana:` URI is invisible to a phone with
     * no wallet, so both kinds travel as https: Velum opens them directly (verified App Links),
     * anything else lands on a page that says who asks, how much, and where to get the app.
     */
    const val WEB = "https://duckerforge.github.io/apex"

    // ---- asking ---------------------------------------------------------------

    /** The universal form of a request: readable by any phone, opened by Apex when it is installed. */
    fun request(address: String, amountSol: Double?, label: String?, message: String?): String {
        val b = StringBuilder(WEB).append("/p/?to=").append(address)
        amountSol?.takeIf { it > 0 }?.let { b.append("&amount=").append(trimAmount(it)) }
        label?.takeIf { it.isNotBlank() }?.let { b.append("&label=").append(Uri.encode(it)) }
        message?.takeIf { it.isNotBlank() }?.let { b.append("&message=").append(Uri.encode(it)) }
        return b.toString()
    }

    /**
     * The classic Solana Pay URI, still right between two wallets (Phantom, Solflare and
     * Backpack catch it with no page in between), so Receive keeps it one switch away.
     */
    fun solanaPay(address: String, amountSol: Double?, label: String?, message: String?): String {
        val b = Uri.Builder().scheme("solana").opaquePart(address).build().toString().let { StringBuilder(it) }
        val params = ArrayList<String>()
        amountSol?.takeIf { it > 0 }?.let { params += "amount=" + trimAmount(it) }
        label?.takeIf { it.isNotBlank() }?.let { params += "label=" + Uri.encode(it) }
        message?.takeIf { it.isNotBlank() }?.let { params += "message=" + Uri.encode(it) }
        if (params.isNotEmpty()) b.append('?').append(params.joinToString("&"))
        return b.toString()
    }

    private fun trimAmount(v: Double): String =
        String.format(java.util.Locale.ROOT, "%.9f", v).trimEnd('0').trimEnd('.')

    // ---- giving ---------------------------------------------------------------

    data class Gift(val pubkey: String, val lamports: Long, val note: String, val createdAt: Long, val claimedAt: Long = 0L) {
        val open: Boolean get() = claimedAt == 0L
    }

    /**
     * `https://…/apex/g/#k=<seed base58>&n=<note>`: the key travels in the link by design, and
     * in the fragment on purpose, which is never sent to the server, so the host never sees it.
     * Velum opens the link itself; a phone without it gets the page, which can hand the gift
     * over to any Solana address the person pastes.
     */
    fun claimUrl(seed: ByteArray, note: String): String =
        WEB + "/g/#k=" + Base58.encode(seed) + (if (note.isNotBlank()) "&n=" + Uri.encode(note.take(60)) else "")

    /* Reads the key out of either form: the web link's fragment, or the old `apex://claim?k=`. */
    fun seedFrom(uri: Uri): ByteArray? =
        (uri.getQueryParameter("k") ?: fragmentParam(uri, "k"))
            ?.let { runCatching { Base58.decode(it) }.getOrNull() }?.takeIf { it.size == 32 }

    /* The note that came with the gift, from wherever this link keeps it. */
    fun noteFrom(uri: Uri): String? = uri.getQueryParameter("n") ?: fragmentParam(uri, "n")?.let { Uri.decode(it) }

    private fun fragmentParam(uri: Uri, key: String): String? =
        uri.fragment?.split('&')?.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() }

    /**
     * The same transfer `createGift` will sign, read back before anyone signs it. The
     * destination is a throwaway key that does not exist yet, so this builds one and keeps it:
     * `createGift` reuses it rather than making a second.
     */
    suspend fun previewGift(ctx: Context, owner: String, lamports: Long): ReceiptEngine.Analyzed? {
        val from = Base58.decodePubkey(owner) ?: return null
        val seed = pendingSeed ?: SoftKey.newSeed().also { pendingSeed = it }
        val to = Base58.decodePubkey(SoftKey.pubkeyOf(seed)) ?: return null
        return WalletActions.preview(ctx, owner, listOf(WalletTx.systemTransfer(from, to, lamports)))
    }

    /** Held between the preview and the signature, so both describe one key. */
    private var pendingSeed: ByteArray? = null

    suspend fun createGift(ctx: Context, signer: SeedVaultSigner, owner: String, lamports: Long, note: String): Pair<String?, String?> {
        val seed = pendingSeed ?: SoftKey.newSeed()
        pendingSeed = null
        val pubkey = SoftKey.pubkeyOf(seed)
        val from = Base58.decodePubkey(owner) ?: return null to ctx.getString(R.string.wa_bad_address)
        val to = Base58.decodePubkey(pubkey) ?: return null to ctx.getString(R.string.wa_bad_address)
        val log = WalletActions.LogInfo(
            kind = "gift",
            outflows = listOf("−" + fmtSol(lamports, 5) + " SOL"),
            recipient = pubkey,
            recipientLabel = ctx.getString(R.string.gift_log),
        )
        // Sealed before the signature, not after. The key that will hold the money existed only in
        // this local: a send whose answer got lost, or the process killed in between, left the SOL
        // at an address whose seed had just gone out of scope. Written first; dropped if the send failed.
        val gift = Gift(pubkey, lamports, note, System.currentTimeMillis())
        remember(ctx, gift, seed)
        return when (val r = WalletActions.signAndSend(ctx, signer, owner, listOf(WalletTx.systemTransfer(from, to, lamports)), log)) {
            is WalletActions.Result.Sent -> claimUrl(seed, note) to null
            is WalletActions.Result.Failed -> { runCatching { forget(ctx, pubkey) }; null to r.message }
        }
    }

    /** Take a gift back. Only works while nobody has claimed it. */
    suspend fun reclaim(ctx: Context, gift: Gift, owner: String): String? = withContext(Dispatchers.IO) {
        val seed = seedOf(ctx, gift.pubkey) ?: return@withContext ctx.getString(R.string.gift_key_gone)
        val sw = SoftKey.sweepAll(seed, owner)
        val sig = sw.signature ?: return@withContext if (sw.error == "empty") ctx.getString(R.string.gift_already_taken) else sw.error
        markClaimed(ctx, gift.pubkey)
        // Money that came back is a line in the book, like the money that left.
        LedgerRecorder.record(ctx, LedgerRecorder.plainMove(ctx, "gift", owner, sig, inflows = solLeg(sw.lamports), outflows = emptyList(), label = ctx.getString(R.string.gift_log_back)))
        null
    }

    /** What the person who opens a link sees, before deciding to take it. */
    suspend fun peek(seed: ByteArray): Long = withContext(Dispatchers.IO) {
        runCatching { SolanaRpc.getBalance(SolanaRpc.urlFor(null), SoftKey.pubkeyOf(seed)) }.getOrNull() ?: 0L
    }

    suspend fun claim(ctx: Context, seed: ByteArray, to: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val sw = SoftKey.sweepAll(seed, to)
        if (sw.signature != null) {
            markClaimed(ctx, SoftKey.pubkeyOf(seed))
            // A gift taken used to arrive with no receipt: SOL in the wallet and
            // not a line to say where from.
            LedgerRecorder.record(ctx, LedgerRecorder.plainMove(ctx, "gift", to, sw.signature, inflows = solLeg(sw.lamports), outflows = emptyList(), label = ctx.getString(R.string.gift_log_taken)))
        }
        sw.signature to sw.error
    }

    private fun solLeg(lamports: Long) = listOf(Leg(com.clearsign.core.NATIVE_SOL_MINT, "SOL", 9, lamports))

    // ---- the list of gifts you made ------------------------------------------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Gift> = runCatching {
        val a = JSONArray(prefs(ctx).getString("gifts", "[]") ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Gift(o.getString("pubkey"), o.getLong("lamports"), o.optString("note"), o.getLong("at"), o.optLong("claimed", 0L))
        }.sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    private fun remember(ctx: Context, gift: Gift, seed: ByteArray) {
        val list = all(ctx) + gift
        save(ctx, list)
        prefs(ctx).edit().putString("seed_" + gift.pubkey, Secrets.seal(seed)).apply()
    }

    /** Drop a gift that never left: the send failed, so the key holds nothing. */
    private fun forget(ctx: Context, pubkey: String) {
        save(ctx, all(ctx).filterNot { it.pubkey == pubkey })
        prefs(ctx).edit().remove("seed_$pubkey").apply()
    }

    private fun seedOf(ctx: Context, pubkey: String): ByteArray? =
        prefs(ctx).getString("seed_$pubkey", null)?.let { Secrets.open(it) }

    fun markClaimed(ctx: Context, pubkey: String) {
        save(ctx, all(ctx).map { if (it.pubkey == pubkey) it.copy(claimedAt = System.currentTimeMillis()) else it })
        prefs(ctx).edit().remove("seed_$pubkey").apply()
    }

    private fun save(ctx: Context, list: List<Gift>) {
        val a = JSONArray()
        list.forEach { g ->
            a.put(JSONObject().put("pubkey", g.pubkey).put("lamports", g.lamports).put("note", g.note).put("at", g.createdAt).put("claimed", g.claimedAt))
        }
        prefs(ctx).edit().putString("gifts", a.toString()).apply()
    }
}
