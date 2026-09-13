"""End-to-end tests for the automatic ON/OFF window inside `mppt_ble serve`.

The pure window semantics live in `test_schedule.py`. These run the *real*
aiohttp app from `_cmd_serve` with the BLE client and the scanner stubbed, so
the schedule loop, the HTTP routes and the manual-override handoff are exercised
together (issue #67).

Every assertion is wall-clock independent: windows are either 24 h (on == off),
disabled, or a one-minute window computed relative to "now", so the suite passes
at any hour on any host.
"""

from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import logging
import os
import socket
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest import mock

import aiohttp
from aiohttp import web

from mppt_ble import client, protocol as P

SECRET = "test-secret"
MAC = "AA:BB:CC:DD:EE:FF"
ALWAYS_ON = {"enabled": True, "on": "00:00", "off": "00:00"}  # degenerate = 24 h window


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def passed_window() -> dict:
    """A one-minute window that ended ~10 minutes ago: "now" is always outside it."""
    now_m = datetime.now().hour * 60 + datetime.now().minute
    on_m = (now_m - 10) % 1440
    off_m = (now_m - 9) % 1440
    return {"enabled": True, "on": "%02d:%02d" % (on_m // 60, on_m % 60), "off": "%02d:%02d" % (off_m // 60, off_m % 60)}


class FakeScanner:
    """Stands in for bleak.BleakScanner: no advertisements, no D-Bus."""

    def __init__(self, *args, **kwargs):
        self.detection_callback = kwargs.get("detection_callback")

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False


class ServeHarness:
    """One `mppt_ble serve` instance with stubbed BLE, bound to a free port."""

    def __init__(self, tmp: Path, devices: dict | None = None, argv: dict | None = None):
        self.tmp = tmp
        self.devices_path = tmp / "devices.json"
        self.devices_path.write_text(json.dumps(devices if devices is not None else {"mac": MAC, "keys": {}}))
        self.port = free_port()
        self.argv = argv or {}
        self.set_mode_calls: list[tuple[str, bool]] = []
        self.read_mode_calls: list[str] = []
        self.pulse_calls: list[str] = []
        self.task: asyncio.Task | None = None
        self._runners: list = []
        self._patches: list = []

    async def set_mode(self, mac: str, on: bool) -> client.SessionResult:
        self.set_mode_calls.append((mac, on))
        return client.SessionResult(True, P.MODE_ON if on else P.MODE_OFF, f"mode {'on' if on else 'off'}", [])

    async def read_mode(self, mac: str) -> client.SessionResult:
        self.read_mode_calls.append(mac)
        return client.SessionResult(True, P.MODE_ON, "mode on", [])

    async def read_panel_voltage(self, mac: str) -> client.RegisterRead:
        return client.RegisterRead(False, {}, "stubbed", [])

    async def pulse(self, mac: str):
        self.pulse_calls.append(mac)
        off = client.SessionResult(True, P.MODE_OFF, "off", [])
        on = client.SessionResult(True, P.MODE_ON, "on", [])
        return off, on

    def _patch(self, target, new):
        p = mock.patch(target, new)
        self._patches.append(p)
        return p.start()

    def _patch_attr(self, obj, name, new):
        p = mock.patch.object(obj, name, new)
        self._patches.append(p)
        return p.start()

    async def start(self) -> "ServeHarness":
        from mppt_ble import __main__ as entry
        from mppt_ble import config, schedule

        env = {
            "MPPT_REMOTE_SECRET": SECRET,
            "MPPT_MAC": MAC,
            "MPPT_PUBLIC_HOST": "charger.example.com",
        }
        # Keep the watchdog's sqlite out of the developer's home directory.
        env["MPPT_WATCHDOG_DB"] = str(self.tmp / "watchdog.sqlite")
        for key, value in env.items():
            os.environ[key] = value

        self._patch("mppt_ble.client.set_mode", self.set_mode)
        self._patch("mppt_ble.client.read_mode", self.read_mode)
        self._patch("mppt_ble.client.read_panel_voltage", self.read_panel_voltage)
        self._patch("mppt_ble.restart.pulse", self.pulse)
        self._patch("bleak.BleakScanner", FakeScanner)
        # Config lives in the test's temp dir, never in the developer's home.
        self._patch_attr(config, "DEFAULT_PATH", self.devices_path)
        self._patch_attr(config, "load_devices", lambda path=None: json.loads(self.devices_path.read_text()))
        # Make the loop tick fast enough to assert on inside a test.
        self._patch_attr(entry, "SCHEDULE_START_DELAY_S", 0.02)
        self._patch_attr(entry, "SCHEDULE_CHECK_S", 0.02)
        self._patch_attr(schedule, "REAPPLY_S", 0.05)

        # Record runners so teardown can release the listening socket.
        real_runner = web.AppRunner

        class TrackingRunner(real_runner):
            async def setup(self):
                await super().setup()
                harness_runners.append(self)

        harness_runners = self._runners
        self._patch_attr(aiohttp.web, "AppRunner", TrackingRunner)

        args = argparse.Namespace(
            mac=self.argv.get("mac", MAC),
            bind=f"127.0.0.1:{self.port}",
            schedule_on=self.argv.get("schedule_on", ""),
            schedule_off=self.argv.get("schedule_off", ""),
            no_schedule=self.argv.get("no_schedule", False),
        )
        self.task = asyncio.create_task(entry._cmd_serve(args))
        await self._wait_listening()
        return self

    async def _wait_listening(self, timeout: float = 10.0) -> None:
        deadline = asyncio.get_event_loop().time() + timeout
        while asyncio.get_event_loop().time() < deadline:
            if self.task.done():
                raise RuntimeError(f"serve exited early: {self.task.exception()!r}")
            try:
                reader, writer = await asyncio.open_connection("127.0.0.1", self.port)
                writer.close()
                with contextlib.suppress(Exception):
                    await writer.wait_closed()
                return
            except OSError:
                await asyncio.sleep(0.02)
        raise TimeoutError(f"serve did not listen on {self.port}")

    @property
    def base(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    def headers(self) -> dict:
        return {"X-Remote-Secret": SECRET}

    async def status(self, session: aiohttp.ClientSession) -> dict:
        async with session.get(f"{self.base}/charger/status", headers=self.headers()) as r:
            assert r.status == 200, await r.text()
            return await r.json()

    async def post(self, session: aiohttp.ClientSession, path: str, body: dict):
        async with session.post(f"{self.base}{path}", headers=self.headers(), json=body) as r:
            try:
                payload = await r.json()
            except Exception:
                payload = {}
            return r.status, payload

    async def wait_for(self, predicate, timeout: float = 3.0, what: str = "condition"):
        deadline = asyncio.get_event_loop().time() + timeout
        while asyncio.get_event_loop().time() < deadline:
            if predicate():
                return True
            await asyncio.sleep(0.02)
        raise AssertionError(f"timed out waiting for {what}")

    async def stop(self) -> None:
        if self.task and not self.task.done():
            self.task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await self.task
        for runner in self._runners:
            with contextlib.suppress(Exception):
                await runner.cleanup()
        for patch in reversed(self._patches):
            with contextlib.suppress(Exception):
                patch.stop()
        for key in ("MPPT_REMOTE_SECRET", "MPPT_MAC", "MPPT_PUBLIC_HOST", "MPPT_WATCHDOG_DB"):
            os.environ.pop(key, None)

    def saved_schedule(self) -> dict:
        return json.loads(self.devices_path.read_text()).get("schedule")


class ServeScheduleTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        logging.disable(logging.CRITICAL)
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)

    async def asyncTearDown(self):
        logging.disable(logging.NOTSET)
        self._tmp.cleanup()

    async def serve(self, devices=None, argv=None) -> ServeHarness:
        harness = ServeHarness(self.tmp, devices=devices, argv=argv)
        await harness.start()
        self.addAsyncCleanup(harness.stop)
        return harness

    async def test_status_reports_the_window_before_anything_is_saved(self):
        h = await self.serve()
        async with aiohttp.ClientSession() as session:
            snap = await h.status(session)
        sched = snap["schedule"]
        self.assertTrue(sched["enabled"])
        self.assertEqual("06:45", sched["onTime"])
        self.assertEqual("17:30", sched["offTime"])
        self.assertEqual("6:45 AM", sched["onTimeText"])
        self.assertEqual("5:30 PM", sched["offTimeText"])
        self.assertIn("Turns ON at 6:45 AM", sched["summary"])
        self.assertIsNotNone(sched["nextTransitionTs"])
        self.assertFalse(sched["overridden"])
        self.assertEqual("default", sched["source"])

    async def test_status_requires_the_secret(self):
        h = await self.serve()
        async with aiohttp.ClientSession() as session:
            async with session.get(f"{h.base}/charger/status") as r:
                self.assertEqual(401, r.status)
            async with session.post(f"{h.base}/charger/schedule", json=ALWAYS_ON) as r:
                self.assertEqual(401, r.status)

    async def test_always_on_window_turns_the_charger_on_by_itself(self):
        h = await self.serve(devices={"mac": MAC, "keys": {}, "schedule": ALWAYS_ON})
        async with aiohttp.ClientSession() as session:
            await h.wait_for(lambda: (MAC, True) in h.set_mode_calls, what="the loop to enable the charger")
            snap = await h.status(session)
        self.assertTrue(snap["schedule"]["wantsOn"])
        self.assertTrue(snap["schedule"]["appliedOn"])
        self.assertEqual("on", snap["schedule"]["lastAction"])
        self.assertTrue(snap["schedule"]["lastOk"])

    async def test_window_that_already_passed_turns_the_charger_off_by_itself(self):
        window = passed_window()
        h = await self.serve(devices={"mac": MAC, "keys": {}, "schedule": window})
        async with aiohttp.ClientSession() as session:
            await h.wait_for(lambda: (MAC, False) in h.set_mode_calls, what="the loop to disable the charger")
            snap = await h.status(session)
        self.assertEqual(window["on"], snap["schedule"]["onTime"])
        self.assertEqual(window["off"], snap["schedule"]["offTime"])
        self.assertFalse(snap["schedule"]["wantsOn"])
        self.assertFalse(snap["schedule"]["appliedOn"])
        self.assertEqual("off", snap["schedule"]["lastAction"])

    async def test_no_schedule_flag_leaves_the_charger_alone(self):
        h = await self.serve(devices={"mac": MAC, "keys": {}, "schedule": ALWAYS_ON}, argv={"no_schedule": True})
        async with aiohttp.ClientSession() as session:
            snap = await h.status(session)
            self.assertFalse(snap["schedule"]["enabled"])
            await asyncio.sleep(0.3)
            self.assertEqual([], h.set_mode_calls)

    async def test_cli_flags_set_the_window(self):
        window = passed_window()
        h = await self.serve(argv={"schedule_on": window["on"], "schedule_off": window["off"]})
        async with aiohttp.ClientSession() as session:
            snap = await h.status(session)
        self.assertEqual(window["on"], snap["schedule"]["onTime"])
        self.assertEqual(window["off"], snap["schedule"]["offTime"])
        self.assertEqual("cli", snap["schedule"]["source"])

    async def test_posting_a_window_saves_it_and_applies_it(self):
        h = await self.serve()
        async with aiohttp.ClientSession() as session:
            status, body = await h.post(session, "/charger/schedule", {"enabled": True, "on": "06:45", "off": "17:30"})
            self.assertEqual(202, status)
            self.assertTrue(body["accepted"])
            self.assertEqual("06:45", body["schedule"]["onTime"])
            self.assertEqual("5:30 PM", body["schedule"]["offTimeText"])
            snap = await h.status(session)
            self.assertEqual("06:45", snap["schedule"]["onTime"])
            self.assertEqual("17:30", snap["schedule"]["offTime"])
            self.assertEqual("http", snap["schedule"]["source"])
        self.assertEqual(
            {"enabled": True, "on": "06:45", "off": "17:30", "override_until": 0},
            h.saved_schedule(),
        )
        # Saving must not disturb the rest of devices.json.
        data = json.loads(h.devices_path.read_text())
        self.assertEqual(MAC, data["mac"])
        self.assertEqual({}, data["keys"])

    async def test_posting_a_window_takes_effect_without_a_restart(self):
        h = await self.serve(devices={"mac": MAC, "keys": {}, "schedule": passed_window()})
        async with aiohttp.ClientSession() as session:
            await h.wait_for(lambda: (MAC, False) in h.set_mode_calls, what="the first OFF")
            h.set_mode_calls.clear()
            status, _ = await h.post(session, "/charger/schedule", ALWAYS_ON)
            self.assertEqual(202, status)
            await h.wait_for(lambda: (MAC, True) in h.set_mode_calls, what="the loop to apply the new window")

    async def test_bad_schedule_bodies_are_rejected_and_change_nothing(self):
        h = await self.serve()
        async with aiohttp.ClientSession() as session:
            for body in [{"on": "25:00"}, {"off": "teatime"}, {"enabled": "sometimes"}, "not-json", []]:
                status, payload = await h.post(session, "/charger/schedule", body)
                self.assertEqual(400, status, body)
                self.assertIn("error", payload)
            snap = await h.status(session)
            self.assertEqual("06:45", snap["schedule"]["onTime"])
        self.assertIsNone(h.saved_schedule())

    async def test_an_omitted_edge_keeps_the_configured_one(self):
        h = await self.serve(devices={"mac": MAC, "keys": {}, "schedule": {"enabled": True, "on": "06:45", "off": "17:30"}})
        async with aiohttp.ClientSession() as session:
            status, body = await h.post(session, "/charger/schedule", {"enabled": False})
            self.assertEqual(202, status)
            self.assertFalse(body["schedule"]["enabled"])
            self.assertEqual("06:45", body["schedule"]["onTime"])
            self.assertEqual("17:30", body["schedule"]["offTime"])

    async def test_manual_off_pauses_an_always_on_window(self):
        h = await self.serve(devices={"mac": MAC, "keys": {}, "schedule": ALWAYS_ON})
        async with aiohttp.ClientSession() as session:
            await h.wait_for(lambda: (MAC, True) in h.set_mode_calls, what="the window to enable")
            status, body = await h.post(session, "/charger", {"action": "off"})
            self.assertEqual(200, status)
            self.assertTrue(body["success"])
            snap = await h.status(session)
            self.assertTrue(snap["schedule"]["overridden"])
            self.assertGreater(snap["schedule"]["overrideUntil"], 0)
            # The window must not immediately undo the hand flip. Count only
            # writes after the manual one, which the stub also records.
            h.set_mode_calls.clear()
            await asyncio.sleep(0.3)
            self.assertEqual([], h.set_mode_calls)
        # The pause is persisted, so a service restart does not resume early.
        self.assertGreater(h.saved_schedule()["override_until"], 0)

    async def test_saving_the_window_again_resumes_it(self):
        h = await self.serve(devices={"mac": MAC, "keys": {}, "schedule": ALWAYS_ON})
        async with aiohttp.ClientSession() as session:
            await h.wait_for(lambda: (MAC, True) in h.set_mode_calls, what="the window to enable")
            await h.post(session, "/charger", {"action": "off"})
            snap = await h.status(session)
            self.assertTrue(snap["schedule"]["overridden"])
            h.set_mode_calls.clear()
            status, body = await h.post(session, "/charger/schedule", ALWAYS_ON)
            self.assertEqual(202, status)
            self.assertFalse(body["schedule"]["overridden"])
            await h.wait_for(lambda: (MAC, True) in h.set_mode_calls, what="the resumed window to re-enable")

    async def test_a_read_does_not_pause_the_window(self):
        h = await self.serve(devices={"mac": MAC, "keys": {}, "schedule": ALWAYS_ON})
        async with aiohttp.ClientSession() as session:
            await h.wait_for(lambda: (MAC, True) in h.set_mode_calls, what="the window to enable")
            status, _ = await h.post(session, "/charger", {"action": "read"})
            self.assertEqual(200, status)
            self.assertEqual([MAC], h.read_mode_calls)
            snap = await h.status(session)
            self.assertFalse(snap["schedule"]["overridden"])

    async def test_the_window_holds_the_auto_pulse_outside_it(self):
        h = await self.serve(devices={"mac": MAC, "keys": {}, "schedule": passed_window()})
        async with aiohttp.ClientSession() as session:
            await h.wait_for(lambda: (MAC, False) in h.set_mode_calls, what="the window to disable")
            snap = await h.status(session)
        self.assertFalse(snap["pulseCandidate"])
        self.assertIsNotNone(snap["pulseBlocked"])
        self.assertIn("automatic OFF until", snap["pulseBlocked"])
        self.assertIn("Automatic ON/OFF", snap["pulseWhy"])

    async def test_a_disabled_window_leaves_the_watchdog_free(self):
        h = await self.serve(argv={"no_schedule": True})
        async with aiohttp.ClientSession() as session:
            snap = await h.status(session)
        self.assertIsNone(snap["pulseBlocked"])

    async def test_page_serves_the_schedule_section_without_the_secret(self):
        h = await self.serve()
        async with aiohttp.ClientSession() as session:
            async with session.get(f"{h.base}/charger") as r:
                self.assertEqual(200, r.status)
                html = await r.text()
        self.assertIn("Automatic ON / OFF", html)
        self.assertIn('id="schedOnTime"', html)
        self.assertIn('id="schedOffTime"', html)
        self.assertIn("/charger/schedule", html)
        # The shell must not carry the secret or any saved window values.
        self.assertNotIn(SECRET, html)


if __name__ == "__main__":
    unittest.main()
