# qkt — Trading Engine Phase 6 Design

**Date:** 2026-05-04
**Author:** elitekaycy (with Claude as pair)
**Status:** Approved (brainstorming complete; implementation plan pending)
**Scope:** Phase 6 of the qkt trading platform. Replaces synthetic in-memory tick feeds with a real, scalable, multi-symbol historical-data subsystem. Ships a content-addressable on-disk store with daily-partitioned gzipped CSVs, per-symbol JSON manifests, lazy gap-fill via a pluggable `DataFetcher`, and a unified provider-agnostic `HistoricalDataProvider` query interface. Strategies + DSL get a single data-access surface that works identically in backtest and live trading. Engine core, indicator catalog, risk engine, and existing strategies remain unchanged. Ships 8-column unified Tick CSV schema (trade + quote rows in one format), `MergingTickFeed` k-way merge for multi-symbol streams, multi-symbol-aware `CandleAggregator` (fixes a latent Phase 5 bug), `IndicatorMap<T>` helper, `TimeRange` + `TimeContext` range factories, and `Backtest.fromStore(...)` ergonomic factory.
**Phase 5 baseline:** [`docs/superpowers/specs/2026-05-03-trading-engine-phase5-design.md`](2026-05-03-trading-engine-phase5-design.md)

---

## 1. Vision (long horizon, for context only)

qkt is an event-driven trading runtime in Kotlin that will eventually expose a SQL-like DSL for trading strategies. Phase 1 shipped the core engine. Phase 2a/2b shipped the event bus, multi-strategy, candles. Phase 3/3b shipped risk + positions + P&L on a `BigDecimal` foundation. Phase 4 shipped the strategy backtest harness. Phase 5 shipped indicators and the `Rule` framework. Phase 6 ships the data layer — the missing piece between "synthetic test data" and "real backtests on real markets."

Data design principle: **strategy code is identical across backtest and live**. The strategy never knows whether its ticks come from a CSV file, a websocket, or an in-memory list. It never knows whether its historical queries hit a local cache, a remote REST API, or a broker's historical-data endpoint. Three abstractions (`TickFeed`, `HistoricalDataProvider`, `Clock`) carry every difference between modes; concrete implementations swap at construction. The DSL phase compiles strategies once and runs them in either mode.

Cross-symbol design principle: **all symbols flow through one timestamp-sorted stream**. In backtest, k-way merge over per-symbol files produces a single deterministic sequence. In live, async coroutines (Phase 8) ingest in parallel and serialize into one channel that drains into the single-threaded engine core. Either way, the engine sees one ordered stream of `TickEvent`s with a `symbol` field; cross-asset strategies hold per-symbol state and read any symbol's state at any moment.

This document is **Phase 6 only**.

## 2. Phase 6 — what we are building

A complete data subsystem. Concrete deliverables:

1. **`Tick` extended** with optional `bid`, `ask`, `bidVolume`, `askVolume` fields and `mid` / `spread` derived accessors. Existing `price` field stays canonical.

2. **8-column unified CSV schema** `timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume`. Trade-source rows leave bid/ask blank; quote-source rows leave price blank (reader fills `price = mid`).

3. **`CsvTickFeed(path)`** — streaming, gzip-aware, strict-validation row reader. Auto-detects `.csv` vs `.csv.gz`.

4. **`MergingTickFeed(feeds)`** — k-way priority-queue merge of N child feeds; deterministic tie-break by feed-list-order on equal timestamps.

5. **`RangeClippedTickFeed(inner, fromMs, toMs)`** — drops out-of-range ticks; closes inner feed early on hitting `to`.

6. **`TickFeed : AutoCloseable`** — lifecycle widening; existing impls add no-op `close()`.

7. **`CandleAggregator` regression tests** — verifies the existing per-symbol `Map<String, MutableCandle>` (already shipped in Phase 2b) handles cross-symbol scenarios correctly. No code change to the aggregator itself.

8. **`IndicatorMap<T>(factory)`** — ergonomic per-symbol indicator wiring. `rsiMap.get("XAUUSD").update(close)`.

9. **`DataStore`** — on-disk content-addressable cache at `~/.qkt/data/symbols/{SYMBOL}/{YYYY-MM-DD}.csv.gz`. Daily-partitioned, gzipped, per-symbol JSON manifest with `kotlinx.serialization` and atomic write-temp-rename. Lazy gap-fill via `DataFetcher`. `./gradlew rebuildManifest` recovery.

10. **`DataFetcher` + `ScriptDataFetcher`** — interface + bash-shell impl shelling to `scripts/fetch-dukascopy.sh` (uses community `dukascopy-node`). Pluggable for future pure-Kotlin or remote-API fetchers.

11. **`HistoricalDataProvider`** unified provider-agnostic query interface with `DataCapability` flags. **`StoreHistoricalDataProvider(store, clock)`** Phase 6 impl backed by `DataStore`. Look-ahead-bias enforcement via `require(range.to <= clock.now())`.

12. **Reduction extensions:** `Sequence<Tick>.maxPrice() / minPrice() / firstPrice() / lastPrice()`, `Sequence<Candle>.highestHigh() / lowestLow() / firstOpen() / lastClose()`, `Sequence<BigDecimal>.runThrough(indicator)` for pipe-style queries.

