#!/usr/bin/env python3
"""Quanti SKR tengono in staging i Seeker censiti, e quanti di loro lo fanno.

Il censimento legge i conti token dei portafogli. Gli SKR messi dai Guardiani
non stanno lì: stanno dentro il programma di staking, in un conto che appartiene
al programma. Quindi "Cosa tengono" li conta per difetto, e i Seeker che
sembrano fermi in realtà hanno i soldi al lavoro.

Questo script legge il programma una volta sola dal PC, incrocia con il roster
del censimento e scrive i due numeri dentro `app/src/main/assets/seeker_holdings.json`.
Non gira nel Worker: `getProgramAccounts` su tutto il programma è troppo per il
piano gratuito di Cloudflare.

Uso:  python3 tools/seeker-worker/skr_stake.py
      python3 tools/seeker-worker/skr_stake.py --dry   (stampa e non scrive)

La RPC arriva da `local.properties` (chiave `clearsign.heliusRpcUrl`), che non
si stampa mai. Senza quella riga usa la RPC pubblica, che spesso rifiuta
`getProgramAccounts`.
"""
import base64, json, os, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
PROGRAM = "SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ"
CONFIG = "4HQy82s9CHTv1GsYKnANHMiHfhcqesYkK6sB3RDSYyqw"
SKR_MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
DECIMALS = 6
USER_STAKE_LEN = 169          # wallet a 41, pool a 73, quote u128 a 105
SHARE_PRICE_OFF = 137         # nel conto di configurazione, u128, diviso 1e9

B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"


def b58(raw: bytes) -> str:
    n = int.from_bytes(raw, 'big')
    out = ''
    while n:
        n, r = divmod(n, 58)
        out = B58[r] + out
    return '1' * (len(raw) - len(raw.lstrip(b'\0'))) + (out or '1')


def rpc_url() -> str:
    p = os.path.join(ROOT, 'local.properties')
    if os.path.exists(p):
        for line in open(p):
            if line.startswith('clearsign.heliusRpcUrl='):
                u = line.split('=', 1)[1].strip()
                if u:
                    return u
    return 'https://api.mainnet-beta.solana.com'


def call(url: str, method: str, params):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    req = urllib.request.Request(url, body, {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=180) as r:
        o = json.loads(r.read())
    if 'error' in o:
        raise SystemExit("la RPC ha detto no: " + str(o['error'].get('message'))[:200])
    return o['result']


def main():
    dry = '--dry' in sys.argv
    url = rpc_url()

    cfg = call(url, "getAccountInfo", [CONFIG, {"encoding": "base64"}])['value']
    raw = base64.b64decode(cfg['data'][0])
    price = int.from_bytes(raw[SHARE_PRICE_OFF:SHARE_PRICE_OFF + 16], 'little') / 1e9
    if price <= 0:
        raise SystemExit("prezzo della quota a zero: il tracciato del conto è cambiato")

    # Solo i byte che servono: indirizzo, pool, quote. Senza dataSlice la
    # risposta è di decine di megabyte.
    accounts = call(url, "getProgramAccounts", [PROGRAM, {
        "encoding": "base64",
        "filters": [{"dataSize": USER_STAKE_LEN}],
        "dataSlice": {"offset": 41, "length": 80},
    }])

    staked = {}
    for a in accounts:
        raw = base64.b64decode(a['account']['data'][0])
        if len(raw) < 80:
            continue
        wallet = b58(raw[0:32])
        shares = int.from_bytes(raw[64:80], 'little')
        if shares:
            staked[wallet] = staked.get(wallet, 0) + shares * price / 10 ** DECIMALS

    roster = []
    for line in open(os.path.join(ROOT, 'app/src/main/assets/seekers.txt')):
        line = line.strip()
        if not line or line[0] == '#':
            continue
        parts = line.split()
        if len(parts) >= 3 and len(parts[0]) >= 32:
            roster.append(parts[0])

    mine = {w: staked[w] for w in roster if w in staked}
    total_ui = sum(mine.values())
    pct = round(100.0 * len(mine) / max(1, len(roster)), 2)
    avg = round(total_ui / max(1, len(mine)), 2)
    med = round(sorted(mine.values())[len(mine) // 2], 2) if mine else 0.0

    print(f"programma: {len(accounts)} conti di staking, {len(staked)} portafogli")
    print(f"censiti:   {len(mine)} su {len(roster)} tengono SKR in staking ({pct}%)")
    print(f"SKR:       {total_ui:,.0f} in tutto, media {avg:,.0f}, mediana {med:,.0f}")

    hp = os.path.join(ROOT, 'app/src/main/assets/seeker_holdings.json')
    o = json.load(open(hp))
    price_usd = None
    try:
        req = urllib.request.Request("https://lite-api.jup.ag/price/v3?ids=" + SKR_MINT,
                                     headers={"Accept": "application/json", "User-Agent": "Apex/1.0"})
        with urllib.request.urlopen(req, timeout=20) as r:
            price_usd = json.loads(r.read()).get(SKR_MINT, {}).get('usdPrice')
    except Exception:
        pass
    o['skrStaked'] = {"pct": pct, "avg": avg, "median": med, "wallets": len(mine),
                      "usdPer": round(avg * price_usd, 2) if price_usd else None}
    if dry:
        print(json.dumps(o['skrStaked'], indent=2))
        return
    json.dump(o, open(hp, 'w'), separators=(',', ':'))
    print("scritto in app/src/main/assets/seeker_holdings.json")


if __name__ == '__main__':
    main()
