"""
Le scritte: sottotitoli e cartelli, come PNG con l'alpha.

Usa i caratteri dell'app, non quelli di sistema: `sora.ttf` per i titoli,
`inter.ttf` per il corpo, `jetbrains_mono.ttf` per i numeri e le etichette.
Sono gli stessi file che finiscono nell'APK, quindi il video e' scritto con la
stessa mano dell'app invece che con il carattere che capita sul portatile.

Perche' PNG e non `drawtext` di ffmpeg: `drawtext` va a capo male, non sa
spaziare le lettere e litiga con gli apostrofi nella riga di comando. Un'
immagine e' fatta una volta, si guarda, e da li' in poi non cambia piu'.
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

REPO = Path(__file__).resolve().parents[2]
FONTS = REPO / "app/src/main/res/font"

INK = (232, 238, 248, 255)
MUTED = (150, 163, 185, 255)
MINT = (77, 255, 208, 255)
VIOLET = (149, 36, 243, 255)


def font(name: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONTS / name), size)


def _wrap(draw, text, fnt, max_w):
    """A capo a mano: si misura parola per parola, come farebbe un tipografo."""
    words, lines, cur = text.split(), [], ""
    for w in words:
        probe = (cur + " " + w).strip()
        if draw.textlength(probe, font=fnt) <= max_w or not cur:
            cur = probe
        else:
            lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines


def subtitle(text: str, width: int, size: int = 44, color=INK, name: str = "inter.ttf",
             leading: float = 1.42, label: str | None = None) -> Image.Image:
    """
    Una battuta della scaletta, su piu' righe, allineata a sinistra.

    Sopra puo' esserci un'etichetta corta in monospaziato — "01 · THE RECEIPT" —
    che serve a chi guarda per sapere a che punto del racconto sta.
    """
    fnt = font(name, size)
    probe = Image.new("RGBA", (10, 10))
    d = ImageDraw.Draw(probe)
    lines = _wrap(d, text, fnt, width)
    line_h = round(size * leading)

    lab_h = round(size * 0.9) if label else 0
    h = lab_h + line_h * len(lines) + round(size * 0.4)
    img = Image.new("RGBA", (width, h), (0, 0, 0, 0))
    dr = ImageDraw.Draw(img)

    y = 0
    if label:
        lf = font("jetbrains_mono.ttf", round(size * 0.40))
        # Le lettere larghe: un'etichetta corta tutta maiuscola si legge meglio
        # se respira, ed e' lo stesso trattamento del tasto di accensione.
        x = 0
        for ch in label.upper():
            dr.text((x, y), ch, font=lf, fill=MINT)
            x += dr.textlength(ch, font=lf) + size * 0.10
        y += lab_h

    for ln in lines:
        dr.text((0, y), ln, font=fnt, fill=color)
        y += line_h
    return img


def title(text: str, width: int, size: int = 92) -> Image.Image:
    """Un cartello grande, per l'apertura e la chiusura."""
    return subtitle(text, width, size=size, name="sora.ttf", leading=1.16)


def save(img: Image.Image, path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)
    return path


if __name__ == "__main__":
    import sys

    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("build/cards")
    save(
        subtitle(
            "Every transaction is simulated on chain first. The receipt is built from "
            "what the network says will happen, not from what the app claims.",
            width=1000, label="02 · the receipt",
        ),
        out / "prova.png",
    )
    print("scritto", out / "prova.png")
