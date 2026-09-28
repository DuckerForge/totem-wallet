"""
The tour as a film: the recorded voice (ElevenLabs), one scene per paragraph, music under.

Unlike make.py, time is not decided here: the voice is already spoken, in one take. Each
scene lasts exactly as long as its paragraph in that take, so the picture changes when the
voice changes subject. The boundaries below were read from a word-level transcription of
the take (faster-whisper, small.en): a new take means new numbers.

Footage is `shot/tour_<key>.mp4`, recorded with `--record` (the phone must be unlocked and
the app open past the fingerprint). A scene with no footage falls back to the previous one
frozen, so a missing clip shows as a still, not as a hole.

Usage:
    TOTEM_VOICE=take.mp3 TOTEM_MUSIC=song.mp3 python3 scripts/video/tour_video.py
    python3 scripts/video/tour_video.py --record [keys...]
"""

from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path

import cards
import cut
import make
import record
import voice
import words
from record import TABS, TAB_Y

HERE = Path(__file__).resolve().parent
SHOT = HERE / "shot"
BUILD = HERE / "build" / "tour"
VOICE = Path(os.environ.get("TOTEM_VOICE", ""))
MUSIC = Path(os.environ.get("TOTEM_MUSIC", ""))
MUSIC_LEVEL = 0.11     # low and steady: no ducking, it pumped with every sentence
# The picture turns this much before the voice does: seeing it first, then hearing it,
# reads as the film knowing where it goes; the other way round reads as late.
LEAD = 1.2
TAKE = 224.3

FRESH = [("launch",), ("wait", 1.5), ("close",), ("wait", 1.2)]


def steps_home() -> list[tuple]:
    # The home as it is: no tab tap (it scrolls to the top, and that showed as a bounce),
    # one slow look down at the portfolio, then still.
    return FRESH + [("wait", 4.0),
                    ("swipe", 600, 1900, 600, 1450, 1600), ("wait", 8.0)]


def steps_agent() -> list[tuple]:
    # The budget, for real: the sheet and the receipt, stopping before the fingerprint.
    # The agent running is drawn (drawn.py): it needs a funded budget.
    return FRESH + [("tap", TABS["agent"], TAB_Y), ("wait", 3.0),
                    ("text", "Give the agent a budget"), ("wait", 5.0),
                    ("swipe", 600, 2000, 600, 1300), ("wait", 3.5),
                    ("swipe", 600, 2000, 600, 1300), ("wait", 3.0),
                    ("text", "See the receipt, then pay"), ("wait", 9.0),
                    ("swipe", 600, 1900, 600, 1400), ("wait", 5.0)]


def steps_watch() -> list[tuple]:
    return FRESH + [("tap", TABS["agent"], TAB_Y), ("wait", 2.5),
                    ("seek", "Watch it work", 5), ("wait", 12.0)]


def steps_bubble() -> list[tuple]:
    # Inside the app only: the Android home would show the other apps' icons. The preview
    # is the bubble's own drawing; each choice changes it, and it ends back on Agent.
    return FRESH + [("tap", TABS["wallet"], TAB_Y), ("wait", 1.5),
                    ("text", "Widget"), ("wait", 3.5),
                    ("text", "Budget"), ("wait", 2.0),
                    ("text", "Health"), ("wait", 2.0),
                    ("text", "Total"), ("wait", 2.0),
                    ("text", "Agent"), ("wait", 2.0),
                    ("swipe", 600, 2000, 600, 1200), ("wait", 4.0),
                    ("close",), ("wait", 3.0)]


def steps_bridge() -> list[tuple]:
    # In the voice's order: the chains, the private send, then the swap. The last seconds
    # of a take can go missing, so the scene ends on something that is fine to lose.
    return FRESH + [("tap", TABS["wallet"], TAB_Y), ("wait", 1.5),
                    ("text", "Bridge"), ("wait", 6.0),
                    ("text", "Private, here on Solana"), ("wait", 4.0),
                    ("close",), ("wait", 1.5),
                    ("text", "Swap"), ("wait", 6.0),
                    ("swipe", 600, 1800, 600, 1300), ("wait", 4.0)]


