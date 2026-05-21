<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/qkt-logo-dark.svg">
    <img alt="qkt" src="docs/assets/qkt-logo-light.svg" width="260">
  </picture>
</p>

<p align="center">
  <a href="https://github.com/elitekaycy/qkt/actions/workflows/check.yml"><img src="https://github.com/elitekaycy/qkt/actions/workflows/check.yml/badge.svg" alt="check"></a>
  <a href="https://github.com/elitekaycy/qkt/actions/workflows/docs.yml"><img src="https://github.com/elitekaycy/qkt/actions/workflows/docs.yml/badge.svg" alt="docs"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue.svg" alt="license"></a>
</p>

An event-driven trading engine in Kotlin with backtest replay, parameter sweeps, attribution-aware risk, and a future SQL-like DSL.

**[Full documentation site](https://elitekaycy.github.io/qkt/)** — quickstart, DSL grammar, CLI reference, deployment guides, architecture diagrams, KDoc API reference.

## Status

Pre-1.0. Latest release: [`v0.28.8`](https://github.com/elitekaycy/qkt/releases/latest). Breaking changes happen in minor releases until `1.0.0`. The engine is functional and tested but the public API is not yet declared stable.

For a 5-minute getting-started, see [`QUICKSTART.md`](QUICKSTART.md).

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
- **STACK pyramiding** — turn one `BUY`/`SELL` action into N price-triggered entries with an optional time fence ([phase 13a](docs/phases/phase-13a-stack.md)).
- **CANCEL action + PORTFOLIO** — `CANCEL`/`CANCEL_ALL` cancel pending orders from inside a strategy; `PORTFOLIO` files compose N strategies with regime-gated activation ([phase 13b](docs/phases/phase-13b.md)).
- **Portfolio daemon** — `qkt deploy mybook.qkt` fans out into per-child `LiveSession`s with their own ports + logs; `qkt start mybook/trend` clears operator-stop ([phase 14](docs/phases/phase-14.md)).
- **DSL `LOG` action** — levels (`INFO`/`WARN`/`ERROR`/`DEBUG`), `{name}` placeholders, structured `key=expr` fields. Stdout shows `[strategy]` prefix; child log files use safe `__`-substituted names ([phase 15](docs/phases/phase-15.md)).
- **Backtest HTML report** — single self-contained `report.html` per run with embedded SVG equity + drawdown chart, Monte Carlo fan, drawdown-period table, per-trade risk column ([phase 16](docs/phases/phase-16.md)).
- **MT5 broker (multi-profile)** — talks to per-broker `mt5-gateway` HTTP services; built-in defaults for Exness, ICMarkets, FTMO, Pepperstone; override or extend via `qkt.config.yaml`; v1 ships Market + Bracket ([phase 17](docs/phases/phase-17.md)).
- **Live broker dispatch** — `LiveSession` accepts a typed broker registry; strategies declaring `EXNESS:EURUSD` route orders through the configured profile (MT5 or any other protocol). Per-session lifecycles via `BrokerFactory` ([phase 18](docs/phases/phase-18.md)).
- **Pre-live confidence pack** — end-to-end MT5 daemon smoke test, `qkt audit-ticks` CLI for TV-vs-MT5 drift comparison, standardized logging guide, memory leak audit + fixes ([phase 19](docs/phases/phase-19.md)).
- **One-shot Docker stack** — `docker compose up` brings up qkt + `mt5-gateway` together; a top-level `QUICKSTART.md` for a 5-minute start ([phase 20](docs/phases/phase-20.md)).
- **Documentation site** — MkDocs Material site with a Diátaxis structure, Mermaid diagrams, and a Dokka API reference, deployed to GitHub Pages ([phase 21](docs/phases/phase-21.md)).
- **DSL indicator + accessor catalog** — SMA/WMA/MACD/Bollinger, `HIGHEST`/`LOWEST`, position dot-accessors, `#` comments ([phase 23](docs/phases/phase-23.md)).
- **Native MT5 pending-order family** — pending entries, OCO, and trailing stops placed natively on MT5, with a full pending-order fill lifecycle ([phase 26](docs/phases/)).
- **Conditional bracketed stacks** — `STACK_AT` builds price-conditional bracketed entry stacks ([phase 27](docs/phases/phase-27-conditional-bracketed-stacks.md)).
- **Multi-source market data** — one strategy can pull different streams from different vendors at once ([phase 28](docs/phases/phase-28-multi-source-marketdata.md)).
- **Engine state persistence** — daemon state survives restarts; in-flight orders and positions are recovered ([phase 29](docs/phases/phase-29-engine-state-persistence.md)).
- **Instrument metadata** — contract size, tick size, and related facts resolved per instrument ([phase 30](docs/phases/phase-30-instrument-metadata.md)).
- **Telegram alerts** — order, halt, and daily-summary notifications pushed to Telegram ([phase 31](docs/phases/phase-31-telegram-alerts.md)).
- **bid/ask in the DSL** — `<stream>.bid` / `.ask` / `.spread` for spread-gated strategies ([phase 32](docs/phases/phase-32-bid-ask.md)).

### STACK example — 3-layer pyramid

A single DSL rule expresses a full scaling-in plan. Layer 1 fires at market on the signal; layers 2 and 3 become pending entries triggered by price, each inheriting the outer bracket:

```
STRATEGY pyr VERSION 1
SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m
RULES
    WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
    THEN BUY btc SIZING 0.1
         STACK 3 SPACING 100
         BRACKET { STOP LOSS BY 50, TAKE PROFIT BY 200 }
```

If layer 1 fills at 50000, layers 2 and 3 trigger at 50100 and 50200 respectively. If layer 1's stop-loss fires before those prices are reached, the pending layers cancel automatically. Add `WITHIN 1h` to the `STACK` clause to abandon unfired layers after one hour. Use the `BELOW` keyword (or a layer-list with `AT entry - N` clauses) for average-down strategies. See [phase 13a](docs/phases/phase-13a-stack.md) for the full reference.

## Quick start

Requires JDK 21 (Gradle's toolchain auto-provisions if not local).

```bash
git clone https://github.com/elitekaycy/qkt.git
cd qkt
./gradlew installDist
./build/install/qkt/bin/qkt --version
```

qkt has three deployment shapes: foreground for one strategy, daemon for many, or Docker for both.

### 1. Foreground — one strategy

```bash
qkt run strategies/ema-crossover.qkt
# [INFO] qkt 0.28.8 — strategy ema-crossover v1 — paper-trading
# QKT_PORT=47291
# ... ticks flow, fills print to stdout, /status etc. served on QKT_PORT
```

`qkt run` paper-trades a single strategy and exposes a per-process observability HTTP port (12b).

### 2. Daemon — many strategies in one JVM

```bash
qkt daemon &                                  # start in background (or under systemd)
qkt deploy strategies/ema.qkt --as ema-fast   # register + start, returns port
qkt deploy strategies/momentum.qkt --as momo
qkt list                                      # NAME UPTIME PORT TRADES STATE
qkt status ema-fast                           # proxies through to the strategy's :PORT/status
qkt logs ema-fast -f                          # tail ~/.local/state/qkt/logs/ema-fast.log
qkt stop ema-fast                             # graceful shutdown
qkt daemon stop                               # shut down the daemon
```

Each deployed strategy gets its own `LiveSession`, observability port, and log file. Strategies on the
same `(broker, symbol, timeframe)` share one CandleHub aggregator (12c). The daemon control plane is
HTTP over TCP `127.0.0.1`; the bound port lands at `~/.local/state/qkt/control.port`.

`qkt daemon --load-dir <path>` auto-deploys every `*.qkt` file in the directory at startup.

### 3. Docker

The daemon is published as a multi-stage image at `ghcr.io/elitekaycy/qkt:<tag>`.

```bash
docker run -d --name qkt-prop \
    -v $(pwd)/strategies:/strategies \
    -p 47000-47100:47000-47100 \
    ghcr.io/elitekaycy/qkt:0.28.8

docker exec qkt-prop qkt list
docker exec qkt-prop qkt logs sample -f
```

A walk-through user image (`FROM ghcr.io/elitekaycy/qkt`) is in [`examples/docker/`](examples/docker/).

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
├── broker/          Broker, PaperBroker, BybitBroker, MT5Broker, CompositeBroker
├── bus/             EventBus
├── candles/         CandleAggregator, CandleHub, TimeWindow
├── cli/             the qkt CLI — command parsing, subcommands, daemon control
├── common/          Clock, FixedClock, SystemClock, Money, Side, IdGenerator,
│                    TradingCalendar (crypto, fx, NYSE), TimeRange, SessionAnchor
├── dsl/             the .qkt DSL — lexer, parser, compiler, evaluator, Kotlin DSL
├── engine/          Engine
├── events/          Event, TickEvent, CandleEvent, SignalEvent, OrderEvent,
│                    BrokerEvent (Filled, Rejected, Reconciled, ...), RiskEvent
├── execution/       Order, OrderRequest, Trade, OrderType, OrderManager
├── indicators/      indicator catalog — SMA, EMA, WMA, MACD, Bollinger, RSI, ...
├── instrument/      instrument metadata — contract size, tick size, symbol policy
├── marketdata/      Tick, Candle, MarketSource, MarketPriceTracker, TickFeed
│                    (Historical, Sequence, Merging), data store, vendors
├── notify/          notification system — Telegram notifier, event routing
├── persistence/     engine state persistence — state file read/write, recovery
├── pnl/             PnLCalculator, StrategyPnL, PnLProvider, StrategyPnLView
├── positions/       PositionTracker, StrategyPositionTracker, Position
├── risk/            RiskEngine, RiskState, EquityTracker, DrawdownTracker,
│                    DailyPnLTracker, RiskRule, HaltRule, RiskView, rules/
├── strategy/        Strategy, StrategyContext, Signal, Mode, WarmupSpec, samples/
└── tools/           operational tooling — audit-ticks and other diagnostics
```

## Build, run, test

```bash
./gradlew build              # compile + test + ktlint + assemble
./gradlew test               # tests only
./gradlew installDist        # produces build/install/qkt/bin/qkt
./gradlew dockerBuild        # builds qkt:local docker image
./gradlew test -PincludeTags=e2e  # run live smoke tests (manual)
./gradlew ktlintFormat       # auto-format
```

### Legacy entry points

These pre-date the `qkt` CLI and remain for reference / one-off debugging:

```bash
./gradlew run                # the original mock-tick demo (com.qkt.app.Main)
./gradlew runLiveDemo        # TradingView paper-trading demo (com.qkt.app.LiveDemo)
./gradlew runMaxAudit        # live audit across asset classes (com.qkt.app.MaxAudit)
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
