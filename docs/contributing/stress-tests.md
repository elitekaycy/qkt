# Stress tests

Stress tests prove invariants about coexistence — how the engine behaves when
multiple strategies, brokers, or feeds share the same runtime. They are
opt-in: excluded from the default `./gradlew test` run so a contributor's
inner-loop stays fast, but mandatory before signing off on changes to
strategy isolation, hub routing, or per-strategy risk plumbing.

## What stress tests guard

The current stress surface centres on **multi-strategy coexistence** (issue
[#138](https://github.com/elitekaycy/qkt/issues/138)). The harness loads 5
standalone DSL strategies plus 1 portfolio (with 2 children) and asserts:

1. **Coexistence.** All 5 standalone strategies and the portfolio receive their
   own equity curve. Per-strategy report keys are exactly the registered ids,
   no orphans or duplicates.
2. **Trade attribution.** Every trade in the result has a `strategyId` that
   matches one of the registered strategies. Per-strategy trade counts match
   `trades.filter { it.strategyId == id }.size`.
3. **Portfolio isolation.** Portfolio trades attribute to the portfolio's id
   (not to any of the children's standalone-like ids), matching the v1
   portfolio simplification where children share the parent's strategyId.
4. **Per-strategy warmup.** Each strategy starts trading only after its OWN
   warmup is complete, not the slowest one — proven by first-trade timestamp
   per strategy >= that strategy's declared warmup bars.
5. **Determinism.** Same seed produces identical trade sequence — required so
   regressions are reproducible from the seed alone.
6. **Throughput smoke ceiling.** 6 strategies (5 + portfolio) over 500 candles
   complete in under 30s. Flags catastrophic regressions; not a real latency
   budget. Live latency is a separate concern.

These properties together are what "I can trust qkt-prod to run all my
strategies side by side" means in code.

### What the harness deliberately does NOT assert

- **Global vs per-strategy P&L parity.** When two strategies trade the same
  symbol, the global ledger uses mixed-basis average-cost accounting while the
  per-strategy ledger is segregated-basis. These diverge by design; comparing
  them would catch the design, not a regression.
- **Per-strategy halt-rule isolation.** Per-strategy halt rules
  (`MaxStrategyDailyLoss`, etc.) need a `StrategyPositionTracker` reference
  that the `Backtest` harness creates internally and doesn't expose. Their
  unit tests live alongside the rules; the stress harness assumes those pass.
- **Hard latency caps under load.** Backtest is synchronous batch — no
  network, no scheduler. A latency cap here would measure JVM warmup, not
  pipeline depth.

## Running

```bash
./gradlew test -PincludeTags=stress
```

The `stress` tag is excluded from the default test run in `build.gradle.kts`.
CI runs it in a separate job (configured in `.github/workflows/check.yml`).

## Adding a new stress strategy

1. Drop a `.qkt` file in `src/test/resources/stress/`. Use
   `BACKTEST:BTCUSDT EVERY 1m` as the symbol so it shares the synthetic stream;
   declare its own `WARMUP N BARS` so warmup-isolation is testable.
2. Add a `Fixture(id, file, warmupBars)` entry in
   `MultiStrategyStressTest.fixtures`.
3. Re-run the test. The warmup-isolation assertion uses `warmupBars`, so make
   sure it matches the largest indicator period or the explicit
   `WARMUP N BARS` declaration in the strategy.

Keep new fixtures **independent in behaviour** — different indicators, different
entry/exit logic — so the stress test exercises a wide surface, not five
flavours of the same strategy.

### Adding another portfolio

The current test loads one portfolio via `PortfolioLoader.load(...)`. To add
another, write a second `portfolio-<name>.qkt` plus its child fixtures under
`src/test/resources/stress/`, then extend `loadStrategies()` to load it
alongside the first. Use a distinct portfolio id so the attribution test still
discriminates.

## How to verify the test would actually catch a leak

The acceptance criterion for #138 says: *"catches at least one
historical-regression scenario"*. To prove this isn't theoretical, you can
deliberately introduce a leak locally:

1. In `TradingPipeline.kt`, find where `strategyId` is propagated into
   `TradeRecord`. Hard-code it to a single strategy:
   ```kotlin
   tradeRecords.add(TradeRecord(trade, realized, "ema_cross", risk))
   ```
2. Re-run the test:
   ```bash
   ./gradlew test --tests 'com.qkt.app.MultiStrategyStressTest' -PincludeTags=stress
   ```
3. **Expected outcome:** the *"every trade is attributed to exactly one
   strategy"* and *"perStrategy tradeCount matches"* tests fail.
4. Revert the change.

If those tests *don't* fail, the stress test isn't actually guarding the
invariant — file a bug.

## Why stress is its own tag

Three reasons:

- **Cost.** A stress test that exercises 5 DSL strategies and a few thousand
  ticks takes a few seconds. That's tolerable in CI but not in every
  contributor's edit-compile-test loop.
- **Specificity.** Stress tests fail in ways that are noisy and hard to read.
  A contributor changing a typo in a docstring doesn't need to debug them.
- **Intent.** The `stress` tag signals to a reviewer that the test guards a
  coexistence property, not a unit behaviour. Bugs found here usually require
  architectural thinking, not a one-line patch.

## Future stress targets

When new coexistence concerns arise, file an issue + add a tagged test.
Candidates currently on the backlog:

- Multi-MT5 broker simultaneous live profiles (#139)
- Per-strategy latency under load — live measurement, not the batch smoke check
  used here (follow-up to #138)
- Multi-symbol stress (current test uses a single symbol; symbol-routing has its
  own unit tests but not a stress harness)