13. **`TimeRange(from, to)`** value type (half-open `from < to`) + **`TimeContext(clock, zone)`** range factories (`yesterday`, `lastDays(n)`, `previousMonth`, `session(date, h1, h2)`, `sessionToday`, `sessionYesterday`, etc.).

14. **`Backtest.fromStore(strategies, rules, store, request, ...)`** factory plumbs all of the above into the existing engine.

15. **`scripts/fetch-dukascopy.sh`** — committed bash example fetcher.

16. **`data/sample/`** — committed test fixtures mirroring production layout (`symbols/{SYMBOL}/{YYYY-MM-DD}.csv` + `manifest.json`). Plain CSV (debuggable, git-diff-friendly), hand-curated.

17. **README "Getting real data"** section.

End state: 167 → ~242 tests, all green. `./gradlew run` output unchanged (Main.kt unchanged). Phase 5 acceptance criteria still hold.

**Phase 6 does NOT include** (deferred):

- Pure-Kotlin Dukascopy fetcher (`.bi5` decoder + LZMA decompression) — Phase 6b.
- `PolygonHistoricalDataProvider` / `IbHistoricalDataProvider` / `AlpacaHistoricalDataProvider` / `TiingoHistoricalDataProvider` / `BinanceHistoricalDataProvider` — drop in behind same interface, separate per-provider integration projects.
- `CachingHistoricalDataProvider(remote, store)` — Phase 6b once a real remote provider exists.
- Concurrent / parallel fetch — Phase 6b (no coroutines yet).
- Lenient validation mode — strict-only in Phase 6.
- Indicator catalog growth (`PreviousDayHigh`, `SessionHigh`, `PriceHistory`, `RollingHigh`, `Crossover`/`Crossunder`, etc.) — Phase 6b/7.
- Live `WebSocketTickFeed` — Phase 8.
- Live `BrokerHistoricalDataProvider` + real broker — Phase 8.
- Coroutines anywhere in the engine — Phase 8.
- Network mocking, performance benchmarks — out of scope.
- LICENSE, GitHub Actions CI, `docs/architecture.md`, `EndToEndTest.kt` split, `MaxPositionSize` reason cleanup — carried backlog from prior phases.

## 3. Decisions log

