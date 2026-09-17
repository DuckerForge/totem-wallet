package com.clearsign.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Transactions the wallet builds *itself* (theme purchase, revoke, close,
 * send): build → simulate → Seed Vault biometric → send → local log. The
 * simulation runs *before* the biometric prompt, so a transaction that would
 * fail never costs the user a fingerprint.
 */
object WalletActions {
    private const val TAG = "ClearSign-Actions"

    /** SKR: the Seeker token. */
    const val SKR_MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
    /** Price of one premium theme, whole SKR. */
    const val THEME_PRICE_SKR = 5L
    /** One-time price of ClearSign Pro, whole SKR. */
    const val PRO_PRICE_SKR = 25L

    sealed interface Result {
        data class Sent(val signature: String) : Result
        data class Failed(val message: String) : Result
    }

    /** What the sign log should say about this action. */
    class LogInfo(
        val kind: String,                       // "theme" | "revoke" | "close" | "send"
        val outflows: List<String> = emptyList(),
        val inflows: List<String> = emptyList(),
        val recipient: String? = null,
        val recipientLabel: String? = null,
        val receipt: com.clearsign.core.Receipt? = null,   // when the caller already analysed the tx (Send)
    )

    /**
     * Build the transaction and read it back, **before** anyone signs anything.
     *
     * `signAndSend` already analyses the bytes — but only after signing and
     * sending, to write the ledger row. That is backwards for the two flows that
     * hand money to a key: funding the agent's budget and making a gift link both
     * asked for a fingerprint without ever showing where the money went. The
     * promise this app is built on is that you see the receipt first, so these
     * flows get the same preview Send and Swap have always had.
     *
     * Returns null when the transaction cannot even be built or simulated.
     */
    suspend fun preview(
        ctx: Context,
        owner: String,
        instructions: List<WalletTx.Instruction>,
        cluster: String? = null,
    ): ReceiptEngine.Analyzed? {
        val rpc = SolanaRpc.urlFor(cluster)
        val bh = withContext(Dispatchers.IO) { SolanaRpc.latestBlockhash(rpc) } ?: return null
        val ownerKey = Base58.decodePubkey(owner) ?: return null
        val tx = WalletTx.build(ownerKey, Base58.decode(bh.hash), instructions)
        return withContext(Dispatchers.IO) {
            runCatching { ReceiptEngine.analyze(ctx, BlocklistScanner(ctx), tx, owner, cluster, requireSim = true) }.getOrNull()
        }
    }

    suspend fun signAndSend(
        ctx: Context,
        signer: SeedVaultSigner,
        owner: String,
        instructions: List<WalletTx.Instruction>,
        log: LogInfo,
        cluster: String? = null,
    ): Result {
        val rpc = SolanaRpc.urlFor(cluster)
        try { signer.ensureAccount(owner) } catch (e: Exception) { return Result.Failed(e.message ?: ctx.getString(R.string.sv_not_connected)) }
        val bh = withContext(Dispatchers.IO) { SolanaRpc.latestBlockhash(rpc) }
            ?: return Result.Failed(ctx.getString(R.string.wa_no_blockhash))
        val ownerKey = Base58.decodePubkey(owner) ?: return Result.Failed(ctx.getString(R.string.wa_bad_address))
        // The person's chosen priority, in front of everything we build ourselves.
        val speed = Settings.speed(ctx)
        val withPriority = if (speed > 0) listOf(WalletTx.setComputeUnitPrice(speed)) + instructions else instructions
        val tx = WalletTx.build(ownerKey, Base58.decode(bh.hash), withPriority)

        val sim = withContext(Dispatchers.IO) { SolanaRpc.simulate(rpc, tx) }
        if (sim != null && !sim.ok) {
            Log.w(TAG, "simulation failed: ${sim.err} logs=${sim.logs.takeLast(3)}")
            val need = instructions.sumOf { i -> i.lamportsMoved }.takeIf { it > 0L }
            val have = withContext(Dispatchers.IO) { runCatching { SolanaRpc.getBalance(rpc, owner) }.getOrNull() }
            return Result.Failed(ctx.getString(R.string.wa_sim_failed, humanError(ctx, sim, need, have)))
        }
        val signature = try { signer.signSuspend(tx) } catch (e: Exception) {
            return Result.Failed(e.message ?: ctx.getString(R.string.sign_error))
        }
        val signed = SolanaTx.attachSignature(tx, 0, signature)
        val out = withContext(Dispatchers.IO) { SolanaRpc.send(rpc, signed) }
        val sig = out.signature ?: return Result.Failed(ctx.getString(R.string.wa_send_failed, out.error ?: "?"))
        log.recipient?.let { Contacts.addHistory(ctx, it) }
        // Ledger: use the caller's receipt, else analyse our own transaction (no simulation required).
        val receipt = log.receipt ?: withContext(Dispatchers.IO) {
            runCatching { ReceiptEngine.analyze(ctx, BlocklistScanner(ctx), tx, owner, cluster, requireSim = false).receipt }.getOrNull()
        }
        val at = System.currentTimeMillis()
        val statement = Attestation.statement(
            at, ctx.getString(R.string.app_name), null, cluster, listOf(Attestation.sha256Hex(tx)), log.outflows, log.inflows,
            receipt?.risks?.map { it.flag.name }?.distinct() ?: emptyList(), owner, sig,
        )
        val attSig = Attestation.sign(statement)
        LedgerRecorder.record(
            ctx,
            LedgerRecorder.fromReceipt(
                at = at, kind = log.kind, dApp = ctx.getString(R.string.app_name), host = null, pkg = ctx.packageName, cluster = cluster, wallet = owner,
                r = receipt, signature = sig, sent = true, txIndex = 0, txCount = 1, groupId = LedgerRecorder.newId(),
                attestation = if (attSig != null) statement else null, attestationSig = attSig, recipientLabelFallback = log.recipientLabel,
            ),
        )
        Haptics.success(ctx)
        return Result.Sent(sig)
    }

