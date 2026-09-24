"""
L'apertura, disegnata fotogramma per fotogramma.

La porta dell'app non si puo' filmare: il riquadro dell'impronta e' una finestra
protetta e annerisce la registrazione dal primo fotogramma. Quel racconto, pero',
e' il marchio che si scrive, ed e' l'unica cosa che apre un video meglio di una
schermata. Quindi si ridisegna qui, con la stessa geometria di
`scripts/make_totem_mark.py`: il filo di luce sul bordo destro del Seeker si
scrive dal basso, il telefono si accende sotto di lui, le ali si aprono dietro,
e poi il nome.

Niente librerie di animazione: ogni fotogramma e' una PNG composta con PIL, e
ffmpeg le mette in fila. Sei secondi a trenta fotogrammi sono centottanta
immagini, che si disegnano in pochi secondi e si guardano una a una se qualcosa
non torna.

Uso:
    python3 scripts/video/intro.py            scrive build/intro_16x9.mp4
"""

from __future__ import annotations

import math
import subprocess
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

import cards
import cut

HERE = Path(__file__).resolve().parent
BUILD = HERE / "build"
FPS = 30

GROUND = (7, 11, 18)
NEON_LOW = (149, 36, 243)
NEON_HIGH = (11, 245, 236)
PHONE = 69.56 / 150.86

# Il racconto, in secondi. Ogni riga e' un momento che si vede finire.
LINE = (0.15, 1.45)      # il filo si scrive dal basso
BODY = (1.30, 2.20)      # il Seeker si accende sotto
WINGS = (2.05, 3.05)     # le ali si aprono dietro
NAME = (3.10, 3.90)      # il nome
CLAIM = (4.10, 4.90)     # la riga sotto
OUT = (6.20, 6.80)       # e si spegne
END = 6.8


def seg(t: float, a: float, b: float) -> float:
    return 0.0 if t <= a else 1.0 if t >= b else (t - a) / (b - a)


