package com.clearsign.app

import android.content.Context
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import com.clearsign.core.Spare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The spare change of every swap, counted until it is worth one swap into stORE. Only a
 * number: the SOL stays in the wallet until you press the button and read the receipt.
 */
object SpareJar {
    private const val PREFS = "spare_jar"
    /**
     * Left in the wallet by the spare swap: the fee, the wrapped SOL account Jupiter opens for the
     * trade (~0.00204) and, the first time, your own stORE account's rent (~0.0015).
     */
    const val RESERVE = 6_000_000L

    val on = mutableStateOf(false)
    val jar = mutableLongStateOf(0L)
    val moved = mutableLongStateOf(0L)
    /** A spare swap sent by the old route and not yet confirmed: not offered a second time meanwhile. */
    val pending = mutableLongStateOf(0L)

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        on.value = p.getBoolean("on", false)
        jar.longValue = p.getLong("jar", 0L)
        moved.longValue = p.getLong("moved", 0L)
    }

    fun setOn(ctx: Context, v: Boolean) {
        on.value = v
        save(ctx)
    }

    /** A swap went out: with SOL on one side and the jar on, its round-up is counted. */
    fun count(ctx: Context, q: Jupiter.Quote) {
        if (!on.value) return
        val sol = when (Jupiter.SOL_MINT) {
            q.inMint -> q.inAmount
            q.outMint -> q.outAmount
            else -> return
        }
        val add = Spare.roundUp(sol)
        if (add == 0L) return
        jar.longValue += add
        save(ctx)
    }

    /** The spare swap went out: what it spent leaves the jar, and never more than the jar held. */
    fun taken(ctx: Context, lamports: Long) {
        val t = minOf(lamports.coerceAtLeast(0), jar.longValue)
        jar.longValue -= t
        moved.longValue += t
        save(ctx)
    }

    /**
     * Runs [book] once the swap is on chain. Ultra returns only when it landed, so at once; the old
     * route's "sent" means a node took it, so its signature is confirmed first, off the screen.
     */
    fun whenLanded(ctx: Context, signature: String, landed: Boolean, spare: Long = 0L, book: (Context) -> Unit) {
        val app = ctx.applicationContext
        if (landed) { book(app); return }
        // Held aside while it confirms, so the jar is not offered again in the meantime; waited on
        // for a blockhash's lifetime, so a swap the node reports late still empties the jar.
        pending.longValue += spare
        AppScope.launch {
            val ok = SolanaRpc.confirmed(SolanaRpc.urlFor(null), signature, timeoutMs = if (spare > 0) 90_000L else 30_000L)
            withContext(Dispatchers.Main) {
                if (ok) book(app)
                pending.longValue = (pending.longValue - spare).coerceAtLeast(0)
            }
        }
    }

    /** What is in the jar and not on its way out already. */
    fun free(): Long = (jar.longValue - pending.longValue).coerceAtLeast(0)

    fun ready(): Boolean = on.value && free() >= Spare.MOVE_AT

    private fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("on", on.value).putLong("jar", jar.longValue).putLong("moved", moved.longValue).apply()
    }
}
