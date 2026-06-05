<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/qkt-logo-dark.svg">
    <img alt="qkt" src="docs/assets/qkt-logo-light.svg" width="260">
  </picture>
</p>

<h3 align="center">Write trading strategies in a SQL-like language.<br/>Backtest them deterministically — then run the same code live.</h3>

<p align="center">
  <a href="https://github.com/elitekaycy/qkt/actions/workflows/check.yml"><img src="https://github.com/elitekaycy/qkt/actions/workflows/check.yml/badge.svg" alt="check"></a>
  <a href="https://github.com/elitekaycy/qkt/actions/workflows/docs.yml"><img src="https://github.com/elitekaycy/qkt/actions/workflows/docs.yml/badge.svg" alt="docs"></a>
  <a href="https://github.com/elitekaycy/qkt/releases/latest"><img src="https://img.shields.io/github/v/release/elitekaycy/qkt?include_prereleases&label=release" alt="release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue.svg" alt="license"></a>
</p>

<p align="center">
  <a href="https://elitekaycy.github.io/qkt/">Documentation</a> ·
  <a href="QUICKSTART.md">Quickstart</a> ·
  <a href="docs/phases/">What's inside</a> ·
  <a href="#install">Install</a>
</p>

---

**qkt** is an event-driven trading engine in Kotlin. You describe a strategy in a small, readable DSL — symbols, indicators, and `WHEN … THEN …` rules — and qkt compiles it into a runnable strategy. The *same* compiled strategy backtests against historical data and trades live; the only things that change are the data feed and the clock. Backtests are deterministic and bit-identical to a live replay, so what you measure is what you get.

```sql
STRATEGY ema_cross VERSION 1

SYMBOLS
  gold = EXNESS:XAUUSD EVERY 5m WARMUP 50 BARS

RULES
  WHEN ema(gold.close, 9) CROSSES ABOVE ema(gold.close, 21)
   AND POSITION.gold = 0
  THEN BUY gold
       BRACKET { STOP LOSS BY 1.5, TAKE PROFIT BY 3.0 }
```

```bash
qkt run ema_cross.qkt        # paper-trade it live, observability on a local port
```

That's a complete strategy: a 9/21 EMA crossover on 5-minute gold, one position at a time, with an attached stop-loss and take-profit bracket. The same file runs against historical data, on a paper broker, or against a real venue — you change the data source, not the strategy.

## Why qkt

- **One language, backtest and live.** A `.qkt` strategy compiles to the same engine objects whether you're replaying history or trading a live account. No separate backtest dialect, no "it worked in the simulator."
- **Deterministic by construction.** Time, IDs, and randomness flow through injected interfaces (`Clock`, `IdGenerator`, seeds). Same inputs → same trades, every run. Backtest is a component swap, not a reimplementation.
- **A DSL that reads like intent.** Indicators (`ema`, `rsi`, `macd`, `atr`, `bollinger`, …), cross-stream rules, brackets, OCO, trailing stops, `STACK` pyramiding, `PORTFOLIO` composition, `SCHEDULE`, and `LOG` — all declared, not wired by hand.
- **Real brokers, real risk.** Live execution on MT5 (multi-profile) and Bybit (Spot + Linear), with reconciliation and reconnection. A risk engine tracks equity, drawdown, and daily loss, and halts as state with operator-driven resume.
- **Run one or run many.** `qkt run` foregrounds a single strategy; the `qkt daemon` hosts many in one JVM, each with its own log, observability port, and recovered-on-restart state.
- **Reports you can read.** Every backtest emits a self-contained `report.html` — equity and drawdown curves, Monte Carlo fan, per-trade risk, Sharpe / Calmar / profit factor.
- **Editor support.** Syntax highlighting and snippets for `.qkt` in VS Code, Neovim/Vim, and any TextMate-based editor.

<sub>For the exhaustive, phase-by-phase feature list, see the collapsible section near the bottom or the <a href="docs/phases/">phase changelogs</a>.</sub>

## Install

### Docker (no local Java)

