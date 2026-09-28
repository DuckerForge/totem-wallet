"""
When each word of the take is said: faster-whisper with word timestamps.

The take is fixed, so the transcript is made once and kept next to the other voice
files (git-ignored), keyed on the file and its modification time. A new take means a
new transcript, by itself.

Usage:
    TOTEM_VOICE=take.mp3 python3 scripts/video/words.py     prints every word with its time
"""

from __future__ import annotations

import json
import os
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent
CACHE = HERE / "voice" / "tour_words.json"


def norm(w: str) -> str:
    return re.sub(r"[^a-z0-9]", "", w.lower())


def words(take: Path) -> list[dict]:
    """[{"w": "fingerprint", "t": 42.31, "end": 42.9}], in order."""
    key = f"{take}|{take.stat().st_mtime}"
    if CACHE.exists():
        data = json.loads(CACHE.read_text())
        if data.get("key") == key:
            return data["words"]
    from faster_whisper import WhisperModel

    model = WhisperModel("small.en", device="cpu", compute_type="int8")
    segments, _ = model.transcribe(str(take), word_timestamps=True, beam_size=5)
    out = [{"w": w.word.strip(), "t": round(w.start, 3), "end": round(w.end, 3)}
           for s in segments for w in s.words]
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    CACHE.write_text(json.dumps({"key": key, "words": out}, indent=0))
    return out


def find(ws: list[dict], phrase: str, after: float) -> float | None:
    """Start of the first occurrence of [phrase] at or after [after] seconds."""
    want = [norm(p) for p in phrase.split()]
    ns = [norm(w["w"]) for w in ws]
    for i in range(len(ws) - len(want) + 1):
        if ws[i]["t"] >= after and ns[i:i + len(want)] == want:
            return ws[i]["t"]
    return None


if __name__ == "__main__":
    for w in words(Path(os.environ["TOTEM_VOICE"])):
        print(f"{w['t']:7.2f}  {w['w']}")
