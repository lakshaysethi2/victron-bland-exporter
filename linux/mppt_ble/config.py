from __future__ import annotations

import json
import os
from pathlib import Path

DEFAULT_PATH = Path.home() / ".config/mppt/devices.json"
FRESH_MS = 90_000


def load_devices(path: Path = DEFAULT_PATH) -> dict:
    data: dict = {"mac": "", "keys": {}, "schedule": {}}
    if path.is_file():
        data.update(json.loads(path.read_text()))
    env_mac = os.environ.get("MPPT_MAC", "").strip()
    if env_mac:
        data["mac"] = env_mac
    return data


def public_host() -> str:
    return os.environ.get("MPPT_PUBLIC_HOST", "").strip() or "local"
