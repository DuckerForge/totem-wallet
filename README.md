# ClearSign — What you see is what you sign

**A safety layer for the Solana Seeker. Every signature comes with a
plain-language receipt built from the *real* transaction — how much leaves your
wallet, to whom, at which address (with a trust badge), the fee, and any risk —
so you never blind-sign again.**

Built for **Clock In · Solana Mobile Hackathon**. Android-native (Jetpack
Compose), integrates the **Solana Mobile Stack** and **Mobile Wallet Adapter**,
and signs with the **Seed Vault**.

---

## The problem

The single biggest cause of crypto losses is **blind signing**: you tap
"Approve" without seeing what you actually authorize. On the Seeker, an app that
talks straight to the signing service can obtain a signature over a transaction
that empties your wallet while showing you only its own name — no amount, no
recipient, no warning. An *unlimited approval* hidden behind a friendly label is
a standing drain right the attacker reuses until you revoke it.

## What ClearSign does

Every request — from an external dApp over Mobile Wallet Adapter, or from the
wallet's own Send flow — is turned into a receipt you can read **before** the
Seed Vault biometric:

- **Real effects, not claims.** The transaction is simulated on-chain (v0 +
  address-lookup-tables + SPL programs), so the receipt shows the true balance
  changes, including hidden splits to fee/referral wallets.
- **A risk engine** that flags drains, unlimited approvals, authority changes,
  wallet-takeover (`Assign`), foreign fee payers, address-poisoning look-alikes,
  brand-new recipients, excessive priority fees, and **transactions that need
  signatures other than yours**. A DANGER risk blocks one-tap approval.
- **Anti-TOCTOU:** the transaction is re-simulated in the instant before signing
  and aborted if the outcome drifted from what you saw.
- **Attested receipts:** each approved receipt is signed by a hardware key of
  the app (Android Keystore, StrongBox when available) — exportable,
  independently-verifiable proof of exactly what you were shown.
- **On-chain decoding:** unknown programs are decoded from their published
  Anchor IDL ("Jupiter v6 · route · in_amount …") instead of opaque bytes.
- **A ledger** of everything you sign, with fiat value at signing time and
  one-tap **CSV (Koinly / CoinTracker) / PDF / JSON** export for taxes.

It works both as a wallet (Send / Receive, revoke approvals, close empty
accounts and reclaim rent) and as the signing device for any dApp via MWA — the
phone as a hardware signer.

## Why it's mobile-first

Seed Vault (hardware-backed keys + biometrics), Mobile Wallet Adapter as the
wallet endpoint, per-app language, haptics, hold-to-sign, and a design system
(Halo) with three additional themes that restyle typography, shape and the whole
**receipt layout** — a glass card, a paper till-receipt, or a green terminal.

## Modules

| Module | What |
|---|---|
| `:core` | Pure-Kotlin clear-signing engine — `RiskEngine`, `AddressTrust` (look-alike detection), `ReceiptBuilder`, `SimulationGuard` (anti-TOCTOU), localized (EN/IT/ES). Device- and network-independent, fully unit-tested. |
| `:app` | Jetpack Compose wallet + MWA endpoint, Seed Vault signer, on-chain simulation (Helius/public RPC), ledger + tax exports, themes, attestation. |
| `:testdapp` | An on-device "attacker" dApp: drain-all, unlimited approve, gasless, bundle, burn-address, unexpected-signer — to exercise every defense live. |
| `reputation/` | An Anchor program for stake-weighted on-chain address reputation (future work). |

## Build & run

Requires Android SDK and a JDK 17–21 toolchain. Configure secrets locally:

```bash
cp local.properties.example local.properties
# set sdk.dir, and optionally clearsign.heliusRpcUrl (blank → public RPC)
```

Then:

```bash
gradle :core:test :app:testDebugUnitTest      # unit tests (core + app, JVM)
gradle :app:assembleRelease                   # → app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

The app runs on the Seeker (or any Android device with the Seed Vault, or the
Seed Vault Simulator). `local.properties` holds machine-local secrets and is
git-ignored — nothing sensitive is committed.

## Architecture

The safety brain (`:core`) depends only on interfaces (`Simulator`,
`TransactionDecoder`, `TransactionScanner`, `HardwareSigner`), so the entire
risk/receipt logic is testable off-device. The Android app supplies the real
implementations. See `core/src/main/kotlin/com/clearsign/core/Ports.kt`.

## SKR

ClearSign Pro (deep address scan, premium themes, background Watchtower alerts,
unlimited exports) is unlocked with a real **SKR** payment on mainnet, signed by
the Seed Vault through the same clear-signing receipt.

## Demo (3 min)

1. A malicious dApp offers a "🎁 free airdrop" that is actually a drain →
   ClearSign shows the real amount and recipient and the risk → **Reject**.
2. An unlimited approval → **DANGER**, one-tap approval is blocked.
3. A transaction that needs another signer → **warning**.
4. A legitimate Send → readable receipt → hold-to-sign with the Seed Vault →
   the entry lands in the ledger with its € value and an exportable attested
   proof.
5. Switch themes live — glass, paper receipt, green terminal.
6. Unlock Pro by paying SKR.
