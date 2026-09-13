"""The envelope, from the bridge's side: a queue the phone drains, never a key.

The phone is the only place the envelope key exists. This module hands it
unsigned transactions plus a declared intent, and waits for the phone's verdict:
signed silently (inside the collar), confirmed by the person (the collar said
"ask"), refused (with the reason, in words), or timed out.

Nothing here can spend. If this machine is compromised, an attacker can queue
requests that the phone will judge — that is all.
"""

from __future__ import annotations

import base64
import json
import os
import secrets
import socket
import threading
import time
from collections import deque
from urllib.parse import quote

from . import agent, jupiter, solana, state

LINK_PATH = os.path.join(os.path.dirname(state.PATH), "link.json")
DEFAULT_PORT = 8765
ASK_WINDOW_S = 100          # the phone gives the person 90 s; we wait a little longer


class LinkError(RuntimeError):
    pass


class _Link:
    def __init__(self) -> None:
        self.cond = threading.Condition()
        self.jobs: deque[dict] = deque()
        self.results: dict[str, dict] = {}
        self.phone: dict | None = None
        self.phone_seen = 0.0
        self.port = DEFAULT_PORT


LINK = _Link()


# ---- identity --------------------------------------------------------------

def token() -> str:
    """One secret per machine, persisted so a pairing survives restarts."""
    try:
        with open(LINK_PATH) as fh:
            t = json.load(fh).get("token")
            if t:
                return t
    except Exception:
        pass
    t = secrets.token_urlsafe(24)
    os.makedirs(os.path.dirname(LINK_PATH), exist_ok=True)
    with open(LINK_PATH, "w") as fh:
        json.dump({"token": t}, fh)
    os.chmod(LINK_PATH, 0o600)
    return t


