package com.clearsign.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import com.clearsign.core.AgentMode
import com.clearsign.core.AgentPolicy
import com.clearsign.core.SpendHistory
import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent budget: a throwaway wallet the agent may spend from on its own. What holds,
 * said straight. By construction: the agent holds only this key, so it can never move
 * more than what is in the budget, and the Seed Vault account is untouchable. By you: a
 * sweep ends its power at once. Not on chain: "only swaps", "only these destinations",
 * "seven days" are things this app reports on, not things the network refuses; the cap is
 * the security boundary, the rest is bookkeeping. The key is software because the Seed
 * Vault will not sign without a person, but it never leaves the phone: at rest the seed
 * is encrypted with an AES key in the Android Keystore, so a stolen backup cannot spend it.
 */
object SessionWallet {
    private const val PREFS = "apex_session"
    private const val KEY_ALIAS = "apex_session_aes"
    private const val CURVE = "Ed25519"

    data class Session(
        val pubkey: String,
        val capLamports: Long,
        val fundedLamports: Long,
        val createdAt: Long,
        val expiresAt: Long,
        val note: String,
        /** Sweep anything above the funded amount home once the gain reaches this. 0 = off. */
        val harvestLamports: Long = 0L,
        val harvestedLamports: Long = 0L,
    ) {
        val expired: Boolean get() = System.currentTimeMillis() > expiresAt
        val daysLeft: Int get() = (((expiresAt - System.currentTimeMillis()) / 86_400_000L) + 1).toInt().coerceAtLeast(0)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- at-rest encryption -------------------------------------------------

    private fun aesKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun seal(seed: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aesKey()) }
        val out = c.iv + c.doFinal(seed)
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }

    private fun open(blob: String): ByteArray? = runCatching {
        val raw = android.util.Base64.decode(blob, android.util.Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, raw, 0, 12))
        c.doFinal(raw, 12, raw.size - 12)
    }.getOrNull()

    // ---- the key ------------------------------------------------------------

    /**
     * The last key prepared but not yet saved. Between the preview and the signature the
     * budget exists only here: writing the seed to disk at preview time would overwrite a
     * budget already funded, and that seed is the only copy of the key to that money. But the
     * receipt must be able to say the destination is yours, or it warns about a wallet you
     * just created.
     */
    @Volatile var preparedPubkey: String? = null
        private set

    fun prepare(): Pair<ByteArray, String> {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val spec = EdDSAPrivateKeySpec(seed, EdDSANamedCurveTable.getByName(CURVE))
        val pub = Base58.encode(spec.a.toByteArray())
        preparedPubkey = pub
        return seed to pub
    }

    fun create(ctx: Context, capLamports: Long, days: Int, note: String, prepared: Pair<ByteArray, String>? = null): Session {
        // Never silently replace a live budget: the old seed would go with it.
        current(ctx)?.let { error("A budget already exists; close it before making another.") }
        val (seed, pubkey) = prepared ?: prepare()
        val now = System.currentTimeMillis()
        val session = Session(pubkey, capLamports, 0L, now, now + days.coerceIn(1, 90) * 86_400_000L, note)
        prefs(ctx).edit()
            .putString("seed", seal(seed))
            .putString("pubkey", pubkey)
            .putLong("cap", capLamports)
            .putLong("funded", 0L)
            .putLong("created", now)
            .putLong("expires", session.expiresAt)
            .putString("note", note)
            .putLong("harvest", 0L)
            .putLong("harvested", 0L)
            .apply()
        return session
    }

    fun current(ctx: Context): Session? {
        val p = prefs(ctx)
        val pub = p.getString("pubkey", null) ?: return null
        // A budget topped up before the ceiling learned to follow the money kept limits sized for
        // an amount that no longer exists: the SOL was there and the agent could spend a cent of
        // it. Put right on first read, once.
        val cap0 = p.getLong("cap", 0L)
        val funded0 = p.getLong("funded", 0L)
        if (funded0 > cap0 && cap0 > 0) {
            p.edit().putLong("cap", funded0).apply()
            rescale(ctx, cap0, funded0)
        }
        return Session(
            pubkey = pub,
            capLamports = p.getLong("cap", 0L),
            fundedLamports = p.getLong("funded", 0L),
            createdAt = p.getLong("created", 0L),
            expiresAt = p.getLong("expires", 0L),
            note = p.getString("note", "") ?: "",
            harvestLamports = p.getLong("harvest", 0L),
            harvestedLamports = p.getLong("harvested", 0L),
        )
    }

    /**
     * Money added to an existing budget. The ceiling rises with it: recording only the deposit
     * left every limit sized for the old amount, and six times more money bought a cent of
     * spending. The cap is "the most this budget may ever be worth", and the collar's caps are
     * shares of it, so [rescale] moves them too.
     */
    fun addFunded(ctx: Context, lamports: Long) {
        val p = prefs(ctx)
        val cap = p.getLong("cap", 0L)
        val funded = p.getLong("funded", 0L) + lamports
        p.edit().putLong("funded", funded).putLong("cap", maxOf(cap, funded)).apply()
        if (funded > cap && cap > 0) rescale(ctx, cap, funded)
    }

    /**
     * Carry the collar across a bigger budget at the same shares: growing the pocket must not
     * loosen the rules, nor leave them tied to an amount that is gone.
     */
    internal fun rescale(ctx: Context, oldCap: Long, newCap: Long) {
        val p = policy(ctx) ?: return
        fun scale(v: Long) = (v.toDouble() / oldCap * newCap).toLong()
        setPolicy(
            ctx,
            p.copy(
                perTxLamports = scale(p.perTxLamports),
                dailyLamports = scale(p.dailyLamports),
                askAboveLamports = scale(p.askAboveLamports),
            ),
        )
    }

    /*
     * Closing forgets everything that belonged to the budget: trading settings, the loop's
     * last word, the open positions. Left behind, a new budget opened showing "you stopped
     * it" about money that was gone and coins the new key never held. Done here because
     * there is more than one way to close a budget.
     */
    /**
     * The account of a closed budget: what went in, what came back, what happened between.
     * Kept apart from the budget's prefs, which [forget] wipes, so the last one can be shown.
     */
    data class Close(
        val fundedLamports: Long, val harvestedLamports: Long, val backLamports: Long,
        val createdAt: Long, val closedAt: Long, val buys: Int, val sells: Int,
    ) {
        val resultLamports: Long get() = harvestedLamports + backLamports - fundedLamports
        val resultPct: Double get() = if (fundedLamports > 0) resultLamports * 100.0 / fundedLamports else 0.0
    }

    fun recordClose(ctx: Context, c: Close) {
        val o = org.json.JSONObject().put("funded", c.fundedLamports).put("harvested", c.harvestedLamports).put("back", c.backLamports)
            .put("created", c.createdAt).put("closed", c.closedAt).put("buys", c.buys).put("sells", c.sells)
        ctx.getSharedPreferences("apex_closes", Context.MODE_PRIVATE).edit().putString("last", o.toString()).apply()
    }

    fun lastClose(ctx: Context): Close? = runCatching {
        val o = org.json.JSONObject(ctx.getSharedPreferences("apex_closes", Context.MODE_PRIVATE).getString("last", null) ?: return null)
        Close(o.getLong("funded"), o.getLong("harvested"), o.getLong("back"), o.getLong("created"), o.getLong("closed"), o.getInt("buys"), o.getInt("sells"))
    }.getOrNull()

    fun forget(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        runCatching { TraderLoop.reset(ctx) }
        runCatching { Positions.clear(ctx) }
    }

    /** Arm (or disarm, with 0) the automatic payout of gains. */
    fun setHarvest(ctx: Context, lamports: Long) = prefs(ctx).edit().putLong("harvest", lamports.coerceAtLeast(0L)).apply()

    fun addHarvested(ctx: Context, lamports: Long) {
        val p = prefs(ctx)
        p.edit().putLong("harvested", p.getLong("harvested", 0L) + lamports).apply()
    }

    // ---- the collar ---------------------------------------------------------

    fun policy(ctx: Context): AgentPolicy? {
        val raw = prefs(ctx).getString("policy", null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            fun set(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
            AgentPolicy(
                mode = runCatching { AgentMode.valueOf(o.getString("mode")) }.getOrDefault(AgentMode.OFF),
                perTxLamports = o.getLong("perTx"), dailyLamports = o.getLong("daily"), askAboveLamports = o.getLong("askAbove"),
                allowedPrograms = set("programs"), allowedMints = set("mints"), allowedDestinations = set("destinations"),
                maxTxPerHour = o.getInt("perHour"), expiresAt = o.getLong("expiresAt"),
                // A budget saved before this setting existed had no way to say yes,
                // so it reads as open. Nothing else about it changes: the caps, the
                // silent threshold and the destination list are whatever it stored.
                allowAnyMint = o.optBoolean("anyMint", true),
            )
        }.getOrNull()
    }

    fun setPolicy(ctx: Context, p: AgentPolicy) {
        val o = JSONObject()
            .put("mode", p.mode.name).put("perTx", p.perTxLamports).put("daily", p.dailyLamports).put("askAbove", p.askAboveLamports)
            .put("programs", JSONArray(p.allowedPrograms.toList())).put("mints", JSONArray(p.allowedMints.toList()))
            .put("destinations", JSONArray(p.allowedDestinations.toList()))
            .put("perHour", p.maxTxPerHour).put("expiresAt", p.expiresAt).put("anyMint", p.allowAnyMint)
        prefs(ctx).edit().putString("policy", o.toString()).apply()
    }

    fun setMode(ctx: Context, mode: AgentMode) { policy(ctx)?.let { setPolicy(ctx, it.copy(mode = mode)) } }

    /**
     * Every move leaves a row; its cost may be zero. Two rules read this: the daily money cap
     * sums the lamports, the hourly move cap counts the rows. A round trip spends nothing but
     * is still a move. Skipping the row entirely let an agent buy and sell without limit,
     * paying fee and spread each time, a silent way to empty a budget. And a closed round trip
     * gives the day back what it returned, with a minus sign: marking zero left the cancelled
     * buy on the counter, so buying and selling once froze a small budget until the next day.
     * The cap says what may leave the budget in a day, and from a closed round trip nothing left.
     */
    fun recordSpend(ctx: Context, lamports: Long, at: Long = System.currentTimeMillis()) {
        val a = spendLog(ctx).filter { it.first > at - 86_400_000L } + (at to lamports)
        prefs(ctx).edit().putString("spend", JSONArray(a.map { JSONArray().put(it.first).put(it.second) }).toString()).apply()
    }

    fun history(ctx: Context, now: Long = System.currentTimeMillis()): SpendHistory {
        val log = spendLog(ctx)
        return SpendHistory(
            // Never below zero: selling a coin bought yesterday brings back money that did
            // not leave today, and must not grant margin above the cap.
            spentLast24hLamports = log.filter { it.first > now - 86_400_000L }.sumOf { it.second }.coerceAtLeast(0L),
            txLastHour = log.count { it.first > now - 3_600_000L },
        )
    }

    /**
     * When the day's counter breathes again. "Resumes tomorrow" is false and useless: the
     * twenty-four hours are a sliding window, not midnight. What blocks now is the oldest row
     * still inside, and it frees exactly twenty-four hours after it was written.
     */
    fun freesAt(ctx: Context, now: Long = System.currentTimeMillis()): Long? =
        spendLog(ctx).filter { it.first > now - 86_400_000L && it.second > 0L }
            .minOfOrNull { it.first }?.plus(86_400_000L)

    private fun spendLog(ctx: Context): List<Pair<Long, Long>> = runCatching {
        val a = JSONArray(prefs(ctx).getString("spend", "[]") ?: "[]")
        (0 until a.length()).map { i -> val e = a.getJSONArray(i); e.getLong(0) to e.getLong(1) }
    }.getOrDefault(emptyList())

    /** Sign a transaction message with the envelope key. No biometrics: we hold it. */
    fun sign(ctx: Context, message: ByteArray): ByteArray? {
        val seed = open(prefs(ctx).getString("seed", null) ?: return null) ?: return null
        val spec = EdDSAPrivateKeySpec(seed, EdDSANamedCurveTable.getByName(CURVE))
        val engine = EdDSAEngine(java.security.MessageDigest.getInstance("SHA-512"))
        engine.initSign(EdDSAPrivateKey(spec))
        engine.update(message)
        return engine.sign()
    }
}