    /**
     * Sign and send a transaction that was built elsewhere (a Jupiter swap): we
     * do not rebuild it — the bytes are signed as-is after the user approved the
     * receipt. The Seed Vault signs the message; the signature is spliced back.
     */
    suspend fun signAndSendRaw(
        ctx: Context, signer: SeedVaultSigner, owner: String, txBytes: ByteArray,
        receipt: com.clearsign.core.Receipt?, kind: String, cluster: String? = null,
        /** Set when the bytes came from Jupiter Ultra: Jupiter lands them, not our RPC. */
        ultraRequestId: String? = null,
    ): Result {
        val rpc = SolanaRpc.urlFor(cluster)
        try { signer.ensureAccount(owner) } catch (e: Exception) { return Result.Failed(e.message ?: ctx.getString(R.string.sv_not_connected)) }
        val signature = try { signer.signSuspend(txBytes) } catch (e: Exception) { return Result.Failed(e.message ?: ctx.getString(R.string.sign_error)) }
        val signed = SolanaTx.attachSignature(txBytes, 0, signature)
        val sig = if (ultraRequestId != null) {
            val ex = withContext(Dispatchers.IO) { JupiterUltra.execute(signed, ultraRequestId) }
            ex.signature?.takeIf { ex.error == null } ?: return Result.Failed(ctx.getString(R.string.wa_send_failed, ex.error ?: ex.status))
        } else {
            val out = withContext(Dispatchers.IO) { SolanaRpc.send(rpc, signed) }
            out.signature ?: return Result.Failed(ctx.getString(R.string.wa_send_failed, out.error ?: "?"))
        }
        val at = System.currentTimeMillis()
        val statement = Attestation.statement(
            at, ctx.getString(R.string.app_name), null, cluster, listOf(Attestation.sha256Hex(txBytes)),
            receipt?.outflows?.map { "−" + it.symbol } ?: emptyList(), receipt?.inflows?.map { "+" + it.symbol } ?: emptyList(),
            receipt?.risks?.map { it.flag.name }?.distinct() ?: emptyList(), owner, sig,
        )
        val attSig = Attestation.sign(statement)
        LedgerRecorder.record(
            ctx,
            LedgerRecorder.fromReceipt(
                at = at, kind = kind, dApp = if (ultraRequestId != null) "Jupiter Ultra" else "Jupiter", host = "jup.ag", pkg = ctx.packageName, cluster = cluster, wallet = owner,
                r = receipt, signature = sig, sent = true, txIndex = 0, txCount = 1, groupId = LedgerRecorder.newId(),
                attestation = if (attSig != null) statement else null, attestationSig = attSig,
            ),
        )
        Haptics.success(ctx)
        return Result.Sent(sig)
    }

    private val FEE_MINTS = listOf(
        "So11111111111111111111111111111111111111112",
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",
        SKR_MINT,
        "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN",
        "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263",
    )

