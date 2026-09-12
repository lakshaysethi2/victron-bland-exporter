"""Optional proxy of a local node-exporter for dashboards that scrape one URL."""

from __future__ import annotations

import os
from urllib.request import urlopen

PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4"


def node_exporter_url() -> str:
    return os.environ.get("NODE_EXPORTER_URL", "").strip()


def prometheus_content_type(raw: str | None) -> str:
    """Strip charset=/escaping= so aiohttp web.Response does not 500.

    node_exporter 1.x sends
    ``text/plain; version=0.0.4; charset=utf-8; escaping=values``.
    aiohttp forbids charset inside the content_type argument.
    """
    if not raw:
        return PROMETHEUS_CONTENT_TYPE
    kept: list[str] = []
    for part in raw.split(";"):
        token = part.strip()
        if not token:
            continue
        key = token.split("=", 1)[0].strip().lower()
        if key in {"charset", "escaping"}:
            continue
        kept.append(token)
    return "; ".join(kept) or PROMETHEUS_CONTENT_TYPE


def fetch_node_metrics(url: str, timeout: float = 3.0) -> tuple[int, str, str]:
    """Return (status, body, content_type)."""
    if not url:
        return 404, "Not Found\n", "text/plain"
    with urlopen(url, timeout=timeout) as resp:
        body = resp.read().decode("utf-8", errors="replace")
        ctype = prometheus_content_type(resp.headers.get("Content-Type"))
        return 200, body, ctype
