package com.clearsign.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.clearsign.core.AgentIntent
import com.clearsign.core.BalanceDelta
import com.clearsign.core.Decision
import com.clearsign.core.Effects
import com.clearsign.core.IntentGuard
import com.clearsign.core.NATIVE_SOL_MINT
import com.clearsign.core.PolicyEngine
import com.clearsign.core.Receipt
import com.clearsign.core.AgentPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The decider. One job in, one verdict out.
 *
 * Simulate the bytes, check the agent's claim against what the network says
 * will happen, then run the collar. Under the rules the envelope signs here,
 * silently, and the phone tells you afterwards. Over them, the ordinary receipt
 * opens and waits for your fingerprint. Outside them, a refusal goes back with
 * the reason in plain words, so the agent can explain instead of retrying.
 */
object AgentBroker {
    private const val TAG = "Apex-Broker"
    private const val CHANNEL = "agent"
    private const val ASK_TIMEOUT_MS = 90_000L   // a blockhash lives about that long

    /**
     * One job for the judge: bytes plus a claim about them. Where it came from
     * changes nothing about how it is judged — that is the point of having one
     * chokepoint — it only changes how the answer is delivered.
     */
    data class Job(
        val id: String,
        val tx: ByteArray,
        val intentJson: String,
        val cluster: String?,
        val agent: String,
        val source: Source = Source.LINK,
    ) {
        enum class Source { LINK, IN_APP }
    }

    /**
     * The verdict, in a shape a machine can branch on.
     *
     * [rule] is the collar's stable code (`per_tx`, `destination`, `vault_touched`…).
     * [said] is what the agent claimed, [simulated] is what the network says will
     * happen: a caller can print both and let a person judge the judge.
     */
    sealed class Verdict(val code: String, val reason: String?, val signature: String?) {
        var rule: String? = null
        var said: String? = null
        var simulated: String? = null

        class SignedSilently(signature: String) : Verdict("signed_silently", null, signature)
        class Confirmed(signature: String?) : Verdict("confirmed_by_user", null, signature)
        class Refused(reason: String) : Verdict("refused", reason, null)
        class Timeout(reason: String) : Verdict("timeout", reason, null)

        fun describe(rule: String?, said: String?, simulated: String?) = apply {
            this.rule = rule; this.said = said; this.simulated = simulated
        }

        fun toJson(): org.json.JSONObject = org.json.JSONObject()
            .put("decision", code).put("rule", rule).put("reason", reason)
            .put("said", said).put("simulated", simulated).put("signature", signature)
    }

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Verdict>>()

