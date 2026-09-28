"""
The scenes the phone cannot film, drawn: the live screen ("Watch it work") of a running agent.

It opens only with a funded budget, and a take would need an agent that happens to buy while
the camera runs. So this redraws EyesScreen.kt as it is laid out: same palette (Halo), same
fonts (app/res/font), same strings (values/strings.xml). The coins and prices are an example;
every sentence on screen is one the app writes.

Out: shot/tour_watch.mp4, 1200 x 2670 like the phone's own recordings, with the same 2.5 s
of lead the montage skips.

Usage:
    python3 scripts/video/drawn.py
"""

from __future__ import annotations

import math
import random
import subprocess
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
FONTS = HERE.parents[1] / "app" / "src" / "main" / "res" / "font"
OUT = HERE / "shot" / "tour_watch.mp4"

W, H, FPS = 1200, 2670, 30
LEAD, SECONDS = 2.5, 19.0
D = W / 411  # px per dp on the Seeker

GROUND, GROUND2 = (7, 11, 18), (14, 19, 26)
CARD, STROKE = (34, 43, 51), (31, 94, 76)
MINT, CYAN, INK, MUTED = (77, 255, 208), (76, 201, 255), (230, 238, 251), (155, 174, 198)
AMBER, RED = (255, 194, 75), (255, 90, 106)


def font(name: str, sp: float) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONTS / name), int(sp * D))


INTER, SORA, MONO = "inter.ttf", "sora.ttf", "jetbrains_mono.ttf"


def dp(v: float) -> int:
    return int(v * D)


def mix(a, b, t):
    return tuple(int(x + (y - x) * t) for x, y in zip(a, b))


# Two coins in play, one guarded by an order on the chain, one by the loop.
COINS = [
    {"sym": "PUMP", "cost": "0.0046", "tp": 30, "sl": 15, "guarded": True, "seed": 3, "drift": 0.09},
    {"sym": "STONK", "cost": "0.0046", "tp": 30, "sl": 15, "guarded": False, "seed": 8, "drift": -0.03},
]

# (second it lands, kind, text): every text is a string of the app, filled in.
THOUGHTS = [
    (0.0, "step", "looking at the market…"),
    (0.0, "found", "read 227 coins, 51 pass the filters"),
    (0.0, "step", "examining SKR"),
    (0.0, "refused", "SKR costs 11% just to get in, leaving it"),
    (3.5, "step", "looking at the market…"),
    (5.5, "found", "read 231 coins, 49 pass the filters"),
    (7.5, "step", "examining PUMP"),
    (9.5, "acted", "Buying more: PUMP"),
    (12.5, "step", "nothing to do, looking again shortly"),
    (15.5, "step", "examining STONK"),
]
SIGIL = {"step": ">", "found": "+", "refused": "x", "warn": "!", "acted": "$"}
TINT = {"step": MUTED, "found": CYAN, "refused": RED, "warn": AMBER, "acted": MINT}


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


def chart(d: ImageDraw.ImageDraw, x, y, w, h, coin, t):
    """The chart with its three lines: in, target, stop. The price walks in as t grows."""
    pts = coin["series"]
    shown = int(len(pts) * min(1.0, 0.72 + t / SECONDS * 0.28))
    lo = 1 - coin["sl"] / 100 - 0.03
    hi = 1 + coin["tp"] / 100 + 0.03

    def yy(v):
        return y + h - (v - lo) / (hi - lo) * h

    for v, label, col in ((1.0, "in", MUTED), (1 + coin["tp"] / 100, f"+{coin['tp']}%", MINT),
                          (1 - coin["sl"] / 100, f"−{coin['sl']}%", RED)):
        py = yy(v)
        for sx in range(x, x + w - dp(34), dp(8)):
            d.line([(sx, py), (sx + dp(4), py)], fill=mix(GROUND, col, 0.55), width=2)
        d.text((x + w - dp(30), py - dp(7)), label, font=font(MONO, 9.5), fill=col)
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
    d.text((x, y - dp(2)), coin["sym"], font=font(SORA, 14), fill=INK)
    return pts[max(0, shown - 1)]