```bash
docker run -d --name qkt \
  -v "$(pwd)/strategies:/strategies" \
  -p 47000-47100:47000-47100 \
  ghcr.io/elitekaycy/qkt:latest
docker exec qkt qkt list
```

### Linux (x64) — self-contained binary, no Java required

Each release ships a bundled runtime, so qkt runs without a system JDK. Download `qkt-<version>-linux-x64.tar.gz` from the [latest release](https://github.com/elitekaycy/qkt/releases/latest), then:

```bash
tar xzf qkt-*-linux-x64.tar.gz
./qkt/bin/qkt --version
# add ./qkt/bin to your PATH to call `qkt` from anywhere
```

### From source (any platform with JDK 21)

```bash
git clone https://github.com/elitekaycy/qkt.git && cd qkt
./gradlew installDist
./build/install/qkt/bin/qkt --version
```

### Windows

```powershell
# winget (recommended)
winget install elitekaycy.qkt

# or the one-line installer
irm https://raw.githubusercontent.com/elitekaycy/qkt/main/scripts/install.ps1 | iex
```

Both install a self-contained build (bundled Java runtime — no prerequisites). Open a new terminal, then `qkt --version`.

## A 60-second tour

```bash
# 1. Paper-trade one strategy in the foreground
qkt run ema_cross.qkt

# 2. Or host many at once under the daemon
qkt daemon &                         # background control plane on 127.0.0.1
qkt deploy ema_cross.qkt --as gold   # register + start, returns a port
qkt deploy momentum.qkt  --as momo
qkt list                             # NAME  UPTIME  PORT  TRADES  STATE
qkt logs gold -f                     # tail this strategy's log
qkt stop gold                        # graceful shutdown
```

Each strategy gets its own `LiveSession`, observability HTTP port, and log file; strategies sharing a `(broker, symbol, timeframe)` share one candle aggregator. State survives a restart — in-flight orders and positions are recovered. Point the daemon at a folder with `qkt daemon --load-dir ./strategies` to auto-deploy every `.qkt` in it.

For backtesting against real history (Dukascopy auto-fetch or your own CSV) and for going live on MT5 / Bybit, follow the [Quickstart](QUICKSTART.md) and the [phase changelogs](docs/phases/).

## Editor support

`.qkt` files get syntax highlighting, snippets, and comment support. The fastest path:

```bash
qkt editor install nvim     # or: vscode, vim
```

This drops the right files into your editor's config. VS Code, Neovim/Vim, and TextMate grammars all live under [`editor/`](editor/) with per-editor install notes — see [editor integrations](docs/how-to/editor-integrations.md).

## Architecture

```
Tick → Engine → Strategy → Signal → Order → Broker → Trade
```

A single-threaded, event-driven pipeline. Every component is deterministic given its inputs and seeds. `Clock`, `IdGenerator`, and `SequenceGenerator` are interfaces, so backtest is a component swap rather than a rewrite. State shared between producers and consumers is exposed only through read-only interfaces — the type system enforces the read/write split. Strategies never touch brokers directly; everything flows through the bus.

Read the per-phase design specs in [`docs/superpowers/specs/`](docs/superpowers/specs/) for depth.

## Documentation

- **[Documentation site](https://elitekaycy.github.io/qkt/)** — quickstart, DSL grammar, CLI reference, deployment guides, architecture diagrams, and the Dokka API reference.
- [`docs/phases/`](docs/phases/) — per-phase changelogs, the authoritative "what's in qkt today" reference.
- [`QUICKSTART.md`](QUICKSTART.md) — a 5-minute getting-started.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) · [`SECURITY.md`](SECURITY.md) · [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md)

## Status

Pre-1.0 and under active development. Breaking changes can land in minor releases until `1.0.0`; the engine is functional and tested, but the public API isn't yet declared stable. See [`docs/release-process.md`](docs/release-process.md) for versioning.

<details>
<summary><b>The full feature list</b> (every phase)</summary>

- **Tick + candle pipeline** with a deterministic event bus.
- **Multi-strategy support** with per-strategy P&L attribution.
- **Risk engine** — equity tracking, drawdown halts, daily-loss halts, halt-as-state with operator-driven resume.
- **Backtest replay engine** with full reporting: equity curves, Sharpe, Calmar, profit factor, win/loss stats.
- **Parameter sweep harness** — sequential or fixed-pool parallel execution with ranked summaries.
- **Backtest HTML report** — self-contained `report.html` with SVG equity + drawdown charts, Monte Carlo fan, drawdown-period table, per-trade risk.
- **MT5 broker (multi-profile)** — per-broker `mt5-gateway` services; built-in defaults for Exness, ICMarkets, FTMO, Pepperstone; Market + Bracket + native pending-order family (entries, OCO, trailing).
- **Bybit Spot + Linear (USDT)** live trading with reconciliation, rate limiting, connection resilience.
- **TradingView live vendor** (anonymous, free-tier) for paper trading.
- **Multi-source market data** — one strategy can pull different streams from different vendors at once.
- **On-disk content-addressable data store** with Dukascopy auto-fetch and bring-your-own CSV.
- **STACK pyramiding** and **conditional bracketed stacks** (`STACK_AT`) — turn one `BUY`/`SELL` into N price-triggered entries with an optional time fence.
- **CANCEL action + PORTFOLIO** — cancel pending orders from inside a strategy; compose N strategies with regime-gated activation.
- **Portfolio daemon** — `qkt deploy mybook.qkt` fans out into per-child `LiveSession`s with their own ports and logs.
- **DSL `LOG` action**, an **indicator + accessor catalog** (SMA/EMA/WMA/MACD/Bollinger/RSI, `HIGHEST`/`LOWEST`, position accessors), and **bid/ask** in the DSL (`.bid` / `.ask` / `.spread`).
- **Engine state persistence** — daemon state survives restarts.
- **Instrument metadata** — contract size, tick size resolved per instrument.
- **Telegram alerts** — order, halt, and daily-summary notifications.
- **One-shot Docker stack**, a **MkDocs documentation site**, and **editor integrations** for VS Code / Neovim / TextMate.

Each capability links to a full changelog under [`docs/phases/`](docs/phases/).

</details>

<details>
<summary><b>Repository layout</b></summary>

```
src/main/kotlin/com/qkt/
├── app/             entry points: Main, LiveSession, TradingPipeline, IndicatorWarmer
├── backtest/        Backtest, BacktestResult, PerformanceReport, metrics/, report/, sweep/
├── broker/          Broker, PaperBroker, BybitBroker, MT5Broker, CompositeBroker
├── bus/             EventBus
├── candles/         CandleAggregator, CandleHub, TimeWindow
├── cli/             the qkt CLI — command parsing, subcommands, daemon control
├── common/          Clock, Money, Side, IdGenerator, TradingCalendar, TimeRange
├── dsl/             the .qkt DSL — lexer, parser, compiler, evaluator, Kotlin DSL
├── engine/          Engine
├── events/          Event, TickEvent, CandleEvent, SignalEvent, OrderEvent, BrokerEvent
├── execution/       Order, OrderRequest, Trade, OrderType, OrderManager
├── indicators/      indicator catalog — SMA, EMA, WMA, MACD, Bollinger, RSI, ...
├── instrument/      instrument metadata — contract size, tick size, symbol policy
├── marketdata/      Tick, Candle, MarketSource, MarketPriceTracker, TickFeed, data store
├── notify/          notification system — Telegram notifier, event routing
├── persistence/     engine state persistence — state file read/write, recovery
├── pnl/             PnLCalculator, StrategyPnL, PnLProvider
├── positions/       PositionTracker, StrategyPositionTracker, Position
├── risk/            RiskEngine, RiskState, EquityTracker, DrawdownTracker, rules/
├── strategy/        Strategy, StrategyContext, Signal, Mode, WarmupSpec, samples/
└── tools/           operational tooling — audit-ticks and other diagnostics
```

Build and test:

```bash
./gradlew build              # compile + test + ktlint + assemble
./gradlew installDist        # produces build/install/qkt/bin/qkt
./gradlew dockerBuild        # builds qkt:local docker image
./scripts/precheck.sh        # the pre-push checklist
```

</details>

## License

Apache 2.0 — see [LICENSE](LICENSE).
