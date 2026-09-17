package com.clearsign.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.clearsign.core.AgentIntent
import com.clearsign.core.IntentGuard
import com.clearsign.core.Receipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The Agent Gate: the hardware co-signer for AI agents.
 *
 * An agent — running anywhere: another app on the phone, a script on a laptop
 * that opens a link, a browser — never holds a key. It hands Omni a transaction
 * plus a *declared intent* ("swap 0.1 SOL → USDC for rebalancing") via
 *
 *   apex://agent/sign?tx=<base64>&intent=<json>[&account=<pubkey>][&cluster=…][&callback=<uri>][&send=0|1]
 *
 * Apex simulates the real bytes, then [IntentGuard] checks the claim against the
 * simulated effect: any undeclared outflow, wrong amount, wrong recipient or
 * smuggled approval becomes a DANGER risk that blocks approval. What matches is
 * shown as a verified intent. Either way the user still holds-to-sign with
 * biometrics and the key never leaves the Seed Vault. The result (signature, or
 * the signed transaction when send=0) goes back through the activity result and,
 * if given, the callback URI.
 */
class AgentGateActivity : ComponentActivity() {
    private lateinit var bridge: ActivityResultBridge
    private lateinit var signer: SeedVaultSigner
    private val scanner by lazy { BlocklistScanner(this) }
    private var ui by mutableStateOf<MwaUi>(MwaUi.Preparing)

    private var callback: Uri? = null
    /** Set when the collar asked for a person: the broker is waiting on this job id. */
    private var envelopeJob: String? = null

