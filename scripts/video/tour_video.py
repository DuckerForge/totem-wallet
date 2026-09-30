"""
The tour as a film: the recorded voice (ElevenLabs), one scene per paragraph, music under.

Unlike make.py, time is not decided here: the voice is already spoken, in one take. Each
scene lasts exactly as long as its paragraph in that take, so the picture changes when the
voice changes subject. The boundaries below were read from a word-level transcription of
the take (faster-whisper, small.en): a new take means new numbers.

Footage is `shot/tour_<key>.mp4`, recorded with `--record` (the phone must be unlocked and
the app open past the fingerprint). A scene with no footage falls back to the previous one
frozen, so a missing clip shows as a still, not as a hole.

Every phone scene is a timed take: its prep walks there off camera and reads every
position, the take taps at fixed seconds. Next to each clip `shot/tour_<key>.offset` keeps
how late the first step showed in the clip, measured after the pull; the build adds it to
START. It is JSON: edit "offset" by hand to correct a bad reading.

Usage:
    TOTEM_VOICE=take.mp3 TOTEM_MUSIC=song.mp3 python3 scripts/video/tour_video.py
    python3 scripts/video/tour_video.py --record all            the whole session, in shooting order
    python3 scripts/video/tour_video.py --record market bridge  only those, still in shooting order
    python3 scripts/video/tour_video.py --dry [all | keys...]   the preps only: what each one finds
    --no-budget   shoots the agent without a funded budget (the fallback)
"""

from __future__ import annotations

import html
import json
import os
import re
import statistics
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
TAKE = 233.1   # the take of 26 Sep with the spare change line (29 Sep) put in after "home."

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
    ("risks", 27.4, "01 · receipt before signature", "Risks first. A severe one is never signed.", (25.0, lambda: [])),
    ("keys", 46.0, "01 · receipt before signature", "The key never leaves the Seed Vault.", (8.0, lambda: [])),
    ("agent", 48.9, "02 · the agent", "Its own budget. Your limits, your key, your rules.", (30.0, steps_agent)),
    ("watch", 76.7, "03 · watch it work", "Charts, thoughts and a voice, live.", (18.0, steps_watch)),
    ("scout", 92.3, "04 · Scout", "What the other Seekers buy and hold.", record.AUTO["crowd"]),
    ("bubble", 126.4, "05 · bubble and widget", "Always in view, over every app.", (18.0, steps_bubble)),
    ("market", 141.3, "06 · market", "Every coin, and what if it were as big as another.", record.AUTO["market"]),
    ("bridge", 158.1, "07 · bridge and private send", "Over two hundred chains, through RocketX.", (24.0, steps_bridge)),
    ("health", 175.6, "08 · wallet health", "Approvals, rent, forgotten fees.", record.AUTO["health"]),
    ("nfc", 191.9, "09 · NFC", "A sticker on the counter. Tap to pay.", (22.0, steps_nfc)),
    ("ore", 211.6, "10 · ORE", "Dig ORE. The same receipt, the same limits.", record.AUTO["ore"]),
    ("spare", 219.7, "11 · spare change", "Every swap puts a little SOL aside.", (14.0, lambda: [])),
    ("close", 228.0, "", "Receipt before signature.", record.AUTO["close"]),
]


# ---------------------------------------------------------------------------
# Takes timed on the voice. Each prep walks to the screens off camera, reads where every
# button is, puts things back, and returns taps at fixed seconds: the screen changes on
# the word. Seconds are take time; the scene starts at TIMED_START, plus the offset the
# pull measured (T1), so film = T + scene begin - START. Nothing here signs: every take
# stops at a receipt or a hold button, and no fingerprint is ever asked mid-take.
# ---------------------------------------------------------------------------
TIMED_START = 3.0
# The shooting order: one session, same clock. watch needs a running agent that holds a coin;
# without one, drawn.py draws it instead.
SESSION = ["intro", "receipt", "risks", "keys", "agent", "watch", "scout", "bubble", "market",
           "bridge", "health", "nfc", "ore", "spare", "close"]

REVIEW = "Review &amp; sign"   # the dump writes the & escaped
SPARE_SHOT = 24_500_000        # what the jar held on 29 Sep before the real swap into stORE
SPARE_BUTTON = f"Put {SPARE_SHOT / 1e9:g} SOL of spare change in stORE"
BUBBLE = (1000, 560)           # where the bubble is parked for the take
NO_BUDGET = False              # --no-budget: the agent's fallback take


def _go(*steps: tuple) -> None:
    for st in steps:
        record.do(st)


def _need(label: str, xml: str | None = None, last: bool = False) -> tuple[int, int]:
    hit = record.find_all(label, xml)
    if not hit:
        raise RuntimeError(f"prep: «{html.unescape(label)}» is not on screen")
    return hit[-1 if last else 0]["xy"]


def _has(xml: str, label: str) -> bool:
    return bool(record.find_all(label, xml))


def _y(label: str, xml: str) -> int | None:
    hit = record.find_all(label, xml)
    return hit[0]["xy"][1] if hit else None


def _see(name: str, value) -> None:
    """What a prep found, said as it finds it: --dry is read from these lines."""
    print(f"    {name:<14} {value}")


def _warn(text: str) -> None:
    print(f"    ! {text}")


def _fresh(tab: str) -> None:
    _go(("launch",), ("wait", 1.0), ("close",), ("wait", 1.0), ("tap", TABS[tab], TAB_Y), ("wait", 1.5))


def _wallet_safety() -> tuple[int, int]:
    """Settings' first drawer, open. It is a switch: tapped while open, it closes."""
    xml = record.dump()
    ws = _need("Wallet and safety", xml)
    if not _has(xml, "TRY AN ATTACK"):
        record.tap(*ws)
        time.sleep(2.0)
    return ws


def _desc_close(xml: str) -> tuple[int, int]:
    """A sheet's own X, by its description."""
    hit = [n for n in record.nodes(xml) if n["desc"] == "Close"]
    if not hit:
        raise RuntimeError("prep: no Close (X) on screen")
    return hit[0]["xy"]


def _review_ready(timeout: float) -> tuple[int, int] | None:
    """The swap's Review button once it can be pressed: it stays disabled until the quote is in."""
    want = html.unescape(REVIEW)

    def ready():
        ns = record.nodes(record.dump())
        return next((n["xy"] for n in ns if n["text"] == want and record.enabled(ns, n)), None)
    return record.wait_for(ready, timeout)


def _bring(what, lo: int, hi: int, tries: int = 8) -> tuple[int, int]:
    """
    Slow drags, never a tap, until [what] sits between y [lo] and [hi]. [what] is a label or
    a function of the dump that returns a position. Not found yet means further down.
    """
    look = what if callable(what) else (lambda xml: next((n["xy"] for n in record.find_all(what, xml)), None))
    mid = (lo + hi) // 2
    for _ in range(tries):
        at = look(record.dump())
        if at and lo <= at[1] <= hi:
            return at
        dy = 700 if at is None else max(-1100, min(1100, at[1] - mid))
        a = 1350 + dy // 2
        record.swipe(600, a, 600, a - dy, 1000)
        time.sleep(1.2)
    raise RuntimeError(f"prep: could not bring «{what if isinstance(what, str) else 'it'}» between y {lo} and {hi}")


def _re_xy(pattern: str):
    """For _bring: the first text matching [pattern]."""
    return lambda xml: next((n["xy"] for _, n in record.find_re(pattern, xml)), None)


# Quick actions at the top of the home, to read how far it scrolled.
HOME_REFS = ("Receive", "Swap", "Bridge", "Scout", "Widget", "Tap", "Send")


def _home_to_grid() -> tuple[tuple[int, int], int]:
    """
    From the top of the home, short slow drags and no tap until the ORE tile ("on the grid")
    is in view. Returns the tile and how far the home scrolled: close drags it back to the
    top, and more than about 450 px past the top is a pull to refresh.
    """
    # Right after Totem starts, the DeFi tiles are still loading from the chain: wait for the
    # ORE tile to exist anywhere on the page before scrolling to it (spare failed without this).
    t0 = time.time()
    if not record.wait_for(lambda: bool(record.find_all("on the grid")), 90.0, every=3.0):
        _warn("the ORE tile did not load in 90 s")
    elif time.time() - t0 > 3:
        _see("DeFi loaded", f"after {time.time() - t0:.0f} s")
    first = record.dump()
    before = {r: _y(r, first) for r in HOME_REFS if _y(r, first) is not None}
    xml, pushed = first, 0
    for _ in range(9):
        tile = [n for n in record.find_all("on the grid", xml) if 300 <= n["xy"][1] <= 2250]
        if tile:
            moved = [before[r] - _y(r, xml) for r in before if _y(r, xml) is not None]
            scrolled = int(statistics.median(moved)) if moved else pushed
            _see("ORE tile", tile[0]["xy"])
            _see("home scrolled", f"{scrolled} px" + ("" if moved else " (estimated)"))
            return tile[0]["xy"], scrolled
        record.swipe(600, 1700, 600, 1400, 900)
        pushed += 300
        time.sleep(1.0)
        xml = record.dump()
    raise RuntimeError("prep: the ORE tile («on the grid») never came into view")