| # | Decision | Why |
|---|---|---|
| D1 | **Engine core stays single-threaded synchronous.** No coroutines in Phase 6. | Determinism, single-writer state mutation, single coherent strategy view per event. Coroutines deferred to Phase 8 at the IO boundary. LMAX Disruptor / Aeron pattern. |
| D2 | **Multi-symbol via k-way merge for backtest.** Live multiplex deferred to Phase 8. | Backtest = global timestamp-sorted stream from N feeds. Live = parallel ingestion → channel → single-threaded core. Same engine consumer. |
| D3 | **`CandleAggregator` is already multi-symbol-aware** (`Map<String, MutableCandle>` keyed by `tick.symbol`, present since Phase 2b). Phase 6 adds regression tests for cross-symbol scenarios; no refactor. | Verified by reading current implementation. Earlier brainstorm assumption of a "latent bug" was wrong. |
| D4 | **`IndicatorMap<T>` helper** for ergonomic per-symbol indicator wiring. | Strategies that hold "RSI on gold + EMA on silver + VWAP on EURUSD" don't write 30 lines of `when (symbol)` boilerplate. Optional — Phase 5 strategies still own indicators directly. |
| D5 | **`Tick` extended with optional `bid`, `ask`, `bidVolume`, `askVolume`.** `price` stays canonical. | Dukascopy and most quote feeds are bid/ask-native. Adding optional fields gives quote-aware strategies access without breaking trade-only strategies. Existing 167 tests unchanged. |
| D6 | **No `MultiSymbolStrategy` base class.** Strategies stay free to organize state however they want. | Avoids inheritance trap. `IndicatorMap<T>` covers the common case via composition. |
| D7 | **CSV first; `.bi5` decoder deferred** to follow-up phase. | Tests can hand-write CSV inline. `.bi5` requires LZMA + format gotchas + Dukascopy CDN URL knowledge — separate integration project. Users can convert via `dukascopy-node` today. |
| D8 | **CSV schema: 8 columns** `timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume`. Header required. Reader fills `price = mid` when only bid/ask given. | Unifies trade-source and quote-source CSVs in one schema. Backward compatible with `Tick.price`. |
| D9 | **`CsvTickFeed` (per-file) + `MergingTickFeed` (k-way merge composition).** Tie-break by feed-list-order on equal timestamps. | Indivisible parts. Composes for "one big file," "per-symbol files," or "mixed real + synthetic." `HistoricalTickFeed(List<Tick>)` retained. |
| D10 | **Streaming default; eager-load helper for tests.** `TickFeed` widens to `AutoCloseable`. | A year of EURUSD ticks is 5GB — must stream. `HistoricalTickFeed.fromCsv(path)` for tests that want eager replay. `Backtest.run()` wraps feed in `use { }`. |
| D11 | **`CandleAggregator(window, bus)` API unchanged.** Internal map keyed by symbol. Tie-break on simultaneous candle emission: `String.compareTo` on symbol. | Bug fix internal to the class; no caller changes. Single-symbol path stays cheap. |
| D12 | **`QKT_DATA_HOME` env, defaults to `~/.qkt/data/`.** Repo `data/sample/` is separate (test fixtures). | Cross-project shared cache by default; CI/dev override via env. Matches `~/.gradle`, `~/.cargo` conventions. |
| D13 | **Daily partition + gzip CSV.** `~/.qkt/data/symbols/{SYMBOL}/{YYYY-MM-DD}.csv.gz`. UTC day boundaries. Empty-day files (weekend/holiday) are header-only. | Gap-fill = day-set arithmetic. ~5× compression on tick data. File sizes streamable in ms. Empty-day convention distinguishes "not fetched" from "market closed." |
| D14 | **JSON manifest per symbol via `kotlinx.serialization`.** Atomic writes (write-temp-rename). `schemaVersion: 1`. `./gradlew rebuildManifest` recovery. | Per-symbol manifest = independent failure domains. Inspectable. Schema versioning enables future migrations. Safety net via filesystem scan. |
| D15 | **`DataFetcher` interface + `ScriptDataFetcher` shelling to `scripts/fetch-dukascopy.sh`** (uses `dukascopy-node`). Fetcher optional. | Smooth UX now without `.bi5` decoder. Phase 6b drops in pure-Kotlin `DukascopyFetcher` behind same interface. No fetcher → clear error on miss. |
| D16 | **Caller passes `DataRequest` to `Backtest.fromStore(...)`.** `Strategy` interface unchanged. Phase 7 DSL synthesizes `DataRequest`. | Hand-written strategies don't need a `dataNeeds()` method; DSL synthesizes it later from declared symbols. Clean evolution path. |
| D17 | **`DataRequest.from`/`to` are nullable.** Null = intersection of cached ranges across requested symbols. Empty cache + null defaults → error pointing at explicit dates. | Run-over-everything-cached UX. Mixed null/explicit also handled. Explicit ranges trigger fetch as today. |
| D18 | **Time clipping at feed layer via `RangeClippedTickFeed`.** Day files streamed as-is. | Day files are MB-sized; full-scan cost is microseconds. Pre-seek incompatible with gzip. ~15-line wrapper, reusable on any `TickFeed`. |
| D19 | **Engine supports cross-symbol / cross-time / session-anchored strategies.** Specific catalog indicators (`PrevDayHigh`, `SessionHigh`, `PriceHistory`, `RollingHigh`, `Crossover`/`Crossunder`) deferred to 6b/7. | Architecture is general. Catalog grows incrementally as use cases demand. No engine refactor needed for any of these patterns. |
| D20 | **`HistoricalDataProvider` unified query interface** with `DataCapability` flags (`TICKS`, `CANDLES_INTRADAY`, `CANDLES_DAILY`). `StoreHistoricalDataProvider` Phase 6 default. Look-ahead bias enforced via `require(to <= clock.now())`. | Provider-agnostic. Polygon, IB, Alpaca, Tiingo, Binance drop in later behind same interface. Look-ahead protection makes backtest results trustworthy. |
| D21 | **`TickFeed` and `HistoricalDataProvider` are separate interfaces.** Streaming = `next()`-driven. Query = range-based `Sequence<T>`. | Different shapes for different access patterns. Both share `DataStore` as the physical backing layer in backtest. |
| D22 | **`DataStore` is persistence-only.** Not a `HistoricalDataProvider` itself; one provider's backend. `CachingHistoricalDataProvider(remote, store)` composes them when remote providers exist. | Layered architecture. Three orthogonal abstractions: `DataStore` (persistence), `TickFeed` (streaming), `HistoricalDataProvider` (query). |
| D23 | **`TimeRange` (half-open `from..to`) + `TimeContext` helpers** — `today()`, `yesterday()`, `lastDays(n)`, `previousMonth()`, `session(date, h1, h2)`, etc. Constructed once per run with engine clock. | Common range patterns ergonomic in hand-written Kotlin and DSL. Mode-agnostic — same calls work in backtest (engine clock) and live (wall clock). |
| D24 | **Live and backtest interface-identical.** Strategy code unchanged across modes. Implementations of `TickFeed`, `HistoricalDataProvider`, `Clock`, `Broker` swap at construction. | DSL compiles once, runs in either mode. Same on-disk `DataStore` works as backtest source and live cache. |
| D25 | **Strict validation on CSV ingestion.** Crash on first malformed row with file:line:content. Empty-day files OK. Lenient mode deferred. | Project skill §8: "Crash on it; don't silently filter." Determinism. Bad rows signal real bugs that need attention. |
| D26 | **`CandleAggregator` is unbounded** — no upfront symbol declaration. Map grows as new symbols arrive. | Validation lives at the ingestion boundary (`CsvTickFeed`). Aggregator trusts what reaches it. Memory bound implicit (~80 bytes/symbol). |
| D27 | **`data/sample/` mirrors production layout.** `data/sample/symbols/{SYMBOL}/{YYYY-MM-DD}.csv` + `manifest.json`. Plain CSV (debuggable, git-diff-friendly). Hand-curated, committed once. | Tests construct real `DataStore` against sample. No special-cased fixtures shape. Onboarding signal: contributors see what their `~/.qkt/data/` will look like. |

