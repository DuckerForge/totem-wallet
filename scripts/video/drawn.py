"""
The scenes the phone cannot film, drawn: the live screen ("Watch it work") of a running agent.

It opens only with a funded budget, and a take would need an agent that happens to buy while
the camera runs. So this redraws EyesScreen.kt as it is laid out: same palette (Halo), same
fonts (app/res/font), same icons (icons.py), same strings (read from values/strings.xml). The
coins and prices are an example; every sentence on screen is one the app writes.

Timed on the voice: t here is film second minus 75.5 (START 2.5 = LEAD). The loop looks on
"watch it work", the charts draw in on "charts", their lines light on "entry, target, stop",
the log types one line per beat of "line by line", then it buys and the voice chip speaks.

The status bar is the real one, keyed off the last clean frame of the agent take (else the
first of the scout take), so the clock is the session's. --clock HH:MM draws one by hand.

Out: shot/tour_watch.mp4 (or --out), 1200 x 2670 like the phone's own recordings, with the
same 2.5 s of lead the montage skips.

Usage:
    python3 scripts/video/drawn.py [--clock HH:MM] [--out PATH]
"""

from __future__ import annotations

import functools
import json
import math
import random
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import icons

HERE = Path(__file__).resolve().parent
RES = HERE.parents[1] / "app" / "src" / "main" / "res"
FONTS = RES / "font"
OUT = HERE / "shot" / "tour_watch.mp4"
# Where the status bar comes from: the scene before this one, else the one after.
TAKES = [HERE / "shot" / "tour_agent.mp4", HERE / "shot" / "tour_scout.mp4"]
BAR_H = 96  # the status bar in a take: clock and icons sit in y 34..76

W, H, FPS = 1200, 2670, 30
LEAD, SECONDS = 2.5, 19.0
D = W / 411  # px per dp on the Seeker

GROUND, GROUND2 = (7, 11, 18), (14, 19, 26)
CARD, STROKE = (34, 43, 51), (31, 94, 76)
MINT, CYAN, INK, MUTED = (77, 255, 208), (76, 201, 255), (230, 238, 251), (155, 174, 198)
AMBER, RED = (255, 194, 75), (255, 90, 106)


@functools.lru_cache(maxsize=None)
def font(name: str, sp: float, weight: int = 400) -> ImageFont.FreeTypeFont:
    f = ImageFont.truetype(str(FONTS / name), int(sp * D))
    f.set_variation_by_axes([weight if a["name"] == b"Weight" else a["default"] for a in f.get_variation_axes()])
    return f


INTER, SORA, MONO = "inter.ttf", "sora.ttf", "jetbrains_mono.ttf"


def _strings() -> dict[str, str]:
    out = {}
    for e in ET.parse(RES / "values" / "strings.xml").getroot().iter("string"):
        out[e.get("name")] = "".join(e.itertext()).replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n")
    return out


STRINGS = _strings()


def txt(key: str, *args) -> str:
    """The app's string [key], filled in the way getString does."""
    s = re.sub(r"%(\d+)\$[sd]", lambda m: str(args[int(m.group(1)) - 1]), STRINGS[key])
    return s.replace("%%", "%")


def dp(v: float) -> int:
    return int(v * D)


def mix(a, b, t):
    return tuple(int(x + (y - x) * t) for x, y in zip(a, b))


def ease(k: float) -> float:
    """Compose's FastOutSlowInEasing, cubic-bezier(0.4, 0, 0.2, 1)."""
    k = min(1.0, max(0.0, k))
    lo, hi = 0.0, 1.0
    for _ in range(24):
        s = (lo + hi) / 2
        if 3 * (1 - s) ** 2 * s * 0.4 + 3 * (1 - s) * s * s * 0.2 + s ** 3 < k:
            lo = s
        else:
            hi = s
    s = (lo + hi) / 2
    return 3 * (1 - s) * s * s + s ** 3