def steps_nfc() -> list[tuple]:
    return FRESH + [("tap", TABS["wallet"], TAB_Y), ("wait", 2.0),
                    ("text", "Receive"), ("wait", 2.5),
                    ("seek", "Tap to be paid", 4), ("wait", 5.0),
                    ("seek", "A sticker", 3), ("wait", 6.0),
                    ("close",), ("wait", 1.5)]


# key, start in the take (s), label, caption, recording (seconds, steps)
SCENES: list[tuple[str, float, str, str, tuple[float, callable] | None]] = [
    ("intro", 0.0, "Totem", "A wallet built for the Solana Seeker.", (16.0, steps_home)),
    ("receipt", 16.8, "01 · receipt before signature", "Simulated on chain before you sign.", record.AUTO["receipt"]),
    ("agent", 48.4, "02 · the agent", "Its own budget. Your limits, your key, your rules.", (30.0, steps_agent)),
    ("watch", 76.7, "03 · watch it work", "Charts, thoughts and a voice, live.", (18.0, steps_watch)),
    ("scout", 92.3, "04 · Scout", "What the other Seekers buy and hold.", record.AUTO["crowd"]),
    ("bubble", 126.4, "05 · bubble and widget", "Always in view, over every app.", (18.0, steps_bubble)),
    ("market", 141.3, "06 · market", "Every coin, and what if it were as big as another.", record.AUTO["market"]),
    ("bridge", 157.6, "07 · bridge and private send", "Over two hundred chains, through RocketX.", (24.0, steps_bridge)),
    ("health", 175.1, "08 · wallet health", "Approvals, rent, forgotten fees.", record.AUTO["health"]),
    ("nfc", 191.9, "09 · NFC", "A sticker on the counter. Paid by touch.", (22.0, steps_nfc)),
    ("ore", 211.6, "10 · ORE", "The agent digs, under the same limits.", record.AUTO["ore"]),
    ("close", 219.2, "", "Receipt before signature.", record.AUTO["close"]),
]



# ---------------------------------------------------------------------------
# Takes timed on the voice. Each prep walks to the screens off camera, reads where every
# button is, puts things back, and returns taps at fixed seconds: the screen changes on
# the word. Seconds are take time; the scene starts at TIMED_START (so L = T - 3).
# ---------------------------------------------------------------------------
TIMED_START = 3.0


def _go(*steps: tuple) -> None:
    for st in steps:
        record.do(st)


def _need(label: str) -> tuple[int, int]:
    at = record.find(label)
    if at is None:
        raise RuntimeError(f"prep: «{label}» is not on screen")
    return at


def prep_receipt() -> list[tuple]:
    # Safe payment while the voice reads the receipt, then each attack as it is named:
    # unlimited approval, the look-alike, the drainer on "a wallet nobody has seen" (its
    # refusal lands on "will not sign"), and the safe one again on "hold to confirm".
    # The row of scenarios scrolls sideways; after each scroll one screen read says where
    # every chip landed, so no tap depends on how far a fling went.
    _go(("launch",), ("wait", 1.0), ("close",), ("wait", 1.0), ("tap", TABS["settings"], TAB_Y), ("wait", 1.5),
        ("text", "Wallet and safety"), ("wait", 2.0), ("text", "TRY AN ATTACK"), ("wait", 2.5))
    y = _need("Safe payment")[1]
    # Away from the edges: a swipe that starts at the edge is Android's back gesture.
    left, right = ("swipe", 1000, y, 250, y), ("swipe", 250, y, 1000, y)
    chips = ("Safe payment", "Wallet drainer", "Look-alike address", "Unlimited approval")
    return [("at", 10.5, *left), ("at", 11.0, *left), ("wait", 0.8), ("scan", *chips),
            ("at", 16.4, "seen", "Unlimited approval"), ("at", 18.1, "seen", "Look-alike address"),
            ("at", 19.0, *right), ("at", 19.5, *right), ("wait", 0.8), ("scan", *chips),
            ("at", 22.0, "seen", "Wallet drainer"), ("at", 28.4, "seen", "Safe payment"),
            ("wait", 10.0)]


