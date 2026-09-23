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
 * The decider: one job in, one verdict out. Simulate the bytes, check the agent's claim
 * against what the network says will happen, run the collar. Under the rules the budget
 * signs here, silently, and tells you after; over them the ordinary receipt waits for your
 * fingerprint; outside them a refusal goes back with the reason in plain words.
 */
object AgentBroker {
    private const val TAG = "Apex-Broker"
    private const val CHANNEL = "agent"
    private const val QUIET = "agent_quiet"
    private const val PROGRESS_ID = 5100
    const val MILESTONE_ID = 5200
    private const val ASK_TIMEOUT_MS = 90_000L   // a blockhash lives about that long

    /**
     * One job for the judge: bytes plus a claim about them. Where it came from changes only how
     * the answer is delivered, never how it is judged.
     */
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
     * The verdict, in a shape a machine can branch on. [rule] is the collar's stable code
     * (`per_tx`, `destination`, `vault_touched`…); [said] is the agent's claim and [simulated]
     * what the network says, so a caller can print both and let a person judge the judge.
     */
    sealed class Verdict(val code: String, val reason: String?, val signature: String?) {
        var rule: String? = null
        var said: String? = null
        var simulated: String? = null

        class SignedSilently(signature: String) : Verdict("signed_silently", null, signature)
        class Confirmed(signature: String?) : Verdict("confirmed_by_user", null, signature)
        /**
         * [by] says who said no, because three things mean opposite things. The collar is a bug
         * report about the proposal. A person is an answer, and asking again in six minutes makes a
         * nuisance. Everything else is the system (a failed send, an unreadable key, an expired
         * blockhash): worth a retry, never a stop. All three used to read "you turned it down".
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
        // Who chose the route. Only bytes we asked Jupiter for, with this budget as taker, carry an
        // Ultra requestId: no outside link does, and the collar uses it to recognize an exchange by
        // shape rather than by program name, since Ultra changes route every call. See isExchange in AgentPolicy.
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
                record(ctx, receipt, session.pubkey, job, "refused", null, false, decision.reason)
                AgentLink.noteAction(ctx, ctx.getString(R.string.agent_last_refused, what))
                notify(ctx, ctx.getString(R.string.agent_notif_refused), decision.reason, null)
                Verdict.Refused(decision.reason, Verdict.Refused.By.COLLAR).explained()
            }
            is Decision.Ask -> {
                Log.i(TAG, "asking: ${decision.reason}")
                AgentLink.noteAction(ctx, ctx.getString(R.string.agent_last_asked, what))
                val v = ask(ctx, job, session.pubkey, decision.reason, what)
                // A question nobody answered used to leave no trace at all: the
                // refusals were in the Receipts tab and the expiries were nowhere,
                // so a loop stuck on a timeout looked like a loop doing nothing.
                if (v is Verdict.Timeout) record(ctx, receipt, session.pubkey, job, "expired", null, false, v.reason)
                v.explained()
            }
            Decision.Auto -> {
                // What the budget actually loses, which is what the rolling caps count. A sale loses
                // nothing, the coin becomes SOL in the same pocket; counting it burned the daily cap twice
                // per round trip. A leg nobody can price used to count as zero, the one certainly wrong
                // answer: the collar already refused anything above the per-move ceiling, so that ceiling
                // is the honest worst case.
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
            // No answer is not no transaction. A read timeout after the node forwarded the bytes came
            // back as "refused", and the coin the budget now held had no row: no target, no stop,
            // nothing counted against the day. The signature is in the bytes, so the chain can be asked.
            out.signature ?: run {
                val own = SolanaTx.firstSignature(signed)
                val landed = own != null && withContext(Dispatchers.IO) { SolanaRpc.confirmed(rpc, own) }
                if (!landed) return Verdict.Refused(ctx.getString(R.string.err_send, out.error ?: "?"))
                own
            }
        }
        // A round trip that comes home gives the day back what it returns: lamports
        // with a minus sign, and still a row, because it is still a move. See
        // staysInPocket and recordSpend.
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
        // "Spent from the budget" was false on a sale, half the cases: a sale spends nothing, it
        // brings money back into the same pocket. The same notice said it under a sale the person
        // had asked for, finger on the button.
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
    fun record(ctx: Context, r: Receipt, envelope: String, job: Job, how: String, txSig: String?, sent: Boolean, note: String?) {
        val at = System.currentTimeMillis()
        val entry = LedgerRecorder.fromReceipt(
            at = at, kind = "agent", dApp = job.agent, host = how, pkg = "link", cluster = job.cluster, wallet = envelope,
            r = r, signature = txSig, sent = sent, txIndex = 0, txCount = 1, groupId = LedgerRecorder.newId(),
            attestation = null, attestationSig = null,
        )
        LedgerRecorder.record(ctx, if (note != null) entry.copy(note = note) else entry)
    }

    /**
     * Say something went wrong, once, where a person will see it. For the loop, running with the
     * app closed: a note nobody reads is no note, and a stuck agent must be loud exactly once
     * rather than quiet eighty-four times.
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

    /** The quiet card: one notification rewritten in place, never a sound. The loop's pulse for somebody glancing at the shade. */
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