# The timeline, on the voice (film second in the comment).
LOOK = 2.9                                  # 78.4 "watch it work": the loop looks
LIGHT = {"in": 7.0, "tp": 7.7, "sl": 8.8}   # 82.5 "entry", 83.2 "target", 84.3 "stop"
VOICE = (13.3, 15.6)                        # 88.8 "a voice that tells you what it just did"

# Two coins in play, one guarded by an order on the chain, one by the loop.
# draw: when its price line draws in, on "charts" (79.9) and "every coin" (80.4).
COINS = [
    {"sym": "PUMP", "cost": "0.0046", "tp": 30, "sl": 15, "guarded": True, "seed": 3, "drift": 0.09, "draw": 4.4},
    {"sym": "STONK", "cost": "0.0046", "tp": 30, "sl": 15, "guarded": False, "seed": 8, "drift": -0.03, "draw": 4.9},
]

# (second it lands, kind, text): the app's strings, with the kind TraderLoop gives each.
THOUGHTS = [
    # The last look, 90 s back: already there when the screen opens.
    (LOOK - 90.0, "step", txt("trace_scanning")),
    (LOOK - 88.4, "step", txt("trace_scanned", 227, 51)),
    (LOOK - 84.9, "refused", txt("trace_bad_rate", "SKR", 11)),
    (LOOK, "step", txt("trace_scanning")),
    (10.1, "step", txt("trace_scanned", 231, 49)),           # 85.6 "What it is thinking"
    (11.2, "found", txt("trace_looking", "BONK")),             # 86.7 "line"
    (12.0, "step", txt("trace_rug_ok", "BONK", 91, "100%")),   # 87.5 "by line"
    (13.1, "acted", txt("trader_bought", "BONK", "0.0046")),   # 88.6, before "And a voice"
]
SIGIL = {"step": ">", "found": "+", "refused": "x", "warn": "!", "acted": "$"}
TINT = {"step": MUTED, "found": CYAN, "refused": RED, "warn": AMBER, "acted": MINT}

# Set by main: the status bar (alpha of its white pixels) and the log's clock at t 0.
BAR: Image.Image | None = None
BASE = 9 * 3600 + 19 * 60 + 12


def series(seed: int, drift: float, n: int = 240) -> list[float]:
    """A price walk, relative to the entry (1.0)."""
    r = random.Random(seed)
    v, out = 1.0 - drift * 0.5, []
    for i in range(n):
        v += r.gauss(drift / n, 0.0045)
        # Between the stop and the target: past either one the coin would be sold.
        v = min(1.26, max(0.88, v))
        out.append(v)
    return out


def rounded(d: ImageDraw.ImageDraw, box, r, fill=None, outline=None, width=1):
    d.rounded_rectangle(box, radius=r, fill=fill, outline=outline, width=width)


def lit(t: float, at: float) -> float:
    """A chart line named by the voice: up in 0.2 s, held to 0.7 s, then down to a glow that stays."""
    k = t - at
    if k <= 0:
        return 0.0
    if k < 0.2:
        return k / 0.2
    if k < 0.7:
        return 1.0
    return max(0.35, 1.0 - (k - 0.7) / 0.6 * 0.65)


def beat(t: float) -> float:
    """The voice chip speaking: three beats across VOICE, like HaloChip's pulse."""
    a, z = VOICE
    if not a <= t <= z:
        return 0.0
    return math.sin(math.pi * 3 * (t - a) / (z - a)) ** 2


