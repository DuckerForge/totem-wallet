"""Rendering the request as a QR the phone can read off the PC screen.

`segno` is the only optional dependency (pure Python, no transitive deps). When
it is missing we degrade: the `qrencode` binary, then the bare URL to paste into
any chat and tap on the phone, then adb.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile

# Above this payload size drop to error-correction L: fewer modules, easier to
# scan off a screen. A Jupiter v0 swap lands around 1150 characters.
DENSE_AT = 900


def _level(url: str) -> str:
    return "L" if len(url) > DENSE_AT else "M"


def available() -> bool:
    try:
        import segno  # noqa: F401

        return True
    except Exception:
        return shutil.which("qrencode") is not None


def render(url: str, png_path: str | None = None, scale: int = 8) -> dict:
    """Returns {ascii, png_path, modules, level, encoder} — any field may be None."""
    level = _level(url)
    out = {"ascii": None, "png_path": None, "modules": None, "level": level, "encoder": None}
    try:
        import segno

        code = segno.make(url, error=level, mode="byte")
        out["encoder"] = "segno"
        out["modules"] = code.symbol_size()[0]
        # Plain Unicode blocks rather than ANSI colour: this string has to survive
        # being pasted through a tool result, a log and a terminal.
        out["ascii"] = _blocks(code, border=2)
        path = png_path or os.path.join(tempfile.gettempdir(), "apex-agent-request.png")
        code.save(path, scale=scale, border=4)
        out["png_path"] = path
        return out
    except ImportError:
        pass
    except Exception:
        pass
    if shutil.which("qrencode"):
        try:
            out["encoder"] = "qrencode"
            out["ascii"] = subprocess.run(
                ["qrencode", "-t", "ANSIUTF8", "-l", level, url], capture_output=True, text=True, timeout=20
            ).stdout
            path = png_path or os.path.join(tempfile.gettempdir(), "apex-agent-request.png")
            subprocess.run(["qrencode", "-o", path, "-s", str(scale), "-l", level, url], timeout=20, check=True)
            out["png_path"] = path
        except Exception:
            out["encoder"] = None
    return out


def _blocks(code, border: int = 2) -> str:
    """Two half-height rows per line, so the QR stays square in a terminal."""
    rows = [list(row) for row in code.matrix]
    width = len(rows[0])
    pad = [0] * (width + 2 * border)
    grid = [pad[:] for _ in range(border)]
    for row in rows:
        grid.append([0] * border + [1 if cell else 0 for cell in row] + [0] * border)
    grid += [pad[:] for _ in range(border)]
    if len(grid) % 2:
        grid.append(pad[:])
    # One character per column, two module-rows per line: a terminal cell is about
    # twice as tall as it is wide, so this comes out square. Light modules are drawn
    # as full blocks because a terminal is dark — the same inversion qrencode uses.
    glyph = {(0, 0): "\u2588", (1, 1): " ", (1, 0): "\u2584", (0, 1): "\u2580"}
    lines = []
    for y in range(0, len(grid), 2):
        top, bottom = grid[y], grid[y + 1]
        lines.append("".join(glyph[(top[x], bottom[x])] for x in range(len(top))))
    return "\n".join(lines)
