package com.clearsign.core

/**
 * What a failed simulation actually means, in words.
 *
 * The node answers with things like `{"InstructionError":[6,{"Custom":6025}]}`,
 * and that was going straight onto the screen as the reason not to sign. It is
 * the right information in the wrong language: nobody can act on it, so it reads
 * as "something broke" and the next thing a person does is press the button
 * anyway.
 *
 * Every line here ends with what to do, because a failure you can retry and a
 * failure you cannot are the same sentence otherwise. The raw code is kept in
 * brackets: it is what makes a bug report useful.
 */
object SimError {

    fun explain(raw: String?, it: Boolean = false): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val low = s.lowercase()

        fun line(body: String) = body + code(s)?.let { c -> " ($c)" }.orEmpty()

        return when {
            // A custom error inside an instruction comes from one of the programs
            // in the route, and for a swap that is almost always the pool saying
            // the price moved out from under the quote.
            low.contains("custom") ->
                line(if (it) "La rotta non regge a questo prezzo. Riprova con un preventivo nuovo." else "The route does not hold at this price. Try again with a fresh quote.")
            low.contains("insufficient") && low.contains("rent") ->
                line(if (it) "Non basta il SOL per l'affitto dei conti da aprire." else "Not enough SOL for the rent on the accounts this opens.")
            low.contains("insufficient") ->
                line(if (it) "Non basta il saldo per questa operazione." else "The balance does not cover this.")
            low.contains("blockhash") ->
                line(if (it) "La transazione è scaduta. Serve rifarla." else "The transaction has expired. It has to be built again.")
            low.contains("accountnotfound") || low.contains("could not find account") ->
                line(if (it) "Un conto che serve non esiste ancora." else "An account this needs does not exist yet.")
            low.contains("alreadyinuse") ->
                line(if (it) "Un conto che vuole aprire esiste già." else "An account it wants to open already exists.")
            low.contains("programfailedtocomplete") ->
                line(if (it) "Un programma della rotta si è interrotto a metà." else "A program in the route stopped halfway.")
            low.contains("accountinuse") ->
                line(if (it) "Un conto è occupato da un'altra transazione in corso." else "An account is busy in another transaction.")
            else -> null
        }
    }

    /** The short form a person can quote back: "Custom 6025", "InstructionError 3". */
    private fun code(raw: String): String? {
        Regex("\"Custom\"\\s*:\\s*(\\d+)").find(raw)?.let { return "Custom " + it.groupValues[1] }
        Regex("Custom\\((\\d+)\\)").find(raw)?.let { return "Custom " + it.groupValues[1] }
        return null
    }
}
