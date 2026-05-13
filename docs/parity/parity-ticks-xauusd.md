# Ticks parity — XAUUSD — TV vs MT5

Generated: 2026-05-13T09:17:00.901235561Z
Window: 60s

## Throughput

| Source | ticks | ticks/s | mean gap (ms) | p95 gap (ms) | max gap (ms) |
| --- | --- | --- | --- | --- | --- |
| TV | 61 | 1.02 | 960 | 2047 | 2070 |
| MT5 | 90 | 1.50 | 672 | 1327 | 1594 |

## Mid-price parity

| Metric | Value |
| --- | --- |
| Paired MT5 ticks | 90 |
| Unpaired TV ticks | 0 |
| Unpaired MT5 ticks | 0 |
| Mean abs(mid_tv − mid_mt5) | 0.21153333 |
| p95 abs(mid_tv − mid_mt5) | 0.49800000 |
| Max abs(mid_tv − mid_mt5) | 0.90200000 |
| Mean relative (bps) | 0.45 |
| Median TV-vs-MT5 capture skew (ms) | -89 (>0 ⇒ TV arrives first) |
