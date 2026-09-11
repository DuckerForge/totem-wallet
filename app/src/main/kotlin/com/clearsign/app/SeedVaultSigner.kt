package com.clearsign.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.clearsign.core.HardwareSigner
import com.solanamobile.seedvault.Bip44DerivationPath
import com.solanamobile.seedvault.BipLevel
import com.solanamobile.seedvault.SeedVault
import com.solanamobile.seedvault.SigningRequest
import com.solanamobile.seedvault.Wallet
import com.solanamobile.seedvault.WalletContractV1
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Bridges Android's Intent-based Seed Vault API to suspend functions.
 *
 * The Seed Vault performs every key operation inside the TEE and gates it with
 * the device biometric; the app only ever sends/receives Intents. This bridge
 * must be constructed while the Activity is CREATED (before it is STARTED),
 * because [ComponentActivity.registerForActivityResult] requires it.
 */
class ActivityResultBridge(activity: ComponentActivity) {
    private var pending: CompletableDeferred<ActivityResult>? = null
    private var pendingPerm: CompletableDeferred<Boolean>? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        pending?.complete(result)
        pending = null
    }

    private val permLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        pendingPerm?.complete(granted)
        pendingPerm = null
    }

    /** Launch [intent] and suspend until its result comes back. */
    suspend fun launch(intent: Intent): ActivityResult = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<ActivityResult>()
        pending = deferred
        launcher.launch(intent)
        deferred.await()
    }

    /** Request a runtime permission and suspend until the user decides. */
    suspend fun requestPermission(permission: String): Boolean = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<Boolean>()
        pendingPerm = deferred
        permLauncher.launch(permission)
        deferred.await()
    }
}

class SeedVaultException(val resultCode: Int, message: String) : Exception(message)

/** One selectable wallet account within an authorised seed. */
data class SvAccount(
    val label: String?,
    val derivationUri: Uri,
    val pubkeyBase58: String,
    val pubkeyBytes: ByteArray,
)

/**
 * Real [HardwareSigner] backed by the Solana Seed Vault. Private keys never
 * leave the vault; signing is authorised by the user's biometric.
 *
 * Call [connect] once (from a coroutine) to authorise the app for a seed and
 * cache the account public key; after that [sign] can be called from a
 * background thread (it blocks that thread while the biometric prompt runs).
 */
