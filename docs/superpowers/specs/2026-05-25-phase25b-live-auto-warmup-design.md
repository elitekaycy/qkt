# Phase 25B — Live engine auto-warmup on deploy

**Date:** 2026-05-25
**Issue:** [#125](https://github.com/elitekaycy/qkt/issues/125)
**Umbrella:** [#53](https://github.com/elitekaycy/qkt/issues/53) Phase 25 — operator tooling

## Goal

When an operator deploys a strategy live (cold-boot or `qkt deploy` on a running engine), the strategy's indicators, snapshot history, and Phase 24 `WarmupGate` should be pre-seeded from the broker's historical bar API. Rules fire on the very next live closed candle — no operator wait, no operator `qkt fetch` step beforehand.

Backtest is intentionally out of scope: it already consumes historical candles sequentially, so warmup is implicit in the playback. This phase changes only the live path.

## Motivation

Phase 24 shipped `WARMUP N BARS` and the `WarmupGate`, but the gate is fed by live closed candles only. A fresh deploy of `EMA(close, 50)` at 5m timeframe blocks for ~4 hours before any rule fires. At 1h timeframe, two days. This is unacceptable for production rotations and for hot-add of new strategies onto a running daemon.

The codebase has most of the building blocks already — `IndicatorWarmer`, `WarmupSpec`, the per-broker historical APIs (`Mt5BarFetcher`, `BybitKlineClient`), and `pipeline.ingestForWarmup()`. The DSL `CompiledStrategy` simply doesn't implement `Warmable`, so the existing warmer skips it entirely. Three other gaps stack on top of that.

## Scope

### In scope

- DSL `CompiledStrategy` implements `Warmable`, computing its warmup requirement from explicit `WARMUP N BARS` plus implicit lookback (indicator periods, `btc.close[N]` references, rolling LET `MAX_N`).
- `IndicatorWarmer` extended to accept per-stream warmup specs (different timeframes per stream).
- Warmup ticks feed Phase 24's `WarmupGate` so it's "warm" before the first live candle.
- Warmup candles seed `CandleHub` history so lookback (`btc.close[N]`) works from tick zero.
- Both MT5 and Bybit broker historical APIs work.
- Deploy fails fast with a clear error when the broker historical fetch errors (rate limit, auth, missing data).
- Cold-boot, hot-add, and retention-growth scenarios all work without disturbing already-running strategies.

### Out of scope (deferred)

- Phase 25A — `qkt fetch` CLI for offline backfill ([#124](https://github.com/elitekaycy/qkt/issues/124)).
- Phase 25C — `TRAILING_STOP` wiring ([#126](https://github.com/elitekaycy/qkt/issues/126)).
- Phase 25D — per-strategy risk caps ([#127](https://github.com/elitekaycy/qkt/issues/127)).
- Phase 25E — VWAP / `TICK_SERIES` ([#128](https://github.com/elitekaycy/qkt/issues/128)).
- Backtest path (works correctly today via sequential candle replay).
- `WarmupSpec.Ticks` semantics — `IndicatorWarmer` already logs a warning and falls back to 1-minute bars; we leave that as-is.
- Cross-strategy de-duplication of historical fetches (if two strategies deploy simultaneously and both want the same 50 bars, we fetch twice). Optimization, not correctness.

## Current state (where the gaps are)

The Explore agent's map of the existing code, condensed:

- `src/main/kotlin/com/qkt/strategy/WarmupSpec.kt` — `sealed class WarmupSpec { None, Bars(window,count), Duration, Ticks }`. Today a *single* spec covers all the warmup the warmer ever sees.
- `src/main/kotlin/com/qkt/strategy/Warmable.kt` — interface with one field, `warmup: WarmupSpec`. Strategies opt in.
- `src/main/kotlin/com/qkt/app/IndicatorWarmer.kt` — `warmup(symbols: List<String>, spec: WarmupSpec, now: Instant)`. Resolves spec to a `BarSpec(window, count)`, iterates over `symbols`, calls `source.bars(symbol, window, range)`, replays each candle as a synthetic tick via `pipeline.ingestForWarmup(tick)`.
- `src/main/kotlin/com/qkt/app/LiveSession.kt` lines 400-408 — filters strategies via `filterIsInstance<Warmable>()`, picks the largest spec, calls warmer.
- `src/main/kotlin/com/qkt/app/TradingPipeline.kt` lines 211-212 — `ingestForWarmup()` publishes `WarmupTickEvent`, which updates indicators/aggregates but suppresses rule firing.
- `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt` lines 158-340 — `CompiledStrategy` (private class). Implements `DslCompiledStrategy : Strategy`. Does NOT implement `Warmable`.
- `src/main/kotlin/com/qkt/dsl/compile/CandleHub.kt` — per-key `ArrayDeque<Candle>` history. `register(key, retention, owner)` reserves; `feed(tick)` drives aggregators; `onClosed(key, owner) { ... }` registers closed-candle callbacks. **No `seed(key, candles)` method exists.**
- `src/main/kotlin/com/qkt/dsl/compile/WarmupGate.kt` — incremented in `evaluate(alias, candle, ...)` (the hub `onClosed` callback). Warmup ticks bypass this path.
- `src/main/kotlin/com/qkt/cli/daemon/StrategyHandle.kt` lines 81-192 — `qkt deploy` creates a NEW `LiveSession` per strategy. Shared `CandleHub` across all sessions.

### Four concrete gaps

1. **`CompiledStrategy` doesn't implement `Warmable`** → DSL strategies are filtered out of warmup entirely. Today's "warmup works for DSL" claim is false.
2. **`WarmupSpec` is one-spec-per-strategy** → multi-stream strategies on different timeframes (5m gold + 1h spx) can't express per-stream warmup correctly. `IndicatorWarmer.warmup()` takes one `spec` and applies it to a list of symbols.
3. **`WarmupGate.onClosedCandle()` is only called in the live-candle path** → after warmup, gate counter is 0, rules don't fire on the first live bar.
4. **`CandleHub` history isn't seeded** → warmup ticks flow through the event bus (`WarmupTickEvent`), not the hub's aggregator. Lookback `btc.close[N]` returns null after warmup even though indicators are ready.

## Approach

### 1. Per-stream WarmupSpec

Extend `IndicatorWarmer.warmup()` to take a `Map<String, WarmupSpec>` (symbol → spec). The single-spec form becomes a thin shim that broadcasts to all symbols, keeping legacy `Warmable` callers working.

Add a new method on `Warmable` (or a sibling interface `PerStreamWarmable`) that returns `Map<String, WarmupSpec>`. DSL strategies use the new interface; legacy strategies continue with the old field. `LiveSession.start()` checks the new interface first, falls back to the old.

Rationale for keeping the legacy field: avoids breaking any non-DSL `Warmable` implementer. Lower risk for a phase that already touches a hot path.

### 2. DSL CompiledStrategy implements Warmable

In `AstCompiler.compile()`, compute per-stream bars-needed:

```
barsNeededPerAlias = mapOf( alias -> max of:
   stream.warmupBars                            // explicit WARMUP N BARS
   max indicator-period referencing the alias   // EMA(close, 50) → 50
   max snapshot-rolling lookback for the alias  // btc.close[N] → N + 1
   max LET ROLLING MAX_N for the alias          // LET x = btc.close[5..10] → 10
)
```

The compiler already has the data: indicator periods are in `IndicatorBinding` arguments, lookback indices are in `SnapshotPlan.rollingMaxN`, `warmupBars` is on `StreamDecl`. Aggregate them at compile time, emit `Map<String, WarmupSpec.Bars>` per declared stream.

Stream alias → broker symbol mapping already exists via `streams: Map<String, HubKey>` on the compiled strategy.

### 3. Feed `WarmupGate` from warmup ticks

Two clean options; I pick (a):

(a) **Route warmup candles through the gate.** `pipeline.ingestForWarmup()` for each completed warmup bar also calls `warmupGate.onClosedCandle(alias)`. The seam is whichever component in the pipeline maps tick → alias.

(b) **Have `CompiledStrategy` subscribe to `WarmupTickEvent`** and pump its own gate. More layered, but more wiring.

Option (a) keeps the gate's responsibility unified: warmup or live, the gate sees every closed bar. Lower coupling.

### 4. Seed `CandleHub` history

Add `CandleHub.seed(key, candles: List<Candle>)` that bulk-loads the ring without re-firing `onClosed` callbacks (since strategies haven't bound their callbacks yet at warmup time).

`IndicatorWarmer` calls `hub.seed(key, candles)` once per stream, *before* replaying warmup ticks. This way, by the time the strategy registers its `onClosed` callback in `bindToHub()`, the hub already has N bars of history that lookback can read.

**Order of operations in LiveSession.start():**

```
1. compile()                                   // produces CompiledStrategy
2. hub.register(key, retention, strategyId)    // per stream
3. hub.seed(key, fetched-historical-candles)   // NEW — populate history
4. warmer.warmup(perStreamSpec, now)           // replay synthetic ticks into pipeline
   - indicators warm via WarmupTickEvent
   - WarmupGate increments via the pipeline seam (gap #3 fix)
5. strategy.bindToHub(hub, ctx, emit)          // register onClosed callbacks
6. source.liveTicks(symbols).forEach { ... }   // live engine loop
```

### 5. Multi-broker (MT5 + Bybit)

`IndicatorWarmer` already calls `source.bars(symbol, window, range)` — `source` is the `MarketSource` abstraction that already dispatches to the right broker via the symbol prefix. Confirmed in the explore: both `Mt5BarFetcher` and `BybitKlineClient` implement the historical-fetch path with the same signature shape (`fetchRange(symbol, window, range): Sequence<Candle>`).

Per-broker tests verify both paths separately. The plumbing is shared.

### 6. Fail-fast on broker fetch failure

`IndicatorWarmer` already throws on `source.bars` failure (no try-catch around line 50). LiveSession.start() propagates the exception, deploy fails. Today the error message is unhelpful ("connection refused", etc.).

Wrap the call site to throw a typed `WarmupFailedException(stream, broker, cause)` with a clear message:

> qkt: failed to fetch warmup history for stream 'gold' (EXNESS:XAUUSD) — broker historical API returned: <cause>. Deploy aborted. Retry after fixing the broker connection, or remove `WARMUP` / reduce indicator periods to deploy without prefetch.

Operator gets a pointed error pointing at the actual problem.

### 7. Hot-add correctness

Per the explore: each `qkt deploy` creates its own `LiveSession` → its own `TradingPipeline` → its own `IndicatorWarmer.warmup()` call. Shared `CandleHub` across sessions, but warmup ticks flow via the event bus inside the new session's pipeline (isolated from other strategies' pipelines).

Two correctness traps:

**Trap A: retention growth.** New strategy needs `btc.close[200]` but existing retention is 50. `CandleHub.register(key, retention=200, owner=newStrategy)` takes the max, growing retention. But the hub doesn't have 200 historical bars — only the 50 it accumulated since startup. The new strategy's `IndicatorWarmer` must fetch the full 200 from the broker (not just the missing 150) and feed via `hub.seed` — but `hub.seed` would clobber the 50 existing bars and might disturb existing strategies that already read from them.

Resolution: `hub.seed(key, candles)` is **prepend-only** — it inserts older candles before the oldest existing bar, preserving the existing ring. If the existing ring already covers part of the requested range, only the older slice is prepended.

**Trap B: in-flight live candle during warmup.** While `IndicatorWarmer` is fetching, a live candle closes on the shared hub. Existing strategies see it (their `onClosed` callbacks fire). The new strategy hasn't called `bindToHub` yet, so it misses the event — which is correct; its `bindToHub` is sequenced *after* warmup.

The fix is the order-of-operations in §4: `hub.seed` first (atomic — strategies bind after), then warmup, then `bindToHub`. The existing strategies are never disturbed.

## Validation rules

- A strategy declaring `WARMUP 50 BARS` and using `EMA(close, 200)` should be warmed with 200 bars (max wins).
- A strategy declaring no WARMUP and no indicators needs no warmup → `WarmupSpec.None`, instant deploy.
- A strategy that the broker can't satisfy (range exceeds broker history, auth fails, rate-limited) → deploy fails before any rule fires.
- A strategy with two streams on different timeframes (`g = EXNESS:XAUUSD EVERY 5m WARMUP 50 BARS, s = BACKTEST:SPX500 EVERY 1h WARMUP 24 BARS`) warms each correctly (50 5m bars for gold, 24 1h bars for spx).
- A strategy with no `WARMUP` declaration but heavy indicators (`EMA(close, 200)`) still warms to 200 bars (implicit requirement).

## Testing strategy

### Unit tests

- `WarmupRequirementsTest` — given a parsed StrategyAst, compute per-stream bars-needed (max across WARMUP, indicator periods, lookback indices, ROLLING LET).
- `CandleHubSeedTest` — `seed(key, candles)` correctly prepends, deduplicates by `startTime`, preserves existing newer candles, doesn't fire `onClosed`.
- `WarmupGateFromWarmupTest` — warmup ticks increment the gate; rules fire on the first *live* candle after warmup.
- `PerStreamWarmupSpecTest` — `IndicatorWarmer.warmup(perStreamSpec)` fetches different windows for different streams.

### Integration tests

- `LiveAutoWarmupColdBootTest` — boot engine with a strategy using `EMA(close, 50)` + a fake `MarketSource` returning 50 known bars. Verify: indicator is ready, gate is warm, hub has 50 bars, first live tick fires the rule.
- `LiveAutoWarmupHotAddTest` — boot engine with strategy A (no warmup), let some live bars flow, then "deploy" strategy B with `WARMUP 100 BARS`. Verify: A's indicator state unchanged, B's indicators warmed, hub retention grew without disturbing A.
- `LiveAutoWarmupBrokerFailureTest` — fake source throws on `bars(...)`. Verify: deploy fails with `WarmupFailedException`, no half-warmed state left behind, existing strategies on the daemon unaffected.

### Manual smoke (operator)

- Deploy a real EMA-crossover strategy against EXNESS:XAUUSD with `WARMUP 50 BARS`. Verify in logs: warmup window fetched, first live signal fires within minutes (not hours).
- Same against Bybit (BACKTEST:BTCUSDT is fine for fixture, BYBIT_SPOT for live confirmation).

## Docs targets

- `docs/phases/phase-25b-live-auto-warmup.md` — phase changelog (created at task end).
- `docs/reference/dsl/streams.md` — note that `WARMUP N BARS` and implicit indicator requirements now trigger automatic prefetch.
- `docs/operations/` — operator note: what to do when deploy fails with `WarmupFailedException` (broker auth, rate limits, history gaps).
- Update `docs/phases/phase-24-risk-sizing-primitives.md` to remove the "Counts live closed candles only" caveat once 25B ships.

## Backwards compatibility

- **Legacy `Warmable` impls keep working.** New per-stream interface is additive; the single-spec form remains a valid fallback.
- **`IndicatorWarmer.warmup(symbols, spec, now)` keeps its signature** as the legacy entry point; new entry point `warmup(perStream, now)` added. LiveSession picks the new one when the strategy supports it.
- **`CandleHub.seed` is new** — no existing callers.
- **DSL `WARMUP N BARS` semantics change.** Before 25B: gates rule firing for the first N live candles. After 25B: triggers prefetch; rules fire on the first live candle (because the gate is satisfied by warmup). Strictly better behavior; not a breaking change for any existing strategy.
- **`WarmupSpec.Ticks`** stays as-is (warner logs warning, falls back to 1m bars).

## Risk assessment

- **Medium risk: in-flight live candle during warmup (Trap B above).** Mitigated by sequence-of-operations (seed → warm → bind). Test `LiveAutoWarmupHotAddTest` covers this.
- **Medium risk: retention growth corrupting existing rings (Trap A).** Mitigated by `hub.seed` being prepend-only and idempotent on overlapping ranges.
- **Low risk: cross-strategy fetch deduplication.** Two simultaneous deploys both fetching the same bars is wasteful but correct. Deferred optimization.
- **Low risk: `WarmupSpec.Duration` interactions with multi-stream specs.** Each stream's spec is independent, so this just works.
- **Operator risk: surprise on first deploy.** A heavy-indicator strategy that used to silently fail with 0 signals will now fail loudly with `WarmupFailedException` if the broker can't satisfy the lookback. Better failure mode but newly visible.

## References

- Issue: [#125](https://github.com/elitekaycy/qkt/issues/125)
- Umbrella: [#53](https://github.com/elitekaycy/qkt/issues/53)
- Predecessor: Phase 24 — `docs/phases/phase-24-risk-sizing-primitives.md`
- Sibling: Phase 25A `qkt fetch` — [#124](https://github.com/elitekaycy/qkt/issues/124)
- Existing code touched: `IndicatorWarmer`, `LiveSession`, `CandleHub`, `AstCompiler` (`CompiledStrategy`), `WarmupSpec`, `Warmable`.
