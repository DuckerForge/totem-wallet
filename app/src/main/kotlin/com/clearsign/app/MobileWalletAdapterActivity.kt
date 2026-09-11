package com.clearsign.app

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.clearsign.core.BalanceDelta
import com.clearsign.core.Receipt
import com.clearsign.core.ReceiptBuilder
import com.clearsign.core.Risk
import com.clearsign.core.RiskEngine
import com.clearsign.core.RiskFlag
import com.clearsign.core.ScanResult
import com.clearsign.core.Severity
import com.clearsign.core.SimulationGuard
import com.solana.mobilewalletadapter.common.signin.SignInWithSolana
import com.solana.mobilewalletadapter.walletlib.association.AssociationUri
import com.solana.mobilewalletadapter.walletlib.authorization.AuthIssuerConfig
import com.solana.mobilewalletadapter.walletlib.protocol.MobileWalletAdapterConfig
import com.solana.mobilewalletadapter.walletlib.scenario.AuthorizeRequest
import com.solana.mobilewalletadapter.walletlib.scenario.AuthorizedAccount
import com.solana.mobilewalletadapter.walletlib.scenario.DeauthorizedEvent
import com.solana.mobilewalletadapter.walletlib.scenario.LocalScenario
import com.solana.mobilewalletadapter.walletlib.scenario.ReauthorizeRequest
import com.solana.mobilewalletadapter.walletlib.scenario.Scenario
import com.solana.mobilewalletadapter.walletlib.scenario.SignAndSendTransactionsRequest
import com.solana.mobilewalletadapter.walletlib.scenario.SignInResult
import com.solana.mobilewalletadapter.walletlib.scenario.SignMessagesRequest
import com.solana.mobilewalletadapter.walletlib.scenario.SignTransactionsRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * ClearSign's Mobile Wallet Adapter endpoint — the "phone as a Ledger".
 *
 * dApps start a local association (solana-wallet://) that Android routes here.
 * For every request we build a plain-language receipt from the *real*
 * transaction bytes, and only sign with the Seed Vault after the user approves
 * what they actually see.
 */
private const val TAG = "ClearSign-MWA"

class MobileWalletAdapterActivity : ComponentActivity() {

    private lateinit var bridge: ActivityResultBridge
    private lateinit var signer: SeedVaultSigner
    private var scenario: Scenario? = null
    private val scanner by lazy { BlocklistScanner(this) }

    private var ui by mutableStateOf<MwaUi>(MwaUi.Preparing)
    private var servingClients = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Themes.load(this)
        Settings.load(this)
        bridge = ActivityResultBridge(this)
        signer = SeedVaultSigner(this, bridge)
        // Bind the websocket server FIRST, before the (slow) Compose init, so the
        // dApp can connect within its timeout.
        startSession(intent)
        enableEdgeToEdge()
        setContent { MwaScreen(ui) }
    }

    // singleTask: a new association reuses this instance, so restart the session.
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.data == null) return   // bringToFront(): same session, just surface the activity
        setIntent(intent)
        val old = scenario
        scenario = null
        servingClients = false
        ui = MwaUi.Preparing
        old?.close() // its teardown callbacks are ignored: they no longer own `scenario`
        startSession(intent)
    }

    /** The native app that opened us (android-app://<package>), or null for a browser/web dApp. */
    private var callerPackage: String? = null

    private fun startSession(intent: android.content.Intent?) {
        callerPackage = referrer?.takeIf { it.scheme == "android-app" }?.host
        Log.i(TAG, "caller package: $callerPackage")
        val associationUri = intent?.data?.let { AssociationUri.parse(it) }
        if (associationUri == null) {
            ui = MwaUi.Error(getString(R.string.err_invalid_association))
            return
        }
        val config = MobileWalletAdapterConfig(
            /* supportsSignAndSendTransactions = */ true,
            /* maxTransactionsPerSigningRequest = */ 10,
            /* maxMessagesPerSigningRequest = */ 10,
            /* supportedTransactionVersions = */ arrayOf<Any>("legacy", 0),
            /* noConnectionWarningTimeoutMs = */ 10_000L,
        )
        val callbacks = Callbacks()
        val sc = associationUri.createScenario(this, config, AuthIssuerConfig("ClearSign"), callbacks)
        callbacks.owner = sc
        scenario = sc
        // Reference pattern (fakewallet): drive the local server on an IO thread
        // via startAsync().get() rather than a synchronous start() on main.
        lifecycleScope.launch(Dispatchers.IO) { runCatching { sc.startAsync().get() } }
    }

    override fun onDestroy() {
        super.onDestroy()
        scenario?.close()
    }

    private fun onMain(block: () -> Unit) = runOnUiThread(block)

    /**
     * Go back to the dApp while keeping the MWA session alive: a dApp that
     * signs "one by one" sends the next request on the same session, and
     * [bringToFront] surfaces us again for it. Called from the Done screen.
     */
    fun backToDapp() {
        if (ui is MwaUi.Done) moveTaskToBack(true)
    }

    private fun bringToFront() {
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        startActivity(
            android.content.Intent(this, MobileWalletAdapterActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Let the Done screen breathe before the session end closes the task. */
    private fun finishSoon() {
        lifecycleScope.launch {
            if (ui is MwaUi.Done) delay(1600)
            finishAndRemoveTask()
        }
    }

    private fun signerIndex(d: SolanaTx.Decoded, myWallet: String): Int {
        val idx = d.staticAccountKeys.indexOf(myWallet)
        return if (idx in 0 until d.numRequiredSignatures) idx else 0
    }

    private fun identityOf(name: String?, uri: Uri?, iconRel: Uri?): DappId {
        val icon = iconRel?.let { rel ->
            if (rel.isAbsolute) rel.toString()
            else uri?.let { runCatching { java.net.URI(it.toString()).resolve(rel.toString()).toString() }.getOrNull() }
        }
        // Browsers are not the dApp: only a native caller carries store intel.
        val pkg = callerPackage?.takeIf { !it.contains("chrome") && !it.contains("browser") && !it.contains("firefox") && !it.contains("brave") }
        return DappId(name = name ?: uri?.host ?: getString(R.string.unknown_dapp), host = uri?.host, iconUrl = icon, store = StoreIntel.of(this, pkg))
    }

    /** After a successful signature: remember the counterparties and log the receipt. */
    private fun remember(items: List<ReceiptEngine.Analyzed>, dApp: DappId, cluster: String?, myWallet: String, signatures: List<String?>, sent: Boolean) {
        val ctx = this
        val signature = signatures.firstOrNull { it != null }
        items.forEach { a ->
            a.receipt.primaryRecipient?.let { Contacts.addHistory(ctx, it) }
            a.receipt.distributions.filter { !it.isNewAccount }.forEach { Contacts.addHistory(ctx, it.address) }
        }
        val first = items.firstOrNull()?.receipt
        val at = System.currentTimeMillis()
        val outflows = items.flatMap { it.receipt.outflows }.map { "−" + fmtDelta(it) }
        val inflows = items.flatMap { it.receipt.inflows }.map { "+" + fmtDelta(it) }
        // Attested receipt: the app's hardware key signs what was shown and approved.
        val statement = Attestation.statement(
            at, dApp.name, dApp.host, cluster, items.map { Attestation.sha256Hex(it.payload) }, outflows, inflows,
            items.flatMap { a -> a.receipt.risks.map { it.flag.name } }.distinct(), myWallet, signature,
        )
        val attSig = Attestation.sign(statement)
        // The structured ledger: one entry per transaction, sharing the approval's attestation.
        val group = LedgerRecorder.newId()
        items.forEachIndexed { i, a ->
            LedgerRecorder.record(
                ctx,
                LedgerRecorder.fromReceipt(
                    at = at, kind = "tx", dApp = dApp.name, host = dApp.host, pkg = dApp.store?.packageName, cluster = cluster, wallet = myWallet,
                    r = a.receipt, signature = signatures.getOrNull(i), sent = sent, txIndex = i, txCount = items.size, groupId = group,
                    attestation = if (attSig != null) statement else null, attestationSig = attSig,
                ),
            )
        }
    }

    /** A signature that moved no value (login, message): still a line in the ledger. */
    private fun rememberPlain(kind: String, dApp: DappId, cluster: String?, myWallet: String, count: Int) {
        LedgerRecorder.record(
            this,
            LedgerRecorder.fromReceipt(
                at = System.currentTimeMillis(), kind = kind, dApp = dApp.name, host = dApp.host, pkg = dApp.store?.packageName, cluster = cluster, wallet = myWallet,
                r = null, signature = null, sent = false, txIndex = 0, txCount = count, groupId = LedgerRecorder.newId(), attestation = null, attestationSig = null,
            ),
        )
    }

    private fun fmtDelta(d: BalanceDelta): String =
        String.format(Locale.ROOT, "%.${minOf(d.decimals, 6)}f", kotlin.math.abs(d.uiAmount)).trimEnd('0').trimEnd('.') + " " + d.symbol

    /** Human text for a message to sign: UTF-8, control characters made visible, binary flagged. */
    private fun messageText(m: ByteArray): String {
        val s = runCatching { String(m, Charsets.UTF_8) }.getOrNull() ?: return getString(R.string.binary_message, m.size)
        val bad = s.count { it == '�' || (it.code < 32 && it != '\n' && it != '\t' && it != '\r') }
        if (s.isBlank() || bad > s.length / 10) return getString(R.string.binary_message, m.size)
        return s.map { if (it.code < 32 && it != '\n' && it != '\t' && it != '\r') '·' else it }.joinToString("")
    }

    private inner class Callbacks : LocalScenario.Callbacks {
        /** The scenario these callbacks belong to; events from a superseded session are ignored. */
        var owner: Scenario? = null
        private val live: Boolean get() = owner != null && owner === scenario

        override fun onScenarioReady() {}
        override fun onScenarioServingClients() { if (live) servingClients = true }
        override fun onScenarioServingComplete() { if (live) onMain { finishSoon() } }
        override fun onScenarioComplete() {}
        override fun onScenarioError() { if (live) onMain { ui = MwaUi.Error(getString(R.string.err_session)) } }
        override fun onScenarioTeardownComplete() {
            if (!live) return
            // Reference pattern: distinguish a normal end (session had connected)
            // from a session that never established (dApp never connected).
            onMain {
                if (servingClients) finishSoon()
                else ui = MwaUi.Error(getString(R.string.err_not_connected))
            }
        }
        override fun onLowPowerAndNoConnection() {
            if (!live || servingClients) return
            onMain {
                ui = MwaUi.Error(getString(R.string.err_low_power))
            }
        }

        override fun onAuthorizeRequest(request: AuthorizeRequest) {
            onMain { bringToFront() }
            Log.i(TAG, "onAuthorizeRequest from ${request.identityName}, chain=${request.chain}, cluster=${request.cluster}, siws=${request.signInPayload != null}")
            val dApp = identityOf(request.identityName, request.identityUri, request.iconRelativeUri)
            val siws = request.signInPayload
            onMain {
                ui = MwaUi.Connect(
                    dApp = dApp,
                    onApprove = {
                        ui = MwaUi.Working(getString(R.string.w_connecting_sv))
                        lifecycleScope.launch {
                            try {
                                val accounts = signer.authorizeAndListAccounts()
                                val rpc = SolanaRpc.urlFor(request.cluster)
                                val balances = withContext(Dispatchers.IO) { SolanaRpc.assetsSummaryMulti(rpc, accounts.map { it.pubkeyBase58 }) }
                                ui = MwaUi.AccountPick(
                                    dApp = dApp,
                                    accounts = accounts.map { a -> val (lam, tok) = balances[a.pubkeyBase58] ?: (null to 0); Triple(a, lam, tok) },
                                    onPick = { acc ->
                                        signer.selectAccount(acc)
                                        // The sign request usually follows within seconds: warm the caches now.
                                        lifecycleScope.launch(Dispatchers.IO) { SolanaRpc.prefetch(rpc, acc.pubkeyBase58) }
                                        if (siws != null) askSignIn(request, dApp, acc, siws) else completeAuthorize(request, acc, null)
                                    },
                                    onDecline = { request.completeWithDecline(); finishAndRemoveTask() },
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "authorize failed", e)
                                request.completeWithDecline()
                                ui = MwaUi.Error(e.message ?: getString(R.string.err_connect_cancelled))
                            }
                        }
                    },
                    onDecline = { request.completeWithDecline(); finishAndRemoveTask() },
                )
            }
        }

        /** Sign-In-With-Solana: show the *parsed* login (domain, statement, nonce) and
         *  flag a domain that doesn't match the dApp's own identity (phishing tell). */
        private fun askSignIn(request: AuthorizeRequest, dApp: DappId, acc: SvAccount, siws: SignInWithSolana.Payload) {
            val message = runCatching { siws.prepareMessage(acc.pubkeyBase58) }.getOrNull()
            if (message == null) { completeAuthorize(request, acc, null); return }
            val host = dApp.host
            val mismatch = host != null && siws.domain != null && siws.domain != host && !host.endsWith(".${siws.domain}")
            ui = MwaUi.SignInRequest(
                dApp = dApp,
                domain = siws.domain ?: "?",
                statement = siws.statement,
                uri = siws.uri?.toString(),
                nonce = siws.nonce,
                issuedAt = siws.issuedAt,
                fullMessage = message,
                domainMismatch = mismatch,
                onApprove = {
                    ui = MwaUi.Working(getString(R.string.w_signing_login))
                    lifecycleScope.launch {
                        try {
                            val bytes = message.toByteArray(Charsets.UTF_8)
                            val sig = signer.signMessageSuspend(bytes)
                            Log.i(TAG, "signMessage by=${acc.pubkeyBase58} msgLen=${bytes.size} msgHex=${bytes.joinToString("") { "%02x".format(it) }} sigHex=${sig.joinToString("") { "%02x".format(it) }} (siws)")
                            completeAuthorize(request, acc, SignInResult(acc.pubkeyBytes, bytes, sig, "ed25519"))
                            rememberPlain("signin", dApp, request.cluster, acc.pubkeyBase58, 1)
                        } catch (e: Exception) {
                            Log.e(TAG, "SIWS sign failed", e)
                            request.completeWithDecline()
                            ui = MwaUi.Error(e.message ?: getString(R.string.err_login_cancelled))
                        }
                    }
                },
                onDecline = { request.completeWithDecline(); finishAndRemoveTask() },
            )
        }

        private fun completeAuthorize(request: AuthorizeRequest, acc: SvAccount, signIn: SignInResult?) {
            ui = MwaUi.Working(getString(R.string.w_authorizing))
            lifecycleScope.launch {
                try {
                    val label = acc.label ?: "ClearSign"
                    if (signIn == null) request.completeWithAuthorize(acc.pubkeyBytes, label, null, null)
                    else request.completeWithAuthorize(AuthorizedAccount(acc.pubkeyBytes, label, null, null, null), null, null, signIn)
                    ui = MwaUi.Working(getString(R.string.w_waiting_dapp))
                } catch (e: Exception) {
                    Log.e(TAG, "completeWithAuthorize failed", e)
                    request.completeWithDecline()
                    ui = MwaUi.Error(e.message ?: getString(R.string.err_connect_cancelled))
                }
            }
        }

        override fun onReauthorizeRequest(request: ReauthorizeRequest) {
            onMain { bringToFront() }
            Log.i(TAG, "onReauthorizeRequest from ${request.identityName}")
            request.completeWithReauthorize()
        }

        override fun onSignTransactionsRequest(request: SignTransactionsRequest) {
            onMain { bringToFront() }
            Log.i(TAG, "onSignTransactionsRequest: ${request.payloads.size} payload(s)")
            val dApp = identityOf(request.identityName, request.identityUri, request.iconRelativeUri)
            val payloads = request.payloads.toList()
            val myWallet = Base58.encode(request.authorizedPublicKey)
            onMain { ui = MwaUi.Working(if (payloads.size > 1) getString(R.string.w_analyzing_n, payloads.size) else getString(R.string.w_analyzing)) }
            val items = try {
                runBlocking { ReceiptEngine.analyzeAll(this@MobileWalletAdapterActivity, scanner, payloads, myWallet, request.cluster, requireSim = false) }
            } catch (e: Exception) {
                Log.e(TAG, "analyze (sign) failed", e)
                request.completeWithDecline(); onMain { finishAndRemoveTask() }; return
            }
            onMain {
                ui = MwaUi.SignRequest(
                    dApp = dApp, receipts = items.map { it.receipt }, willSend = false, cluster = request.cluster,
                    onApprove = {
                        ui = MwaUi.Working(getString(R.string.w_drift))
                        lifecycleScope.launch {
                            try {
                                val drift = ReceiptEngine.driftGuard(items, myWallet, request.cluster)
                                if (drift != null) {
                                    request.completeWithDecline()
                                    ui = MwaUi.Error(getString(R.string.err_blocked, drift.detail)); return@launch
                                }
                                ui = MwaUi.Working(getString(R.string.w_signing))
                                signer.ensureAccount(myWallet)
                                val txSigs = ArrayList<String?>()
                                val signed = payloads.map { p ->
                                    val sig = signer.signSuspend(p); txSigs.add(Base58.encode(sig))
                                    val d = SolanaTx.decode(p)
                                    val idx = d?.let { signerIndex(it, myWallet) } ?: 0
                                    SolanaTx.attachSignature(p, idx, sig)
                                }.toTypedArray()
                                request.completeWithSignedPayloads(signed)
                                remember(items, dApp, request.cluster, myWallet, signatures = txSigs, sent = false)
                                ui = MwaUi.Done(
                                    if (payloads.size > 1) getString(R.string.done_signed_n, payloads.size) else getString(R.string.done_signed),
                                    signature = null, cluster = request.cluster,
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "signTransactions failed", e)
                                request.completeWithDecline()
                                ui = MwaUi.Error(e.message ?: getString(R.string.err_sign_cancelled))
                            }
                        }
                    },
                    onDecline = { request.completeWithDecline(); finishAndRemoveTask() },
                )
            }
        }

        override fun onSignAndSendTransactionsRequest(request: SignAndSendTransactionsRequest) {
            onMain { bringToFront() }
            Log.i(TAG, "onSignAndSendTransactionsRequest: ${request.payloads.size} payload(s), cluster=${request.cluster}")
            val dApp = identityOf(request.identityName, request.identityUri, request.iconRelativeUri)
            val payloads = request.payloads.toList()
            val cluster = request.cluster
            val myWallet = Base58.encode(request.publicKey)
            onMain { ui = MwaUi.Working(if (payloads.size > 1) getString(R.string.w_analyzing_n, payloads.size) else getString(R.string.w_analyzing)) }
            val items = try {
                runBlocking { ReceiptEngine.analyzeAll(this@MobileWalletAdapterActivity, scanner, payloads, myWallet, cluster, requireSim = true) }
            } catch (e: Exception) {
                Log.e(TAG, "analyze (signAndSend) failed", e)
                request.completeWithDecline(); onMain { finishAndRemoveTask() }; return
            }
            onMain {
                ui = MwaUi.SignRequest(
                    dApp = dApp, receipts = items.map { it.receipt }, willSend = true, cluster = cluster,
                    onApprove = {
                        ui = MwaUi.Working(getString(R.string.w_drift))
                        lifecycleScope.launch {
                            try {
                                val drift = ReceiptEngine.driftGuard(items, myWallet, cluster)
                                if (drift != null) {
                                    request.completeWithDecline()
                                    ui = MwaUi.Error(getString(R.string.err_blocked, drift.detail)); return@launch
                                }
                                ui = MwaUi.Working(getString(R.string.w_signing))
                                signer.ensureAccount(myWallet)
                                val sigs = ArrayList<ByteArray>()
                                var sendError: String? = null
                                var lastSig: String? = null
                                val txSigs = ArrayList<String?>()
                                for (p in payloads) {
                                    val sig = signer.signSuspend(p)
                                    sigs.add(sig)
                                    if (sendError != null) continue
                                    val d = SolanaTx.decode(p)
                                    val idx = d?.let { signerIndex(it, myWallet) } ?: 0
                                    val signedTx = SolanaTx.attachSignature(p, idx, sig)
                                    ui = MwaUi.Working(getString(R.string.w_sending))
                                    val out = withContext(Dispatchers.IO) { SolanaRpc.send(SolanaRpc.urlFor(cluster), signedTx) }
                                    if (out.signature != null) lastSig = out.signature else sendError = out.error
                                    txSigs.add(out.signature)
                                }
                                if (sendError == null) {
                                    request.completeWithSignatures(sigs.toTypedArray())
                                    remember(items, dApp, cluster, myWallet, signatures = txSigs, sent = true)
                                    ui = MwaUi.Done(
                                        if (payloads.size > 1) getString(R.string.done_sent_n, payloads.size) else getString(R.string.done_sent),
                                        signature = lastSig, cluster = cluster,
                                    )
                                } else {
                                    request.completeWithNotSubmitted(sigs.toTypedArray())
                                    ui = MwaUi.Error(getString(R.string.err_send, sendError))
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "signAndSend failed", e)
                                request.completeWithDecline()
                                ui = MwaUi.Error(e.message ?: getString(R.string.err_sign_cancelled))
                            }
                        }
                    },
                    onDecline = { request.completeWithDecline(); finishAndRemoveTask() },
                )
            }
        }

        override fun onSignMessagesRequest(request: SignMessagesRequest) {
            onMain { bringToFront() }
            Log.i(TAG, "onSignMessagesRequest: ${request.payloads.size} message(s)")
            val dApp = identityOf(request.identityName, request.identityUri, request.iconRelativeUri)
            val payloads = request.payloads
            // Sign with the address the dApp asked for (falls back to the authorized one).
            val signWith = Base58.encode(request.addresses.firstOrNull() ?: request.authorizedPublicKey)
            val texts = payloads.map { messageText(it) }
            onMain {
                ui = MwaUi.MessageRequest(
                    dApp = dApp,
                    messages = texts,
                    onApprove = {
                        ui = MwaUi.Working(getString(R.string.w_signing_msg))
                        lifecycleScope.launch {
                            try {
                                signer.ensureAccount(signWith)
                                // MWA sign_messages returns each message with its signature appended.
                                val signed = payloads.map { m ->
                                    val sig = signer.signMessageSuspend(m)
                                    // Diagnostic: lets the signature be verified off-device against the raw message.
                                    Log.i(TAG, "signMessage by=$signWith msgLen=${m.size} msgHex=${m.joinToString("") { "%02x".format(it) }} sigHex=${sig.joinToString("") { "%02x".format(it) }}")
                                    m + sig
                                }.toTypedArray()
                                request.completeWithSignedPayloads(signed)
                                rememberPlain("message", dApp, request.cluster, signWith, payloads.size)
                                ui = MwaUi.Done(
                                    if (payloads.size > 1) getString(R.string.done_msgs_n, payloads.size, dApp.name) else getString(R.string.done_msg, dApp.name),
                                    signature = null, cluster = request.cluster,
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "signMessages failed", e)
                                request.completeWithDecline()
                                ui = MwaUi.Error(e.message ?: getString(R.string.err_sign_cancelled))
                            }
                        }
                    },
                    onDecline = { request.completeWithDecline(); finishAndRemoveTask() },
                )
            }
        }

        override fun onDeauthorizedEvent(event: DeauthorizedEvent) {
            event.complete()
        }
    }
}

/** Who is asking: the dApp's declared name, its verified host, and its icon (absolute URL). */
data class DappId(val name: String, val host: String?, val iconUrl: String?, val store: StoreInfo? = null)

sealed interface MwaUi {
    data object Preparing : MwaUi
    data class Connect(val dApp: DappId, val onApprove: () -> Unit, val onDecline: () -> Unit) : MwaUi
    data class AccountPick(
        val dApp: DappId,
        val accounts: List<Triple<SvAccount, Long?, Int>>, // account, SOL lamports, token count
        val onPick: (SvAccount) -> Unit,
        val onDecline: () -> Unit,
    ) : MwaUi
    data class SignInRequest(
        val dApp: DappId,
        val domain: String,
        val statement: String?,
        val uri: String?,
        val nonce: String?,
        val issuedAt: String?,
        val fullMessage: String,
        val domainMismatch: Boolean,   // SIWS domain ≠ the dApp's own host → phishing tell
        val onApprove: () -> Unit,
        val onDecline: () -> Unit,
    ) : MwaUi
    data class SignRequest(
        val dApp: DappId,
        val receipts: List<Receipt>,   // one per payload in the bundle
        val willSend: Boolean,
        val cluster: String?,
        val onApprove: () -> Unit,
        val onDecline: () -> Unit,
    ) : MwaUi {
        val count: Int get() = receipts.size
    }
    data class MessageRequest(
        val dApp: DappId,
        val messages: List<String>,    // every message the dApp wants signed — all shown, all reviewed
        val onApprove: () -> Unit,
        val onDecline: () -> Unit,
    ) : MwaUi
    data class Working(val message: String) : MwaUi
    data class Done(val message: String, val signature: String? = null, val cluster: String? = null) : MwaUi
    data class Error(val message: String) : MwaUi
}