def local_ip() -> str:
    """The address the phone can reach on the same Wi-Fi."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def pair_url(host: str | None = None, port: int | None = None, name: str = "agent") -> str:
    base = host or f"http://{local_ip()}:{port or LINK.port}"
    return f"apex://agent/pair?host={quote(base, safe='')}&token={quote(token(), safe='')}&name={quote(name[:40], safe='')}"


def parse_pair_url(url: str) -> dict:
    from urllib.parse import parse_qs, urlparse
    u = urlparse(url)
    q = parse_qs(u.query)
    return {"host": q.get("host", [None])[0], "token": q.get("token", [None])[0], "name": q.get("name", [None])[0], "path": u.path}


# ---- the wire, phone side ---------------------------------------------------

def handle_hello(body: dict) -> dict:
    with LINK.cond:
        LINK.phone = body
        LINK.phone_seen = time.time()
    return {"ok": True}


def handle_next(wait_s: float = 25.0) -> dict | None:
    """Long-poll: block until a job exists or the window closes."""
    deadline = time.time() + wait_s
    with LINK.cond:
        LINK.phone_seen = time.time()
        while not LINK.jobs:
            left = deadline - time.time()
            if left <= 0:
                return None
            LINK.cond.wait(left)
        return LINK.jobs.popleft()


def handle_result(body: dict) -> dict:
    job_id = body.get("id")
    if not job_id:
        return {"ok": False}
    with LINK.cond:
        LINK.results[job_id] = body
        LINK.phone_seen = time.time()
        LINK.cond.notify_all()
    return {"ok": True}


# ---- the wire, model side ---------------------------------------------------

def linked(max_age_s: float = 90.0) -> bool:
    return LINK.phone is not None and (time.time() - LINK.phone_seen) < max_age_s


def submit(tx: bytes, intent: dict, cluster: str | None, agent_name: str, timeout_s: float = ASK_WINDOW_S) -> dict:
    if not linked():
        raise LinkError("no phone is linked: run apex_agent_pair and scan the QR with Apex (Agent tab → Link an agent)")
    job_id = state.new_request_id()
    job = {
        "id": job_id,
        "tx": base64.urlsafe_b64encode(tx).decode().rstrip("="),
        "intent": json.dumps(intent, ensure_ascii=False),
        "cluster": cluster,
        "agent": agent_name,
        "created": time.time(),
    }
    with LINK.cond:
        LINK.jobs.append(job)
        LINK.cond.notify_all()
        deadline = time.time() + timeout_s
        while job_id not in LINK.results:
            left = deadline - time.time()
            if left <= 0:
                # Never leave it queued: a stale blockhash would just fail on chain, but be clean.
                try:
                    LINK.jobs.remove(job)
                except ValueError:
                    pass
                return {"decision": "timeout", "reason": "the phone did not answer in time", "job_id": job_id}
            LINK.cond.wait(left)
        r = LINK.results.pop(job_id)
    out = {"decision": r.get("decision"), "reason": r.get("reason"), "signature": r.get("signature"), "job_id": job_id}
    if out["signature"]:
        out["explorer_url"] = f"https://solscan.io/tx/{out['signature']}"
    out["what_it_means"] = {
        "signed_silently": "inside the collar: the envelope signed and sent it, nobody was asked",
        "confirmed_by_user": "the collar asked; the person confirmed with their fingerprint",
        "refused": "outside the collar: not signed. Explain the reason to the user; do not retry the same thing",
        "timeout": "the person did not answer before the request expired",
    }.get(out["decision"], "")
    return out


def envelope_pubkey() -> str:
    pk = (LINK.phone or {}).get("envelope")
    if not pk:
        raise LinkError("the phone has no envelope yet: create one in Apex (Agent tab)")
    return pk


def status(cluster: str | None = None) -> dict:
    p = LINK.phone or {}
    out = {
        "linked": linked(),
        "phone_last_seen_s_ago": round(time.time() - LINK.phone_seen) if LINK.phone_seen else None,
        "envelope": p.get("envelope"),
        "mode": p.get("mode"),
        "caps_sol": {
            "per_move": (p.get("perTxLamports") or 0) / 1e9,
            "per_day": (p.get("dailyLamports") or 0) / 1e9,
            "silent_below": (p.get("askAboveLamports") or 0) / 1e9,
            "spent_last_24h": (p.get("spentLast24hLamports") or 0) / 1e9,
            "moves_per_hour": p.get("maxTxPerHour"),
        },
        "allowed_mints": p.get("allowedMints"),
        "allowed_destinations": p.get("allowedDestinations"),
        "pending_jobs": len(LINK.jobs),
        "pair_url": pair_url(),
    }
    if p.get("envelope"):
        try:
            out["envelope_balance_sol"] = solana.balance(solana.rpc_url(cluster), p["envelope"]) / 1e9
        except Exception as exc:
            out["envelope_balance_error"] = str(exc)
    if not out["linked"]:
        out["how_to_link"] = "call apex_agent_pair, show the QR, scan it in Apex → Agent → Link an agent"
    return out


# ---- the two moves ----------------------------------------------------------

def send(to: str, amount_sol: float, reason: str, agent_name: str, cluster: str | None = None) -> dict:
    """SOL out of the envelope to `to`. The phone decides; we only build and wait."""
    solana.pubkey(to)
    if amount_sol <= 0:
        raise LinkError("amount must be positive")
    env = envelope_pubkey()
    url = solana.rpc_url(cluster)
    lamports = int(round(amount_sol * 1e9))
    tx = solana.build_transfer_sol(env, to, lamports, solana.latest_blockhash(url), memo=None)
    sim = solana.simulate(url, tx)
    if sim.get("err"):
        raise LinkError(f"the transaction would fail: {sim['err']}")
    intent = {"action": "transfer", "outMint": "SOL", "outAmount": lamports / 1e9, "to": to, "agent": agent_name, "reason": reason}
    out = submit(tx, intent, cluster, agent_name)
    out.update({"from_envelope": env, "to": to, "amount_sol": lamports / 1e9})
    return out


def swap(in_mint: str, out_mint: str, out_amount: float, reason: str, agent_name: str, slippage_bps: int = 50, cluster: str | None = None) -> dict:
    """A Jupiter swap paid from the envelope. `out_amount` is what LEAVES the envelope."""
    env = envelope_pubkey()
    src = agent._resolve_mint(in_mint)
    dst = agent._resolve_mint(out_mint)
    url = solana.rpc_url(cluster)
    src_addr = solana.WSOL if src == "SOL" else src
    in_dec = solana.mint_decimals(url, src_addr)
    out_dec = solana.mint_decimals(url, dst)
    raw = int(round(out_amount * (10 ** in_dec)))
    if raw <= 0:
        raise LinkError("out_amount must be positive")
    q = jupiter.quote(src_addr, dst, raw, slippage_bps)
    tx = jupiter.swap_tx(q, env)
    intent = jupiter.derive_intent(q, agent._label(src), agent._label(dst), in_dec, out_dec, reason, agent_name)
    out = submit(tx, intent, cluster, agent_name)
    out.update({"from_envelope": env, "route": jupiter.route_labels(q), "intent": intent})
    return out


# ---- a listener of its own, for stdio mode ----------------------------------

def serve_in_background(port: int = DEFAULT_PORT) -> None:
    """The phone needs an HTTP endpoint even when the model talks to us over stdio."""
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    tok = token()
    LINK.port = port

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def _ok(self, code: int, body: dict | None) -> None:
            raw = json.dumps(body).encode() if body is not None else b""
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            if raw:
                self.wfile.write(raw)

        def _auth(self) -> bool:
            return self.headers.get("Authorization", "") == "Bearer " + tok

        def do_GET(self):
            if not self._auth():
                self._ok(401, {"error": "unauthorised"}); return
            if self.path.startswith("/link/next"):
                job = handle_next()
                self._ok(200, job) if job else self._ok(204, None)
                return
            self._ok(404, {"error": "not found"})

        def do_POST(self):
            if not self._auth():
                self._ok(401, {"error": "unauthorised"}); return
            try:
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
            except Exception:
                self._ok(400, {"error": "bad JSON"}); return
            if self.path.startswith("/link/hello"):
                self._ok(200, handle_hello(body)); return
            if self.path.startswith("/link/result"):
                self._ok(200, handle_result(body)); return
            self._ok(404, {"error": "not found"})

        def log_message(self, *a):
            pass

    srv = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    threading.Thread(target=srv.serve_forever, name="apex-link", daemon=True).start()


def route(path: str, method: str, body: dict | None, bearer: str | None) -> tuple[int, dict | None] | None:
    """For the combined --http server: handle /link/* here, or return None."""
    if not path.startswith("/link/"):
        return None
    if bearer != token():
        return 401, {"error": "unauthorised"}
    if method == "GET" and path.startswith("/link/next"):
        job = handle_next()
        return (200, job) if job else (204, None)
    if method == "POST" and path.startswith("/link/hello"):
        return 200, handle_hello(body or {})
    if method == "POST" and path.startswith("/link/result"):
        return 200, handle_result(body or {})
    return 404, {"error": "not found"}
