#!/usr/bin/env python3
"""Rebuild the Apex launcher icon from the artwork the user provided.

The art (docs/brand-apex-source.jpg) is a tall bird-in-a-shield on a flat pale
grey plate. Dropped straight into an adaptive icon it reads *small*: the subject
is only 43% as wide as the tile, so the sides are empty grey.

What this does about it:
  * cuts the subject out of its plate with a soft matte, using a replacement
    colour that is a near neighbour of the original grey, so no halo appears
    (the earlier alpha-matte attempt was scrapped for exactly that halo);
  * paints a silver radial plate plus a blue glow behind the bird, so the empty
    sides carry colour instead of blank paper;
  * scales the subject to the full adaptive-icon safe zone and exports the five
    densities of mipmap/ic_launcher_bird.png (also used by the splash screen).

Usage:  python3 scripts/make_icon.py [--preview]
"""
import os
import statistics
import sys

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "docs", "brand-apex-source.jpg")
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

MASTER = 1080          # render big, downsample once per density
SAFE = 2 / 3           # adaptive icons show the middle 72dp of 108dp
SUBJECT_H = 0.95       # subject height, as a share of the visible safe zone
PLATE_IN = (243, 244, 249)
PLATE_OUT = (188, 192, 208)
GLOW = (76, 201, 255)


def cutout(im):
    """Return the subject cropped to its bounding box, with a soft alpha."""
    im = im.convert("RGB")
    w, h = im.size
    px = im.load()
    edge = [px[x, y] for x in range(0, w, 7) for y in (1, 2, h - 2, h - 3)]
    edge += [px[x, y] for y in range(0, h, 7) for x in (1, 2, w - 2, w - 3)]
    bg = tuple(int(statistics.median(c[i] for c in edge)) for i in range(3))

    # alpha ramps from 0 at the plate colour to 255 once clearly off it
    lo, hi = 26, 96
    alpha = Image.new("L", (w, h))
    ap = alpha.load()
    for y in range(h):
        for x in range(w):
            p = px[x, y]
            d = abs(p[0] - bg[0]) + abs(p[1] - bg[1]) + abs(p[2] - bg[2])
            ap[x, y] = 0 if d <= lo else (255 if d >= hi else int(255 * (d - lo) / (hi - lo)))
    alpha = alpha.filter(ImageFilter.MedianFilter(5))
    im.putalpha(alpha)
    return im.crop(alpha.getbbox()), bg


def plate(size):
    """Silver radial plate: light in the middle, deeper at the corners."""
    small = 96
    g = Image.new("RGB", (small, small))
    d = g.load()
    c = (small - 1) / 2
    for y in range(small):
        for x in range(small):
            t = min(1.0, (((x - c) ** 2 + (y - c) ** 2) ** 0.5) / (c * 1.32))
            t = t * t * (3 - 2 * t)  # smoothstep, keeps the centre broad
            d[x, y] = tuple(int(PLATE_IN[i] + (PLATE_OUT[i] - PLATE_IN[i]) * t) for i in range(3))
    return g.resize((size, size), Image.BICUBIC)


def build(size):
    subject, _ = cutout(Image.open(SRC))
    canvas = plate(size).convert("RGBA")

    target_h = int(size * SAFE * SUBJECT_H)
    scale = target_h / subject.height
    sub = subject.resize((max(1, int(subject.width * scale)), target_h), Image.LANCZOS)

    # optical centring: the beak sticks out to the right, the shield is centred,
    # so nudge left by a sliver of the width instead of centring the bounding box
    x = (size - sub.width) // 2 - int(sub.width * 0.025)
    y = (size - sub.height) // 2

    glow = Image.new("RGBA", (size, size), GLOW + (0,))
    halo = sub.getchannel("A").point(lambda v: int(v * 0.55))
    layer = Image.new("L", (size, size))
    layer.paste(halo, (x, y))
    glow.putalpha(layer.filter(ImageFilter.GaussianBlur(size * 0.055)))
    canvas = Image.alpha_composite(canvas, glow)

    canvas.paste(sub, (x, y), sub)
    return canvas.convert("RGB")


def main():
    master = build(MASTER)
    if "--preview" in sys.argv:
        out = os.environ.get("PREVIEW", "/tmp/apex_icon_preview.png")
        master.resize((432, 432), Image.LANCZOS).save(out)
        # what the launcher actually shows: the middle 72dp, squircle-masked
        vis = int(432 * SAFE)
        off = (432 - vis) // 2
        tile = master.resize((432, 432), Image.LANCZOS).crop((off, off, off + vis, off + vis))
        mask = Image.new("L", (vis, vis), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, vis - 1, vis - 1), radius=int(vis * 0.30), fill=255)
        shown = Image.new("RGB", (vis, vis), (12, 14, 18))
        shown.paste(tile, (0, 0), mask)
        shown.save(out.replace(".png", "_tile.png"))
        print("preview:", out)
        return

    for name, px in DENSITIES.items():
        path = os.path.join(ROOT, "app", "src", "main", "res", "mipmap-" + name, "ic_launcher_bird.png")
        master.resize((px, px), Image.LANCZOS).save(path)
        print("wrote", path)


if __name__ == "__main__":
    main()
