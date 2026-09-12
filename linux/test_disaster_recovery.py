"""Disaster-recovery runbook is in git and names restore keys, not live secrets."""

from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUNBOOK = ROOT / "docs" / "disaster-recovery.md"
EXAMPLE = Path(__file__).resolve().parent / "secrets.env.example"
README = Path(__file__).resolve().parent / "README.md"


class DisasterRecoveryRunbookTest(unittest.TestCase):
    def test_runbook_exists_and_names_restore_surface(self):
        text = RUNBOOK.read_text(encoding="utf-8")
        for needle in (
            "git-backed",
            "laptop-only",
            "mppt.lak.nz",
            "linger",
            "DC:AD:B0:54:DB:4E",
            "CLOUDFLARED_TOKEN_FILE",
            "secrets.env",
            "non-critical",
            "watchdog.sqlite",
        ):
            self.assertIn(needle, text, msg=needle)

    def test_example_env_lists_live_key_names(self):
        text = EXAMPLE.read_text(encoding="utf-8")
        for key in (
            "MPPT_REMOTE_SECRET",
            "MPPT_PUBLIC_HOST",
            "NODE_EXPORTER_URL",
            "CLOUDFLARED_TOKEN_FILE",
            "MPPT_MAX_PULSES_PER_HOUR",
        ):
            self.assertIn(key, text, msg=key)

    def test_readme_points_at_runbook(self):
        text = README.read_text(encoding="utf-8")
        self.assertIn("docs/disaster-recovery.md", text)


if __name__ == "__main__":
    unittest.main()
