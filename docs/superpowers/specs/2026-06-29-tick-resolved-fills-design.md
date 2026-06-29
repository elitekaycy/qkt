# Tick-resolved fills — design spec

- **Date:** 2026-06-29
- **Status:** draft
- **Scope:** backtest replay/feed layer only. Live execution, `OrderManager`, `RiskEngine`, and broker models are untouched (see §7).

## 1. Problem

A backtest can replay history two ways today:

- **Exact ticks** — every stored tick flows through the full engine. Correct, but slow: a 3-year XAUUSD run is ~81M ticks at ~22k ticks/s ≈ 60 min, single-core. It is the parity anchor for live.
- **`--bars` research tier** — replays a prebuilt OHLC bar store (~10–30x faster) by synthesizing 4 ticks per bar (`O→L→H→C`). Fast, but the intrabar path is discarded at aggregation, so fills drift. Measured on 10 forge survivors (180-day windows, winner params, mt5-sim): median |P&L drift| 79% at 30m, 37% at 1m, with residual sign flips. Documented as "not for grading."

The drift has two layers, both rooted in one fact — a bar keeps 4 numbers for a window that held thousands of ticks:

1. **Which trades happen.** Stop/limit/touch entries and SL/TP exits fire on level crossings. A 4-point synthetic path misses or mis-orders crossings, so the strategy takes a *different set of trades* (seen as trade-count divergence, e.g. tick 136 vs bar 202).
2. **How a shared trade resolves.** When one bar straddles both a stop and a target, the outcome depends on which was hit first; `O→L→H→C` assumes an order, which is directionally biased.

Neither layer is reconstructable from a coarse bar — it is information loss, not a bug. The only exact source is the ticks.

**Key observation that makes a faster-exact engine possible:** qkt strategies evaluate *only on bar close*. Intrabar, the strategy does nothing; the sole consumer of intrabar prices is the `OrderManager` (trigger/fill resolution). So fills are the only thing that needs ticks — and a tick can only cause a fill by crossing a live order/position level. Ticks in windows with no live level in range provably cannot change the outcome.

## 2. Goal and non-goals

**Goal.** A backtest replay that is **byte-identical to the full-tick replay** but decodes and processes only the ticks that can affect a fill — driving signals from bars and resolving fills from on-demand tick slices. Exact, and faster than full ticks (approaching bar speed for strategies that are flat or away from levels much of the time).

**Non-goals.**
- Not a new approximation tier. Correctness is defined as *equal to full-tick replay*, not "close to it." If it is not byte-identical, it is a bug.
- No change to live execution, `OrderManager`, `RiskEngine`, or broker fill/slippage models.
- Not a parallelism change — this reduces work per replay; it composes with sweep-level parallelism but does not parallelize a single replay.

## 3. Core idea

Drive the replay by the bar clock. At each bar boundary, for each symbol:

1. If a fill is *possible* during this bar (predicate in §4), decode that bar's **tick slice** and feed it through the price tracker + `OrderManager` in tick order, stopping early once the symbol's live order/position set clears.
2. Otherwise, skip the ticks entirely — advance state by the bar (mark-to-market at bar close).
3. Then deliver the closed candle to the strategy (bar-close signal evaluation), which may place new orders that become live for the next bar.

Strategy logic and candle aggregation run on bars (as `--bars` does today). Only the `OrderManager` ever sees ticks, and only for fill-possible windows.

## 4. Soundness — the "fill-possible bar" predicate

A bar `B` for symbol `S` (with prebuilt extremes `B.low`, `B.high`, `B.open`) **needs tick resolution** iff there is a live order/position on `S` and any of:

