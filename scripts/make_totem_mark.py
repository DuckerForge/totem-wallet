#!/usr/bin/env python3
"""Draw the Totem mark and export every bitmap the app and the site read.

The mark is the Seeker seen from the back, a slab of dark glass, its right edge lit by one
neon thread (violet below, cyan above), and the two panels of the old V opened behind it
as wings: the thunderbird, the best known totem. Same geometry as `GateDemo` in
LoginDemo.kt, which animates it, so the door settles on this exact image.

Exports (names kept from the old mark, the code reads them by name):
  mipmap-*/brand_bird.png       the mark alone, transparent ground, RGBA (door, splash, home chip)
  mipmap-*/ic_launcher_bird.png full-bleed launcher background, subject in the safe zone
  web/apex/icon.png             the mark at 216 px

Usage:  python3 scripts/make_totem_mark.py
"""
import os

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
MARK = {"mdpi": 96, "hdpi": 144, "xhdpi": 192, "xxhdpi": 288, "xxxhdpi": 384}
ICON = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

S = 1080 * 2            # rendered at 2160 and downsampled: the antialiasing comes from the shrink
PHONE = 69.56 / 150.86  # the Seeker, width over height
GROUND = (7, 11, 18)
NEON_LOW = (149, 36, 243)
NEON_HIGH = (11, 245, 236)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def glass(size, depth=0.0):
    """A slab of dark glass: cold gradient, hairline of light on the edges."""
    w, h = size
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    grad = Image.new("RGBA", (w, h))
    px = grad.load()
    top, mid, bot = (38, 32, 66), (16, 20, 36), (9, 12, 22)
    for y in range(h):
        for x in range(w):
            u = (x / w + y / h) / 2
            c = lerp(top, mid, u / 0.55) if u < 0.55 else lerp(mid, bot, (u - 0.55) / 0.45)
            px[x, y] = c + (int(255 * (1 - depth * 0.4)),)
    mask = Image.new("L", (w, h), 0)
    r = int(min(w, h) * (0.092 if h > w else 0.28))
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, h - 1), radius=r, fill=255)
    im.paste(grad, (0, 0), mask)
    edge = ImageDraw.Draw(im)
    edge.rounded_rectangle((0, 0, w - 1, h - 1), radius=r, outline=(150, 170, 255, int(255 * (0.35 - depth * 0.12))), width=max(2, h // 160))
    return im


def paste_rotated(canvas, layer, pivot_on_canvas, pivot_on_layer, degrees):
    """Rotate a layer around a pivot and paste it so the pivot lands on the canvas point."""
    big = Image.new("RGBA", (layer.width * 3, layer.height * 3), (0, 0, 0, 0))
    off = (layer.width, layer.height)
    big.paste(layer, off)
    pivot = (off[0] + pivot_on_layer[0], off[1] + pivot_on_layer[1])
    rot = big.rotate(degrees, resample=Image.BICUBIC, center=pivot)
    canvas.alpha_composite(rot, (int(pivot_on_canvas[0] - pivot[0]), int(pivot_on_canvas[1] - pivot[1])))


def draw_mark(size, scale=0.92, plate=True):
    """The whole mark inside a square of `size`, the subject filling `scale` of it."""
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    if plate:
        pl = Image.new("RGBA", (size, size))
        ppx = pl.load()
        for y in range(size):
            for x in range(size):
                u = (x + y) / (2 * size)
                ppx[x, y] = lerp((20, 24, 38), (7, 9, 15), u) + (255,)
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, size - 1, size - 1), radius=int(size * 0.22), fill=255)
        im.paste(pl, (0, 0), mask)

    sq = size * scale
    # The phone is most of the square: the composition is the phone, and the wings reach
    # about as far sideways as it is tall. Drawn smaller it read as a stamp in a big empty box.
    ph = sq * 0.74
    pw = ph * PHONE
    cx, cy = size / 2, size / 2 + sq * 0.01

    # The old V's panels, behind the body. Low and wide, not high and narrow: pivoted near
    # the top and turned only a third of a right angle they came out above the phone like a
    # pair of horns, and a dark shape with two horns is a steakhouse sign, not a wallet.
    wing_w, wing_l = pw * 0.92, ph * 0.56
    for side in (-1, 1):
        layer = glass((int(wing_w), int(wing_l)), depth=0.12)
        pivot_layer = (wing_w / 2, wing_l * 0.42)
        pivot_canvas = (cx + side * pw * 0.34, cy + ph * 0.10)
        paste_rotated(im, layer, pivot_canvas, pivot_layer, -side * 58.0)

    # the Seeker, from the back
    body = glass((int(pw), int(ph)), depth=0.0)
    d = ImageDraw.Draw(body)
    w, h = body.size
    edge = (170, 190, 255, 150)
    ink = (22, 28, 44, 255)
    iw, ih, ix, iy = w * 0.22, h * 0.29, w * 0.12, h * 0.05
    lw = max(2, int(h * 0.007))
    d.rounded_rectangle((ix, iy, ix + iw, iy + ih), radius=int(iw / 2), outline=edge, width=lw)
    for k in range(3):
        lx, ly, rr = ix + iw / 2, iy + ih * (0.19 + 0.31 * k), iw * 0.30
        d.ellipse((lx - rr, ly - rr, lx + rr, ly + rr), fill=ink, outline=edge, width=lw)
    fx, fy, fr = ix + iw * 1.45, iy + ih * 0.26, iw * 0.14
    d.ellipse((fx - fr, fy - fr, fx + fr, fy + fr), outline=edge, width=lw)
    bw = max(3, int(h * 0.014))
    d.line((w - 1, h * 0.22, w - 1, h * 0.29), fill=edge, width=bw)
    d.line((w - 1, h * 0.33, w - 1, h * 0.46), fill=edge, width=bw)
    im.alpha_composite(body, (int(cx - pw / 2), int(cy - ph / 2)))

    # the neon: the right edge itself, lit. A wide soft halo, the thread, a white-hot core.
    x = cx + pw / 2
    y0, y1 = cy - ph / 2 + ph * 0.092, cy + ph / 2 - ph * 0.092
    core = max(3, int(ph * 0.011))
    for width, alpha, blur in ((core * 8, 120, ph * 0.05), (core * 3, 200, ph * 0.012), (core, 255, 0)):
        layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        ld = ImageDraw.Draw(layer)
        steps = 80
        for i in range(steps):
            ya = y1 - (y1 - y0) * i / steps
            yb = y1 - (y1 - y0) * (i + 1) / steps
            c = lerp(NEON_LOW, NEON_HIGH, (i + 0.5) / steps) + (alpha,)
            ld.line((x, ya, x, yb), fill=c, width=int(width))
        for yy, c in ((y1, NEON_LOW), (y0, NEON_HIGH)):
            ld.ellipse((x - width / 2, yy - width / 2, x + width / 2, yy + width / 2), fill=c + (alpha,))
        if blur:
            layer = layer.filter(ImageFilter.GaussianBlur(blur))
        im.alpha_composite(layer)
    hot = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(hot).line((x, y0, x, y1), fill=(235, 245, 255, 140), width=max(1, int(core * 0.35)))
    im.alpha_composite(hot)
    return im