# ---- what the session changes, and puts back once at the end (T3) -------------------
JAR = "shared_prefs/spare_jar.xml"
FOLLOWS = "shared_prefs/apex_follows.xml"
SETTINGS = "shared_prefs/clearsign_settings.xml"
COMPANION = "shared_prefs/apex_companion.xml"
WATCHLIST = "shared_prefs/apex_watchlist.xml"
MISSING = "__none__"
SAVED: dict[str, str | None] = {}   # before the session; None = the file did not exist
UNSAVED: set[str] = set()           # could not be read: a take that changes it does not run
DIRTY: set[str] = set()             # changed by a take or a prep
JAR_SET: list[bool] = []            # the jar is at SPARE_SHOT for the rest of the session
TOUCHES = {"scout": FOLLOWS}        # what a take changes on camera
# What a prep already changes, dry run included: the bubble's face and spot, XRP's star and alerts.
PREP_TOUCHES = {"bubble": COMPANION, "market": WATCHLIST}


def _prefs(path: str) -> str | None:
    """One of Totem's prefs files, or None when it does not exist."""
    out = record.sh("shell", f"run-as {record.PKG} sh -c 'cat {path} 2>/dev/null || echo {MISSING}'", timeout=30)
    if out.strip() == MISSING:
        return None
    if "<map" not in out:
        raise RuntimeError(f"run-as could not read {path}: {out.strip()[:120]}")
    return out


def _prefs_put(path: str, xml: str | None) -> None:
    """
    Write [xml] over a prefs file, or remove it for None. Only with Totem stopped: a running
    app keeps its prefs in memory and would write its own copy back. A stale .bak would
    win over the new file at the next start, so it goes too.
    """
    if xml is None:
        record.sh("shell", f"run-as {record.PKG} rm -f {path} {path}.bak", timeout=30)
        return
    subprocess.run(["adb", "shell", f"run-as {record.PKG} sh -c 'cat > {path} && rm -f {path}.bak'"],
                   input=xml.encode(), check=True, timeout=30)


def _save_session() -> None:
    for path in (JAR, FOLLOWS, COMPANION, WATCHLIST):
        if path in SAVED or path in UNSAVED:
            continue
        try:
            SAVED[path] = _prefs(path)
        except RuntimeError as e:
            UNSAVED.add(path)
            _warn(f"{e}: the takes that change it are skipped")


def _restore_session() -> None:
    """One stop, every changed file back, one fingerprint."""
    if not DIRTY:
        return
    print("— putting back " + ", ".join(sorted(p.rsplit("/", 1)[-1] for p in DIRTY)))
    record.sh("shell", "am", "force-stop", record.PKG)
    for path in sorted(DIRTY):
        _prefs_put(path, SAVED.get(path))
    DIRTY.clear()
    JAR_SET.clear()
    _go(("launch",))
    print("  unlock Totem with the fingerprint")
    if not record.wait_unlocked(180):
        _warn("Totem is still locked")


def _jar_shot() -> None:
    """
    The jar at SPARE_SHOT for spare and close. Once per session: writing it stops Totem, and
    that asks for the fingerprint. It stays there between the two takes.
    """
    if JAR_SET:
        return
    if JAR in UNSAVED:
        raise RuntimeError("prep: the jar could not be saved, so it would not be put back")
    moved = re.search(r'name="moved" value="(\d+)"', SAVED.get(JAR) or "")
    record.sh("shell", "am", "force-stop", record.PKG)
    _prefs_put(JAR, "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
               f'    <long name="moved" value="{moved.group(1) if moved else 0}" />\n'
               f'    <long name="jar" value="{SPARE_SHOT}" />\n    <boolean name="on" value="true" />\n</map>\n')
    DIRTY.add(JAR)
    JAR_SET.append(True)
    _go(("launch",))
    print("  unlock Totem with the fingerprint for the spare change shots")
    if not record.wait_unlocked(180):
        raise RuntimeError("prep: Totem stayed locked")


# ---- the takes, in shooting order --------------------------------------------------------

def prep_intro() -> list[tuple]:
    # Settings, the attack page on "shows you what a transaction", the drainer on "really
    # does", the Agent tab on "It has an agent", then Scout and what the Seekers are buying.
    _fresh("wallet")
    scout = _need("Scout")
    record.tap(*scout)
    time.sleep(3.0)
    buying = _need("Buying")
    record.tap(*buying)
    time.sleep(1.5)
    if "No coin has three separate buyers yet" in record.dump():
        _warn("Buying is empty: the take stays on Live")
        buying = None
    record.tap(*_need("Back"))   # Scout's arrow, by its description
    time.sleep(1.5)
    _go(("tap", TABS["settings"], TAB_Y), ("wait", 1.5))
    ws = _wallet_safety()
    attack = _need("TRY AN ATTACK")
    record.tap(*attack)
    time.sleep(2.5)
    drainer = _need("Wallet drainer")
    record.tap(*drainer)
    time.sleep(1.5)
    xml = record.dump()
    if "Signature blocked: severe risk. The Seed Vault is never even asked." not in xml:
        _warn("the drainer's blocked banner is not on screen")
    # The attack page is a dialog: back closes it. Only when it is certainly open.
    if "Offline demo: no network" in xml:
        record.key("back")
    else:
        record.tap(*_need("Back", xml))
    time.sleep(1.2)
    record.tap(*ws)
    time.sleep(1.0)
    _go(("tap", TABS["wallet"], TAB_Y), ("wait", 2.0))
    for name, at in (("scout", scout), ("buying", buying), ("wallet+safety", ws), ("attack", attack), ("drainer", drainer)):
        _see(name, at)
    steps = [("at", 4.8, "xy", TABS["settings"], TAB_Y), ("at", 5.7, "xy", *ws), ("at", 6.6, "xy", *attack),
             ("at", 7.8, "xy", *drainer), ("at", 9.7, "key", "back"), ("at", 10.5, "xy", TABS["agent"], TAB_Y),
             ("at", 15.4, "xy", TABS["wallet"], TAB_Y), ("at", 16.2, "xy", *scout)]
    if buying:
        steps.append(("at", 17.3, "xy", *buying))
    return steps + [("wait", 4.0)]


def prep_receipt() -> list[tuple]:
    # Take A: a real swap review. "Finding the best route" under "simulated on the chain",
    # the review on "what leaves, what comes back", one slow drag to the flow map and the
    # fee. The hold button is never touched.
    _fresh("wallet")
    _go(("text", "Swap"), ("wait", 3.0), ("text", "25%"))
    review = _review_ready(10)
    if review is None:
        _warn("Review never read as enabled: waiting 4 s more")
        time.sleep(4.0)
        review = _need(REVIEW)
    # Warm-up: one review built off camera, so the take's is quick.
    record.tap(*review)
    if not record.wait_for(lambda: record.find("YOU RECEIVE"), 12):
        _warn("the warm-up review did not show YOU RECEIVE within 12 s")
    record.tap(*_need("Back"))
    time.sleep(2.0)
    review = _review_ready(10) or _need(REVIEW)
    _see("review", review)
    print("    reshoot if the review is not up by clip 8.9")
    return [("at", 4.2, "xy", *review), ("at", 11.0, "swipe", 600, 2000, 600, 800, 2000), ("wait", 6.0)]


CHIPS = ("Safe payment", "Wallet drainer", "Look-alike address", "Unlimited approval")


def _chip_row() -> dict[str, tuple[int, int]]:
    # The topmost match: the receipt below repeats some of these names as risk titles.
    xml = record.dump()
    out = {}
    for c in CHIPS:
        hits = record.find_all(c, xml)
        if hits:
            out[c] = min(hits, key=lambda n: n["xy"][1])["xy"]
    return out


def _chips_fit(at: dict) -> bool:
    return len(at) == len(CHIPS) and all(70 <= p[0] <= 1130 for p in at.values())