    /**
     * The mints worth opening a fee account for: the usual suspects, plus the
     * coins this person actually trades.
     *
     * A hand-written list of six was the whole reason the fee earned nothing:
     * every other coin had no account, and a swap into a coin with no account
     * does not merely skip the fee, it **fails** ([Jupiter.feeAccountIfUsable]).
     * Blocking, so call it on IO.
     */
    fun feeMintsToOpen(ctx: Context, owner: String): List<String> {
        val held = runCatching { SolanaRpc.tokenAccountsOf(SolanaRpc.urlFor(null), owner) }
            .getOrDefault(emptyList()).filter { it.amount > 0 }.map { it.mint }
        val watched = runCatching { Watchlist.all(ctx) }.getOrDefault(emptyList())
        // Bounded before the questions, not after: each candidate costs one call
        // to the chain, and a long watchlist would turn opening this card into a
        // minute of waiting.
        return (FEE_MINTS + watched + held).distinct().take(20)
            // Already open is nothing to do, and one transaction can only carry
            // so many accounts before it stops fitting.
            .filter { Jupiter.feeAccountIfUsable(it) == null && Jupiter.feeAccountFor(it) != null }
            .take(8)
    }

    suspend fun activateSwapFees(ctx: Context, signer: SeedVaultSigner, owner: String): Result {
        val treasury = Base58.decodePubkey(BuildConfig.SKR_TREASURY) ?: return Result.Failed(ctx.getString(R.string.theme_unlock_no_treasury))
        val ownerKey = Base58.decodePubkey(owner) ?: return Result.Failed(ctx.getString(R.string.wa_bad_address))
        val program = Base58.decode(SolanaTx.TOKEN_PROGRAM)
        val mints = withContext(Dispatchers.IO) { feeMintsToOpen(ctx, owner) }
        if (mints.isEmpty()) return Result.Failed(ctx.getString(R.string.swapfees_nothing))
        val ixs = buildList {
            add(WalletTx.setComputeUnitLimit(30_000 + 25_000 * mints.size))
            for (m in mints) {
                val mint = Base58.decode(m)
                add(WalletTx.createAtaIdempotent(ownerKey, Pda.associatedTokenAddress(treasury, mint, program), treasury, mint, program))
            }
        }
        val r = signAndSend(ctx, signer, owner, ixs, LogInfo(kind = "setup", recipientLabel = ctx.getString(R.string.swapfees_log)))
        if (r is Result.Sent) Jupiter.forgetFeeAccounts()
        return r
    }

    /**
     * The two failures a person actually meets, said in their own words.
     *
     * The System Program reports "not enough lamports" as `custom program error:
     * 0x1`, which tells a human nothing. Everything else falls back to the raw
     * text, because a wrong guess is worse than an honest dump.
     */
    private fun humanError(ctx: Context, sim: SolanaRpc.SimResult, needLamports: Long? = null, haveLamports: Long? = null): String {
        val raw = (sim.logs + listOfNotNull(sim.err)).joinToString(" ")
        val systemProgram = raw.contains(SYSTEM_PROGRAM)
        if (raw.contains("insufficient lamports", true) || (systemProgram && raw.contains("custom program error: 0x1"))) {
            return if (needLamports != null && haveLamports != null) {
                ctx.getString(R.string.wa_not_enough_sol, fmtSol(needLamports, 4), fmtSol(haveLamports, 4))
            } else {
                ctx.getString(R.string.wa_not_enough_sol_short)
            }
        }
        if (systemProgram && raw.contains("custom program error: 0x0")) return ctx.getString(R.string.wa_account_exists)
        val log = sim.logs.lastOrNull { it.contains("Error", true) || it.contains("insufficient", true) || it.contains("failed", true) }
        return log?.substringAfter("Program log: ")?.take(120) ?: sim.err?.take(120) ?: "?"
    }

    private const val SYSTEM_PROGRAM = "11111111111111111111111111111111"

    // ---- premium themes in SKR -------------------------------------------------

    /** The owner's SKR position, read fresh. */
    data class SkrQuote(val account: SolanaRpc.TokenAccountInfo?, val decimals: Int) {
        val balance: Long get() = account?.amount ?: 0L
        val priceRaw: Long get() = THEME_PRICE_SKR * pow10(decimals)
        val proPriceRaw: Long get() = PRO_PRICE_SKR * pow10(decimals)
        val enough: Boolean get() = account != null && balance >= priceRaw
        val enoughForPro: Boolean get() = account != null && balance >= proPriceRaw
        fun uiBalance(): String = fmtSol(balance, minOf(decimals, 4)).let { if (decimals == 0) balance.toString() else it }
    }

