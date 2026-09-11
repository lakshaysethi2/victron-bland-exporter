"""Optional proxy of a local node-exporter for dashboards that scrape one URL."""

from __future__ import annotations

import os
from urllib.request import urlopen


def node_exporter_url() -> str:
    return os.environ.get("NODE_EXPORTER_URL", "").strip()


def fetch_node_metrics(url: str, timeout: float = 3.0) -> tuple[int, str, str]:
    """Return (status, body, content_type)."""
    if not url:
        return 404, "Not Found\n", "text/plain"
    with urlopen(url, timeout=timeout) as resp:
        body = resp.read().decode("utf-8", errors="replace")
        ctype = resp.headers.get("Content-Type") or "text/plain; version=0.0.4"
        return 200, body, ctype
