"""Persist gap samples, pulse times and daily extremes so a restart is not blind."""

from __future__ import annotations

import datetime as dt
import logging
import os
import sqlite3
from pathlib import Path

log = logging.getLogger("mppt_yield")

KEEP_S = 3 * 3600
KEEP_DAYS = 30
LEARN_DAYS = 7
DEFAULT_PATH = Path.home() / ".config/mppt/watchdog.sqlite"

Sample = tuple[float, float, float | None, float | None]


def _max(values: list[float | None]) -> float | None:
    known = [float(v) for v in values if v is not None]
    return max(known) if known else None


def _min(values: list[float | None]) -> float | None:
    known = [float(v) for v in values if v is not None]
    return min(known) if known else None


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
            CREATE TABLE IF NOT EXISTS days (
              day TEXT PRIMARY KEY,
              pv_max REAL,
              pv_night REAL,
              watts_peak REAL
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
            self._record_day(ts, watts, pv)
            self._writes += 1
            if self._writes % 30 == 0:
                self._prune(ts)
            self.conn.commit()
        except sqlite3.Error as exc:
            log.warning("watchdog db sample: %s", exc)

    def _record_day(self, ts: float, watts: float, pv: float | None) -> None:
        """Tiny per-day extremes: the schedule learns its levels from these."""
        day = dt.date.fromtimestamp(ts).isoformat()
        row = self.conn.execute(
            "SELECT pv_max, pv_night, watts_peak FROM days WHERE day = ?", (day,)
        ).fetchone()
        pv_max = _max([pv, row[0] if row else None])
        pv_night = _min([pv, row[1] if row else None])
        watts_peak = _max([watts, row[2] if row else None])
        if row is not None and (pv_max, pv_night, watts_peak) == (row[0], row[1], row[2]):
            return
        self.conn.execute(
            "INSERT OR REPLACE INTO days(day, pv_max, pv_night, watts_peak) VALUES (?,?,?,?)",
            (day, pv_max, pv_night, watts_peak),
        )

    def learned_inputs(self, now: float, days: int = LEARN_DAYS) -> dict:
        """History for the schedule: past ``days`` days plus today's peak output."""
        today = dt.date.fromtimestamp(now).isoformat()
        try:
            rows = self.conn.execute(
                "SELECT pv_max, pv_night FROM days WHERE day < ? ORDER BY day DESC LIMIT ?",
                (today, days),
            ).fetchall()
            peak = self.conn.execute(
                "SELECT watts_peak FROM days WHERE day = ?", (today,)
            ).fetchone()
        except sqlite3.Error as exc:
            log.warning("watchdog db days: %s", exc)
            return {}
        return {
            "pv_max": _max([r[0] for r in rows]),
            "pv_night": _min([r[1] for r in rows]),
            "watts_peak_today": _max([peak[0] if peak else None]),
            "days": sum(1 for r in rows if r[0] is not None or r[1] is not None),
        }

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
        self.conn.execute(
            "DELETE FROM days WHERE day < ?",
            ((dt.date.fromtimestamp(now) - dt.timedelta(days=KEEP_DAYS)).isoformat(),),
        )
