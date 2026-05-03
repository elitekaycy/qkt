# qkt

An event-driven trading engine in Kotlin, with a future SQL-like DSL for writing trading strategies.

## Status

- **Phase 1** — Core engine MVP. Tick generation, strategy interface with callback-emit, signal-to-order conversion, mock broker, deterministic foundations. Shipped.
- Phases 2–5 — event bus, risk engine, backtesting, DSL. Planned.

## What it does today

```
Tick → Engine → Strategy → Signal → Order → MockBroker → Trade
```

A `MockTickFeed` produces 100 seeded random-walk ticks for `XAUUSD`. An `EveryNthTickBuyStrategy` emits a buy signal every 10 ticks. The `Engine` converts each signal into a `MARKET` order and routes it to a `MockBroker`, which fills at the latest tracked price. Filled trades print to stdout.

## Build and run

Requirements: JDK 21 (Gradle's toolchain auto-provisions if not local).

```bash
./gradlew build      # compile + test + assemble
./gradlew test       # tests only
./gradlew run        # main application — prints fills, exits
```

Local pre-push helper:

```bash
./scripts/precheck.sh
```

## Layout

```
src/main/kotlin/com/qkt/
├── common/        Side, Clock, IdGenerator
├── marketdata/    Tick, Candle, TickFeed, MockTickFeed, MarketPriceProvider
├── execution/     OrderType, Order, Trade
├── strategy/      Signal, Strategy, EveryNthTickBuyStrategy
├── broker/        Broker, MockBroker
├── engine/        Engine
└── app/           Main
```

```
docs/
├── superpowers/specs/        per-feature design specs
├── superpowers/plans/        per-feature implementation plans
└── phase<N>-backlog.md       carried-over items per phase
```

## Architecture

Single-threaded event-driven pipeline. Every component is deterministic given its inputs and seeds. `Clock`, `IdGenerator`, and pull-model `TickFeed` are interfaces so backtesting in Phase 4 becomes a component swap, not a rewrite.

Read the Phase 1 design doc at `docs/superpowers/specs/2026-05-03-trading-engine-phase1-design.md` for the full picture.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Conventions are codified in `.claude/skills/qkt/SKILL.md` — read it before opening a PR.

## License

TBD.