def ease(t: float) -> float:
    return t * t * (3 - 2 * t)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def glass(size, alpha=1.0, depth=0.0):
    w, h = size
    if w < 2 or h < 2:
        return None
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    grad = Image.new("RGBA", (w, h))
    px = grad.load()
    top, mid, bot = (38, 32, 66), (16, 20, 36), (9, 12, 22)
    for y in range(h):
        u0 = y / h
        for x in range(w):
            u = (x / w + u0) / 2
            c = lerp(top, mid, u / 0.55) if u < 0.55 else lerp(mid, bot, (u - 0.55) / 0.45)
            px[x, y] = c + (int(255 * alpha * (1 - depth * 0.4)),)
    mask = Image.new("L", (w, h), 0)
    r = int(min(w, h) * (0.092 if h > w else 0.28))
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, h - 1), radius=r, fill=255)
    im.paste(grad, (0, 0), mask)
    ImageDraw.Draw(im).rounded_rectangle(
        (0, 0, w - 1, h - 1), radius=r,
        outline=(150, 170, 255, int(255 * alpha * (0.35 - depth * 0.12))),
        width=max(2, h // 160),
    )
    return im


def rotated(canvas, layer, pivot_canvas, pivot_layer, degrees):
    big = Image.new("RGBA", (layer.width * 3, layer.height * 3), (0, 0, 0, 0))
    off = (layer.width, layer.height)
    big.paste(layer, off)
    pivot = (off[0] + pivot_layer[0], off[1] + pivot_layer[1])
    rot = big.rotate(degrees, resample=Image.BICUBIC, center=pivot)
    canvas.alpha_composite(rot, (int(pivot_canvas[0] - pivot[0]), int(pivot_canvas[1] - pivot[1])))


def frame(t: float, w: int, h: int, name_img, claim_img) -> Image.Image:
    im = Image.new("RGBA", (w, h), GROUND + (255,))
    fade = 1.0 - ease(seg(t, *OUT))

    ph = h * 0.46
    pw = ph * PHONE
    cx, cy = w / 2, h * 0.38

    # il respiro dietro
    lit = max(ease(seg(t, *LINE)), ease(seg(t, *WINGS)))
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    r = ph * 0.9
    ImageDraw.Draw(glow).ellipse((cx - r, cy - r, cx + r, cy + r), fill=(120, 140, 255, int(26 * lit * fade)))
    im.alpha_composite(glow.filter(ImageFilter.GaussianBlur(ph * 0.22)))

    # le ali, dietro
    aw = ease(seg(t, *WINGS))
    if aw > 0:
        wing_w, wing_l = pw * 0.92, ph * 0.56
        for side in (-1, 1):
            layer = glass((int(wing_w), int(wing_l)), alpha=aw * fade, depth=0.12)
            if layer is None:
                continue
            rotated(im, layer, (cx + side * pw * 0.34, cy + ph * 0.10),
                    (wing_w / 2, wing_l * 0.42), -side * (30 + 28 * aw))

    # il corpo
    ab = ease(seg(t, *BODY))
    if ab > 0:
        body = glass((int(pw), int(ph)), alpha=ab * fade)
        if body is not None:
            d = ImageDraw.Draw(body)
            bw, bh = body.size
            edge = (170, 190, 255, int(150 * ab * fade))
            iw, ih, ix, iy = bw * 0.22, bh * 0.29, bw * 0.12, bh * 0.05
            lw = max(2, int(bh * 0.007))
            d.rounded_rectangle((ix, iy, ix + iw, iy + ih), radius=int(iw / 2), outline=edge, width=lw)
            for k in range(3):
                lx, ly, rr = ix + iw / 2, iy + ih * (0.19 + 0.31 * k), iw * 0.30
                d.ellipse((lx - rr, ly - rr, lx + rr, ly + rr), fill=(22, 28, 44, int(255 * ab * fade)), outline=edge, width=lw)
            fx, fy, fr = ix + iw * 1.45, iy + ih * 0.26, iw * 0.14
            d.ellipse((fx - fr, fy - fr, fx + fr, fy + fr), outline=edge, width=lw)
            kw = max(3, int(bh * 0.014))
            d.line((bw - 1, bh * 0.22, bw - 1, bh * 0.29), fill=edge, width=kw)
            d.line((bw - 1, bh * 0.33, bw - 1, bh * 0.46), fill=edge, width=kw)
            im.alpha_composite(body, (int(cx - pw / 2), int(cy - ph / 2)))

    # il filo: si scrive dal basso, e resta il bordo destro del telefono
    p = ease(seg(t, *LINE))
    if p > 0:
        x = cx + pw / 2
        y1 = cy + ph / 2 - ph * 0.092
        y0 = cy - ph / 2 + ph * 0.092
        core = max(3, int(ph * 0.011))
        head = y1 - (y1 - y0) * p
        for width, alpha, blur in ((core * 8, 120, ph * 0.05), (core * 3, 200, ph * 0.012), (core, 255, 0)):
            layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
            ld = ImageDraw.Draw(layer)
            steps = 90
            for i in range(steps):
                ya = y1 - (y1 - y0) * (i / steps)
                yb = y1 - (y1 - y0) * ((i + 1) / steps)
                if ya < head:
                    break
                c = lerp(NEON_LOW, NEON_HIGH, (i + 0.5) / steps) + (int(alpha * fade),)
                ld.line((x, ya, x, max(yb, head)), fill=c, width=int(width))
            ld.ellipse((x - width / 2, y1 - width / 2, x + width / 2, y1 + width / 2),
                       fill=NEON_LOW + (int(alpha * fade),))
            if blur:
                layer = layer.filter(ImageFilter.GaussianBlur(blur))
            im.alpha_composite(layer)
        # la punta accesa mentre viaggia
        if p < 1:
            tip = Image.new("RGBA", (w, h), (0, 0, 0, 0))
            c = lerp(NEON_LOW, NEON_HIGH, p)
            rr = core * 3.2
            ImageDraw.Draw(tip).ellipse((x - rr, head - rr, x + rr, head + rr), fill=c + (int(150 * fade),))
            im.alpha_composite(tip.filter(ImageFilter.GaussianBlur(core * 1.6)))

    # il nome, e la riga sotto
    for img, when, y in ((name_img, NAME, h * 0.72), (claim_img, CLAIM, h * 0.82)):
        a = ease(seg(t, *when)) * fade
        if a <= 0:
            continue
        lift = int((1 - ease(seg(t, *when))) * h * 0.012)
        layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        layer.alpha_composite(img, (int((w - img.width) / 2), int(y) + lift))
        im.alpha_composite(Image.blend(Image.new("RGBA", (w, h), (0, 0, 0, 0)), layer, a))
    return im.convert("RGB")


def build(lay: cut.Layout, out: Path) -> Path:
    BUILD.mkdir(parents=True, exist_ok=True)
    frames = BUILD / f"intro_{lay.name}"
    frames.mkdir(exist_ok=True)
    for old in frames.glob("*.png"):
        old.unlink()

    # `cards` scrive allineato a sinistra su una tela larga quanto la colonna del testo,
    # che e' giusto per un sottotitolo di fianco al telefono e sbagliato per un cartello:
    # centrare quella tela lascia la scritta a sinistra. Si ritaglia al segno e si centra.
    def centred(img):
        box = img.getbbox()
        return img.crop(box) if box else img

    name = centred(cards.title("TOTEM WALLET", lay.w, size=int(lay.w * 0.052)))
    claim = centred(cards.subtitle("What you see is what you sign.", lay.w,
                                   size=int(lay.w * 0.019), color=cards.MUTED))

    n = int(END * FPS)
    for i in range(n):
        frame(i / FPS, lay.w, lay.h, name, claim).save(frames / f"{i:04d}.png")
    subprocess.run(
        ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-framerate", str(FPS),
         "-i", str(frames / "%04d.png"), "-c:v", "libx264", "-preset", "medium",
         "-crf", "18", "-pix_fmt", "yuv420p", str(out)],
        check=True,
    )
    return out


if __name__ == "__main__":
    lay = cut.TALL if "--tall" in sys.argv else cut.WIDE
    print("scritto", build(lay, BUILD / f"intro_{lay.name}.mp4"))