def chart(d: ImageDraw.ImageDraw, x, y, w, h, coin, t):
    """The chart with its three lines: in, target, stop. The price draws in on its word."""
    pts = coin["series"]
    now = int(len(pts) * min(1.0, 0.72 + t / SECONDS * 0.28))
    lo = 1 - coin["sl"] / 100 - 0.03
    hi = 1 + coin["tp"] / 100 + 0.03

    def yy(v):
        return y + h - (v - lo) / (hi - lo) * h

    fs = font(SORA, 14, 700)
    sym = d.textbbox((x, y - dp(2)), coin["sym"], font=fs)
    dash_end = x + w - dp(44)
    for v, label, col, key in ((1.0, txt("eyes_entry"), MUTED, "in"),
                               (1 + coin["tp"] / 100, f"+{coin['tp']}%", MINT, "tp"),
                               (1 - coin["sl"] / 100, f"−{coin['sl']}%", RED, "sl")):
        py = yy(v)
        g = lit(t, LIGHT[key])
        # A line at the height of the coin's name starts after it, not through it.
        sx0 = sym[2] + dp(8) if sym[1] - dp(3) <= py <= sym[3] + dp(3) else x
        for sx in range(int(sx0), int(dash_end), dp(8)):
            d.line([(sx, py), (sx + dp(4), py)], fill=mix(GROUND, col, 0.55 + 0.45 * g), width=2 + round(dp(1.3) * g))
        size = round((9.5 + 3.5 * g) * 4) / 4
        d.text((x + w - dp(6), py), label, font=font(MONO, size, 700 if g > 0.3 else 400), fill=col, anchor="rm")
    k = min(1.0, max(0.0, (t - coin["draw"]) / 1.0))
    shown = int(now * (1 - (1 - k) ** 3))
    line = [(x + i * (w - dp(40)) / len(pts), yy(pts[i])) for i in range(shown)]
    if len(line) > 1:
        up = pts[shown - 1] >= 1.0
        col = MINT if up else RED
        d.line(line, fill=col, width=dp(1.6), joint="curve")
        cx, cy = line[-1]
        pulse = 0.5 + 0.5 * math.sin(t * 5)
        rr = dp(3 + 2 * pulse)
        d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], outline=col, width=2)
        d.ellipse([cx - dp(2.2), cy - dp(2.2), cx + dp(2.2), cy + dp(2.2)], fill=col)
    d.text((x, y - dp(2)), coin["sym"], font=fs, fill=INK)
    return pts[max(0, now - 1)]


@functools.lru_cache(maxsize=None)
def agent_icon() -> Image.Image:
    return icons.icon("AGENT", MINT + (255,), dp(14))


