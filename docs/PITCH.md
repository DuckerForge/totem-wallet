# ClearSign — Pitch Deck

_"What you see is what you sign." A safety layer for the Solana Seeker._
_Clock In · Solana Mobile Hackathon. One-liner + 10 slides + speaker notes._

---

## Slide 1 — Title
**ClearSign**
*What you see is what you sign.*
The Seeker signs blind. ClearSign makes every signature readable.

> Speaker: "Hi — I'm building ClearSign. It turns the Seeker into a wallet where
> you actually see what you're signing, every time."

## Slide 2 — The problem (make it hurt)
Blind signing is the #1 cause of crypto losses. On a phone, an app can ask the
signer to sign a transaction that **empties your wallet** while showing you only
its own name — no amount, no recipient, no warning. An *unlimited approval* is a
permanent drain right the attacker reuses until you revoke it.

> Speaker: "This isn't hypothetical — it's how drainers work today, and the
> hardware signer on the Seeker shows less than a good software wallet does."

## Slide 3 — This is a real Seeker gap
The device's own signing screen can show just an app name and Approve. We
verified it and reported it (Tier-2). **ClearSign is the fix that ships as a
product** — not a patch request, an app you can install today.

> Speaker: "We didn't invent a scary story. We found the gap, reported it, and
> then built the defense."

## Slide 4 — The solution: a receipt before the fingerprint
Every request — from any dApp over **Mobile Wallet Adapter**, or from ClearSign's
own Send — becomes a plain receipt **before** the Seed Vault biometric:
how much leaves, to whom, which address (trust badge), the fee, the risks.

> Speaker: [show the receipt] "One screen. Real numbers. Then you hold to sign."

## Slide 5 — It reads the *truth*, not the claim
- On-chain **simulation** (v0 + lookup tables + SPL) → real balance changes,
  including hidden splits to fee/referral wallets.
- **Risk engine**: drains, unlimited approvals, authority changes, wallet
  takeover, foreign fee payer, address-poisoning look-alikes, excessive fees,
  **"needs another signer"**. DANGER blocks one-tap.
- **Anti-TOCTOU**: re-simulates the instant before signing.

> Speaker: "A 🎁 'free airdrop' that's actually a drain can't hide — the receipt
> shows the real recipient and amount, and we block it."

## Slide 6 — Things no wallet does (X-factor)
- **Attested receipts**: every approval is signed by a hardware key of the app —
  exportable, verifiable proof of exactly what you were shown.
- **On-chain IDL decoding**: "Jupiter v6 · route · in_amount …" instead of opaque bytes.
- **Address activity scan**: what a wallet does, with whom, funded by an exchange?
- **Tax-ready ledger**: fiat value at signing, export to Koinly / CoinTracker / PDF.

> Speaker: "The attested receipt is the one nobody else has — cryptographic
> proof of what the wallet told you, for disputes or your accountant."

## Slide 7 — Live demo (30–40s within the pitch)
Malicious airdrop → blocked. Unlimited approval → DANGER. Legit send → receipt →
hold-to-sign → it lands in the ledger with its € value and an exportable proof.
Then switch themes live — glass, paper receipt, green terminal.

> Speaker: [device on screen] keep it fast, let the app talk.

## Slide 8 — Built for mobile, and for SKR
Seed Vault, MWA endpoint, per-app language, haptics, hold-to-sign, three themes
that restyle the whole receipt. **ClearSign Pro** — deep scans, premium themes,
background Watchtower alerts, unlimited exports — is unlocked with a real **SKR**
payment on mainnet, signed through the same clear receipt.

> Speaker: "SKR isn't bolted on — it buys real utility, paid the safe way."

## Slide 9 — Why it sticks + how it makes money
- **Habit**: it sits in front of every dApp interaction; the ledger + Watchtower
  give a daily reason to open it.
- **Money, honestly**: never a fee on signing. Revenue from swap referral
  (Jupiter), Pro exports/scans (SKR), and a B2B clear-signing SDK. Grants.

> Speaker: "A guardian that charges you to open the door isn't a guardian. We
> monetize the power features, never the safety."

## Slide 10 — Ask / close
Android APK + open repo + this demo. Publishing to the dApp Store next.
**ClearSign: stop signing blind.**

> Speaker: "Everything you saw runs on a real Seeker today. Thanks."

---

### Design notes for the deck
- Dark, Halo palette (mint #4dffd0 → cyan #4cc9ff on #070b12). Big type, few words.
- Use real screenshots (adb) on slides 4–7. One receipt screenshot is the hero.
- 10 slides max; ~20s each; the live demo carries slide 7.
