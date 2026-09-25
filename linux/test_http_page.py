import unittest

from mppt_ble.http_page import render_page


class HttpPageTest(unittest.TestCase):
    def test_page_has_controls_and_live_fields(self):
        html = render_page("mppt.lak.nz")
        self.assertIn("<title>MPPT</title>", html)
        self.assertIn("mppt.lak.nz", html)
        self.assertIn("X-Remote-Secret", html)
        self.assertIn("/charger/status", html)
        self.assertIn("Pulse cascade", html)
        self.assertIn("panelVoltage", html)
        self.assertIn("deltaVoltage", html)
        self.assertIn("pulseWhy", html)
        self.assertIn("Recent pulses", html)
        self.assertIn("Cooldown", html)
        self.assertIn("At most 4 auto-pulses per hour", html)
        self.assertIn("Gap now", html)
        self.assertIn("2 min avg", html)
        self.assertIn("2h Voc", html)
        self.assertIn("avgGap", html)
        self.assertIn("vocGap", html)
        self.assertIn("tag live", html)
        self.assertIn("tag calc", html)
        self.assertIn("tag rule", html)
        self.assertIn("hardcoded", html)
        self.assertIn('id="rules"', html)
        self.assertIn("envelopeW", html)
        self.assertIn("panelMinV", html)
        self.assertIn("gapMarginFrac", html)
        self.assertIn("minVocBusMult", html)
        self.assertIn("panelError", html)
        self.assertIn("GATT", html)

    def test_page_has_editable_daily_schedule(self):
        html = render_page("mppt.lak.nz")
        self.assertIn("Charger schedule", html)
        self.assertIn("Daily window", html)
        self.assertIn("Sunlight boost", html)
        self.assertIn('id="schEnabled"', html)
        self.assertIn('id="schOn"', html)
        self.assertIn('id="schOff"', html)
        self.assertIn('id="btnSaveSched"', html)
        self.assertIn('id="schedClock"', html)
        self.assertIn('id="schedPill"', html)
        self.assertIn('id="schedTrack"', html)
        self.assertIn("schSummary", html)
        self.assertIn("/charger/schedule", html)
        self.assertIn("paintSchedule", html)
        self.assertIn("serverZone", html)
        self.assertIn("Unsaved changes", html)

    def test_max_per_hour_in_hint(self):
        html = render_page("mppt.lak.nz", max_per_hour=6)
        self.assertIn("At most 6 auto-pulses per hour", html)

    def test_window_labels_follow_args(self):
        html = render_page("mppt.lak.nz", gap_avg_s=180, extrema_s=3600)
        self.assertIn("3 min avg", html)
        self.assertIn("1h Voc", html)

    def test_host_is_sanitized(self):
        html = render_page('<script>alert(1)</script>')
        title = html.split("<h1>", 1)[1].split("</h1>", 1)[0]
        self.assertNotIn("<", title)
        self.assertIn("scriptalert1script", title)


if __name__ == "__main__":
    unittest.main()