def frame(t: float) -> Image.Image:
    img = Image.new("RGB", (W, H), GROUND)
    d = ImageDraw.Draw(img)
    # The ground: a vertical gradient, ground2 to ground.
    for yy in range(0, H, 6):
        d.rectangle([0, yy, W, yy + 6], fill=mix(GROUND2, GROUND, yy / H))

    # The status bar, lifted from a take: white where its clock and icons are.
    if BAR is not None:
        img.paste((255, 255, 255), (0, 0), BAR)

    top = dp(44)
    pad = dp(16)
    # The loop clock: outer arc to the next look, inner to the next hunt, a breathing dot.
    # Both restart at LOOK, and the dot swells once, as it does when the loop looks.
    cx, cy, R = pad + dp(20), top + dp(20), dp(18)
    look_left = 90 - (t - LOOK) % 90
    hunt_left = 360 - (t - LOOK) % 360
    d.ellipse([cx - R, cy - R, cx + R, cy + R], outline=mix(GROUND, MUTED, 0.25), width=dp(2.2))
    d.arc([cx - R, cy - R, cx + R, cy + R], -90, -90 + 360 * look_left / 90, fill=MINT, width=dp(2.2))
    r2 = R - dp(5)
    d.arc([cx - r2, cy - r2, cx + r2, cy + r2], -90, -90 + 360 * hunt_left / 360, fill=CYAN, width=dp(1.6))
    b = dp(3 + 1.2 * (0.5 + 0.5 * math.sin(t * 2.4)))
    d.ellipse([cx - b, cy - b, cx + b, cy + b], fill=MINT)
    if 0 < t - LOOK < 1.6:
        e = ease((t - LOOK) / 1.6)
        rs = dp(2.5 * (2 + 3 * e))
        d.ellipse([cx - rs, cy - rs, cx + rs, cy + rs], outline=mix(GROUND2, MINT, 0.55 * (1 - e)), width=dp(1.5))

    tx = cx + R + dp(12)
    d.text((tx, top + dp(2)), txt("eyes_title").upper(), font=font(INTER, 11), fill=MUTED)
    d.text((tx, top + dp(17)), txt("eyes_watching", len(COINS)), font=font(INTER, 12), fill=INK)
    d.text((tx, top + dp(33)), txt("eyes_next_look", int(look_left)),
           font=font(MONO, 10), fill=MUTED)
    d.text((W - pad - dp(20), top + dp(10)), "×", font=font(INTER, 20), fill=MUTED)

    y = top + dp(62)
    for coin in COINS:
        ch = dp(118)
        box_h = ch + dp(112)
        rounded(d, [pad, y, W - pad, y + box_h], dp(16), fill=CARD, outline=mix(CARD, STROKE, 0.6), width=2)
        inner = pad + dp(14)
        v = chart(d, inner, y + dp(12), W - 2 * inner, ch, coin, t)
        move = (v - 1) * 100
        ry = y + dp(12) + ch + dp(10)
        d.text((inner, ry), f"{move:+.1f}%", font=font(MONO, 15), fill=MINT if move >= 0 else RED)
        d.text((inner + dp(70), ry + dp(4)), txt("eyes_since_entry", coin["cost"]), font=font(INTER, 11), fill=MUTED)
        gy = ry + dp(24)
        if coin["guarded"]:
            g, gc = txt("eyes_guard_chain", coin["tp"]), MINT
        else:
            g, gc = txt("eyes_guard_loop"), AMBER
        d.text((inner, gy), g, font=font(INTER, 9.6), fill=gc)
        cy2 = gy + dp(22)
        cxx = inner
        for label, col in ((txt("eyes_sell"), RED), (txt("eyes_more"), MINT), (txt("eyes_next"), CYAN)):
            f = font(INTER, 11)
            wlab = d.textlength(label, font=f)
            rounded(d, [cxx, cy2, cxx + wlab + dp(22), cy2 + dp(28)], dp(8),
                    fill=mix(CARD, col, 0.10), outline=mix(CARD, col, 0.45), width=2)
            d.text((cxx + dp(11), cy2 + dp(7)), label, font=f, fill=col)
            cxx += wlab + dp(30)
        y += box_h + dp(12)

    # What it is thinking: the lines so far, the last one typing itself.
    box_top = y
    box_h = dp(222)
    rounded(d, [pad, box_top, W - pad, box_top + box_h], dp(14), fill=GROUND, outline=mix(GROUND, CYAN, 0.2), width=2)
    for sy in range(box_top + 4, box_top + box_h - 4, 6):  # the tube glass
        d.line([(pad + 6, sy), (W - pad - 6, sy)], fill=mix(GROUND, CYAN, 0.03))
    ix = pad + dp(12)
    d.text((ix, box_top + dp(12)), txt("eyes_thoughts").upper(), font=font(INTER, 11), fill=MUTED)

    # The voice chip, as the app draws it: its icon and "Voice on". It beats while it speaks.
    k = beat(t)
    fv = font(INTER, 12, 600)
    lab = txt("eyes_voice_on")
    x1 = W - pad - dp(12)
    x0 = x1 - dp(12 + 14 + 6 + 12) - d.textlength(lab, font=fv)
    y0, y1 = box_top + dp(6), box_top + dp(36)
    if k > 0.01:
        for i in (3, 2, 1):  # the glow
            e = dp(1.4 * i * k)
            rounded(d, [x0 - e, y0 - e, x1 + e, y1 + e], dp(10) + e, outline=mix(GROUND, MINT, 0.3 * k / i), width=2)
    rounded(d, [x0, y0, x1, y1], dp(10), fill=mix(GROUND, MINT, 0.08 + 0.18 * k),
            outline=mix(GROUND, MINT, 0.45 + 0.55 * k), width=2)
    ic = agent_icon()
    img.paste(ic, (int(x0 + dp(12)), int((y0 + y1) / 2 - ic.height / 2)), ic)
    d.text((x0 + dp(32), (y0 + y1) / 2), lab, font=fv, fill=MINT, anchor="lm")

    # Eight lines fill the box: never more, or the last one sits on its border.
    landed = [th for th in THOUGHTS if th[0] <= t][-8:]
    rings = []
    ly = box_top + dp(44)
    for i, (at, kind, text) in enumerate(landed):
        last = i == len(landed) - 1
        n = len(text)
        if last and at > 0:
            n = int(min(1.0, (t - at) / max(0.3, min(2.2, len(text) * 0.016))) * len(text))
        s = int(BASE + at)
        stamp = f"{s // 3600:02d}:{s % 3600 // 60:02d}:{s % 60:02d}"
        d.text((ix, ly + dp(1.5)), stamp, font=font(MONO, 9.5), fill=mix(GROUND, MUTED, 0.55))
        d.text((ix + dp(52), ly), SIGIL[kind], font=font(MONO, 11), fill=TINT[kind])
        f = font(MONO, 11, 700 if kind == "acted" else 400)
        d.text((ix + dp(64), ly), text[:n], font=f, fill=INK if kind == "step" else TINT[kind])
        if last and (int(t * 2) % 2 == 0):
            cw = d.textlength(text[:n], font=f)
            d.rectangle([ix + dp(64) + cw + dp(2), ly + dp(1), ix + dp(64) + cw + dp(8), ly + dp(14)], fill=MINT)
        # The ring leaves the line that moved money.
        if kind == "acted" and 0 < t - at < 1.4:
            rings.append((ix + dp(64) + d.textlength(text, font=f) / 2, ly + dp(8), (t - at) / 1.4))
        ly += dp(21)

    # The foot: look now, and the stop.
    fy = box_top + box_h + dp(14)
    stop = txt("trader_stop_action")
    fs = font(MONO, 11.5, 700)
    sw = d.textlength(stop, font=fs) + dp(28)
    rounded(d, [pad, fy, W - pad - sw - dp(10), fy + dp(50)], dp(14),
            fill=mix(GROUND, MINT, 0.14), outline=mix(GROUND, MINT, 0.6), width=2)
    # ArmBar: the label in capitals, mono bold, 4 sp apart.
    fl = font(MONO, 13, 700)
    ll = txt("eyes_hunt_now").upper()
    lw = sum(d.textlength(c, font=fl) for c in ll) + dp(4) * (len(ll) - 1)
    lx = (pad + W - pad - sw - dp(10)) / 2 - lw / 2
    for c in ll:
        d.text((lx, fy + dp(25)), c, font=fl, fill=MINT, anchor="lm")
        lx += d.textlength(c, font=fl) + dp(4)
    rounded(d, [W - pad - sw, fy, W - pad, fy + dp(50)], dp(6),
            fill=mix(GROUND, AMBER, 0.10), outline=mix(GROUND, AMBER, 0.45), width=2)
    d.text((W - pad - sw + dp(14), fy + dp(17)), stop, font=fs, fill=AMBER)

    # ActRing's numbers: from a tenth of the width to all of it in 1.4 s, fading as it grows.
    if rings:
        over = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        o = ImageDraw.Draw(over)
        for rx, ry, kk in rings:
            e = ease(kk)
            r = W * (0.1 + 0.9 * e)
            o.ellipse([rx - r * 0.6, ry - r * 0.6, rx + r * 0.6, ry + r * 0.6], fill=MINT + (int(255 * 0.12 * (1 - e)),))
            o.ellipse([rx - r, ry - r, rx + r, ry + r], outline=MINT + (int(255 * 0.5 * (1 - e)),), width=dp(2))
        img = Image.alpha_composite(img.convert("RGBA"), over).convert("RGB")
    return img