    suspend fun skrQuote(owner: String, cluster: String? = null): SkrQuote = withContext(Dispatchers.IO) {
        val rpc = SolanaRpc.urlFor(cluster)
        val accts = SolanaRpc.tokenAccountsOf(rpc, owner, force = true).filter { it.mint == SKR_MINT }
        val best = accts.maxByOrNull { it.amount }
        SkrQuote(best, best?.decimals ?: 6)
    }

    val treasuryConfigured: Boolean get() = Base58.decodePubkey(BuildConfig.SKR_TREASURY) != null

    /**
     * Pay [THEME_PRICE_SKR] SKR to the treasury and unlock [palette]. The
     * treasury's associated token account is created idempotently by the buyer,
     * so the treasury never has to be prepared in advance.
     */
    suspend fun payTheme(ctx: Context, signer: SeedVaultSigner, owner: String, palette: HaloPalette, quote: SkrQuote): Result {
        val src = quote.account ?: return Result.Failed(ctx.getString(R.string.theme_unlock_no_skr))
        if (!quote.enough) return Result.Failed(ctx.getString(R.string.theme_unlock_insufficient, quote.uiBalance(), THEME_PRICE_SKR))
        val treasury = Base58.decodePubkey(BuildConfig.SKR_TREASURY) ?: return Result.Failed(ctx.getString(R.string.theme_unlock_no_treasury))
        val ownerKey = Base58.decodePubkey(owner) ?: return Result.Failed(ctx.getString(R.string.wa_bad_address))
        val mint = Base58.decode(SKR_MINT)
        val program = WalletTx.tokenProgramFor(src.program)
        val treasuryAta = Pda.associatedTokenAddress(treasury, mint, program)
        val ixs = listOf(
            WalletTx.setComputeUnitLimit(80_000),
            WalletTx.createAtaIdempotent(ownerKey, treasuryAta, treasury, mint, program),
            WalletTx.tokenTransferChecked(Base58.decode(src.pubkey), mint, treasuryAta, ownerKey, quote.priceRaw, quote.decimals, program),
            WalletTx.memo("clearsign:theme:${palette.id}"),
        )
        val name = ctx.getString(palette.nameRes)
        val r = signAndSend(
            ctx, signer, owner, ixs,
            LogInfo(kind = "theme", outflows = listOf("−$THEME_PRICE_SKR SKR"), recipient = BuildConfig.SKR_TREASURY, recipientLabel = ctx.getString(R.string.wa_log_theme, name)),
        )
        if (r is Result.Sent) {
            Themes.unlock(ctx, palette.id, r.signature)
            Themes.select(ctx, palette.id)
        }
        return r
    }

    /** Pay [PRO_PRICE_SKR] SKR to the treasury and unlock ClearSign Pro. */
    suspend fun unlockPro(ctx: Context, signer: SeedVaultSigner, owner: String, quote: SkrQuote): Result {
        val src = quote.account ?: return Result.Failed(ctx.getString(R.string.theme_unlock_no_skr))
        if (!quote.enoughForPro) return Result.Failed(ctx.getString(R.string.theme_unlock_insufficient, quote.uiBalance(), PRO_PRICE_SKR))
        val treasury = Base58.decodePubkey(BuildConfig.SKR_TREASURY) ?: return Result.Failed(ctx.getString(R.string.theme_unlock_no_treasury))
        val ownerKey = Base58.decodePubkey(owner) ?: return Result.Failed(ctx.getString(R.string.wa_bad_address))
        val mint = Base58.decode(SKR_MINT)
        val program = WalletTx.tokenProgramFor(src.program)
        val treasuryAta = Pda.associatedTokenAddress(treasury, mint, program)
        val ixs = listOf(
            WalletTx.setComputeUnitLimit(80_000),
            WalletTx.createAtaIdempotent(ownerKey, treasuryAta, treasury, mint, program),
            WalletTx.tokenTransferChecked(Base58.decode(src.pubkey), mint, treasuryAta, ownerKey, quote.proPriceRaw, quote.decimals, program),
            WalletTx.memo("clearsign:pro"),
        )
        val r = signAndSend(
            ctx, signer, owner, ixs,
            LogInfo(kind = "theme", outflows = listOf("−$PRO_PRICE_SKR SKR"), recipient = BuildConfig.SKR_TREASURY, recipientLabel = ctx.getString(R.string.pro_log)),
        )
        if (r is Result.Sent) Pro.set(ctx, r.signature)
        return r
    }

    private fun pow10(n: Int): Long { var v = 1L; repeat(n) { v *= 10 }; return v }
}
