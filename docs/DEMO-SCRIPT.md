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

**0:00 to 0:12, the hook**
Voice: "A phone can ask you to sign something and show you nothing but a name and a button. This is Totem."
Show: the attacker dApp asking to sign. Cut to our receipt appearing over it.

**0:12 to 0:45, the receipt**
Voice: "Every transaction is simulated on chain first. The receipt is built from what the network says will happen, not from what the app claims."
Show: the drain attempt. The receipt says the real amount and the real recipient, and the risk in red. Reject.
Then the unlimited approval: severe risk, the signature is blocked before the Seed Vault is even asked.

**0:40 to 1:00, the wallet itself**
Voice: "It is a whole wallet, not a demo. Everything the phone holds on one page, what it is worth today, what is staked, what is working in DeFi, and every coin on Solana with its chart."
Show: the home with the balance and the eight actions, the portfolio, the staking and DeFi cards, then Market and one coin with its chart.

**0:45 to 1:05, signing something real**
Voice: "A real payment reads the same way. Hold to confirm, then the Seed Vault."
Show: Send, the receipt, the address trust badge, hold, fingerprint, the entry landing in the ledger.
Then tap "Show the proof": a QR signed by this phone, verified by the other device on screen.

**1:05 to 1:40, the agent**
Voice: "A budget kept apart from the wallet, inside a collar you set. It never sees the seed. It looks for a coin, buys a slice, sells at a target or a stop, and above its limits it has to ask for your fingerprint."
Show: the first minute card, make a budget, the collar (per move, per day, silent below), the full screen payment.
Then "Watch it work": the timer ring, the charts with entry, target and stop drawn, the reasoning typed out, the voice saying what it does. Turn the phone sideways for the two chart layout.

**1:40 to 1:58, ORE**
Voice: "ORE is a game on Solana: a five by five grid, one round a minute. Totem reads the grid and says what a square really costs and what it can pay, before you put anything on it. The agent can dig from its budget, under the same collar, and bring the gains home in ORE."
Show: Dig ORE, the grid with the emptiest squares marked, the outlook line with the expected ORE and the expected cost, hold to dig, the receipt. Then Claim, and the ORE landing in the wallet.

**1:20 to 1:34, the agent's own page**
Voice: "The agent has a page of its own: what it may spend, what it has already done, the model it runs on, and a live trace of every step it takes."
Show: the Agent tab, the budget card with the collar, the last budget's result, the Pro rows.

**1:34 to 1:50, the wallet checks itself**
Voice: "It also checks the wallet itself. Approvals you forgot, accounts holding rent you can reclaim, and pool fees nobody ever collected, read straight from the chain with no key of yours."
Show: Settings, Wallet and safety: the health score, forgotten money, delegations and accounts, trusted contacts.

**1:58 to 2:15, what nobody else has**
Voice: "It also reads the crowd it lives in."
Show: Scout, what the active Seekers are buying, the census card, half of them keep SKR staked with the Guardians, a fact no balance shows.
Then wallet health: forgotten money, unclaimed pool fees on Orca, Raydium and Meteora, read straight from the chain with no key.

**2:15 to 2:32, the phone itself**
Voice: "It uses the hardware the Seeker actually has."
Show: pay by touch between two phones, or write a payment request on an NFC sticker and tap it.
Show: a hand over the screen, the numbers vanish, the fingerprint brings them back.

**2:32 to 2:50, the close**
Voice: "Receipt before signature, everywhere. On a dApp, on a Blink, on a bridge, on the grid, and on everything the agent does."
Show: the bridge quote, then the ledger with the day's entries, then the app icon.
Last line: "Totem. What you see is what you sign."

## Rules for the cut

- No stock footage, no slides. The phone screen is the whole video.
- Every number on screen is real money on mainnet, however small.
- Never show a seed phrase, the RPC key, or the partner keys.
- The Seed Vault prompt records black (protected window): cut away at the fingerprint and come back on the result. Never try to film it.
- `screenrecord` writes the file only when its time limit runs out: always wait past `--time-limit`.
- Notifications silenced, the tester app never in frame.
- Subtitles burned in, because the room at judging will be loud.
- The editing machine is in `scripts/video/`: `beats.py` for the timing, `cards.py` for the titles, `phone.py` for the frame, `cut.py` to assemble.
