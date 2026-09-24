"""
Il girato, preso dal telefono senza una mano sopra.

`beats.py` dice cosa si vede in ogni scena, `cut.py` la monta. In mezzo mancava
questo: qualcuno che apra l'app, tocchi i posti giusti e registri lo schermo
mentre lo fa. Fatto a mano si sbaglia, e ogni ripresa esce diversa dalla
precedente, il che e' il vero motivo per cui un video da tre minuti si rifa'
venti volte.

Ogni scena e' una lista di gesti. Un gesto e' (cosa, argomenti):

    ("tap", x, y)          tocca
    ("swipe", x1,y1,x2,y2) trascina, 300 ms
    ("wait", secondi)      resta fermo, che e' meta' del montaggio
    ("key", nome)          un tasto di sistema
    ("launch",)            apre l'app da fredda
    ("stop",)              la chiude
    ("text", "Send")       cerca quel testo sullo schermo e tocca il suo centro

Le coordinate fisse si sono rivelate la parte fragile: una riga aperta poco prima
sposta tutto quello che sta sotto, e il tocco successivo finisce su un'altra voce.
`("text", ...)` chiede ad `uiautomator` dov'e' quella scritta adesso. Le scritte
sono quelle inglesi dell'app: se cambiano, cambia qui.

Le coordinate sono in pixel dello schermo vero, 1200 x 2670, lette dal telefono.

**Quello che questo file non puo' fare.** Le scene con una firma vogliono il
dito sul sensore, e un dito non si scrive. Quelle hanno `hands=True` in
`beats.py` e qui non ci sono: si registrano col telefono in mano, con
`python3 record.py --manual <chiave>`, che registra e basta mentre tocchi tu.
Il resto si registra da solo.

Uso:
    python3 scripts/video/record.py            tutte le scene automatiche
    python3 scripts/video/record.py door hook  solo quelle
    python3 scripts/video/record.py --manual sign 25   registra 25 s a mano
"""

from __future__ import annotations

import subprocess
import sys
import time
from pathlib import Path

PKG = "com.clearsign.app"
ACT = f"{PKG}/{PKG}.MainActivity"
OUT = Path(__file__).resolve().parent / "shot"

# La barra in basso, letta sullo schermo da 1200 x 2670.
TABS = {"wallet": 152, "market": 362, "agent": 570, "receipts": 790, "settings": 1030}
TAB_Y = 2502


def sh(*args: str, timeout: float = 60) -> str:
    return subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout).stdout


def tap(x: int, y: int) -> None:
    sh("shell", "input", "tap", str(x), str(y))


def swipe(x1: int, y1: int, x2: int, y2: int, ms: int = 300) -> None:
    sh("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))


def key(name: str) -> None:
    sh("shell", "input", "keyevent", f"KEYCODE_{name.upper()}")


def find(label: str) -> tuple[int, int] | None:
    """Il centro della scritta [label] sullo schermo, adesso. None se non c'e'."""
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml", timeout=30)
    xml = sh("shell", "cat", "/sdcard/ui.xml", timeout=30)
    needle = f'text="{label}"'
    i = xml.find(needle)
    if i < 0:
        i = xml.find(f'content-desc="{label}"')
        if i < 0:
            return None
    j = xml.find('bounds="[', i)
    if j < 0:
        return None
    raw = xml[j + 9: xml.find('"', j + 9)]
    try:
        a, b = raw.split("][")
        x1, y1 = (int(v) for v in a.split(","))
        x2, y2 = (int(v) for v in b.rstrip("]").split(","))
    except ValueError:
        return None
    return (x1 + x2) // 2, (y1 + y2) // 2


def do(step: tuple) -> None:
    kind = step[0]
    if kind == "text":
        at = find(step[1])
        if at is None:
            print(f"    (non trovo «{step[1]}» sullo schermo)")
        else:
            tap(*at)
    elif kind == "tap":
        tap(step[1], step[2])
    elif kind == "swipe":
        swipe(*step[1:])
    elif kind == "key":
        key(step[1])
    elif kind == "wait":
        time.sleep(step[1])
    elif kind == "stop":
        sh("shell", "am", "force-stop", PKG)
    elif kind == "launch":
        sh("shell", "am", "start", "-n", ACT)
    else:
        raise ValueError(f"gesto sconosciuto: {kind}")


def unlocked() -> bool:
    """
    La barra delle schede esiste solo dentro l'app, dopo l'impronta. Vale come prova che
    siamo dentro, non come prova del contrario: Scout e gli altri fogli a schermo intero
    non ce l'hanno, e con questa sola il giro si fermava li'.
    """
    png = OUT / ".probe.png"
    png.parent.mkdir(parents=True, exist_ok=True)
    raw = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=30).stdout
    if not raw:
        return False
    png.write_bytes(raw)
    try:
        from PIL import Image

        r, g, b = Image.open(png).convert("RGB").getpixel((TABS["wallet"], TAB_Y))
    except Exception:
        return False
    # La pillola della scheda scelta: tinta, mai grigia, su ogni tema.
    return abs(r - g) + abs(g - b) > 6 and 12 < r < 230