    suspend fun handle(ctx: Context, job: Job): Verdict {
        val session = SessionWallet.current(ctx) ?: return Verdict.Refused(ctx.getString(R.string.env_none_short))
        val policy = SessionWallet.policy(ctx) ?: return Verdict.Refused(ctx.getString(R.string.env_none_short))
        val intent = parseIntent(job.intentJson) ?: return Verdict.Refused(ctx.getString(R.string.agent_bad_link))
        val locale = deviceLocaleTag()

        // The mint the proposal says is coming back. Without it the simulation
        // cannot see a coin arriving into an account that this very transaction
        // creates, and the intent check reads that as the agent lying.
        val expect = runCatching { JSONObject(job.intentJson).optString("expectMint") }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
        val analyzed = try {
            withContext(Dispatchers.IO) {
                ReceiptEngine.analyze(ctx, BlocklistScanner(ctx), job.tx, session.pubkey, job.cluster, requireSim = true, expectMints = listOfNotNull(expect))
            }
        } catch (e: Exception) {
            Log.e(TAG, "analyze failed", e)
            return Verdict.Refused(ctx.getString(R.string.agent_sim_failed, e.message ?: "?"))
        }
        val guard = IntentGuard.check(intent, analyzed.receipt, session.pubkey, locale)
        val receipt = analyzed.receipt.copy(risks = (listOf(guard) + analyzed.receipt.risks).distinctBy { it.flag to it.detail }.sortedByDescending { it.severity.ordinal })
        val prices = withContext(Dispatchers.IO) { pricer(receipt) }
        // The vault is named so the collar can refuse anything reaching for it, and
        // the writable set comes from the decoded message, not from the claim.
        val vault = Settings.watchWallet(ctx)
        val writable = SolanaTx.decode(job.tx)?.writableKeys?.toSet().orEmpty()
        val decision = PolicyEngine.decide(
            policy, receipt, guard, SessionWallet.history(ctx), prices,
            locale = locale, vault = vault, writableKeys = writable,
        )
        val what = IntentGuard.summary(intent, locale == "it")
        val effect = Effects.summary(receipt, locale)
        fun Verdict.explained() = describe(decision.code, what, effect)

        return when (decision) {
            is Decision.Refuse -> {
                Log.i(TAG, "refused: ${decision.reason}")
                record(ctx, receipt, session.pubkey, job, "refused", null, false, decision.reason)
                AgentLink.noteAction(ctx, ctx.getString(R.string.agent_last_refused, what))
                notify(ctx, ctx.getString(R.string.agent_notif_refused), decision.reason, null)
                Verdict.Refused(decision.reason).explained()
            }
            is Decision.Ask -> {
                Log.i(TAG, "asking: ${decision.reason}")
                AgentLink.noteAction(ctx, ctx.getString(R.string.agent_last_asked, what))
                ask(ctx, job, session.pubkey, decision.reason, what).explained()
            }
            Decision.Auto -> {
                val value = receipt.outflows.filter { it.rawAmount < 0 }.sumOf { prices(it) ?: 0L }
                signAndSend(ctx, job, session.pubkey, receipt, value, what).explained()
            }
        }
    }

    private suspend fun signAndSend(ctx: Context, job: Job, envelope: String, receipt: Receipt, valueLamports: Long, what: String): Verdict {
        val sig = SessionWallet.sign(ctx, SolanaTx.messageBytes(job.tx)) ?: return Verdict.Refused(ctx.getString(R.string.env_key_missing))
        val idx = SolanaTx.decode(job.tx)?.let { d -> d.staticAccountKeys.indexOf(envelope).takeIf { it in 0 until d.numRequiredSignatures } } ?: 0
        val signed = SolanaTx.attachSignature(job.tx, idx, sig)
        val out = withContext(Dispatchers.IO) { SolanaRpc.send(SolanaRpc.urlFor(job.cluster), signed) }
        val txSig = out.signature ?: return Verdict.Refused(ctx.getString(R.string.err_send, out.error ?: "?"))
        SessionWallet.recordSpend(ctx, valueLamports)
        // The book of what the agent is holding, and what it paid. Kept here and
        // not in the loop so a coin bought or sold from the chat is tracked too.
        runCatching {
            val cfg = TraderLoop.config(ctx)
            Positions.applyReceipt(ctx, receipt, envelope, cfg.takeProfitPct, cfg.stopLossPct)
        }
        record(ctx, receipt, envelope, job, "auto", txSig, true, null)
        AgentLink.noteAction(ctx, ctx.getString(R.string.agent_last_signed, what))
        notify(ctx, ctx.getString(R.string.agent_notif_signed), what, txSig)
        runCatching { HealthWidgetData.refresh(ctx) }
        return Verdict.SignedSilently(txSig)
    }

