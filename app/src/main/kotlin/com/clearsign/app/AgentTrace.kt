package com.clearsign.app

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

/**
 * What the agent is doing right now, in its own words, as it happens.
 *
 * The screen used to say two things: "trading" or "watching the market". Both are
 * true and neither tells you whether the thing is alive, stuck, or halfway
 * through refusing a coin for a reason you would have wanted to know. A loop that
 * works invisibly is indistinguishable from a loop that has died — that is what
 * cost sixteen hours this morning.
 *
 * So the loop narrates. Every meaningful step appends one short line here, the
 * chat renders the last few as a console with a caret, and the difference between
 * thinking and stopped becomes something you can see rather than something you
 * have to trust.
 *
 * Deliberately in memory only. This is a window onto a running process, not a
 * record: the ledger is the record, and writing forty lines a minute to disk to
 * produce an effect would be the wrong trade.
 */
object AgentTrace {
    private const val MAX = 60

    /**
     * Che riga e'.
     *
     * `REFUSED` e' rosso con la ics, ed e' giusto cosi': vuol dire che qualcosa
     * e' stato **fermato**. Ma ci finiva dentro anche "non sono riuscito a
     * mettere l'ordine su Jupiter", che non e' un rifiuto e non e' un pericolo:
     * e' una cosa che non si puo' fare e che cambia chi tiene d'occhio la
     * posizione. Letta in rosso con la ics, subito dopo un acquisto, sembra che
     * la moneta appena comprata sia esplosa.
     *
     * `WARN` e' ambra col punto esclamativo: guarda, sappilo, ma non e' andato
     * storto niente.
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
