package com.clearsign.core

/**
 * Minimal message catalog so the receipt reads in the user's own language.
 * Clear-signing is worthless if the human can't read it — yet almost every
 * wallet shows raw program data or English-only. Add locales by extending the
 * [catalog]; unknown locales fall back to English.
 */
enum class Msg {
    YOU_SEND, YOU_RECEIVE, TO, FEE, TRUSTED_CONTACT, KNOWN_ADDRESS,
    NEW_ADDRESS, FLAGGED_ADDRESS, NOTHING_ELSE, REVIEW_WARNINGS,
}

object Localization {
    private val en = mapOf(
        Msg.YOU_SEND to "You send",
        Msg.YOU_RECEIVE to "You receive",
        Msg.TO to "to",
        Msg.FEE to "Network fee",
        Msg.TRUSTED_CONTACT to "trusted contact",
        Msg.KNOWN_ADDRESS to "known address",
        Msg.NEW_ADDRESS to "new address",
        Msg.FLAGGED_ADDRESS to "FLAGGED address",
        Msg.NOTHING_ELSE to "You receive nothing else.",
        Msg.REVIEW_WARNINGS to "Review the warnings before approving.",
    )
    private val it = mapOf(
        Msg.YOU_SEND to "Invii",
        Msg.YOU_RECEIVE to "Ricevi",
        Msg.TO to "a",
        Msg.FEE to "Commissione di rete",
        Msg.TRUSTED_CONTACT to "contatto fidato",
        Msg.KNOWN_ADDRESS to "indirizzo conosciuto",
        Msg.NEW_ADDRESS to "indirizzo nuovo",
        Msg.FLAGGED_ADDRESS to "indirizzo SEGNALATO",
        Msg.NOTHING_ELSE to "Non ricevi nient'altro.",
        Msg.REVIEW_WARNINGS to "Controlla gli avvisi prima di approvare.",
    )
    private val es = mapOf(
        Msg.YOU_SEND to "Envías",
        Msg.YOU_RECEIVE to "Recibes",
        Msg.TO to "a",
        Msg.FEE to "Comisión de red",
        Msg.TRUSTED_CONTACT to "contacto de confianza",
        Msg.KNOWN_ADDRESS to "dirección conocida",
        Msg.NEW_ADDRESS to "dirección nueva",
        Msg.FLAGGED_ADDRESS to "dirección MARCADA",
        Msg.NOTHING_ELSE to "No recibes nada más.",
        Msg.REVIEW_WARNINGS to "Revisa las advertencias antes de aprobar.",
    )

    private val catalog = mapOf("en" to en, "it" to it, "es" to es)

