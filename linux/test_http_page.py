import unittest

from mppt_ble.http_page import render_page


class HttpPageTest(unittest.TestCase):
    def test_page_has_controls_and_live_fields(self):
        html = render_page("mppt.lak.nz")
        self.assertIn("MPPT Charger", html)
        self.assertIn("mppt.lak.nz", html)
        self.assertIn("X-Remote-Secret", html)
        self.assertIn("/charger/status", html)
        self.assertIn("PULSE", html)
        self.assertIn("panelVoltage", html)
        self.assertIn("deltaVoltage", html)

    def test_host_is_sanitized(self):
        html = render_page('<script>alert(1)</script>')
        sub = html.split('class="sub">', 1)[1].split("</div>", 1)[0]
        self.assertNotIn("<", sub)
        self.assertIn("scriptalert1script", sub)


if __name__ == "__main__":
    unittest.main()
