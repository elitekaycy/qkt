# Phase 4 Backlog

Items remaining after Phase 3b (P&L + BigDecimal migration) shipped. Phase 4 is the backtest replay engine.

## Phase 4 primary scope

- **Event log** — record every event published on the bus (or a configurable subset) into a replayable structure. Likely a `subscribeAll`-style hook or a sidecar component that observes all event types.
- **Replay engine** — given a recorded event log, drive the engine through the same sequence of events to reproduce identical outcomes. Same trades, same fills, same P&L.
- **Determinism guarantees** — the replay must produce byte-for-byte identical output to the live run that recorded the events. Same FixedClock, same MonotonicSequenceGenerator, same SequentialIdGenerator state, same random seeds.
- **Time skip** — replay should not run at wall-clock speed. Compress thousands of seconds of trading into milliseconds for fast strategy iteration.
- **Historical data ingestion** — beyond replay of our own recorded events, the ability to drive an engine from external historical tick data (CSV, JSON, etc.). Bridging external data into our `TickFeed`.
- **Performance metrics on replay** — total return, drawdown, sharpe, etc. Or at least foundational numbers.

Decisions to brainstorm:
- Event log shape: in-memory list, file-based (JSON/binary), or both?
- Recording: opt-in subscriber registered in `main()` or always-on?
- Replay scope: replay TickEvents only (let the engine re-derive everything), or replay every event including SignalEvent/OrderEvent/TradeEvent?
- Backtest determinism: how do we capture the random seed of the original run for replay?
- Strategy state: do strategies persist across replay/live? (No — fresh strategy instance per run, deterministic from input events.)
- Performance: how fast must replay be? 1M ticks/second? Just "fast enough for fast iteration"?
- Reporting: include performance metrics in scope, or defer to Phase 4b?

## Items shipped in Phase 3b (closed)

- `Money` helper object (`MathContext.DECIMAL64`, `SCALE = 8`, `HALF_EVEN`, `ZERO`, `of(String/Long/Int)`) — done.
- BigDecimal migration across all monetary + quantity fields — done. 14 production files + 12 test files.
- `Position.avgEntryPrice` — done.
- `PositionTracker.apply(trade): BigDecimal` with weighted-average + flipping — done; verified by 4 new tests.
- `PnLProvider` + `PnLCalculator` (realized + unrealized + total) — done; 8 tests.
- `OrderFactory.kt` shared `Signal.toOrder` — done.
- Main.kt formatted output (`stripTrailingZeros`, `setScale(2)` for P&L) — done.
- 107 tests; `./gradlew run` shows clean P&L output.

## Items shipped in Phase 3 (closed)

- Risk engine, position tracking, MaxPositionSize, MaxOpenPositions, RiskRejectedEvent — done.

## Items shipped in Phase 2b (closed)

- CandleAggregator, TimeWindow, Strategy.onCandle, MockTickFeed.tickIntervalMs — done.

## Items shipped in Phase 2a (closed)

- Event bus, multi-strategy, SLF4J, sequence ids — done.

## Items shipped post-Phase-1 (closed)

- Version catalog, ktlint, README, CONTRIBUTING, .editorconfig, pre-push hook, project skill — all done.

## Carried-over observations (still open)

### Code quality

- **`MaxPositionSize` reason string shows `|4.00000000|` instead of `|4|`.** Cosmetic. Fix: use `.stripTrailingZeros().toPlainString()` in the reason format. ~3 lines. Easy follow-up.
- **`EndToEndTest.kt` is now ~360 lines** (well above the project skill's 200-line cap). High-priority refactor: split into `EndToEndTest` (basic pipeline) + `EndToEndCandleTest` + `EndToEndRiskTest` + `EndToEndPnLTest`. Phase 4 should split this when it adds backtest tests.
- **`MarketPriceTracker` naming** — symmetric to `PositionTracker` now. Convention is settled; leave.
- **`Signal.toOrder` deduplicated** — done in 3b via `OrderFactory.kt`.

### Build configuration

- **`kotlin { jvmToolchain(21) }` + `java { toolchain { ... } }` double-declared.** Trim when convenient.
- **`testLogging.showStandardStreams = true`** is increasingly noisy. Revisit during Phase 4.
- **No `allWarningsAsErrors`** — decide whether to flip on during Phase 4.
- **`.kotlin/`** not in `.gitignore` — add if it appears.

### Validation gaps (intentionally absent)

- `MarketPriceTracker.update` accepts NaN, Infinity, negative prices silently. (BigDecimal can't be NaN/Infinity, but negative prices still allowed.)
- No symbol whitelist.
- No bus-level guard against re-entrant publication of the same event type → `StackOverflowError`.
- Subscriber registration during a `publish` would CME.
- `PositionTracker.apply` does not validate trade fields beyond what flowed through risk gate.

### Observability

- No tracing of strategy → signal → order → trade chain across the bus. **Phase 4 backtest replay is the natural place to add `subscribeAll` hook or event-log support.**
- No way to inspect engine state at runtime beyond reading trackers directly. (Phase 3b PnLProvider + PositionProvider help.)
- `MockTickFeed` is single-symbol. Cross-symbol strategies still need pre-seeded tracker tests OR a multi-symbol mock. Phase 4's historical data ingestion may motivate a real multi-symbol feed.

## Process / open-source readiness items (still open)

- **LICENSE file** — needs your call (MIT vs Apache 2.0 vs BSL). Blocks public-ness.
- **GitHub Actions CI workflow** — `.github/workflows/build.yml` running `./gradlew build` on PRs.
- **`docs/architecture.md`** — high-level overview that references each phase's spec. Now is a great time (post-3b) since the runtime is mature.
- **detekt integration** — alongside ktlint. Optional.
- **Issue templates / PR template** — when the repo gains external contributors.
