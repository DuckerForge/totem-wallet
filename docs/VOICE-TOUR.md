# The tour, to read

A spoken walk through the whole app: what Totem is, then every feature, in the order the author ranks them. One paragraph is one breath of the voice. Every claim here is already in the README,
the submission answers or the demo voice track; keep it that way.

Generate the audio with `python3 scripts/video/tour.py`.

---

Totem is a wallet built for the Solana Seeker. It shows you what a transaction really does before you sign it. It has an agent that trades a small budget for you, and tells you what it is doing. And it knows what the other Seekers are buying.

Before any payment, the transaction is simulated on the chain. You see what leaves, what comes back, who gets it and the fee. The risks come first: an unlimited approval, an address that only looks like one of your contacts, a wallet nobody has seen before. If the risk is severe, it will not sign. Otherwise you hold to confirm, then your fingerprint. The key never leaves the Seed Vault.

The agent gets its own small budget, never your seed. You decide how much a trade, how much a day, and above that it has to ask you. Bring your own AI key, from a free provider or a paid one. And give it your own rules, typed in or loaded from a file. Before every buy, it reads them, and they can stop the trade. They can never raise a limit.

Open the live screen and watch it work. The charts of every coin it holds, with the entry, the target and the stop. What it is thinking, line by line. And a voice that tells you what it just did.

Scout shows what the other Seekers are doing. More than ten thousand active wallets, all holding the same phone as you. What they are buying right now. The whales, and what they hold. Follow a wallet, and your phone tells you when it buys. Let the agent copy it: its buys go to the front of the line, through the same checks, and its sells can be mirrored. And what a typical Seeker keeps: the coins, the amounts, and the SKR that half of them keep staked.

A bubble that floats over every app, and a widget on your home screen. The agent, your balance and your wallet's health, always in view. Sell or stop the agent, without opening the app.

Every coin, ranked by size, with its chart. Follow the ones you care about, and get told when they move. And one question, answered with plain arithmetic: what would this coin be worth if it were as big as that one?

Move SOL or USDC to more than two hundred chains, through RocketX. Or send to another address of yours, so the chain no longer shows a line between them. Swaps go out protected from the bots that jump the queue.

One score for how safe your wallet is. It finds the approvals that still let someone spend your tokens, and the empty accounts holding your rent. One signature fixes them. It even finds pool fees you never collected.

Write a payment request on an NFC sticker, stick it to a counter, and anyone can pay by touching it. Your phone is not needed. Or be paid by touch, phone to phone. And once you have paid, show a proof signed by your phone. The shop checks it on theirs, no explorer needed.

And ORE: the agent can dig from its budget, under the same limits, and bring the gains home.

Receipt before signature. Everywhere. Totem.