def in_app() -> bool:
    """La finestra a fuoco e' la nostra. Non dice se e' sbloccata, dice dove siamo."""
    return PKG in sh("shell", "dumpsys", "window", "windows")


def wait_unlocked(limit: float = 180) -> bool:
    end = time.time() + limit
    while time.time() < end:
        if unlocked():
            return True
        time.sleep(2)
    return False


def record(key_name: str, seconds: float, steps: list[tuple]) -> Path:
    """Registra lo schermo mentre esegue [steps]. Torna il file locale."""
    OUT.mkdir(parents=True, exist_ok=True)
    remote = f"/sdcard/beat_{key_name}.mp4"
    sh("shell", "rm", "-f", remote)
    # `--time-limit` e' la rete di sicurezza: se qualcosa si pianta, si ferma da solo.
    rec = subprocess.Popen(
        ["adb", "shell", "screenrecord", "--bit-rate", "16M", "--time-limit",
         str(int(seconds) + 6), remote],
    )
    time.sleep(1.2)   # screenrecord ci mette un attimo ad aprire l'encoder
    try:
        for step in steps:
            do(step)
    finally:
        # SIGINT al processo remoto: screenrecord chiude il file per bene.
        sh("shell", "pkill", "-INT", "-f", "screenrecord")
        rec.wait(timeout=30)
    time.sleep(1.5)   # il muxer scrive l'indice dopo il segnale
    local = OUT / f"beat_{key_name}.mp4"
    sh("pull", remote, str(local), timeout=180)
    sh("shell", "rm", "-f", remote)
    return local


# ---------------------------------------------------------------------------
# Le scene che si registrano da sole. Le chiavi sono quelle di `beats.py`.
# ---------------------------------------------------------------------------

def scene_door() -> list[tuple]:
    """
    L'app da fredda. Il lancio sta dentro la ripresa, non prima: fuori, la porta era
    gia' comparsa quando l'encoder apriva, e di quel che si vede all'avvio non restava
    niente. Il riquadro dell'impronta annerisce il girato, quindi si chiude e si riprende
    da li': il marchio fermo e il tasto per entrare.
    """
    return [("stop",), ("wait", 0.6), ("launch",), ("wait", 2.6), ("key", "back"), ("wait", 4.0)]


def scene_hook() -> list[tuple]:
    """Impostazioni, «Prova un attacco»: la dApp che chiede e non mostra niente."""
    return [
        ("launch",), ("wait", 1.5),                          # l'app davanti, sempre
        ("tap", TABS["wallet"], TAB_Y), ("wait", 1.2),       # da un punto noto
        ("tap", TABS["settings"], TAB_Y), ("wait", 2.0),
        ("text", "Wallet and safety"), ("wait", 2.5),
        ("text", "TRY AN ATTACK"), ("wait", 3.0),
    ]


