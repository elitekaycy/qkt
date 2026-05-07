# qkt

[![check](https://github.com/elitekaycy/qkt/actions/workflows/check.yml/badge.svg)](https://github.com/elitekaycy/qkt/actions/workflows/check.yml)

An event-driven trading engine in Kotlin with backtest replay, parameter sweeps, attribution-aware risk, and a future SQL-like DSL.

## Status

Pre-1.0. Latest release: [`v0.10.1`](https://github.com/elitekaycy/qkt/releases/latest). Breaking changes happen in minor releases until `1.0.0`. The engine is functional and tested but the public API is not yet declared stable.

See [`docs/phases/`](docs/phases/) for per-phase changelogs and [`docs/release-process.md`](docs/release-process.md) for versioning.

## Features

- **Tick + candle pipeline** with a deterministic event bus ([phase 2a](docs/phases/), [phase 2b](docs/phases/)).
- **Multi-strategy support** with per-strategy P&L attribution ([phase 8](docs/phases/phase-8-strategy-context-and-pnl-attribution.md)).
- **Risk engine** — equity tracking, drawdown halts, daily loss halts, halt-as-state with operator-driven resume ([phase 9](docs/phases/phase-9-risk-engine.md)).
- **Bybit Spot + Linear (USDT)** live trading with reconciliation, rate limiting, and connection resilience ([phase 7e–7h](docs/phases/)).
- **Backtest replay engine** with full reporting: equity curves, Sharpe, Calmar, profit factor, win/loss stats ([phase 10](docs/phases/phase-10-backtest-reporting.md)).
- **Parameter sweep harness** with sequential or fixed-pool parallel execution and ranked summary reports ([phase 10b](docs/phases/phase-10b-parameter-sweep.md)).
- **TradingView live vendor** (anonymous, free-tier) for paper trading ([phase 7c](docs/phases/)).
- **On-disk content-addressable data store** with Dukascopy auto-fetch and bring-your-own CSV ([phase 6](docs/phases/)).

## Quickstart

Requires JDK 21 (Gradle's toolchain auto-provisions if not local).

```bash
git clone https://github.com/elitekaycy/qkt.git
cd qkt
./gradlew build      # compile + test + assemble
./gradlew run        # main application — prints fills, exits
```

A minimal in-process backtest:

```kotlin
import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick

val ticks = (1..100).map { Tick("X", Money.of((100 + it).toString()), it * 60_000L) }
val result = Backtest(
    strategies = listOf("buy-and-hold" to MyStrategy()),
    ticks = ticks,
    candleWindow = TimeWindow.ONE_MINUTE,
).run()

println("Total P&L: ${result.global.totalPnL}")
println("Sharpe: ${result.global.sharpeRatio}")
println("Max drawdown: ${result.global.maxDrawdown}")
```

For real historical data via the Dukascopy fetcher or live trading via TradingView, see the relevant phase changelogs in [`docs/phases/`](docs/phases/).

## Architecture

```
Tick → Engine → Strategy → Signal → Order → Broker → Trade
```

Single-threaded event-driven pipeline. Every component is deterministic given its inputs and seeds. `Clock`, `IdGenerator`, and `SequenceGenerator` are interfaces so backtest is a component swap, not a rewrite. State that is shared between producers and consumers is exposed via read-only interfaces — the type system enforces the read/write split.

For depth, read the per-phase design specs in [`docs/superpowers/specs/`](docs/superpowers/specs/).

## Repository layout

```
src/main/kotlin/com/qkt/
├── app/             entry points: Main, LiveSession, TradingPipeline, IndicatorWarmer
├── backtest/        Backtest, BacktestResult, EquitySample, EquityCurveCollector,
│                    PerformanceReport, ReportBuilder, SampleCadence, TradeRecord
│   ├── metrics/     profitFactor, winLossStats, sharpe, calmar
│   ├── report/      BacktestReportWriter, SweepReportWriter, ReportSerializer
│   └── sweep/       BacktestSweep, SweepRun, SweepResult
├── broker/          Broker, PaperBroker, BybitBroker, CompositeBroker
├── bus/             EventBus
├── candles/         CandleAggregator, TimeWindow
├── common/          Clock, FixedClock, SystemClock, Money, Side, IdGenerator,
│                    TradingCalendar (crypto, fx, NYSE), TimeRange, SessionAnchor
├── engine/          Engine
├── events/          Event, TickEvent, CandleEvent, SignalEvent, OrderEvent,
│                    BrokerEvent (Filled, Rejected, Reconciled, ...), RiskEvent
├── execution/       Order, OrderRequest, Trade, OrderType
├── marketdata/      Tick, Candle, MarketSource, MarketPriceTracker, TickFeed
│                    (Historical, Sequence, Merging), data store, vendors
├── pnl/             PnLCalculator, StrategyPnL, PnLProvider, StrategyPnLView
├── positions/       PositionTracker, StrategyPositionTracker, Position
├── risk/            RiskEngine, RiskState, EquityTracker, DrawdownTracker,
│                    DailyPnLTracker, RiskRule, HaltRule, RiskView, rules/
└── strategy/        Strategy, StrategyContext, Signal, Mode, WarmupSpec, samples/
```

## Build, run, test

```bash
./gradlew build              # compile + test + ktlint + assemble
./gradlew test               # tests only
./gradlew run                # main application
./gradlew runLiveDemo        # TradingView paper-trading demo
./gradlew test -PincludeTags=e2e  # run live smoke tests (manual)
./gradlew ktlintFormat       # auto-format
```

Pre-push helper:

```bash
./scripts/precheck.sh
```

After cloning, install the git hook so precheck runs automatically before every push:

```bash
./scripts/install-hooks.sh
```

## Getting real data

Phase 6 ships a content-addressable on-disk data store at `~/.qkt/data/` (override via the `QKT_DATA_HOME` environment variable). Strategies query this store via `Backtest.fromStore(...)` or directly via `DataStore.openFeed(...)`. The store is empty on first install — populate it via Dukascopy auto-fetch, bring-your-own CSV, or the bundled sample fixture. See the [phase 6 changelog](docs/phases/) for the full guide.

## Live trading

Phase 7 ships a live runtime alongside the historical backtest. Vendors include TradingView (anonymous, free-tier) for market data and Bybit (Spot + Linear/USDT) for execution. Live ticks feed the same `TradingPipeline` your backtest uses; the only difference is the `TickFeed` (live) and the `Clock` (system time). See [`docs/phases/phase-7-live-runtime.md`](docs/phases/phase-7-live-runtime.md) and the Bybit phase changelogs for setup and constraints.

## Documentation

- [`docs/phases/`](docs/phases/) — per-phase changelogs (the authoritative "what's in qkt today" reference).
- [`docs/superpowers/specs/`](docs/superpowers/specs/) — design specs per phase.
- [`docs/superpowers/plans/`](docs/superpowers/plans/) — implementation plans per phase.
- [`docs/release-process.md`](docs/release-process.md) — versioning and tagging policy.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — how to contribute.
- [`SECURITY.md`](SECURITY.md) — vulnerability disclosure.
- [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) — community standards.

## License

Apache 2.0 — see [LICENSE](LICENSE).
