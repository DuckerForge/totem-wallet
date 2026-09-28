"""
The app's own icons, for the film: HaloIcons.kt read and redrawn with Pillow.

HaloIcons.kt draws every icon with a small DSL on a 24-unit grid (line, circle, dot, arc,
poly, path { moveTo/lineTo/quadTo/cubicTo/arcTo/close }, two). That DSL is regular enough
to translate: the Kotlin is read at run time and each icon body becomes a list of calls
on [Pen]. So an icon in the video is the icon in the app, and changes with it.

Bodies that step outside the DSL (raw Canvas calls, loops) are skipped; the few the film
needs from those are drawn here by hand, from the same coordinates.

Usage:
    python3 scripts/video/icons.py      writes a sheet of every icon to the scratch folder
"""

from __future__ import annotations

import math
import re
from pathlib import Path

from PIL import Image, ImageDraw

SRC = Path(__file__).resolve().parents[2] / "app/src/main/kotlin/com/clearsign/app/HaloIcons.kt"
SS = 4  # drawn this many times larger, then scaled down: smooth edges without antialiasing


class Pen:
    """The G class of HaloIcons.kt, on a Pillow image."""

    def __init__(self, d: ImageDraw.ImageDraw, u: float, tint: tuple, sw: float):
        self.d, self.u, self.tint, self.sw = d, u, tint, sw
        self.stack: list[tuple] = []

    def p(self, x, y):
        return (x * self.u, y * self.u)

    def _stroke(self, pts, closed=False, fill=False, tint=None):
        tint = tint or self.tint
        if fill:
            self.d.polygon(pts, fill=tint)
            return
        if closed:
            pts = pts + [pts[0]]
        w = max(1, round(self.sw))
        self.d.line(pts, fill=tint, width=w, joint="curve")
        r = self.sw / 2
        for x, y in (pts[0], pts[-1]):  # round caps
            self.d.ellipse([x - r, y - r, x + r, y + r], fill=tint)

    def line(self, x1, y1, x2, y2):
        self._stroke([self.p(x1, y1), self.p(x2, y2)])

    def circle(self, cx, cy, r, fill=False):
        (x, y), rr = self.p(cx, cy), r * self.u
        if fill:
            self.d.ellipse([x - rr, y - rr, x + rr, y + rr], fill=self.tint)
        else:
            self.d.ellipse([x - rr, y - rr, x + rr, y + rr], outline=self.tint, width=max(1, round(self.sw)))

    def dot(self, cx, cy, r=1.3):
        self.circle(cx, cy, r, fill=True)

    def _arc_pts(self, cx, cy, r, start, sweep):
        n = max(8, int(abs(sweep) / 4))
        return [self.p(cx + r * math.cos(math.radians(start + sweep * i / n)),
                       cy + r * math.sin(math.radians(start + sweep * i / n))) for i in range(n + 1)]

    def arc(self, cx, cy, r, start, sweep):
        self._stroke(self._arc_pts(cx, cy, r, start, sweep))

    def poly(self, *pts, close=False, fill=False):
        self._stroke([self.p(pts[i], pts[i + 1]) for i in range(0, len(pts), 2)], closed=close, fill=fill)

    # path { ... }
    def path_begin(self, fill=False, close=False):
        self.stack.append(("path", fill, close, [[]]))

    def _cur(self):
        return self.stack[-1][3][-1]

    def moveTo(self, x, y):
        subs = self.stack[-1][3]
        if subs[-1]:
            subs.append([])
        subs[-1].append((x, y))

    def lineTo(self, x, y):
        self._cur().append((x, y))

    def quadTo(self, cx, cy, x, y):
        x0, y0 = self._cur()[-1]
        for i in range(1, 13):
            t = i / 12
            self._cur().append(((1 - t) ** 2 * x0 + 2 * (1 - t) * t * cx + t * t * x,
                                (1 - t) ** 2 * y0 + 2 * (1 - t) * t * cy + t * t * y))

    def cubicTo(self, c1x, c1y, c2x, c2y, x, y):
        x0, y0 = self._cur()[-1]
        for i in range(1, 17):
            t = i / 16
            a, b, c, e = (1 - t) ** 3, 3 * (1 - t) ** 2 * t, 3 * (1 - t) * t * t, t ** 3
            self._cur().append((a * x0 + b * c1x + c * c2x + e * x, a * y0 + b * c1y + c * c2y + e * y))

    def arcTo(self, cx, cy, r, start, sweep):
        n = max(8, int(abs(sweep) / 4))
        for i in range(n + 1):
            a = math.radians(start + sweep * i / n)
            self._cur().append((cx + r * math.cos(a), cy + r * math.sin(a)))

    def close_(self):
        self.stack[-1] = self.stack[-1][:2] + (True,) + self.stack[-1][3:]

    def path_end(self):
        _, fill, close, subs = self.stack.pop()
        for sub in subs:
            if len(sub) > 1:
                self._stroke([self.p(x, y) for x, y in sub], closed=close, fill=fill)

    # two(tint.copy(alpha = a)) { ... }
    def two_begin(self, alpha):
        self.stack.append(("two", self.tint))
        self.tint = self.tint[:3] + (int(self.tint[3] * alpha),)

    def two_end(self):
        self.tint = self.stack.pop()[1]


