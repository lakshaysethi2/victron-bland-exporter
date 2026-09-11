import unittest

from mppt_ble.auth import secret_ok


class AuthTest(unittest.TestCase):
    def test_header(self):
        self.assertTrue(secret_ok({"X-Remote-Secret": "abc"}, "abc"))
        self.assertTrue(secret_ok({"Authorization": "Bearer abc"}, "abc"))
        self.assertFalse(secret_ok({"X-Remote-Secret": "abd"}, "abc"))
        self.assertFalse(secret_ok({}, "abc"))
        self.assertFalse(secret_ok({"X-Remote-Secret": "abc"}, ""))
