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

    def test_max_per_hour_in_hint(self):
        html = render_page("mppt.lak.nz", max_per_hour=6)
        self.assertIn("At most 6 auto-pulses per hour", html)

    def test_window_labels_follow_args(self):
        html = render_page("mppt.lak.nz", gap_avg_s=180, extrema_s=3600)
        self.assertIn("3 min avg", html)
        self.assertIn("1h Voc", html)

    def test_page_has_the_automatic_on_off_section(self):
        """Issue #67: the /charger page must expose the scheduled ON/OFF window."""
        html = render_page("charger.example.com")
        self.assertIn("Automatic ON / OFF", html)
        self.assertIn("Enforce this window every day", html)
        self.assertIn("Turn ON at", html)
        self.assertIn("Turn OFF at", html)
        self.assertIn('id="schedOnTime"', html)
        self.assertIn('id="schedOffTime"', html)
        self.assertIn('type="time"', html)
        self.assertIn("Save window", html)
        self.assertIn("Resume window", html)
        self.assertIn('id="schedSum"', html)
        self.assertIn('id="schedNext"', html)
        self.assertIn('id="schedNote"', html)
        self.assertIn("/charger/schedule", html)
        self.assertIn("paintSchedule", html)
        self.assertIn("tickSchedule", html)
        # The countdown keeps ticking between status polls.
        self.assertIn("if (last && last.schedule) tickSchedule(last.schedule);", html)

    def test_schedule_placeholders_default_to_the_documented_window(self):
        html = render_page("charger.example.com")
        self.assertIn('id="schedOnTime" value="06:45"', html)
        self.assertIn('id="schedOffTime" value="17:30"', html)

    def test_schedule_placeholders_follow_args(self):
        html = render_page("charger.example.com", schedule_on="07:00", schedule_off="18:15")
        self.assertIn('id="schedOnTime" value="07:00"', html)
        self.assertIn('id="schedOffTime" value="18:15"', html)

    def test_bad_schedule_placeholders_fall_back_to_defaults(self):
        html = render_page("charger.example.com", schedule_on='"><script>', schedule_off="25:99")
        self.assertIn('id="schedOnTime" value="06:45"', html)
        self.assertIn('id="schedOffTime" value="17:30"', html)
        self.assertNotIn("<script>", html.split("id=\"schedOnTime\"", 1)[1].split(">", 1)[0])

    def test_schedule_section_needs_the_secret_to_show_values(self):
        """The page is served unauthenticated, so it must ship no saved window."""
        html = render_page("charger.example.com", schedule_on="05:15", schedule_off="21:45")
        self.assertIn("Unlock to load the window.", html)
        self.assertNotIn("05:15 AM", html)
        self.assertNotIn("9:45 PM", html)

    def test_host_is_sanitized(self):
        html = render_page('<script>alert(1)</script>')
        title = html.split("<h1>", 1)[1].split("</h1>", 1)[0]
        self.assertNotIn("<", title)
        self.assertIn("scriptalert1script", title)


if __name__ == "__main__":
    unittest.main()
