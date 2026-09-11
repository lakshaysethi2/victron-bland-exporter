"""Byte frames matching app/.../charger/ChargerProtocol.kt."""

from __future__ import annotations

SERVICE = "306b0001-b081-4037-83dc-e59fcc3cdfd0"
CONTROL = "306b0002-b081-4037-83dc-e59fcc3cdfd0"
SINGLE = "306b0003-b081-4037-83dc-e59fcc3cdfd0"
BULK = "306b0004-b081-4037-83dc-e59fcc3cdfd0"

REG_DEVICE_MODE = 0x0200
MODE_ON = 0x01
MODE_OFF = 0x04
MODE_OFF_LEGACY = 0x00

# VE.Direct HEX: PV input voltage. Instant Readout does not carry this.
REG_PANEL_VOLTAGE = 0xEDBB
PANEL_NA = 0xFFFF
PANEL_POLL_S = 60.0
PANEL_POLL_BACKOFF_S = 300.0
PANEL_FRESH_S = 300.0

# This SmartSolar drops the link on 306b0002 fa80ff, and on f941 after a 06008218 blob.
SAFE_INIT = [
    (SINGLE, bytes.fromhex("01")),
    (SINGLE, bytes.fromhex("0300")),
]
# Wakes the session without fa80ff.
F980 = bytes.fromhex("f980")
# VictronConnect stream-enable. Without the trailing 03010303, 0xEDBB GET returns
# 09 00 19 edbb 01 (unknown id). With it, 08 03 19 value frames stream. Do not
# follow with f941 — that combination drops this unit.
STREAM_ENABLE = bytes.fromhex("060082189342102703010303")


def hex_bytes(data: bytes) -> str:
    return data.hex()


def make_read(register_id: int, opcode: int = 0x81, kind: int = 0x03) -> bytes:
    return bytes(
        [
            0x05,
            kind,
            opcode,
            0x19,
            (register_id >> 8) & 0xFF,
            register_id & 0xFF,
        ]
    )


def make_write(register_id: int, value: bytes) -> bytes:
    if not 1 <= len(value) <= 15:
        raise ValueError("legacy writes are 1..15 bytes")
    return bytes(
        [
            0x06,
            0x03,
            0x82,
            0x19,
            (register_id >> 8) & 0xFF,
            register_id & 0xFF,
            0x40 + len(value),
        ]
    ) + value


def make_mode_write(on: bool) -> bytes:
    return make_write(REG_DEVICE_MODE, bytes([MODE_ON if on else MODE_OFF]))


def _frame_start(data: bytes, pos: int) -> int:
    """08/09 … 19 register frames (type 00 or 03)."""
    for i in range(pos, len(data) - 5):
        if data[i] in (0x08, 0x09) and data[i + 2] == 0x19:
            return i
    return -1


def parse_register_stream(data: bytes) -> tuple[dict[int, bytes], bytes]:
    result: dict[int, bytes] = {}
    pos = 0
    while pos + 6 <= len(data):
        start = _frame_start(data, pos)
        if start < 0:
            return result, b""
        if data[start] == 0x09:
            if start + 6 > len(data):
                return result, data[start:]
            reg = (data[start + 3] << 8) | data[start + 4]
            # 1-byte 09 ACK (unknown-id / not-supported) must not clobber a 2-byte value.
            if not panel_payload_ok(result.get(reg)):
                result[reg] = bytes([data[start + 5]])
            pos = start + 6
            continue
        length_type = data[start + 5]
        if length_type == 0x58:
            if start + 7 > len(data):
                return result, data[start:]
            length = data[start + 6]
            value_start = start + 7
        elif length_type == 0x50:
            length, value_start = 16, start + 6
        else:
            length, value_start = length_type & 0x0F, start + 6
        if length <= 0 or value_start + length > len(data):
            return result, data[start:]
        reg = (data[start + 3] << 8) | data[start + 4]
        result[reg] = data[value_start : value_start + length]
        pos = value_start + length
    return result, b""


def panel_voltage_of(raw: bytes | None) -> float | None:
    """Little-endian un16 at 0.01 V. 0xFFFF is night/no-PV, not 655.35 V."""
    if raw is None or len(raw) < 2:
        return None
    centivolts = raw[0] | (raw[1] << 8)
    if centivolts == PANEL_NA:
        return None
    return centivolts / 100.0


def panel_payload_ok(raw: bytes | None) -> bool:
    """True when the device sent a 2-byte EDBB value (including night 0xFFFF)."""
    return raw is not None and len(raw) >= 2


def charger_mode_of(values: dict[int, bytes]) -> int | None:
    raw = values.get(REG_DEVICE_MODE)
    if not raw:
        return None
    return raw[0]


def mode_text(mode: int | None) -> str:
    if mode == MODE_ON:
        return "ON"
    if mode in (MODE_OFF, MODE_OFF_LEGACY):
        return "OFF"
    return "Unknown"


def mode_matches(mode: int | None, on: bool) -> bool:
    if mode == MODE_ON:
        return on
    if mode in (MODE_OFF, MODE_OFF_LEGACY):
        return not on
    return False
