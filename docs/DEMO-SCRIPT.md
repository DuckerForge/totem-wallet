# Three minute demo, Clock In

Shot on a real Seeker. Screen recording plus voice. English only. Target 2:50.
The wallet is Totem. Say the name once at the start and once at the end, nowhere else.

## Before recording

- Release build installed, signed with the real key, Seed Vault set up.
- A test wallet with a little SOL, some SKR, and at least one dead token account so wallet health has something to find.
- Enough SOL for one ORE dig (0.01 is plenty) and, if the timing allows, a round already won so Claim has something to collect.
- `:testdapp` installed for the attack beats. Plan B if it will not start: Settings, «Try an attack», the drainer scenario shows the same red receipt with no dApp.
- The agent stopped, no open budget, so the first minute card shows.
- Two NFC stickers and, if a second phone is there, the contact tap beat.
- Airplane mode off, battery saver off, notifications allowed, TalkBack off.
- Language set to English in Settings, so the recording matches the voice.

## Beat sheet

Fourteen beats, 2:42. The order is by force, not by the order the features were built: the
thesis first, then the thing hardest to copy, then the two prizes that are handed out by hand
(ORE 30k, SKR 10k), then the hardware only a Seeker has, then the rest.

**0:00 · 6s · the mark** — drawn, not filmed: the fingerprint sheet is a secure window and
records black. Music only.

**0:06 · 16s · the blind signature, stopped** — no hands
Voice: "A wallet shows you a name and a button, and you sign away everything. Totem simulates
the transaction on chain and shows what will actually happen. This one is blocked before the
Seed Vault is even asked."
Show: Settings, TRY AN ATTACK, Wallet drainer. The real amount, the two red risks, and
"Signature blocked: severe risk. The Seed Vault is never even asked."

**0:22 · 12s · a real payment reads the same** — fingerprint
Voice: "A real payment reads the same way. Hold to confirm, then the Seed Vault. The keys never
leave it."
Show: Send, the receipt, the trust badge, hold, the print, the row landing in the ledger.

**0:34 · 22s · the agent, inside a collar** — fingerprint, and an open budget with positions
Voice: "The agent gets a budget of its own and never your seed. You set the collar: how much a
move, how much a day, and above that it has to ask you. Then you watch it work, every step, as
it happens."
Show: the budget with the collar, then Watch it work: the timer rings, the charts with entry,
target and stop drawn, the reasoning typed as it lands. Close on the notification with the
generated picture and the Sell now button.

**0:56 · 12s · the agent that lies, blocked** — no hands
Voice: "An AI agent never holds a key here. It hands over a transaction and says what it does.
Totem checks the claim against the real effect. This one lied, and it is blocked."
Show: from the tester, Agent Gate, lying agent. Refused in red, with what does not add up.

**1:08 · 10s · always with you** — no hands
Voice: "It stays with you: a bubble over any app, and a widget on the home screen, both showing
what the agent is doing and how the wallet is."
Show: the bubble dragged over another app, then the widget refreshing itself.

**1:18 · 18s · ORE** — no hands
Voice: "ORE is a game on Solana: a five by five grid, one round a minute. Totem reads the
program itself and says what a square really costs and what it can pay. The agent can dig from
its budget, under the same collar, and bring the gains home in ORE."
Show: the grid with the real odds, the round, the miners, the emptiest squares, the expected
ORE against the expected cost. Then the Dig ORE switch inside the budget.

**1:36 · 14s · the crowd, and the number nobody has** — no hands
Voice: "Totem reads the crowd it lives in: ten thousand Seeker wallets, and what they are
buying right now. And a fact no balance shows: half of them keep their SKR staked with the
Guardians."
Show: the census card first, big. Then Live and one person's page.

**1:50 · 16s · what only a Seeker does** — second phone, NFC tag
Voice: "The Seeker has hardware nobody else has. Get paid by touch. Write a payment request on
a cent sticker. Swap contacts by touching two phones. And prove a payment with a QR this phone
signed, checked by another phone with no explorer and no network."
Show: tap between the two phones, the sticker written and tapped, contact tap, then the signed
proof read by the other phone.

**2:06 · 8s · a link for someone with no wallet** — fingerprint
Voice: "Pay someone who has no wallet at all. The money travels inside a link."
Show: the gift amount, the link, the page opening on the other phone.

**2:14 · 10s · private swap, and the bridge** — no hands
Voice: "Swaps go out privately, past the bots that read the public queue. And a bridge to two
hundred chains, with the receipt first."
Show: the swap with its route and the private line, then the bridge quote.

**2:24 · 10s · the wallet audits itself** — no hands
Voice: "It audits itself: approvals you forgot, rent locked in dead accounts, and pool fees
nobody ever collected, read straight from the chain with no key of yours."
Show: the health score, forgotten money on Orca, Raydium and Meteora, delegations, burn and
reclaim.

**2:34 · 10s · the market, and the book** — no hands
Voice: "Every coin on Solana with its chart, and what yours would be worth with theirs. Every
signature kept, with what it cost that day."
Show: favourites, a coin with its chart, "at X's market cap yours would be worth Y". Then the
ledger with its filters and the P&L card.

**2:44 · 6s · the close** — no hands
Voice: "Receipt before signature. Everywhere. Totem."
Show: the mark, still.

### What must not be said, because it is not true

- There is no on-chain stop loss. Jupiter only offers it through a custodial vault. The stop
  lives in the agent's loop, and the app says so.
- Forgotten money is found and counted, not collected: the app opens the platform's own page.
- The collar is enforced on the phone, not on chain, and the app says so.

## Rules for the cut

- No stock footage, no slides. The phone screen is the whole video.
- Every number on screen is real money on mainnet, however small.
- Never show a seed phrase, the RPC key, or the partner keys.
- The Seed Vault prompt records black (protected window): cut away at the fingerprint and come back on the result. Never try to film it.
- `screenrecord` writes the file only when its time limit runs out: always wait past `--time-limit`.
- Notifications silenced, the tester app never in frame.
- Subtitles burned in, because the room at judging will be loud.
- The editing machine is in `scripts/video/`: `beats.py` for the timing, `cards.py` for the titles, `phone.py` for the frame, `cut.py` to assemble.