## 4. Architecture

### 4.1 Layered overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Strategy / DSL (Phase 5/7)                      │
│  consumes:                                                           │
│    • TickFeed              (streaming, "next tick please")          │
│    • HistoricalDataProvider (query, "ticks in this range please")   │
│    • TimeContext           (range factories — yesterday, last 30d)  │
│    • IndicatorMap<T>        (per-symbol indicator state)            │
└────────────────────────────────┬────────────────────────────────────┘
                                 │
   ┌─────────────────────────────┼─────────────────────────────┐
   ▼                             ▼                             ▼
┌──────────────┐      ┌──────────────────────┐      ┌──────────────────┐
│   TickFeed   │      │HistoricalDataProvider│      │    TimeContext   │
│  (streaming) │      │      (query)         │      │   (range util)   │
└──────┬───────┘      └──────────┬───────────┘      └────────┬─────────┘
       │                         │                           │
   ┌───┴────┐         ┌──────────┴───────────┐            uses Clock
   ▼        ▼         ▼                      ▼
┌─────┐ ┌──────┐ ┌──────────┐         ┌──────────────┐
│ Csv │ │Merge │ │Store-    │         │ Polygon /    │
│Tick │ │Tick  │ │Historical│         │ IB / Alpaca  │
│Feed │ │Feed  │ │DataProv. │         │ ... (future) │
└──┬──┘ └──┬───┘ └─────┬────┘         └──────────────┘
   │       │            │
   └───────┴────────────┴──────────────┐
                                       ▼
                              ┌──────────────────┐
                              │    DataStore     │
                              │  (persistence)   │
                              │ ~/.qkt/data/     │
                              │   symbols/       │
                              │     EURUSD/      │
                              │       manifest   │
                              │       2024-01-15.csv.gz
                              └────────┬─────────┘
                                       ▼
                              ┌──────────────────┐
                              │   DataFetcher    │
                              │  (gap-fill)      │
                              │ ScriptDataFetcher│
                              │  → bash script   │
                              │  → dukascopy-node│
                              └──────────────────┘
```

### 4.2 Three orthogonal abstractions

| Interface | Shape | Purpose | Phase 6 impl | Future impls |
|---|---|---|---|---|
| `TickFeed` | `next(): Tick?` | Forward-only stream | `CsvTickFeed`, `MergingTickFeed`, `RangeClippedTickFeed`, retained `HistoricalTickFeed`/`MockTickFeed` | `WebSocketTickFeed` (Phase 8) |
| `HistoricalDataProvider` | `ticks(symbol, range): Sequence<Tick>` | Random-access range query, look-ahead-protected | `StoreHistoricalDataProvider` | `PolygonHistoricalDataProvider`, `IbHistoricalDataProvider`, `AlpacaHistoricalDataProvider`, `CachingHistoricalDataProvider` (all Phase 6b+) |
| `DataStore` | `dayFile`, `manifest`, `materializeMissing` | Persistence + manifests + gap-fill orchestration | `DefaultDataStore` | (FS is sufficient) |

### 4.3 Component dependencies (Phase 6 delta)

```
common/TimeRange.kt      ──▶ (none — leaf)
common/TimeContext.kt    ──▶ common/Clock, common/TimeRange

marketdata/Tick.kt                   ──▶ common (Money), java.math.BigDecimal
marketdata/TickFeed.kt               ──▶ AutoCloseable (java.lang)
marketdata/CsvTickFeed.kt            ──▶ marketdata/TickFeed, marketdata/Tick, common/Money, java.util.zip.GZIPInputStream
marketdata/MergingTickFeed.kt        ──▶ marketdata/TickFeed, marketdata/Tick, java.util.PriorityQueue
marketdata/RangeClippedTickFeed.kt   ──▶ marketdata/TickFeed, marketdata/Tick
marketdata/HistoricalTickFeed.kt     ──▶ marketdata/TickFeed, marketdata/Tick, marketdata/CsvTickFeed (factory)