# ---- the status bar --------------------------------------------------------------------

def _probe(clip: Path) -> tuple[float, datetime]:
    """Length of a take and the phone's clock when it ended (screenrecord stamps the end)."""
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration:format_tags=creation_time",
                          "-of", "json", str(clip)], capture_output=True, text=True, check=True).stdout
    fmt = json.loads(out)["format"]
    made = fmt.get("tags", {}).get("creation_time")
    end = (datetime.fromisoformat(made.replace("Z", "+00:00")).astimezone() if made
           else datetime.fromtimestamp(clip.stat().st_mtime).astimezone())
    return float(fmt["duration"]), end


def _strip(clip: Path, at: float) -> Image.Image | None:
    raw = subprocess.run(["ffmpeg", "-v", "error", "-ss", f"{at:.2f}", "-i", str(clip), "-frames:v", "1",
                          "-vf", f"crop={W}:{BAR_H}:0:0", "-f", "rawvideo", "-pix_fmt", "gray", "-"],
                         capture_output=True).stdout
    return Image.frombytes("L", (W, BAR_H), raw) if len(raw) == W * BAR_H else None


def _by_hand(clock: str) -> Image.Image:
    """No take to lift it from: clock, do not disturb, wifi, signal, battery, where the Seeker puts them."""
    g = Image.new("L", (W, BAR_H), 0)
    d = ImageDraw.Draw(g)
    d.text((80, 54), clock, font=font(INTER, 14, 600), fill=255, anchor="lm")
    d.ellipse([871, 36, 908, 73], outline=255, width=4)
    d.line([(880, 54), (899, 54)], fill=255, width=4)
    d.pieslice([922, 41, 984, 103], 225, 315, fill=255)
    d.polygon([(1041, 72), (1076, 72), (1076, 37)], fill=255)
    d.rounded_rectangle([1093, 40, 1115, 73], 3, fill=255)
    d.rectangle([1099, 35, 1109, 40], fill=255)
    return g


