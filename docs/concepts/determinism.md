# Determinism

The backtest and a live-paper run produce **bit-identical trades** given the same compiled strategy and the same tick sequence. Same fills, same realized PnL, same final positions. This is enforced by a regression test: [`BacktestLiveParityTest`](https://github.com/elitekaycy/qkt/blob/main/src/test/kotlin/com/qkt/parity/BacktestLiveParityTest.kt).

## Why this matters

Without it, backtests are theater. You'd never know whether a 12% Sharpe in the report would survive contact with the live engine. With it, the report is a real prediction of paper-traded behavior — the only remaining drift is between paper and the broker (slippage, partial fills, network), which is bounded and observable.

## What's deterministic

Given the same ticks:

- Order ids (`SequentialIdGenerator` is seeded the same way)
- Order request timestamps (driven by tick timestamps via `FixedClock` in both runtimes)
- Fill prices (`PaperBroker` uses the same `MarketPriceTracker` last-price logic)
- Realized PnL per trade
- Final position quantities

## What's not deterministic

- `System.currentTimeMillis()` calls — but the engine doesn't make any in the strategy path; clock access goes through the injected `Clock`
- Thread scheduling — irrelevant because the bus is synchronous within its publisher thread
- RNG — engine uses none; tests that need randomness pass an explicit seeded `Random`

## What live-broker adds

Live runs against a real broker (MT5, Bybit) lose this property because:

- Network latency between order submit and fill
- Slippage (broker fills at a price different from the trigger)
- Partial fills
- Broker-side SL/TP triggers detected via polling, not synchronously

The backtest model treats fills as immediate at the tracker's last price. Live runs are subject to real-world execution. The gap between them is what `qkt audit-ticks` and the backtest-vs-live audit programs exist to quantify.

## Concrete example: same ticks, same trades

Two backtest runs on identical input produce identical output. Here's the property:

```kotlin
val ticks = HistoricalTickFeed.fromCsv(Path.of("data/btc.csv")).toList()
val r1 = Backtest(strategies, rules, ticks, candleWindow = ONE_MINUTE, initialTimestamp = 0L).run()
val r2 = Backtest(strategies, rules, ticks, candleWindow = ONE_MINUTE, initialTimestamp = 0L).run()
assertThat(r1.trades).isEqualTo(r2.trades)       // every Trade id, price, qty matches
assertThat(r1.totalPnL).isEqualTo(r2.totalPnL)   // exact BigDecimal equality
```

No tolerance, no `isCloseTo`. Exact equality. This is what makes parameter sweeps and walk-forward validation trustworthy — re-runs always produce the same numbers.

## The parity contract

```kotlin
// BacktestLiveParityTest
val backtestResult = Backtest(strategies, ticks, ...).run()

val liveTrades = mutableListOf<Trade>()
LiveSession(strategies, FakeSource(ticks, FixedClock(...)), ..., onTrade = { t, _, _ -> liveTrades.add(t) })
    .start()
    .awaitTermination(...)

assertThat(liveTrades).isEqualTo(backtestResult.trades.map { it.trade })
```

Failure to maintain this contract is a P0 bug. The test runs in CI on every PR.
