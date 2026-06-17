# Sweep single-pass fan-out — Design

Date: 2026-06-17
Status: approved, pre-implementation
Scope of this spec: make `qkt sweep` decode and replay the shared market stream once per worker instead of once per grid combo, by fanning each tick out to N isolated per-combo engines. A measurement spike gates the build. No change to the live path, the fill model, or per-combo computation — backtest=live parity is preserved by construction.

---

## 1. Purpose

A grid sweep runs the *same* strategy over the *same* market data with *different parameters*. The market data is identical across every combo; only the strategy's reaction differs. Today the engine does not exploit that: it decodes and replays the full tick stream from scratch once per combo. For a 16-combo grid that is 16 independent decodes of byte-identical data.

This design removes the redundancy: decode and replay the market stream once, and drive N parameterized engines from that single pass. The win shows up directly in G2 (grid) and, because walk-forward folds (G4) and significance slices (G5) are each composed of `qkt sweep` calls, it compounds there too.

Non-goals: de-duplicating decode *across* folds/slices (overlapping date ranges), the single out-of-sample `backtest` calls inside G4, and any change to the live trading path or the fill model. These are out of scope (§8).

---

## 2. The problem

Observed on the research box (bot2), 2026-06-17: a single G2 sweep took **7h32m** wall-clock (strategy s#31, event log 09:34→17:06); a full G4 walk-forward took **3h24m**. The funnel flows correctly — candidates reach G3/G4 and are rejected on merit — but the grid and walk-forward *stations* are slow, and that slowness is largely wasted re-work.

Root cause, traced through the code:

```
SweepCommand                                  (cli/SweepCommand.kt)
  → ctx.provision()                           once — ensures day-files exist on disk; NO decode
  → BacktestSweep(configs, factory, par).run() (backtest/sweep/BacktestSweep.kt)
       → factory(combo) = ctx.backtest(combo)  FRESH per combo, ×N   (cli/BacktestContext.kt:58)
            → Backtest.fromStore → fromSource   builds one replayFeed per basket symbol
                 → LocalMarketSource.ticks(sym, range)   (marketdata/source/LocalMarketSource.kt:38)
```

`LocalMarketSource.ticks()` is a bare `sequence { … }` with no cache (LocalMarketSource.kt:51): every iteration re-walks the day range, re-opens every day-file, and re-decodes it via `openDayFeed()`. Each combo's `Backtest.run()` re-iterates this from scratch.

The on-disk format is uncompressed columnar scaled-int64 (`BinaryTickFormat`, `qkt-tick-bin-v1`). Decoding reconstructs a `BigDecimal` per value per tick and allocates a `Tick` object. After the first combo, the OS page cache holds the raw bytes, so the repeated cost across combos 2..N is **CPU — re-parsing int64→BigDecimal→Tick and re-aggregating ticks→bars — not disk I/O**. Tick decode is the dominant per-backtest cost (the same property that made the binary store a ~2.7× decode win and made cross-strategy parallelism the headline throughput lever).

Cost multipliers seen in the live grids: baskets carry up to 4 symbols (s#31: NZDUSD+AUDUSD+XAGUSD+XAUUSD, gold and silver are the heaviest streams), and fine timeframes blow up tick counts (s#42: 1m/5m on EURUSD+gold). XAUUSD alone is ~11.9 GB across 1789 day-files; busy days are ~200–300k ticks.

---

## 3. Why decode-once fan-out (and not the alternatives)

The redundant work per combo is **decode + tick→bar aggregation + market-state updates** — all param-independent. The fix is to do that shared work once and fan it out to N parameterized engines, while *streaming* (never materializing the decoded ticks).

Two alternatives were rejected:

- **Shared immutable decoded-tick cache.** Decode once into an in-memory structure that all combos iterate. Conceptually simple, but a decoded `Tick` is a heavy object (3–6 `BigDecimal`s); a 2-year, 4-symbol basket materializes to tens of GB and OOMs the box. Only safe for short/single-symbol ranges, so not a general fix.
- **In-memory columnar reuse.** Hold each day-file's `long[]` columns in memory once and rebuild `Tick`s per combo. Saves file-open/read/header-parse but still pays the `BigDecimal`+`Tick` allocation per combo — which is the dominant cost — so the smallest change yields the smallest win.

Fan-out is the only option that is both memory-bounded (nothing materialized) and decode-optimal (decode collapses to once per worker), and it is the correct primitive: it expresses "one market stream, many parameterizations" directly.

---

## 4. Architecture

`Backtest.run()` delegates to `com.qkt.research.ReplayEngine(...).runToEnd()`. `ReplayEngine` is documented as *"the shared replay core … pacing only decides when we stop pulling ticks, never the tick→ingest order"* and already exposes `advanceUntil(stop)` / `advanceToEnd()`, pulling one tick at a time (`feed.next()`, ReplayEngine.kt:258). The fan-out is therefore an extraction, not a rewrite.

State in `ReplayEngine` splits cleanly:

- **Param-independent (shareable):** the feed/decode, candle aggregation (`candleHub`), `MarketPriceTracker`, clock, calendar.
- **Param-dependent (isolated, one per combo):** the `Strategy` instance, broker/fills, `PositionTracker`, P&L, `StrategyPnL`, risk/halts, trade tape, equity collector.

```
                         ┌──────────────────────────────────────────────┐
 one shared decoded      │ SweepReplay (per worker)                       │
 merged feed  ──tick──▶  │   pull tick once ──▶ ingest(tick) on every     │
 (LocalMarketSource,     │                      engine in fixed order     │
  MergingTickFeed)       │      ┌─────────────┐ ┌─────────────┐ ...        │
                         │      │ ReplayEngine│ │ ReplayEngine│            │
                         │      │  combo 1    │ │  combo 2    │            │
                         │      │ (isolated   │ │ (isolated   │            │
                         │      │  broker/pnl)│ │  broker/pnl)│            │
                         │      └─────────────┘ └─────────────┘            │
                         │   at end: result() per combo ──▶ SweepResult    │
                         └──────────────────────────────────────────────┘
```

Components:

1. **`ReplayEngine.ingest(tick)`** — extract the per-tick body out of `advanceUntil`'s loop into a public method; the loop becomes "pull tick, call `ingest`." This is a pure refactor with no behavior change (the parity test in §7 proves it). `runToEnd()` / `advanceUntil` keep working unchanged for the existing batch and research paths.
2. **`SweepReplay` driver** — owns one shared decoded merged feed; constructs N `ReplayEngine`s, one per combo, each with its own isolated execution state; pulls each tick once and calls `ingest` on all N in a fixed combo order (deterministic); on feed end, collects each engine's result into a `SweepResult`.
3. **`SweepCommand` rewire** — call `SweepReplay` in place of `BacktestSweep`. `BacktestContext.build`/`provision` are unchanged; ranking, JSON, and table output are unchanged.

**Sharing depth is decided by the spike (§5), and the architecture supports either.** The baseline shares the decode (the confirmed-heavy part): each `ReplayEngine` keeps its own `candleHub`, so the raw tick is fanned out and each combo aggregates its own bars. If the spike shows candle aggregation is also significant, the deeper variant hoists `candleHub` and `MarketPriceTracker` to the driver and fans out *bar-close events* alongside ticks. Baseline first; deeper only if the numbers justify it.

---

## 5. Step 0 — the spike (gates the build)

Before building, measure. Instrument one representative backtest (e.g. s#31's 4-symbol basket over its train range) to attribute wall-clock to:

- (a) feed decode (`LocalMarketSource.ticks` / `openDayFeed`),
- (b) candle aggregation (`candleHub`),
- (c) per-combo execution (strategy eval + broker + P&L + risk).

The shareable fraction (a)+(b) sets the ceiling on the win: a single sweep goes from `N×decode` to `~1×decode`, leaving the irreducible `N×execution`. Decision rule:

- If (a)+(b) is a large share (build is clearly worth it) → proceed with Approach A; pick baseline vs deeper sharing from whether (b) is material.
- If (a)+(b) is small → **stop and report**. The expected win would be modest (≈1.2×) and not worth the refactor; the throughput levers stay (cross-strategy parallelism, binary store, the already-applied session-budget/cores config).

The spike is throwaway instrumentation; it does not ship.

---

## 6. Concurrency and memory

Partition the N combos across `parallelism` workers (one thread each). Each worker does **one** shared decode pass over its combo subset, so decode passes across the sweep = `parallelism` (e.g. 3), not N (16). Engine work is unchanged (N× total, spread across the worker threads); the saving is the decode passes the fan-out eliminates, parallelized.

Memory per worker = the current tick + its subset of isolated engine states (orders, P&L — small). Nothing is materialized, so peak memory is bounded and far below today's transient per-combo decode buffers.

qkt-forge's scheduler accounting is unaffected: a sweep still occupies ≈`parallelism` cores, so `core_cost`/`sweep_parallelism` keep their meaning. The forge side calls `qkt sweep --parallelism N --json` exactly as before — no qkt-forge change.

---

## 7. Parity and testing

The change is safe only if it is bit-identical to the current path. This is the acceptance gate.

- **Bit-identical regression test.** For a representative strategy + grid + cached data, assert the `SweepReplay` result **exactly equals** the current per-combo `ctx.backtest(combo).run()` for every combo — every reported metric, and the full trade tape where the harness exposes it. `ReplayEngine`'s own contract already guarantees bit-identical results regardless of pacing, so this verifies an invariant the codebase already holds rather than introducing a new one.
- **Determinism.** Engines are advanced in a fixed combo order per tick; each combo's clock/RNG is seeded exactly as today. Order of results matches the input combo order before ranking.
- **Existing suite.** The current sweep tests (`BacktestSweepParallelTest`, `BacktestSweepSequentialTest`, `BacktestSweepFailFastTest`, `BacktestSweepValidationTest`, `SweepCommandTest`) must stay green; behaviors they pin (fail-fast, validation, JSON shape) are preserved.

---

## 8. Scope and non-goals

In scope:

- The spike (§5).
- `ReplayEngine.ingest(tick)` extraction.
- `SweepReplay` driver.
- `SweepCommand` rewire to `SweepReplay`.
- Bit-identical parity test + keeping the existing sweep suite green.

Out of scope (deferred or excluded):

- Cross-fold / cross-slice decode de-duplication for overlapping date ranges (a larger, separate change).
- The single out-of-sample `backtest` calls inside G4 (not sweeps — no fan-out benefit).
- Any change to the live trading path, `liveTicks`, the fill model, or per-combo computation.
- Any qkt-forge change (it keeps calling `qkt sweep --parallelism N --json`).

---

## 9. Risks and mitigations

- **`ingest` extraction perturbs tick→ingest order.** `ReplayEngine` bundles shared and isolated state; the extraction must preserve the exact processing order. Mitigation: the §7 bit-identical test fails on any drift; the extraction is a mechanical move of an existing block.
- **Per-combo exception semantics.** Today one combo throwing aborts the whole sweep (`BacktestSweep.runParallel` rethrows). The forge expects a JSON array for all combos and treats a sweep error as the gate's error. Mitigation: **match current fail-fast behavior** in `SweepReplay`; do not silently isolate failures (that would change gate semantics). Noted, not changed.
- **Spike shows a small win.** Mitigation: the spike is the gate — if the shareable fraction is small, stop and report rather than build.

---

## 10. References

- Root cause and box measurements: memory `project_bot2_cache_repair_2026_06_16` (G2/G4/G5 re-decode section).
- Code: `cli/SweepCommand.kt`, `cli/BacktestContext.kt`, `backtest/sweep/BacktestSweep.kt`, `backtest/Backtest.kt`, `research/ReplayEngine.kt`, `marketdata/source/LocalMarketSource.kt`, `marketdata/BinaryTickFormat.kt`.
- Decode economics: memory `reference_backtest_decode_perf`.
