"""Composing (and re-reading) the apex://agent/sign request URL."""

from __future__ import annotations

import base64
import json
import shlex
import urllib.parse

SCHEME = "apex://agent/sign"


def build_url(
    tx: bytes,
    intent: dict,
    account: str | None = None,
    cluster: str | None = None,
    send: bool = True,
    callback: str | None = None,
) -> str:
    """The request the phone will open.

    `callback` is deliberately optional and should stay unused from a PC: the
    phone re-opens it itself with ACTION_VIEW, so an http callback would pop a
    browser on the phone and put the signed transaction in its history.
    """
    parts = [
        "tx=" + urllib.parse.quote(base64.urlsafe_b64encode(tx).decode(), safe=""),
        "intent=" + urllib.parse.quote(json.dumps(intent, separators=(",", ":"), ensure_ascii=False), safe=""),
    ]
    if account:
        parts.append("account=" + urllib.parse.quote(account, safe=""))
    if cluster:
        parts.append("cluster=" + urllib.parse.quote(cluster, safe=""))
    if not send:
        parts.append("send=0")
    if callback:
        parts.append("callback=" + urllib.parse.quote(callback, safe=""))
    return SCHEME + "?" + "&".join(parts)


def parse_url(url: str) -> dict:
    """Inverse of build_url, for the self-test."""
    query = urllib.parse.urlparse(url).query
    q = urllib.parse.parse_qs(query, keep_blank_values=True)
    one = {k: v[0] for k, v in q.items()}
    raw = one.get("tx", "")
    pad = "=" * (-len(raw) % 4)
    return {
        "tx": base64.urlsafe_b64decode(raw + pad),
        "intent": json.loads(one["intent"]) if "intent" in one else None,
        "account": one.get("account"),
        "cluster": one.get("cluster"),
        "send": one.get("send") != "0",
        "callback": one.get("callback"),
    }


def adb_argv(url: str, adb: str, target: str | None = None) -> list[str]:
    """The URL must be quoted for the *remote* shell too, or `&` splits the command."""
    argv = [adb]
    if target:
        argv += ["-s", target]
    argv += ["shell", f"am start -a android.intent.action.VIEW -d {shlex.quote(url)}"]
    return argv