def prep_risks() -> list[tuple]:
    # Take B: the offline attack page, the chip row placed once so all four are on screen
    # and it never moves during the take. Opens on Unlimited approval, then the look-alike,
    # the drainer with its blocked banner, and the safe one on "Otherwise". Never held.
    _fresh("settings")
    _wallet_safety()
    _go(("text", "TRY AN ATTACK"), ("wait", 2.5))
    start = _chip_row().get("Safe payment")
    if start is None:
        raise RuntimeError("prep: «Safe payment» is not on screen")
    y = start[1]
    record.swipe(1000, y, 810, y, 1200)   # slow: no fling
    time.sleep(1.0)
    at = _chip_row()
    for _ in range(3):
        if _chips_fit(at):
            break
        left, right = at.get("Safe payment"), at.get("Unlimited approval")
        if (left is None or left[0] < 70) and (right is None or right[0] > 1130):
            raise RuntimeError(f"prep: the four chips do not fit on screen together: {at}")
        if left is None or left[0] < 70:
            d = 150 if left is None else 70 - left[0] + 60
            record.swipe(400, y, 400 + d, y, 1200)
        else:
            d = 150 if right is None else right[0] - 1130 + 60
            record.swipe(1000, y, 1000 - d, y, 1200)
        time.sleep(1.0)
        at = _chip_row()
    if not _chips_fit(at):
        raise RuntimeError(f"prep: could not place the chip row: {at}")
    for chip in ("Wallet drainer", "Look-alike address"):
        record.tap(*at[chip])
        time.sleep(1.5)
        if "Signature blocked" not in record.dump():
            _warn(f"{chip}: no blocked banner on screen")
    record.tap(*at["Unlimited approval"])
    time.sleep(2.0)
    again = _chip_row()
    if _chips_fit(again) and any(abs(again[c][0] - at[c][0]) > 20 for c in CHIPS):
        _warn("the row moved when a chip was tapped: using where it is now")
        at = again
    for c in CHIPS:
        _see(c, at[c])
    return [("at", 7.6, "xy", *at["Look-alike address"]), ("at", 10.9, "xy", *at["Wallet drainer"]),
            ("at", 17.8, "xy", *at["Safe payment"]), ("wait", 6.0)]


HARDWARE = r"^\d+\. Hardware signing$"


def prep_keys() -> list[tuple]:
    # A still on "Keys live in the Seeker's Seed Vault… The app never sees a key."
    _fresh("settings")
    _wallet_safety()
    record.tap(*_bring("Your defenses", 400, 2200))
    time.sleep(2.0)
    _see("hardware", _bring(_re_xy(HARDWARE), 1000, 1600))
    return [("wait", 7.0)]


def _pro_on() -> bool | None:
    try:
        return 'name="agent_pro" value="true"' in (_prefs(SETTINGS) or "")
    except RuntimeError:
        return None


PRO_TAPPED: list[bool] = []   # prep turned Pro on: the cleanup turns it off again


def _ensure_pro() -> None:
    """The Pro block holds Model and key. Turned on if it was off, and back off afterwards."""
    PRO_TAPPED.clear()
    on = _pro_on()
    if on is None:
        _warn("could not read the Pro switch: assuming it is on")
    elif not on:
        record.tap(*_need("Pro"))
        PRO_TAPPED.append(True)
        _see("pro", "was off, turned on for the take")
        time.sleep(1.2)


def _fix_swipe(fix: int) -> tuple[int, int, int, int, int]:
    """A drag that moves the content up by about [fix] px, slow enough not to fling, centred."""
    fix = max(-1400, min(1400, fix))
    a = 1300 + fix // 2
    return 600, a, 600, a - fix, 1600


# The long drag down the rules sheet, slow enough that it moves the content by its length.
RULES_DRAG = (600, 2300, 600, 300, 2200)


def _rules_error(xml: str) -> int | None:
    """How far YOUR OWN RULES and "Load a file" still are from mid-screen (px up); None when fine."""
    ya, yb = _y("YOUR OWN RULES", xml), _y("Load a file", xml)
    if ya is not None and yb is not None:
        err = (ya + yb) // 2 - 1300
        return None if abs(err) <= 150 and ya >= 400 and yb <= 2250 else err
    if ya is not None:
        return ya - 1000
    if yb is not None:
        return yb - 1600
    # Neither: still among the sliders means not far enough.
    return 700 if _has(xml, "Ask me above") or _has(xml, "Per day") else -700


RULES_TOP = "What the agent may sign without you"   # the rules sheet's first lines, and only there
RULES_MARKS = (RULES_TOP, "Ask me above", "Per day", "YOUR OWN RULES", "Load a file")


def _rules_up(rules: tuple[int, int]) -> None:
    record.tap(*rules)
    # Polled: on the first open of a run the sheet took longer than a fixed 1.5 s.
    if not record.wait_for(lambda: RULES_TOP in record.dump(), 5.0):
        raise RuntimeError("prep: the rules sheet did not open")


def _rules_down(xml: str) -> None:
    """
    System back only while the sheet is certainly up; with nothing open it leaves the app. The
    tab bar is in the dump only when no sheet covers the page: then there is nothing to close,
    and a drag down would scroll the page under it (that lost the Rules chip on 29 Sep).
    """
    if 'text="Receipts"' in xml:
        return
    record.key("back")
    time.sleep(1.5)


def prep_agent() -> list[tuple]:
    # With a funded budget: the collar (Per move, Per day) on "a trade / a day", Ask me above
    # on "it has to ask you", the model and key on "Bring your own AI key", then the rules
    # again down to YOUR OWN RULES and "Load a file". Nothing is saved, nothing is signed.
    if NO_BUDGET:
        return prep_agent_nobudget()
    _fresh("agent")
    if _has(record.dump(), "The agent has no budget yet"):
        raise RuntimeError("prep: no budget on the phone. Fund the smallest one, or run with --no-budget")
    _ensure_pro()
    record.swipe(600, 2100, 600, 900, 1000)
    time.sleep(1.2)
    rules = _need("Rules")
    _rules_up(rules)
    record.swipe(600, 2000, 600, 1200, 900)
    time.sleep(1.2)
    xml = record.dump()
    ask = _y("Ask me above", xml)
    if ask is None or not 600 <= ask <= 2200:
        _warn(f"Ask me above is at y {ask}, not between 600 and 2200")
    _rules_down(xml)
    record.swipe(600, 2100, 600, 900, 1000)
    time.sleep(1.2)
    xml = record.dump()
    model = None
    if not _has(xml, "FREE, NO CARD"):
        # A saved key keeps the section closed: the take opens it, as here.
        model = _need("Model and key", xml)
        record.tap(*model)
        time.sleep(1.5)
        if not _has(record.dump(), "FREE, NO CARD"):
            _warn("Model and key opened without FREE, NO CARD in view")
    record.swipe(600, 900, 600, 2100, 700)
    time.sleep(1.2)
    rules2 = _need("Rules")
    # The long drag in the sheet, then one correction tuned on full repeats of the take's
    # own moves, so the take can do exactly what worked here. Slow drags: a fast one flings,
    # and a fling does not land twice in the same place.
    fix, err = 0, None
    for _ in range(3):
        rules2 = _need("Rules")
        _rules_up(rules2)
        record.swipe(*RULES_DRAG)
        time.sleep(0.4)
        if abs(fix) >= 40:
            record.swipe(*_fix_swipe(fix))
        time.sleep(1.5)
        xml = record.dump()
        err = _rules_error(xml)
        _rules_down(xml)
        if err is None:
            break
        fix += err
    if err is not None:
        _warn(f"YOUR OWN RULES and Load a file still {err} px off mid-screen")
    # Back to the top: a tab change starts the page over, with Model and key closed again.
    _go(("tap", TABS["wallet"], TAB_Y), ("wait", 1.0), ("tap", TABS["agent"], TAB_Y), ("wait", 2.0))
    for name, at in (("rules", rules), ("model", model), ("rules2", rules2), ("correction", fix)):
        _see(name, at)
    steps = [("at", 7.3, "swipe", 600, 2100, 600, 900, 1000), ("at", 9.0, "xy", *rules),
             ("at", 12.4, "swipe", 600, 2000, 600, 1200, 900), ("at", 15.3, "key", "back"),
             ("at", 16.0, "swipe", 600, 2100, 600, 900, 1000)]
    if model:
        steps.append(("at", 17.6, "xy", *model))
    steps += [("at", 19.8, "swipe", 600, 900, 600, 2100, 700), ("at", 21.0, "xy", *rules2),
              ("at", 21.8, "swipe", *RULES_DRAG)]
    if abs(fix) >= 40:
        steps.append(("at", 24.6, "swipe", *_fix_swipe(fix)))
    return steps + [("wait", 10.0)]


