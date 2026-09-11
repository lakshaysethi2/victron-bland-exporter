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

# This SmartSolar drops the link on 306b0002 fa80ff and on 06008218… blobs.
SAFE_INIT = [
    (SINGLE, bytes.fromhex("01")),
    (SINGLE, bytes.fromhex("0300")),
]


def hex_bytes(data: bytes) -> str:
    return data.hex()


def make_read(register_id: int, opcode: int = 0x81) -> bytes:
    return bytes(
        [
            0x05,
            0x03,
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


def parse_register_stream(data: bytes) -> tuple[dict[int, bytes], bytes]:
    result: dict[int, bytes] = {}
    pos = 0
    while pos + 6 <= len(data):
        start = data.find(b"\x08\x03\x19", pos)
        if start < 0:
            return result, b""
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
