"""Instant Readout advert parser — same layout as VictronParser.kt."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

CHARGE_STATE = {
    0: "OFF",
    1: "LOW_POWER",
    2: "FAULT",
    3: "BULK",
    4: "ABSORPTION",
    5: "FLOAT",
    6: "STORAGE",
    7: "EQUALIZE",
    8: "PASSTHRU",
    9: "INVERTING",
    10: "POWER_SUPPLY",
    11: "STARTING_UP",
    12: "REPEATED_ABSORPTION",
    13: "AUTO_EQUALIZE",
    14: "BATTERY_SAFE",
}

VICTRON_COMPANY_ID = 0x02E1


class BitReader:
    def __init__(self, data: bytes) -> None:
        self.data = data
        self.byte_index = 0
        self.bit_offset = 0

    def read_unsigned(self, bits: int) -> int:
        result = 0
        remaining = bits
        current_bit = self.bit_offset
        current_byte = self.byte_index
        while remaining > 0:
            if current_byte >= len(self.data):
                break
            byte_val = self.data[current_byte]
            bits_in_this = 8 - current_bit
            take = min(remaining, bits_in_this)
            mask = ((1 << take) - 1) << current_bit
            extracted = (byte_val & mask) >> current_bit
            result |= extracted << (bits - remaining)
            remaining -= take
            current_bit += take
            if current_bit >= 8:
                current_bit = 0
                current_byte += 1
        self.byte_index = current_byte
        self.bit_offset = current_bit
        return result

    def read_signed(self, bits: int) -> int:
        unsigned = self.read_unsigned(bits)
        sign = 1 << (bits - 1)
        if unsigned & sign:
            return unsigned - (1 << bits)
        return unsigned


@dataclass
class ParsedDevice:
    mac: str
    model_id: int
    record_type: int
    data: dict[str, Any]
    rssi: int


def _payload(manufacturer_data: bytes) -> bytes:
    # BlueZ/bleak: company id already stripped (same as Android).
    if len(manufacturer_data) >= 4 and manufacturer_data[0] == 0xE1 and manufacturer_data[1] == 0x02:
        return manufacturer_data[2:]
    return manufacturer_data


def is_victron(manufacturer_data: bytes) -> bool:
    p = _payload(manufacturer_data)
    return len(p) >= 2 and p[0] == 0x10 and p[1] == 0x02


def _decrypt(ciphertext: bytes, key: bytes, iv2: bytes) -> bytes:
    full_iv = iv2 + bytes(14)
    decryptor = Cipher(algorithms.AES(key), modes.CTR(full_iv)).decryptor()
    return decryptor.update(ciphertext) + decryptor.finalize()


def parse_solar(decrypted: bytes) -> dict[str, Any]:
    r = BitReader(decrypted)
    charge_state = r.read_unsigned(8)
    charger_error = r.read_unsigned(8)
    battery_voltage = r.read_signed(16)
    battery_current = r.read_signed(16)
    yield_today = r.read_unsigned(16)
    solar_power = r.read_unsigned(16)
    load_current = r.read_unsigned(9)
    return {
        "charge_state": None if charge_state == 0xFF else CHARGE_STATE.get(charge_state, charge_state),
        "charger_error": None if charger_error == 0xFF else charger_error,
        "battery_voltage": None if battery_voltage == 0x7FFF else battery_voltage / 100.0,
        "battery_current": None if battery_current == 0x7FFF else battery_current / 10.0,
        "yield_today_wh": None if yield_today == 0xFFFF else yield_today * 10,
        "solar_power_w": None if solar_power == 0xFFFF else solar_power,
        "load_current_a": None if load_current == 0x1FF else load_current / 10.0,
        "device_type": "mppt",
    }


def parse_battery(decrypted: bytes) -> dict[str, Any]:
    r = BitReader(decrypted)
    time_to_go = r.read_unsigned(16)
    voltage = r.read_signed(16)
    alarm = r.read_unsigned(16)
    aux = r.read_unsigned(16)
    aux_mode = r.read_unsigned(2)
    raw_current = r.read_unsigned(22)
    current = 0x7FFFFF if raw_current == 0x3FFFFF else raw_current
    signed = None
    if current != 0x7FFFFF:
        sign_bit = 1 << 21
        signed = current - (1 << 22) if current & sign_bit else current
    consumed_raw = r.read_unsigned(20)
    consumed = None if consumed_raw == 0xFFFFF else consumed_raw
    soc = r.read_unsigned(10)
    return {
        "time_to_go_min": None if time_to_go == 0xFFFF else time_to_go,
        "battery_voltage": None if voltage == 0x7FFF else voltage / 100.0,
        "alarm": alarm,
        "aux": aux,
        "aux_mode": aux_mode,
        "battery_current": None if signed is None else signed / 1000.0,
        "consumed_ah": None if consumed is None else -consumed / 10.0,
        "soc_percent": None if soc == 0x3FF else soc / 10.0,
        "device_type": "batterymonitor",
    }


def parse_advertisement(
    mac: str,
    manufacturer_data: bytes,
    rssi: int,
    encryption_key_hex: str | None,
) -> ParsedDevice | None:
    p = _payload(manufacturer_data)
    if not is_victron(p) or len(p) < 8 or not encryption_key_hex or len(encryption_key_hex) != 32:
        return None
    model_id = (p[3] << 8) | p[2]
    record_type = p[4]
    iv = p[5:7]
    key_check = p[7]
    encrypted = p[8:]
    try:
        key = bytes.fromhex(encryption_key_hex)
    except ValueError:
        return None
    if key_check != key[0]:
        return None
    decrypted = _decrypt(encrypted, key, iv)
    if record_type == 0x01:
        data = parse_solar(decrypted)
    elif record_type == 0x02:
        data = parse_battery(decrypted)
    else:
        data = {"raw": decrypted.hex()}
    return ParsedDevice(mac=mac, model_id=model_id, record_type=record_type, data=data, rssi=rssi)
