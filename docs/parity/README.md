# Parity harness — TV vs MT5/Bybit

Throwaway tools that compare TradingView's market data against MT5 (XAUUSD) and Bybit (BTCUSDT) to validate TV is good enough for prod.

## Bars: XAUUSD M5 — TV vs MT5

**Prereq:** SSH tunnel to the MT5 gateway running on `173.249.58.247`:

```sh
ssh -L 5003:localhost:5003 root@173.249.58.247
```

(Keep the tunnel open while the harness runs.)

**Run:**

```sh
./gradlew runParityBarsXauusd
```

**Env overrides (all optional):**

| Var | Default |
| --- | --- |
| `TV_SYMBOL` | `OANDA:XAUUSD` |
| `MT5_URL` | `http://localhost:5003` |
| `MT5_SYMBOL` | `XAUUSD` |
| `BARS` | `300` |
| `OUT` | `docs/parity/parity-bars-xauusd-m5.md` |

**Output:** a markdown report at `$OUT` with coverage, close-price deltas (mean / p95 / max), unaligned bars, and a per-bar table for the last 20 aligned candles.
