"""
Il montaggio: una scena alla volta, poi cucite.

Il piano diceva "un grafo solo, senza file intermedi". Scrivendolo si e' visto
che e' la scelta sbagliata: con un grafo solo, per rifare tre secondi di una
scena si rirende tutto il video, e un video da tre minuti si rifa' venti volte
prima di essere buono. Una scena alla volta si rifa' in sei secondi, e `concat`
le rimette insieme senza ricomprimere niente (`-c copy`).

Ogni scena e' quattro strati:

    1. il fondo e il corpo del telefono   una PNG sola, gia' composta qui
    2. la registrazione dello schermo     dentro il rettangolo del vetro
    3. il filo di luce del vetro          sopra il video, se no il vetro e' finto
    4. il sottotitolo                     con dissolvenza in entrata e in uscita

Due disposizioni dallo stesso girato: orizzontale il telefono a sinistra e il
testo a destra, verticale il telefono in mezzo e il testo sotto.
"""

from __future__ import annotations

import json
import subprocess
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw

import cards
import phone

GROUND = (7, 11, 18, 255)


@dataclass
class Layout:
    name: str
    w: int
    h: int
    screen_h: int
    phone_x: int
    phone_y: int
    text_x: int
    text_y: int
    text_w: int
    text_size: int


WIDE = Layout("16x9", 1920, 1080, 940, 118, 70, 760, 330, 1000, 44)
TALL = Layout("9x16", 1080, 1920, 1280, 96, 250, 96, 1640, 888, 46)


def background(lay: Layout, out: Path) -> Path:
    """
    Il fondo: il nero dell'app con un alone del marchio, viola da una parte e
    ciano dall'altra, molto spento. Non deve farsi guardare: dietro c'e' un
    telefono, e il telefono e' il video.
    """
    img = Image.new("RGBA", (lay.w, lay.h), GROUND)
    glow = Image.new("RGBA", (lay.w, lay.h), (0, 0, 0, 0))
    d = ImageDraw.Draw(glow)
    step = 6
    for x in range(0, lay.w, step):
        k = x / lay.w
        d.rectangle(
            [x, 0, x + step, lay.h],
            fill=(
                int(0x95 + (0x0B - 0x95) * k),
                int(0x24 + (0xF5 - 0x24) * k),
                int(0xF3 + (0xEC - 0xF3) * k),
                30,
            ),
        )
    img.alpha_composite(glow)
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)
    return out


def stage(lay: Layout, work: Path) -> dict:
    """Prepara fondo, telefono e maschera per una disposizione. Una volta sola."""
    d = work / lay.name
    d.mkdir(parents=True, exist_ok=True)
    f = phone.build(lay.screen_h, d)
    back = Image.open(d / "phone_back.png").convert("RGBA")
    front = Image.open(d / "phone_front.png").convert("RGBA")

    base = Image.open(background(lay, d / "bg.png")).convert("RGBA")
    base.alpha_composite(back, (lay.phone_x, lay.phone_y))
    base.convert("RGB").save(d / "base.png")

    over = Image.new("RGBA", (lay.w, lay.h), (0, 0, 0, 0))
    over.alpha_composite(front, (lay.phone_x, lay.phone_y))
    over.save(d / "over.png")

    # Gli angoli tondi del vetro: il video ci va dentro, non fino allo spigolo.
    mask = Image.new("L", (f.screen_w, f.screen_h), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, f.screen_w - 1, f.screen_h - 1], round(f.screen_w * 0.075), fill=255,
    )
    rgba = Image.new("RGBA", (f.screen_w, f.screen_h), (255, 255, 255, 255))
    rgba.putalpha(mask)
    rgba.save(d / "screenmask.png")

    return {
        "dir": d, "frame": f,
        "sx": lay.phone_x + f.screen_x, "sy": lay.phone_y + f.screen_y,
    }


def scene(lay: Layout, st: dict, clip: Path, seconds: float, text: str | None,
          label: str | None, out: Path, start: float = 0.0, fps: int = 30) -> Path:
    """Una scena montata, pronta per essere cucita alle altre."""
    d = st["dir"]
    f = st["frame"]
    card = None
    if text:
        img = cards.subtitle(text, lay.text_w, size=lay.text_size, label=label)
        card = d / f"card_{out.stem}.png"
        cards.save(img, card)

    # Il video: scalato alla larghezza del vetro, tagliato al centro se avanza,
    # e con gli angoli tondi presi dalla maschera.
    # Dimensioni forzate, non "copri e taglia".
    #
    # `force_original_aspect_ratio=increase` arrotondava per difetto e sputava
    # 422x938 dentro una maschera da 422x939: un pixel, e ffmpeg si ferma.
    # Ma la sorgente **ha gia' esattamente** il rapporto del vetro, perche' e'
    # lo schermo di quel telefono: forzare le misure non deforma niente e
    # toglie di mezzo tutta la questione.
    chain = [
        f"[1:v]scale={f.screen_w}:{f.screen_h},setsar=1[scr]",
        "[3:v]alphaextract[am]",
        "[scr][am]alphamerge[scrm]",
        f"[0:v][scrm]overlay={st['sx']}:{st['sy']}[a]",
        "[a][2:v]overlay=0:0[b]",
    ]
    last = "b"
    ins = ["-loop", "1", "-i", str(d / "base.png"),
           "-ss", f"{start}", "-t", f"{seconds}", "-i", str(clip),
           "-loop", "1", "-i", str(d / "over.png"),
           "-loop", "1", "-i", str(d / "screenmask.png")]
    if card:
        ins += ["-loop", "1", "-i", str(card)]
        # Il sottotitolo entra e esce in mezzo secondo: una scritta che appare
        # di colpo si legge come un errore.
        chain.append(
            f"[4:v]format=rgba,fade=t=in:st=0:d=0.5:alpha=1,"
            f"fade=t=out:st={max(0.1, seconds - 0.5):.2f}:d=0.5:alpha=1[cd]"
        )
        chain.append(f"[{last}][cd]overlay={lay.text_x}:{lay.text_y}[c]")
        last = "c"

    out.parent.mkdir(parents=True, exist_ok=True)
    cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", *ins,
           "-filter_complex", ";".join(chain), "-map", f"[{last}]",
           "-t", f"{seconds}", "-r", str(fps),
           "-c:v", "libx264", "-preset", "medium", "-crf", "17",
           "-pix_fmt", "yuv420p", str(out)]
    subprocess.run(cmd, check=True)
    return out


def stitch(parts: list[Path], out: Path) -> Path:
    """Cuce senza ricomprimere: quello che e' stato reso non si tocca piu'."""
    lst = out.parent / (out.stem + ".txt")
    lst.write_text("".join(f"file '{p.resolve()}'\n" for p in parts))
    subprocess.run(
        ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
         "-f", "concat", "-safe", "0", "-i", str(lst), "-c", "copy", str(out)],
        check=True,
    )
    return out


if __name__ == "__main__":
    import sys

    # Prova: una scena sola, nei due formati, da una registrazione qualsiasi.
    clip = Path(sys.argv[1])
    work = Path(sys.argv[2]) if len(sys.argv) > 2 else Path("build")
    for lay in (WIDE, TALL):
        st = stage(lay, work)
        o = scene(
            lay, st, clip, 5.0,
            "Every transaction is simulated on chain first. The receipt is built from "
            "what the network says will happen, not from what the app claims.",
            "02 · the receipt", work / f"prova_{lay.name}.mp4",
        )
        print("scritto", o)
