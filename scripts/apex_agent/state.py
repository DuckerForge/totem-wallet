"""Small durable state: which wallet to use, and what we have proposed.

Kept out of the repo (an agent's state is per-machine) and written atomically so
a crash mid-write cannot leave a half file.
"""

from __future__ import annotations

import json
import os
import tempfile
import time
import uuid

DIR = os.path.join(os.path.expanduser("~"), ".local", "state", "apex-agent")
PATH = os.path.join(DIR, "state.json")


LEGACY = os.path.join(os.path.expanduser("~"), ".local", "state", "omni-agent", "state.json")


def load() -> dict:
    # Carry over state written before the rename, once.
    if not os.path.exists(PATH) and os.path.exists(LEGACY):
        try:
            os.makedirs(DIR, exist_ok=True)
            with open(LEGACY) as src, open(PATH, "w") as dst:
                dst.write(src.read())
        except Exception:
            pass
    try:
        with open(PATH) as fh:
            got = json.load(fh)
    except Exception:
        got = {}
    got.setdefault("wallet", None)
    got.setdefault("cluster", None)
    got.setdefault("requests", {})
    return got


def save(data: dict) -> None:
    os.makedirs(DIR, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=DIR, suffix=".tmp")
    try:
        with os.fdopen(fd, "w") as fh:
            json.dump(data, fh, indent=1)
        os.replace(tmp, PATH)
    except Exception:
        os.path.exists(tmp) and os.unlink(tmp)
        raise


def new_request_id() -> str:
    return uuid.uuid4().hex[:8]


def put_request(req_id: str, payload: dict) -> dict:
    data = load()
    payload["created_at"] = time.time()
    data["requests"][req_id] = payload
    # Keep the file small: only the last 20 proposals matter.
    if len(data["requests"]) > 20:
        oldest = sorted(data["requests"].items(), key=lambda kv: kv[1].get("created_at", 0))[:-20]
        for key, _ in oldest:
            data["requests"].pop(key, None)
    save(data)
    return payload


def get_request(req_id: str) -> dict | None:
    return load()["requests"].get(req_id)


def update_request(req_id: str, **fields) -> dict | None:
    data = load()
    req = data["requests"].get(req_id)
    if req is None:
        return None
    req.update(fields)
    save(data)
    return req