marketdata/store/DataRoot.kt         ──▶ java.nio.file.Path
marketdata/store/DataRequest.kt      ──▶ java.time.Instant
marketdata/store/Manifest.kt         ──▶ kotlinx.serialization.Serializable
marketdata/store/ManifestStore.kt    ──▶ marketdata/store/Manifest, kotlinx.serialization.json.Json
marketdata/store/DataStore.kt        ──▶ marketdata/TickFeed, marketdata/store/{DataRequest, Manifest}
marketdata/store/DefaultDataStore.kt ──▶ marketdata/store/{DataStore, Manifest, ManifestStore, DataFetcher, DataRoot}, marketdata/* feeds
marketdata/store/DataFetcher.kt      ──▶ java.nio.file.Path, java.time.LocalDate
marketdata/store/ScriptDataFetcher.kt ──▶ marketdata/store/DataFetcher

marketdata/history/HistoricalDataProvider.kt    ──▶ marketdata/Tick, marketdata/Candle, common/TimeRange, candles/TimeWindow
marketdata/history/StoreHistoricalDataProvider.kt ──▶ marketdata/history/HistoricalDataProvider, marketdata/store/DataStore, common/Clock, marketdata/CsvTickFeed
marketdata/history/Reductions.kt                ──▶ marketdata/{Tick, Candle}, indicators/Indicator

candles/CandleAggregator.kt          ──▶ unchanged externally (internal Map<String, InProgressCandle>)
indicators/IndicatorMap.kt           ──▶ indicators/Indicator
app/Backtest.kt                       ──▶ marketdata/store/DataStore, marketdata/store/DataRequest (additive: fromStore factory)
```

No new top-level packages outside `com.qkt.marketdata.{store, history}`. No cycles.

### 4.4 What changes from Phase 5

**Modified:**
- `marketdata/Tick.kt` — adds 4 nullable fields + `mid`/`spread` accessors.
- `marketdata/TickFeed.kt` — extends `AutoCloseable`.
- `marketdata/HistoricalTickFeed.kt` — adds `fromCsv` factory + no-op `close()`.
- `app/Backtest.kt` — primary constructor switches to `feed: TickFeed`; secondary constructor with `ticks: List<Tick>` wraps in `HistoricalTickFeed` for backward compat. Adds `fromStore(...)` factory.
- `app/TradingPipeline.kt` — may need minor adjustment if it references `feed` lifecycle (verified during implementation).
- `build.gradle.kts` + `gradle/libs.versions.toml` — add `kotlinx-serialization-json` + serialization Kotlin plugin.

**Unchanged from spec assumption:**
- `candles/CandleAggregator.kt` — already multi-symbol-aware; only adds regression tests.

**New:**
- `common/TimeRange.kt`, `common/TimeContext.kt`.
- `marketdata/CsvTickFeed.kt`, `marketdata/MergingTickFeed.kt`, `marketdata/RangeClippedTickFeed.kt`, `marketdata/ConcatenatedTickFeed.kt`.
- `marketdata/store/` package: `DataRoot`, `DataRequest`, `Manifest`, `ManifestStore`, `DataStore`, `DefaultDataStore`, `DataFetcher`, `ScriptDataFetcher`.
- `marketdata/history/` package: `HistoricalDataProvider`, `StoreHistoricalDataProvider`, `Reductions`.
- `indicators/IndicatorMap.kt`.

**New tests:** mirror per §7.

**New non-source:** `data/sample/`, `data/.gitignore`, `scripts/fetch-dukascopy.sh`, README "Getting real data" section.

### 4.5 What does NOT change

- `Engine`, `EventBus`, all event types — unchanged.
- `Strategy` interface, `Signal`, `EveryNthTickBuyStrategy`, `EmaCrossoverStrategy` — unchanged.
- `RiskRule`, `RiskEngine`, `MaxPositionSize`, `MaxOpenPositions` — unchanged.
- `MarketPriceProvider`, `PositionTracker`, `PnLCalculator`, `MockBroker`, `Money`, `Clock`/`FixedClock`, `IdGenerator`, `SequenceGenerator` — unchanged.
- `MockTickFeed`, `OrderFactory.toOrder` — unchanged.
- `TimeWindow` (interface unchanged; may add `.daily()` factory).
- `TradingPipeline`, `BacktestResult`, `TradeRecord` — unchanged.
- `Main.kt` — unchanged. `./gradlew run` produces identical output to Phase 5.
- All Phase 5 indicator catalog (8 indicators + Rule + sample strategy) — unchanged.
- All 167 Phase 1-5 tests — pass unchanged.

## 5. File layout

```
src/main/kotlin/com/qkt/
├── app/
│   ├── Main.kt                                  # unchanged
│   ├── TradingPipeline.kt                       # unchanged
│   ├── Backtest.kt                              # MODIFIED — adds fromStore factory
│   ├── BacktestResult.kt                        # unchanged
│   └── TradeRecord.kt                           # unchanged
├── broker/                                      # unchanged
├── bus/                                         # unchanged
├── candles/
│   ├── CandleAggregator.kt                      # unchanged (already multi-symbol)
│   └── TimeWindow.kt                            # unchanged
├── common/
│   ├── Money.kt                                 # unchanged
│   ├── Clock.kt                                 # unchanged
│   ├── IdGenerator.kt                           # unchanged
│   ├── Side.kt                                  # unchanged
│   ├── TimeRange.kt                             # NEW
│   └── TimeContext.kt                           # NEW
├── engine/                                      # unchanged
├── events/                                      # unchanged
├── execution/                                   # unchanged
├── indicators/
│   ├── Indicator.kt                             # unchanged
│   ├── Rule.kt                                  # unchanged
│   ├── IndicatorMap.kt                          # NEW
│   └── catalog/                                 # unchanged
├── marketdata/
│   ├── Tick.kt                                  # MODIFIED — bid/ask/bidVolume/askVolume + mid/spread
│   ├── Candle.kt                                # unchanged
│   ├── MarketPriceProvider.kt                   # unchanged
│   ├── MockTickFeed.kt                          # unchanged
│   ├── TickFeed.kt                              # MODIFIED — extends AutoCloseable
│   ├── HistoricalTickFeed.kt                    # MODIFIED — fromCsv factory
│   ├── CsvTickFeed.kt                           # NEW
│   ├── MergingTickFeed.kt                       # NEW
│   ├── RangeClippedTickFeed.kt                  # NEW
│   ├── ConcatenatedTickFeed.kt                  # NEW (internal helper)
│   ├── store/
│   │   ├── DataRoot.kt                          # NEW
│   │   ├── DataRequest.kt                       # NEW
│   │   ├── Manifest.kt                          # NEW
│   │   ├── ManifestStore.kt                     # NEW
│   │   ├── DataStore.kt                         # NEW
│   │   ├── DefaultDataStore.kt                  # NEW
│   │   ├── DataFetcher.kt                       # NEW
│   │   └── ScriptDataFetcher.kt                 # NEW
│   └── history/
│       ├── HistoricalDataProvider.kt            # NEW
│       ├── StoreHistoricalDataProvider.kt       # NEW
│       └── Reductions.kt                        # NEW
├── pnl/                                         # unchanged
├── positions/                                   # unchanged
├── risk/                                        # unchanged
└── strategy/                                    # unchanged

src/test/kotlin/com/qkt/
├── app/
│   ├── BacktestTest.kt                          # MODIFIED — adds fromStore tests
│   ├── BacktestFromStoreTest.kt                 # NEW (end-to-end)
│   └── EndToEndTest.kt                          # unchanged (still over cap; carry-over)
├── candles/
│   └── CandleAggregatorTest.kt                  # MODIFIED — multi-symbol scenarios
├── common/
│   ├── TimeRangeTest.kt                         # NEW
│   └── TimeContextTest.kt                       # NEW
├── indicators/
│   ├── IndicatorMapTest.kt                      # NEW
│   └── RuleTest.kt + catalog/                   # unchanged
├── marketdata/
│   ├── TickTest.kt                              # NEW
│   ├── CsvTickFeedTest.kt                       # NEW
│   ├── MergingTickFeedTest.kt                   # NEW
│   ├── RangeClippedTickFeedTest.kt              # NEW
│   ├── ConcatenatedTickFeedTest.kt              # NEW (or rolled into MergingTickFeedTest)
│   ├── HistoricalTickFeedTest.kt                # MODIFIED — adds fromCsv test
│   ├── store/
│   │   ├── DataRootTest.kt                      # NEW
│   │   ├── ManifestTest.kt                      # NEW
│   │   ├── DefaultDataStoreTest.kt              # NEW
│   │   ├── ScriptDataFetcherTest.kt             # NEW
│   │   └── DataStoreIntegrationTest.kt          # NEW
│   └── history/
│       ├── StoreHistoricalDataProviderTest.kt   # NEW
│       └── ReductionsTest.kt                    # NEW
└── ...

data/
├── .gitignore                                   # NEW
└── sample/
    └── symbols/
        ├── EURUSD/
        │   ├── manifest.json
        │   ├── 2024-01-15.csv
        │   └── 2024-01-16.csv
        ├── XAUUSD/
        │   ├── manifest.json
        │   ├── 2024-01-15.csv
        │   └── 2024-01-16.csv
        └── BTCUSD/
            ├── manifest.json
            ├── 2024-01-15.csv
            └── 2024-01-16.csv

scripts/
└── fetch-dukascopy.sh                           # NEW (executable)

README.md                                        # MODIFIED — "Getting real data" section
build.gradle.kts                                 # MODIFIED — kotlinx-serialization
gradle/libs.versions.toml                        # MODIFIED — serialization version
```

## 6. Component contracts

### 6.1 `Tick` (modified)

```kotlin
data class Tick(
    val symbol: String,
    val price: BigDecimal,
    val timestamp: Long,
    val volume: BigDecimal? = null,
    val bid: BigDecimal? = null,
    val ask: BigDecimal? = null,
    val bidVolume: BigDecimal? = null,
    val askVolume: BigDecimal? = null,
) {
    val mid: BigDecimal? get() = ...    // (bid + ask) / 2 when both present
    val spread: BigDecimal? get() = ... // ask - bid when both present
}
```

### 6.2 CSV format

```
timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume
1714000000000,XAUUSD,2400.50,1.5,,,,
1714000000123,EURUSD,1.0842,,1.0841,1.0843,2.0,1.5
1714000000200,XAUUSD,2400.55,2.0,2400.54,2400.56,5.0,3.0
```

Reader rules:
- Header row required, exact match.
- `timestamp` (Long) and `symbol` (non-empty) always required.
- At least one of `price` OR (`bid` AND `ask`) required. If only bid/ask, reader sets `price = mid`.
- Empty fields → null.
- Strict timestamp non-decreasing within file.
- Bid > ask, negative prices/volumes, unparseable BigDecimals → throw with file:line:reason.

### 6.3 On-disk store layout

```
$QKT_DATA_HOME or ~/.qkt/data/
└── symbols/
    └── EURUSD/
        ├── manifest.json
        ├── 2024-01-15.csv.gz
        ├── 2024-01-16.csv.gz
        └── ...
```

`manifest.json`:
```json
{
  "schemaVersion": 1,
  "schema": "qkt-csv-v1",
  "symbol": "EURUSD",
  "ranges": [
    {"from": "2024-01-15", "to": "2024-01-17"}
  ],
  "lastUpdated": "2026-05-04T14:23:00Z"
}
```

`ranges`: half-open day intervals (`from` inclusive, `to` exclusive). Sorted, non-overlapping, coalesced.

### 6.4 SPI signatures

```kotlin
interface TickFeed : AutoCloseable {
    fun next(): Tick?
    override fun close() {}
}

interface DataStore {
    val root: Path
    fun manifest(symbol: String): Manifest
    fun dayFile(symbol: String, day: LocalDate): Path?
    fun openFeed(request: DataRequest): TickFeed
    fun prefetch(request: DataRequest)
    fun rebuildManifests()
}

interface DataFetcher {
    fun fetch(symbol: String, day: LocalDate, target: Path)
}

enum class DataCapability { TICKS, CANDLES_INTRADAY, CANDLES_DAILY }

interface HistoricalDataProvider {
    val capabilities: Set<DataCapability>
    fun ticks(symbol: String, range: TimeRange): Sequence<Tick>
    fun candles(symbol: String, window: TimeWindow, range: TimeRange): Sequence<Candle>
}

class TimeRange(val from: Instant, val to: Instant) { /* half-open, from < to */ }