- a working level (stop, limit, stop-loss, take-profit, if-touched trigger) lies within `[B.low, B.high]`; or
- the bar gaps onto/through a level (`B.open` already at/beyond a working level); or
- a **trailing stop** is live on `S` (its level depends on the intrabar path, so the bar's extreme alone is insufficient).

Otherwise `B` is skipped for `S`.

**Why this is exact, not heuristic:** the bar's `low`/`high` are the *true* intrabar extremes (computed from ticks at build time). If a level is outside `[B.low, B.high]`, no tick in the bar touched it, so no fill was possible — skipping is provably lossless. If a level is inside the range, we do not guess the order; we load the real ticks and read it. The predicate decides only *whether* to look, never *what happened*.

Trailing stops are the one case where the bar extreme is insufficient (the level moves intrabar), so any bar with a live trailing stop loads its ticks unconditionally.

## 5. Most-optimized form

Three levers, in order of impact:

**5.1 Random-access tick slices (the enabler).** The binary tick store is mmap'd, columnar, timestamp-sorted, day-partitioned. Add a time-range slice to the tick feed: binary-search the timestamp column for `[B.start, B.end)` → `[startIdx, endIdx)` in O(log n), then lazily decode only that range. This is what makes "pay decode only for active windows" real — no sequential scan of skipped ticks. Without it the whole scheme collapses to a full scan.

**5.2 Minimal per-active-tick work.** Active-window ticks go to `priceTracker.update` + `OrderManager.evaluateTriggers` only. They do **not** run `strategy.onTick` (the strategy decided on bar close) or candle aggregation (candles come from the prebuilt store). So an active tick is strictly cheaper than a full-replay tick.

**5.3 Within-bar early-out.** Process a bar's tick slice in order until the symbol's live order/position set is empty (everything that could fill has filled) or the slice ends. A flat-after-exit symbol stops mid-bar. Sequential intrabar events (entry tick, then its SL/TP tick later in the same bar) fall out naturally because the slice is processed in tick order through the unchanged `OrderManager`.

**Per-symbol independence.** The predicate is per-symbol, so a portfolio run pays tick cost only on symbols currently in a trade. Flat symbols cost bars only.

**Performance model (must be measured, not assumed):**

```
full-tick:      N · C_full
tick-resolved:  M · C_bar  +  K · C_fill
```

where `N` = all ticks, `M` = bars (≪ N), `K` = ticks in fill-possible windows (≤ N, often ≪ N), `C_fill < C_full` (no strategy/candle per tick). Speedup ≈ `(N/K) · (C_full/C_fill)`. For flat-heavy strategies (session/breakout) `N/K` is large → approaches bar speed. For always-in-market strategies (high-trade reversion) `K → N` → little gain over full ticks, but still exact. So the floor is "no slower than full ticks (minus the strategy/candle per-tick savings)," the ceiling is "near bar speed," and the result is always exact.

## 6. Correctness and parity

- **Oracle:** byte-identical to the full-tick replay. The acceptance gate is a parity test asserting identical trades, P&L, equity curve, and rejections across a strategy set (bracketed, trailing, stop-entry, multi-symbol, gap-open cases).
- **Event ordering is load-bearing.** Full-tick fires a candle close on the first tick of the next bar, then the strategy acts, then subsequent ticks resolve. The tick-resolved interleave (§3) must reproduce this causal order exactly. The exact ordering is pinned by making the parity test pass, not by prose.
- **Fail loud on data holes.** If a fill-possible bar lacks its tick slice (a hole in the tick store), the run errors and names the gap — it must not silently fall back to a bar approximation (qkt does not paper over data anomalies).

## 7. Architecture boundary (hard constraint)

The optimization lives entirely in the **replay/feed layer** (`BacktestContext`, the replay loop, the tick feed). The `OrderManager`, `RiskEngine`, broker fill/slippage models, and the live path are **unchanged** — they process whatever ticks they are fed, identically in backtest and live. The skip/seek decision must never leak into those components; if it did, it would break the backtest==live invariant. Parity chain:

```
tick-resolved  ≡  full-tick replay  ≡  (faithful model of)  live
```

Live cannot and does not skip ticks (it processes the real-time stream through the single-consumer queue); this is a replay-only optimization that inherits live-parity from the full-tick replay it equals.

## 8. CLI surface (naming — open decision)

Proposed: `qkt backtest <strategy> --tick-fills` — requires a built bar store (drives signals + the skip predicate) and the tick store (resolves fills). Distinct from `--bars` (which stays the approximate research tier). Because it is exact, a later phase may make it the default for exact runs once parity is proven across the suite. Naming to be finalized in the plan (avoid muddying `--bars`, which is documented "not for grading").

## 9. Risks and open questions

- **Event-ordering edge cases** (candle-close-vs-first-tick, same-tick order-placement-then-trigger, OCO sibling-cancel timing). Resolved by iterating against the parity oracle; flagged here as the main implementation risk.
- **Trailing-stop-heavy / always-in-market strategies** see little speedup (K→N). Acceptable — still exact, never slower than full ticks in the limit.
- **Tick-store coverage** must match the bar windows being resolved; holes error rather than approximate.
- **Mark-to-market on skipped bars** uses bar close — confirm this matches full-tick MTM sampling for equity-curve byte-identity (likely needs MTM at bar boundaries only, which full-tick also effectively does on candle close).
- **OCO / bracket interplay within one bar** (both legs' levels in range) must resolve to the first-crossed leg — the within-bar tick scan handles it, but it is a priority parity-test case.

## 10. Test plan

1. **Parity oracle (primary):** for a curated strategy set covering each order shape (market, stop/limit entry, SL/TP bracket, trailing stop, if-touched, multi-symbol, gap-open), assert tick-resolved output == full-tick output byte-for-byte (trades, P&L, equity, rejections).
2. **Skip-soundness unit tests:** the fill-possible predicate returns false only when no live level is in `[low, high]` and no trailing is live; true otherwise.
3. **Slice correctness:** the time-range tick slice returns exactly the ticks in `[B.start, B.end)` against a known fixture.
4. **Benchmark:** wall-clock + ticks-decoded vs full-tick across flat-heavy and always-in strategies; record the speedup distribution. No silent caps.
5. **Hole handling:** a missing tick slice for a fill-possible bar errors with a clear message.

## 11. Out of scope / phasing

- Phase A: the slice API + the replay mode + the parity oracle (opt-in `--tick-fills`).
- Phase B (separate spec): consider making it the default exact path; forge wires G6/G7 grading to it once parity + speedup are proven.
- Not in scope: parallelizing a single replay; changing BigDecimal money math; the `--bar-fill-tf` finer-screen work (separate, already built on `feat-bars-fill-tf`).

## 12. References

- Bar-fill optimism map and the `O→L→H→C` synthesis: `docs/parity/backtest-vs-live.md` (A6), `BarTickFeed`, `PaperBroker` fill-at-trigger (#514), the coarsest-built-tf resolver (#515).
- Drift measurement (10 survivors, 30m vs 1m vs ticks, winner params + mt5-sim): forge `run/drift_measure2.json`.
- Companion screen-side work: `--bar-fill-tf` finer-fill override (`feat-bars-fill-tf`).
