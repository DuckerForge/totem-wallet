#!/usr/bin/env python3
"""Cosa tengono i Seeker attivi, con la mediana e non solo la media.

La colonna del censimento mostrava la media fra chi ha una moneta, e su questa
gente la media mente: poche balene la tirano su per tutti. Misurato oggi sugli
SKR in staking, media 39.962 e mediana 11.381, tre volte e mezzo di distanza.
Quindi si misurano tutte e due e si mostra la mediana, che è il portafoglio di
mezzo, cioè quello che una persona immagina quando legge "a testa".

Legge i conti token dei portafogli censiti, prezza con Jupiter, e scrive
`rows` dentro `app/src/main/assets/seeker_holdings.json` lasciando intatto il
resto del file.

Uso:  python3 tools/seeker-worker/holdings.py --dry     stampa e non scrive
      python3 tools/seeker-worker/holdings.py           scrive
      python3 tools/seeker-worker/holdings.py --limit 300   una prova corta

La RPC arriva da `local.properties` e non si stampa mai.
"""
import json, os, statistics, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
TOKEN22 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
# Un conto vuoto non è un possesso, e sotto un centesimo è polvere che sposta
# le percentuali senza dire niente.
DUST_USD = 0.01


def rpc_url() -> str:
    p = os.path.join(ROOT, 'local.properties')
    for key in ('clearsign.scanRpcUrl=', 'clearsign.heliusRpcUrl='):
        if os.path.exists(p):
            for line in open(p):
                if line.startswith(key):
                    u = line.split('=', 1)[1].strip()
                    if u:
                        return u
    return 'https://api.mainnet-beta.solana.com'


def call(method, params, tries=3):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    for attempt in range(tries):
        try:
            req = urllib.request.Request(rpc_url(), body, {"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=40) as r:
                o = json.loads(r.read())
            if 'error' in o:
                raise RuntimeError(str(o['error'])[:120])
            return o['result']
        except Exception as e:
            if attempt == tries - 1:
                return None
            time.sleep(1.5 * (attempt + 1))
    return None


def roster():
    out = []
    for line in open(os.path.join(ROOT, 'app/src/main/assets/seekers.txt')):
        line = line.strip()
        if not line or line[0] == '#':
            continue
        p = line.split()
        if len(p) >= 3 and len(p[0]) >= 32:
            out.append(p[0])
    return out


def prices(mints):
    """Jupiter prices, fifty at a time. Missing means we cannot value it."""
    out = {}
    mints = list(mints)
    for i in range(0, len(mints), 50):
        chunk = mints[i:i + 50]
        url = "https://lite-api.jup.ag/price/v3?ids=" + ",".join(chunk)
        try:
            req = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "Apex/1.0"})
            with urllib.request.urlopen(req, timeout=25) as r:
                d = json.loads(r.read())
            for m, v in d.items():
                p = v.get('usdPrice')
                if p:
                    out[m] = float(p)
        except Exception:
            pass
        time.sleep(0.2)
    return out


def symbols(mints):
    out = {}
    mints = list(mints)
    for i in range(0, len(mints), 100):
        chunk = mints[i:i + 100]
        try:
            url = "https://lite-api.jup.ag/tokens/v2/search?query=" + ",".join(chunk)
            req = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "Apex/1.0"})
            with urllib.request.urlopen(req, timeout=25) as r:
                for t in json.loads(r.read()):
                    if t.get('id') and t.get('symbol'):
                        out[t['id']] = t['symbol']
        except Exception:
            pass
        time.sleep(0.2)
    return out


def main():
    dry = '--dry' in sys.argv
    limit = None
    if '--limit' in sys.argv:
        limit = int(sys.argv[sys.argv.index('--limit') + 1])

    wallets = roster()
    if limit:
        wallets = wallets[:limit]
    print(f"portafogli da leggere: {len(wallets):,}")

    # mint -> lista delle quantità, una per portafoglio che ce l'ha
    held = {}
    with_tokens = 0
    read = 0
    for w in wallets:
        mine = {}
        for prog in (TOKEN, TOKEN22):
            res = call("getTokenAccountsByOwner", [w, {"programId": prog}, {"encoding": "jsonParsed"}])
            for a in (res or {}).get('value', []):
                info = a['account']['data']['parsed']['info']
                amt = info.get('tokenAmount', {})
                ui = amt.get('uiAmount')
                if ui:
                    mine[info['mint']] = mine.get(info['mint'], 0.0) + float(ui)
        read += 1
        if mine:
            with_tokens += 1
            for m, q in mine.items():
                held.setdefault(m, []).append(q)
        if read % 100 == 0:
            print(f"  {read:,}/{len(wallets):,}  monete distinte finora: {len(held):,}", flush=True)

    # Solo le monete che almeno venti portafogli hanno: sotto quella soglia una
    # mediana è rumore e la riga direbbe più di quello che sa.
    keep = {m: q for m, q in held.items() if len(q) >= 20}
    print(f"\nletti {read:,}, con token {with_tokens:,}, monete tenute da almeno venti: {len(keep):,}")

    px = prices(keep.keys())
    syms = symbols(keep.keys())
    rows = []
    for m, qty in keep.items():
        p = px.get(m, 0.0)
        vals = sorted(x * p for x in qty)
        rows.append({
            "s": syms.get(m, m[:6]),
            "m": m,
            "p": round(100.0 * len(qty) / max(1, with_tokens), 2),
            # La mediana è quello che mostra la scheda, la media resta per chi
            # vuole vedere quanto è storta la distribuzione.
            "u": round(statistics.median(vals), 2),
            "avg": round(statistics.fmean(vals), 2),
            "n": len(qty),
        })
    rows.sort(key=lambda r: -r['p'])
    rows = rows[:34]

    for r in rows[:12]:
        skew = (r['avg'] / r['u']) if r['u'] > 0 else 0
        print(f"  {r['s']:12s} {r['p']:5.1f}%  tipico ${r['u']:>10,.2f}  media ${r['avg']:>10,.2f}  storta x{skew:.1f}")

    if dry:
        return
    hp = os.path.join(ROOT, 'app/src/main/assets/seeker_holdings.json')
    o = json.load(open(hp))
    o['rows'] = rows
    o['sample'] = read
    o['withTokens'] = with_tokens
    o['at'] = time.strftime('%Y-%m-%d')
    json.dump(o, open(hp, 'w'), separators=(',', ':'))
    print("\nscritto in app/src/main/assets/seeker_holdings.json")


if __name__ == '__main__':
    main()
