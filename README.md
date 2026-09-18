# Velum Wallet

**A wallet for the Solana Seeker that reads every transaction before you sign it, and an agent that trades a small budget on its own, inside a collar you set.**

Built for **Clock In, the Solana Mobile hackathon** (deadline 8 October 2026). Android native, Jetpack Compose, Seed Vault, Mobile Wallet Adapter. Public page: https://duckerforge.github.io/apex/

> The `apex/` path in the URL, the `web/apex` folder and the `clearsign.*` keys in `local.properties` keep older names on purpose. The page path is baked into verified Android App Links and into gift links already handed out, and the property keys are read by the build. Renaming them breaks working things for nothing.

---

## What it does

**Receipt before signature.** Every transaction, from a dApp over Mobile Wallet Adapter or from the wallet's own Send, Swap, Blink or bridge flow, is simulated on chain and turned into a receipt: what leaves, to whom, the fee, the risks, the address's trust badge, look‑alike detection against your own contacts. Then hold to confirm, then the fingerprint. Nothing is signed blind.

**The agent.** You set a budget apart from your wallet (a session wallet, never your seed). The agent looks for a coin every few minutes on all of Solana, with the gates of the winning bots (mint and freeze authority, holders, liquidity, sellability, honeypot check) plus Rugcheck and Jupiter Shield. It buys a slice, sells at your take profit or stop loss, mirrors the sells of the wallets you follow, recovers rent after every sale, and closes the whole budget when it expires. Every action passes a policy engine: above its limits it stops and asks for your fingerprint.

**Watch it work.** A live screen with the charts of the open positions, the entry, target and stop drawn on them, the agent's reasoning typed as it lands, a voice that says what it does (offline text to speech), a timer ring for the next look, and Sell, Buy more, Sell all, Ask. Notifications carry a picture of where every coin sits between its stop and its target, with their own vibration rhythms.

**Scout.** A census of what Seeker holders buy, computed by a small Cloudflare Worker, so the agent and the Market tab can say "the crowd is buying this" and "the wallet you follow sold this".

**Market.** Favourites with prices, logos and total value, price alerts on 5% moves, a "what if this coin had that coin's market cap" ladder, verified BTC and ETH bridged to Solana, a chart in every sheet.

**Portfolio and DeFi.** Tokens, native stake, SKR staked with the Guardians, Jupiter Lend and Jupiter positions, with the yield per day. Realized P&L per token, and a shareable P&L card.

**Seeker specific.** Pay by touch (NFC host card emulation), write a Solana Pay request on an NFC sticker, exchange contacts by touching two Seekers (signed with the phone's attestation key), proof of payment as a QR signed by the phone, guest mode (cover the screen or long press the balance, fingerprint to come back), a floating companion bubble and a widget you can customise.

**Bridge.** Move SOL or USDC to and from other chains through RocketX, with the receipt before the signature and a plain line on what privacy it does and does not give.

**Blinks.** Open a Solana Action link from X, Discord or a QR: the card, the buttons, the receipt, the signature. Dialect's registry is checked; blocked hosts are refused.

Everything is in English by default and in Italian when the phone is Italian.

## Modules

| Module | What it is |
|---|---|
| `:core` | Pure Kotlin. Risk engine, address trust and look‑alikes, receipt builder, simulation guard, policy engine, NFC tap protocol, contact tap, Scout signals, budget math. Unit tested with kotlin.test. |
| `:app` | The Compose wallet: Seed Vault signer, MWA endpoint, simulation over RPC, agent loop, Jupiter Ultra and Trigger, RocketX, Rugcheck, NFC reader and writer, Blinks, proof, companion service, themes, ledger and exports. |
| `:testdapp` | An on‑device attacker dApp: drain all, unlimited approve, gasless, bundle, burn address, unexpected signer. |
| `tools/seeker-worker` | The Cloudflare Worker behind Scout. |
| `web/apex` | The public page, mirrored on GitHub Pages. |

## Build and run

Android SDK and a JDK 17 to 21.

```bash
cp local.properties.example local.properties
# sdk.dir, and optionally:
#   clearsign.heliusRpcUrl   (blank means the public RPC)
#   clearsign.rocketxKey     (the RocketX partner key, for the bridge)
#   clearsign.jupReferral    (a Jupiter referral account, for the Ultra fee)
```

```bash
gradle :core:test :app:testDebugUnitTest      # unit tests, JVM
gradle :app:assembleDebug                     # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` is git ignored. Nothing secret is in the repository. The agent's session wallet lives in the app's private storage and never sees the seed.

## Security in one paragraph

The user's keys stay in the Seed Vault. The agent has its own small wallet, funded from a receipt the user signed, and every move it makes goes through the same simulation and the same policy engine as a human signature: amount caps, allowed programs, allowed destinations, a kill switch, a budget expiry that closes everything and sends the money back. What the model says is advice; what the policy engine says is law. Unknown never blocks a coin check; only a clear "no" does, and the trace says which one.

## Documentation

`docs/` holds the internal notes: how the agent gate works, the demo script, the hackathon plan, the pitch. Most are in Italian.
