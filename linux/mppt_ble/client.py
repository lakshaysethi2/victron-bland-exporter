"""One-shot GATT session against a SmartSolar (BlueZ / bleak)."""

from __future__ import annotations

import asyncio
import logging
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
        rows.append(
            {
                "mac": d.address,
                "name": d.name,
                "rssi": getattr(d, "rssi", None),
            }
        )
    return rows


class MpptClient:
    def __init__(self, device: BLEDevice):
        self.device = device
        self.notifies: list[str] = []
        self.regs: dict[int, bytes] = {}
        self._buf = b""
        self._mode: int | None = None
        self._mode_event = asyncio.Event()

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
        await asyncio.sleep(0.08)

    async def run(self, on: bool | None) -> SessionResult:
        async with BleakClient(self.device, timeout=20.0) as client:
            log.info("connected %s svcs=%s", self.device.address, len(client.services.services) if client.services else 0)
            await asyncio.sleep(0.2)
            for uuid, payload in P.SAFE_INIT:
                await self._write(client, uuid, payload)
            if on is not None:
                await self._write(client, P.SINGLE, P.make_mode_write(on))
            await self._write(client, P.SINGLE, P.make_read(P.REG_DEVICE_MODE, 0x81))
            await asyncio.sleep(0.5)

        if self._mode is not None:
            return SessionResult(
                True, self._mode, f"mode {P.mode_text(self._mode)}", self.notifies
            )
        if on is not None:
            return SessionResult(
                True,
                None,
                "GATT writes accepted; no 0x0200 echo (confirm Instant Readout)",
                self.notifies,
            )
        return SessionResult(False, None, "no device-mode readback", self.notifies)


async def set_mode(mac: str, on: bool) -> SessionResult:
    device = await find_device(mac)
    return await MpptClient(device).run(on=on)


async def read_mode(mac: str) -> SessionResult:
    device = await find_device(mac)
    return await MpptClient(device).run(on=None)
