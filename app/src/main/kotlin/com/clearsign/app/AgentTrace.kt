package com.clearsign.app

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

/**
 * What the agent is doing right now: one short line per step, shown by the chat as a console,
 * so a stuck or refusing loop can be told from a working one. In memory only; the ledger is
 * the record.
 */
object AgentTrace {
    private const val MAX = 60

    /**
     * Line kind. `REFUSED` is red with the x: something was stopped. `WARN` is amber with the
     * exclamation mark, for failures that are not refusals (an order Jupiter would not place).
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

    /** Wrap one tick, so the caret shows only while it runs. */
    suspend fun <T> working(block: suspend () -> T): T {
        busy.value = true
        return try { block() } finally { busy.value = false }
    }

    fun clear() { lines.clear() }
}