def status_bar(clock: str | None = None) -> tuple[Image.Image, datetime, str]:
    """The bar (alpha of its white pixels), the phone's clock in it, and where it came from."""
    if clock is None:
        # The agent take backwards from its end, then the scout take forwards: the first
        # clean one wins. A sheet or a scrim under the bar shows up as mid greys.
        found = []
        for clip, forward in ((TAKES[0], False), (TAKES[1], True)):
            if not clip.exists():
                continue
            dur, end = _probe(clip)
            for k in range(12):
                at = 3.0 + 1.5 * k if forward else dur - 1.0 - 1.5 * k
                if not 0.5 <= at <= dur - 0.2:
                    continue
                g = _strip(clip, at)
                if g is not None:
                    found.append((sum(g.histogram()[61:190]), g, end - timedelta(seconds=dur - at), f"{clip.name} at {at:.1f}s"))
        if found:
            least = min(f[0] for f in found)
            clean = [f for f in found if f[0] <= least * 1.2 + 200]
            # The stamp is good to a couple of seconds: away from a minute's edge, the bar's
            # minute is the one computed, and the log can match it.
            dirt, g, when, src = next((f for f in clean if 5 <= f[2].second <= 55), clean[0])
            return g.point(lambda v: max(0, min(255, (v - 60) * 255 // 175))), when, src
        clock = datetime.now().strftime("%H:%M")
    hh, mm = (int(p) for p in clock.split(":"))
    return _by_hand(clock), datetime.now().replace(hour=hh, minute=mm, second=12), "drawn by hand"


def main() -> None:
    global BAR, BASE
    args = sys.argv[1:]
    out = Path(args[args.index("--out") + 1]) if "--out" in args else OUT
    BAR, when, src = status_bar(args[args.index("--clock") + 1] if "--clock" in args else None)
    # The log's clock: the bar's, held inside the bar's minute up to the last line.
    BASE = when.hour * 3600 + when.minute * 60 + when.second
    BASE -= max(0, when.second + math.ceil(THOUGHTS[-1][0]) - 59)
    print(f"status bar: {src}, {when:%H:%M:%S}")
    for c in COINS:
        c["series"] = series(c["seed"], c["drift"])
    out.parent.mkdir(parents=True, exist_ok=True)
    enc = subprocess.Popen([
        "ffmpeg", "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}",
        "-r", str(FPS), "-i", "-", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", str(out)],
        stdin=subprocess.PIPE)
    total = int((LEAD + SECONDS) * FPS)
    for i in range(total):
        t = max(0.0, i / FPS - LEAD)
        enc.stdin.write(frame(t).tobytes())
    enc.stdin.close()
    enc.wait()
    print(out)


if __name__ == "__main__":
    main()
