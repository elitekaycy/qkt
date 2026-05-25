# Phase 25B — Live auto-warmup on deploy

## Summary

Closes the live-side gap left by Phase 24's `WARMUP N BARS`: deploying a DSL strategy with `EMA(close, 50)` no longer makes the operator wait for 50 live closed candles before the first signal can fire. On every `qkt deploy` (cold-boot or hot-add against a running engine), the engine now:

- Derives per-stream bars-needed from `WARMUP N BARS` + indicator periods, taking the max per stream.
- Fetches that history from the broker's historical bar API (MT5 + Bybit).
- Seeds the shared `CandleHub` so lookback (`btc.close[N]`) works from tick zero.
- Replays the same bars through the existing `IndicatorWarmer` so indicators warm.
- Credits Phase 24's `WarmupGate` to match — rules fire on the very next live closed candle.

Backtest is untouched; it already gets warmup implicitly via sequential candle replay.

## What's new

### DSL `CompiledStrategy` now implements `PerStreamWarmable`

```qkt
SYMBOLS
    gold = EXNESS:XAUUSD EVERY 5m WARMUP 50 BARS,
    spx  = BACKTEST:SPX500 EVERY 1h
RULES
    WHEN ema(spx.close, 24) > gold.close THEN ...
```

On deploy, the engine fetches:
- 50 bars of 5m XAUUSD from EXNESS (max of explicit 50 and no implicit need).
- 24 bars of 1h SPX500 from BACKTEST (implicit from `ema(..., 24)`).

Different timeframes on different streams now warm independently — the legacy single-spec `Warmable` collapsed everything to one window, which over- or under-fetched at least one stream in multi-stream strategies.

### `WarmupRequirements.compute(ast)`

Inspects the parsed AST and returns per-alias bars-needed: max of explicit `WARMUP N BARS` and indicator periods walked from rule conditions and `LET` expressions. Nested indicators report the outer period only; set explicit `WARMUP N BARS` to override when the true warmup exceeds it.

### `CandleHub.seed(key, candles)`

Prepend-only bulk load — places historical candles older than the existing oldest in the ring without firing `onClosed` callbacks. Safe to call before strategies bind their callbacks, and safe in hot-add: existing strategies on the daemon are never disturbed because the seed only prepends.

### `WarmupGate.recordBars(alias, count)`

Pre-credits the Phase 24 gate so it doesn't sit cold after the hub has already been seeded. Called from `CompiledStrategy.bindToHub` using `hub.historySize(key)`.

### `IndicatorWarmer.warmup(perStream, now)`

Per-stream form of the existing single-spec method. Each symbol gets its own `WarmupSpec`. Legacy single-spec callers continue to work via a thin wrapper.

### `WarmupFailedException`

Thrown when the broker historical API errors during deploy. Operator sees:

```
qkt: failed to fetch warmup history for stream 'gold' (EXNESS:XAUUSD) — broker historical API returned: rate limit exceeded. Deploy aborted. Retry after fixing the broker connection, or remove WARMUP / reduce indicator periods to deploy without prefetch.
```

Deploy aborts before any rule fires. Half-warm strategies are worse than not-deployed strategies.

## Migration from Phase 24

Pure addition for DSL strategies — every existing strategy still parses, and behavior strictly improves: `WARMUP 50 BARS` that previously meant "wait 50 live bars" now means "prefetch 50 bars and fire on the first live bar." Indicator-derived bars-needed is also new — a strategy with `EMA(close, 200)` but no explicit `WARMUP` now also gets 200 bars pre-fetched. This was previously a silent bug surface (rule never fired because indicator stayed cold).

The legacy single-spec `Warmable` interface still works for non-DSL strategies — `LiveSession.start()` falls back to it when `PerStreamWarmable` isn't implemented.

## File map

| Concern | Files |
| --- | --- |
| Interface | `src/main/kotlin/com/qkt/strategy/PerStreamWarmable.kt` |
| Requirements derivation | `src/main/kotlin/com/qkt/dsl/compile/WarmupRequirements.kt` |
| Hub seed API | `src/main/kotlin/com/qkt/dsl/compile/CandleHub.kt` (`seed`) |
| Gate seed API | `src/main/kotlin/com/qkt/dsl/compile/WarmupGate.kt` (`recordBars`) |
| Per-stream warmer | `src/main/kotlin/com/qkt/app/IndicatorWarmer.kt` |
| Deploy-time wiring | `src/main/kotlin/com/qkt/app/LiveSession.kt` (`seedHubFromHistory` + per-stream branch) |
| Error type | `src/main/kotlin/com/qkt/app/WarmupFailedException.kt` |
| Strategy integration | `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt` (`CompiledStrategy : PerStreamWarmable`) |

## Common gotchas

- **Broker rate limits.** A heavy strategy spanning many streams + deep indicators issues many `bars(...)` calls at deploy. If the broker rate-limits, deploy aborts with `WarmupFailedException`. Workaround: stagger deploys, or use a broker with higher historical-API quotas.
- **Nested indicators undershoot warmup.** `EMA(EMA(close, 9), 21)` reports only 21. The true warmup is closer to 29 (inner warms first, outer needs 21 inner-warm samples). Set explicit `WARMUP 30 BARS` if you use this pattern.
- **Lookback indices (`btc.close[20]`) not yet derived.** Set explicit `WARMUP 21 BARS` to cover them. Will be added as a follow-up.
- **Hot-add doesn't backfill `WarmupGate` for existing strategies.** Phase 25B is forward-only: when strategy B deploys, only B gets warmed. Strategy A (already running) is not disturbed.
