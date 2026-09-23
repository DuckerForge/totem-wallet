package com.clearsign.app

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

/**
 * What the agent is doing right now, in its own words. The screen said "trading" or "watching
 * the market", neither telling you whether the thing is alive, stuck, or refusing a coin for a
 * reason you wanted to know: a loop that works invisibly looks like one that died, which cost
 * sixteen hours one morning. So the loop narrates one short line per step, and the chat renders
 * the last few as a console. In memory only: a window on a running process, the ledger is the record.
 */
object AgentTrace {
    private const val MAX = 60

    /**
     * What kind of line. `REFUSED` is red with the x, rightly: something was stopped. But "could
     * not place the order on Jupiter" landed there too, and it is neither a refusal nor a danger:
     * read in red right after a buy it looked like the coin had exploded. `WARN` is amber with
     * the exclamation mark: look, know it, nothing went wrong.
     */
    enum class Kind { STEP, FOUND, REFUSED, WARN, ACTED }

    data class Line(val at: Long, val text: String, val kind: Kind)

    /** Observed directly by Compose: appending here redraws the console. */
    val lines = mutableStateListOf<Line>()

    /** True while a tick is actually in flight, which is what the caret means. */
    val busy = mutableStateOf(false)

    fun say(text: String, kind: Kind = Kind.STEP) {
        if (text.isBlank()) return
        // The same line twice in a row is the loop idling, not news.
        if (lines.lastOrNull()?.text == text) return
        lines += Line(System.currentTimeMillis(), text.trim(), kind)
        while (lines.size > MAX) lines.removeAt(0)
    }

    /** Wrap one tick, so the caret is honest about when it is running. */
    suspend fun <T> working(block: suspend () -> T): T {
        busy.value = true
        return try { block() } finally { busy.value = false }
    }

    fun clear() { lines.clear() }
}
