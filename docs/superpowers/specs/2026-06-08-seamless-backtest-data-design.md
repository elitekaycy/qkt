# Seamless backtest data — design

Date: 2026-06-08
Issues: #337 (data completeness), part of #142 (production-readiness)

## Problem

Running a backtest is not seamless and gives no completeness guarantee:

- Acquiring data is a **separate manual step** the user must remember before `qkt backtest`,
  and the only bundled-ish path (the tick store's `DataFetcher`) is an **un-bundled user
  script** — so out of the box, a fresh machine cannot fetch anything. Forget the step and the
  backtest errors on missing days or, worse, runs on whatever partial data is on disk.
- The store tracks coverage at **whole-day granularity** via a manifest: a day is "covered" if
  a file exists. A **truncated or partial** day is treated as complete — never refetched,
  never flagged. No content/quality validation.

Net: a backtest can silently run on incomplete data and produce a clean-looking but wrong
result. For real investor money that is the dangerous failure mode — the report looks fine.

The defining requirement: **a backtest must work with no broker/infra running.** Open a
laptop, `qkt backtest XAUUSD …`, and it Just Works — fetches what it needs from a public
source, reuses cache, fills gaps, and refuses to run on holes.

## Goals

1. `qkt backtest <strategy> --from X --to Y` auto-acquires data with no separate step and **no
   running broker/gateway** — derive what to fetch (broker, symbol, timeframe per stream) from
   the strategy's own `SYMBOLS` declarations.
2. Reuse cached data; fetch only what is missing (idempotent).
3. Validate completeness against the trading calendar and **fail loud** on holes.

## Non-goals (deferred)

- **Gateway and histdata sources.** The `DataFetcher` interface makes a fallback chain
  (`dukascopy → histdata → gateway`) a clean later add. V1 builds **dukascopy only**, which
  covers XAUUSD/FX/metals/indices — the symbols in play. The MT5 gateway becomes an opt-in
  source in a follow-up (broker-exact data when you have the gateway up).
- **True async/background prefetch.** Auto-fetch runs inline with progress output; "seamless"
  = no manual step and no infra, not asynchronous.

## Decisions

### D1 — Default source: dukascopy ticks (public, no infra)

Auto-fetch defaults to **dukascopy** historical tick data.

Rationale:
- **Public, free, no auth, no infra** → genuinely seamless offline. This is the decisive
  property; a gateway-based default fails exactly the case that matters (backtest without the
  broker running).
- **Tick data**, not bars → removes the intra-bar fill-approximation that a bar source would
  impose on stop/limit strategies (hedge-straddle, latch-stack). Strictly better fills.
- Slots into machinery that **already exists**: `DefaultDataStore` already has the
  `materializeMissing` → `DataFetcher` seam (cache-check → fetch missing days → coalesce
  manifest). A real `DukascopyTickFetcher : DataFetcher` drops in as the default; today the
  seam is inert only because the fetcher is an un-bundled script.

Trade-off accepted: dukascopy is an **independent feed**, not your exact broker's ticks — its
prices/spreads differ slightly from your venue. For "does my strategy logic hold on realistic
gold ticks" it is the industry-standard answer; broker-exact validation is the deferred
gateway opt-in.

### D2 — Validation behavior: hard-fail by default, explicit override

On incomplete data the backtest **refuses to run** by default, printing exactly which
day(s)/session-hour(s)/symbol(s) are missing. `--allow-incomplete` proceeds knowingly (e.g. a
genuinely thin holiday week), printing what is being ignored.

Rationale: real money rides on this — the safe default blocks a clean-looking-but-wrong
result, with a deliberate escape hatch.

## Architecture

Three new units, slotting in front of and inside the existing tick store. Stores stay storage;
acquisition and validation are isolated, testable units.

### 1. `DukascopyTickFetcher` (implements existing `DataFetcher`)

`fetch(symbol, day, target)` — downloads dukascopy's 24 hourly `.bi5` files for the UTC day,
decodes, assembles the day's ticks, writes the existing `symbols/{SYM}/{day}.csv.gz` format.

- URL pattern: `https://datafeed.dukascopy.com/datafeed/{INSTRUMENT}/{YYYY}/{MM0}/{DD}/{HH}h_ticks.bi5`
  (`MM0` = zero-indexed month; `HH` = 00–23 GMT).
- Each `.bi5` is an **LZMA** stream of 20-byte big-endian records:
  `int32 msOffsetFromHour, int32 ask, int32 bid, float32 askVol, float32 bidVol`. Prices are
  integer points; divide by the instrument's scale (e.g. XAUUSD ÷1000, EURUSD ÷100000).
- A missing/empty hour (404 or zero-length — e.g. weekend, or a non-session hour) yields no
  ticks for that hour, not an error. The calendar decides whether that hour was expected.
- Adds an LZMA dependency (`org.tukaani:xz`). HTTP via the okhttp already used by
  `Mt5BarFetcher`.

### 2. `DukascopyInstruments`

Small table mapping `qktSymbol` → `(dukascopyName, priceScale, decimals)`. Strips the broker
prefix (`EXNESS:XAUUSD` → `XAUUSD`) and supplies the point scaling. Extensible; an unmapped
symbol fails fast with a clear "no dukascopy mapping for X — add it or use --no-fetch" message.

### 3. `TickCompletenessValidator` (the trust core)

Pure function over what is on disk. Given `(symbol, range)` and the symbol's `TradingCalendar`
(via existing `SymbolCalendars`: fx/crypto/nyse), it walks each **session hour** the calendar
says should be open in the range and checks the stored day file has at least one tick in that
hour. Classifies each day:

- **non-trading** — calendar-closed (weekend/holiday): no data expected, OK.
- **complete** — every expected session hour has ticks.
- **incomplete** — a trading day with one or more empty expected session hours (lists which).
- **missing** — a trading day with no file/ticks at all.

Session-hour granularity matches dukascopy's hourly fetch unit and catches the partial-day
hole that whole-day manifest tracking misses today. Reads day files lazily, short-circuiting an
hour on its first tick. Returns a structured report; no I/O beyond reading the local files.

### 4. `BacktestDataProvisioner`

Orchestrates ensure → validate → repair → re-validate for each `(broker, symbol, timeframe)`
the strategy declares:

1. Configure `DefaultDataStore` with the `DukascopyTickFetcher` and `prefetch` the range
   (existing `materializeMissing` fetches missing whole days; progress printed per day).
2. Run `TickCompletenessValidator`.
3. For any day flagged **incomplete** (e.g. a prior interrupted fetch left it partial), delete
   the day file + manifest entry and refetch once, then re-validate that day.
4. If any day is still **missing/incomplete** → throw `IncompleteDataException` with the
   per-day/hour report, unless `allowIncomplete` (then log the report and continue).

`--no-fetch` skips steps 1 and 3 (offline) but still validates, so a cache-only run is guarded.

## Data flow

```
qkt backtest strat.qkt --from X --to Y
  │
  ├─ parse strategy → ast.streams, each carrying {broker, qktSymbol, timeframe}  (e.g. EXNESS:XAUUSD EVERY 5m)
  │
  ├─ BacktestDataProvisioner.ensure(streams, range, allowIncomplete, fetchEnabled)
  │     for each stream:
  │       DefaultDataStore(fetcher = DukascopyTickFetcher).prefetch(range)   ← cache → dukascopy, progress
  │       report = TickCompletenessValidator.validate(symbol, range, calendar)
  │       repair incomplete days (refetch once), re-validate
  │       if report.hasHoles && !allowIncomplete: throw IncompleteDataException(report)
  │
  └─ Backtest.fromStore(strategies, store, request).run()   ← replays real ticks, unchanged
```

## CLI surface (`qkt backtest`)

- default — auto-fetch missing data from dukascopy, then validate; seamless, no infra.
- `--no-fetch` — do not fetch; use only cached data (still validated).
- `--allow-incomplete` — run despite missing/incomplete days; prints what is ignored.

Existing flags (`--from/--to`, `--data-root`, `--broker`, `--instruments`, …) unchanged.

## Error handling

- **Unmapped symbol:** fail with `no dukascopy mapping for <symbol>; add one or pass --no-fetch`.
- **Network error fetching a day:** fail with the day + reason (do not silently skip).
- **Empty hour from dukascopy:** no ticks recorded; the **calendar** decides if that hour was
  expected, so a holiday is not a false hole and a missing session hour is not hidden.
- **Incomplete after repair:** `IncompleteDataException` with the per-day/hour report; non-zero
  exit. `--allow-incomplete` downgrades to a logged warning.

## Testing

- **`DukascopyTickFetcher`** (unit): decode a captured `.bi5` fixture → exact ticks (offsets,
  bid/ask scaling, volumes); empty/404 hour → no ticks, no throw. No live network in tests.
- **`DukascopyInstruments`** (unit): symbol mapping + scaling; unmapped symbol fails clearly.
- **`TickCompletenessValidator`** (unit, no mocks): real `TradingCalendar` (XAUUSD fx) fixtures
  — full day complete; weekend non-trading; a missing session hour → incomplete; a fully empty
  trading day → missing; a holiday → non-trading.
- **`BacktestDataProvisioner`** (unit): fake `DataFetcher` (no network) serving scripted days —
  fetches only missing, skips present, repairs an injected partial day, throws on a genuine
  hole, `allowIncomplete` proceeds, `--no-fetch` skips fetch but still validates.
- **`BacktestCommand` integration:** end-to-end with a fake fetcher injected — clean range
  runs; a holed range fails without `--allow-incomplete`, passes with it.

## Documented limitations (ship with these stated)

- Dukascopy is an independent feed, not your broker's exact ticks — prices/spreads differ
  slightly from your venue. Broker-exact validation is the deferred gateway opt-in.
- Dukascopy symbol coverage is FX/metals/indices/some-crypto; an unmapped symbol fails fast
  rather than guessing.

## Follow-ups (interface already supports them)

- **Gateway opt-in source** — broker-exact bars/ticks via `Mt5BarFetcher` when the gateway is
  up, as a `--source gateway` chain entry.
- **histdata fallback** — second public source in the chain for days/symbols dukascopy lacks.
- **Shared `BarFetcherFactory`** — DRY the `qkt fetch` fetcher-building when the gateway source
  lands.
- `#336` (cost-aware default broker) and `#338` (metric conventions) remain independent.
