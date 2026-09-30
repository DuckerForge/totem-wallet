# Security

## Keys

- **Your wallet key never touches the app.** It stays in the Seed Vault. Every signature goes through the Seed Vault prompt, after the receipt and a hold to confirm.
- **The agent's budget key** is made on the phone and is the only key Totem holds. It is sealed with AES-GCM under a non-exportable AndroidKeyStore key, and the sealed blob is a file under `noBackupFilesDir` ([SealedStore.kt](app/src/main/kotlin/com/clearsign/app/SealedStore.kt)). `android:allowBackup="false"`. Budgets made by older builds kept the sealed blob in SharedPreferences; it moves to the file on first read and the old copy is removed only after the file reads back identical.
- **Gift links** use a throwaway key per gift, stored the same way until the gift is claimed or taken back.
- The budget key can only lose what is in the budget. Its limits (per move, per day, silent threshold, allowed destinations, main account untouchable) are enforced on the phone by [AgentPolicy.kt](core/src/main/kotlin/com/clearsign/core/AgentPolicy.kt), not on chain: they protect you from the agent, not from a compromised phone.

## Audit notes, 30 Sep 2026

Triage of the Clock In security audit on commit `255b498`.

| Finding | Status |
|---|---|
| Solana secret key written to SharedPreferences (2) | Fixed. The key was already sealed with the Keystore; it now lives in a no-backup file (see above). |
| Variable written into the page as HTML (`web/apex`) | Fixed. The payment page also wrote the amount from the link as HTML, which the scan did not flag; both now go in as text. |
| `init_if_needed` reinitialization (2), arbitrary CPI | Removed. They were in `reputation/`, an Anchor program never deployed and not used by the app. |
| Dependency vulnerabilities (7) | Came with `reputation/Cargo.lock`, removed with it. |
| Java native deserialization (16) | False positive. No `ObjectInputStream`, `readObject` or other Java serialization exists in the code. |
| Server-side request forgery, `tools/seeker-worker/src/worker.js` | False positive. The fetched URL comes from the worker's own environment, and only an allowlist of read-only RPC methods is forwarded. |
| Exported components without a permission (5) | By design: the launcher, the Mobile Wallet Adapter endpoint, the gift link handler, the agent deep link and the widget must be reachable. The NFC service requires `BIND_NFC_SERVICE`. Every request is simulated first; the main wallet always needs the fingerprint, and the budget signs alone only within its limits. |
| `usesCleartextTraffic="true"` | By design: the optional bridge to an agent on a computer runs over plain http on the local network. Everything else is https. |
| XML external entities (2) | Developer scripts in `scripts/video` that parse the app's own `strings.xml` and `uiautomator` dumps from our own phone, not app code. |

## Reporting

Open an issue on this repository, or use the contact on the project page.