class TimeContext(clock: Clock, zone: ZoneId = ZoneOffset.UTC) {
    fun now(): Instant
    fun today(): TimeRange
    fun yesterday(): TimeRange
    fun lastHours(n: Long): TimeRange
    fun lastDays(n: Long): TimeRange
    fun lastWeeks(n: Long): TimeRange
    fun thisWeek(): TimeRange
    fun lastWeek(): TimeRange
    fun thisMonth(): TimeRange
    fun previousMonth(): TimeRange
    fun thisYear(): TimeRange
    fun previousYear(): TimeRange
    fun session(date: LocalDate, startHour: Int, endHour: Int): TimeRange
    fun sessionToday(startHour: Int, endHour: Int): TimeRange
    fun sessionYesterday(startHour: Int, endHour: Int): TimeRange
}

class IndicatorMap<T : Indicator<*>>(factory: () -> T) {
    fun get(symbol: String): T
    fun has(symbol: String): Boolean
    fun symbols(): Set<String>
    fun all(): Map<String, T>
}
```

## 7. Test plan

Total target: **~75 new tests** → 167 + 75 = ~242 tests after Phase 6.

### 7.1 Unit tests (~55)

- `TickTest` — 1 test: `mid` and `spread` accessors.
- `CsvTickFeedTest` — ~14 tests: format parsing (trade-row, quote-row, full 8-col, gzipped, plain, empty data file, skip empty lines), 11 negative validation tests, close test.
- `MergingTickFeedTest` — ~6 tests: interleave, tie-break, N-feeds, empty feed, close propagation, single-feed passthrough.
- `RangeClippedTickFeedTest` — ~4 tests.
- `HistoricalTickFeedTest` — ~2 new tests (fromCsv).
- `TimeRangeTest` — ~3 tests.
- `TimeContextTest` — ~10 tests with `FixedClock`.
- `ManifestTest` + `ManifestStoreTest` — ~8 tests.
- `DataRootTest` — ~2 tests.
- `DefaultDataStoreTest` — ~10 tests.
- `ScriptDataFetcherTest` — ~4 tests with fake bash script.
- `StoreHistoricalDataProviderTest` + `ReductionsTest` — ~10 tests.
- `IndicatorMapTest` — ~4 tests.
- `CandleAggregatorTest` — ~5 new tests (multi-symbol scenarios).

### 7.2 Integration tests (~12)

- `DataStoreIntegrationTest` — ~6 tests against real `data/sample/`.
- `StoreHistoricalDataProviderIntegrationTest` — ~3 tests.
- `BacktestFromStoreTest` — ~3 tests including determinism check.

### 7.3 Sample data

`data/sample/symbols/{EURUSD, XAUUSD, BTCUSD}/{2024-01-15, 2024-01-16}.csv` + per-symbol `manifest.json`. ~200 ticks total, ~7KB. Hand-curated to cover: multi-symbol merge, day boundaries, empty Saturday (BTC), trade vs quote schemas, intra-day clipping.

### 7.4 Determinism guarantees

- Same CSV input + same backtest config → byte-identical `BacktestResult` (asserted by re-running).
- `MergingTickFeed` tie-break is order-stable.
- `IndicatorMap` insertion order preserved (`mutableMapOf` returns `LinkedHashMap`).
- `CandleAggregator` simultaneous emissions tie-break alphabetically.

## 8. Error handling

All validation crashes loudly with `file:line:reason`-style messages. See per-component table in design discussion (§6 of brainstorm). Key principles:

- Strict CSV validation; no lenient mode in Phase 6.
- Manifest corruption → clear error pointing at `./gradlew rebuildManifest`.
- Look-ahead bias enforced via `require(range.to <= clock.now())`.
- Missing data + no fetcher → clear error pointing at `DataFetcher` configuration or explicit fetch.
- Validation lives at the ingestion boundary; internal calls trust their inputs.

## 9. Acceptance criteria

### 9.1 Functional
- `Tick` extended; `TickFeed` widened to `AutoCloseable`; all new feeds (`CsvTickFeed`, `MergingTickFeed`, `RangeClippedTickFeed`, `ConcatenatedTickFeed`) work per spec.
- `CandleAggregator` aggregates per-symbol with no API change.
- `IndicatorMap` provides per-symbol lazy instantiation.
- `TimeRange` + `TimeContext` provide range factories.
- `DataStore` + `DefaultDataStore` + `Manifest` + `DataFetcher` + `ScriptDataFetcher` ship per spec.
- `HistoricalDataProvider` + `StoreHistoricalDataProvider` + reductions ship.
- `Backtest.fromStore(...)` factory wires everything.
- `./gradlew rebuildManifest` recovery works.

### 9.2 Repo / data
- `data/sample/` mirrors production layout with EURUSD + XAUUSD + BTCUSD fixtures.
- `data/.gitignore` configured.
- `scripts/fetch-dukascopy.sh` committed and executable.
- README "Getting real data" section.

### 9.3 Build
- `kotlinx-serialization-json` + plugin added.
- `./gradlew build` clean (compile + test + ktlint).
- Test count: 167 → ~242.

### 9.4 Tests
- Per §7 inventory: ~55 unit + ~12 integration + ~5 end-to-end = ~75 new.
- Negative test per validation rule.
- Determinism test asserts byte-identical re-runs.

### 9.5 Behavioral
- `./gradlew run` output unchanged from Phase 5 (Main.kt unchanged).
- All Phase 1-5 tests still pass.
- Phase 5 strategies (`EveryNthTickBuyStrategy`, `EmaCrossoverStrategy`) compile unchanged.
- Empty-day files handled gracefully.
- Re-running cached backtest → zero fetcher invocations.

### 9.6 Forward-compatibility
- Future `HistoricalDataProvider` impls (Polygon, IB, Alpaca, Tiingo, Binance) drop in without engine changes.
- Future `DataFetcher` impls (pure-Kotlin Dukascopy etc.) drop in without `DataStore` changes.
- Live mode (Phase 8) swaps `TickFeed` + `HistoricalDataProvider` + `Clock` + `Broker` impls only; strategy code unchanged.

### 9.7 Out of scope (verified absent)
- No `.bi5` decoder code in `src/main`.
- No HTTP client in `src/main`.
- No coroutines in production.
- No new third-party deps beyond `kotlinx-serialization-json`.
- No live-trading code paths.
- No additions to Phase 5 indicator catalog.

## 10. Out of scope (explicit deferrals)

### 10.1 Data fetchers (Phase 6b)
- Pure-Kotlin `DukascopyFetcher` (`.bi5` + LZMA + bid/ask reconstruction).
- `PolygonHistoricalDataProvider`, `AlpacaHistoricalDataProvider`, `TiingoHistoricalDataProvider`, `IbHistoricalDataProvider`, `BinanceHistoricalDataProvider`.
- `CachingHistoricalDataProvider(remote, store)`.

### 10.2 Indicator catalog growth (6b/7)
- `PreviousDayHigh/Low/Open/Close`.
- `SessionHigh/Low/Open/Close(symbol, startHour, endHour)`.
- `SessionPrice(symbol, hour, minute)`.
- `PriceHistory(symbol, retention)`.
- `RollingHigh/Low(period)`.
- `DailyCandle(symbol)`.
- `Crossover(a, b)` / `Crossunder(a, b)` Rule variants.

### 10.3 Engine / runtime (Phase 8)
- Concurrent / parallel fetch (needs coroutines).
- `WebSocketTickFeed` (live).
- Live broker historical data provider.
- `LiveBroker` + real broker integration.
- Coroutines anywhere in engine.

### 10.4 Data quality / tooling (later)
- Lenient CSV mode.
- Mid-fetch corruption detection (atomic per-fetch tempfile + rename).
- Cache TTL / staleness detection.
- Inter-process write coordination.
- Symbol whitelist (opt-in `CandleAggregator(window, knownSymbols)`).
- `scripts/build-sample-data.sh` regenerator.
- GitHub Actions CI workflow.
- Performance benchmarking.
- `./gradlew prefetch` / `./gradlew listManifests` Gradle tasks.

### 10.5 Carried-forward backlog (still open)
- LICENSE choice.
- `docs/architecture.md`.
- `EndToEndTest.kt` split (over 200-line cap).
- `MaxPositionSize` reason cleanup (`|4.00000000|` → `|4|`).
- detekt integration.
- Issue / PR templates.

### 10.6 DSL prerequisites covered by Phase 6
| DSL feature | Phase 6 capability |
|---|---|
| `ticks(symbol, range)` syntax | `HistoricalDataProvider.ticks` ✓ |
| `candles(symbol, window, range)` syntax | `HistoricalDataProvider.candles` ✓ |
| `yesterday`, `last 30 days`, `session(...)` | `TimeContext` factories ✓ |
| `max`, `min`, `first`, `last` | Reduction extension functions ✓ |
| `between` clauses | `TimeRange` half-open semantics ✓ |
| Per-symbol indicator references | `IndicatorMap<T>` ✓ |
| `from "..." to "..."` backtest config | `Backtest.fromStore(DataRequest(symbols, from, to))` ✓ |
| Mode-agnostic compiled artifact | Live/backtest interface-identical ✓ |

DSL needs only parser, AST, code generator. None of those touch Phase 6 work. Phase 7 is parser/codegen only; engine plumbing is done.