def prep_bridge() -> list[tuple]:
    # The chains, the private send on "another address", then the swap on "swaps go out".
    _go(("launch",), ("wait", 1.0), ("close",), ("wait", 1.0), ("tap", TABS["wallet"], TAB_Y), ("wait", 1.5))
    bridge, swap = _need("Bridge"), _need("Swap")
    record.tap(*bridge); time.sleep(3.0)
    private = _need("Private, here on Solana")
    _close_sheets()
    return [("at", 2.0, "xy", *bridge), ("at", 11.4, "xy", *private),
            ("at", 17.0, "key", "back"), ("at", 17.9, "xy", *swap), ("wait", 5.0)]


def prep_nfc() -> list[tuple]:
    # Receive, Tap, the sticker form on "NFC sticker", then this phone on "phone to phone".
    _go(("launch",), ("wait", 1.0), ("close",), ("wait", 1.0), ("tap", TABS["wallet"], TAB_Y), ("wait", 1.5))
    recv = _need("Receive")
    record.tap(*recv); time.sleep(2.5)
    tap_row = _need("Tap")
    record.tap(*tap_row); time.sleep(2.5)
    sticker, phone = _need("A sticker"), _need("This phone")
    _close_sheets()
    return [("at", 1.4, "xy", *recv), ("at", 3.0, "xy", *tap_row), ("at", 4.3, "xy", *sticker),
            ("at", 14.0, "xy", *phone), ("wait", 9.0)]


def _close_sheets() -> None:
    """
    Close what the takes opened, each by its own button. System back only on the tap
    sheet, which has none: anywhere else it can leave the app, and leaving locks it. The
    attack page is told by its offline note, not by its title, which Settings shows too.
    """
    for _ in range(3):
        record.sh("shell", "uiautomator", "dump", "/sdcard/ui.xml", timeout=30)
        xml = record.sh("shell", "cat", "/sdcard/ui.xml", timeout=30)
        if 'Offline demo: no network' in xml and 'text="Back"' in xml:
            record.tap(*record.find("Back"))
        elif 'content-desc="Close"' in xml:
            record.tap(*record.find("Close"))
        elif 'text="Tap to be paid"' in xml:
            record.key("back")
        else:
            return
        time.sleep(1.5)


TIMED = {"receipt": (36.0, prep_receipt), "bridge": (24.0, prep_bridge), "nfc": (26.0, prep_nfc)}


