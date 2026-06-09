# Data-source parity — XAUUSD — dukascopy (backtest) vs MT5 (live)

Generated: 2026-06-09T08:36:46.953715296Z
Day: 2026-06-04  Timeframe: 1m
Source A (backtest): dukascopy `XAUUSD` ticks, aggregated by the engine's CandleAggregator
Source B (live): MT5 broker `XAUUSDm` historical bars via the gateway

## Coverage

| Metric | Value |
| --- | --- |
| Dukascopy bars | 1379 |
| MT5 bars | 1378 |
| Aligned | 1377 |
| Only in dukascopy | 2 |
| Only in MT5 | 1 |

## Close-price parity (the prices your strategy decides on)

| Metric | Value |
| --- | --- |
| Mean abs(close_duka − close_mt5) | 0.16707480 |
| p95 abs(close_duka − close_mt5) | 0.36850000 |
| Max abs(close_duka − close_mt5) | 0.71700000 |
| Mean relative (bps) | 0.37 |
| Max delta at | 2026-06-04T06:52:00Z |

## Spread — the cost a paper backtest ignores

Dukascopy ticks carry a real bid/ask; a paper-broker backtest throws it away and
fills at the mid. These are the spreads present in the data but not in paper PnL.
Compare against the broker spread the MT5 gateway reports for XAUUSDm.

| Metric | Value |
| --- | --- |
| Dukascopy mean spread | 0.62881 |
| Dukascopy p50 spread | 0.60000000 |
| Dukascopy p95 spread | 0.91000000 |
| Dukascopy max spread | 5.95400000 |

## How to read this

- **Close parity** measures whether the *price level* your backtest replays matches what
  the broker recorded. Small deltas (a fraction of a dollar / a few bps on gold) mean the
  data source is faithful: a backtest that fires on a price will see that price live too.
- **It does not measure execution.** Even with identical prices, live fills pay the spread
  and slippage; the default paper backtest does not. That gap is catalogued separately in
  [backtest-vs-live.md](backtest-vs-live.md). Use `--broker mt5-sim` to model the spread.
- **Unaligned bars** are usually session edges (the broker's day starts/ends at a different
  wall-clock than 00:00 UTC) — expected, not a data fault, as long as the interior aligns.

## Per-bar (last 20 aligned)

| time | duka close | mt5 close | abs Δclose | abs Δopen | abs Δhigh | abs Δlow |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-06-04T23:39:00Z | 4463.81500000 | 4463.892 | 0.07700000 | 0.09100000 | 0.11950000 | 0.11500000 |
| 2026-06-04T23:40:00Z | 4464.15500000 | 4464.228 | 0.07300000 | 0.01400000 | 0.18150000 | 0.21600000 |
| 2026-06-04T23:41:00Z | 4464.77500000 | 4464.666 | 0.10900000 | 0.05300000 | 0.04400000 | 0.16900000 |
| 2026-06-04T23:42:00Z | 4464.04500000 | 4464.058 | 0.01300000 | 0.28500000 | 0.13600000 | 0.07800000 |
| 2026-06-04T23:43:00Z | 4462.21000000 | 4462.168 | 0.04200000 | 0.27400000 | 0.14650000 | 0.29400000 |
| 2026-06-04T23:44:00Z | 4463.45500000 | 4463.127 | 0.32800000 | 0.05200000 | 0.15700000 | 0.22800000 |
| 2026-06-04T23:45:00Z | 4462.53000000 | 4462.501 | 0.02900000 | 0.05400000 | 0.05400000 | 0.35100000 |
| 2026-06-04T23:46:00Z | 4461.93500000 | 4462.041 | 0.10600000 | 0.02300000 | 0.08600000 | 0.17200000 |
| 2026-06-04T23:47:00Z | 4462.50500000 | 4462.653 | 0.14800000 | 0.21800000 | 0.07300000 | 0.20950000 |
| 2026-06-04T23:48:00Z | 4462.28500000 | 4462.262 | 0.02300000 | 0.18700000 | 0.07600000 | 0.17400000 |
| 2026-06-04T23:49:00Z | 4464.12000000 | 4463.764 | 0.35600000 | 0.07800000 | 0.08300000 | 0.22000000 |
| 2026-06-04T23:50:00Z | 4465.30000000 | 4465.204 | 0.09600000 | 0.31450000 | 0.13100000 | 0.18300000 |
| 2026-06-04T23:51:00Z | 4464.72000000 | 4464.75 | 0.03000000 | 0.10700000 | 0.13550000 | 0.14000000 |
| 2026-06-04T23:52:00Z | 4463.27000000 | 4463.253 | 0.01700000 | 0.00900000 | 0.06300000 | 0.17100000 |
| 2026-06-04T23:53:00Z | 4463.83000000 | 4463.772 | 0.05800000 | 0.06100000 | 0.14750000 | 0.09500000 |
| 2026-06-04T23:54:00Z | 4464.50500000 | 4464.44 | 0.06500000 | 0.00400000 | 0.01900000 | 0.03400000 |
| 2026-06-04T23:55:00Z | 4464.20000000 | 4464.294 | 0.09400000 | 0.01600000 | 0.11500000 | 0.05000000 |
| 2026-06-04T23:56:00Z | 4463.68500000 | 4463.677 | 0.00800000 | 0.03400000 | 0.25700000 | 0.18000000 |
| 2026-06-04T23:57:00Z | 4461.87500000 | 4461.811 | 0.06400000 | 0.37600000 | 0.22100000 | 0.19500000 |
| 2026-06-04T23:58:00Z | 4463.70000000 | 4463.67 | 0.03000000 | 0.01900000 | 0.03000000 | 0.04800000 |