def prep_agent_nobudget() -> list[tuple]:
    # The fallback: the "no budget yet" card (its budget key never leaves this phone), the
    # new budget sheet unscrolled on "a trade / a day", then Model and key to the end.
    # "See the receipt, then pay" is never tapped.
    _fresh("agent")
    _ensure_pro()
    give = _need("Give the agent a budget")
    record.tap(*give)
    time.sleep(2.0)
    xml = record.dump()
    if not _has(xml, "A new budget"):
        raise RuntimeError("prep: the new budget sheet did not open")
    if "Above its limits it asks for your fingerprint." not in xml:
        _warn("the sheet's first line is not in view")
    record.key("back")
    time.sleep(1.5)
    record.swipe(600, 2100, 600, 900, 1000)
    time.sleep(1.2)
    xml = record.dump()
    model = None if _has(xml, "FREE, NO CARD") else _need("Model and key", xml)
    _go(("tap", TABS["wallet"], TAB_Y), ("wait", 1.0), ("tap", TABS["agent"], TAB_Y), ("wait", 2.0))
    _see("give", give)
    _see("model", model)
    steps = [("at", 9.0, "xy", *give), ("at", 15.3, "key", "back"),
             ("at", 16.0, "swipe", 600, 2100, 600, 900, 1000)]
    if model:
        steps.append(("at", 17.6, "xy", *model))
    return steps + [("wait", 16.0)]


def _after_agent() -> None:
    if PRO_TAPPED:
        _close_sheets()
        _go(("tap", TABS["agent"], TAB_Y), ("wait", 1.5))
        record.tap(*_need("Pro"))
        PRO_TAPPED.clear()


VOICE_WAS: list[bool] = []    # the page's voice before the prep switched it on
WATCH_LOOK = 14.0             # take second of the loop's look: its line types on "line by line"


def _next_look(timeout: float = 100.0) -> float:
    """
    When the loop will look next, in time.monotonic(). It waits for one look in the phone's log
    (TraderLoop writes "Apex-Loop: look" as it starts one), then adds the breath between two:
    ninety seconds after a tick that, holding one coin, takes a fifth of one (measured: 90.17).
    """
    # One shell word: split, date read "%H:%M:%S.000" as a second argument and logcat refused the time.
    since = record.sh("shell", "date '+%m-%d %H:%M:%S.000'").strip()
    log = subprocess.Popen(["adb", "logcat", "-T", since, "-s", "Apex-Loop:I"], stdout=subprocess.PIPE, text=True)
    try:
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            line = log.stdout.readline()
            if "look" in line:
                return time.monotonic() + 90.2
        raise RuntimeError("prep: the loop did not look within 100 s. Is it on?")
    finally:
        log.kill()


def prep_watch() -> list[tuple]:
    # The live page of a running agent that holds a coin: it opens on "watch it work", the chart
    # and its three lines are there for "charts" and "entry, target, stop", the thoughts come up
    # on "what it is thinking", and the loop's own look lands on "line by line", read out by the
    # voice, already on. This prep starts nothing, buys nothing, sells nothing: with the loop off
    # or no coin held it stops, and drawn.py's scene is the one to use. "Look now" and the coin's
    # chips are never tapped: the look is the loop's, timed from its log.
    VOICE_WAS.clear()
    _fresh("agent")
    xml = record.dump()
    if not _has(xml, "Stop the agent"):
        raise RuntimeError("prep: the loop is off. Start it from Agent, or keep drawn.py's scene")
    if not _has(xml, "POSITIONS"):
        raise RuntimeError("prep: the agent holds no coin, so the page would have no chart")
    link = _need("Watch it work", xml)
    record.tap(*link)
    time.sleep(3.0)
    xml = record.dump()
    if not _has(xml, "WHAT IT IS THINKING"):
        raise RuntimeError("prep: the live page did not open")
    VOICE_WAS.append(_has(xml, "Voice on"))
    if not VOICE_WAS[0]:
        record.tap(*_need("Voice off", xml))
        time.sleep(1.0)
        xml = record.dump()
    # The thoughts brought up to mid-screen, when they start below it.
    think = _y("WHAT IT IS THINKING", xml) or 0
    lift = min(1100, think - 900) if think > 1500 else 0
    drag = (600, 2100, 600, 2100 - lift, 1600)
    _close_sheets()
    # A tab change starts the page over at the top, where the link was found.
    _go(("tap", TABS["wallet"], TAB_Y), ("wait", 1.0), ("tap", TABS["agent"], TAB_Y), ("wait", 2.0))
    _see("link", link)
    _see("thoughts", f"y {think}, lift {lift}")
    # The take starts WATCH_LOOK seconds before the loop's next look.
    look = _next_look()
    wait = look - WATCH_LOOK - time.monotonic()
    if wait < 3.0:
        wait += 90.2
    _see("look", f"take starts in {wait:.0f} s")
    time.sleep(wait)
    steps = [("at", 5.9, "xy", *link)]
    if lift:
        steps.append(("at", 13.1, "swipe", *drag))
    # The take lasts as long as its steps: run past the scene's end (18.6) with the voice speaking.
    return steps + [("wait", 15.0)]


def _after_watch() -> None:
    """The voice back as it was, then the page closed."""
    if VOICE_WAS and not VOICE_WAS[0]:
        xml = record.dump()
        if _has(xml, "Voice on"):
            record.tap(*_need("Voice on", xml))
            time.sleep(1.0)
    VOICE_WAS.clear()
    _close_sheets()


ADDRESS = r"^[1-9A-HJ-NP-Za-km-z]{2,8}…[1-9A-HJ-NP-Za-km-z]{2,8}$"
SCOUT_TABS = ("Live", "Buying", "Holding", "Whales", "Followed")


def _row_star(ns: list[dict], text: dict) -> tuple[int, int]:
    """The star in a Live feed row: a clickable node with no words, left of Buy."""
    j = text["i"]
    while j >= 0 and not ns[j]["clickable"]:
        j = ns[j]["parent"]
    top, bottom = (ns[j]["box"][1], ns[j]["box"][3]) if j >= 0 else (text["xy"][1] - 60, text["xy"][1] + 110)
    stars = [n for n in ns if n["clickable"] and n["i"] != j and not n["text"] and not n["desc"]
             and 820 <= n["xy"][0] <= 1060 and top <= n["xy"][1] <= bottom]
    if not stars:
        raise RuntimeError("prep: no star in the first feed row")
    return stars[0]["xy"]


def prep_scout() -> list[tuple]:
    # Live, Buying, the whales and what one holds, Follow, the Followed list, one followed
    # wallet's page, then Holding and "50.8% keep SKR staked". Follows are put back at the
    # end of the session (T3).
    if FOLLOWS in UNSAVED:
        raise RuntimeError("prep: the follows could not be saved, so they would not be put back")
    _fresh("wallet")
    _go(("text", "Scout"), ("wait", 3.0))
    xml = record.dump()
    ns = record.nodes(xml)
    rows = sorted((n for n in ns if " bought " in n["text"]), key=lambda n: n["xy"][1])
    if not rows:
        raise RuntimeError("prep: the Live feed has no «… bought …» row")
    name = rows[0]["text"].split(" bought ")[0]
    _see("wallet A", name)
    # The Followed tab exists only with a follow already there: A may be one.
    known = False
    if _has(xml, "Followed"):
        record.tap(*_need("Followed", xml))
        time.sleep(2.0)
        known = _has(record.dump(), name)
        record.tap(*_need("Live"))
        time.sleep(2.0)
        xml = record.dump()
        ns = record.nodes(xml)
        rows = sorted((n for n in ns if n["text"].startswith(name + " bought ")), key=lambda n: n["xy"][1])
    if not known:
        if not rows:
            raise RuntimeError(f"prep: «{name}» left the feed")
        record.tap(*_row_star(ns, rows[0]))
        DIRTY.add(FOLLOWS)
        time.sleep(1.0)
    # Out and in again: the page reads its follows once per visit.
    record.tap(*_need("Back"))
    time.sleep(1.5)
    _go(("text", "Scout"), ("wait", 3.0))
    record.scan(list(SCOUT_TABS))
    tabs = {t: record.SEEN.get(t) for t in SCOUT_TABS}
    if not all(tabs.values()):
        raise RuntimeError(f"prep: Scout tabs missing: {[t for t, v in tabs.items() if not v]}")
    record.tap(*tabs["Whales"])
    time.sleep(3.0)
    whales = sorted((n for _, n in record.find_re(ADDRESS) if n["xy"][1] > tabs["Whales"][1] + 60),
                    key=lambda n: n["xy"][1])
    if not whales:
        raise RuntimeError("prep: no whale address («XXXX…XXXX») on the Whales tab")
    whale1 = whales[0]["xy"]
    record.tap(*whale1)
    if not record.wait_for(lambda: record.find("Holds"), 10):
        _warn("the first whale's holdings did not load")
    xml = record.dump()
    if not _has(xml, "Follow") and _has(xml, "Following"):
        # Followed before: undone here so the take can follow it on camera.
        record.tap(*_need("Following", xml))
        DIRTY.add(FOLLOWS)
        time.sleep(1.0)
        xml = record.dump()
    follow = _need("Follow", xml)
    record.tap(*whale1)
    time.sleep(1.5)
    record.tap(*tabs["Followed"])
    time.sleep(2.0)
    a_row = _need(name)
    record.tap(*tabs["Live"])
    time.sleep(2.0)
    for t in SCOUT_TABS:
        _see(t, tabs[t])
    for label, at in (("whale1", whale1), ("follow", follow), ("A row", a_row)):
        _see(label, at)
    return [("at", 11.9, "xy", *tabs["Buying"]), ("at", 14.2, "xy", *tabs["Whales"]),
            ("at", 15.4, "xy", *whale1), ("at", 17.0, "xy", *follow),
            ("at", 18.8, "xy", *tabs["Followed"]), ("at", 23.3, "xy", *a_row),
            ("at", 27.1, "swipe", 600, 700, 600, 2400),   # drops the wallet page, which has no X
            ("at", 29.4, "xy", *tabs["Holding"]),
            ("at", 33.5, "swipe", 600, 1900, 600, 1500, 700), ("wait", 6.0)]