def scene_receipt() -> list[tuple]:
    """
    Lo scontrino sul drainer: la cifra vera, il rischio in rosso, bloccato.

    Il cammino sta dentro la scena, anche se poi nel montaggio si salta: una scena
    che dava per buono dove l'aveva lasciata la precedente falliva ogni volta che si
    rigirava da sola, ed e' proprio allora che serve.
    """
    return [
        ("launch",), ("wait", 1.5),
        ("tap", TABS["wallet"], TAB_Y), ("wait", 1.2),
        ("tap", TABS["settings"], TAB_Y), ("wait", 2.0),
        ("text", "Wallet and safety"), ("wait", 2.0),
        ("text", "TRY AN ATTACK"), ("wait", 2.5),
        ("text", "Wallet drainer"), ("wait", 3.0),
        ("swipe", 600, 1750, 600, 1150), ("wait", 3.0),
        ("swipe", 600, 1750, 600, 1200), ("wait", 3.5),
    ]


def scene_crowd() -> list[tuple]:
    """Scout: cosa comprano i Seeker adesso, e il censimento."""
    return [
        ("launch",), ("wait", 1.5),
        ("tap", TABS["wallet"], TAB_Y), ("wait", 2.0),
        ("text", "Scout"), ("wait", 5.0),
        ("swipe", 600, 1900, 600, 1200), ("wait", 3.0),
        ("swipe", 600, 1900, 600, 1200), ("wait", 3.0),
    ]


def scene_close() -> list[tuple]:
    """Il registro della giornata, poi il mercato, poi la home."""
    return [
        # La freccia dentro l'app, non il tasto indietro del telefono: quello, su una
        # pagina che non ha niente sopra, esce dall'app, e da li' in poi i tocchi
        # finiscono sull'app di qualcun altro.
        # Mai il tasto indietro del telefono: su una pagina che non ha niente sopra
        # esce dall'app, e da li' in poi i tocchi finiscono sull'app di qualcun altro.
        # La freccia dell'app sta dove sta, anche quando non c'e': un tocco a vuoto
        # sull'intestazione non fa niente.
        ("launch",), ("wait", 1.5), ("tap", 93, 205), ("wait", 1.5),
        ("tap", TABS["receipts"], TAB_Y), ("wait", 3.0),
        ("swipe", 600, 1900, 600, 1200), ("wait", 2.5),
        ("tap", TABS["market"], TAB_Y), ("wait", 3.0),
        ("tap", TABS["wallet"], TAB_Y), ("wait", 3.0),
    ]


AUTO: dict[str, tuple[float, callable]] = {
    "door": (9.0, scene_door),
    "hook": (9.0, scene_hook),
    "receipt": (24.0, scene_receipt),
    "crowd": (16.0, scene_crowd),
    "close": (16.0, scene_close),
}


def main(argv: list[str]) -> int:
    if argv and argv[0] == "--manual":
        name = argv[1]
        seconds = float(argv[2]) if len(argv) > 2 else 20.0
        print(f"registro {seconds:.0f} s: tocca tu, ora.")
        print("scritto", record(name, seconds, [("wait", seconds)]))
        return 0

    names = argv or list(AUTO)
    unknown = [n for n in names if n not in AUTO]
    if unknown:
        print("scene automatiche:", ", ".join(AUTO))
        print("non automatiche (serve il dito):", "sign, budget, gate, hardware")
        print("sconosciute:", ", ".join(unknown))
        return 2

    if "door" not in names and not unlocked():
        print("Sblocca l'app con l'impronta e lasciala aperta, aspetto…")
        if not wait_unlocked():
            print("l'app e' ancora chiusa, mi fermo.")
            return 1

    for name in names:
        seconds, build = AUTO[name]
        print(f"— {name} ({seconds:.0f} s)")
        steps = build()
        if name != "door" and not in_app():
            print("  l'app non e' davanti, la riapro e aspetto l'impronta…")
            sh("shell", "am", "start", "-n", ACT)
            if not wait_unlocked():
                print("  niente da fare, mi fermo.")
                return 1
        path = record(name, seconds, steps)
        size = path.stat().st_size if path.exists() else 0
        print(f"  {path}  {size // 1024} KB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
