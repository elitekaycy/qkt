# Perpetual funding soak

Holds 0.01 BTC_USDC-PERPETUAL for 80 minutes on a gateway account, so the venue charges funding at least
once (the paper adapter charges each hour Deribit publishes; Deribit realizes funding at its 08:00 UTC
settlement, so start a Deribit run before 07:00 UTC), then closes it. It passes when qkt's realized P&L
equals the venue's deals net less the funding it charged, to the last digit, and a replay of the gateway's
bars makes the same fills. It runs longer than the catalog's ten minutes, so it is not in the catalog:

```bash
QKT_DERIV_GATEWAY_KEY=… QKT_LIVE_DEMO_ORDER_APPROVAL=LOCALHOST_DEMO_ONLY \
  scripts/live-validation/run-derivatives-lane-case.py --case scripts/live-validation/funding-soak \
  --out /tmp/funding-soak --gateway-url http://127.0.0.1:8443 --expected-login paper \
  --arm I_UNDERSTAND_DEMO_ORDER_0.01 --budget-seconds 6000
```
