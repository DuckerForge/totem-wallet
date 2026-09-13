"""Solana encoding and the transactions the agent can build. Standard library only."""

from __future__ import annotations

import json
import struct
import urllib.request

B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

SYSTEM_PROGRAM = "11111111111111111111111111111111"
MEMO_PROGRAM = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"
TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
WSOL = "So11111111111111111111111111111111111111112"

MAINNET = "https://api.mainnet-beta.solana.com"


class RpcError(RuntimeError):
    pass


def b58encode(raw: bytes) -> str:
    n = int.from_bytes(raw, "big")
    out = ""
    while n:
        n, r = divmod(n, 58)
        out = B58[r] + out
    return "1" * (len(raw) - len(raw.lstrip(b"\0"))) + out


def b58decode(text: str) -> bytes:
    n = 0
    for ch in text:
        idx = B58.find(ch)
        if idx < 0:
            raise ValueError(f"not base58: {text!r}")
        n = n * 58 + idx
    body = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return b"\0" * (len(text) - len(text.lstrip("1"))) + body


def pubkey(text: str) -> bytes:
    raw = b58decode(text)
    if len(raw) != 32:
        raise ValueError(f"a pubkey is 32 bytes, got {len(raw)}: {text}")
    return raw


def shortvec(n: int) -> bytes:
    """Solana's compact-u16 length prefix."""
    out = b""
    while True:
        byte = n & 0x7F
        n >>= 7
        out += bytes([byte | 0x80]) if n else bytes([byte])
        if not n:
            return out


def rpc_url(cluster: str | None) -> str:
    """Mirrors SolanaRpc.urlFor on the app side."""
    c = (cluster or "").lower()
    if c in ("devnet", "solana:devnet"):
        return "https://api.devnet.solana.com"
    if c in ("testnet", "solana:testnet"):
        return "https://api.testnet.solana.com"
    return MAINNET


def rpc(url: str, method: str, params: list, timeout: int = 20) -> dict:
    body = {"jsonrpc": "2.0", "id": 1, "method": method, "params": params}
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    got = json.loads(urllib.request.urlopen(req, timeout=timeout).read())
    if "error" in got:
        raise RpcError(f"{method}: {got['error']}")
    return got.get("result")


def latest_blockhash(url: str) -> str:
    return rpc(url, "getLatestBlockhash", [{"commitment": "finalized"}])["value"]["blockhash"]


def slot(url: str) -> int:
    return rpc(url, "getSlot", [{"commitment": "confirmed"}])


def balance(url: str, owner: str) -> int:
    return rpc(url, "getBalance", [owner, {"commitment": "confirmed"}])["value"]


def mint_decimals(url: str, mint: str) -> int:
    if mint.upper() == "SOL" or mint == WSOL:
        return 9
    return rpc(url, "getTokenSupply", [mint, {"commitment": "confirmed"}])["value"]["decimals"]


def signatures_for(url: str, owner: str, limit: int = 25) -> list[dict]:
    return rpc(url, "getSignaturesForAddress", [owner, {"limit": limit, "commitment": "confirmed"}]) or []


def transaction(url: str, signature: str) -> dict | None:
    return rpc(url, "getTransaction", [signature, {"maxSupportedTransactionVersion": 0, "encoding": "json"}])


def simulate(url: str, tx: bytes) -> dict:
    import base64

    params = [
        base64.b64encode(tx).decode(),
        {"encoding": "base64", "sigVerify": False, "replaceRecentBlockhash": True, "commitment": "processed"},
    ]
    return rpc(url, "simulateTransaction", params)["value"]


def build_transfer_sol(owner: str, to: str, lamports: int, blockhash: str, memo: str | None = None) -> bytes:
    """A legacy SOL transfer, optionally tagged with a memo.

    The memo carries a tag the PC can later recognise in `getSignaturesForAddress`,
    which is how an agent on another machine learns the outcome without any server.
    A memo moves no balance and is not one of the operations IntentGuard forbids,
    so it never trips the gate.
    """
    o, t, sysp = pubkey(owner), pubkey(to), pubkey(SYSTEM_PROGRAM)
    keys = [o, t, sysp]
    readonly_unsigned = 1
    data = struct.pack("<I", 2) + struct.pack("<Q", lamports)
    ixs = bytes([2]) + shortvec(2) + bytes([0, 1]) + shortvec(len(data)) + data
    count = 1
    if memo:
        keys.append(pubkey(MEMO_PROGRAM))
        readonly_unsigned = 2
        tag = memo.encode()
        ixs += bytes([3]) + shortvec(0) + shortvec(len(tag)) + tag
        count = 2
    message = (
        bytes([1, 0, readonly_unsigned])
        + shortvec(len(keys))
        + b"".join(keys)
        + pubkey(blockhash)
        + shortvec(count)
        + ixs
    )
    return shortvec(1) + b"\0" * 64 + message


def recent_blockhash_of(tx_json: dict) -> str | None:
    """The blockhash inside a fetched transaction — used to recognise a v0 swap."""
    try:
        return tx_json["transaction"]["message"]["recentBlockhash"]
    except (KeyError, TypeError):
        return None
