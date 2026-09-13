"""The operations an agent performs, shared by the CLI and the MCP server.

The shape of the flow: build → propose → await. Building derives the *declared
intent* from the same data used to make the bytes, so the declaration cannot
drift. Proposing rebuilds on a fresh blockhash and re-simulates before it shows
a QR, because the user needs time to scan and a blockhash dies in ~90 seconds.
Awaiting reads the chain, because the phone has no way to call the PC back.
"""

from __future__ import annotations

import os
import subprocess
import time

from . import jupiter, link, qr, solana, state

# Mints worth naming; anything else travels as its address, which IntentGuard
# matches just as well (it compares the claim against mint *or* symbol).
KNOWN = {
    "SOL": "SOL",
    solana.WSOL: "SOL",
    "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v": "USDC",
    "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB": "USDT",
    "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3": "SKR",
    "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN": "JUP",
    "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263": "BONK",
}

MINT_OF = {"SOL": solana.WSOL, "USDC": "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"}

ADB = os.environ.get("APEX_ADB", os.path.expanduser("~/Android/Sdk/platform-tools/adb"))


class AgentError(RuntimeError):
    pass


def _label(mint: str) -> str:
    return KNOWN.get(mint, KNOWN.get(mint.upper(), mint))


def _resolve_mint(text: str) -> str:
    """Accept a symbol or an address; return an address (SOL stays 'SOL')."""
    up = text.strip().upper()
    if up == "SOL":
        return "SOL"
    if up in MINT_OF:
        return MINT_OF[up]
    return text.strip()


def _wallet() -> tuple[str, str | None]:
    data = state.load()
    if not data.get("wallet"):
        raise AgentError("No wallet set. Call apex_set_wallet with the Seeker account first.")
    return data["wallet"], data.get("cluster")


# ---- status ----------------------------------------------------------------


def status() -> dict:
    data = state.load()
    out = {
        "wallet": data.get("wallet"),
        "cluster": data.get("cluster") or "mainnet",
        "qr_available": qr.available(),
        "adb": os.path.exists(ADB),
        "pending": [k for k, v in data["requests"].items() if not v.get("outcome")],
    }
    if data.get("wallet"):
        try:
            url = solana.rpc_url(data.get("cluster"))
            out["balance_sol"] = solana.balance(url, data["wallet"]) / 1e9
        except Exception as exc:
            out["balance_error"] = str(exc)
    return out


def set_wallet(wallet: str, cluster: str | None = None) -> dict:
    solana.pubkey(wallet)  # raises when it is not a 32-byte base58 key
    data = state.load()
    data["wallet"] = wallet
    if cluster:
        data["cluster"] = cluster
    state.save(data)
    return status()


# ---- building --------------------------------------------------------------


def build_transfer(to: str, amount: float, reason: str, agent: str, mint: str = "SOL") -> dict:
    """A SOL transfer with its declared intent.

    Token transfers are intentionally not built here yet: they need associated
    token account derivation. Use a swap for token movement, or send from the app.
    """
    if _resolve_mint(mint) != "SOL":
        raise AgentError("Only SOL transfers are supported from the agent today; use apex_build_swap for tokens.")
    wallet, cluster = _wallet()
    solana.pubkey(to)
    if amount <= 0:
        raise AgentError("amount must be positive")
    lamports = int(round(amount * 1e9))
    req_id = state.new_request_id()
    intent = {
        "action": "transfer",
        "outMint": "SOL",
        "outAmount": lamports / 1e9,
        "to": to,
        "agent": agent,
        "reason": reason,
    }
    state.put_request(
        req_id,
        {
            "kind": "transfer",
            "wallet": wallet,
            "cluster": cluster,
            "to": to,
            "lamports": lamports,
            "intent": intent,
            "memo_tag": "apex:" + req_id,
        },
    )
    return {
        "req_id": req_id,
        "kind": "transfer",
        "intent": intent,
        "preview": {"from": wallet, "to": to, "amount_sol": lamports / 1e9, "lamports": lamports},
        "next": "call apex_propose with this req_id",
    }


def build_swap(in_mint: str, out_mint: str, out_amount: float, reason: str, agent: str, slippage_bps: int = 50) -> dict:
    """A Jupiter swap. `out_amount` is what LEAVES the wallet, matching IntentGuard."""
    wallet, cluster = _wallet()
    src = _resolve_mint(in_mint)
    dst = _resolve_mint(out_mint)
    url = solana.rpc_url(cluster)
    src_addr = solana.WSOL if src == "SOL" else src
    in_dec = solana.mint_decimals(url, src_addr)
    out_dec = solana.mint_decimals(url, dst)
    raw = int(round(out_amount * (10 ** in_dec)))
    if raw <= 0:
        raise AgentError("out_amount must be positive")
    q = jupiter.quote(src_addr, dst, raw, slippage_bps)
    intent = jupiter.derive_intent(q, _label(src), _label(dst), in_dec, out_dec, reason, agent)
    req_id = state.new_request_id()
    state.put_request(
        req_id,
        {
            "kind": "swap",
            "wallet": wallet,
            "cluster": cluster,
            "in_mint": src_addr,
            "out_mint": dst,
            "raw_amount": raw,
            "slippage_bps": slippage_bps,
            "in_decimals": in_dec,
            "out_decimals": out_dec,
            "reason": reason,
            "agent": agent,
            "intent": intent,
        },
    )
    return {
        "req_id": req_id,
        "kind": "swap",
        "intent": intent,
        "preview": {
            "route": jupiter.route_labels(q),
            "price_impact_pct": float(q.get("priceImpactPct") or 0) * 100,
            "expected_in_amount": intent["inAmount"],
        },
        "next": "call apex_propose with this req_id",
    }


