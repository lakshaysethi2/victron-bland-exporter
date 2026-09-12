"""Persist gap samples and pulse times so a restart does not go blind."""

from __future__ import annotations

import logging
import os
import sqlite3
from pathlib import Path

log = logging.getLogger("mppt_yield")

KEEP_S = 3 * 3600
DEFAULT_PATH = Path.home() / ".config/mppt/watchdog.sqlite"

Sample = tuple[float, float, float | None, float | None]


def default_path() -> Path:
    env = os.environ.get("MPPT_WATCHDOG_DB", "").strip()
    return Path(env).expanduser() if env else DEFAULT_PATH


class WatchdogStore:
    def __init__(self, path: Path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(str(path), timeout=5)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA synchronous=NORMAL")
        self.conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS samples (
              ts REAL PRIMARY KEY,
              watts REAL NOT NULL,
              pv REAL,
              outv REAL
            );
            CREATE TABLE IF NOT EXISTS pulses (
              ts REAL PRIMARY KEY
            );
            """
        )
        self.conn.commit()
        self._writes = 0

    def record_sample(
        self, ts: float, watts: float, pv: float | None, out: float | None
    ) -> None:
        try:
            self.conn.execute(
                "INSERT OR REPLACE INTO samples(ts, watts, pv, outv) VALUES (?,?,?,?)",
                (ts, watts, pv, out),
            )
            self._writes += 1
            if self._writes % 30 == 0:
                self._prune(ts)
            self.conn.commit()
        except sqlite3.Error as exc:
            log.warning("watchdog db sample: %s", exc)

    def record_pulse(self, ts: float) -> None:
        try:
            self.conn.execute("INSERT OR REPLACE INTO pulses(ts) VALUES (?)", (ts,))
            self.conn.commit()
        except sqlite3.Error as exc:
            log.warning("watchdog db pulse: %s", exc)

    def load(self, now: float, keep_s: float = KEEP_S) -> tuple[list[Sample], list[float]]:
        cutoff = now - keep_s
        try:
            sample_rows = self.conn.execute(
                "SELECT ts, watts, pv, outv FROM samples WHERE ts >= ? ORDER BY ts",
                (cutoff,),
            ).fetchall()
            pulse_rows = self.conn.execute(
                "SELECT ts FROM pulses WHERE ts >= ? ORDER BY ts",
                (cutoff,),
            ).fetchall()
        except sqlite3.Error as exc:
            log.warning("watchdog db load: %s", exc)
            return [], []
        samples = [
            (float(ts), float(watts), pv, outv) for ts, watts, pv, outv in sample_rows
        ]
        pulses = [float(row[0]) for row in pulse_rows]
        return samples, pulses

    def _prune(self, now: float, keep_s: float = KEEP_S) -> None:
        cutoff = now - keep_s
        self.conn.execute("DELETE FROM samples WHERE ts < ?", (cutoff,))
        self.conn.execute("DELETE FROM pulses WHERE ts < ?", (cutoff,))
