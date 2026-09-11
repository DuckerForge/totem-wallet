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
        val tx = WalletTx.build(ownerKey, Base58.decode(bh.hash), instructions)

        val sim = withContext(Dispatchers.IO) { SolanaRpc.simulate(rpc, tx) }
        if (sim != null && !sim.ok) {
            Log.w(TAG, "simulation failed: ${sim.err} logs=${sim.logs.takeLast(3)}")
            return Result.Failed(ctx.getString(R.string.wa_sim_failed, humanError(sim)))
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
            at, "ClearSign", null, cluster, listOf(Attestation.sha256Hex(tx)), log.outflows, log.inflows,
            receipt?.risks?.map { it.flag.name }?.distinct() ?: emptyList(), owner, sig,
        )
        val attSig = Attestation.sign(statement)
        LedgerRecorder.record(
            ctx,
            LedgerRecorder.fromReceipt(
                at = at, kind = log.kind, dApp = "ClearSign", host = null, pkg = ctx.packageName, cluster = cluster, wallet = owner,
                r = receipt, signature = sig, sent = true, txIndex = 0, txCount = 1, groupId = LedgerRecorder.newId(),
                attestation = if (attSig != null) statement else null, attestationSig = attSig, recipientLabelFallback = log.recipientLabel,
            ),
        )
        Haptics.success(ctx)
        return Result.Sent(sig)
    }

    private fun humanError(sim: SolanaRpc.SimResult): String {
        val log = sim.logs.lastOrNull { it.contains("Error", true) || it.contains("insufficient", true) || it.contains("failed", true) }
        return log?.substringAfter("Program log: ")?.take(120) ?: sim.err?.take(120) ?: "?"
    }

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
