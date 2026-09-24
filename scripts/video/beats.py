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
    # L'ordine e' per forza, non per come sono nate le funzioni: la tesi, poi la cosa piu'
    # difficile da copiare, poi i due premi che si assegnano a mano (ORE 30k, SKR 10k), poi
    # l'hardware che solo un Seeker ha. Chi guarda si ferma presto, e i premi li assegna
    # qualcuno che il video lo guarda fino a dove regge.
    #
    # La porta non c'e': l'apertura la disegna `intro.py`, perche' sul telefono il riquadro
    # dell'impronta e' una finestra protetta e la registrazione esce nera.
    Beat(
        "receipt", 16.0, "01 \u00b7 the blind signature",
        "A wallet shows you a name and a button, and you sign away everything. Totem simulates "
        "the transaction on chain and shows what will actually happen. This one is blocked "
        "before the Seed Vault is even asked.",
        "Impostazioni, TRY AN ATTACK, Wallet drainer: la cifra vera, i due rischi in rosso, "
        "la firma bloccata.",
    ),
    Beat(
        "sign", 12.0, "02 \u00b7 a real payment",
        "A real payment reads the same way. Hold to confirm, then the Seed Vault. The keys "
        "never leave it.",
        "Invia, lo scontrino, la fiducia dell'indirizzo, tieni premuto, l'impronta, la riga "
        "nel registro.",
        hands=True,
    ),
    Beat(
        "agent", 22.0, "03 \u00b7 the agent, in a collar",
        "The agent gets a budget of its own and never your seed. You set the collar: how much "
        "a move, how much a day, and above that it has to ask you. Then you watch it work, "
        "every step, as it happens.",
        "La paghetta col collare, poi Guardalo lavorare: anelli, grafici con entrata, "
        "obiettivo e stop, i pensieri battuti. Chiudi sulla notifica col disegno.",
        hands=True,
    ),
    Beat(
        "gate", 12.0, "04 \u00b7 the agent that lies",
        "An AI agent never holds a key here. It hands over a transaction and says what it "
        "does. Totem checks the claim against the real effect. This one lied, and it is "
        "blocked.",
        "Dal tester: Agent Gate, lying agent. Rifiutato in rosso, con cosa non torna.",
    ),
    Beat(
        "bubble", 10.0, "05 \u00b7 always with you",
        "It stays with you: a bubble over any app, and a widget on the home screen, both "
        "showing what the agent is doing and how the wallet is.",
        "La bolla trascinata sopra un'altra app, poi il widget che si aggiorna da solo.",
    ),
    Beat(
        "ore", 18.0, "06 \u00b7 ORE",
        "ORE is a game on Solana: a five by five grid, one round a minute. Totem reads the "
        "program itself and says what a square really costs and what it can pay. The agent can "
        "dig from its budget, under the same collar, and bring the gains home in ORE.",
        "La griglia con le probabilita' vere, il giro, i minatori, le caselle piu' vuote, "
        "l'ORE atteso contro il costo atteso. Poi l'interruttore Dig ORE nella paghetta.",
    ),
    Beat(
        "crowd", 14.0, "07 \u00b7 the crowd",
        "Totem reads the crowd it lives in: ten thousand Seeker wallets, and what they are "
        "buying right now. And a fact no balance shows: half of them keep their SKR staked "
        "with the Guardians.",
        "Prima la scheda del censimento, grande. Poi la diretta e la pagina di una persona.",
    ),
    Beat(
        "hardware", 16.0, "08 \u00b7 what only a Seeker does",
        "The Seeker has hardware nobody else has. Get paid by touch. Write a payment request "
        "on a cent sticker. Swap contacts by touching two phones. And prove a payment with a "
        "QR this phone signed, checked by another phone with no explorer and no network.",
        "Tocco fra i due telefoni, l'adesivo scritto e toccato, lo scambio contatti, e la "
        "prova firmata letta dall'altro telefono.",
        hands=True,
    ),
    Beat(
        "gift", 8.0, "09 \u00b7 a link for someone with no wallet",
        "Pay someone who has no wallet at all. The money travels inside a link.",
        "La cifra, il link, e la pagina che si apre sull'altro telefono.",
        hands=True,
    ),
    Beat(
        "swap", 10.0, "10 \u00b7 private swap, and the bridge",
        "Swaps go out privately, past the bots that read the public queue. And a bridge to two "
        "hundred chains, with the receipt first.",
        "Lo swap con la rotta e la riga sulla coda privata, poi il preventivo del ponte.",
    ),
    Beat(
        "health", 10.0, "11 \u00b7 the wallet audits itself",
        "It audits itself: approvals you forgot, rent locked in dead accounts, and pool fees "
        "nobody ever collected, read straight from the chain with no key of yours.",
        "Il punteggio, i soldi dimenticati su Orca, Raydium e Meteora, le deleghe, i conti "
        "vuoti col rent, brucia e recupera.",
    ),
    Beat(
        "market", 10.0, "12 \u00b7 the market, and the book",
        "Every coin on Solana with its chart, and what yours would be worth with theirs. Every "
        "signature kept, with what it cost that day.",
        "Preferiti, una moneta col grafico, at X's market cap. Poi il registro e la scheda P&L.",
    ),
    Beat(
        "close", 6.0, "13 \u00b7 everywhere",
        "Receipt before signature. Everywhere. Totem.",
        "Il marchio, fermo.",
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
