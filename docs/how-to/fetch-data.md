# Backfill historical bars with `qkt fetch`

`qkt fetch` pulls historical OHLC bars from a broker's native API and writes them to a local store at `~/.qkt/data/bars/{BROKER}/{SYMBOL}/{TIMEFRAME}/{YYYY-MM-DD}.csv`. Backtests then read from that store instead of aggregating ticks at query time — fast, deterministic, and lets you backtest against broker history without first capturing ticks.

## When to use it

- **Before a backtest** on broker data: `qkt fetch EXNESS:XAUUSD --tf 5m --from 2024-01-01 --to 2024-12-31` then `qkt backtest strategy.qkt …` reads from the bar store automatically.
- **Air-gapped / offline runs.** Pre-populate the bar store on a connected machine; copy `~/.qkt/data/bars/` to the offline one.
- **Faster repeated backtests.** Aggregating from ticks every time is slow; the bar store is one CSV read per day.

You usually don't need `qkt fetch` for *live deployments* — `LiveSession` auto-fetches warmup history on deploy ([Phase 25B](../phases/phase-25b-live-auto-warmup.md)).

## Basic usage

```bash
# Explicit date range (UTC days, inclusive).
qkt fetch EXNESS:XAUUSD --tf 5m --from 2024-01-01 --to 2024-12-31

# Last N days from today.
qkt fetch BYBIT_SPOT:BTCUSDT --tf 1h --last 90d
```

Output:

```
qkt fetch: EXNESS:XAUUSD @ 5m from 2024-01-01 to 2024-01-03 (3 days)
  [1/3] 2024-01-01  fetched 288 bars
  [2/3] 2024-01-02  fetched 288 bars
  [3/3] 2024-01-03  skipped (already on disk)
qkt fetch: done — fetched=2 skipped=1 total=3
```

## Supported brokers

| Broker prefix | Backend | Notes |
|---|---|---|
| `EXNESS`, `ICMARKETS`, `FTMO`, `PEPPERSTONE`, … | `mt5-gateway` (per broker profile in `qkt.config.yaml`) | Pulls bars from the configured MT5 gateway. The profile's `gatewayUrl` must be reachable. |
| `BYBIT_SPOT` | `api.bybit.com /v5/market/kline?category=spot` | No auth needed for public kline data. |
| `BYBIT_LINEAR` | `api.bybit.com /v5/market/kline?category=linear` | Same, USDT perps. |
| `BACKTEST` | (refused) | `BACKTEST` *is* the local store — there's nothing to fetch from. Use a real broker prefix. |

For MT5 brokers, `qkt fetch` looks up the gateway URL via `qkt.config.yaml`:

```yaml
brokers:
  EXNESS:
    type: mt5
    extends: exness
    gatewayUrl: "${EXNESS_GATEWAY_URL:-http://localhost:5050}"
```

`qkt brokers list` shows what's resolved.

## On-disk layout

```
~/.qkt/data/bars/
└── EXNESS/
    └── XAUUSD/
        └── 5m/
            ├── 2024-01-01.csv     # one UTC day per file
            ├── 2024-01-02.csv
            ├── …
            └── manifest.json      # contiguous date ranges already on disk
```

Each `*.csv` is one UTC day with header `timestamp,open,high,low,close,volume`. The manifest tracks contiguous ranges so re-fetching is a no-op (it skips days already on disk and coalesces adjacent days into a single range).

## How backtests pick it up

`qkt backtest` wires the bar store into `LocalMarketSource`. When a strategy asks for bars on `(broker, symbol, tf)` and every UTC day in the requested range is on disk, the source reads from the bar store directly. If any day is missing, it falls back to tick aggregation (your `~/.qkt/data/symbols/…/{date}.csv` tick files). The two stores coexist safely — no migration needed.

The engine is tick-driven (backtest and live differ only in feed and clock), so each fetched bar is **replayed as four synthetic ticks** in Open → Low → High → Close order. Those four re-aggregate to exactly the stored bar — first=open, max=high, min=low, last=close, with the bar's volume on the close tick — so indicators and candle-close rules see the identical OHLC they would from real candles. This is what lets **bars-only venues backtest at all**: a crypto symbol like `BYBIT_SPOT:BTCUSDT`, where there are no recorded ticks to replay, drives the same single pipeline off its bars. When real ticks *are* present for a symbol, they are preferred and the bar synthesis is not used.

**Intra-bar fidelity caveat.** The true order in which a bar's high and low were hit is unknowable from OHLC alone, so the replay always visits the **low before the high**. This is the pessimistic choice: for a long with a protective stop, the adverse extreme is reached first, so a stop is filled at the low rather than skipped — a bar-based backtest will not *overstate* a risk-managed strategy. Fills that depend on the exact within-bar path (e.g. a stop and a take-profit both inside one bar) are therefore an approximation, not tick-accurate.

## Limits and gotchas

- **MT5 historical APIs are broker-dependent.** Some brokers throttle aggressively or only serve a limited window (e.g. 90 days). On rate-limit / data-unavailable, `qkt fetch` stops and prints the error; re-run after the broker recovers.
- **`qkt fetch` is bar-level only.** Ticks still come from the existing Dukascopy / script-fetcher path (`qkt backtest --fetcher dukascopy --fetcher-script …`).
- **One timeframe at a time.** Want 1m AND 5m? Run `qkt fetch` twice. The store keys by timeframe so they don't collide.
- **Idempotent skip is per-day-file.** If a day file exists, that day is skipped without consulting the broker. To re-fetch (e.g., after fixing a corrupt file), delete the day file and re-run.

## Related

- [Phase 25B — live auto-warmup](../phases/phase-25b-live-auto-warmup.md) — same broker historical APIs power live engine warmup; you usually don't need to pre-fetch for live deploys.
- [Backtest model](../concepts/backtest-model.md) — what the engine guarantees and doesn't.
- [Broker integration](../concepts/broker-integration.md) — how broker profiles are resolved.