# ---- proposing -------------------------------------------------------------


def _rebuild(req: dict) -> tuple[bytes, dict, str]:
    """Fresh bytes on a fresh blockhash, plus the intent that describes them."""
    url = solana.rpc_url(req.get("cluster"))
    if req["kind"] == "transfer":
        blockhash = solana.latest_blockhash(url)
        tx = solana.build_transfer_sol(req["wallet"], req["to"], req["lamports"], blockhash, req.get("memo_tag"))
        return tx, req["intent"], blockhash
    q = jupiter.quote(req["in_mint"], req["out_mint"], req["raw_amount"], req.get("slippage_bps", 50))
    tx = jupiter.swap_tx(q, req["wallet"])
    intent = jupiter.derive_intent(
        q, _label(req["in_mint"]), _label(req["out_mint"]), req["in_decimals"], req["out_decimals"],
        req["reason"], req["agent"],
    )
    # A v0 transaction carries its own blockhash; read it back for outcome matching.
    return tx, intent, _blockhash_of_v0(tx)


def _blockhash_of_v0(tx: bytes) -> str:
    """The blockhash sits after the signatures, the header and the account keys."""
    i = 0
    sigs = tx[i] & 0x7F
    i += 1
    i += sigs * 64
    if tx[i] & 0x80:  # versioned prefix
        i += 1
    i += 3  # header
    n = tx[i] & 0x7F
    i += 1
    i += n * 32
    return solana.b58encode(tx[i : i + 32])


def propose(req_id: str, send: bool = True, demo_lie: dict | None = None) -> dict:
    req = state.get_request(req_id)
    if req is None:
        raise AgentError(f"unknown req_id {req_id}")
    tx, intent, blockhash = _rebuild(req)
    if demo_lie:
        # The demo: same bytes, a false declaration. Forced to sign-only so the
        # scene can never move money.
        intent = {**intent, **demo_lie}
        send = False
    rpc = solana.rpc_url(req.get("cluster"))
    sim = solana.simulate(rpc, tx)
    if sim.get("err"):
        raise AgentError(f"the transaction would fail on chain, not showing a QR: {sim['err']}")
    slot0 = solana.slot(rpc)
    url = link.build_url(tx, intent, account=req["wallet"], cluster=req.get("cluster"), send=send)
    drawn = qr.render(url)
    state.update_request(
        req_id,
        proposed_at=time.time(), blockhash=blockhash, slot0=slot0, send=send,
        url=url, intent=intent, outcome=None,
    )
    return {
        "req_id": req_id,
        "url": url,
        "url_length": len(url),
        "qr_ascii": drawn["ascii"],
        "qr_png": drawn["png_path"],
        "qr_modules": drawn["modules"],
        "qr_encoder": drawn["encoder"],
        "send": send,
        "slot0": slot0,
        "memo_tag": req.get("memo_tag"),
        "declared": intent,
        "expires_in_s": 90,
        "how": "Scan the QR with Apex (scan icon on the wallet home), or open the URL on the phone.",
    }


def send_via_adb(req_id: str, target: str | None = None) -> dict:
    req = state.get_request(req_id)
    if req is None or not req.get("url"):
        raise AgentError("propose the request first")
    argv = link.adb_argv(req["url"], ADB, target)
    done = subprocess.run(argv, capture_output=True, text=True, timeout=60)
    return {"argv": argv, "stdout": done.stdout.strip(), "stderr": done.stderr.strip(), "code": done.returncode}


# ---- the outcome, read off the chain ---------------------------------------


def await_outcome(req_id: str, timeout_s: int = 180) -> dict:
    req = state.get_request(req_id)
    if req is None:
        raise AgentError(f"unknown req_id {req_id}")
    if not req.get("proposed_at"):
        raise AgentError("propose the request first")
    if not req.get("send", True):
        return {
            "status": "not_observable",
            "note": "This request was sign-only (send=0), so nothing was broadcast. "
                    "The signed transaction stayed on the phone.",
        }
    rpc = solana.rpc_url(req.get("cluster"))
    tag = req.get("memo_tag")
    blockhash = req.get("blockhash")
    slot0 = req.get("slot0") or 0
    deadline = time.time() + max(5, timeout_s)
    checked: set[str] = set()
    while time.time() < deadline:
        try:
            entries = solana.signatures_for(rpc, req["wallet"], limit=25)
        except Exception:
            entries = []
        for entry in entries:
            sig = entry.get("signature")
            if not sig or sig in checked or (entry.get("slot") or 0) <= slot0:
                continue
            hit = bool(tag and tag in (entry.get("memo") or ""))
            if not hit and blockhash:
                checked.add(sig)
                try:
                    full = solana.transaction(rpc, sig)
                except Exception:
                    full = None
                hit = bool(full and solana.recent_blockhash_of(full) == blockhash)
            if hit:
                err = entry.get("err")
                out = {
                    "status": "failed" if err else "confirmed",
                    "signature": sig,
                    "slot": entry.get("slot"),
                    "err": err,
                    "explorer_url": _explorer(sig, req.get("cluster")),
                }
                state.update_request(req_id, outcome=out)
                return out
        time.sleep(2)
    return {
        "status": "timeout",
        "hint": "No matching transaction yet. A blockhash lives about 90 seconds — "
                "if the user has not scanned by now, call apex_propose again.",
    }


def _explorer(signature: str, cluster: str | None) -> str:
    base = f"https://solscan.io/tx/{signature}"
    c = (cluster or "").lower()
    if "devnet" in c:
        return base + "?cluster=devnet"
    if "testnet" in c:
        return base + "?cluster=testnet"
    return base