    /** Open the receipt and wait for the person. The gate reports back through [complete]. */
    private suspend fun ask(ctx: Context, job: Job, envelope: String, why: String, what: String): Verdict {
        val deferred = CompletableDeferred<Verdict>()
        pending[job.id] = deferred
        val uri = Uri.Builder().scheme("apex").authority("agent").path("/sign")
            .appendQueryParameter("tx", Base64.encodeToString(job.tx, Base64.URL_SAFE or Base64.NO_WRAP))
            .appendQueryParameter("intent", job.intentJson)
            .appendQueryParameter("account", envelope)
            .apply { job.cluster?.let { appendQueryParameter("cluster", it) } }
            .build()
        val open = Intent(ctx, AgentGateActivity::class.java).setData(uri)
            .putExtra("signer", "envelope").putExtra("job", job.id).putExtra("agent", job.agent).putExtra("why", why)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (job.source == Job.Source.LINK) {
            // From a background service starting an activity may be blocked, so the
            // notification is the path that always works. In-app we are already in front.
            val pi = PendingIntent.getActivity(ctx, job.id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            notify(ctx, ctx.getString(R.string.agent_notif_ask, what), why, null, pi, heads = true)
        }
        runCatching { ctx.startActivity(open) }
        val v = withTimeoutOrNull(ASK_TIMEOUT_MS) { deferred.await() }
        pending.remove(job.id)
        if (v == null) {
            cancelNotification(ctx, job.id)
            AgentLink.noteAction(ctx, ctx.getString(R.string.agent_last_expired, what))
            return Verdict.Timeout(ctx.getString(R.string.agent_ask_timeout))
        }
        return v
    }

    fun complete(jobId: String, verdict: Verdict) { pending.remove(jobId)?.complete(verdict) }
    fun isPending(jobId: String) = pending.containsKey(jobId)

    // ---- helpers -------------------------------------------------------------

    /** Price every leg in SOL-equivalent lamports; null when unknown, which is never signed silently. */
    private fun pricer(receipt: Receipt): (BalanceDelta) -> Long? {
        val mints = (receipt.outflows + receipt.inflows).map { it.mint }.toSet() + NATIVE_SOL_MINT
        val q = runCatching { Prices.quotes(mints) }.getOrDefault(emptyMap())
        val solUsd = q[NATIVE_SOL_MINT]?.usd
        return { d ->
            when {
                d.mint == NATIVE_SOL_MINT || d.mint == AgentPolicy.WSOL -> kotlin.math.abs(d.rawAmount)
                solUsd == null || solUsd <= 0.0 -> null
                else -> q[d.mint]?.usd?.let { usd -> (kotlin.math.abs(d.uiAmount) * usd / solUsd * 1e9).toLong() }
            }
        }
    }

    fun parseIntent(json: String): AgentIntent? = runCatching {
        val o = JSONObject(json)
        AgentIntent(
            action = o.optString("action", "other").ifBlank { "other" },
            outMint = o.optString("outMint").takeIf { it.isNotBlank() }, outAmount = o.optDouble("outAmount").takeIf { !it.isNaN() },
            inMint = o.optString("inMint").takeIf { it.isNotBlank() }, inAmount = o.optDouble("inAmount").takeIf { !it.isNaN() },
            to = o.optString("to").takeIf { it.isNotBlank() }, agent = o.optString("agent").takeIf { it.isNotBlank() },
            reason = o.optString("reason").takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    /** Every decision is a ledger row: host says how it went (auto / asked / refused). */
    fun record(ctx: Context, r: Receipt, envelope: String, job: Job, how: String, txSig: String?, sent: Boolean, note: String?) {
        val at = System.currentTimeMillis()
        val entry = LedgerRecorder.fromReceipt(
            at = at, kind = "agent", dApp = job.agent, host = how, pkg = "link", cluster = job.cluster, wallet = envelope,
            r = r, signature = txSig, sent = sent, txIndex = 0, txCount = 1, groupId = LedgerRecorder.newId(),
            attestation = null, attestationSig = null,
        )
        LedgerRecorder.record(ctx, if (note != null) entry.copy(note = note) else entry)
    }

    fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.agent_channel), NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun notify(ctx: Context, title: String, body: String, txSig: String?, tap: PendingIntent? = null, heads: Boolean = false) {
        channel(ctx)
        val open = tap ?: PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).putExtra("open", "agent").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open).setAutoCancel(true)
            .apply { if (heads) setCategory(Notification.CATEGORY_CALL) }
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(if (tap != null) 5000 else (txSig?.hashCode() ?: body.hashCode()), n)
    }

    private fun cancelNotification(ctx: Context, jobId: String) {
        ctx.getSystemService(NotificationManager::class.java).cancel(5000)
    }
}