class SeedVaultSigner(
    private val context: Context,
    private val bridge: ActivityResultBridge,
) : HardwareSigner {

    private var authToken: Long? = null
    private var selected: SvAccount? = null

    private fun derivationUriForAccount(index: Int) =
        Bip44DerivationPath.newBuilder().setAccount(BipLevel(index, true)).build().toUri()

    val isConnected: Boolean get() = authToken != null && selected != null

    private val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            context, WalletContractV1.PERMISSION_ACCESS_SEED_VAULT,
        ) == PackageManager.PERMISSION_GRANTED

    fun isSeedVaultAvailable(): Boolean = try {
        hasPermission && SeedVault.isAvailable(context, true)
    } catch (_: Exception) {
        false
    }

    /**
     * Grant the runtime permission, authorise (or reuse) a seed, and fetch the
     * account public key. [ACCESS_SEED_VAULT] is a *dangerous* permission, so it
     * must be requested at runtime before any Seed Vault call.
     */
    suspend fun connect(): String {
        if (!hasPermission) {
            val granted = bridge.requestPermission(WalletContractV1.PERMISSION_ACCESS_SEED_VAULT)
            if (!granted) throw SeedVaultException(-1, context.getString(R.string.sv_perm_denied))
        }
        if (!SeedVault.isAvailable(context, true)) {
            throw SeedVaultException(-1, context.getString(R.string.sv_unavailable))
        }
        val accounts = authorizeAndListAccounts()
        selected = accounts.first()
        return selected!!.pubkeyBase58
    }

    /**
     * Ensure permission + Seed Vault availability, authorise a seed (the user
     * picks which), and return the seed's accounts so the caller can let the
     * user choose which wallet to connect. Call [selectAccount] with the choice.
     */
    suspend fun authorizeAndListAccounts(): List<SvAccount> {
        if (!hasPermission) {
            val granted = bridge.requestPermission(WalletContractV1.PERMISSION_ACCESS_SEED_VAULT)
            if (!granted) throw SeedVaultException(-1, context.getString(R.string.sv_perm_denied))
        }
        if (!SeedVault.isAvailable(context, true)) {
            throw SeedVaultException(-1, context.getString(R.string.sv_unavailable))
        }
        val token = existingAuthToken() ?: authorize()
        authToken = token
        return listAccounts(token)
    }

    fun selectAccount(account: SvAccount) {
        selected = account
    }

    /**
     * Make sure we are connected *and* signing with exactly the account the dApp
     * was authorized for. After a reauthorize (auth token from a previous run)
     * the signer starts cold, and blindly taking the first account would sign
     * with the wrong key — so the account is matched by public key, or we refuse.
     */
    suspend fun ensureAccount(pubkeyBase58: String) {
        if (isConnected && selected?.pubkeyBase58 == pubkeyBase58) return
        val accounts = authorizeAndListAccounts()
        selected = accounts.firstOrNull { it.pubkeyBase58 == pubkeyBase58 }
            ?: throw SeedVaultException(-2, context.getString(R.string.sv_account_missing, "${pubkeyBase58.take(4)}…${pubkeyBase58.takeLast(4)}"))
    }

    /**
     * List the seed's accounts. Prefers the accounts content provider (gives the
     * user-set names like "atreides.skr"); if that needs privileged access
     * (Simulator), falls back to enumerating the first several BIP44 accounts.
     */
    private suspend fun listAccounts(token: Long): List<SvAccount> {
        accountsFromProvider(token).takeIf { it.isNotEmpty() }?.let { return it }
        val uris = (0..7).map { derivationUriForAccount(it) }
        val result = bridge.launch(Wallet.requestPublicKeys(context, token, ArrayList(uris)))
        if (result.resultCode != Activity.RESULT_OK) {
            throw SeedVaultException(result.resultCode, context.getString(R.string.sv_accounts_failed))
        }
        val responses = Wallet.onRequestPublicKeysResult(result.resultCode, result.data!!)
        return responses.mapIndexed { i, r ->
            SvAccount(context.getString(R.string.account_n, i + 1), uris[i], r.publicKeyEncoded, r.publicKey)
        }
    }

    private fun accountsFromProvider(token: Long): List<SvAccount> = try {
        val cursor = Wallet.getAccounts(
            context,
            token,
            arrayOf(
                WalletContractV1.ACCOUNTS_BIP32_DERIVATION_PATH,
                WalletContractV1.ACCOUNTS_PUBLIC_KEY_ENCODED,
                WalletContractV1.ACCOUNTS_PUBLIC_KEY_RAW,
                WalletContractV1.ACCOUNTS_ACCOUNT_NAME,
                WalletContractV1.ACCOUNTS_ACCOUNT_IS_USER_WALLET,
            ),
        )
        val all = ArrayList<Pair<SvAccount, Boolean>>()
        cursor?.use {
            while (it.moveToNext()) {
                val path = it.getString(0) ?: continue
                val b58 = it.getString(1) ?: continue
                val raw = it.getBlob(2) ?: continue
                val name = it.getString(3)?.takeIf { n -> n.isNotBlank() }
                val isUserWallet = it.getInt(4) != 0
                all.add(SvAccount(name, Uri.parse(path), b58, raw) to isUserWallet)
            }
        }
        // Prefer the accounts the user actually uses (named wallets); if the seed
        // has none flagged, fall back to showing all (capped) so nothing is hidden.
        val userWallets = all.filter { it.second }.map { it.first }
        if (userWallets.isNotEmpty()) userWallets else all.map { it.first }.take(10)
    } catch (_: SecurityException) {
        emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Reuse an existing authorisation by reading the wallet content provider.
     * This direct read needs ACCESS_SEED_VAULT_PRIVILEGED (only granted to
     * system apps / the Seed Vault Simulator's privileged callers), so on a
     * normal install it throws SecurityException — we then fall back to the
     * Intent-based [authorize] flow, which only needs ACCESS_SEED_VAULT.
     */
    private fun existingAuthToken(): Long? = try {
        val cursor = Wallet.getAuthorizedSeeds(
            context,
            arrayOf(WalletContractV1.AUTHORIZED_SEEDS_AUTH_TOKEN),
        ) ?: return null
        cursor.use { if (it.moveToFirst()) it.getLong(0) else null }
    } catch (_: SecurityException) {
        null
    }

    private suspend fun authorize(): Long {
        val result = bridge.launch(
            Wallet.authorizeSeed(context, WalletContractV1.PURPOSE_SIGN_SOLANA_TRANSACTION),
        )
        if (result.resultCode != Activity.RESULT_OK) {
            throw SeedVaultException(result.resultCode, context.getString(R.string.sv_auth_failed))
        }
        return Wallet.onAuthorizeSeedResult(result.resultCode, result.data!!)
    }

    override fun publicKey(): String =
        selected?.pubkeyBase58 ?: error(context.getString(R.string.sv_not_connected))

    fun publicKeyBytes(): ByteArray =
        selected?.pubkeyBytes ?: error(context.getString(R.string.sv_not_connected))

    /** Suspend variant used by the MWA endpoint; shows the biometric prompt. */
    suspend fun signSuspend(serializedTx: ByteArray): ByteArray {
        val token = authToken ?: error(context.getString(R.string.sv_not_connected))
        val path = selected?.derivationUri ?: error(context.getString(R.string.sv_no_account))
        // The Seed Vault signs the bytes it receives as-is: hand it the transaction
        // *message* (past the signature array), never the whole serialized tx —
        // otherwise the signature covers the wrong bytes and every node rejects it.
        val message = runCatching { SolanaTx.messageBytes(serializedTx) }.getOrDefault(serializedTx)
        val request = arrayListOf(SigningRequest(message, listOf(path)))
        val result = bridge.launch(Wallet.signTransactions(context, token, request))
        if (result.resultCode != Activity.RESULT_OK) {
            throw SeedVaultException(result.resultCode, context.getString(R.string.sv_sign_refused, result.resultCode))
        }
        val responses = Wallet.onSignTransactionsResult(result.resultCode, result.data!!)
        return responses.first().signatures.first()
    }

    /** Sign an off-chain message (e.g. a dApp login challenge) with the Seed Vault. */
    suspend fun signMessageSuspend(message: ByteArray): ByteArray {
        val token = authToken ?: error(context.getString(R.string.sv_not_connected))
        val path = selected?.derivationUri ?: error(context.getString(R.string.sv_no_account))
        val request = arrayListOf(SigningRequest(message, listOf(path)))
        val result = bridge.launch(Wallet.signMessages(context, token, request))
        if (result.resultCode != Activity.RESULT_OK) {
            throw SeedVaultException(result.resultCode, context.getString(R.string.sv_msg_refused, result.resultCode))
        }
        val responses = Wallet.onSignMessagesResult(result.resultCode, result.data!!)
        return responses.first().signatures.first()
    }

    /**
     * Synchronous [HardwareSigner.sign] required by ClearSignFlow. It blocks the
     * *calling* thread (never call it on the main thread) while the Seed Vault
     * biometric prompt runs on the main thread.
     */
    override fun sign(serializedTx: ByteArray): ByteArray = runBlocking { signSuspend(serializedTx) }
}
