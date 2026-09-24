"""
La scaletta, come dati.

Le battute sono copiate **parola per parola** da `docs/DEMO-SCRIPT.md`, che le
ha gia' scritte in inglese con i tempi. Non si riscrivono qui: se una frase va
cambiata si cambia li', e qui si ricopia. Un video con due copie dello stesso
testo finisce con due testi diversi.

Rispetto alla scaletta del 16/09 cambia una cosa sola, e per una ragione: i due
documenti litigavano. `HACKATHON-PLAN.md` chiama l'Agent Gate — l'agente che
mente e viene bloccato — la parte che non si taglia mai; la scaletta riscritta
spende quel minuto sulla paghetta e il Gate non lo mostra affatto. Qui il blocco
1:05–1:45 si divide in due da venti secondi, e i dieci secondi mancanti si
prendono dal censimento, che e' un bel numero ma non e' il prodotto.

    0:00   3s   la porta            il marchio che si forma
    0:03   9s   l'amo              la dApp che chiede e non mostra
    0:12  33s   lo scontrino       drain e approvazione illimitata, rifiutati
    0:45  20s   la firma vera      tieni premuto, stacco, nel registro
    1:05  20s   la paghetta        il collare e la riga in dollari
    1:25  20s   l'Agent Gate       l'onesto passa, il bugiardo e' bloccato
    1:45  10s   il censimento      meta' dei Seeker tiene SKR in staking
    1:55  25s   il telefono        tocco fra due telefoni, mano sullo schermo
    2:20  20s   la chiusura        ponte, registro, marchio
                                   ------
                                   2:40
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class Beat:
    key: str            # il nome del file girato: beat_<key>.mp4
    seconds: float
    label: str | None   # l'etichetta corta sopra il sottotitolo
    text: str | None    # la battuta, verbatim dalla scaletta
    shot: str           # cosa si vede, per chi gira
    hands: bool = False # True se serve l'impronta, cioe' se serve l'utente
    start: float = 0.0  # da che punto della registrazione tagliare


SCRIPT: list[Beat] = [
    Beat(
        # il marchio si forma sotto lo splash e sotto il riquadro dell'impronta, che
        # annerisce la registrazione: si riprende da quando quel riquadro e' chiuso.
        "door", 3.0, None, None,
        "La porta: il marchio fermo, il nome, il tasto per entrare. Si apre l'app da fredda.",
        start=4.9,
    ),
    Beat(
        "hook", 9.0, "01 · blind",
        "A phone can ask you to sign something and show you nothing but a name and a button.",
        "La dApp che chiede di firmare, con il suo nome e un tasto e basta.",
        start=0.0,
    ),
    Beat(
        # il cammino fino alla dApp sta in testa al girato: si riprende da dove
        # lo scontrino e' in pagina.
        "receipt", 33.0, "02 · the receipt",
        "Every transaction is simulated on chain first. The receipt is built from what the "
        "network says will happen, not from what the app claims.",
        "Dal :testdapp: il drain, lo scontrino che dice la cifra vera e il rischio in rosso, "
        "rifiutato. Poi l'approvazione illimitata: pericolo, un tocco solo bloccato.",
        start=0.0,
    ),
    Beat(
        "wallet", 20.0, "03 · the wallet",
        "It is a whole wallet, not a demo. Everything the phone holds on one page, what it is "
        "worth today, what is staked, what is working in DeFi, and every coin on Solana with "
        "its chart.",
        "La home col saldo e le otto azioni, il portafoglio, lo staking e la DeFi, poi Mercato "
        "e una moneta col suo grafico.",
    ),
    Beat(
        "sign", 20.0, "04 · a real payment",
        "A real payment reads the same way. Hold to confirm, then the Seed Vault.",
        "Manda, lo scontrino, la fiducia dell'indirizzo, tieni premuto. STACCO. "
        "Riprende dal registro con il valore in euro, poi la prova QR.",
        hands=True,
    ),
    Beat(
        "budget", 20.0, "05 · the budget",
        "A budget kept apart from the wallet. It never sees the seed.",
        "La paghetta: creala, il collare coi tetti, la riga del calcolo in dollari.",
        hands=True,
    ),
    Beat(
        "gate", 20.0, "06 · the agent that lies",
        "It looks for a coin, buys a slice, sells at a target or a stop, and asks for a "
        "fingerprint above its limits.",
        "scripts/test-agent.sh honest: 'Intento agente OK', si firma. Poi liar: dichiara "
        "0,1 SOL, la transazione ne manda 5 a un indirizzo mai visto. Bloccato, in rosso.",
        hands=True,
    ),
    Beat(
        "agent", 16.0, "07 · the agent's page",
        "The agent has a page of its own: what it may spend, what it has already done, the "
        "model it runs on, and a live trace of every step it takes.",
        "La scheda Agente: la paghetta col collare, il risultato dell'ultima, le righe Pro.",
    ),
    Beat(
        "health", 18.0, "08 · the wallet checks itself",
        "It also checks the wallet itself. Approvals you forgot, accounts holding rent you can "
        "reclaim, and pool fees nobody ever collected, read straight from the chain with no key "
        "of yours.",
        "Impostazioni, Wallet and safety: il punteggio, i soldi dimenticati, le deleghe e i "
        "conti, i contatti fidati.",
    ),
    Beat(
        # Questa scena vale trentamila dollari: e' il premio che ORE ha messo il 21/09 per
        # chi lo integra davvero, e nella scaletta non c'era affatto.
        "ore", 22.0, "09 · ORE",
        "ORE is a game on Solana: a five by five grid, one round a minute. Totem reads the "
        "grid and says what a square really costs and what it can pay, before you put "
        "anything on it. The agent can dig from its budget, under the same collar, and bring "
        "the gains home in ORE.",
        "La griglia cinque per cinque, le caselle piu' vuote segnate, la riga con l'ORE "
        "atteso e il costo atteso. Niente firma: si guarda la griglia.",
        start=0.0,
    ),
    Beat(
        "crowd", 10.0, "10 · the crowd",
        "It also reads the crowd it lives in.",
        "Il censimento: meta' dei Seeker tiene SKR in staking dai Guardiani. Poi i soldi "
        "dimenticati, le commissioni mai riscosse lette dalla catena senza chiavi.",
        start=0.0,
    ),
    Beat(
        "hardware", 25.0, "11 · the phone itself",
        "It uses the hardware the Seeker actually has.",
        "Pagamento col tocco fra due telefoni, oppure la richiesta scritta su un adesivo NFC. "
        "Poi la mano sullo schermo: i numeri spariscono, l'impronta li riporta.",
    ),
    Beat(
        "close", 20.0, "12 · everywhere",
        "Receipt before signature, everywhere. On a dApp, on a Blink, on a bridge, and on "
        "everything the agent does.",
        "Il preventivo del ponte, il registro della giornata, l'icona. Ultima riga: "
        "What you see is what you sign.",
        start=0.0,
    ),
]


def total() -> float:
    return sum(b.seconds for b in SCRIPT)


def needs_hands() -> list[Beat]:
    """Le scene in cui serve il dito dell'utente: si registra mentre tocca lui."""
    return [b for b in SCRIPT if b.hands]


def timeline() -> list[tuple[float, Beat]]:
    t, out = 0.0, []
    for b in SCRIPT:
        out.append((t, b))
        t += b.seconds
    return out


def as_script() -> str:
    """La traccia per la voce, coi minutaggi. Si legge e si registra."""
    rows = []
    for at, b in timeline():
        m, s = divmod(int(at), 60)
        rows.append(f"{m}:{s:02d}  {b.text or '(nessuna voce, solo il marchio)'}")
    return "\n".join(rows)


if __name__ == "__main__":
    for at, b in timeline():
        m, s = divmod(int(at), 60)
        mark = " [impronta]" if b.hands else ""
        print(f"{m}:{s:02d}  {b.seconds:>4.0f}s  {b.key:<9}{mark}")
    m, s = divmod(int(total()), 60)
    print(f"\ntotale {m}:{s:02d}   (tetto 2:50)")
    print(f"scene con impronta: {', '.join(b.key for b in needs_hands())}")
