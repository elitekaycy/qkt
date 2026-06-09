# Run a parameter sweep

Grid-search over your strategy's parameters to find what worked on historical data.

## From the CLI

Mark the values you want to tune with `PARAM` (or a plain `LET`), then sweep them. A single value
is a fixed override; a comma-list is a sweep axis, and `qkt sweep` runs the cartesian product:

```bash
# one combo — just override the file defaults
qkt backtest strategy.qkt --from 2024-01-01 --to 2024-02-01 --param fast=12 --param slow=26

# grid: 3 x 2 = 6 combos, ranked by Sharpe
qkt sweep strategy.qkt --from 2024-01-01 --to 2024-02-01 \
    --param fast=8,12,16 --param slow=20,40 --rank sharpe
```

`--rank` accepts `sharpe` (default), `calmar`, `profitFactor`, `totalPnL`, or `winRate`. Add
`--json` for machine-readable rows, or `--parallelism N` to run combos concurrently.

!!! warning "A sweep alone proves nothing — walk-forward validate"
    A sweep with 100 parameter combos and 50 trades per combo will find ~5 winners by chance alone.
    Use `qkt walkforward` to check the winner holds up out-of-sample:

    ```bash
    qkt walkforward strategy.qkt --from 2024-01-01 --to 2024-06-01 \
        --param fast=8,12,16 --train 60d --test 20d --step 20d --rank sharpe
    ```

    It trains on each rolling window (picking the best params), tests them on the *next, unseen*
    window, and reports mean in-sample vs out-of-sample score — a large gap is overfitting.
    See [Phase 10c — Walk-forward](../phases/phase-10c-walk-forward.md) for the protocol.

## Programmatic (advanced)

The `BacktestSweep` class takes a Kotlin lambda that builds a strategy per parameter combination, runs each on the same tick stream, and returns ranked results. It's a small wrapper around `Backtest.run()`.

### Sketch — a Kotlin sweep harness

```kotlin title="src/test/kotlin/com/qkt/sweeps/EmaCrossSweep.kt"
package com.qkt.sweeps

import com.qkt.backtest.sweep.BacktestSweep
import com.qkt.indicators.catalog.EMA
import com.qkt.marketdata.HistoricalTickFeed
import com.qkt.strategy.samples.EmaCrossoverStrategy
import org.junit.jupiter.api.Test
import java.nio.file.Path

class EmaCrossSweep {
    @Test
    fun `find best EMA pair on BTC`() {
        val ticks = HistoricalTickFeed.fromCsv(Path.of("data/btc-2024.csv.gz")).toList()

        data class Config(val fast: Int, val slow: Int)

        val configs = (5..15 step 2).flatMap { fast ->
            (20..40 step 5).map { slow -> Config(fast, slow) }
        }

        val sweep = BacktestSweep<Config>(
            configs = configs,
            buildStrategy = { c -> EmaCrossoverStrategy(fast = c.fast, slow = c.slow) },
            rules = emptyList(),
            ticks = ticks,
        )

        val results = sweep.run()
        val ranked = results.sortedByDescending { it.result.totalPnL }

        println("top 5:")
        ranked.take(5).forEach { run ->
            println("  fast=${run.config.fast} slow=${run.config.slow} " +
                    "pnl=${run.result.totalPnL} sharpe=${run.result.global.sharpeRatio}")
        }
    }
}
```

Run it the same way you run any test:

```bash
./gradlew test --tests com.qkt.sweeps.EmaCrossSweep
```

### What `BacktestSweep` returns

A `SweepResult<C>` for each config, containing:

- `config: C` — the input parameter combination
- `result: BacktestResult` — full backtest output (trades, equity curve, drawdown, Sharpe, Calmar, profit factor)

You decide how to rank, filter, and report — pure Kotlin from there.

### Parallel vs sequential

`BacktestSweep` accepts a parallelism parameter; sequential by default. For a few dozen configs, sequential is fast enough. For hundreds, bump it:

```kotlin
val sweep = BacktestSweep(
    configs = configs,
    buildStrategy = ::buildStrategy,
    rules = emptyList(),
    ticks = ticks,
    parallelism = 4,         // run 4 backtests concurrently
)
```

The engine is single-threaded per strategy; parallelism here means multiple strategies running in parallel on independent tick replays.

### Walk-forward — manual today

Phase 10c shipped walk-forward as a Kotlin API. Until the CLI lands:

```kotlin
val fullTicks: List<Tick> = ...
val trainDays = 60
val testDays  = 14
val msPerDay  = 86_400_000L

var cursor = fullTicks.first().timestamp
val testResults = mutableListOf<SweepResult<Config>>()

while (cursor + (trainDays + testDays) * msPerDay <= fullTicks.last().timestamp) {
    val trainEnd = cursor + trainDays * msPerDay
    val testEnd  = trainEnd + testDays * msPerDay

    val trainTicks = fullTicks.filter { it.timestamp in cursor until trainEnd }
    val testTicks  = fullTicks.filter { it.timestamp in trainEnd until testEnd }

    val trainSweep = BacktestSweep(configs, ::buildStrategy, emptyList(), trainTicks).run()
    val bestOnTrain = trainSweep.maxByOrNull { it.result.totalPnL }!!.config

    val testRun = Backtest(
        strategies = listOf(buildStrategy(bestOnTrain)),
        rules = emptyList(),
        ticks = testTicks,
    ).run()
    testResults.add(SweepResult(bestOnTrain, testRun))

    cursor = trainEnd     // roll forward
}

// Aggregate testResults — that's your honest out-of-sample performance
```

Verbose, but it's the protocol — and once the CLI ships in Phase 25 it'll wrap this exact loop.

## How to pick a winner from the results

A robust winner has:

- **Trade count ≥ 100** across the full period — fewer than that and you're fitting to noise.
- **Walk-forward Sharpe ≥ 1.0** on out-of-sample windows.
- **Walk-forward Sharpe / in-sample Sharpe ≥ 0.5** — degradation is normal, but a >50% drop suggests overfitting.
- **Max drawdown your account can survive** — your risk tolerance, not the strategy's preference.

If multiple combos qualify, pick the one with the **simplest** params (fewer free variables = harder to overfit).

## Lock the winner in

Hard-code the winning params back into the strategy file:

```qkt
LET fast = 12
LET slow = 26
```

Re-run a full backtest with the locked params to confirm. Then paper-trade for at least 2 weeks before considering anything else.

## Performance tips

- **Date range matters more than param count.** 5 years of data beats 5 params.
- **Use `EVERY 5m` or `EVERY 15m`** for sweeps — 1-minute candles produce 5–15× more data without much added signal.
- **Sweep the right things.** Indicator periods, stop/target ratios, regime filters. Don't sweep things like `LET symbol = "BTCUSDT"` — you're not choosing which market to trade.

## See also

- [Phase 10b — Parameter sweep](../phases/phase-10b-parameter-sweep.md) — internals
- [Phase 10c — Walk-forward](../phases/phase-10c-walk-forward.md) — protocol
- [Backtest model](../concepts/backtest-model.md) — what the report numbers mean
- [Planned features](../planned.md) — when the CLI wrapper lands