def _density() -> float:
    nums = re.findall(r"density:\s*(\d+)", record.sh("shell", "wm", "density"))
    return int(nums[-1]) / 160 if nums else 2.75


def _bubble_frame() -> tuple[int, int, int, int] | None:
    """The bubble's window on screen, from the window manager: uiautomator does not see overlays."""
    out = record.sh("shell", "dumpsys", "window", "windows")
    for block in re.split(r"\n(?=\s*Window #\d+)", out):
        if "APPLICATION_OVERLAY" in block and record.PKG in block:
            m = re.search(r"(?<![A-Za-z])m?[Ff]rame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", block)
            if m:
                return tuple(int(v) for v in m.groups())
    return None


def _bubble_centre() -> tuple[int, int] | None:
    f = _bubble_frame()
    if f:
        return (f[0] + f[2]) // 2, (f[1] + f[3]) // 2
    # The saved spot, as the plan reads it. Its y is the window's, under the status bar: a guess.
    try:
        xml = _prefs(COMPANION) or ""
    except RuntimeError:
        return None
    x, y = (re.search(rf'name="{k}" value="(-?\d+)"', xml) for k in ("x", "y"))
    if not x or not y:
        return None
    size = re.search(r'name="size" value="(\d+)"', xml)
    half = int((int(size.group(1)) if size else 54) * _density() / 2)
    _warn("bubble read from its prefs, not from the window manager: check the drag")
    return int(x.group(1)) + half, int(y.group(1)) + half


PAGE_MARKS = ("Bubble and widget", "Come back on its own", "WHAT THE SMALL CIRCLE SAYS", "WALLET HEALTH WIDGET")


