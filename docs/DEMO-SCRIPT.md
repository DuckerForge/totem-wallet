# ClearSign — 3-minute demo video script

Shot on a real Seeker (rule: must run on device). Screen-record + voiceover.
Keep it fast; the app does the talking. Target ~2:50.

## Setup (before recording)
- Seeker with ClearSign installed (release APK) and Seed Vault set up with a test wallet holding a little SOL + some SKR.
- The attacker dApp: `:testdapp` installed, and/or the Solana Pay bench
  (`~/Scrivania/bounty-hunting/solana-pay-wallet-test`) reachable over LAN/HTTPS.
- Treasury wallet configured (`clearsign.skrTreasury`) so the SKR unlock is real.
- Clean home screen; pick the Halo theme to start.

## Beat sheet

**0:00–0:15 — Hook**
Voice: "This is the Seeker. When an app asks it to sign, it can show you just a
name and Approve. ClearSign fixes that."
Show: app icon → open ClearSign → the onboarding line "See what you sign".

**0:15–0:50 — Attack #1: hidden drain**
Open the attacker dApp, tap "🎁 Free airdrop". It routes to ClearSign.
Voice: "The label says free airdrop. The receipt says the truth."
Show: receipt reveals real recipient + amount + risk; the DANGER card; hold-to-sign
is blocked / you tap **Reject**.

**0:50–1:15 — Attack #2 & #3**
Trigger the unlimited-approval scenario → DANGER "unlimited approval".
Trigger a tx that needs another signer → amber "extra signatures required".
Voice: "Unlimited approvals and unexpected co-signers — flagged before you sign."

**1:15–1:55 — The good path**
From ClearSign, Send 0.001 SOL to a contact.
Voice: "The same receipt for your own payments." → hold-to-sign → Seed Vault
biometric → Done.
Open the **Scontrini/Receipts** tab: the entry with its € value; open it → the
attested proof; tap **Share proof** and **Export → Koinly CSV**.
Voice: "Every signature becomes a receipt with its value — and a signed proof.
Export for your taxes in one tap."

**1:55–2:20 — X-factor: themes**
In Settings, switch theme: Halo → Ember (paper receipt) → Phosphor (green
terminal). Re-open a receipt to show the layout change.
Voice: "One app, three ways to read your money."

**2:20–2:45 — SKR**
Tap a locked Pro feature → pay SKR sheet → hold-to-pay → the SKR transfer signs
through the same clear receipt → Pro unlocked (Watchtower + deep scan on).
Voice: "Pro is unlocked with SKR, on mainnet, signed the safe way."

**2:45–2:55 — Close**
Voice: "ClearSign. Stop signing blind." → logo.

## Capture tips
- `adb exec-out screenrecord --output-format=h264 - > demo.h264` or use the
  Seeker's built-in screen recorder for audio; add voiceover in edit.
- Portrait, 1080p. Keep cuts tight. Show real numbers, never a mock.
