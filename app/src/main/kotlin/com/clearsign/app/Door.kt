package com.clearsign.app

/**
 * La porta chiede l'impronta ogni volta che si rientra nell'app. Ma aprire la
 * fotocamera per uno scan, o il foglio di condivisione, e' un'altra activity
 * sopra la nostra: la nostra va in stop e la porta scattava lo stesso, e al
 * ritorno chiedeva il dito per una cosa che non era mai stata lasciata.
 *
 * Chi lancia una di quelle schermate chiama [hold] un attimo prima; lo stop
 * che arriva subito dopo lo lascia passare, una volta sola.
 */
internal object Door {
    @Volatile private var heldAt = 0L

    fun hold() { heldAt = System.currentTimeMillis() }

    /** Vero se uno stop e' arrivato entro tre secondi da un [hold]: si consuma. */
    fun consumeHold(): Boolean {
        val ok = System.currentTimeMillis() - heldAt < 3_000L
        heldAt = 0L
        return ok
    }
}
