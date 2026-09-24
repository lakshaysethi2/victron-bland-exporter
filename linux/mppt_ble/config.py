from __future__ import annotations

import json
import os
from pathlib import Path

from .schedule import normalize_config, validated_config

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


def load_schedule(path: Path = DEFAULT_PATH) -> dict:
    """Persisted daily window, defaults applied for missing/bad fields."""
    return normalize_config(load_devices(path).get("schedule"))


def save_schedule(
    enabled: object,
    enable_time: object,
    disable_time: object,
    path: Path = DEFAULT_PATH,
) -> dict:
    """Validate and atomically persist the window; raises ValueError/OSError."""
    entry = validated_config(enabled, enable_time, disable_time)
    data: dict = {}
    if path.is_file():
        try:
            loaded = json.loads(path.read_text())
            if isinstance(loaded, dict):
                data = loaded
        except (OSError, ValueError):
            data = {}
    data["schedule"] = entry
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)
    return entry


def public_host() -> str:
    return os.environ.get("MPPT_PUBLIC_HOST", "").strip() or "local"
