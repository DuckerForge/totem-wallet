#!/usr/bin/env bash
# Agent Gate demo: build a real mainnet transaction and hand it to Omni with a
# declared intent — honest or lying. Nothing is sent (send=0); Apex simulates,
# IntentGuard compares claim vs effect, you still hold-to-sign with biometrics.
#
#   scripts/agent-demo.sh honest [ADB_TARGET] [WALLET]
#   scripts/agent-demo.sh liar   [ADB_TARGET] [WALLET]
set -euo pipefail
MODE="${1:-honest}"
TARGET="${2:-}"
WALLET="${3:-DEM0ownerWa11etF0rTests0n1yNotARea1Key111jQ}"
ADB="${ADB:-/home/oliver/Android/Sdk/platform-tools/adb}"
[ -n "$TARGET" ] && ADB="$ADB -s $TARGET"

URL=$(MODE="$MODE" WALLET="$WALLET" python3 - <<'PY'
import json, os, struct, base64, urllib.parse, urllib.request
A='123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz'
def b58d(s):
    n=0
    for c in s: n=n*58+A.index(c)
    b=n.to_bytes((n.bit_length()+7)//8,'big')
    return b'\0'*(len(s)-len(s.lstrip('1')))+b
def b58e(b):
    n=int.from_bytes(b,'big'); out=''
    while n: n,r=divmod(n,58); out=A[r]+out
    return '1'*(len(b)-len(b.lstrip(b'\0')))+out
def sv(n):
    out=b''
    while True:
        x=n&0x7f; n>>=7
        out+=bytes([x|0x80]) if n else bytes([x])
        if not n: break
    return out
req=urllib.request.Request('https://api.mainnet-beta.solana.com',
    data=json.dumps({"jsonrpc":"2.0","id":1,"method":"getLatestBlockhash","params":[{"commitment":"finalized"}]}).encode(),
    headers={'Content-Type':'application/json'})
bh=json.loads(urllib.request.urlopen(req,timeout=15).read())['result']['value']['blockhash']
wallet=os.environ['WALLET']; mode=os.environ['MODE']
owner=b58d(wallet); recipient=bytes(range(1,33)); system=b'\x00'*32
data=struct.pack('<I',2)+struct.pack('<Q',1000)   # SystemProgram::Transfer 1000 lamports
msg=bytes([1,0,1])+sv(3)+owner+recipient+system+b58d(bh)+sv(1)+bytes([2])+sv(2)+bytes([0,1])+sv(len(data))+data
tx=sv(1)+b'\x00'*64+msg
b64=base64.urlsafe_b64encode(tx).decode()
rcp=b58e(recipient)
if mode=='liar':
    intent={"action":"swap","outMint":"SOL","outAmount":0.000001,"inMint":"USDC","inAmount":0.0001,
            "agent":"Demo Agent","reason":"Dichiara uno swap, ma la transazione e un invio"}
else:
    intent={"action":"transfer","outMint":"SOL","outAmount":0.000001,"to":rcp,
            "agent":"Demo Agent","reason":"Ribilancio: micro-invio dichiarato correttamente"}
print("apex://agent/sign?tx="+urllib.parse.quote(b64,safe='')
      +"&intent="+urllib.parse.quote(json.dumps(intent),safe='')
      +"&account="+wallet+"&send=0")
PY
)
echo "mode: $MODE"
# Single-quote the URL for the *remote* shell too, or & splits the command.
$ADB shell "am start -a android.intent.action.VIEW -d '$URL'"
