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
# The launcher ground is light on purpose, and it took four tries to land there.
#
# The mark is dark glass, which is the whole idea of it, and as a launcher icon that made
# it the one tile that vanished: on a real home screen every neighbour (Play Store,
# Discord, WhatsApp) carries a light or saturated fill, so a dark square reads as a hole
# in the wallpaper. Lifting the ground off black did nothing, because the subject stayed
# dark either way. Drawing the slab as a bright outline on black did nothing either: a
# hairline cannot carry an icon at 48 px. What works is inverting it, and the cleanest
# inversion is the plainest: a near-white ground with the slab as a dark silhouette and
# the neon thread as the only colour. Size cannot help, the launcher masks this to its
# middle two thirds and the subject already fills them.
#
# GROUND stays for anything drawn on the app's own dark surfaces; the launcher uses the
# gradient below.
GROUND = (14, 22, 34)
GROUND_HI = (96, 252, 124)
GROUND_LO = (31, 175, 56)
# Violet to blue to green, the three stops of the brand ramp. They must stay equal to
# NEON_LOW / NEON_MID / NEON_HIGH in LoginDemo.kt: the door animates this same thread and
# settles on this bitmap, so a different ramp there would land green on cyan.
NEON_LOW = (153, 69, 255)
NEON_MID = (76, 201, 255)
NEON_HIGH = (20, 241, 149)



def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))
def ramp(t):
    """The brand thread, bottom to top. Two segments, like the door's."""
    return lerp(NEON_LOW, NEON_MID, t * 2) if t < 0.5 else lerp(NEON_MID, NEON_HIGH, (t - 0.5) * 2)


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


def draw_mark(size, scale=0.92, plate=True, detail=True, wings=None, thread=1.0):
    """The whole mark inside a square of `size`, the subject filling `scale` of it.

    `detail` draws the camera island, `wings` overrides the panels' fill, `thread` scales the
    neon. The launcher passes detail=False with pale wings and a fatter thread, and the three
    go together for one reason: at 48 px the island is three sub-pixel rings that read as
    dirt, and dark wings behind a dark slab merge into one blob with no shape at all. Large,
    on the door and the home chip, the island is exactly what says Seeker instead of any
    black rectangle (see `phone` in LoginDemo.kt), so it stays there.
    """
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
        if wings:
            flat = Image.new("RGBA", layer.size, (0, 0, 0, 0))
            r = int(min(layer.size) * 0.10)
            ImageDraw.Draw(flat).rounded_rectangle((0, 0, layer.width - 1, layer.height - 1), radius=r, fill=wings + (255,))
            layer = flat
        pivot_layer = (wing_w / 2, wing_l * 0.42)
        pivot_canvas = (cx + side * pw * 0.34, cy + ph * 0.10)
        paste_rotated(im, layer, pivot_canvas, pivot_layer, -side * 58.0)

    # the Seeker, from the back
    body = glass((int(pw), int(ph)), depth=0.0)
    d = ImageDraw.Draw(body)
    w, h = body.size
    edge = (170, 190, 255, 150)
    ink = (22, 28, 44, 255)
    if detail:
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
    core = max(3, int(ph * 0.011 * thread))
    for width, alpha, blur in ((core * 8, 120, ph * 0.05), (core * 3, 200, ph * 0.012), (core, 255, 0)):
        layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        ld = ImageDraw.Draw(layer)
        steps = 80
        for i in range(steps):
            ya = y1 - (y1 - y0) * i / steps
            yb = y1 - (y1 - y0) * (i + 1) / steps
            c = ramp((i + 0.5) / steps) + (alpha,)
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
    icon = Image.new("RGBA", (S, S))
    px = icon.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = lerp(GROUND_HI, GROUND_LO, (x + y) / (2 * S)) + (255,)
    # The breath belongs here, on the full-bleed ground, not inside the subject's own square,
    # where the blur gets clipped and leaves a rectangle on whatever the mark is laid over.
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    # A whisper of shade under the subject so it sits on the ground instead of floating.
    # Almost nothing: on a light ground a halo turns into a smudge.
    ImageDraw.Draw(glow).ellipse((S * 0.24, S * 0.22, S * 0.76, S * 0.72), fill=(120, 140, 150, 26))
    icon.alpha_composite(glow.filter(ImageFilter.GaussianBlur(S * 0.09)))
    # A little over the 2/3 an adaptive icon guarantees, and the ceiling is not a matter of
    # taste: rendered under a circle mask, 0.76 cuts the phone's bottom edge off and 0.72
    # puts it on the line. 0.70 is the largest that never clips, on a circle or a squircle.
    FILL = 0.70
    subject = draw_mark(int(S * FILL), scale=1.0, plate=False, detail=False, wings=(112, 132, 172), thread=1.5)
    icon.alpha_composite(subject, (int(S * (1 - FILL) / 2), int(S * (1 - FILL) / 2)))
    for dpi, px in ICON.items():
        out = os.path.join(RES, f"mipmap-{dpi}", "ic_launcher_bird.png")
        icon.convert("RGB").resize((px, px), Image.LANCZOS).save(out)
        print("wrote", out)


if __name__ == "__main__":
    main()
