import unittest

from mppt_ble.readout import parse_advertisement


class ReadoutTest(unittest.TestCase):
    def test_bluesolar_fixture(self):
        advert = bytes.fromhex("100242a0016207adceb37b605d7e0ee21b24df5c")
        parsed = parse_advertisement(
            "AA:BB:CC:DD:EE:FF",
            advert,
            -60,
            "adeccb947395801a4dd45a2eaa44bf17",
        )
        self.assertIsNotNone(parsed)
        assert parsed is not None
        self.assertEqual(0xA042, parsed.model_id)
        self.assertEqual("ABSORPTION", parsed.data["charge_state"])
        self.assertAlmostEqual(13.88, parsed.data["battery_voltage"], delta=0.01)
        self.assertEqual(30, parsed.data["yield_today_wh"])
        self.assertEqual(19, parsed.data["solar_power_w"])

    def test_smartshunt_fixture(self):
        advert = bytes.fromhex("100289a302b040af925d09a4d89aa0128bdef48c6298a9")
        parsed = parse_advertisement(
            "11:22:33:44:55:66",
            advert,
            -55,
            "aff4d0995b7d1e176c0c33ecb9e70dcd",
        )
        self.assertIsNotNone(parsed)
        assert parsed is not None
        self.assertAlmostEqual(12.53, parsed.data["battery_voltage"], delta=0.01)
        self.assertAlmostEqual(50.0, parsed.data["soc_percent"], delta=0.1)
        self.assertEqual(3, parsed.data["aux_mode"])

    def test_wrong_key(self):
        advert = bytes.fromhex("100242a0016207adceb37b605d7e0ee21b24df5c")
        self.assertIsNone(
            parse_advertisement("AA:BB:CC:DD:EE:FF", advert, -60, "00" * 16)
        )
