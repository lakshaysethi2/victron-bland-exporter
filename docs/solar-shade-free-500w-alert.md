# Shade-free 500 W alert → charger pulse (Linux laptop)

The house MPPT is controlled by the Linux box next to it (`linux/mppt_ble`), not the Android phone.

When Grafana fires `Solar power below 500W (11:30-15:30 NZST)`, pulse the charger:

```bash
curl -sS -H "X-Remote-Secret: $MPPT_REMOTE_SECRET" \
  -X POST http://127.0.0.1:5338/charger \
  -d '{"action":"restart"}'
```

Or point the Grafana webhook at `https://<tunnel>/charger` with that header.
The default Grafana **firing** payload (`kind=shade-free-expect`) is enough once `serve` accepts `restart`.
Resolved webhooks must not pulse. Cooldown 10 minutes. Always ends ON.

Existing helper on the laptop (no Grafana required):

```bash
python -m mppt_ble.yield_reset --mac "$MPPT_MAC" --metrics http://127.0.0.1:5338/metrics
```
