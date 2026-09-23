# Velum Wallet, il pitch deck

_Dieci slide con le note per chi parla. Riscritto il 23 settembre 2026 per CLOCK IN. Testo in inglese, come
tutto il materiale per i giudici; le indicazioni fra parentesi quadre sono in italiano._

[Sfondo scuro `#070b12`, menta `#4dffd0` e ciano `#4cc9ff`, Sora per i titoli. Poche parole grandi, uno
screenshot vero per slide, presi con `scripts/screenshots.sh`. Venti secondi a slide, la demo porta la settima.]

---

## Slide 1. Title

**Velum**
*What you see is what you sign.*
A Seeker wallet that reads every transaction before you sign it, and an AI agent that trades a capped budget inside a collar you set.

> "Hi. I'm building Velum. It makes the Seeker sign only what you can read, and it lets an AI agent trade for you without ever holding your key."

[Screenshot: la porta, il marchio con i due telefoni.]

## Slide 2. The problem

Wallets sign blind. A dApp can ask the signer to approve a transaction that empties your wallet while showing you only its own name: no amount, no recipient, no warning. An unlimited approval is a drain the attacker reuses until you revoke it.

And now agents want your key. An AI that trades for you needs to sign, and a key in an agent's hands is a key in a prompt injection's hands.

> "Two problems, one root: the signature happens where nobody can read it."

[Screenshot: lo scontrino sopra la dApp del tester che prova a svuotare, rischio in rosso.]

## Slide 3. A receipt before the fingerprint

Every request, from any dApp over Mobile Wallet Adapter, from Send, Swap, a Blink, the bridge, an NFC sticker, or an agent, becomes a plain receipt before the Seed Vault biometric: what leaves, to whom, the fee, the risks, the address's trust badge, look-alike detection against your own contacts.

Then hold to confirm. Then the fingerprint. Keys never leave the vault.

> "One screen, real numbers, then you hold to sign."

[Screenshot: uno scontrino pulito, invio a un contatto fidato.]

## Slide 4. It reads the truth, not the claim

- On-chain simulation, legacy and v0 with lookup tables: real balance changes, including hidden splits to fee and referral wallets.
- Risk engine: drains, unlimited approvals, authority changes, wallet takeover, foreign fee payer, address poisoning, a second signer, a wager paid for somebody else.
- Anti-TOCTOU: it re-simulates the instant before signing and refuses if the state drifted.
- On-chain IDL decoding: "Jupiter v6 · route(in_amount: 2 000 000)" instead of 200 bytes.

> "A free airdrop that is really a drain cannot hide: the receipt shows the real recipient and the real amount, and we block it."

[Screenshot: lo scontrino con i rischi, uno in DANGER.]

## Slide 5. The agent, inside a collar

You open a budget: a second key, capped in SOL and in days, apart from your wallet. The agent hunts on all of Solana with the gates the winning bots use (mint and freeze authority, holders, liquidity, sellability, Rugcheck, Jupiter Shield), buys a slice, sells at your take profit or stop loss, mirrors the Seekers you follow, and closes the budget when it expires.

A policy engine reads the simulated effect of every move, never the agent's claim. Above its limits it stops and asks for your fingerprint. Your own rules, in plain Markdown, can only close doors.

> "The collar is the product. The agent can be wrong, hallucinate, get injected: it cannot spend more than the budget, and it cannot reach your wallet."

[Screenshot: la pagina Agente con la paghetta aperta, «Dig ORE» e «Put the gains in ORE» accesi.]

## Slide 6. Watch it work

A live screen: the charts of the open positions with entry, target and stop drawn on them, the agent's reasoning typed as it lands, a voice that says what it does, a timer ring for the next look. Notifications carry a picture of where every coin sits between its stop and its target. A widget and a floating bubble show wallet health and the agent's work over any app.

> "A loop that works invisibly is indistinguishable from a loop that has died. This one narrates."

[Screenshot: Eyes con i grafici e la console.]

## Slide 7. Live demo

1. The tester dApp asks to drain the wallet: DANGER, blocked.
2. A real send: receipt, hold, fingerprint, it lands in the ledger with its fiat value.
3. Open a budget, start the agent: it finds a coin, refuses one, says why.
4. ORE: the grid, three squares, the real odds, one dig, the square rolling at round end.

> [Telefono a schermo. Veloce, lascia parlare l'app. Il prompt del Seed Vault viene nero in registrazione: stacco.]

## Slide 8. ORE, three ways

Velum reads the ORE program by hand (Steel, no IDL). A Deploy or a Claim from any dApp shows up on the receipt as what it is: a wager, with the squares and the SOL at stake. The wallet has the 5x5 grid with the real odds from the program's own rules, and the winning square replayed when the round closes. The agent can dig: it delegates part of the budget to an on-chain executor that plays every round while you sleep, and when the budget closes the gain, not the stake, can be swapped into ORE and brought home as hard money.

> "ORE is not a tab with a logo. It is in the receipt, in the wallet and in the agent, verified byte by byte on chain."

[Screenshot: la griglia a meta' scelta, la quota sotto ogni casella, la riga dell'atteso.]

## Slide 9. Built for the Seeker, and for SKR

Seed Vault, MWA endpoint, per-app language. Pay by touch over NFC, a Solana Pay request written on a sticker, contacts exchanged by touching two Seekers, payment proofs as a QR signed by the phone, guest mode. Scout: a census of 120,000 Seeker Genesis Token holders, one token per phone, so the crowd is real people; half of them keep SKR staked, and the wallet reads that stake straight from the Guardians' program with the yield measured from the share price.

> "SKR is where the crowd is. Scout watches what real Seekers buy, and the wallet shows what they stake."

[Screenshot: Scout in diretta con una riga aperta e il bottone Compra dentro.]

## Slide 10. Why it sticks, and the ask

It sits in front of every signature, so it opens every day. The agent and the ORE executor work while the phone is in the pocket. Revenue is honest: never a fee on signing, a small fee on swaps through Jupiter, nothing on safety.

Everything runs on a real Seeker today: APK, open repo, 396 unit tests, first commit on 11 September, during the hackathon. Publishing to the dApp Store next.

**Velum. Stop signing blind.**

> "Thanks. Everything you saw runs on this phone."

[Screenshot: la home con il saldo, i cerchi e le tessere DeFi con ORE.]
