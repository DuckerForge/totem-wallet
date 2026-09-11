# ClearSign — How it works

A plain-language tour of what happens inside ClearSign, from a dApp request to a
signed transaction and its receipt. Read top to bottom.

## The one idea
A hardware signer should guarantee "you sign what you see." On the Seeker, an app
can get a signature while showing you almost nothing. ClearSign puts a **readable
receipt in front of every signature** — built from the *real* transaction, not
from what the app claims.

## The two ways a transaction reaches ClearSign
1. **From another dApp** (the "phone as a Ledger" path). A dApp starts a
   `solana-wallet://` association; Android routes it to
   `MobileWalletAdapterActivity`. ClearSign is a full **Mobile Wallet Adapter**
   wallet endpoint: it answers authorize / sign / signAndSend / signMessages /
   sign-in (SIWS).
2. **From ClearSign itself** — Send, Swap (Jupiter), revoke an approval, close a
   dust account, unlock Pro. Same receipt, same Seed Vault signature.

## From raw bytes to a receipt (the core)
For every request, `ReceiptEngine.analyze` runs a concurrent pipeline:
1. **Decode** the transaction on-device (`SolanaTx`): legacy + v0, including
   address-lookup-table references.
2. **Resolve lookup tables** over RPC so v0 swaps (e.g. Jupiter) reveal every
   account they touch.
3. **Simulate** on the cluster (`SolanaRpc.simulateEffects`): the network runs
   the transaction and reports the *real* balance changes — for your wallet and
   for every destination — so a payment that secretly splits across several
   wallets (fees, referrals, rent) is shown, not hidden.
4. **Assess risk** (`:core` `RiskEngine`, pure Kotlin, unit-tested): unlimited
   approvals, authority changes, wallet takeover (`Assign`), drains, foreign fee
   payer, address-poisoning look-alikes, brand-new recipients, excessive priority
   fees, and transactions that need signatures other than yours. A DANGER risk
   **blocks one-tap approval**.
5. **Enrich**: counterparty history/age (`walletIntel`), community reputation,
   a blocklist scan, and — for unknown programs — the method name decoded from
   the program's on-chain **Anchor IDL** (`AnchorIdl`).
6. **Build the receipt** (`ReceiptBuilder`): what leaves, to whom, the fee, the
   split map, the risks.

The result is `SignReceiptBody` — the amount hero, the risk cards, the node map
of where the money goes, the details, and the stats. Themes (Halo / Aurora /
Ember / Phosphor) restyle it into a glass card, a paper receipt, or a terminal.

## Signing (and why the earlier bug mattered)
When you hold-to-sign, ClearSign hands the **transaction message** (the bytes
after the signature array, `SolanaTx.messageBytes`) to the **Seed Vault**, which
signs with your biometric; the signature is spliced back with
`SolanaTx.attachSignature`. Passing the *whole* serialized transaction instead of
the message is exactly what produced "invalid signature" and is now fixed.

Right before signing, `driftGuard` **re-simulates** and aborts if the outcome
drifted from the receipt you approved (anti-TOCTOU).

## The receipt after signing (the ledger)
Every approved transaction is recorded in a structured **ledger**
(`Ledger`, monthly JSONL in app storage): the real legs (mint, amount,
decimals), fee, counterparties, risks, and a **fiat snapshot** (SOL price in
EUR/USD at signing, via CoinGecko). The Receipts tab groups them by day, totals
the period in your currency, and exports **Koinly / CoinTracker CSV, PDF, or a
JSON bundle** — ready for taxes.

## Attested receipts (unique)
At approval, a hardware key of the app (Android Keystore, StrongBox when
available) signs a canonical statement of what was shown — payload hashes, the
lines, the risks, the signer. That ES256 signature is stored with the entry and
**exportable**: independently-verifiable proof of exactly what your wallet told
you. No other wallet does this.

## Wallet Health
The Wallet tab shows a live **security score** computed from your token accounts:
live approvals (an unlimited one is the worst), reclaimable dust, frozen
accounts. Every issue has a one-tap fix (revoke / close) right below — each fix
is itself a clear-signed transaction.

## Swap (revenue, the honest way)
Swap uses **Jupiter**: a quote, then a ready-to-sign transaction. ClearSign never
signs it blind — the swap goes through the same receipt. A small **0.5% platform
fee** is routed to the project's fee wallet and shown **honestly** as one of the
destinations in the split map ("ClearSign fee 0.5%"). Never a fee on the
signature itself.

## SKR
**ClearSign Pro** (premium themes, background Watchtower alerts, deep address
scans, unlimited exports) is unlocked with a one-time **SKR** payment on mainnet,
signed through the same clear receipt. Safety is always free; only power features
are paid.

## Store & counterparty intelligence
When a native dApp opens ClearSign, the identity pill shows where it came from
(dApp Store / Play / sideload), its version and update dates (from the phone),
and its **dApp Store reputation** — rating, reviews, verified publisher — from
the free Seeker Tracker catalog, or "not on the dApp Store" as a caution. The
expensive deep **address scan** (activity, exchange funding, funnel patterns) is
a Pro, on-demand action so it never burns the RPC quota on its own.

## Architecture
The safety brain (`:core`) depends only on interfaces (`Simulator`,
`TransactionDecoder`, `TransactionScanner`, `HardwareSigner`), so all the
risk/receipt logic is unit-tested off-device. `:app` supplies the real
implementations (Seed Vault, MWA, RPC). `:testdapp` is an on-device attacker used
to exercise every defense. Secrets live only in `local.properties` (git-ignored).
