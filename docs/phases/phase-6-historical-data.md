# Phase 6 — Historical data layer

## Summary

Phase 6 replaces in-memory tick lists with a real data subsystem: on-disk content-addressable storage at `~/.qkt/data/`, gzipped CSV format, daily partitioning, k-way merging across symbols, range clipping, and a pluggable fetcher abstraction (Dukascopy via bash bridge, BYO CSV). This is the layer that lets a strategy reference `BACKTEST:BTCUSDT EVERY 1m` and get years of real tick data without thinking about file paths.

`Tick` gains optional `bid` / `ask` / `bidVolume` / `askVolume` fields. The 8-column CSV format becomes the canonical interchange.

## What's new

### Tick + CSV format

- `Tick` extended (backward-compatible — all new fields default to `null`):
  - `bid: BigDecimal?`, `ask: BigDecimal?`, `bidVolume: BigDecimal?`, `askVolume: BigDecimal?`
  - `mid: BigDecimal?` — derived from bid/ask
  - `spread: BigDecimal?` — `ask - bid`
- 8-column CSV schema: `timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume`
  - Trade rows: bid/ask blank
  - Quote rows: `price = mid`

### Feeds

- `CsvTickFeed(path: Path)` — streaming gzip-aware CSV reader. Strict validation; throws on malformed rows.
- `MergingTickFeed(feeds: List<TickFeed>)` — k-way priority-queue merge, deterministic tie-break by feed-list order
- `RangeClippedTickFeed(inner, fromMs, toMs)` — drops out-of-range ticks, stops early at `toMs`
- `TickFeed` now extends `AutoCloseable` (existing impls add no-op `close()`)

### Data store

- `DataStore` at `~/.qkt/data/symbols/{SYMBOL}/{YYYY-MM-DD}.csv.gz`
  - Daily-partitioned, gzipped
  - Per-symbol JSON manifest (`manifest.json`, schema v1)
  - Atomic write-temp-rename
  - Lazy gap-fill (request a missing range → fetcher fills it)
- `DataFetcher` interface — pluggable source
- `ScriptDataFetcher(scriptPath)` — bash bridge; ships with `scripts/fetch-dukascopy.sh` using community `dukascopy-node`
- `HistoricalDataProvider` (high-level query API):
  - `DataCapability` enum: `TICKS`, `CANDLES_INTRADAY`, `CANDLES_DAILY`
  - `StoreHistoricalDataProvider` — Phase 6 impl; enforces look-ahead-bias guard via `require(range.to <= clock.now())`

### Time helpers

- `TimeRange(from, to)` — half-open interval value type
- `TimeContext(clock, zone)` — range factories:
  - `today()`, `yesterday()`, `lastDays(n)`, `previousMonth()`
  - `session(date, openHour, closeHour)` — exchange-session ranges
- `IndicatorMap<T>(factory)` — ergonomic per-symbol indicator wiring (closes the Phase 5 gap)

### Backtest integration

- `Backtest.fromStore(strategies, rules, store, request, candleWindow?, initialTimestamp): BacktestResult` — factory that pulls ticks from the store, builds a `MergingTickFeed`, runs the backtest

### Reductions

- `Sequence<Tick>.maxPrice() / minPrice() / firstPrice() / lastPrice()`
- `Sequence<Candle>.highestHigh() / lowestLow() / firstOpen() / lastClose()`

### Tooling

- `./gradlew rebuildManifest` — recovery for manifest corruption
- `data/sample/` test fixtures — 2 days of EURUSD, XAUUSD, BTCUSD ticks + per-symbol manifests
- `scripts/fetch-dukascopy.sh` — example fetcher
- README "Getting real data" section

## Migration

`TickFeed` is now `AutoCloseable`. Existing implementations need an empty `close()` (or use the default in the interface):

```kotlin
// All Phase 1-5 TickFeed impls add:
override fun close() {}
```

`Backtest` primary constructor signature changed to `feed: TickFeed`. The Phase 4 `Backtest(strategies, rules, ticks: List<Tick>, ...)` shape is preserved via a secondary constructor that wraps the list in `HistoricalTickFeed`.

