# ClearSign — What You See Is What You Sign

A hardware-secured signer for the **Solana Seeker**: no signature ever happens
without a **plain-language receipt** showing exactly *how much* leaves your
wallet, *to whom*, *to which address* (with a trust badge), and the fee. Tap OK
→ the Seed Vault re-simulates in that instant (anti‑TOCTOU) and signs with
biometrics. Works both as a wallet and as the signing device for external
dApps/desktops via Mobile Wallet Adapter — the "phone as a Ledger".

It exists to kill the #1 cause of crypto losses: **blind signing** (wallet
drainers, malicious dApps, address‑poisoning look‑alikes, unlimited approvals).

## Status (2026-09-11)
- ✅ **`:core`** — pure-Kotlin clear-signing engine, device/network independent, **32 unit tests green**.
  `AddressTrust` (look-alike detection), `RiskEngine` (instruction + effect risks: drains, brand-new recipient, foreign fee payer, owner change, durable nonce, **fee sanity vs network median**), `SimulationGuard` (anti-TOCTOU with 1 % tolerance), `ReceiptBuilder` + `Localization` (EN/IT/ES), `Ports` + `ClearSignFlow`.
- ✅ **`:app`** — Jetpack Compose wallet for the Seeker, **18 JVM tests green**, installed on device:
  - **MWA endpoint** ("phone as a Ledger"): every sign / signAndSend / signMessages / SIWS request gets a plain-language receipt built from the real bytes: simulation (v0 + lookup tables), split map of every destination, wallet intel, community reputation, blocklist scan, decoded **Anchor IDL calls**, STATS. Hold-to-sign with the Seed Vault; a DANGER risk blocks the whole bundle; re-simulation right before signing.
  - **Wallet features**: Send (same receipt as a dApp request; paste / QR scan / contacts), Receive (QR), **Delegations & accounts** (revoke approvals, close empty accounts and reclaim rent), signature log with **attested receipts** (hardware-key-signed proof of what was shown), trusted contacts, dApp memory ("3rd signature · since Sep 10").
  - **Themes**: Halo (free) + Aurora · Ember · Phosphor, unlocked with a real **SKR payment** signed by the Seed Vault (treasury wallet in `local.properties`, key `clearsign.skrTreasury`).
  - Localized EN/IT (per-app language on Android 13+), hand-drawn icon set, haptics, R8 release build ≈ 3 MB.
- ⏳ **Reputation program** (`reputation/`, Anchor) compiles; not deployed yet (devnet funding).
- 🧪 **`:testdapp`** — scenarios that exercise every flow (bundles, burn address, drain-all, assign wallet, gasless, priority fee, approvals).

See `PROGRESS.md` for the detailed changelog and what to verify on device.

## Run the tests / build
```bash
export JAVA_HOME=/home/oliver/Applications/android-studio/jbr
/home/oliver/gradle/gradle-8.11.1/bin/gradle :core:test :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
(JBR 21 is used because Gradle 8.11.1 does not support the system's JDK 25.)

## Architecture
The safety brain (`:core`) depends only on interfaces (`Simulator`,
`TransactionDecoder`, `TransactionScanner`, `HardwareSigner`). The Android app
supplies the real implementations, so the entire risk/receipt logic is testable
off‑device. See `core/src/main/kotlin/com/clearsign/core/Ports.kt`.

## On-device next steps (verify first — noted as project risks)
1. **Seed Vault SDK**: confirm the API for implementing an MWA *wallet* endpoint (and whether secp256k1 signing is possible → the multi‑chain stretch). Seed Vault is Solana/ed25519, Android‑only.
2. **Blockaid Solana** API access/quota (fallback: GoPlus / self‑hosted heuristics).
3. **Re‑simulation latency** at signing must stay instant.

## Hackathon demo (3 min)
1. External dApp swap → readable receipt → sign.
2. Scam tx (drainer / unlimited approval / **poisoned look‑alike address**) → blocked with a warning.
3. Seeker signs for a **desktop** dApp over QR — the phone as a Ledger.