    override fun onDestroy() {
        super.onDestroy()
        // Closing the receipt without deciding is a "no": never leave the agent hanging.
        envelopeJob?.let { AgentBroker.complete(it, AgentBroker.Verdict.Refused(getString(R.string.agent_declined), AgentBroker.Verdict.Refused.By.PERSON)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Themes.load(this); Settings.load(this); Pro.load(this)
        bridge = ActivityResultBridge(this)
        signer = SeedVaultSigner(this, bridge)
        enableEdgeToEdge()
        setContent { ScaledText { MwaScreen(ui) } }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent); handle(intent)
    }

    private fun handle(intent: Intent?) {
        val data = intent?.data
        val txB64 = data?.getQueryParameter("tx")
        val intentJson = data?.getQueryParameter("intent")
        callback = data?.getQueryParameter("callback")?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val send = data?.getQueryParameter("send") != "0"
        val cluster = data?.getQueryParameter("cluster")
        val tx = txB64?.let { b -> runCatching { Base64.decode(b, Base64.URL_SAFE or Base64.NO_WRAP) }.getOrNull() ?: runCatching { Base64.decode(b, Base64.DEFAULT) }.getOrNull() }
        val agentIntent = intentJson?.let { parseIntent(it) }
        if (tx == null || agentIntent == null) { fail(getString(R.string.agent_bad_link)); return }
        // Two pockets. "envelope": the agent's capped key, which this app holds and
        // signs with after the collar said "ask" — presence is proved with the
        // device biometrics. Otherwise: the Seed Vault account, as always.
        // The envelope branch signs with the budget key after the collar has
        // already decided, so the only thing allowed onto it is a job the collar
        // itself is waiting on. Without that check any installed app could send
        // `apex://agent/sign` with `signer=envelope` and a transaction of its
        // own, and reach the budget key with no cap, no allowed destination and
        // no rate limit between it and the money. The activity is exported: the
        // extras are whatever the caller typed.
        val askedEnvelope = intent?.getStringExtra("signer") == "envelope"
        envelopeJob = intent?.getStringExtra("job")?.takeIf { askedEnvelope && AgentBroker.isPending(it) }
        // Asked for the budget key and the collar knows nothing about it: refuse
        // outright rather than quietly falling back to the Seed Vault, so the
        // caller cannot use this screen to get a signature it did not ask for.
        if (askedEnvelope && envelopeJob == null) { fail(getString(R.string.agent_bad_link)); return }
        val envelope = envelopeJob?.let { SessionWallet.current(this)?.pubkey }
        if (envelopeJob != null && envelope == null) { fail(getString(R.string.env_none_short)); return }
        val owner = envelope ?: data.getQueryParameter("account")?.takeIf { it.isNotBlank() } ?: Settings.watchWallet(this)
        if (owner == null) { fail(getString(R.string.agent_no_wallet)); return }

        // Two different things, never conflated: what we can verify, and what the
        // request claims about itself. A malicious app can call itself "Claude";
        // it cannot fake the package Android reports, nor how the link arrived.
        val callerPkg = referrer?.takeIf { it.scheme == "android-app" }?.host
        val viaQr = intent?.getStringExtra("via") == "qr"
        val origin = when {
            envelopeJob != null -> getString(R.string.agent_origin_link_env, intent?.getStringExtra("agent") ?: "agent")
            viaQr -> getString(R.string.agent_origin_qr)
            callerPkg != null -> getString(R.string.agent_origin_app, callerPkg)
            else -> getString(R.string.agent_origin_link)
        }
        val claimed = (if (envelopeJob != null) intent?.getStringExtra("agent") else null) ?: agentIntent.agent?.takeIf { it.isNotBlank() }
        val dApp = DappId(
            name = claimed ?: callerPkg ?: getString(R.string.agent_unknown),
            host = null, iconUrl = null,
            store = StoreIntel.of(this, callerPkg),
            origin = origin, nameIsClaimed = claimed != null,
        )

        ui = MwaUi.Working(getString(R.string.w_analyzing))
        lifecycleScope.launch {
            val analyzed = try {
                withContext(Dispatchers.IO) {
                    // The same expected mint the broker uses. Without it the
                    // simulation cannot see a coin arriving into an account that
                    // this very transaction creates, and the intent check calls
                    // the agent a liar for a purchase that is perfectly honest.
                    val expect = intentJson?.let { runCatching { org.json.JSONObject(it).optString("expectMint") }.getOrNull() }
                        ?.takeIf { it.isNotEmpty() }
                    ReceiptEngine.analyze(this@AgentGateActivity, scanner, tx, owner, cluster, requireSim = true, expectMints = listOfNotNull(expect))
                }
            } catch (e: Exception) { Log.e(TAG, "analyze failed", e); fail(e.message ?: getString(R.string.err_sign_cancelled)); return@launch }

            // The gate itself: claim vs. simulated effect.
            var guard = IntentGuard.check(agentIntent, analyzed.receipt, owner, deviceLocaleTag())
            agentIntent.reason?.takeIf { it.isNotBlank() }?.let { r -> guard = guard.copy(detail = guard.detail + "  " + getString(R.string.agent_reason, r)) }
            val askedWhy = intent?.getStringExtra("why")?.takeIf { envelopeJob != null && it.isNotBlank() }
            val receipt: Receipt = analyzed.receipt.copy(risks = (listOf(guard) + analyzed.receipt.risks).distinctBy { it.flag to it.detail }.sortedByDescending { it.severity.ordinal })
            val item = analyzed.copy(receipt = receipt)

            ui = MwaUi.SignRequest(
                dApp = dApp, receipts = listOf(receipt), willSend = send, cluster = cluster, askedWhy = askedWhy,
                onApprove = {
                    ui = MwaUi.Working(getString(R.string.w_drift))
                    lifecycleScope.launch {
                        try {
                            ReceiptEngine.driftGuard(listOf(item), owner, cluster)?.let { drift ->
                                ui = MwaUi.Error(getString(R.string.err_blocked, drift.detail)); deliverError(drift.detail); return@launch
                            }
                            ui = MwaUi.Working(getString(R.string.w_signing))
                            val sig = if (envelopeJob != null) {
                                if (!Presence.confirm(this@AgentGateActivity, getString(R.string.agent_bio_title), getString(R.string.agent_bio_sub))) {
                                    ui = MwaUi.Error(getString(R.string.err_sign_cancelled)); deliverError("cancelled"); return@launch
                                }
                                SessionWallet.sign(this@AgentGateActivity, SolanaTx.messageBytes(tx)) ?: run {
                                    ui = MwaUi.Error(getString(R.string.env_key_missing)); deliverError("no envelope"); return@launch
                                }
                            } else {
                                signer.ensureAccount(owner)
                                signer.signSuspend(tx)
                            }
                            val idx = SolanaTx.decode(tx)?.let { d -> d.staticAccountKeys.indexOf(owner).takeIf { i -> i in 0 until d.numRequiredSignatures } } ?: 0
                            val signed = SolanaTx.attachSignature(tx, idx, sig)
                            var txSig: String? = null
                            if (send) {
                                ui = MwaUi.Working(getString(R.string.w_sending))
                                val out = withContext(Dispatchers.IO) { SolanaRpc.send(SolanaRpc.urlFor(cluster), signed) }
                                txSig = out.signature ?: run { ui = MwaUi.Error(getString(R.string.err_send, out.error ?: "?")); deliverError(out.error ?: "send failed"); return@launch }
                            }
                            record(receipt, owner, dApp.name, callerPkg, cluster, tx, txSig, send, how = if (envelopeJob != null) "asked" else null)
                            val signedB64 = Base64.encodeToString(signed, Base64.NO_WRAP)
                            envelopeJob?.let { j ->
                                // A move the collar stopped and a person waved
                                // through is still a move the budget paid for. It
                                // was never written to the spend log, and the daily
                                // cap and the hourly limit are computed from that
                                // log alone, so anything that went through "ask"
                                // was invisible to both of them. The exact price is
                                // the broker's business; here the honest bound is
                                // the ceiling the collar allows for one move.
                                runCatching {
                                    val out = receipt.outflows.filter { it.rawAmount < 0 }
                                    val allSol = out.isNotEmpty() && out.all { it.mint == com.clearsign.core.NATIVE_SOL_MINT }
                                    val spent = if (allSol) out.sumOf { kotlin.math.abs(it.rawAmount) }
                                    else SessionWallet.policy(this@AgentGateActivity)?.perTxLamports ?: 0L
                                    val pol = SessionWallet.policy(this@AgentGateActivity)
                                    val homeAgain = pol != null && com.clearsign.core.staysInPocket(receipt, pol)
                                    SessionWallet.recordSpend(this@AgentGateActivity, if (homeAgain) 0L else spent)
                                }
                                AgentBroker.complete(j, AgentBroker.Verdict.Confirmed(txSig))
                                AgentLink.noteAction(this@AgentGateActivity, getString(R.string.agent_last_confirmed, IntentGuard.summary(agentIntent, deviceLocaleTag() == "it")))
                                envelopeJob = null
                            }
                            deliverOk(txSig, signedB64)
                            ui = MwaUi.Done(
                                if (send) getString(R.string.agent_done_sent) else getString(R.string.agent_done_signed),
                                signature = txSig, cluster = cluster,
                                // Sign-only: the agent lives on another machine, so hand the
                                // signed bytes back through the screen.
                                signedTx = if (send) null else signedB64,
                            )
                            delay(2500); finishAndRemoveTask()
                        } catch (e: Exception) {
                            Log.e(TAG, "agent sign failed", e)
                            ui = MwaUi.Error(e.message ?: getString(R.string.err_sign_cancelled)); deliverError(e.message ?: "cancelled")
                        }
                    }
                },
                onDecline = {
                    envelopeJob?.let { j -> AgentBroker.complete(j, AgentBroker.Verdict.Refused(getString(R.string.agent_declined), AgentBroker.Verdict.Refused.By.PERSON)); envelopeJob = null }
                    deliverError("declined"); finishAndRemoveTask()
                },
            )
        }
    }

    /** {"action":"swap","outMint":"SOL","outAmount":0.1,"inMint":"USDC","inAmount":10,"to":"…","agent":"…","reason":"…"} */
    private fun parseIntent(json: String): AgentIntent? = runCatching {
        val o = JSONObject(json)
        AgentIntent(
            action = o.optString("action", "other").ifBlank { "other" },
            outMint = o.optString("outMint").takeIf { it.isNotBlank() }, outAmount = o.optDouble("outAmount").takeIf { !it.isNaN() },
            inMint = o.optString("inMint").takeIf { it.isNotBlank() }, inAmount = o.optDouble("inAmount").takeIf { !it.isNaN() },
            to = o.optString("to").takeIf { it.isNotBlank() }, agent = o.optString("agent").takeIf { it.isNotBlank() },
            reason = o.optString("reason").takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    private fun record(r: Receipt, owner: String, agent: String, pkg: String?, cluster: String?, tx: ByteArray, txSig: String?, sent: Boolean, how: String? = null) {
        val at = System.currentTimeMillis()
        val statement = Attestation.statement(
            at, agent, null, cluster, listOf(Attestation.sha256Hex(tx)),
            r.outflows.map { "−" + it.symbol }, r.inflows.map { "+" + it.symbol }, r.risks.map { it.flag.name }.distinct(), owner, txSig,
        )
        val attSig = Attestation.sign(statement)
        LedgerRecorder.record(
            this,
            LedgerRecorder.fromReceipt(
                at = at, kind = "agent", dApp = agent, host = how, pkg = pkg, cluster = cluster, wallet = owner,
                r = r, signature = txSig, sent = sent, txIndex = 0, txCount = 1, groupId = LedgerRecorder.newId(),
                attestation = if (attSig != null) statement else null, attestationSig = attSig,
            ),
        )
    }

    private fun deliverOk(txSig: String?, signedB64: String) {
        setResult(Activity.RESULT_OK, Intent().putExtra("signature", txSig).putExtra("signed_tx", signedB64))
        callback?.let { cb ->
            val u = cb.buildUpon().apply { txSig?.let { appendQueryParameter("signature", it) }; appendQueryParameter("signed_tx", signedB64) }.build()
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun deliverError(msg: String) {
        setResult(Activity.RESULT_CANCELED, Intent().putExtra("error", msg))
        callback?.let { cb -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, cb.buildUpon().appendQueryParameter("error", msg).build()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    private fun fail(msg: String) {
        ui = MwaUi.Error(msg); deliverError(msg)
        envelopeJob?.let { j -> AgentBroker.complete(j, AgentBroker.Verdict.Refused(msg)); envelopeJob = null }
    }

    companion object { private const val TAG = "ClearSign-Agent" }
}
