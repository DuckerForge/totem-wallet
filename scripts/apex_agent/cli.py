#!/usr/bin/env python3
"""Command line for the Agent Gate, for when there is no MCP client around.

    python3 -m apex_agent.cli wallet cHAH…VZjQ
    python3 -m apex_agent.cli transfer <dest> 0.000001 "why" [--send0] [--adb]
    python3 -m apex_agent.cli swap SOL USDC 0.001 "why"
    python3 -m apex_agent.cli lie <dest> 0.000001      # the blocked demo
    python3 -m apex_agent.cli watch <req_id>
    python3 -m apex_agent.cli selftest

    python3 -m apex_agent.cli pair                       # QR da inquadrare in Apex → Agent → Collega
    python3 -m apex_agent.cli agent status
    python3 -m apex_agent.cli agent send <dest> 0.001    # decide il telefono: silenzioso / chiede / rifiuta
    python3 -m apex_agent.cli agent swap SOL USDC 0.001
"""

from __future__ import annotations

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from apex_agent import agent, envelope, jupiter, link, qr, solana, state  # noqa: E402

NAME = "apex-agent cli"


def show(result: dict) -> None:
    if result.get("qr_ascii"):
        print(result["qr_ascii"])
        print()
    printable = {k: v for k, v in result.items() if k != "qr_ascii"}
    print(json.dumps(printable, indent=1, ensure_ascii=False))


def selftest() -> int:
    failures = []

    def check(label, ok):
        print(("  ok   " if ok else "  FAIL ") + label)
        if not ok:
            failures.append(label)

    print("encoding")
    check("base58 round-trip", solana.b58encode(solana.b58decode("DEM0ownerWa11etF0rTests0n1yNotARea1Key111jQ")) == "DEM0ownerWa11etF0rTests0n1yNotARea1Key111jQ")
    check("system program is 32 zero bytes", solana.b58decode(solana.SYSTEM_PROGRAM) == b"\0" * 32)
    check("shortvec vectors", [solana.shortvec(n).hex() for n in (0, 127, 128, 16384)] == ["00", "7f", "8001", "808001"])

    print("golden transaction (must match scripts/agent-demo.sh byte for byte)")
    wallet = "DEM0ownerWa11etF0rTests0n1yNotARea1Key111jQ"
    blockhash = "7HEAk6cYxJC7DCqw54fm48TeeCBpBvoMLoxAoir4agg5"
    recipient = solana.b58encode(bytes(range(1, 33)))
    tx = solana.build_transfer_sol(wallet, recipient, 1000, blockhash)
    import struct

    data = struct.pack("<I", 2) + struct.pack("<Q", 1000)
    sv = solana.shortvec
    ref_msg = (
        bytes([1, 0, 1]) + sv(3) + solana.b58decode(wallet) + bytes(range(1, 33)) + b"\0" * 32
        + solana.b58decode(blockhash) + sv(1) + bytes([2]) + sv(2) + bytes([0, 1]) + sv(len(data)) + data
    )
    check("bytes identical", tx == sv(1) + b"\0" * 64 + ref_msg)
    check("memo variant is longer and still parses", len(solana.build_transfer_sol(wallet, recipient, 1000, blockhash, "omni:deadbeef")) > len(tx))

    print("request URL")
    intent = {"action": "transfer", "outMint": "SOL", "outAmount": 0.000001, "to": recipient, "agent": NAME, "reason": "self-test"}
    url = link.build_url(tx, intent, account=wallet)
    back = link.parse_url(url)
    check("tx survives the round-trip", back["tx"] == tx)
    check("intent survives the round-trip", back["intent"] == intent)
    check("send defaults to true", back["send"] is True)
    check("send=0 is carried", link.parse_url(link.build_url(tx, intent, send=False))["send"] is False)
    import base64

    raw = url.split("tx=")[1].split("&")[0]
    from urllib.parse import unquote

    raw = unquote(raw)
    check("base64 decodes as URL_SAFE (what the app tries first)", base64.urlsafe_b64decode(raw) == tx)

    print("QR")
    drawn = qr.render(url)
    check("an encoder is available", drawn["encoder"] is not None)
    check("ascii produced", bool(drawn["ascii"]))
    check("png written", bool(drawn["png_path"]) and os.path.exists(drawn["png_path"]))
    check("transfer stays under 900 chars (error level M)", len(url) < qr.DENSE_AT)

    print("collegamento (coda in memoria, offline)")
    try:
        import threading, time as _t
        url = envelope.pair_url("http://192.168.1.10:8765", name="claude-code")
        got = envelope.parse_pair_url(url)
        check("l'URL di pairing si rilegge identico", got["host"] == "http://192.168.1.10:8765" and got["name"] == "claude-code" and got["path"] == "/pair")
        check("il token del ponte è stabile fra due letture", envelope.token() == envelope.token() and len(envelope.token()) >= 24)
        envelope.handle_hello({"envelope": "DEM0ownerWa11etF0rTests0n1yNotARea1Key111jQ", "mode": "AUTONOMOUS"})
        check("dopo l'hello il telefono risulta collegato", envelope.linked())
        res: dict = {}
        def phone():
            job = envelope.handle_next(5)
            res["job"] = job
            _t.sleep(0.1)
            envelope.handle_result({"id": job["id"], "decision": "signed_silently", "signature": "5ig"})
        th = threading.Thread(target=phone); th.start()
        out = envelope.submit(b"\x01" + bytes(64) + b"\x00", {"action": "transfer"}, None, "test", timeout_s=5)
        th.join()
        check("il telefono riceve il lavoro una volta sola", res.get("job") is not None and envelope.handle_next(0.2) is None)
        check("il verdetto sblocca chi aspetta", out.get("decision") == "signed_silently" and out.get("signature") == "5ig")
        out = envelope.submit(b"\x01" + bytes(64) + b"\x00", {"action": "transfer"}, None, "test", timeout_s=0.3)
        check("senza risposta l'esito è timeout e la coda resta vuota", out.get("decision") == "timeout" and envelope.handle_next(0.1) is None)
        code, body = envelope.route("/link/next", "GET", None, "wrong-token")
        check("un token sbagliato viene rifiutato", code == 401)
    except Exception as exc:
        check(f"collegamento ({exc})", False)

    print("network")
    try:
        rpc = solana.rpc_url(None)
        fresh = solana.latest_blockhash(rpc)
        check("blockhash is 32 bytes", len(solana.b58decode(fresh)) == 32)
        live = solana.build_transfer_sol(wallet, recipient, 1000, fresh)
        check("golden transaction simulates without error", solana.simulate(rpc, live).get("err") is None)
        quote = jupiter.quote(solana.WSOL, "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", 1_000_000)
        check("jupiter quote has a route", bool(quote.get("routePlan")))
        swap = jupiter.swap_tx(quote, wallet)
        swap_url = link.build_url(swap, {"action": "swap", "outMint": "SOL", "outAmount": 0.001, "agent": NAME, "reason": "self-test"}, account=wallet)
        check("swap URL still fits a scannable QR", len(swap_url) < 1600)
        check("swap blockhash is readable from the v0 bytes", len(solana.b58decode(agent._blockhash_of_v0(swap))) == 32)
    except Exception as exc:
        check(f"network checks ({exc})", False)

    print()
    print("FAILURES: " + ", ".join(failures) if failures else "all green")
    return 1 if failures else 0


