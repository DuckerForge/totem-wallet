"""
Il video, dall'inizio alla fine, con un comando.

`beats.py` dice cosa va detto e quanto dura, `record.py` lo gira, `cut.py` monta
la singola scena. Questo tiene il filo: per ogni battuta cerca il girato, monta,
e alla fine cuce. Le scene che mancano non fermano niente: si stampa quali sono
e si monta il resto, perche' un video incompleto che si guarda dice molto piu'
di un errore.

Uso:
    python3 scripts/video/make.py                 monta quello che c'e'
    python3 scripts/video/make.py --record        prima gira le scene automatiche
    python3 scripts/video/make.py --tall          verticale invece di 16:9
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import beats
import cut
import record

HERE = Path(__file__).resolve().parent
SHOT = HERE / "shot"
BUILD = HERE / "build"


def duration(path: Path) -> float:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=nw=1:nk=1", str(path)],
        capture_output=True, text=True,
    ).stdout.strip()
    return float(out) if out else 0.0


def main(argv: list[str]) -> int:
    lay = cut.TALL if "--tall" in argv else cut.WIDE
    if "--record" in argv:
        rc = record.main([])
        if rc != 0:
            return rc

    BUILD.mkdir(parents=True, exist_ok=True)
    st = cut.stage(lay, BUILD)

    parts: list[Path] = []
    missing: list[str] = []
    for b in beats.SCRIPT:
        clip = SHOT / f"beat_{b.key}.mp4"
        if not clip.exists():
            missing.append(b.key)
            continue
        have = duration(clip)
        # Non si allunga mai un girato: se la scena e' piu' corta della battuta,
        # dura quanto il girato e il sottotitolo si stringe con lei.
        seconds = min(b.seconds, max(0.8, have - b.start - 0.2))
        out = BUILD / f"scene_{b.key}_{lay.name}.mp4"
        cut.scene(lay, st, clip, seconds, b.text, b.label, out, start=b.start)
        parts.append(out)
        print(f"  {b.key:<9} {seconds:>5.1f}s  (girato {have:.1f}s)")

    if not parts:
        print("nessun girato in", SHOT)
        return 1

    final = BUILD / f"totem_{lay.name}.mp4"
    cut.stitch(parts, final)
    total = duration(final)
    m, s = divmod(int(total), 60)
    print(f"\n{final}   {m}:{s:02d}")
    if missing:
        print("mancano (serve il dito sul sensore):", ", ".join(missing))
        print("si girano con:  python3 scripts/video/record.py --manual <chiave> <secondi>")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
