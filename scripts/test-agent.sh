#!/usr/bin/env bash
# Apex Agent Gate — the scripted test, in the order a judge should see it.
#
#   scripts/test-agent.sh honest   # the agent tells the truth  -> you can sign
#   scripts/test-agent.sh liar     # the agent lies             -> Apex blocks it
#   scripts/test-agent.sh swap     # a real Jupiter v0 swap, sign-only
#   scripts/test-agent.sh result   # read the outcome off the chain
#
# Delivery: pushed straight to a cabled Seeker when one is attached, and always
# rendered as a QR you can scan off the screen. Nothing moves without your
# fingerprint, and the liar case is forced to sign-only so it cannot spend.
set -euo pipefail
cd "$(dirname "$0")/.."
MODE="${1:-honest}"
WALLET="${APEX_WALLET:-DEM0ownerWa11etF0rTests0n1yNotARea1Key111jQ}"
DEST="${APEX_DEST:-4wBqpZM9xaSheZzJSMawUKKwhdpChKbZ5eu5ky4Vigw}"
AMOUNT="${APEX_AMOUNT:-0.000001}"
export APEX_TARGET="${APEX_TARGET:-adb-SM02E305271773-7jJhXi._adb-tls-connect._tcp}"
export APEX_ADB="${APEX_ADB:-/home/oliver/Android/Sdk/platform-tools/adb}"

python3 scripts/apex_agent/cli.py wallet "$WALLET" mainnet >/dev/null

case "$MODE" in
  honest) python3 scripts/apex_agent/cli.py transfer "$DEST" "$AMOUNT" \
            "Test del gate: invio dichiarato correttamente" --adb ;;
  liar)   python3 scripts/apex_agent/cli.py lie "$DEST" "$AMOUNT" --adb ;;
  swap)   # Sign-only: proves the v0 path, the quote-derived intent and the QR
          # density for a swap, without putting a market order on chain.
          python3 scripts/apex_agent/cli.py swap SOL USDC "${APEX_SWAP:-0.001}" \
            "Test del gate: swap dichiarato dal preventivo" --send0 --adb ;;
  result) ID="${2:?serve il req_id stampato dal passo honest}"
          python3 scripts/apex_agent/cli.py watch "$ID" 120 ;;
  *) echo "uso: $0 honest|liar|result [req_id]"; exit 2 ;;
esac