## Usage cookbook

### Fetch a month of data and backtest

```bash
qkt fetch BTCUSDT --from 2024-01-01 --to 2024-02-01
```

Then in code:

```kotlin
val store = DataStore.openDefault()
val provider = StoreHistoricalDataProvider(store, clock = SystemClock())
val request = HistoricalDataRequest(
    symbols = listOf("BTCUSDT"),
    range = TimeRange.of("2024-01-01", "2024-02-01", zone = ZoneOffset.UTC),
)
val result = Backtest.fromStore(
    strategies = listOf(MyStrategy()),
    rules = emptyList(),
    store = store,
    request = request,
    candleWindow = TimeWindow.ONE_MINUTE,
).run()
```

### Stream a CSV directly (no store)

```kotlin
CsvTickFeed(Path.of("data/btc-2024-jan.csv.gz")).use { feed ->
    while (true) {
        val tick = feed.next() ?: break
        // process tick
    }
}
```

The `.use` block guarantees the file handle closes.

### Multi-symbol merged feed

```kotlin
val feeds = listOf(
    CsvTickFeed(Path.of("data/btc.csv.gz")),
    CsvTickFeed(Path.of("data/eth.csv.gz")),
    CsvTickFeed(Path.of("data/eur.csv.gz")),
)
val merged: TickFeed = MergingTickFeed(feeds)
// merged yields ticks in monotonic timestamp order across all three symbols
```

### Range-clip a feed for walk-forward

```kotlin
val full = CsvTickFeed(Path.of("data/btc-2024.csv.gz"))
val trainWindow = RangeClippedTickFeed(full, fromMs = ts("2024-01-01"), toMs = ts("2024-03-01"))
val testWindow  = RangeClippedTickFeed(full, fromMs = ts("2024-03-01"), toMs = ts("2024-04-01"))
```

(Note: each clipped feed wraps its own reader — they're not shared.)

### Per-symbol indicator wiring

```kotlin
class MultiSymbolStrategy : Strategy {
    private val emas = IndicatorMap { EMA(period = 20) }

    override fun onCandle(c: Candle, ctx: StrategyContext, emit: (Signal) -> Unit) {
        val ema = emas[c.symbol]
        ema.update(c.close)
        if (ema.isReady && c.close > ema.value()!!) {
            emit(Signal.Buy(c.symbol, BigDecimal("0.1")))
        }
    }
}
```

The map lazy-creates per-symbol indicator instances on first access.

### Write a custom fetcher

```kotlin
class PolygonFetcher(private val apiKey: String) : DataFetcher {
    override fun fetch(symbol: String, range: TimeRange): Sequence<Tick> {
        // call Polygon REST, parse, yield Ticks
    }
}

val store = DataStore.openDefault(fetcher = PolygonFetcher(System.getenv("POLYGON_KEY")))
```

The store calls the fetcher on cache miss and writes the result back atomically.

## Testing patterns

- Use the `data/sample/` fixtures for tests that need real(ish) data
- Test atomicity: kill the writer mid-write, verify the partial file isn't visible (the temp-rename guarantees this)
- Test the look-ahead-bias guard: request `range.to > clock.now()`, assert exception

## Known limitations

- **No pure-Kotlin Dukascopy `.bi5` decoder** — uses bash bridge to community `dukascopy-node`. Replaceable.
- **No real-provider implementations** — Polygon, IB, Alpaca, etc. are all DIY. Phase 6 ships the interface, not impls.
- **No `CachingHistoricalDataProvider`** — every query hits the disk store. Fine at qkt scale; would matter at extreme query volumes.
- **No concurrent fetch** — sequential gap-fill. Multi-symbol parallel fetch is a future optimization.
- **No lenient CSV mode** — first malformed row throws. By design — bad data should fail loud.
- **No live `WebSocketTickFeed`** — that lands in Phase 7. Phase 6 is historical-only.

## References

- Spec: [`docs/superpowers/specs/2026-05-04-trading-engine-phase6-design.md`](https://github.com/elitekaycy/qkt/blob/main/docs/superpowers/specs/2026-05-04-trading-engine-phase6-design.md)