def main():
    # No plate: in the door and on the home chip a rounded square behind the mark reads as a
    # box around it, and the door already has its own ground.
    mark = draw_mark(S, scale=1.0, plate=False)
    for dpi, px in MARK.items():
        out = os.path.join(RES, f"mipmap-{dpi}", "brand_bird.png")
        mark.resize((px, px), Image.LANCZOS).save(out)
        print("wrote", out)
    mark.resize((216, 216), Image.LANCZOS).save(os.path.join(ROOT, "web", "apex", "icon.png"))
    print("wrote web/apex/icon.png")

    # Launcher: adaptive icons show the middle 2/3, so the subject sits there on a full-bleed ground.
    icon = Image.new("RGBA", (S, S), GROUND + (255,))
    # The breath belongs here, on the full-bleed ground, not inside the subject's own square,
    # where the blur gets clipped and leaves a rectangle on whatever the mark is laid over.
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((S * 0.22, S * 0.18, S * 0.78, S * 0.74), fill=(120, 140, 255, 30))
    icon.alpha_composite(glow.filter(ImageFilter.GaussianBlur(S * 0.09)))
    subject = draw_mark(int(S * 2 / 3), scale=1.0, plate=False)
    icon.alpha_composite(subject, (int(S / 6), int(S / 6)))
    for dpi, px in ICON.items():
        out = os.path.join(RES, f"mipmap-{dpi}", "ic_launcher_bird.png")
        icon.convert("RGB").resize((px, px), Image.LANCZOS).save(out)
        print("wrote", out)


if __name__ == "__main__":
    main()
