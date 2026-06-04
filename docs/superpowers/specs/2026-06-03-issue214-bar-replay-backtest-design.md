# Backtest replay from fetched OHLC bars (#214)

Status: design approved
Issue: #214 (P2, bug, effort: advanced) — bars-only venues (Bybit/crypto) have no backtest path.

## Problem

`qkt fetch BROKER:SYMBOL` writes OHLC bars to `LocalBarStore`, but the backtest replay feed is
built only from `source.ticks()` (`backtest/Backtest.kt:145`), reading a separate tick store.
Fetched bars are consumed only by `IndicatorWarmer`, never by the replay. So `qkt fetch` +
`qkt backtest` on a bars-only venue yields a silent **0-trade** backtest. Bybit's public API
exposes only klines (bars), so crypto has **no backtest path at all**. Two concrete bugs:
- `Backtest.fromSource` hard-requires the `TICKS` capability (`Backtest.kt:138`).
- `BacktestCommand` builds the request from `ast.streams.map { it.symbol }` (bare symbol), dropping
  the broker prefix that `LocalMarketSource.bars()` needs to split `broker:symbol`.

## Goals

- `qkt fetch BYBIT_SPOT:BTCUSDT --tf 5m` then `qkt backtest` produces trades from the fetched bars.
- The fix preserves the backtest=live determinism invariant.
- Real ticks are still used when available (MT5 venues with tick CSVs) — bar synthesis is the
  fallback for bars-only data.
- `IndicatorWarmer` uses the same OHLC synthesis (fixing a pre-existing degenerate-warmup bug and
  keeping warmup consistent with replay).
- Correct `docs/how-to/fetch-data.md`.

## Non-goals

- A second, bar-driven engine input path. (Rejected — see Architecture.)
- Perfect intra-bar path reconstruction (impossible from OHLC; see the fidelity caveat).
- Changing how live trading consumes data.

## Architecture: synthesize ticks from OHLC bars

The engine is irreducibly **tick-driven**: both backtest and live push ticks into one shared
`TradingPipeline.ingest(tick)` (`app/TradingPipeline.kt:261`), and candles are synthesized
*downstream* by `CandleHub`/`CandleAggregator` from those ticks. The pipeline doc states the
invariant: *"the only difference between backtest and live is the tick feed and the clock."* A
bar-driven replay would need the engine to consume candles directly — a second input path that
**forks the pipeline and breaks the invariant**. So the only invariant-preserving fix is to
**synthesize a tick feed from the OHLC bars** and drive the same single pipeline. `IndicatorWarmer`
already does exactly this (reads `source.bars()`, emits a synthetic close-tick) — this generalizes
that pattern into the replay feed.

### `BarTickFeed` — OHLC → ticks

A `TickFeed` (`next(): Tick?` / `close()`) built from a `Sequence<Candle>` (`source.bars(symbol,
window, range)`). For each candle it yields **four** synthetic ticks at distinct, strictly
increasing sub-bar timestamps inside `[startTime, endTime)`:

| order | price | timestamp | volume |
|-------|-------|-----------|--------|
| 1 | `open`  | `startTime`        | 0 |
| 2 | `low`   | `startTime + Δ`    | 0 |
| 3 | `high`  | `startTime + 2Δ`   | 0 |
| 4 | `close` | `endTime - 1`      | `candle.volume` |

where `Δ = (endTime - startTime) / 4`. Two properties fall out:

- **Indicators stay correct.** `CandleAggregator` rebuilds a candle from these ticks as
  (first, max, min, last) = (open, high, low, close) — *exactly the original bar*. Volume on the
  close tick makes the aggregated volume sum to `candle.volume`.
- **Stops/brackets become realistic.** The low and high ticks let intra-bar stop-loss /
  take-profit / bracket fills trigger, instead of only at the close.

It stays the single tick pipeline (everything evaluates on ticks uniformly), so backtest=live holds
and it's fully deterministic given the fixed convention.

The candle→ticks mapping is a single shared function `candleToTicks(candle): List<Tick>` (the four
O→L→H→C ticks above), used by **both** `BarTickFeed` and `IndicatorWarmer` — one definition of the
convention, no fourth near-duplicate walker.

### Warmup uses the same synthesis (fixes a pre-existing bug)

