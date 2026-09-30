package com.clearsign.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.compose.ui.graphics.toArgb
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
 * One job in, one verdict out: simulate the bytes, check the agent's claim against the
 * simulation, run the collar. Within the rules the budget signs silently and notifies after;
 * above them the receipt waits for a fingerprint; outside them it is refused with the reason.
 */
object AgentBroker {
    private const val TAG = "Apex-Broker"
    private const val CHANNEL = "agent"
    private const val QUIET = "agent_quiet"
    private const val PROGRESS_ID = 5100
    const val MILESTONE_ID = 5200
    private const val ASK_TIMEOUT_MS = 90_000L   // a blockhash lives about that long

    /** Bytes plus a claim about them. The source changes only how the answer is delivered. */
    data class Job(
        val id: String,
        val tx: ByteArray,
        val intentJson: String,
        val cluster: String?,
        val agent: String,
        val source: Source = Source.LINK,
        /** Set when the bytes came from Jupiter Ultra: Jupiter lands them, not our RPC. */
        val ultraRequestId: String? = null,
    ) {
        enum class Source { LINK, IN_APP }
    }

    /**
     * Machine-readable verdict. [rule] is the collar's stable code (`per_tx`, `destination`,
     * `vault_touched`…); [said] is the agent's claim and [simulated] the network's, so a caller
     * can show both.
     */
    sealed class Verdict(val code: String, val reason: String?, val signature: String?) {
        var rule: String? = null
        var said: String? = null
        var simulated: String? = null

        class SignedSilently(signature: String) : Verdict("signed_silently", null, signature)
        class Confirmed(signature: String?) : Verdict("confirmed_by_user", null, signature)
        /**
         * [by] says who refused. COLLAR: the proposal is at fault. PERSON: an answer, don't ask again
         * soon. SYSTEM (failed send, unreadable key, expired blockhash): worth a retry, never a stop.
         */
        class Refused(reason: String, val by: By = By.SYSTEM) : Verdict("refused", reason, null) {
            enum class By { PERSON, COLLAR, SYSTEM }
            val byCollar: Boolean get() = by == By.COLLAR
        }
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

        // The mint the proposal expects back. Without it the simulation can't see a coin landing
        // in an account this same transaction creates, and the intent check flags a mismatch.
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
        // Only bytes we requested from Jupiter, with this budget as taker, carry an Ultra requestId.
        // The collar then recognizes an exchange by shape, not program name, since Ultra's route
        // changes every call. See isExchange in AgentPolicy.
        val routeIsOurs = job.ultraRequestId != null
        val decision = PolicyEngine.decide(
            policy, receipt, guard, SessionWallet.history(ctx), prices,
            locale = locale, vault = vault, writableKeys = writable, routeIsOurs = routeIsOurs,
        )
        val what = IntentGuard.summary(intent, locale == "it")
        val effect = Effects.summary(receipt, locale)
        fun Verdict.explained() = describe(decision.code, what, effect)

        return when (decision) {
            is Decision.Refuse -> {
                Log.i(TAG, "refused: ${decision.reason}")
                record(ctx, receipt, session.pubkey, job, "refused", null, false, decision.reason, decision.text)
                AgentLink.noteAction(ctx, ctx.getString(R.string.agent_last_refused, what))
                notify(ctx, ctx.getString(R.string.agent_notif_refused), decision.reason, null)
                Verdict.Refused(decision.reason, Verdict.Refused.By.COLLAR).explained()
            }
            is Decision.Ask -> {
                Log.i(TAG, "asking: ${decision.reason}")
                AgentLink.noteAction(ctx, ctx.getString(R.string.agent_last_asked, what))
                val v = ask(ctx, job, session.pubkey, decision.reason, what)
                // Record expiries too, or a loop stuck on timeouts looks idle.
                if (v is Verdict.Timeout) record(ctx, receipt, session.pubkey, job, "expired", null, false, v.reason)
                v.explained()
            }
            Decision.Auto -> {
                // Count what the budget actually loses, as the rolling caps do. A sale loses nothing, or a
                // round trip would burn the daily cap twice. An unpriced leg counts as the per-move cap,
                // the worst case: the collar already refused anything above it.
                val legs = receipt.outflows.filter { it.rawAmount < 0 }.map { prices(it) }
                val value = when {
                    PolicyEngine.isUnwind(policy, receipt, routeIsOurs) -> 0L
                    legs.any { it == null } -> policy.perTxLamports
                    else -> legs.sumOf { it ?: 0L }
                }
                signAndSend(ctx, job, session.pubkey, receipt, value, what, routeIsOurs).explained()
            }
        }
    }

