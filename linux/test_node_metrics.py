import os
import unittest
from unittest import mock

from mppt_ble.node_metrics import fetch_node_metrics, node_exporter_url


class NodeMetricsTest(unittest.TestCase):
    def test_unset_env_is_empty(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("NODE_EXPORTER_URL", None)
            self.assertEqual("", node_exporter_url())

    def test_empty_url_is_404(self):
        status, body, _ = fetch_node_metrics("")
        self.assertEqual(404, status)
        self.assertIn("Not Found", body)


if __name__ == "__main__":
    unittest.main()