def prep_bubble() -> list[tuple]:
    # The bubble over the home, the page on "every app", the widget card, three faces on
    # "the agent, your balance, wallet's health", then the panel on "Sell or stop the agent".
    _fresh("wallet")
    widget = _need("Widget")
    record.tap(*widget)
    time.sleep(2.5)
    record.tap(*_need("On"))
    time.sleep(2.5)
    # The window manager first: the saved spot exists from any earlier use and would answer
    # at once, bubble up or not.
    f = record.wait_for(_bubble_frame, 6)
    at = ((f[0] + f[2]) // 2, (f[1] + f[3]) // 2) if f else _bubble_centre()
    if at is None:
        raise RuntimeError("prep: the bubble did not come up (draw over other apps allowed?)")
    record.swipe(*at, *BUBBLE, 800)
    time.sleep(1.5)
    now = _bubble_centre()
    if now is None or abs(now[0] - BUBBLE[0]) > 60 or abs(now[1] - BUBBLE[1]) > 60:
        _warn(f"the bubble is at {now}, not at {BUBBLE}")
    record.swipe(600, 2000, 600, 1200, 1200)
    time.sleep(1.2)
    xml = record.dump()
    faces = {f: _need(f, xml) for f in ("Agent", "Total", "Health")}
    for f, p in faces.items():
        if abs(p[0] - BUBBLE[0]) < 120 and abs(p[1] - BUBBLE[1]) < 120:
            _warn(f"the {f} chip sits under the bubble")
    record.tap(*_need("A coin", xml))   # the starting face, so each tap changes it
    time.sleep(1.0)
    for _ in range(2):
        record.swipe(600, 1200, 600, 2000, 1200)
        time.sleep(0.8)
    record.tap(*_need("Close"))
    time.sleep(1.5)
    _see("widget", widget)
    _see("bubble", now)
    for f, p in faces.items():
        _see(f, p)
    return [("at", 6.2, "xy", *widget), ("at", 7.5, "swipe", 600, 2000, 600, 1200, 1200),
            ("at", 9.9, "xy", *faces["Agent"]), ("at", 11.0, "xy", *faces["Total"]),
            ("at", 12.4, "xy", *faces["Health"]), ("at", 15.5, "xy", *BUBBLE), ("wait", 3.5)]


def _after_bubble() -> None:
    """Panel closed, bubble off, page closed: it must not float into the other takes."""
    # The frame only decides the fold. Off is always tapped: it is harmless when the bubble is
    # already off, and a frame the parse misses must not leave it floating over the other takes.
    f = _bubble_frame()
    d = _density()
    if f is None:
        _warn("the bubble's window was not read: turning it off anyway, check it by hand")
    elif f[2] - f[0] > 120 * d:
        # Open (300 dp wide, the bubble at most 66): a tap on its title line, not a button, folds it.
        record.tap(f[0] + int(50 * d), f[1] + int(22 * d))
        time.sleep(1.0)
    xml = record.dump()
    if not any(m in xml for m in PAGE_MARKS):
        _close_sheets()
        _go(("tap", TABS["wallet"], TAB_Y), ("wait", 1.5))
        record.tap(*_need("Widget"))
        time.sleep(2.5)
    else:
        for _ in range(3):
            record.swipe(600, 1000, 600, 2200, 400)
            time.sleep(0.5)
    # Off by its left end: the parked bubble can float over the button's middle.
    ns = record.nodes(record.dump())
    off = next((n for n in ns if n["text"] == "Off"), None)
    if off is None:
        raise RuntimeError("«Off» is not on screen")
    j = off["i"]
    while j >= 0 and not ns[j]["clickable"]:
        j = ns[j]["parent"]
    box = ns[j]["box"] if j >= 0 else off["box"]
    record.tap(box[0] + 60, off["xy"][1])
    time.sleep(1.5)
    record.tap(*_need("Close"))
    time.sleep(1.5)
    if _bubble_frame():
        _warn("the bubble is still up: turn it off by hand")


X10 = "to do a ×10"
# The coin the market take opens. Not XRP: a coin with no Solana mint is charted by CoinGecko,
# which refuses this network; Solana's chart comes from GeckoTerminal. Its follow is put back.
MARKET_COIN = "Solana"


def _coin_star(xml: str) -> tuple[int, int]:
    hit = [n for n in record.nodes(xml) if n["desc"] == "Follow"]
    if hit:
        return hit[0]["xy"]
    # Without its description (A2): the clickable node right of the name, at its height.
    title = _y(MARKET_COIN, xml)
    near = [n for n in record.nodes(xml) if n["clickable"] and n["xy"][0] > 1000
            and title is not None and abs(n["xy"][1] - title) <= 60]
    if not near:
        raise RuntimeError(f"prep: no star on the {MARKET_COIN} sheet")
    return near[0]["xy"]


def _coin_clean(xml: str) -> str:
    """Not followed, no move alerts: what the take switches on must start off."""
    changed = False
    unstar = [n for n in record.nodes(xml) if n["desc"] == "Stop following"]
    if unstar:
        record.tap(*unstar[0]["xy"])
        changed = True
        time.sleep(1.0)
    if "On: a notification at every 5% move" in xml:
        record.tap(*_need("Tell me when it moves", xml))
        changed = True
        time.sleep(1.0)
    return record.dump() if changed else xml


def prep_market() -> list[tuple]:
    # The list on "ranked by size", XRP and its cached chart on "with its chart", the star,
    # the move alerts on "get told", the what-if, and the BTC row opening on "worth".
    _fresh("market")
    time.sleep(1.5)
    xrp = _need(MARKET_COIN)
    for attempt in (1, 2):
        record.tap(*xrp)
        time.sleep(6.0)   # warms the chart cache for the take
        xml = record.dump()
        if "No price history for this coin yet." not in xml:
            break
        record.key("back")
        time.sleep(1.5)
        if attempt == 2:
            raise RuntimeError(f"prep: {MARKET_COIN} has no chart, twice")
        print(f"    {MARKET_COIN} has no chart yet: again in 20 s")
        time.sleep(20.0)
    xml = _coin_clean(xml)
    moves = _need("Tell me when it moves", xml)
    star = _coin_star(xml)
    record.swipe(600, 2100, 600, 700, 900)
    time.sleep(1.2)
    y = _y(X10, record.dump())
    if y is None or not 900 <= y <= 2200:
        _warn(f"«{X10}» is at y {y}, not between 900 and 2200")
    record.key("back")   # the coin sheet is certainly open
    time.sleep(1.5)
    _go(("tap", TABS["market"], TAB_Y), ("wait", 1.5))
    xrp = _need(MARKET_COIN, last=True)   # again, the ranked row: unfollowing it above moves the list
    for label, at in (("xrp", xrp), ("star", star), ("moves", moves), ("×10 after drag", y)):
        _see(label, at)
    return [("at", 6.2, "xy", *xrp), ("at", 8.6, "xy", *star), ("at", 10.0, "xy", *moves),
            ("at", 12.2, "swipe", 600, 2100, 600, 700, 900), ("wait", 0.6), ("scan", X10),
            ("at", 17.1, "seen", X10), ("wait", 4.0)]


def _after_market() -> None:
    """XRP back to unfollowed with its alerts off."""
    _close_sheets()
    _go(("tap", TABS["market"], TAB_Y), ("wait", 2.0))
    at = record.find(MARKET_COIN)
    if at is None:
        _warn(f"{MARKET_COIN} not found to switch it back: check its star and alerts by hand")
        return
    record.tap(*at)
    time.sleep(3.0)
    xml = record.dump()
    if not any(m in xml for m in record.COIN_SHEET):
        _warn(f"the {MARKET_COIN} sheet did not open to switch it back")
        return
    _coin_clean(xml)
    record.key("back")
    time.sleep(1.5)


def prep_bridge() -> list[tuple]:
    # USDC on "USDC", the chains on "more than 200 chains", the private send on "another
    # address", then the swap with 25% and its review on "protected". No hold.
    _fresh("wallet")
    xml = record.dump()
    bridge, swap = _need("Bridge", xml), _need("Swap", xml)
    record.tap(*bridge)
    time.sleep(3.5)
    usdc = _need("USDC")
    record.tap(*usdc)
    time.sleep(1.5)
    # Read after USDC: its extra row moves everything above it (the sheet grows upwards).
    xml = record.dump()
    more, private = _need("Other chains", xml), _need("Private, here on Solana", xml)
    record.tap(*more)
    time.sleep(2.0)
    xml = record.dump()
    closes = [n for n in record.nodes(xml) if n["desc"] == "Close"] or record.find_all("Close", xml)
    if not closes or closes[-1]["xy"][1] >= 500:
        raise RuntimeError(f"prep: the chain picker's X is not at the top: {closes and closes[-1]['xy']}")
    pick_close = closes[-1]["xy"]
    record.tap(*pick_close)
    time.sleep(1.5)
    record.tap(*private)
    time.sleep(4.0)   # the minimum line comes from the network and moves the X
    bridge_close = _desc_close(record.dump())
    record.tap(*bridge_close)
    time.sleep(1.5)
    record.tap(*swap)
    time.sleep(2.5)
    pct = _need("25%")
    record.tap(*pct)
    t = time.time()
    review = _review_ready(8)
    if review is None:
        raise RuntimeError("prep: Review never became pressable after 25%")
    took = time.time() - t
    _see("review ready", f"{took:.1f} s after 25% (screen reads included; the take allows 2.5 s)")
    record.tap(*_desc_close(record.dump()))
    time.sleep(1.5)
    for label, at in (("bridge", bridge), ("usdc", usdc), ("more", more), ("pick close", pick_close),
                      ("private", private), ("bridge close", bridge_close), ("swap", swap), ("25%", pct),
                      ("review", review)):
        _see(label, at)
    return [("at", 3.4, "xy", *bridge), ("at", 6.3, "xy", *usdc), ("at", 7.8, "xy", *more),
            ("at", 9.3, "swipe", 600, 2000, 600, 1100, 700), ("at", 10.7, "xy", *pick_close),
            ("at", 11.4, "xy", *private), ("at", 15.6, "xy", *bridge_close), ("at", 16.2, "xy", *swap),
            ("at", 17.0, "xy", *pct), ("at", 19.5, "xy", *review), ("wait", 4.0)]


RECLAIM = r"^Reclaim the rent \(\+[^)]+\)$"


def _reclaim(xml: str) -> str | None:
    hits = record.find_re(RECLAIM, xml)
    return hits[0][1]["text"] if hits else None


def prep_health() -> list[tuple]:
    # The drawer on "One score", the findings on "approvals / empty accounts", the empty
    # account fix and its receipt on "One signature", then the sheet dropped. Nothing signed.
    _fresh("settings")
    xml = record.dump()
    ws = _need("Wallet and safety", xml)
    if _has(xml, "TRY AN ATTACK"):
        record.tap(*ws)   # it was open: the take opens it
        time.sleep(1.5)
    record.tap(*ws)
    warmed = time.time()
    time.sleep(3.0)   # fills the 60 s cache: the take has to start within the minute
    label = _reclaim(record.dump())
    record.swipe(600, 1900, 600, 1300, 800)
    time.sleep(1.2)
    xml = record.dump()
    label = label or _reclaim(xml)
    if label is None:
        raise RuntimeError("prep: no «Reclaim the rent (+… SOL)» button: nothing to reclaim?")
    y = _y(label, xml)
    if y is None or not 700 <= y <= 2200:
        _warn(f"«{label}» is at y {y} after the drag, not between 700 and 2200")
    for _ in range(2):
        record.swipe(600, 700, 600, 2100, 150)
        time.sleep(0.4)
    time.sleep(0.8)
    ws = _need("Wallet and safety")
    record.tap(*ws)
    time.sleep(1.0)
    _see("wallet+safety", ws)
    _see("reclaim", f"«{label}» at y {y}")
    _see("cache", f"warmed {time.time() - warmed:.0f} s ago; the take must start within 60 s")
    return [("at", 3.2, "xy", *ws), ("at", 7.0, "swipe", 600, 1900, 600, 1300, 800),
            ("wait", 0.6), ("scan", label), ("at", 13.9, "seen", label),
            ("at", 17.0, "swipe", 600, 700, 600, 2400),   # drops the fix sheet: it has no X
            ("wait", 4.0)]


def _receipt_with_proof() -> tuple[tuple[int, int], tuple[int, int]]:
    """A payment on the Receipts tab whose receipt shows "Show the proof" without scrolling."""
    ns = record.nodes(record.dump())
    rows = [n for n in ns if re.match(r"^−\d", n["text"]) and 350 < n["xy"][1] < 2300]
    rows.sort(key=lambda n: (not n["text"].endswith(" SOL"), n["xy"][1]))
    for row in rows[:6]:
        record.tap(*row["xy"])
        time.sleep(2.0)
        proof = record.find("Show the proof")
        if proof and proof[1] < 2350:
            record.tap(*proof)
            time.sleep(2.0)
            xml = record.dump()
            if "Proof of payment" not in xml:
                _warn("Show the proof did not open Proof of payment")
            record.tap(*_desc_close(xml))
            time.sleep(1.5)
            record.tap(*_desc_close(record.dump()))
            time.sleep(1.5)
            return row["xy"], proof
        record.tap(*_desc_close(record.dump()))
        time.sleep(1.5)
    raise RuntimeError("prep: no receipt on the first screen of Receipts shows Show the proof")


def prep_nfc() -> list[tuple]:
    # The take opens on the tap sheet in sticker mode: write on "stick it to a counter",
    # back on "Your phone is not needed", this phone and Start on "phone to phone", then a
    # receipt and its proof. Keep every NFC tag away: "Lock the sticker" is on.
    _fresh("receipts")
    time.sleep(0.5)
    row, proof = _receipt_with_proof()
    _go(("tap", TABS["wallet"], TAB_Y), ("wait", 1.5))
    record.tap(*_need("Receive"))
    time.sleep(2.5)
    record.tap(*_need("Tap"))
    time.sleep(2.5)
    xml = record.dump()
    sticker, phone, start = _need("A sticker", xml), _need("This phone", xml), _need("Start", xml)
    record.tap(*sticker)
    time.sleep(1.5)
    write = _need("Write the sticker")
    record.tap(*write)
    time.sleep(1.5)
    cancel = _need("Cancel")
    record.tap(*cancel)
    time.sleep(1.5)
    for label, at in (("row", row), ("proof", proof), ("sticker", sticker), ("phone", phone),
                      ("start", start), ("write", write), ("cancel", cancel)):
        _see(label, at)
    print("    keep every NFC tag away from the phone during the take")
    return [("at", 7.0, "xy", *write), ("at", 11.5, "xy", *cancel), ("at", 13.9, "xy", *phone),
            ("at", 15.3, "xy", *start), ("at", 16.8, "key", "back"),   # the tap sheet has no X
            ("at", 17.4, "xy", TABS["receipts"], TAB_Y), ("at", 18.3, "xy", *row),
            ("at", 19.1, "xy", *proof), ("wait", 5.0)]


COUNTDOWN = r"^(\d+) s$"


def _round_left(xml: str | None = None) -> int | None:
    hits = record.find_re(COUNTDOWN, xml)
    return int(hits[0][0].group(1)) if hits else None


def prep_ore() -> list[tuple]:
    # The tile on "And ORE", Dig on "The agent can dig", three squares on "from its budget",
    # Dig again and its receipt with "Hold to dig" on "the same limits". Never held.
    _fresh("wallet")
    tile, _ = _home_to_grid()
    record.tap(*tile)
    time.sleep(3.0)
    # Read inside an open round, or the last button says "Wait for the next round".
    if not record.wait_for(lambda: (_round_left() or 0) >= 30, 150):
        raise RuntimeError("prep: no ORE round with 30 s left came up")
    dig = _need("Dig")
    record.tap(*dig)
    time.sleep(1.5)
    xml = record.dump()
    label = _y("Emptiest squares now:", xml)
    if label is None:
        raise RuntimeError("prep: «Emptiest squares now:» is not on screen")
    threes = [n for n in record.find_all("3", xml) if abs(n["xy"][1] - label) <= 60]
    if not threes:
        raise RuntimeError("prep: no «3» beside «Emptiest squares now:»")
    chip3 = threes[0]["xy"]
    record.tap(*chip3)
    time.sleep(1.0)
    go = _need("Dig")
    record.tap(*_need("Back"))
    time.sleep(1.2)
    left = record.wait_for(lambda: (lambda s: s if s and s >= 45 else None)(_round_left()), 150)
    if not left:
        raise RuntimeError("prep: no ORE round with 45 s left came up")
    record.shut_sheet()   # the ORE sheet has no X, and the veil is inside it
    time.sleep(1.5)
    for name, at in (("tile", tile), ("dig", dig), ("chip 3", chip3), ("go", go), ("round left", f"{left} s")):
        _see(name, at)
    return [("at", 4.0, "xy", *tile), ("at", 5.8, "xy", *dig), ("at", 6.9, "xy", *chip3),
            ("at", 7.9, "xy", *go), ("wait", 4.5)]


def prep_spare() -> list[tuple]:
    # Settings' spare change card on "puts a little SOL aside", the ORE sheet with the jar
    # full on "When there is enough", the button on "you tap once", the swap into stORE on
    # "staked ORE". Nothing is signed; the jar stays full for close.
    _jar_shot()
    _go(("wait", 1.0), ("close",), ("wait", 1.0), ("tap", TABS["wallet"], TAB_Y), ("wait", 1.5))
    tile, _ = _home_to_grid()
    record.tap(*tile)
    time.sleep(2.5)
    button = _need(SPARE_BUTTON)
    # The take opens on the ORE sheet with the jar's line in view: a still Settings screen at
    # the start wrote no frames, and screenrecord began five seconds late (29 Sep). The round
    # countdown ticks every second, so this one records from the first second.
    _see("button", button)
    return [("at", 8.4, "xy", *button), ("wait", 5.0)]


def prep_close() -> list[tuple]:
    # The spare swap's receipt on "Receipt before signature", the X, Receipts on
    # "Everywhere.", the home back at the top on "Totem." The hold is never touched.
    _jar_shot()
    _go(("wait", 1.0), ("close",), ("wait", 1.0), ("tap", TABS["receipts"], TAB_Y), ("wait", 1.0))
    ledger = record.wait_for(lambda: not _has(record.dump(), "Nothing yet"), 4)
    if not ledger:
        _warn("the Receipts tab says Nothing yet: the take skips it")
    _go(("tap", TABS["wallet"], TAB_Y), ("wait", 1.5))
    tile, scrolled = _home_to_grid()
    record.tap(*tile)
    time.sleep(2.5)
    record.tap(*_need(SPARE_BUTTON))
    review = _review_ready(15)
    if review is None:
        raise RuntimeError("prep: the spare swap's Review never became pressable")
    x = _desc_close(record.dump())   # the swap sheet's own X; ("close",) would tap Back
    # Back to the top with a small overshoot, well under the pull to refresh (about 450 px).
    # The reading can only be high (the header shrinks as the home scrolls), never low.
    back = min(1450, scrolled + 150)
    y1 = 900 if 900 + back <= 2300 else 2300 - back
    if scrolled > 650:
        _warn(f"the home scrolled {scrolled} px (the plan wants about 650 or less)")
    _see("review", review)
    _see("x", x)
    _see("drag back", f"{back} px from y {y1}")
    steps = [("at", 2.4, "xy", *review), ("at", 5.3, "swipe", 600, 1700, 600, 1100, 700), ("at", 6.4, "xy", *x)]
    if ledger:
        steps.append(("at", 7.2, "xy", TABS["receipts"], TAB_Y))
    steps.append(("at", 8.0, "xy", TABS["wallet"], TAB_Y))
    # Already at the top: no drag, or all of it would be overscroll and start a refresh.
    if scrolled >= 50:
        steps.append(("at", 8.7, "swipe", 600, y1, 600, y1 + back, 500))
    return steps + [("wait", 4.0)]


def _close_sheets() -> None:
    """
    Close what the takes opened, each by its own way out. System back only where the sheet
    is certain (the tap sheet, a coin, the defenses list): with nothing open it leaves the
    app, and leaving locks it. The attack page is told by its offline note, not by its
    title, which Settings shows too. The sheets with no X are dragged down (T4).
    """
    for _ in range(5):
        xml = record.dump()
        if 'Offline demo: no network' in xml and 'text="Back"' in xml:
            record.tap(*record.find_all("Back", xml)[0]["xy"])
        elif any(m in xml for m in record.SWIPE_SHUT):
            record.shut_sheet()
        elif "active wallets watched" in xml and 'content-desc="Back"' in xml:
            record.tap(*[n for n in record.nodes(xml) if n["desc"] == "Back"][0]["xy"])
        elif any(m in xml for m in record.COIN_SHEET):
            record.key("back")
        elif 'text="Your defenses"' in xml and record.DEFENSES.search(xml):
            record.key("back")
        elif 'content-desc="Close"' in xml:
            record.tap(*_desc_close(xml))
        elif 'text="Tap to be paid"' in xml:
            record.key("back")
        else:
            return
        time.sleep(1.5)


# seconds of take, prep; the durations of the plan's section 2
TIMED = {"intro": (22.0, prep_intro), "receipt": (17.0, prep_receipt), "risks": (25.0, prep_risks),
         "keys": (8.0, prep_keys), "agent": (35.0, prep_agent), "watch": (22.0, prep_watch), "scout": (40.0, prep_scout),
         "bubble": (22.0, prep_bubble), "market": (22.0, prep_market), "bridge": (25.0, prep_bridge),
         "health": (23.0, prep_health), "nfc": (26.0, prep_nfc), "ore": (14.0, prep_ore),
         "spare": (14.0, prep_spare), "close": (14.0, prep_close)}
# Put back after the take, or after the prep in --dry.
AFTER = {"agent": _after_agent, "watch": _after_watch, "bubble": _after_bubble, "market": _after_market}


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
                ("the fee", "COINS", "the fee", M)],
    "risks": [("unlimited approval", "INFINITY", "unlimited approval", R),
              ("your contacts", "MASK", "a look-alike contact", R),
              ("nobody has seen", "SPARK", "never seen before", A),
              ("will not sign", "BLOCK", "will not sign", R),
              ("hold to confirm", "HOLD", "hold to confirm", M),
              ("fingerprint", "FINGERPRINT", "your fingerprint", M)],
    "keys": [("key never leaves", "LOCK", "the key never leaves", M)],
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
               ("sell or stop", "SWAP", "sell or stop, from anywhere", A)],
    "market": [("ranked by size", "CHART", "ranked by size", M),
               ("follow the ones", "STAR", "follow", M),
               ("get told", "MEGAPHONE", "told when they move", M),
               ("arithmetic", "INFO", "plain arithmetic", C),
               ("as big as", "CHART", "as big as that one", M)],
    "bridge": [("sol or usdc", "COINS", "SOL or USDC", M),
               ("200 chains", "BRIDGE", "200+ chains", M),
               ("another address", "MASK", "Private, here on Solana", C),
               ("protected", "SHIELD_LOCK", "protected from bots", M)],
    "health": [("one score", "SHIELD_LOCK", "one score", M),
               ("approvals", "INFINITY", "approvals it can revoke", M),
               ("empty accounts", "TRASH", "rent in empty accounts", M),
               ("one signature", "SIGN", "one signature", M),
               ("pool fees", "COINS", "Orca, Raydium, Meteora checked", M)],
    "nfc": [("nfc sticker", "NFC", "an NFC sticker", M),
            ("pay by touching", "NFC", "tap to pay", M),
            ("phone to phone", "NFC", "phone to phone", M),
            ("a proof", "QR", "a signed proof", M),
            ("another phone", "CHECK", "no network needed", M)],
    "ore": [("or", "GEM", "ORE", C),
            ("dig", "AGENT", "the agent digs", M),
            ("same limits", "LOCK", "same limits", M),
            ("gains home", "WALLET", "gains come home", M)],
    "spare": [("puts a little", "COINS", "a little set aside", M),
              ("tap once", "HOLD", "one tap into stORE", M),
              ("staked", "GEM", "staked ORE: stORE", C)],
    "close": [("everywhere", "SHIELD_LOCK", "every swap, send and trade", M)],
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


