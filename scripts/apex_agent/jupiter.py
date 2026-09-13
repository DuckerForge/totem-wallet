"""Jupiter swaps, on the same hosts the app uses (Jupiter.kt)."""

from __future__ import annotations

import base64
import json
import urllib.request

from . import solana

HOSTS = ["https://lite-api.jup.ag/swap/v1", "https://api.jup.ag/swap/v1"]


def _get(url: str, timeout: int = 20) -> dict | None:
    try:
        return json.loads(urllib.request.urlopen(url, timeout=timeout).read())
    except Exception:
        return None


def _post(url: str, body: dict, timeout: int = 25) -> dict | None:
    try:
        req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
        return json.loads(urllib.request.urlopen(req, timeout=timeout).read())
    except Exception:
        return None


def quote(in_mint: str, out_mint: str, raw_amount: int, slippage_bps: int = 50) -> dict:
    q = f"inputMint={in_mint}&outputMint={out_mint}&amount={raw_amount}&slippageBps={slippage_bps}"
    for host in HOSTS:
        got = _get(f"{host}/quote?{q}")
        if got and "outAmount" in got:
            return got
    raise RuntimeError("no quote from Jupiter")


def swap_tx(q: dict, user_pubkey: str) -> bytes:
    """The ready-to-sign v0 transaction.

    No `feeAccount`: a platform fee injected from the PC would be an outflow the
    agent did not declare, and IntentGuard would rightly block it.
    """
    body = {
        "quoteResponse": q,
        "userPublicKey": user_pubkey,
        "wrapAndUnwrapSol": True,
        "dynamicComputeUnitLimit": True,
    }
    for host in HOSTS:
        got = _post(f"{host}/swap", body)
        if got and got.get("swapTransaction"):
            return base64.b64decode(got["swapTransaction"])
    raise RuntimeError("Jupiter would not build the swap transaction")


def derive_intent(q: dict, in_symbol: str, out_symbol: str, in_decimals: int, out_decimals: int, reason: str, agent: str) -> dict:
    """The declared intent, computed from the quote itself.

    Deriving it from the quote (rather than from what the caller says it wants)
    is what keeps the declaration from drifting away from the bytes.
    """
    return {
        "action": "swap",
        "outMint": in_symbol,
        "outAmount": int(q["inAmount"]) / (10 ** in_decimals),
        "inMint": out_symbol,
        "inAmount": int(q["outAmount"]) / (10 ** out_decimals),
        "agent": agent,
        "reason": reason,
    }


def route_labels(q: dict) -> list[str]:
    out = []
    for step in q.get("routePlan") or []:
        label = (step.get("swapInfo") or {}).get("label")
        if label and label not in out:
            out.append(label)
    return out
