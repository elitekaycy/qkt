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

After cloning, wire up the git hook so `precheck.sh` runs automatically before every push:

```bash
./scripts/install-hooks.sh
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

## Getting real data

Phase 6 ships a content-addressable on-disk data store at `~/.qkt/data/` (override via the `QKT_DATA_HOME` environment variable). Strategies query this store via `Backtest.fromStore(...)` or directly via `DataStore.openFeed(...)`. The store is empty on first install — populate it by either:

### Option 1: Auto-fetch via the bundled bash script (Dukascopy)

Requires Node.js 18+ and `dukascopy-node`:

```bash
npm install -g dukascopy-node
```

Then wire the fetcher when constructing your store:

`ScriptDataFetcher.dukascopy(...)` requires an absolute path to the bundled script. Use `Path.of(System.getProperty("user.dir"), "scripts/fetch-dukascopy.sh")` if you run from the qkt repo root, or hard-code the absolute path to your installed copy.

```kotlin
val store = DefaultDataStore.fromEnv(
    fetcher = ScriptDataFetcher.dukascopy(Path.of("/path/to/qkt/scripts/fetch-dukascopy.sh")),
)
val backtest = Backtest.fromStore(
    strategies = listOf(MyStrategy()),
    rules = listOf(MaxPositionSize("EURUSD", Money.of("3"))),
    store = store,
    request = DataRequest(
        symbols = listOf("EURUSD"),
        from = Instant.parse("2024-01-15T00:00:00Z"),
        to = Instant.parse("2024-01-22T00:00:00Z"),
    ),
).run()
```

The store will fetch missing days lazily on first run and cache them locally. Subsequent runs over the same range hit the cache and never touch the network.

### Option 2: Bring your own data

Drop CSV files matching the qkt schema into `~/.qkt/data/symbols/{SYMBOL}/{YYYY-MM-DD}.csv` (or `.csv.gz`). Schema:

```
timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume
```

Then call `DefaultDataStore.fromEnv().rebuildManifests()` to populate manifests from the file list.

### Option 3: Sample data

The repo ships a tiny fixture set at `data/sample/symbols/` that mirrors the production layout. Point a store at it for tests and demos:

```kotlin
val store = DefaultDataStore(root = Path.of("data/sample"))
```

## Live trading (TradingView)

Phase 7 ships a live runtime alongside the historical backtest. The first vendor is TradingView via WebSocket, anonymous mode (no login required, free-tier symbol coverage). Live ticks feed the same `TradingPipeline` your backtest uses; the only difference is the `TickFeed` (live) and the `Clock` (system time).

### Run the bundled demo

```bash
./gradlew runLiveDemo
```

The demo connects to TradingView, subscribes to `OANDA:EURUSD`, `OANDA:XAUUSD`, and `BINANCE:BTCUSDT`, runs `BreakoutOfYesterdayHighStrategy` against EURUSD, and prints fills via the existing `MockBroker` to stdout. Press Ctrl-C to stop.

### Construct a LiveSession in your own code

```kotlin
import com.qkt.app.LiveSession
import com.qkt.candles.TimeWindow
import com.qkt.common.SystemClock
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.strategy.samples.BreakoutOfYesterdayHighStrategy

fun main() {
    val source = TradingViewMarketSource.connect()
    val handle =
        LiveSession(
            strategies = listOf(BreakoutOfYesterdayHighStrategy("OANDA:EURUSD")),
            source = source,
            symbols = listOf("OANDA:EURUSD"),
            candleWindow = TimeWindow.ONE_MINUTE,
            clock = SystemClock(),
            calendar = TradingCalendar.fxDefault(),
        ).start()
    handle.awaitTermination(java.time.Duration.ofMinutes(10))
}
```

### Run the live smoke test (manual)

The smoke test connects to real TradingView and asserts that at least one tick arrives within 30 seconds. Tagged `@Tag("e2e")` and excluded from the default `./gradlew test` run.

```bash
./gradlew test -PincludeTags=e2e
```

### TradingView vendor constraints

- **Anonymous mode only.** Logged-in / premium symbol coverage and higher rate limits are not implemented in Phase 7. See `docs/phases/phase-7-live-runtime.md` for the deferred-work list.
- **No tick history.** TradingView does not expose historical ticks. `TradingViewMarketSource.ticks(...)` throws `UnsupportedDataException`. Use bar history via `bars(...)` for warmup.
- **Symbol form is `EXCHANGE:SYMBOL`.** TV does not accept bare symbols (`EURUSD`). Use `OANDA:EURUSD`, `BINANCE:BTCUSDT`, etc. The `TradingViewMarketSource.supports(symbol)` check rejects symbols that do not match the form.
- **Rate limits apply.** The anonymous quote-session subscribes to a few dozen symbols comfortably. For larger universes, use `CompositeMarketSource` to route per-asset-class to dedicated vendors (e.g. Binance for crypto, OANDA for FX).
- **Paper trading only.** All fills go through `MockBroker`, which fills at the latest in-process price. Real-broker integrations land in Phase 7d / 8 behind a `LiveBroker` interface.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Conventions are codified in `.claude/skills/qkt/SKILL.md` — read it before opening a PR.

## License

TBD.