    private suspend fun signAndSend(ctx: Context, job: Job, envelope: String, receipt: Receipt, valueLamports: Long, what: String, routeIsOurs: Boolean): Verdict {
        val sig = SessionWallet.sign(ctx, SolanaTx.messageBytes(job.tx)) ?: return Verdict.Refused(ctx.getString(R.string.env_key_missing))
        val idx = SolanaTx.decode(job.tx)?.let { d -> d.staticAccountKeys.indexOf(envelope).takeIf { it in 0 until d.numRequiredSignatures } } ?: 0
        val signed = SolanaTx.attachSignature(job.tx, idx, sig)
        val txSig = if (job.ultraRequestId != null) {
            val ex = withContext(Dispatchers.IO) { JupiterUltra.execute(signed, job.ultraRequestId) }
            ex.signature?.takeIf { ex.error == null } ?: return Verdict.Refused(ctx.getString(R.string.err_send, ex.error ?: ex.status))
        } else {
            val rpc = SolanaRpc.urlFor(job.cluster)
            val out = withContext(Dispatchers.IO) { SolanaRpc.send(rpc, signed) }
            // No answer doesn't mean no transaction: a read timeout can follow a forwarded send, leaving
            // a held coin with no position row. The signature is in the bytes, so ask the chain.
            out.signature ?: run {
                val own = SolanaTx.firstSignature(signed)
                val landed = own != null && withContext(Dispatchers.IO) { SolanaRpc.confirmed(rpc, own) }
                if (!landed) return Verdict.Refused(ctx.getString(R.string.err_send, out.error ?: "?"))
                own
            }
        }
        // A sale back into SOL is recorded as negative spend, still as a row since it is a move.
        // See staysInPocket and recordSpend.
        val pol = SessionWallet.policy(ctx)
        val homeAgain = pol != null && com.clearsign.core.staysInPocket(receipt, pol, routeIsOurs)
        SessionWallet.recordSpend(ctx, if (homeAgain) -valueLamports else valueLamports)
        // The book of what the agent is holding, and what it paid. Kept here and
        // not in the loop so a coin bought or sold from the chat is tracked too.
        runCatching {
            val cfg = TraderLoop.config(ctx)
            Positions.applyReceipt(ctx, receipt, envelope, cfg.takeProfitPct, cfg.stopLossPct)
        }
        record(ctx, receipt, envelope, job, "auto", txSig, true, null)
        AgentLink.noteAction(ctx, ctx.getString(R.string.agent_last_signed, what))
        // A sale spends nothing, so it gets its own notice instead of "spent from the budget".
        notify(ctx, ctx.getString(if (homeAgain) R.string.agent_notif_sold else R.string.agent_notif_signed), what, txSig)
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
            // Who lands the bytes if the person says yes: an Ultra route is sent by
            // Jupiter, not our RPC. See AgentGateActivity.
            .putExtra("ultra", job.ultraRequestId)
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
    fun record(ctx: Context, r: Receipt, envelope: String, job: Job, how: String, txSig: String?, sent: Boolean, note: String?, why: com.clearsign.core.Refusals.Text? = null) {
        val at = System.currentTimeMillis()
        val entry = LedgerRecorder.fromReceipt(
            at = at, kind = "agent", dApp = job.agent, host = how, pkg = "link", cluster = job.cluster, wallet = envelope,
            r = r, signature = txSig, sent = sent, txIndex = 0, txCount = 1, groupId = LedgerRecorder.newId(),
            attestation = null, attestationSig = null,
        )
        LedgerRecorder.record(ctx, if (note != null) entry.copy(note = note, why = why) else entry)
    }

    /**
     * Report a problem once, where a person will see it. For the loop running with the app
     * closed: a stuck agent should alert once, not fail silently over and over.
     */
    fun warn(
        ctx: Context, title: String, body: String, picture: android.graphics.Bitmap? = null, color: Int? = null,
        rhythm: Rhythm? = null, actions: List<Notification.Action> = emptyList(), id: Int? = null, largeIcon: android.graphics.Bitmap? = null,
        /** Opens the crowd feed with this coin already unfolded, instead of the agent page. */
        openMint: String? = null,
    ) = notify(
        ctx, title, body, null, picture = picture, color = color, rhythm = rhythm, actions = actions, id = id,
        largeIcon = largeIcon, openMint = openMint,
    )

    fun dismiss(ctx: Context, id: Int) = ctx.getSystemService(NotificationManager::class.java).cancel(id)

    /**
     * A vibration you can tell apart in a pocket. Android lets a channel own the pattern, not a
     * notification, so each rhythm is its own channel: two short taps for a coin going up, one
     * long for down, three for a sale or a stop.
     */
    enum class Rhythm(val channel: String, val nameRes: Int, val pattern: LongArray) {
        UP("agent_up", R.string.agent_channel_up, longArrayOf(0, 60, 90, 60)),
        DOWN("agent_down", R.string.agent_channel_down, longArrayOf(0, 420)),
        STOP("agent_stop", R.string.agent_channel_stop, longArrayOf(0, 110, 100, 110, 100, 110)),
    }

    private fun channelFor(ctx: Context, r: Rhythm): String {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(r.channel) == null) {
            nm.createNotificationChannel(
                NotificationChannel(r.channel, ctx.getString(r.nameRes), NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true); vibrationPattern = r.pattern
                },
            )
        }
        return r.channel
    }

    /** Silent progress notification, updated in place: the loop's status at a glance. */
    fun progress(ctx: Context, title: String, body: String, picture: android.graphics.Bitmap? = null) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(QUIET) == null) {
            nm.createNotificationChannel(NotificationChannel(QUIET, ctx.getString(R.string.agent_channel_quiet), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).putExtra("open", "agent").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, QUIET)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title).setContentText(body)
            .setColor(Halo.palette.accent.toArgb())
            .setStyle(
                if (picture != null) Notification.BigPictureStyle().bigPicture(picture).setSummaryText(body)
                else Notification.BigTextStyle().bigText(body),
            )
            .setContentIntent(open).setOnlyAlertOnce(true).setAutoCancel(false)
            .build()
        nm.notify(PROGRESS_ID, n)
    }

    fun progressClear(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(PROGRESS_ID)
    }

    fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.agent_channel), NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun notify(
        ctx: Context, title: String, body: String, txSig: String?, tap: PendingIntent? = null, heads: Boolean = false,
        picture: android.graphics.Bitmap? = null, color: Int? = null,
        rhythm: Rhythm? = null, actions: List<Notification.Action> = emptyList(), id: Int? = null, largeIcon: android.graphics.Bitmap? = null,
        openMint: String? = null,
    ) {
        channel(ctx)
        val open = tap ?: PendingIntent.getActivity(
            ctx, openMint?.hashCode() ?: 0,
            Intent(ctx, MainActivity::class.java)
                .putExtra("open", if (openMint != null) "crowd" else "agent")
                .apply { openMint?.let { putExtra("mint", it) } }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, rhythm?.let { channelFor(ctx, it) } ?: CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title).setContentText(body)
            .setStyle(
                if (picture != null) Notification.BigPictureStyle().bigPicture(picture).setSummaryText(body)
                else Notification.BigTextStyle().bigText(body),
            )
            .setColor(color ?: Halo.palette.accent.toArgb())
            .setContentIntent(open).setAutoCancel(true)
            .apply {
                if (heads) setCategory(Notification.CATEGORY_CALL)
                largeIcon?.let { setLargeIcon(it) }
                actions.forEach { addAction(it) }
            }
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id ?: if (tap != null) 5000 else (txSig?.hashCode() ?: body.hashCode()), n)
    }

    private fun cancelNotification(ctx: Context, jobId: String) {
        ctx.getSystemService(NotificationManager::class.java).cancel(5000)
    }
}
