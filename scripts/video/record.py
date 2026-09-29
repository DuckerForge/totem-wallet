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
    ("start", "pkg/.Act")  apre un'altra app, per la scena dell'agente
    ("text", "Send")       cerca quel testo sullo schermo e tocca il suo centro
    ("seek", "ORE")        scorre finche' non lo trova, poi lo tocca
    ("close",)             chiude il foglio aperto, qualunque sia, e niente se non c'e' ne'uno

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

import html
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = "com.clearsign.app"
ACT = f"{PKG}/{PKG}.MainActivity"
# La dApp di prova, che nella scena dell'Agent Gate fa la parte dell'agente.
TESTER = "com.clearsign.tester/.MainActivity"
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


def dump() -> str:
    """The screen as uiautomator reads it now: the focused window only, visible nodes only."""
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml", timeout=30)
    return sh("shell", "cat", "/sdcard/ui.xml", timeout=30)


_BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def nodes(xml: str) -> list[dict]:
    """
    Every node of a dump in document order: text and content-desc unescaped, box, centre,
    clickable, enabled, and the index of its parent. Empty when the dump is not XML.
    """
    a, b = xml.find("<hierarchy"), xml.rfind("</hierarchy>")
    if a < 0 or b < 0:
        return []
    # A control character in some app's text is not XML and would sink the whole read.
    body = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]|&#(?:[0-8]|1[124-9]|2\d|3[01]);", "", xml[a:b + len("</hierarchy>")])
    try:
        root = ET.fromstring(body)
    except ET.ParseError:
        return []
    out: list[dict] = []

    def walk(el: ET.Element, parent: int) -> None:
        for child in el:
            if child.tag != "node":
                continue
            m = _BOUNDS.fullmatch(child.get("bounds", ""))
            box = tuple(int(v) for v in m.groups()) if m else (0, 0, 0, 0)
            out.append({"i": len(out), "parent": parent,
                        "text": child.get("text", ""), "desc": child.get("content-desc", ""),
                        "box": box, "xy": ((box[0] + box[2]) // 2, (box[1] + box[3]) // 2),
                        "clickable": child.get("clickable") == "true",
                        "enabled": child.get("enabled") != "false"})
            walk(child, len(out) - 1)

    walk(root, -1)
    return out


def find_all(label: str, xml: str | None = None) -> list[dict]:
    """
    The nodes whose text is [label], or failing that whose content-desc is. The label can
    be written the way the dump writes it ("Review &amp; sign") or plainly.
    """
    ns = nodes(dump() if xml is None else xml)
    want = html.unescape(label)
    return [n for n in ns if n["text"] == want] or [n for n in ns if n["desc"] == want]


def find_re(pattern: str, xml: str | None = None) -> list[tuple[re.Match, dict]]:
    """The nodes whose text matches [pattern], with the match."""
    out = []
    for n in nodes(dump() if xml is None else xml):
        m = re.search(pattern, n["text"])
        if m:
            out.append((m, n))
    return out


def enabled(ns: list[dict], node: dict) -> bool:
    """Whether [node] can be pressed: the first clickable node from it upwards decides."""
    j = node["i"]
    while j >= 0:
        if not ns[j]["enabled"]:
            return False
        if ns[j]["clickable"]:
            return True
        j = ns[j]["parent"]
    return node["enabled"]


def wait_for(check, timeout: float, every: float = 0.4):
    """Ask [check] until it answers something, or [timeout] seconds pass. Returns the answer or None."""
    end = time.time() + timeout
    while True:
        got = check()
        if got:
            return got
        if time.time() >= end:
            return None
        time.sleep(every)


def find(label: str, last: bool = False) -> tuple[int, int] | None:
    """Il centro della scritta [label] sullo schermo, adesso. None se non c'e'."""
    hit = find_all(label)
    if not hit:
        return None
    return (hit[-1] if last else hit[0])["xy"]


# When the current take started, for the ("at", ...) steps.
T0 = 0.0
# Where labels were on screen at the last ("scan", ...), for ("at", T, "seen", label).
SEEN: dict[str, tuple[int, int]] = {}


def scan(labels: list[str]) -> None:
    """One screen read for several labels: each lookup costs seconds, one read costs one."""
    xml = dump()
    for label in labels:
        hit = find_all(label, xml)
        if hit:
            SEEN[label] = hit[0]["xy"]
        else:
            SEEN.pop(label, None)


# Sheets with no X of their own, told by what they say: a drag down from high up closes
# them. ORE, a wallet page from Scout, the empty account fix, the agent's rules. The ORE
# sheet draws its own status bar, so the old tap on the veil at 600,110 landed inside it.
SWIPE_SHUT = (
    "one round a minute", 'text="Emptiest squares now:"', 'text="Tap the squares below"', "Hold to dig",
    'text="WHAT THEY HOLD RIGHT NOW"', "Tap the star to follow", "You follow this wallet.",
    'text="Close empty account"', 'text="Revoke delegation"', 'text="Burn token"',
    "What the agent may sign without you", 'text="YOUR OWN RULES"', 'text="Load a file"',
    'text="Ask me above"', 'text="Per move"', 'text="Per day"',
)
# A coin opened from the market: no Back label and no X, system back is the way out. Told
# by words only that sheet has, the what-if too for when it is scrolled down.
COIN_SHEET = ('text="Save and follow"', 'text="Tell me when it moves"',
              "What if it were as big as", "Arithmetic, not a forecast")
# The numbered list of "Your defenses" under its title: only that sheet has both.
DEFENSES = re.compile(r'text="\d+\. [^"]+"')


def shut_sheet() -> None:
    swipe(600, 500, 600, 2400)


def _close_one(xml: str) -> bool:
    """Close the top thing once, by its own way out. False when there was nothing to close."""
    at = find_all("Back", xml) if 'text="Back"' in xml else []
    if at:
        tap(*at[0]["xy"])
    elif any(m in xml for m in SWIPE_SHUT):
        shut_sheet()
    elif "active wallets watched" in xml:
        # Scout's arrow, by its description. A page scrolled away still has it: it sits above the list.
        arrow = find_all("Back", xml)
        tap(*(arrow[0]["xy"] if arrow else (93, 205)))
    elif any(m in xml for m in COIN_SHEET):
        # A coin opened from the market has no Back label: every scene after it stayed
        # stuck there. System back only here, anywhere else it leaves the app and locks it.
        key("back")
    elif ('text="Your defenses"' in xml and DEFENSES.search(xml)) or 'text="Tap to be paid"' in xml:
        # Two more sheets with no X; certainly open when this is on screen.
        key("back")
    elif 'content-desc="Close"' in xml:
        # The sheets' own X (swap, receive): tapping it never leaves the app.
        tap(*[n for n in nodes(xml) if n["desc"] == "Close"][0]["xy"])
    else:
        return False
    return True


def do(step: tuple) -> None:
    kind = step[0]
    if kind == "scan":
        scan(list(step[1:]))
        return
    if kind == "at":
        # ("at", T, "text", label[, hint, "left"|"right"]) taps [label] at T seconds into the
        # take; ("at", T, "swipe", x1, y1, x2, y2[, ms]) swipes then, 300 ms unless said. A
        # swipe of D ms keeps the take busy about D + 0.5 s. Looking a label up costs two
        # to four seconds, so it is looked up first and the tap waits for its second: the
        # screen changes on the word, not four seconds after it. A label out of a sideways
        # row is brought in by swiping the row where [hint] sits.
        at, what = step[1], step[2]
        pos = None
        if what == "text":
            time.sleep(0.8)  # let the previous tap land before reading the screen
            pos = find(step[3])
            if pos is None and len(step) > 4:
                hint = find(step[4])
                if hint is not None:
                    a, b = (1000, 250) if step[5] == "left" else (250, 1000)
                    swipe(a, hint[1], b, hint[1], 250)
                    time.sleep(0.8)
                    pos = find(step[3])
            if pos is None:
                print(f"    (non trovo «{step[3]}» sullo schermo)")
                return
        elif what == "xy":
            pos = (step[3], step[4])
        elif what == "seen":
            pos = SEEN.get(step[3])
            if pos is None:
                print(f"    (non ho visto «{step[3]}» nell'ultima lettura)")
                return
        elif what == "key":
            pos = None
        late = time.time() - T0 - at
        if late < 0:
            time.sleep(-late)
        # Never touch anything outside Totem: an edge swipe once took the take to the
        # Android home and recorded the other apps.
        if not in_app():
            raise RuntimeError("Totem is not in front: stopping the take")
        elif late > 0.3:
            print(f"    ({what} {step[3:4]} arrives {late:.1f}s late)")
        if what == "key":
            key(step[3])
        elif pos is not None:
            tap(*pos)
        else:
            # The duration too: without it every drag was a 300 ms fling.
            swipe(*step[3:8])
        return
    if kind == "close":
        # Chiudere «quello che c'e' sopra» non e' un punto sullo schermo: in alto a
        # sinistra la home ha l'interruttore della luce e Scout ha la freccia, e toccare
        # li' alla cieca cambiava tema a ogni scena. Si guarda prima cosa c'e'.
        # A few passes: Back on a review only goes back to its form, the X comes after.
        for _ in range(3):
            if not _close_one(dump()):
                return
            time.sleep(1.2)
        return
    if kind == "seek":
        # Scorre finche' la scritta non compare. Un tocco su un'intestazione che apre e
        # chiude e' un interruttore, non un comando: toccarla quando la sezione era gia'
        # aperta la chiudeva, e la cosa da riprendere spariva.
        for _ in range(int(step[2]) if len(step) > 2 else 5):
            at = find(step[1])
            if at is not None:
                tap(*at)
                return
            swipe(600, 1900, 600, 1250)
            time.sleep(1.1)
        print(f"    (non trovo «{step[1]}» nemmeno scorrendo)")
    elif kind in ("text", "textlast"):
        # textlast: the same words twice on screen, like a sheet titled Start with a Start button.
        at = find(step[1], last=kind == "textlast")
        if at is None:
            print(f"    (non trovo «{step[1]}» sullo schermo)")
        else:
            tap(*at)
    elif kind == "tap":
        tap(step[1], step[2])
        if step[2] == TAB_Y:
            # A tab keeps its scroll: the home left at the bottom by the ORE scene hid
            # Receive, Swap and Bridge from every scene after it. Back to the top.
            time.sleep(0.6)
            for _ in range(3):
                swipe(600, 700, 600, 2100, 150)
                time.sleep(0.3)
    elif kind == "swipe":
        swipe(*step[1:])
    elif kind == "key":
        key(step[1])
    elif kind == "wait":
        time.sleep(step[1])
    elif kind == "stop":
        sh("shell", "am", "force-stop", PKG)
    elif kind == "launch":
        # `am start` on a running Totem stacks a second copy on top: the first one stops,
        # which locks it, and the new one opens at the door. Only launch when not in front.
        if not in_app():
            sh("shell", "am", "start", "-n", ACT)
    elif kind == "start":
        sh("shell", "am", "start", "-n", step[1])
    else:
        raise ValueError(f"gesto sconosciuto: {kind}")


def unlocked() -> bool:
    """
    Siamo dentro, non alla porta. Prima si guardava il colore di un pixel della pillola
    della scheda scelta, e su un tema col fondo nero quel pixel e' nero come tutto il
    resto, quindi diceva sempre di no.
    """
    if not in_app():
        return False
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml", timeout=30)
    xml = sh("shell", "cat", "/sdcard/ui.xml", timeout=30)
    # La domanda giusta e' «e' alla porta?», non «c'e' la barra delle schede»: meta'
    # dell'app sono fogli a schermo intero che la barra non ce l'hanno, e con quella
    # domanda il giro si fermava su Scout dicendo che l'app era chiusa.
    door = ('text="Unlock"', 'text="Connect Seed Vault"', 'text="TOTEM WALLET"')
    return not any(d in xml for d in door)


def in_app() -> bool:
    """La finestra a fuoco e' la nostra. Non dice se e' sbloccata, dice dove siamo."""
    # The focused window, not any window: the bubble is ours too and floats over everything.
    return any(PKG in line for line in sh("shell", "dumpsys", "window").splitlines() if "mCurrentFocus" in line)


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
    # Lo schermo che si spegne a meta' ripresa manda l'app in secondo piano, e l'app in
    # secondo piano si richiude: meta' delle scene erano la porta chiusa a chiave.
    key("wakeup")
    remote = f"/sdcard/beat_{key_name}.mp4"
    sh("shell", "rm", "-f", remote)
    # `--time-limit` e' la rete di sicurezza: se qualcosa si pianta, si ferma da solo.
    rec = subprocess.Popen(
        ["adb", "shell", "screenrecord", "--bit-rate", "16M", "--time-limit",
         str(int(seconds) + 40), remote],
    )
    global T0
    T0 = time.time()
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

def scene_receipt() -> list[tuple]:
    """
    Lo scontrino sul drainer: la cifra vera, il rischio in rosso, la firma bloccata.

    Il cammino sta dentro la scena, anche se poi nel montaggio si salta: una scena che dava
    per buono dove l'aveva lasciata la precedente falliva ogni volta che si rigirava da sola,
    ed e' proprio allora che serve.
    """
    return [
        ("launch",), ("wait", 1.5), ("close",), ("wait", 1.2),
        ("tap", TABS["wallet"], TAB_Y), ("wait", 1.2),
        ("tap", TABS["settings"], TAB_Y), ("wait", 2.0),
        ("text", "Wallet and safety"), ("wait", 2.0),
        ("text", "TRY AN ATTACK"), ("wait", 2.5),
        ("text", "Wallet drainer"), ("wait", 5.0),
        ("swipe", 600, 1750, 600, 1250), ("wait", 4.0),
    ]


def scene_gate() -> list[tuple]:
    """
    L'agente che mente. La dApp di prova fa da agente: dichiara uno swap e manda un invio,
    e il portafoglio la smentisce. Nessuna firma: il bugiardo non ci arriva.
    """
    return [
        ("close",), ("wait", 1.0),
        ("start", TESTER), ("wait", 3.0),
        ("text", "Agent Gate, lying agent"), ("wait", 6.0),
        ("swipe", 600, 1800, 600, 1300), ("wait", 4.0),
    ]


def scene_bubble() -> list[tuple]:
    """
    La bolla sopra un'altra app, e il widget. La bolla deve essere gia' accesa: il permesso
    di disegnare sopra le altre app si da' una volta a mano, e qui non si puo' chiedere.
    """
    return [
        ("launch",), ("wait", 1.5), ("close",), ("wait", 1.0),
        ("key", "home"), ("wait", 3.0),
        ("swipe", 300, 1300, 900, 1300), ("wait", 3.0),
        ("key", "home"), ("wait", 3.0),
    ]


def scene_ore() -> list[tuple]:
    """
    La griglia di ORE. Nessuna firma: si apre e si guarda, le probabilita' vere lette dal
    programma. E' la scena del premio ORE.

    La scheda ORE sta in fondo al portafoglio, sotto «IN DEFI», e la sezione puo' essere
    chiusa: si cerca scorrendo invece di toccare l'intestazione, che e' un interruttore.
    """
    return [
        ("launch",), ("wait", 1.5), ("close",), ("wait", 1.2),
        ("tap", TABS["wallet"], TAB_Y), ("wait", 2.0),
        ("seek", "ORE", 6), ("wait", 6.0),
        ("swipe", 600, 1900, 600, 1300), ("wait", 4.0),
        ("swipe", 600, 1900, 600, 1400), ("wait", 4.0),
        ("swipe", 600, 1400, 600, 1900), ("wait", 3.0),
    ]


def scene_crowd() -> list[tuple]:
    """
    Scout: prima il censimento, che e' il numero che vale il premio SKR, poi la diretta,
    le balene e la pagina di una persona.
    """
    return [
        ("launch",), ("wait", 1.5), ("close",), ("wait", 1.2),
        ("tap", TABS["wallet"], TAB_Y), ("wait", 2.0),
        ("text", "Scout"), ("wait", 5.0),
        ("text", "Holding"), ("wait", 5.0),
        ("swipe", 600, 1900, 600, 1300), ("wait", 3.0),
        ("text", "Whales"), ("wait", 4.0),
        ("text", "Live"), ("wait", 3.0),
        ("tap", 120, 551), ("wait", 5.0),
    ]


def scene_swap() -> list[tuple]:
    """Lo swap con la rotta e la riga sulla coda privata, poi il preventivo del ponte."""
    return [
        ("launch",), ("wait", 1.5), ("close",), ("wait", 1.2),
        ("tap", TABS["wallet"], TAB_Y), ("wait", 2.0),
        ("text", "Swap"), ("wait", 4.5),
        ("swipe", 600, 1800, 600, 1300), ("wait", 4.0),
        ("close",), ("wait", 1.5),
        ("text", "Bridge"), ("wait", 9.0),
    ]


def scene_health() -> list[tuple]:
    """Quello che il portafoglio si controlla da solo: punteggio, rent, deleghe, contatti."""
    return [
        ("launch",), ("wait", 1.5), ("close",), ("wait", 1.2),
        ("tap", TABS["settings"], TAB_Y), ("wait", 2.2),
        ("text", "Wallet and safety"), ("wait", 3.5),
        ("swipe", 600, 1900, 600, 1350), ("wait", 3.0),
        ("swipe", 600, 1900, 600, 1350), ("wait", 3.0),
    ]


def scene_market() -> list[tuple]:
    """Il mercato con una moneta e il confronto di capitalizzazione, poi il registro."""
    return [
        ("launch",), ("wait", 1.5), ("close",), ("wait", 1.2),
        ("tap", TABS["market"], TAB_Y), ("wait", 3.5),
        ("swipe", 600, 1900, 600, 1400), ("wait", 2.0),
        ("text", "Bitcoin"), ("wait", 5.0),
        ("swipe", 600, 1900, 600, 1300), ("wait", 4.0),
        ("close",), ("wait", 1.5),
        ("tap", TABS["receipts"], TAB_Y), ("wait", 4.0),
        ("swipe", 600, 1900, 600, 1300), ("wait", 3.0),
    ]


def scene_close() -> list[tuple]:
    """La chiusura: la home, ferma, col marchio del lanciatore."""
    return [
        ("launch",), ("wait", 1.5), ("close",), ("wait", 1.2),
        ("tap", TABS["wallet"], TAB_Y), ("wait", 5.0),
    ]


AUTO: dict[str, tuple[float, callable]] = {
    "receipt": (26.0, scene_receipt),
    "gate": (18.0, scene_gate),
    "bubble": (14.0, scene_bubble),
    "ore": (26.0, scene_ore),
    "crowd": (36.0, scene_crowd),
    "swap": (22.0, scene_swap),
    "health": (20.0, scene_health),
    "market": (26.0, scene_market),
    "close": (10.0, scene_close),
}


def tour(names: list[str]) -> int:
    """
    Le scene in una ripresa sola, o in poche se non ci stanno.

    Fra una ripresa e l'altra l'app va in secondo piano e si richiude: le scene girate
    una per una finivano per filmare la porta chiusa a chiave. Si registra di seguito, si
    cammina senza mai uscire, e alla fine si taglia il master nei pezzi, ognuno col suo
    secondo d'inizio misurato mentre accadeva.

    `screenrecord` pero' si ferma a centottanta secondi, e superato il tetto il file
    finisce a meta' strada senza dirlo: il giro si spezza in gruppi che ci stanno.
    """
    groups: list[list[str]] = [[]]
    budget = 0.0
    for name in names:
        need = AUTO[name][0] * 1.2
        if budget + need > 160 and groups[-1]:
            groups.append([])
            budget = 0.0
        groups[-1].append(name)
        budget += need

    for g, group in enumerate(groups):
        if not unlocked():
            print("Apri l'app con l'impronta e lasciala aperta, aspetto…")
            sh("shell", "am", "start", "-n", ACT)
            if not wait_unlocked():
                print("l'app e' ancora chiusa, mi fermo.")
                return 1
        if len(groups) > 1:
            print(f"— ripresa {g + 1} di {len(groups)}: {', '.join(group)}")
        if _take(group) != 0:
            return 1
    return 0


def _take(names: list[str]) -> int:
    """Una ripresa e i suoi tagli."""
    OUT.mkdir(parents=True, exist_ok=True)
    remote = "/sdcard/tour.mp4"
    sh("shell", "rm", "-f", remote)
    total = min(180, int(sum(AUTO[n][0] for n in names) * 1.4) + 25)
    rec = subprocess.Popen(
        ["adb", "shell", "screenrecord", "--bit-rate", "16M", "--time-limit", str(total), remote],
    )
    time.sleep(1.2)
    t0 = time.time()
    marks: list[tuple[str, float, float]] = []
    try:
        for name in names:
            begin = time.time() - t0
            for step in AUTO[name][1]():
                do(step)
            marks.append((name, begin, time.time() - t0))
            print(f"  {name:<9} {begin:>5.1f} → {time.time() - t0:>5.1f}")
    finally:
        sh("shell", "pkill", "-INT", "-f", "screenrecord")
        rec.wait(timeout=30)
    time.sleep(1.5)
    master = OUT / "tour.mp4"
    sh("pull", remote, str(master), timeout=300)
    sh("shell", "rm", "-f", remote)

    have = float(subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", str(master)],
        capture_output=True, text=True).stdout.strip() or 0)
    for name, begin, end in marks:
        if begin >= have - 0.5:
            print(f"  {name}: fuori dal girato, la ripresa si e' fermata a {have:.0f}s")
            continue
        end = min(end, have)
        out = OUT / f"beat_{name}.mp4"
        # `-ss` prima di `-i` con `-c copy` salta al fotogramma chiave e per l'ultimo pezzo
        # copiava fino in fondo: novanta secondi al posto di diciotto. Si taglia dopo aver
        # aperto il file, e si ricomprime: due secondi a pezzo, esatti.
        subprocess.run(
            ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", str(master),
             "-ss", f"{begin:.2f}", "-to", f"{end:.2f}", "-an",
             "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", str(out)],
            check=False,
        )
        print(f"  {out.name}")
    return 0


def main(argv: list[str]) -> int:
    if argv and argv[0] == "--manual":
        name = argv[1]
        seconds = float(argv[2]) if len(argv) > 2 else 20.0
        print(f"registro {seconds:.0f} s: tocca tu, ora.")
        print("scritto", record(name, seconds, [("wait", seconds)]))
        return 0

    one = "--one" in argv
    argv = [a for a in argv if a != "--one"]
    names = argv or list(AUTO)
    unknown = [n for n in names if n not in AUTO]
    if unknown:
        print("scene automatiche:", ", ".join(AUTO))
        print("non automatiche (serve il dito):", "sign, budget, gate, hardware")
        print("sconosciute:", ", ".join(unknown))
        return 2

    if one:
        return tour([n for n in names if n != "door"])

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
