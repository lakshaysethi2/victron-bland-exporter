# Shade-free 500 W alert → charger pulse

When Grafana fires `Solar power below 500W (11:30-15:30 NZST)`, the MPPT is usually stuck. The fix is an off→on pulse.

## Exporter

`POST /charger` with the same secret as the remote page:

```json
{"action":"restart"}
```

That sends GATT OFF, waits 4 seconds, then GATT ON. Cooldown is 10 minutes.

Header: `X-Remote-Secret: <the remote secret>`.
Never put that secret in git, dashboards, or this file.

## Grafana contact point

1. Alerting → Contact points → webhook.
2. URL: `https://<your-tunnel-host>/charger`
3. HTTP method POST.
4. Header `X-Remote-Secret` = the remote secret already on the phone.
5. Body: `{"action":"restart"}`
6. Notification policy: this alert rule only, **firing** (not resolved).

Related issues: #33 (alert pulse), #31 (optional 30-minute cycle).