# What the voice says, as it says it: (phrase in the take, app icon, chip label, tint).
# Phrases are matched word by word against the take's own transcript (words.py), from the
# scene's start on; one that is not heard in its scene is reported and left out.
M, R, A, C = cards.MINT, cards.RED, cards.AMBER, cards.CYAN
CUES: dict[str, list[tuple[str, str, str, tuple]]] = {
    "intro": [("transaction really does", "RECEIPT", "what it really does", M),
              ("an agent", "AGENT", "an agent", M),
              ("small budget", "HOURGLASS", "a small budget", M),
              ("other seekers", "SCOUT", "the other Seekers", M)],
    "receipt": [("simulated", "FLASK", "simulated on chain", M),
                ("what leaves", "SEND", "what leaves", M),
                ("comes back", "RECEIVE", "what comes back", M),
                ("who gets it", "PEOPLE", "who gets it", M),
                ("the fee", "COINS", "the fee", M),
                ("unlimited approval", "INFINITY", "unlimited approval", R),
                ("your contacts", "MASK", "a look-alike contact", R),
                ("nobody has seen", "SPARK", "never seen before", A),
                ("will not sign", "BLOCK", "will not sign", R),
                ("hold to confirm", "HOLD", "hold to confirm", M),
                ("fingerprint", "FINGERPRINT", "your fingerprint", M),
                ("key never leaves", "LOCK", "the key never leaves", M)],
    "agent": [("small budget", "HOURGLASS", "its own budget", M),
              ("never your seed", "SHIELD_LOCK", "never your seed", M),
              ("a trade", "COINS", "per trade", M),
              ("a day", "CALENDAR", "per day", M),
              ("ask you", "FINGERPRINT", "above that, it asks", A),
              ("ai key", "KEY", "your AI key", M),
              ("own rules", "PEN", "your own rules", M),
              ("from a file", "NOTE", "from a file", M),
              ("stop the trade", "BLOCK", "they can stop a trade", A),
              ("raise a limit", "LOCK", "never raise a limit", M)],
    "watch": [("live screen", "SCAN", "the live screen", M),
              ("charts", "CHART", "every chart", M),
              ("the entry", "TAG", "entry, target, stop", M),
              ("thinking", "AGENT", "what it is thinking", C),
              ("voice", "MEGAPHONE", "a voice", M)],
    "scout": [("scout", "SCOUT", "Scout", M),
              ("active wallets", "PEOPLE", "10,000+ Seekers", M),
              ("buying right now", "CHART", "buying right now", M),
              ("the whales", "GEM", "the whales", C),
              ("follow a wallet", "STAR", "follow a wallet", M),
              ("tells you", "MEGAPHONE", "told when it buys", M),
              ("copy it", "COPY", "the agent copies", M),
              ("same checks", "SHIELD_LOCK", "same checks", M),
              ("be mirrored", "SWAP", "sells mirrored", M),
              ("the skr", "COINS", "SKR staked", M)],
    "bubble": [("bubble", "AGENT", "a bubble", M),
               ("widget", "WIDGET", "a widget", M),
               ("balance", "WALLET", "your balance", M),
               ("health", "SHIELD_LOCK", "wallet health", M),
               ("sell or stop", "SWAP", "sell or stop", A)],
    "market": [("ranked by size", "CHART", "ranked by size", M),
               ("follow the ones", "STAR", "follow", M),
               ("get told", "MEGAPHONE", "told when they move", M),
               ("arithmetic", "INFO", "plain arithmetic", C),
               ("as big as", "CHART", "as big as that one", M)],
    "bridge": [("sol or usdc", "COINS", "SOL or USDC", M),
               ("200 chains", "BRIDGE", "200+ chains", M),
               ("another address", "MASK", "no line between them", C),
               ("protected", "SHIELD_LOCK", "protected from bots", M)],
    "health": [("one score", "SHIELD_LOCK", "one score", M),
               ("approvals", "INFINITY", "approvals still open", A),
               ("empty accounts", "TRASH", "rent in empty accounts", M),
               ("one signature", "SIGN", "one signature", M),
               ("pool fees", "COINS", "forgotten pool fees", M)],
    "nfc": [("nfc sticker", "NFC", "an NFC sticker", M),
            ("pay by touching", "HOLD", "pay by touch", M),
            ("phone to phone", "NFC", "phone to phone", M),
            ("a proof", "QR", "a signed proof", M),
            ("no network", "CHECK", "no network needed", M)],
    "ore": [("or", "GEM", "ORE", C),
            ("dig", "DOWNLOAD", "it digs", M),
            ("same limits", "LOCK", "same limits", M),
            ("gains home", "WALLET", "gains come home", M)],
}
CHIP_EARLY = 0.2   # a chip lands just before its word, like the scenes do


def chips_for(key: str, begin: float, end: float, ws: list[dict]) -> list[tuple[Path, float]]:
    out = []
    for k, (phrase, icon, label, tint) in enumerate(CUES.get(key, [])):
        t = words.find(ws, phrase, begin)
        if t is None or t >= end:
            print(f"    ({key}: not heard in its scene: «{phrase}»)")
            continue
        png = BUILD / "chips" / f"{key}_{k:02d}.png"
        png.parent.mkdir(parents=True, exist_ok=True)
        cards.chip(icon, label, tint).save(png)
        out.append((png, round(t - begin - CHIP_EARLY, 2)))
    return out


# Where each take gets to the point, read off its frames: before this it is still navigating.
START = {"intro": 4.0, "receipt": 3.0, "agent": 9.0, "scout": 12.0, "bubble": 18.0, "market": 4.0,
         "bridge": 3.0, "health": 8.5, "nfc": 3.0, "ore": 12.5, "close": 9.0}


