import unittest

from mppt_ble.protocol import (
    REG_PANEL_VOLTAGE,
    make_read,
    panel_payload_ok,
    panel_voltage_of,
    parse_register_stream,
)


class PanelVoltageProtocolTest(unittest.TestCase):
    def test_read_frame_matches_victron_hex(self):
        self.assertEqual(0xEDBB, REG_PANEL_VOLTAGE)
        self.assertEqual(bytes.fromhex("05038119edbb"), make_read(REG_PANEL_VOLTAGE))
        self.assertEqual(
            bytes.fromhex("05008119edbb"), make_read(REG_PANEL_VOLTAGE, kind=0x00)
        )

    def test_notify_222v(self):
        parsed, leftover = parse_register_stream(bytes.fromhex("080319edbb42b856"))
        self.assertEqual(b"", leftover)
        self.assertAlmostEqual(222.00, panel_voltage_of(parsed[0xEDBB]), delta=0.001)
        self.assertTrue(panel_payload_ok(parsed[0xEDBB]))

    def test_type00_value_frame(self):
        parsed, leftover = parse_register_stream(bytes.fromhex("080019edbb42b856"))
        self.assertEqual(b"", leftover)
        self.assertAlmostEqual(222.00, panel_voltage_of(parsed[0xEDBB]), delta=0.001)

    def test_type00_ack_is_not_a_voltage(self):
        parsed, leftover = parse_register_stream(bytes.fromhex("090019edbb01"))
        self.assertEqual(b"", leftover)
        self.assertEqual(b"\x01", parsed[0xEDBB])
        self.assertFalse(panel_payload_ok(parsed[0xEDBB]))
        self.assertIsNone(panel_voltage_of(parsed[0xEDBB]))

    def test_ffff_is_na(self):
        self.assertIsNone(panel_voltage_of(bytes.fromhex("ffff")))
        self.assertTrue(panel_payload_ok(bytes.fromhex("ffff")))
        self.assertIsNone(panel_voltage_of(None))
        self.assertIsNone(panel_voltage_of(b"\x00"))


if __name__ == "__main__":
    unittest.main()
