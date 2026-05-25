# Multi-strategy stress test — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the daemon can host 5 concurrent DSL strategies without cross-strategy state leak, signal misrouting, warmup interference, or per-strategy risk-rule bleed. The test is the regression guard for "I can trust qkt-prod to run all my strategies side by side."

**Architecture:** No new production code. The test exercises existing infrastructure: `CandleHub` (shared aggregator, per-strategy listeners), `StrategyPositionTracker` (`(strategyId, symbol)` keys), `TradeHistory` (per-strategy ring buffers), per-strategy risk rules (`MaxStrategyDailyLoss`, etc.), and the `Backtest.perStrategy` per-strategy report.

**Tech stack:** Kotlin, JUnit 5 + AssertJ. No mocks. Synthetic tick generator is a tiny helper, not a fixture library.

**Spec:** GitHub issue [#138](https://github.com/elitekaycy/qkt/issues/138) is the design spec. Scope decisions captured below.

**Working branch:** `issue138-multi-strategy-stress`. One PR into `dev` at the end.

---

## Scope decisions

| Decision | Choice |
|---|---|
| Strategy count | 5 (per issue) |
| Latency | Log per-tick processing time + soft wall-clock ceiling. No hard p95 cap in batch test. |
| Warmup isolation | Backtest-level — each strategy starts trading at its OWN warmup completion, not the slowest one |
| Test tag | `@Tag("stress")` — opt-in CI job, excluded from default `./gradlew test` (mirroring `e2e`, `dockerSmoke`) |

---

## File structure

| File | Responsibility | Action |
|---|---|---|
| `src/test/kotlin/com/qkt/app/MultiStrategyStressTest.kt` | The stress test class. | Create |
| `src/test/kotlin/com/qkt/app/SyntheticTickStream.kt` | Deterministic seedable tick generator helper. | Create |
| `src/test/resources/stress/ema-cross.qkt` | EMA-cross buyer strategy. | Create |
| `src/test/resources/stress/rsi-revert.qkt` | RSI mean-revert strategy. | Create |
| `src/test/resources/stress/breakout.qkt` | Range breakout strategy. | Create |
| `src/test/resources/stress/dd-aware.qkt` | Self-halts on drawdown via `ACCOUNT.dd_pct`. | Create |
| `src/test/resources/stress/streak-aware.qkt` | Sizes down after loss streak via `ACCOUNT.loss_streak`. | Create |
| `src/test/resources/stress/portfolio.qkt` | Portfolio bundling two children. | Create |
| `src/test/resources/stress/portfolio-child-a.qkt` | EMA(5)/EMA(25) child. | Create |
| `src/test/resources/stress/portfolio-child-b.qkt` | RSI(14) child. | Create |
| `build.gradle.kts` | Add `stress` to excluded default tags. | Modify |
| `docs/contributing/stress-tests.md` | How to run + extend. | Create |
| `docs/contributing/index.md` | Link the new page. | Modify |
| `mkdocs.yml` | Nav entry. | Modify |

---

## Task 1: Strategy fixtures

**Files:** five `.qkt` files under `src/test/resources/stress/`.

Each fixture must:
- Declare its own warmup spec (different durations so warmup-isolation is testable).
- Trade BTC (single shared symbol) so the CandleHub is exercised across all five.
- Be deterministic given a deterministic tick stream.
- Use different indicators / different account accessors so we cover the surface.

- [ ] **ema-cross.qkt** — `WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21) AND POSITION.btc = 0 THEN BUY btc SIZING 1 LOT`. Warmup: 21 bars.
- [ ] **rsi-revert.qkt** — `WHEN rsi(btc.close, 14) < 30 AND POSITION.btc = 0 THEN BUY btc SIZING 1 LOT`. Warmup: 14 bars.
- [ ] **breakout.qkt** — `WHEN btc.close > highest(btc.high, 20)[1] AND POSITION.btc = 0 THEN BUY btc SIZING 1 LOT`. Warmup: 20 bars.
- [ ] **dd-aware.qkt** — like ema-cross but gated by `AND ACCOUNT.dd_pct < 5`. Warmup: 21 bars.
- [ ] **streak-aware.qkt** — like ema-cross but `SIZING CASE WHEN ACCOUNT.loss_streak >= 2 THEN 0.5 ELSE 1 END LOT`. Warmup: 21 bars.

Each fixture also defines `EXITS` so positions actually close — `WHEN POSITION.btc > 0 AND btc.close < ema(btc.close, 9) THEN CLOSE btc` or similar.

---

## Task 2: SyntheticTickStream helper

**File:** `src/test/kotlin/com/qkt/app/SyntheticTickStream.kt`.

- [ ] Class `SyntheticTickStream(seed: Long, ticks: Int, startPrice: BigDecimal, startTimestamp: Long)`.
- [ ] Produces a `List<Tick>` via seeded `Random` — random walk with drift, fixed tick interval (60s for hourly candles? 10s for fast cycle? — pick something that gives ~500 candles from ticks).
- [ ] Deterministic: same seed → same list. Asserted by Task 4 determinism test.
- [ ] Volume populated (so VWAP indicators can be added later if needed).

---

## Task 3: Stress test class

**File:** `src/test/kotlin/com/qkt/app/MultiStrategyStressTest.kt`.

Class-level `@Tag("stress")`. Loads 5 standalone DSL strategies plus 1 portfolio with 2 children.

- [ ] **Test 1:** `five standalone strategies plus one portfolio coexist and produce trade timelines`. Run, assert `perStrategy` keys exactly match registered ids (5 + portfolioId), each has an equity curve, ≥3 of 6 actually trade.
- [ ] **Test 2:** `same seed produces identical trade sequence`. Run twice, assert trades + per-strategy equity curves match.
- [ ] **Test 3:** `every trade is attributed to exactly one strategy and that strategy owns it`. Every trade's strategyId is in the registered set; perStrategy tradeCount equals filtered count for each id.
- [ ] **Test 4:** `portfolio trades attribute to portfolio id, not to standalone strategies`. Per portfolio v1 simplification, child trades use the parent's strategyId — verify no child id appears.
- [ ] **Test 5:** `each strategy starts trading only after its OWN warmup completes`. For each standalone strategy, first trade's candle index >= that strategy's warmupBars.
- [ ] **Test 6:** `wall-clock smoke ceiling`. Measure `System.nanoTime()` around `Backtest.run()`. Assert under 30s (very generous — flags catastrophic regressions only). Print actual duration to stdout for trend monitoring.

### Tests deliberately not included

- ~~Per-strategy daily-loss halt isolation~~ — needs `StrategyPositionTracker` injection from inside `Backtest`, which the harness doesn't expose. Unit tests on `MaxStrategyDailyLoss` etc. cover the rule logic; coexistence at the engine level isn't what's at stake.
- ~~Aggregate global vs sum-of-per-strategy realized PnL parity~~ — when strategies share a symbol, global uses mixed-basis avg-cost accounting and per-strategy is segregated-basis. They diverge by design. Comparing them would catch the design choice, not a regression.

---

## Task 4: build.gradle.kts tag exclusion

**File:** `build.gradle.kts`.

- [ ] Add `"stress"` to the default `excludeTags(...)` list alongside `e2e`, `e2e-live`, `dockerSmoke`.
- [ ] CI runs it via `-PincludeTags=stress` in a separate job (already-existing CI mechanism).

---

## Task 5: Contributing docs

**File:** `docs/contributing/stress-tests.md`.

- [ ] What the stress test guards (per-strategy isolation invariants).
- [ ] How to run: `./gradlew test -PincludeTags=stress`.
- [ ] How to extend: add a `.qkt` to `src/test/resources/stress/`, wire it into the test's strategy list.
- [ ] Methodology for "did the stress test actually catch a leak?" — instructions for deliberately introducing a bug locally to verify the test would fail.

Link from `docs/contributing/index.md` and add nav entry in `mkdocs.yml`.

---

## Acceptance

- All six tests pass with `./gradlew test -PincludeTags=stress`.
- Default `./gradlew test` does not run the stress test (verified by exclusion).
- ktlint clean.
- Issue #138 acceptance criteria satisfied (test runs deterministically; documented; can detect regressions per the methodology section).

---

## Out of scope

- Hard latency p95 cap (deferred to live-integration latency test, follow-up issue if desired).
- Daemon-level hot-deploy mid-stream warmup test (Backtest-level coverage chosen; deeper test can land separately if multi-strategy reliability concern arises).
- Multi-symbol stress (single-symbol BTC keeps the test focused on strategy-level isolation, not hub-symbol routing).
