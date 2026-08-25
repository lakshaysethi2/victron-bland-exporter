from __future__ import annotations

import hmac
import os


def configured_secret() -> str:
    return os.environ.get("MPPT_REMOTE_SECRET", "").strip()


def provided_secret(headers: dict[str, str]) -> str:
    raw = (headers.get("X-Remote-Secret") or headers.get("x-remote-secret") or "").strip()
    if raw:
        return raw
    auth = (headers.get("Authorization") or headers.get("authorization") or "").strip()
    if auth.lower().startswith("bearer "):
        return auth[7:].strip()
    return ""


def secret_ok(headers: dict[str, str], expected: str | None = None) -> bool:
    want = (expected if expected is not None else configured_secret()).encode("utf-8")
    got = provided_secret(headers).encode("utf-8")
    if not want or not got or len(want) != len(got):
        return False
    return hmac.compare_digest(want, got)