    private val riskEn = mapOf(
        RiskFlag.SIMULATION_FAILED to "Transaction could not be simulated — refusing to sign blind.",
        RiskFlag.SIMULATION_UNAVAILABLE to "The network did not answer the simulation — refusing to sign blind.",
        RiskFlag.BLOCKED_MALICIOUS to "Flagged as malicious by security scan.",
        RiskFlag.SANCTIONED to "Recipient is on a sanctions list: %s",
        RiskFlag.UNLIMITED_APPROVAL to "Grants unlimited spending authority over your tokens.",
        RiskFlag.LIMITED_APPROVAL to "Lets a delegate spend your tokens on your behalf.",
        RiskFlag.AUTHORITY_CHANGE to "Changes ownership/authority of an account or mint.",
        RiskFlag.ACCOUNT_CLOSE to "Closes an account and sends the rent to a new address.",
        RiskFlag.LOOKALIKE_ADDRESS to "Recipient looks like %s but is a different address (possible address-poisoning).",
        RiskFlag.NEW_UNKNOWN_RECIPIENT to "First time sending to this address.",
        RiskFlag.STATE_DRIFT to "On-chain state changed after preview — the outcome no longer matches what you saw.",
        RiskFlag.COMMUNITY_FLAGGED to "The community flagged this address as a scam.",
        RiskFlag.DRAINS_BALANCE to "Sends out %s%% of your %s balance.",
        RiskFlag.WALLET_OWNER_CHANGE to "Hands ownership of YOUR WALLET account to a program: it could take everything.",
        RiskFlag.DURABLE_NONCE to "Durable-nonce transaction: this signature never expires and can be submitted at any time.",
        RiskFlag.FOREIGN_FEE_PAYER to "Someone else (%s) pays the network fee — typical of \"gasless\" drainer kits.",
        RiskFlag.AGENT_INTENT_MISMATCH to "The agent declared «%s», but the transaction actually: %s.",
        RiskFlag.AGENT_INTENT_OK to "Agent %s declares: %s — consistent with the simulation.",
        RiskFlag.BRAND_NEW_RECIPIENT to "The recipient has no on-chain history at all (brand-new wallet).",
        RiskFlag.FEE_EXCESSIVE to "Pays %s× the network's current priority fee (%s SOL extra) — the dApp set a far higher price than needed.",
        RiskFlag.EXTRA_SIGNERS to "Needs %s more signature(s) besides yours (e.g. %s): it only goes through once someone else also signs.",
        RiskFlag.WAGER to "Puts %s SOL on the ORE grid. It is a wager: the SOL can go to the other squares.",
        RiskFlag.WAGER_FOR_OTHER to "Pays %s SOL of ORE squares for another wallet, %s, not for you.",
    )
    private val riskIt = mapOf(
        RiskFlag.SIMULATION_FAILED to "Impossibile simulare la transazione: non firmo alla cieca.",
        RiskFlag.SIMULATION_UNAVAILABLE to "La rete non ha risposto alla simulazione: non firmo alla cieca.",
        RiskFlag.BLOCKED_MALICIOUS to "Segnalata come malevola dalla scansione di sicurezza.",
        RiskFlag.SANCTIONED to "Il destinatario è in una lista di sanzioni: %s",
        RiskFlag.UNLIMITED_APPROVAL to "Concede autorità di spesa ILLIMITATA sui tuoi token.",
        RiskFlag.LIMITED_APPROVAL to "Autorizza un delegato a spendere i tuoi token per conto tuo.",
        RiskFlag.AUTHORITY_CHANGE to "Cambia il proprietario o l'autorità di un account o di un mint.",
        RiskFlag.ACCOUNT_CLOSE to "Chiude un account e manda il rent a un indirizzo nuovo.",
        RiskFlag.LOOKALIKE_ADDRESS to "Il destinatario somiglia a %s ma è un indirizzo diverso (possibile address-poisoning).",
        RiskFlag.NEW_UNKNOWN_RECIPIENT to "Prima volta che invii a questo indirizzo.",
        RiskFlag.STATE_DRIFT to "Lo stato on-chain è cambiato dopo l'anteprima: l'esito non corrisponde più a ciò che hai visto.",
        RiskFlag.COMMUNITY_FLAGGED to "La community ha segnalato questo indirizzo come truffa.",
        RiskFlag.DRAINS_BALANCE to "Fa uscire il %s%% del tuo saldo in %s.",
        RiskFlag.WALLET_OWNER_CHANGE to "Cede la proprietà del TUO WALLET a un programma: potrebbe prendersi tutto.",
        RiskFlag.DURABLE_NONCE to "Transazione con durable nonce: la firma non scade mai e può essere inviata in qualsiasi momento.",
        RiskFlag.FOREIGN_FEE_PAYER to "Le commissioni le paga qualcun altro (%s): tipico dei kit drainer \"gasless\".",
        RiskFlag.AGENT_INTENT_MISMATCH to "L'agente ha dichiarato «%s», ma la transazione in realtà: %s.",
        RiskFlag.AGENT_INTENT_OK to "L'agente %s dichiara: %s — coerente con la simulazione.",
        RiskFlag.BRAND_NEW_RECIPIENT to "Il destinatario non ha alcuno storico on-chain (wallet nuovo di zecca).",
        RiskFlag.FEE_EXCESSIVE to "Paga %s× la commissione di priorità attuale della rete (%s SOL in più): la dApp ha impostato un prezzo molto più alto del necessario.",
        RiskFlag.EXTRA_SIGNERS to "Richiede %s firma/e oltre alla tua (es. %s): si completa solo se firma anche qualcun altro.",
        RiskFlag.WAGER to "Mette %s SOL sulla griglia di ORE. È una scommessa: il SOL può andare alle altre caselle.",
        RiskFlag.WAGER_FOR_OTHER to "Paga %s SOL di caselle ORE per un altro portafoglio, %s, non per te.",
    )

    /** Localized explanation for a risk; unknown locales read English. */
    fun riskDetail(flag: RiskFlag, locale: String = "en", vararg args: Any): String {
        val tpl = (if (locale == "it") riskIt else riskEn)[flag] ?: riskEn[flag] ?: flag.name
        return if (args.isEmpty()) tpl else String.format(java.util.Locale.ROOT, tpl, *args)
    }

    fun t(msg: Msg, locale: String = "en"): String =
        (catalog[locale] ?: en)[msg] ?: en.getValue(msg)

    fun trustWord(level: TrustLevel, locale: String = "en"): String = when (level) {
        TrustLevel.TRUSTED -> t(Msg.TRUSTED_CONTACT, locale)
        TrustLevel.KNOWN -> t(Msg.KNOWN_ADDRESS, locale)
        TrustLevel.NEW -> t(Msg.NEW_ADDRESS, locale)
        TrustLevel.FLAGGED -> t(Msg.FLAGGED_ADDRESS, locale)
    }
}
