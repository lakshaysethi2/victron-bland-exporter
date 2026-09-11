"""One-shot GATT session against a SmartSolar (BlueZ / bleak)."""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field

from bleak import BleakClient, BleakScanner
from bleak.backends.device import BLEDevice

from . import protocol as P

log = logging.getLogger("mppt_ble")


@dataclass
class SessionResult:
    success: bool
    mode: int | None
    message: str
    notifies: list[str] = field(default_factory=list)


@dataclass
class RegisterRead:
    success: bool
    values: dict[int, bytes]
    message: str
    notifies: list[str] = field(default_factory=list)

    def panel_volts(self) -> float | None:
        return P.panel_voltage_of(self.values.get(P.REG_PANEL_VOLTAGE))


async def find_device(mac: str | None, timeout: float = 12.0) -> BLEDevice:
    if mac:
        dev = await BleakScanner.find_device_by_address(mac, timeout=timeout)
        if dev is None:
            raise RuntimeError(f"no advertisement from {mac} in {timeout}s")
        return dev
    found = await BleakScanner.discover(timeout=timeout)
    victron = [d for d in found if d.name and "SmartSolar" in d.name]
    if not victron:
        raise RuntimeError(f"no SmartSolar in {len(found)} BLE ads")
    return victron[0]


async def scan(timeout: float = 8.0) -> list[dict]:
    found = await BleakScanner.discover(timeout=timeout)
    rows = []
    for d in found:
        rows.append({"mac": d.address, "name": d.name, "rssi": getattr(d, "rssi", None)})
    return rows


class MpptClient:
    def __init__(self, device: BLEDevice):
        self.device = device
        self.notifies: list[str] = []
        self.regs: dict[int, bytes] = {}
        self._buf = b""
        self._mode: int | None = None
        self._mode_event = asyncio.Event()
        self._wrote = False

    def _on_notify(self, _handle: int, data: bytearray) -> None:
        raw = bytes(data)
        if not raw:
            return
        self.notifies.append(raw.hex())
        log.info("notify %s", raw.hex())
        self._buf += raw
        if len(self._buf) > 512:
            self._buf = self._buf[-256:]
        parsed, leftover = P.parse_register_stream(self._buf)
        self._buf = leftover
        self.regs.update(parsed)
        mode = P.charger_mode_of(parsed)
        if mode is not None:
            self._mode = mode
            self._mode_event.set()
            log.info("device mode %s (%s)", P.mode_text(mode), mode)

    async def _write(self, client: BleakClient, uuid: str, payload: bytes) -> None:
        log.info("write %s %s", uuid[:8], payload.hex())
        await client.write_gatt_char(uuid, payload, response=False)
        self._wrote = True
        await asyncio.sleep(0.15)

    async def run(self, on: bool | None) -> SessionResult:
        async with BleakClient(self.device, timeout=20.0) as client:
            log.info("connected %s", self.device.address)
            await asyncio.sleep(0.3)
            for uuid in (P.CONTROL, P.SINGLE, P.BULK):
                try:
                    await client.start_notify(uuid, self._on_notify)
                except Exception as e:
                    log.debug("notify %s: %s", uuid[:8], e)
            for uuid, payload in P.SAFE_INIT:
                await self._write(client, uuid, payload)
            if on is not None:
                await self._write(client, P.SINGLE, P.make_mode_write(on))
                if not on:
                    await self._write(
                        client, P.SINGLE, P.make_write(P.REG_DEVICE_MODE, bytes([P.MODE_OFF_LEGACY]))
                    )
            await self._write(client, P.SINGLE, P.make_read(P.REG_DEVICE_MODE, 0x81))
            await self._wait_regs({P.REG_DEVICE_MODE}, 1.5)

        if self._mode is not None:
            ok = on is None or P.mode_matches(self._mode, on)
            return SessionResult(ok, self._mode, f"mode {P.mode_text(self._mode)}", self.notifies)
        if on is not None and self._wrote:
            return SessionResult(
                True,
                None,
                "wrote mode; no GATT echo — treat write as accepted",
                self.notifies,
            )
        return SessionResult(False, None, "no device-mode readback", self.notifies)

    async def _wait_regs(self, wanted: set[int], timeout_s: float) -> None:
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            if wanted <= self.regs.keys():
                return
            await asyncio.sleep(0.05)

    async def read_regs(self, registers: list[int]) -> RegisterRead:
        wanted = set(registers)
        async with BleakClient(self.device, timeout=20.0) as client:
            log.info("connected %s", self.device.address)
            await asyncio.sleep(0.3)
            for uuid in (P.CONTROL, P.SINGLE, P.BULK):
                try:
                    await client.start_notify(uuid, self._on_notify)
                except Exception as e:
                    log.debug("notify %s: %s", uuid[:8], e)
            for uuid, payload in P.SAFE_INIT:
                await self._write(client, uuid, payload)
            try:
                await self._write(client, P.CONTROL, P.F980)
            except Exception as e:
                log.debug("f980: %s", e)
            for reg in registers:
                kind = 0x00 if reg == P.REG_PANEL_VOLTAGE else 0x03
                await self._write(client, P.SINGLE, P.make_read(reg, 0x81, kind=kind))
            await self._wait_regs(wanted, 2.0)
            if P.REG_PANEL_VOLTAGE in wanted and not P.panel_payload_ok(
                self.regs.get(P.REG_PANEL_VOLTAGE)
            ):
                await asyncio.sleep(0.4)

        if P.REG_PANEL_VOLTAGE in wanted and not P.panel_payload_ok(
            self.regs.get(P.REG_PANEL_VOLTAGE)
        ):
            raw = self.regs.get(P.REG_PANEL_VOLTAGE)
            return RegisterRead(
                False,
                dict(self.regs),
                f"0xEDBB ack without value ({None if raw is None else raw.hex()})",
                self.notifies,
            )
        missing = [r for r in registers if r not in self.regs]
        if missing:
            names = ",".join(f"0x{r:04X}" for r in missing)
            return RegisterRead(False, dict(self.regs), f"no readback for {names}", self.notifies)
        return RegisterRead(True, dict(self.regs), "ok", self.notifies)


async def set_mode(mac: str, on: bool) -> SessionResult:
    last = SessionResult(False, None, "no attempt", [])
    for attempt in range(2):
        device = await find_device(mac)
        last = await MpptClient(device).run(on=on)
        if last.success:
            if last.mode is None or P.mode_matches(last.mode, on):
                return last
        log.warning("set_mode attempt %s: %s", attempt + 1, last.message)
        await asyncio.sleep(0.6)
    return last


async def read_mode(mac: str) -> SessionResult:
    device = await find_device(mac)
    return await MpptClient(device).run(on=None)


async def read_registers(mac: str, registers: list[int]) -> RegisterRead:
    last = RegisterRead(False, {}, "no attempt", [])
    for attempt in range(2):
        device = await find_device(mac)
        last = await MpptClient(device).read_regs(registers)
        if last.success:
            return last
        log.warning("read_registers attempt %s: %s", attempt + 1, last.message)
        await asyncio.sleep(0.6)
    return last


async def read_panel_voltage(mac: str) -> RegisterRead:
    return await read_registers(mac, [P.REG_PANEL_VOLTAGE])