`IndicatorWarmer` currently emits **one** close-tick per warmup bar
(`IndicatorWarmer.kt:70-76`). That re-aggregates to a degenerate candle with `O=H=L=C=close`, so
range-based indicators (`atr`, anything using `high - low`) warm up on **zero-range** candles, then
jump to correct ranges the moment replay begins — a latent correctness bug independent of crypto.
Replace warmup's single close-tick with the same `candleToTicks(candle)` (four ticks via
`pipeline.ingestForWarmup`), so warmup candles re-aggregate to the real OHLC and warmup is consistent
with replay. The look-ahead guard (each synthetic tick's timestamp must be `< now`) still applies to
all four ticks.

### Fidelity caveat (documented, deliberate)

The intra-bar **High/Low order** is unknowable from OHLC alone. We use the **pessimistic
convention `O → L → H → C`**: the adverse extreme (low, for a long) is hit before the favorable one,
so the backtest will not *overstate* a risk-managed strategy's performance. This is the standard
conservative OHLC-fill choice; it is an explicit assumption, documented in `fetch-data.md` and a
KDoc on `BarTickFeed`. (A close-only synthesis was rejected: it silently overstates stop-strategy
performance by never triggering intra-bar stops — a footgun for a system heading to real money.)

### Feed selection — prefer real ticks, fall back to bars

The backtest replay builds its feed **per symbol**:
- if the source supports `TICKS` and tick data is present for the range → `SequenceTickFeed(source.ticks(...))` (today's path, e.g. MT5);
- else if it supports `BARS` and bar data is present → `BarTickFeed(source.bars(symbol, window, range))`.

So MT5 (real ticks) is unchanged; crypto (bars-only) gets the synthesized feed. `Backtest.fromSource`
relaxes its hard `TICKS`-only `require` to "`TICKS` or `BARS`". Multi-symbol backtests merge
per-symbol feeds with the existing `MergingTickFeed` (by timestamp), regardless of which kind each
symbol resolved to. The bar `window` (timeframe) comes from the strategy's stream declaration.

### Symbol-prefix fix

`BacktestCommand` passes the **prefixed `qktSymbol`** (`StreamDecl.qktSymbol` = `"$broker:$symbol"`)
into the `MarketRequest`, not the bare `it.symbol`, so both `source.bars()` and `source.ticks()` can
split `broker:symbol` and resolve the venue/store. (`--symbols` override validation compares against
the same prefixed list.)

## Data flow

`qkt fetch BYBIT_SPOT:BTCUSDT --tf 5m` → bars in `LocalBarStore` →
`qkt backtest` → `BacktestCommand` builds a `MarketRequest` of prefixed `qktSymbol`s →
`Backtest.fromSource` selects, per symbol, the real-tick feed or `BarTickFeed` →
`BarTickFeed` reads `source.bars()` and yields O→L→H→C ticks →
`pipeline.ingest(tick)` (the same pipeline live uses) → `CandleHub` rebuilds the bars for
indicators, stops evaluate against the intra-bar low/high → trades.

## Testing

- `BarTickFeed` unit: one candle → four ticks in O,L,H,C order with the right prices/timestamps;
  the four re-aggregate (via `CandleAggregator`) to the original OHLC candle; volume preserved;
  timestamps strictly increasing and within `[startTime, endTime)`; multi-candle ordering.
- Feed selection: a source with only bars → `BarTickFeed`; a source with ticks → unchanged tick feed.
- `BacktestCommand` symbol fix: the request carries `BYBIT_SPOT:BTCUSDT`, not `BTCUSDT`.
- **End-to-end regression (the issue's repro):** seed a `LocalBarStore` with bars for
  `BYBIT_SPOT:BTCUSDT`, run a backtest of a strategy with an always-true close rule
  (`WHEN btc.close > 0 THEN BUY btc`), assert **> 0 trades**. Plus a stop-strategy test asserting an
  intra-bar stop fills at the low (proving the OHLC synthesis, not just close).
- Warmup: a range-based indicator (`atr`) warmed from bars gets a **non-zero** range (proving the
  OHLC warmup, vs the degenerate zero-range close-only synthesis it replaces).
- Determinism: the same bar store backtested twice yields identical trades.

## Backtest invariant / safety

The synthesized ticks drive the same `TradingPipeline` live uses; no engine or live-path change.
Synthesis is a pure function of the stored bars + the fixed O→L→H→C convention (no clock/random), so
a backtest is bit-identical across runs. The pessimistic order is a documented approximation, not a
determinism hole.

## References

- Issue #214 (found during #73 crypto E2E). Backlog: Tier 6 (asset-class expansion).
- Replay: `backtest/Backtest.kt:126-162` (`fromSource`, the `TICKS` require at :138, `source.ticks()` at :145).
- Engine tick-drive: `engine/Engine.kt:onTick`, `research/ReplayEngine.kt:203-218`, `app/TradingPipeline.kt:261` (`ingest`).
- Candle synthesis: `candles/CandleAggregator.kt:30-42`, `dsl/compile/CandleHub.kt:82-87`.
- Prior art (bars→synthetic tick): `app/IndicatorWarmer.kt:50-79`.
- Bar store / source: `marketdata/source/LocalMarketSource.kt:62-91` (`bars()` needs `broker:symbol`), `marketdata/store/LocalBarStore.kt`.
- Symbol bug: `cli/BacktestCommand.kt:53-67` (`ast.streams.map { it.symbol }`); fix uses `StreamDecl.qktSymbol`.
- Capabilities: `MarketSourceCapability` (`TICKS`, `BARS`, `LIVE_TICKS`); `TickFeed`/`SequenceTickFeed`/`MergingTickFeed`.