def frame(t: float) -> Image.Image:
    img = Image.new("RGB", (W, H), GROUND)
    d = ImageDraw.Draw(img)
    # The ground: a vertical gradient, ground2 to ground.
    for yy in range(0, H, 6):
        d.rectangle([0, yy, W, yy + 6], fill=mix(GROUND2, GROUND, yy / H))

    # Status bar, the time only.
    d.text((dp(22), dp(10)), "09:31", font=font(INTER, 13), fill=INK)

    top = dp(44)
    pad = dp(16)
    # The loop clock: outer arc to the next look, inner to the next hunt, a breathing dot.
    cx, cy, R = pad + dp(20), top + dp(20), dp(18)
    look = 1 - ((t + 48) % 90) / 90
    hunt = 1 - ((t + 200) % 360) / 360
    d.ellipse([cx - R, cy - R, cx + R, cy + R], outline=mix(GROUND, MUTED, 0.25), width=dp(2.2))
    d.arc([cx - R, cy - R, cx + R, cy + R], -90, -90 + 360 * look, fill=MINT, width=dp(2.2))
    r2 = R - dp(5)
    d.arc([cx - r2, cy - r2, cx + r2, cy + r2], -90, -90 + 360 * hunt, fill=CYAN, width=dp(1.6))
    b = dp(3 + 1.2 * (0.5 + 0.5 * math.sin(t * 2.4)))
    d.ellipse([cx - b, cy - b, cx + b, cy + b], fill=MINT)

    tx = cx + R + dp(12)
    d.text((tx, top + dp(2)), "ITS EYES", font=font(INTER, 11), fill=MUTED)
    d.text((tx, top + dp(17)), "Watching 2 coins, every 90 seconds", font=font(INTER, 12), fill=INK)
    d.text((tx, top + dp(33)), f"next look in {int((t + 48) % 90 and 90 - (t + 48) % 90)}s",
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
        d.text((inner + dp(70), ry + dp(4)), f"bought with {coin['cost']} SOL", font=font(INTER, 11), fill=MUTED)
        gy = ry + dp(24)
        if coin["guarded"]:
            g, gc = f"Order on Jupiter: sells alone at +{coin['tp']}%, phone off too. The stop is the loop's.", MINT
        else:
            g, gc = "No order on the chain (slice under $5): the loop sells, every 90 s, while it runs.", AMBER
        d.text((inner, gy), g, font=font(INTER, 9.6), fill=gc)
        cy2 = gy + dp(22)
        cxx = inner
        for label, col in (("Sell", RED), ("More", MINT), ("Sell and look for another", CYAN)):
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
    d.text((ix, box_top + dp(12)), "WHAT IT IS THINKING", font=font(INTER, 11), fill=MUTED)
    f = font(INTER, 11)
    lab = "Voice on"
    wl = d.textlength(lab, font=f)
    vx = W - pad - dp(12) - wl - dp(22)
    rounded(d, [vx, box_top + dp(8), W - pad - dp(12), box_top + dp(34)], dp(8),
            fill=mix(GROUND, MINT, 0.10), outline=mix(GROUND, MINT, 0.45), width=2)
    d.text((vx + dp(11), box_top + dp(14)), lab, font=f, fill=MINT)

    landed = [th for th in THOUGHTS if th[0] <= t]
    ly = box_top + dp(44)
    base = 9 * 3600 + 30 * 60 + 12
    for i, (at, kind, text) in enumerate(landed[-9:]):
        last = i == len(landed[-9:]) - 1
        n = len(text)
        if last and at > 0:
            n = int(min(1.0, (t - at) / max(0.3, min(2.2, len(text) * 0.016))) * len(text))
        s = int(base + at)
        stamp = f"{s // 3600:02d}:{s % 3600 // 60:02d}:{s % 60:02d}"
        d.text((ix, ly + dp(1.5)), stamp, font=font(MONO, 9.5), fill=mix(GROUND, MUTED, 0.55))
        d.text((ix + dp(52), ly), SIGIL[kind], font=font(MONO, 11), fill=TINT[kind])
        d.text((ix + dp(64), ly), text[:n], font=font(MONO, 11),
               fill=INK if kind == "step" else TINT[kind])
        if last and (int(t * 2) % 2 == 0):
            cw = d.textlength(text[:n], font=font(MONO, 11))
            d.rectangle([ix + dp(64) + cw + dp(2), ly + dp(1), ix + dp(64) + cw + dp(8), ly + dp(14)], fill=MINT)
        ly += dp(21)

    # The foot: look now, and the stop.
    fy = box_top + box_h + dp(14)
    stop = "Stop the agent"
    fs = font(MONO, 11.5)
    sw = d.textlength(stop, font=fs) + dp(28)
    rounded(d, [pad, fy, W - pad - sw - dp(10), fy + dp(50)], dp(14),
            fill=mix(GROUND, MINT, 0.14), outline=mix(GROUND, MINT, 0.6), width=2)
    fl = font(SORA, 15)
    ll = "Look now"
    d.text(((pad + W - pad - sw - dp(10)) / 2 - d.textlength(ll, font=fl) / 2, fy + dp(14)), ll, font=fl, fill=MINT)
    rounded(d, [W - pad - sw, fy, W - pad, fy + dp(50)], dp(6),
            fill=mix(GROUND, AMBER, 0.10), outline=mix(GROUND, AMBER, 0.45), width=2)
    d.text((W - pad - sw + dp(14), fy + dp(17)), stop, font=fs, fill=AMBER)

    # The ring that leaves the middle of the screen when the loop moves money.
    for at, kind, _ in THOUGHTS:
        if kind == "acted" and 0 < t - at < 1.4:
            k = (t - at) / 1.4
            rr = dp(40 + 260 * k)
            col = mix(GROUND, MINT, 1 - k)
            d.ellipse([W / 2 - rr, H / 2 - rr, W / 2 + rr, H / 2 + rr], outline=col, width=dp(2.5 * (1 - k) + 0.5))
    return img


def main() -> None:
    for c in COINS:
        c["series"] = series(c["seed"], c["drift"])
    OUT.parent.mkdir(parents=True, exist_ok=True)
    enc = subprocess.Popen([
        "ffmpeg", "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}",
        "-r", str(FPS), "-i", "-", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", str(OUT)],
        stdin=subprocess.PIPE)
    total = int((LEAD + SECONDS) * FPS)
    for i in range(total):
        t = max(0.0, i / FPS - LEAD)
        enc.stdin.write(frame(t).tobytes())
    enc.stdin.close()
    enc.wait()
    print(OUT)


if __name__ == "__main__":
    main()