def shoot(keys: list[str]) -> int:
    print("waiting for Totem open and unlocked")
    if not record.wait_unlocked(600):
        print("the app is locked: unlock it with the fingerprint, then run again")
        return 1
    for key, _, _, _, rec in SCENES:
        if keys and key not in keys or rec is None:
            continue
        seconds, steps = rec
        if key in TIMED:
            seconds, prep = TIMED[key]
            if not record.wait_unlocked(90):
                print(f"  {key}: the app is locked, stopping here")
                return 1
            timed_steps = prep()
            print(f"  recording {key} ({seconds:.0f}s, timed)")
            clip = record.record(f"tour_{key}", seconds, timed_steps)
            clip.replace(SHOT / f"tour_{key}.mp4")
            _close_sheets()
            continue
        # The app locks again on its own: a scene shot at the door is a wasted take.
        if not record.wait_unlocked(90):
            print(f"  {key}: the app is locked, stopping here")
            return 1
        print(f"  recording {key} ({seconds:.0f}s)")
        clip = record.record(f"tour_{key}", seconds, steps())
        clip.replace(SHOT / f"tour_{key}.mp4")
    return 0


def build(lay: cut.Layout) -> Path:
    BUILD.mkdir(parents=True, exist_ok=True)
    st = cut.stage(lay, BUILD)
    fade = make.FADE
    parts, last_clip = [], None
    ws = words.words(VOICE)
    for i, (key, at, label, caption, _) in enumerate(SCENES):
        begin = max(0.0, at - LEAD) if i else 0.0
        end = SCENES[i + 1][1] - LEAD if i + 1 < len(SCENES) else TAKE + 1.5
        # Every scene but the last runs one fade longer: the fade overlaps it with the next,
        # and this keeps each cut where it was put.
        seconds = end - begin + (fade if i + 1 < len(SCENES) else 0.0)
        clip = SHOT / f"tour_{key}.mp4"
        if not clip.exists():
            clip = last_clip
            if clip is None:
                print(f"  {key}: no footage, skipped")
                continue
            print(f"  {key}: no footage, using the previous one")
        last_clip = clip
        start = START.get(key, 2.5)  # past the launch
        out = BUILD / f"scene_{key}_{lay.name}.mp4"
        # A scene is redone only when something it is made of changed: twelve scenes
        # at phone resolution take twenty minutes, and a fix usually touches one.
        chips = chips_for(key, begin, end, ws)
        sig = f"{clip}|{clip.stat().st_mtime}|{start}|{seconds:.3f}|{caption}|{label}|{begin:.3f}"
        sig += "|" + ";".join(f"{c[0].name}@{c[1]}" for c in chips) + f"|{CUES.get(key)}"
        stamp = out.with_suffix(".key")
        if not (out.exists() and stamp.exists() and stamp.read_text() == sig):
            source = make.padded(clip, seconds + start, 0.0, BUILD)
            cut.scene(lay, st, source, seconds, caption, label or None, out, start=start, bg_at=begin, chips=chips)
            stamp.write_text(sig)
        parts.append(out)
        print(f"  {key:<8} {seconds:5.1f}s")
    silent = make.crossfade(parts, BUILD / f"silent_{lay.name}.mp4")
    total = voice.duration(silent)

    mix = BUILD / "mix.m4a"
    subprocess.run([
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-i", str(VOICE), "-stream_loop", "-1", "-i", str(MUSIC),
        "-filter_complex",
        f"[0:a]apad,atrim=0:{total:.2f}[talk];"
        f"[1:a]atrim=0:{total:.2f},volume={MUSIC_LEVEL},afade=t=in:st=0:d=1.5,"
        f"afade=t=out:st={max(0.0, total - 3):.2f}:d=3[music];"
        "[music][talk]amix=inputs=2:normalize=0[out]",
        "-map", "[out]", "-c:a", "aac", "-b:a", "192k", str(mix)], check=True)
    final = BUILD / f"totem_tour_{lay.name}.mp4"
    subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", str(silent), "-i", str(mix),
                    "-map", "0:v", "-map", "1:a", "-c:v", "copy", "-c:a", "copy", "-shortest", str(final)], check=True)
    m, s = divmod(int(voice.duration(final)), 60)
    print(f"\n{final}   {m}:{s:02d}")
    return final


def main(argv: list[str]) -> int:
    if "--record" in argv:
        return shoot([a for a in argv if not a.startswith("--")])
    if not VOICE.exists() or not MUSIC.exists():
        print("set TOTEM_VOICE and TOTEM_MUSIC to the two audio files")
        return 1
    build(cut.TALL if "--tall" in argv else cut.WIDE)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
