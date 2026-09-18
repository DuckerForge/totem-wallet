"""
Il Seeker, disegnato.

Niente mockup scaricato da internet: il telefono si disegna qui, con le misure
vere lette dal telefono stesso il 18/09/2026 (`dumpsys display`):

    schermo   1200 x 2670        rapporto 1 : 2,225
    densita'  480 dpi            cioe' 3x
    foro      pill, x 540->660, y 0->78, in alto al centro

Il foro **non si disegna**: la registrazione dello schermo e' il framebuffer, e
li' dentro la zona del foro e' gia' nera. Disegnarcelo sopra vorrebbe dire
disegnarlo due volte.

Escono due strati e un rettangolo:

    back.png    ombra, corpo, vetro nero          va **sotto** il video
    front.png   il bordo acceso e il riflesso     va **sopra** il video
    rect        dove incastrare il video, al pixel

Due strati e non uno perche' il bordo deve passare sopra il video: e' il bordo
del vetro, non del corpo, e un vetro che finisce sotto l'immagine si vede che e'
finto.

La luce viene da sinistra in alto, come in tutta l'app, e il bordo e' acceso
solo dove la luce lo prende: chiaro in cima, spento a meta', scuro in fondo. E'
la stessa regola della sfera del compagno flottante, cosi' le due cose sembrano
uscite dalla stessa mano.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, asdict
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

# Le misure vere del Seeker.
SCREEN_W = 1200
SCREEN_H = 2670
RATIO = SCREEN_H / SCREEN_W  # 2.225

GROUND = (7, 11, 18)  # #070b12, il fondo dell'app


@dataclass
class Frame:
    """Dove sta il telefono nel fotogramma, e dove sta il suo schermo."""

    body_x: int
    body_y: int
    body_w: int
    body_h: int
    screen_x: int
    screen_y: int
    screen_w: int
    screen_h: int


def _rounded(size, radius, fill):
    img = Image.new("RGBA", size, (0, 0, 0, 0))
    ImageDraw.Draw(img).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius, fill=fill)
    return img


def _vertical_gradient(size, top, bottom):
    """Una sfumatura verticale, riga per riga. Serve al corpo e al bordo."""
    w, h = size
    g = Image.new("RGBA", (1, h))
    px = g.load()
    for y in range(h):
        k = y / max(1, h - 1)
        px[0, y] = tuple(int(top[i] + (bottom[i] - top[i]) * k) for i in range(4))
    return g.resize((w, h))


def build(screen_h: int, out_dir: Path, bezel: int | None = None) -> Frame:
    """
    Disegna il telefono con lo schermo alto [screen_h] e scrive i due strati.

    Il bordo di cornice e' proporzionale: circa due millimetri veri, che a
    schermo pieno sono il tre per cento della larghezza.
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    # La larghezza si ricava dall'altezza, e poi l'altezza si riallinea alla
    # larghezza intera: due arrotondamenti nello stesso verso fanno sbagliare il
    # rapporto di un millesimo, e su uno schermo lungo si vede.
    screen_w = round(screen_h / RATIO)
    screen_h = round(screen_w * RATIO)
    bezel = bezel if bezel is not None else max(6, round(screen_w * 0.031))
    body_w = screen_w + bezel * 2
    body_h = screen_h + bezel * 2
    body_r = round(body_w * 0.105)
    screen_r = max(2, body_r - round(bezel * 0.75))

    # Il fotogramma del telefono ha un margine per l'ombra, che deborda.
    pad = round(body_w * 0.16)
    size = (body_w + pad * 2, body_h + pad * 2)
    bx, by = pad, pad

    # ---- sotto: ombra, corpo, vetro -------------------------------------
    back = Image.new("RGBA", size, (0, 0, 0, 0))

    # L'ombra cade in basso a destra, perche' la luce viene da sinistra in alto.
    shadow = Image.new("RGBA", size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle(
        [bx + round(pad * 0.18), by + round(pad * 0.34), bx + body_w + round(pad * 0.18), by + body_h + round(pad * 0.34)],
        body_r, fill=(0, 0, 0, 165),
    )
    back.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(pad * 0.42)))

    # Il corpo: quasi nero, appena piu' chiaro in alto.
    body = _rounded((body_w, body_h), body_r, (255, 255, 255, 255))
    grad = _vertical_gradient((body_w, body_h), (26, 30, 40, 255), (8, 10, 15, 255))
    grad.putalpha(body.getchannel("A"))
    back.alpha_composite(grad, (bx, by))

    # Il vetro spento, sotto al video: se una clip non copre tutto, sotto c'e'
    # nero e non un buco trasparente.
    glass = _rounded((screen_w, screen_h), screen_r, (0, 0, 0, 255))
    back.alpha_composite(glass, (bx + bezel, by + bezel))
    back.save(out_dir / "phone_back.png")

    # ---- sopra: il bordo del vetro e il riflesso -------------------------
    front = Image.new("RGBA", size, (0, 0, 0, 0))

    # Il filo di luce intorno al vetro. Chiaro in cima, spento a meta', scuro in
    # fondo: e' un bordo illuminato, non una cornice disegnata.
    ring = Image.new("RGBA", (screen_w, screen_h), (0, 0, 0, 0))
    ImageDraw.Draw(ring).rounded_rectangle(
        [0, 0, screen_w - 1, screen_h - 1], screen_r, outline=(255, 255, 255, 255), width=max(2, bezel // 5),
    )
    light = _vertical_gradient((screen_w, screen_h), (255, 255, 255, 130), (120, 130, 150, 28))
    light.putalpha(Image.eval(ring.getchannel("A"), lambda a: a).point(lambda a: a))
    ring = Image.composite(light, Image.new("RGBA", ring.size, (0, 0, 0, 0)), ring.getchannel("A"))
    front.alpha_composite(ring, (bx + bezel, by + bezel))

    # Il bordo esterno del corpo, lo stesso trattamento un filo piu' sobrio.
    edge = Image.new("RGBA", (body_w, body_h), (0, 0, 0, 0))
    ImageDraw.Draw(edge).rounded_rectangle(
        [0, 0, body_w - 1, body_h - 1], body_r, outline=(255, 255, 255, 255), width=max(2, bezel // 4),
    )
    elight = _vertical_gradient((body_w, body_h), (255, 255, 255, 96), (0, 0, 0, 120))
    edge = Image.composite(elight, Image.new("RGBA", edge.size, (0, 0, 0, 0)), edge.getchannel("A"))
    front.alpha_composite(edge, (bx, by))

    front.save(out_dir / "phone_front.png")

    frame = Frame(
        body_x=bx, body_y=by, body_w=body_w, body_h=body_h,
        screen_x=bx + bezel, screen_y=by + bezel, screen_w=screen_w, screen_h=screen_h,
    )
    (out_dir / "phone.json").write_text(json.dumps(asdict(frame), indent=1))
    return frame


if __name__ == "__main__":
    import sys

    h = int(sys.argv[1]) if len(sys.argv) > 1 else 960
    where = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("build")
    f = build(h, where)
    print(json.dumps(asdict(f), indent=1))
    print("rapporto schermo:", round(f.screen_h / f.screen_w, 4), "(vero: 2.225)")
