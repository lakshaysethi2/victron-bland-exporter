import os
import unittest
from unittest import mock

from mppt_ble.node_metrics import (
    fetch_node_metrics,
    node_exporter_url,
    prometheus_content_type,
)


class NodeMetricsTest(unittest.TestCase):
    def test_unset_env_is_empty(self):
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("NODE_EXPORTER_URL", None)
            self.assertEqual("", node_exporter_url())

    def test_empty_url_is_404(self):
        status, body, _ = fetch_node_metrics("")
        self.assertEqual(404, status)
        self.assertIn("Not Found", body)

    def test_strips_charset_from_node_exporter_content_type(self):
        raw = "text/plain; version=0.0.4; charset=utf-8; escaping=values"
        self.assertEqual("text/plain; version=0.0.4", prometheus_content_type(raw))
        self.assertNotIn("charset", prometheus_content_type(raw))

    def test_fetch_does_not_pass_charset_to_aiohttp(self):
        class FakeResp:
            headers = {
                "Content-Type": "text/plain; version=0.0.4; charset=utf-8; escaping=values"
            }

            def read(self) -> bytes:
                return b"node_load1 0.19\nnode_cpu_seconds_total{mode=\"idle\"} 1\n"

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

        with mock.patch("mppt_ble.node_metrics.urlopen", return_value=FakeResp()):
            status, body, ctype = fetch_node_metrics("http://127.0.0.1:9100/metrics")
        self.assertEqual(200, status)
        self.assertIn("node_load1", body)
        self.assertEqual("text/plain; version=0.0.4", ctype)
        self.assertNotIn("charset", ctype)


if __name__ == "__main__":
    unittest.main()
