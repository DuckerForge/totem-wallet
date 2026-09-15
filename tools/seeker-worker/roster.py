#!/usr/bin/env python3
"""Spezza seekers.txt nei pezzi che il worker legge da KV: `rw` (balene con indice) e `rd:<k>` (blocchi di cento delfini).

Uso:  python3 roster.py > /tmp/kv_roster.json
      npx wrangler kv bulk put --binding=SEEKER --remote /tmp/kv_roster.json
"""
import json, sys, os
here = os.path.dirname(os.path.abspath(__file__))
roster = []
for l in open(os.path.join(here, '../../app/src/main/assets/seekers.txt')):
    l = l.strip()
    if not l or l[0] == '#': continue
    p = l.split()
    if len(p) >= 3 and len(p[0]) >= 32: roster.append((p[0], p[1] == 'b'))
whales = [a for a, w in roster if w]; dolphins = [a for a, w in roster if not w]
ordered = whales + dolphins           # l'indice globale: balene prima, poi delfini
idx = {a: i for i, a in enumerate(ordered)}
rw = {"n": len(ordered), "nd": (len(dolphins) + 99) // 100, "w": [[a, idx[a]] for a in whales]}
entries = [{"key": "rw", "value": json.dumps(rw, separators=(',', ':'))}]
for k in range(rw["nd"]):
    chunk = dolphins[k * 100:(k + 1) * 100]
    entries.append({"key": f"rd:{k}", "value": json.dumps([[a, idx[a]] for a in chunk], separators=(',', ':'))})
json.dump(entries, sys.stdout)
print(f"rw: {len(whales)} balene, {rw['nd']} blocchi di delfini, n = {rw['n']}", file=sys.stderr)