def _bridge(tool: str, args: dict) -> dict:
    """Call a tool on the bridge already running on this machine (`mcp_server.py --http 8765`)."""
    import urllib.request
    port = os.environ.get("APEX_LINK_PORT", str(envelope.DEFAULT_PORT))
    token = os.environ.get("APEX_MCP_TOKEN")
    url = f"http://127.0.0.1:{port}/mcp" + (f"?token={token}" if token else "")
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": tool, "arguments": args}}).encode()
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=130) as r:
            reply = json.loads(r.read())
    except Exception as exc:
        return {"error": f"nessun ponte in ascolto sulla porta {port} ({exc}); avvialo con: APEX_MCP_TOKEN=… python3 scripts/apex_agent/mcp_server.py --http {port}"}
    if "error" in reply:
        return {"error": reply["error"].get("message")}
    text = reply["result"]["content"][0]["text"]
    try:
        return json.loads(text)
    except Exception:
        return {"text": text}


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    cmd, rest = argv[0], argv[1:]
    if cmd == "selftest":
        return selftest()
    if cmd == "pair":
        url = envelope.pair_url(name="apex-cli")
        out = {"pair_url": url}
        try:
            out.update(qr.render(url, os.path.join(os.path.dirname(envelope.LINK_PATH), "pair.png")))
        except Exception as exc:
            out["qr_error"] = str(exc)
        if out.get("ascii"):
            print(out["ascii"])
        print(url)
        print("\nIl ponte deve restare acceso: python3 scripts/apex_agent/mcp_server.py --http 8765")
        return 0
    if cmd == "agent":
        # The phone talks to ONE running bridge; the CLI is just another client of it.
        sub = rest[0] if rest else "status"
        if sub == "send":
            show(_bridge("apex_agent_send", {"to": rest[1], "amount": float(rest[2]), "reason": " ".join(rest[3:]) or "richiesta dalla CLI"})); return 0
        if sub == "swap":
            show(_bridge("apex_agent_swap", {"in_mint": rest[1], "out_mint": rest[2], "out_amount": float(rest[3]), "reason": " ".join(rest[4:]) or "richiesta dalla CLI"})); return 0
        show(_bridge("apex_agent_status", {})); return 0
    if cmd == "status":
        show(agent.status())
        return 0
    if cmd == "wallet":
        show(agent.set_wallet(rest[0], rest[1] if len(rest) > 1 else None))
        return 0
    if cmd in ("transfer", "lie"):
        to, amount = rest[0], float(rest[1])
        # Flags are not prose: never let "--adb" end up as the reason the phone shows.
        words = [a for a in rest[2:] if not a.startswith("--")]
        reason = words[0] if words else (
            "Demo: l'agente dichiara uno swap ma la transazione è un invio" if cmd == "lie"
            else "Test del gate dalla riga di comando"
        )
        built = agent.build_transfer(to, amount, reason, NAME)
        lie = {"action": "swap", "outMint": "SOL", "outAmount": amount, "inMint": "USDC", "inAmount": 1.0} if cmd == "lie" else None
        send = "--send0" not in rest and cmd != "lie"
        out = agent.propose(built["req_id"], send=send, demo_lie=lie)
        show(out)
        if "--adb" in rest:
            print(json.dumps(agent.send_via_adb(built["req_id"], os.environ.get("APEX_TARGET")), indent=1))
        return 0
    if cmd == "swap":
        built = agent.build_swap(rest[0], rest[1], float(rest[2]), rest[3] if len(rest) > 3 else "CLI swap", NAME)
        out = agent.propose(built["req_id"], send="--send0" not in rest)
        show(out)
        if "--adb" in rest:
            print(json.dumps(agent.send_via_adb(built["req_id"], os.environ.get("APEX_TARGET")), indent=1))
        return 0
    if cmd == "watch":
        show(agent.await_outcome(rest[0], int(rest[1]) if len(rest) > 1 else 180))
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
