import unittest

from mppt_ble.protocol import (
    ACL_SETTLE_S,
    PANEL_ADAPTER_RESET_AFTER,
    PANEL_POLL_BACKOFF_MAX_S,
    PANEL_POLL_S,
    REG_BATTERY_VOLTAGE_SETTING,
    REG_PANEL_VOLTAGE,
    make_read,
    panel_payload_ok,
    panel_poll_sleep_s,
    panel_voltage_of,
    parse_register_stream,
    should_reset_adapter,
    stream_started,
    system_voltage_of,
)


class PanelVoltageProtocolTest(unittest.TestCase):
    def test_edef_40v_mode(self):
        self.assertEqual(0xEDEF, REG_BATTERY_VOLTAGE_SETTING)
        self.assertEqual(40.0, system_voltage_of(b"\x28"))
        self.assertIsNone(system_voltage_of(b"\x00"))

    def test_poll_at_least_every_10s(self):
        self.assertLessEqual(PANEL_POLL_S, 10.0)

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

    def test_live_hq2531_118v(self):
        parsed, leftover = parse_register_stream(bytes.fromhex("080319edbb422f2e"))
        self.assertEqual(b"", leftover)
        self.assertAlmostEqual(118.23, panel_voltage_of(parsed[0xEDBB]), delta=0.001)

    def test_ack_does_not_clobber_value(self):
        parsed, _ = parse_register_stream(bytes.fromhex("080319edbb422f2e090019edbb01"))
        self.assertAlmostEqual(118.23, panel_voltage_of(parsed[0xEDBB]), delta=0.001)

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

    def test_f901_only_is_not_panel_voltage(self):
        parsed, leftover = parse_register_stream(bytes.fromhex("f901"))
        self.assertNotIn(REG_PANEL_VOLTAGE, parsed)
        self.assertFalse(panel_payload_ok(parsed.get(REG_PANEL_VOLTAGE)))
        self.assertIsNone(panel_voltage_of(parsed.get(REG_PANEL_VOLTAGE)))
        self.assertFalse(stream_started(["f901"]))

    def test_stream_started_ignores_control_ack(self):
        self.assertFalse(stream_started(["f901"]))
        self.assertFalse(stream_started([]))
        self.assertTrue(stream_started(["f901", "08001893421027"]))
        self.assertTrue(stream_started(["080319edbb425934"]))

    def test_session_awake_is_f980_reply_or_stream(self):
        from mppt_ble.protocol import session_awake

        self.assertFalse(session_awake(["f901"]))
        self.assertTrue(session_awake(["029f000001000301ff07000300"]))
        self.assertTrue(session_awake(["f901", "08001893421027"]))

    def test_poll_sleep_backs_off_on_failure(self):
        self.assertGreaterEqual(panel_poll_sleep_s(True, 0, 3.0), 6.0)
        self.assertEqual(panel_poll_sleep_s(True, 0, 11.0), ACL_SETTLE_S)
        self.assertGreaterEqual(panel_poll_sleep_s(False, 1, 5.0), 14.0)
        self.assertGreaterEqual(panel_poll_sleep_s(False, 2, 5.0), 34.0)
        self.assertLessEqual(panel_poll_sleep_s(False, 8, 0.0), PANEL_POLL_BACKOFF_MAX_S)
        self.assertGreaterEqual(panel_poll_sleep_s(False, 8, 200.0), 1.0)

    def test_adapter_reset_disabled(self):
        self.assertEqual(0, PANEL_ADAPTER_RESET_AFTER)
        self.assertFalse(should_reset_adapter(0))
        self.assertFalse(should_reset_adapter(1))
        self.assertFalse(should_reset_adapter(6))
        self.assertFalse(should_reset_adapter(12))


if __name__ == "__main__":
    unittest.main()