def _translate(body: str) -> list[str] | None:
    """One icon body, Kotlin to Python calls on `g`. None if it leaves the DSL."""
    if re.search(r"\bs\.|\bfor\b|\bval\b|Path\(", body):
        return None
    b = re.sub(r"//[^\n]*", "", body)
    b = re.sub(r"(\d)f\b", r"\1", b)
    b = b.replace("true", "True").replace("false", "False")
    out, i, stack = [], 0, []
    tokens = re.findall(r"\s+|two\(tint\.copy\(alpha = [\d.]+\)\)\s*\{|path(?:\([^)]*\))?\s*\{|\}|\{|[^;{}\s][^;{}\n]*", b)
    for t in tokens:
        t = t.strip()
        if not t:
            continue
        m = re.match(r"two\(tint\.copy\(alpha = ([\d.]+)\)\)\s*\{", t)
        if m:
            out.append(f"g.two_begin({m.group(1)})"); stack.append("two"); continue
        m = re.match(r"path(?:\(([^)]*)\))?\s*\{", t)
        if m:
            out.append(f"g.path_begin({m.group(1) or ''})"); stack.append("path"); continue
        if t == "{":
            stack.append("block"); continue
        if t == "}":
            kind = stack.pop() if stack else "block"
            if kind == "path":
                out.append("g.path_end()")
            elif kind == "two":
                out.append("g.two_end()")
            continue
        t = t.replace("close()", "close_()")
        t = re.sub(r"\b(line|circle|dot|arc|poly|moveTo|lineTo|quadTo|cubicTo|arcTo|close_)\(", r"g.\1(", t)
        out.append(t)
    return out


def _bodies() -> dict[str, list[str]]:
    src = SRC.read_text()
    start = src.index("fun draw(icon: HIcon)")
    src = src[start:]
    found: dict[str, list[str]] = {}
    for m in re.finditer(r"HIcon\.([A-Z_]+) -> ", src):
        name, j = m.group(1), m.end()
        eol = src.index("\n", j)
        brace = src.find("{", j, eol)
        if brace >= 0:  # a block, or `path {` spanning lines: up to the matching brace
            depth, k = 0, brace
            while True:
                depth += {"{": 1, "}": -1}.get(src[k], 0)
                k += 1
                if depth == 0:
                    break
            body = src[j + 1:k - 1] if brace == j else src[j:k]
        else:  # one expression, up to the end of the line
            body = src[j:eol]
        calls = _translate(body)
        if calls is not None:
            found[name] = calls
    return found


_CACHE: dict[str, list[str]] | None = None


def _gem(g: Pen, ground):
    g.path_begin(fill=True); g.moveTo(12, 2.5); g.lineTo(21.5, 12); g.lineTo(12, 21.5); g.lineTo(2.5, 12); g.close_(); g.path_end()
    for a, b in (((12, 2.5), (12, 21.5)), ((2.5, 12), (21.5, 12))):
        g.d.line([g.p(*a), g.p(*b)], fill=ground[:3] + (140,), width=max(1, round(0.9 * g.u)))


def icon(name: str, tint: tuple, px: int, ground=(7, 11, 18, 255)) -> Image.Image:
    """The icon [name] in [tint], [px] pixels square, on transparent."""
    global _CACHE
    if _CACHE is None:
        _CACHE = _bodies()
    size = px * SS
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    u = size / 24
    g = Pen(ImageDraw.Draw(img), u, tuple(tint[:3]) + (tint[3] if len(tint) > 3 else 255,), 1.9 * u)
    if name == "GEM":
        _gem(g, ground)
    elif name == "SCOUT":  # the lens, lightly filled, with the crowd's line inside
        (x, y), r = g.p(10.5, 10.5), 6.2 * u
        g.d.ellipse([x - r, y - r, x + r, y + r], fill=g.tint[:3] + (26,))
        g.circle(10.5, 10.5, 6.2)
        g.path_begin(); g.moveTo(6.9, 12.4); g.lineTo(9.6, 9.4); g.lineTo(11.6, 11.1); g.lineTo(14.1, 7.8); g.path_end()
        g.line(15.1, 15.1, 20.5, 20.5)
    else:
        if name not in _CACHE:
            raise KeyError(f"{name}: not drawable from HaloIcons.kt")
        exec("\n".join(_CACHE[name]), {"g": g})
    return img.resize((px, px), Image.LANCZOS)


if __name__ == "__main__":
    import sys
    names = sorted(_bodies()) + ["GEM"]
    cols = 10
    sheet = Image.new("RGBA", (cols * 110, (len(names) // cols + 1) * 110), (7, 11, 18, 255))
    for i, n in enumerate(names):
        sheet.alpha_composite(icon(n, (77, 255, 208, 255), 72), ((i % cols) * 110 + 19, (i // cols) * 110 + 19))
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "icons_sheet.png")
    sheet.save(out)
    print(len(names), "icons ->", out)