# Where each take gets to the point: every phone take starts at TIMED_START, and the build
# adds the offset measured on its clip. A watch drawn by drawn.py has its own lead (2.5).
START = {key: TIMED_START for key in SESSION}
# These two begin 0.5 s later than their words would put them, so the scene before
# finishes its sentence. The take skips the same 0.5 s and stays on the voice.
START["agent"] = TIMED_START + 0.5
START["bridge"] = TIMED_START + 0.5


def measure_offset(clip: Path, at: float) -> float | None:
    """
    T1: how much later than [at] the screen first changed in [clip] (negative: earlier, as
    when the encoder starts late). The first frame over 0.01 of scene change that belongs
    to the burst of the main change, looked for from 2 s before [at] to 0.8 s after.
    """
    lo, hi = max(0.0, at - 2.0), at + 0.8
    r = subprocess.run(
        ["ffmpeg", "-hide_banner", "-nostats", "-to", f"{hi + 0.5:.2f}", "-i", str(clip), "-an",
         "-vf", f"select='between(t,{lo:.2f},{hi:.2f})*gte(scene,0)',metadata=print:key=lavfi.scene_score",
         "-f", "null", "-"], capture_output=True, text=True)
    frames, t = [], None
    for line in r.stderr.splitlines():
        m = re.search(r"pts_time:(-?[\d.]+)", line)
        if m:
            t = float(m.group(1))
            continue
        m = re.search(r"lavfi\.scene_score=([\d.]+)", line)
        if m and t is not None:
            frames.append((t, float(m.group(1))))
            t = None
    if not frames:
        return None
    top = max(s for _, s in frames)
    if top < 0.01:
        return None
    strong = max(0.01, 0.25 * top)
    for i, (t, s) in enumerate(frames):
        if s >= 0.01 and any(s2 >= strong for t2, s2 in frames[i:] if t2 <= t + 0.8):
            return round(t - at, 2)
    return None


