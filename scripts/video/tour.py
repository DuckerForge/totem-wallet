"""
The tour, read aloud: docs/VOICE-TOUR.md, one wav per paragraph, then one file.

Same voice and speed as the demo (voice.py). A pause between paragraphs, because each
one is a new feature and the ear needs to hear the turn.

Usage:
    python3 scripts/video/tour.py        writes scripts/video/voice/tour.wav and prints the times
"""

from __future__ import annotations

import subprocess
from pathlib import Path

from voice import OUT, duration, say

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "docs" / "VOICE-TOUR.md"
GAP = 0.9


def paragraphs() -> list[str]:
    body = SRC.read_text().split("\n---\n", 1)[1]
    return [" ".join(p.split()) for p in body.split("\n\n") if p.strip()]


def main() -> None:
    parts = OUT / "tour"
    parts.mkdir(parents=True, exist_ok=True)
    silence = parts / "gap.wav"
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "lavfi", "-i", "anullsrc=r=22050:cl=mono",
                    "-t", str(GAP), silence], check=True)
    files, at = [], 0.0
    for i, text in enumerate(paragraphs()):
        wav = say(text, parts / f"{i:02d}.wav")
        d = duration(wav)
        print(f"{int(at // 60)}:{at % 60:04.1f}  {d:4.1f}s  {text[:60]}")
        files += [wav, silence]
        at += d + GAP
    listing = parts / "list.txt"
    listing.write_text("".join(f"file '{f}'\n" for f in files[:-1]))
    out = OUT / "tour.wav"
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "concat", "-safe", "0", "-i", listing,
                    "-c", "copy", out], check=True)
    print(f"total {int(duration(out) // 60)}:{duration(out) % 60:04.1f}  ->  {out}")


if __name__ == "__main__":
    main()
