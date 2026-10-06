# Perpetual funding soak on Bybit

Holds 0.001 BTCUSDT (Bybit linear perpetual) for 65 minutes across a Bybit funding time, then closes it.
Bybit charges BTCUSDT funding every 8 hours, at 00:00, 08:00 and 16:00 UTC, as a funding execution on the
held position. Start it between 55 and 5 minutes before one of those times (for 08:00: 07:05 to 07:55 UTC)
on a flat Bybit testnet account behind a gateway running the `bybit` adapter with `GATEWAY_SETTING_CATEGORY=linear`.
The case turns qkt's measured-usage window off (`config:`), a demo-only setting. It passes when qkt's
realized P&L equals the venue's deals net less the funding Bybit charged, to the last digit, the strategy
booked that funding, and a replay of the gateway's bars makes the same fills. Run it with
`run-derivatives-lane-case.py --case scripts/live-validation/funding-soak-bybit`, `--expected-login` the
`account_login` the gateway's `/v1/health` reports, the gateway's trader token in `QKT_DERIV_GATEWAY_KEY`, and
`--budget-seconds 4800`. It is not in the catalog: the catalog runs at any hour, and funding comes three
times a day.
