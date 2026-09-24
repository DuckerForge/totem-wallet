"""
La voce, sintetizzata, una traccia per battuta.

La scaletta ha gia' le frasi in inglese, parola per parola. Qui si leggono ad
alta voce con Piper, che gira su questa macchina e non manda niente a nessuno,
e si misura quanto durano. **Quella misura e' il montaggio**: una scena dura
quanto la sua frase piu' un respiro, non quanto diceva un numero scritto a mano
tre giorni fa. Prima era il contrario, e la voce o restava indietro o finiva su
un'immagine gia' cambiata.

La voce e' `en_US-lessac-medium`, scaricata una volta in
`~/.local/share/piper-voices`. `--length-scale` sopra 1 rallenta: 1.06 perche'
a velocita' piena una frase tecnica non si segue.

Uso:
    python3 scripts/video/voice.py           genera i wav e stampa le durate
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import beats

HERE = Path(__file__).resolve().parent
OUT = HERE / "voice"
MODEL = Path.home() / ".local/share/piper-voices/en_US-lessac-medium.onnx"

# Il respiro dopo la frase: senza, la scena stacca sull'ultima sillaba.
TAIL = 0.65
# Quanto dura una scena muta, tipo la porta: non e' zero, e' un'immagine da guardare.
SILENT = 3.0


def duration(path: Path) -> float:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=nw=1:nk=1", str(path)],
        capture_output=True, text=True,
    ).stdout.strip()
    # ffprobe risponde "N/A" per un file che non ha una durata scritta, e quel testo
    # buttava giu' tutto il montaggio con un errore che non diceva quale file fosse.
    return float(out) if out.replace(".", "", 1).isdigit() else 0.0


def say(text: str, out: Path, speed: float = 1.06) -> Path:
    OUT.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["piper", "-m", str(MODEL), "-f", str(out), "--length-scale", str(speed),
         "--sentence-silence", "0.35"],
        input=text, text=True, capture_output=True, check=True,
    )
    return out


def track(key: str, text: str | None) -> tuple[Path | None, float]:
    """Il wav della battuta e quanto deve durare la scena. Muta: nessun wav."""
    if not text:
        return None, SILENT
    wav = OUT / f"{key}.wav"
    if not wav.exists():
        say(text, wav)
    return wav, duration(wav) + TAIL


def all_tracks() -> dict[str, tuple[Path | None, float]]:
    return {b.key: track(b.key, b.text) for b in beats.SCRIPT}


def main() -> int:
    if not MODEL.exists():
        print("manca la voce:", MODEL)
        print("si scarica con: python3 -c \"from pathlib import Path;"
              " from piper.download_voices import download_voice;"
              " download_voice('en_US-lessac-medium', Path.home()/'.local/share/piper-voices')\"")
        return 1
    total = 0.0
    for b in beats.SCRIPT:
        wav, seconds = track(b.key, b.text)
        total += seconds
        mark = "" if wav else "   (muta)"
        print(f"{b.key:<9} {seconds:>5.1f}s{mark}")
    m, s = divmod(int(total), 60)
    print(f"\nparlato {m}:{s:02d}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
