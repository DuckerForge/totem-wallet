"""
Il video, dall'inizio alla fine, con un comando.

`beats.py` dice cosa va detto, `voice.py` lo dice e lo misura, `record.py` lo
gira, `cut.py` monta la singola scena. Questo tiene il filo.

**Il tempo lo decide la voce.** Una scena dura quanto la sua frase letta ad alta
voce piu' un respiro, non quanto diceva un numero scritto nella scaletta: quei
numeri erano stime, e una stima sbagliata di due secondi lascia la voce sopra
un'immagine gia' cambiata. Se il girato e' piu' corto della frase si congela
l'ultimo fotogramma invece di tagliare le parole; se e' piu' lungo, avanza.

Le scene che mancano non fermano niente: si stampa quali sono e si monta il
resto, perche' un video incompleto che si guarda dice piu' di un errore.

Uso:
    python3 scripts/video/make.py                 monta quello che c'e'
    python3 scripts/video/make.py --record        prima gira le scene automatiche
    python3 scripts/video/make.py --tall          verticale invece di 16:9
    python3 scripts/video/make.py --mute          senza voce
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import beats
import cut
import intro
import record
import voice

# Quanto dura una dissolvenza fra due scene. Mezzo secondo: si sente che e' un taglio
# voluto e non si perde tempo. Le scene si sovrappongono per questo tratto, quindi la
# voce di ognuna va anticipata della somma delle dissolvenze che la precedono.
FADE = 0.5

HERE = Path(__file__).resolve().parent
SHOT = HERE / "shot"
BUILD = HERE / "build"


def run(*args: str) -> None:
    subprocess.run([*args], check=True, capture_output=True)


def padded(clip: Path, need: float, start: float, work: Path) -> Path:
    """
    Il girato allungato fino a [need] secondi congelando l'ultimo fotogramma.
    Serve quando la frase dura piu' di quello che si e' riusciti a riprendere:
    meglio un'immagine ferma che una parola tagliata a meta'.
    """
    have = voice.duration(clip) - start
    if have >= need - 0.05:
        return clip
    out = work / f"pad_{clip.stem}.mp4"
    run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", str(clip),
        "-vf", f"tpad=stop_mode=clone:stop_duration={need - have + 0.3:.2f}",
        "-an", "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", str(out))
    return out


def crossfade(parts: list[Path], out: Path, d: float = FADE) -> Path:
    """
    Cuce con una dissolvenza fra una scena e l'altra invece che di netto.

    `concat -c copy` non ricomprime niente ed e' la via giusta quando le scene si
    attaccano; per sovrapporle serve `xfade`, che invece ricomprime. Si paga una volta,
    alla fine, e in cambio il video non sbatte da una schermata all'altra.
    """
    if len(parts) == 1:
        return cut.stitch(parts, out)
    args = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error"]
    for p in parts:
        args += ["-i", str(p)]
    lens = [voice.duration(p) for p in parts]
    chain, prev, at = [], "[0:v]", 0.0
    for i in range(1, len(parts)):
        at += lens[i - 1] - d
        tag = f"[v{i}]"
        chain.append(f"{prev}[{i}:v]xfade=transition=fade:duration={d}:offset={at:.3f}{tag}")
        prev = tag
    args += ["-filter_complex", ";".join(chain), "-map", prev,
             "-c:v", "libx264", "-preset", "medium", "-crf", "19", "-pix_fmt", "yuv420p", str(out)]
    run(*args)
    return out


def audio(plan: list[tuple[float, Path | None]], total: float, out: Path) -> Path | None:
    """Una traccia sola: ogni voce al secondo in cui comincia la sua scena."""
    heard = [(at, w) for at, w in plan if w is not None]
    if not heard:
        return None
    args = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error"]
    for _, w in heard:
        args += ["-i", str(w)]
    # Ogni voce ritardata al suo posto, poi sommate. `adelay` vuole millisecondi.
    parts = "".join(
        f"[{i}:a]adelay={int(at * 1000)}|{int(at * 1000)},apad[a{i}];"
        for i, (at, _) in enumerate(heard)
    )
    mix = "".join(f"[a{i}]" for i in range(len(heard)))
    args += ["-filter_complex",
             f"{parts}{mix}amix=inputs={len(heard)}:normalize=0,atrim=0:{total:.2f}[out]",
             "-map", "[out]", "-c:a", "aac", "-b:a", "160k", str(out)]
    run(*args)
    return out


def main(argv: list[str]) -> int:
    lay = cut.TALL if "--tall" in argv else cut.WIDE
    if "--record" in argv:
        rc = record.main([])
        if rc != 0:
            return rc

    BUILD.mkdir(parents=True, exist_ok=True)
    st = cut.stage(lay, BUILD)
    tracks = {} if "--mute" in argv else voice.all_tracks()

    # L'apertura: il marchio che si scrive, disegnato qui perche' sul telefono e' coperto
    # dal riquadro dell'impronta.
    opening = BUILD / f"intro_{lay.name}.mp4"
    if "--no-intro" not in argv and not opening.exists():
        intro.build(lay, opening)
    parts: list[Path] = [] if "--no-intro" in argv else [opening]
    plan: list[tuple[float, Path | None]] = []
    missing: list[str] = []
    at = voice.duration(opening) - FADE if parts else 0.0
    for b in beats.SCRIPT:
        clip = SHOT / f"beat_{b.key}.mp4"
        if not clip.exists():
            missing.append(b.key)
            continue
        wav, said = tracks.get(b.key, (None, b.seconds))
        # Quanto dura la scena: almeno la frase, ma se la scaletta le da' piu' tempo e il
        # girato ce l'ha, si prende quello. Una frase corta su una cosa lunga da guardare
        # la faceva sparire in tre secondi.
        have0 = voice.duration(clip) - b.start
        want = max(said, min(b.seconds, have0))
        source = padded(clip, want, b.start, BUILD)
        have = voice.duration(source) - b.start
        seconds = max(1.0, min(want, have - 0.15))
        out = BUILD / f"scene_{b.key}_{lay.name}.mp4"
        cut.scene(lay, st, source, seconds, b.text, b.label, out, start=b.start)
        parts.append(out)
        # La voce parte un quarto di secondo dopo lo stacco: entrare sulla
        # prima immagine suona come se stesse gia' parlando da prima.
        plan.append((at + 0.35, wav))
        at += seconds - FADE
        print(f"  {b.key:<9} {seconds:>5.1f}s")

    if not parts:
        print("nessun girato in", SHOT)
        return 1

    silent = BUILD / f"silent_{lay.name}.mp4"
    crossfade(parts, silent)
    final = BUILD / f"totem_{lay.name}.mp4"
    voiced = audio(plan, at, BUILD / "voice.m4a")
    if voiced is None:
        silent.replace(final)
    else:
        run("ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-i", str(silent), "-i", str(voiced),
            "-map", "0:v", "-map", "1:a", "-c:v", "copy", "-c:a", "copy",
            "-shortest", str(final))

    m, s = divmod(int(voice.duration(final)), 60)
    print(f"\n{final}   {m}:{s:02d}")
    if missing:
        print("mancano (serve il dito sul sensore):", ", ".join(missing))
        print("si girano con:  python3 scripts/video/record.py --manual <chiave> <secondi>")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
