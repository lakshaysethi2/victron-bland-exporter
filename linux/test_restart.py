from mppt_ble.restart import grafana_firing, wants_restart


def test_explicit_restart():
    assert wants_restart({"action": "restart"})
    assert not wants_restart({"action": "on"})


def test_grafana_firing():
    body = {
        "status": "firing",
        "alerts": [
            {
                "status": "firing",
                "labels": {
                    "kind": "shade-free-expect",
                    "alertname": "Solar power below 500W (11:30-15:30 NZST)",
                },
            }
        ],
    }
    assert grafana_firing(body)
    assert wants_restart(body)


def test_grafana_resolved():
    body = {"status": "resolved", "alerts": [{"status": "resolved", "labels": {"kind": "shade-free-expect"}}]}
    assert not grafana_firing(body)
