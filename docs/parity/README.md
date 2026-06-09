# Parity reports

Two kinds of parity live in this directory:

- **Data-source parity** — are the prices a backtest replays the same prices the broker recorded?
- **Execution parity** — given the same prices, does the backtest fill and account for trades the same way live does? See [`backtest-vs-live.md`](backtest-vs-live.md).

## Data-source parity — dukascopy (backtest) vs MT5 (live)

This is the one that matters for trusting a backtest. Since v0.35.0 a backtest auto-fetches its
ticks from **dukascopy**, so dukascopy — not TradingView — is the source it actually replays. The
report [`parity-dukascopy-vs-mt5-xauusd.md`](parity-dukascopy-vs-mt5-xauusd.md) pulls one day of
dukascopy ticks with the real backtest fetcher, aggregates them with the engine's own candle
builder, and compares against the broker's own bars from the MT5 gateway.

**Latest run (XAUUSD, 2026-06-04, 1m):** 1377 of 1379 minutes aligned; mean close delta **0.167**
(~0.37 bps on gold), p95 0.369, max 0.717. The price level a backtest sees tracks the broker feed
to a fraction of a dollar. Spread (the cost a paper backtest ignores) is reported alongside.

**Regenerate** (needs the SSH tunnel to the gateway, `ssh -L 5003:localhost:5003 root@173.249.58.247`):

```sh
DAY=2026-06-04 MT5_URL=http://localhost:5003 ./gradlew runParityDataXauusd
```

Env overrides: `DUKA_SYMBOL`, `MT5_SYMBOL`, `DAY`, `TIMEFRAME`, `MT5_URL`, `OUT`.

## Data-source harness — TV vs MT5/Bybit (vendor cross-check)

Throwaway tools that compare TradingView's market data against MT5 (XAUUSD) and Bybit (BTCUSDT).
These predate the dukascopy default; they validate TradingView as a *live* feed vendor, not the
backtest source. Kept as a second opinion on the broker feed.

### Bars: XAUUSD M5 — TV vs MT5

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