def _offset_file(clip: Path) -> Path:
    return clip.with_suffix(".offset")


def save_offset(clip: Path, steps: list[tuple]) -> None:
    at = next((st[1] for st in steps if st[0] == "at"), None)
    off = measure_offset(clip, at) if at is not None else None
    _offset_file(clip).write_text(json.dumps(
        {"at": at, "offset": off or 0.0, "measured": off is not None, "size": clip.stat().st_size}, indent=1) + "\n")
    if at is None:
        print("  offset: no timed step in this take, 0 used")
    elif off is None:
        print(f"  offset: no change found near {at}: 0 used, check the clip by eye")
    else:
        print(f"  offset {off:+.2f} s: the step sent at {at} shows at clip {at + off:.2f}")


def take_offset(clip: Path) -> float:
    """The offset saved beside [clip], if it was measured on this very file."""
    try:
        d = json.loads(_offset_file(clip).read_text())
    except (OSError, ValueError):
        return 0.0
    if d.get("size") != clip.stat().st_size:
        print(f"  {clip.name}: its offset belongs to another take, not used")
        return 0.0
    return float(d.get("offset") or 0.0)


def _show(steps: list[tuple]) -> None:
    for st in steps:
        print("      " + " ".join(str(v) for v in st))


def _tidy(key: str) -> None:
    """Whatever the take or the prep left open, closed; whatever it switched on, off."""
    try:
        if key in AFTER:
            AFTER[key]()
    except Exception as e:   # a cleanup that fails must not stop the session's own
        _warn(f"{key} cleanup: {e}")
    _close_sheets()


def shoot(keys: list[str], dry: bool = False) -> int:
    order = [k for k in SESSION if k in keys]
    print("waiting for Totem open and unlocked")
    if not record.wait_unlocked(600):
        print("the app is locked: unlock it with the fingerprint, then run again")
        return 1
    _save_session()
    failed: list[str] = []
    try:
        for key in order:
            seconds, prep = TIMED[key]
            if not record.wait_unlocked(90):
                print(f"  {key}: the app is locked, stopping here")
                failed.append(key)
                break
            print(f"— {key}" + (" (prep only)" if dry else f" ({seconds:.0f} s)"))
            try:
                if key in PREP_TOUCHES:
                    if PREP_TOUCHES[key] in UNSAVED:
                        raise RuntimeError(f"{PREP_TOUCHES[key]} could not be saved, so it could not be put back")
                    DIRTY.add(PREP_TOUCHES[key])
                steps = prep()
                if dry:
                    _show(steps)
                    continue
                if key in TOUCHES:
                    DIRTY.add(TOUCHES[key])
                print(f"  recording {key}")
                clip = record.record(f"tour_{key}", seconds, steps)
                out = SHOT / f"tour_{key}.mp4"
                clip.replace(out)
                save_offset(out, steps)
            except RuntimeError as e:
                print(f"  {key}: {e}")
                failed.append(key)
            finally:
                _tidy(key)
    finally:
        _restore_session()
    if failed:
        print("not done: " + " ".join(failed))
        return 1
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
        # Past the launch, plus how late this take's steps showed in its clip (T1).
        off = take_offset(clip)
        if off:
            print(f"  {key}: offset {off:+.2f} s")
        # A drawn watch has no offset file and drawn.py's own lead.
        lead = 2.5 if key == "watch" and not _offset_file(clip).exists() else START.get(key, 2.5)
        start = max(0.0, lead + off)
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
    global NO_BUDGET
    NO_BUDGET = "--no-budget" in argv
    dry = "--dry" in argv
    if "--record" in argv or dry:
        keys = [a for a in argv if not a.startswith("--")]
        if keys == ["all"] or (dry and not keys):
            keys = list(SESSION)
        if not keys:
            print("say which takes: --record all, or some of: " + " ".join(SESSION))
            return 2
        unknown = [k for k in keys if k not in TIMED]
        if unknown:
            print("unknown: " + " ".join(unknown) + "\ntakes: " + " ".join(SESSION))
            return 2
        return shoot(keys, dry=dry)
    if not VOICE.exists() or not MUSIC.exists():
        print("set TOTEM_VOICE and TOTEM_MUSIC to the two audio files")
        return 1
    build(cut.TALL if "--tall" in argv else cut.WIDE)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
