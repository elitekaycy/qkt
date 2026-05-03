# Phase 3b Backlog

Items remaining after Phase 3 (risk engine + position tracking) shipped. Phase 3b is the P&L + BigDecimal migration layer.

## Phase 3b primary scope

- **`Position.avgEntryPrice`** — extend `Position` data class with weighted-average entry price. Required for unrealized P&L = `(currentPrice - avgEntryPrice) * quantity`.
- **`PositionTracker.apply` weighted-average update** — buys update both quantity and avgEntryPrice (weighted by lot size). Sells reduce quantity; avgEntryPrice unchanged. Closing to zero resets.
- **`PnLCalculator` or `PnLProvider`** — computes realized P&L per trade (close vs avgEntryPrice) and unrealized P&L per open position (current tracker price vs avgEntryPrice). Aggregate across symbols.
- **`PnLEvent` (or similar)** — event published on P&L state change, OR query-on-demand interface. Decision point for brainstorming.
- **`BigDecimal` migration** — replace `Double` for prices and quantities throughout: `Tick`, `Order`, `Trade`, `Candle`, `Signal`, `MarketPriceProvider`, `MarketPriceTracker`, `MockBroker`, `MockTickFeed`, `EveryNthTickBuyStrategy`, `CandleAggregator`, `Position`, `PositionTracker`, `MaxPositionSize`, all tests. Big mechanical pass.

Decisions to brainstorm:
- BigDecimal migration first or P&L first? They can ship in either order, but BigDecimal touches every data class — doing it first means P&L code is born correct. Doing P&L first lets us defer the migration if it turns out we don't need precise numbers yet.
- P&L: pushed via `PnLEvent` on every state change, or pulled via `PnLProvider.realized()`/`unrealized()`?
- Aggregate-across-symbols P&L: a separate query, or computed by summing per-symbol?
- BigDecimal scope: prices only, or quantities too? Lot sizes (`size: Double`) in `Signal.Buy`?
- BigDecimal scale + rounding mode: standardize as one constant or per-call?
- Keep `Double`-based `MaxPositionSize` for now (Phase 3) or migrate as part of 3b?

## Items shipped in Phase 3 (closed)

- `Position`, `PositionProvider`, `PositionTracker` — done (`com.qkt.positions`).
- `RiskRule`, `Decision`, `RiskEngine` — done (`com.qkt.risk`).
- `MaxPositionSize`, `MaxOpenPositions` rules — done (`com.qkt.risk.rules`).
- `RiskRejectedEvent` variant — done.
- Risk gate between SignalEvent and OrderEvent — done.
- TradeEvent → tracker.apply registered FIRST — done; ordering verified by EndToEndTest.
- 90 tests; `./gradlew run` produces 3 FILLED + 7 REJECTED + Done.

## Items shipped in Phase 2b (closed)

- `CandleEvent` + `CandleAggregator` — done.
- `TimeWindow` value class — done.
- `Strategy.onCandle` default no-op — done.
- `MockTickFeed.tickIntervalMs` — done.

## Items shipped in Phase 2a (closed)

- Event bus + multi-strategy + SLF4J — done.
- Sequence id stamping — done.

## Items shipped post-Phase-1 (closed)

- Version catalog, ktlint, README, CONTRIBUTING, .editorconfig, pre-push hook, project skill — all done.

## Carried-over observations (still open)

### Build configuration

- **`kotlin { jvmToolchain(21) }` + `java { toolchain { ... } }` double-declared.** Trim when convenient.
- **`testLogging.showStandardStreams = true`** is increasingly noisy with FILLED/REJECTED/CANDLE output. Per CLAUDE.md "Test output must be pristine," revisit during 3b.
- **No `allWarningsAsErrors`** — decide whether to flip on during 3b.
- **`.kotlin/`** not in `.gitignore` — add if it appears.

### Code quality

- **`EndToEndTest.kt` is ~340 lines** (above the project skill's 200-line cap). Phase 3 added the `positions` field, extended `wirePipeline` with rules, and added 3 risk tests. Phase 3b will likely add P&L tests. **High-priority refactor for 3b**: split into `EndToEndTest` (basic pipeline) + `EndToEndCandleTest` + `EndToEndRiskTest`.
- **`MarketPriceTracker` naming smell** — symmetry with `PositionTracker` makes "Tracker" feel more justified now. Probably leave both.
- **`Signal.toOrder` is duplicated** between `Main.kt` and `EndToEndTest.kt`. Phase 3b's BigDecimal migration touches every Order construction — extract to a shared home then.
- **`PositionTracker.apply` uses floating-point equality `next == 0.0`.** Phase 3b's BigDecimal migration should use `compareTo(BigDecimal.ZERO) == 0` or a tolerance check.
- **`Position` will gain `avgEntryPrice` in 3b.** All existing `PositionTrackerTest` cases need updating to assert on the new field.

### Validation gaps (intentionally absent)

- `MarketPriceTracker.update` accepts `NaN`, `Infinity`, negative prices silently. Phase 3b BigDecimal migration is the natural place to add boundary validation.
- No symbol whitelist — strategies emit signals for arbitrary symbols. Real-broker phase concern.
- No bus-level guard against re-entrant publication of the same event type → `StackOverflowError`. Acceptable.
- Subscriber registration during a `publish` would CME. Phase 5 DSL may force a fix.

### Observability

- No tracing of strategy → signal → order → trade chain across the bus. Phase 4 backtest replay will need event log support (`subscribeAll`).
- No way to inspect engine state at runtime (last tick, positions, P&L) outside of the tracker reads. Phase 3b P&L reporting will partially address.
- `MockTickFeed` is single-symbol. Cross-symbol strategies still need pre-seeded tracker tests.

## Process / open-source readiness items (still open)

- **LICENSE file** — needs your call (MIT vs Apache 2.0 vs BSL). Blocks public-ness.
- **GitHub Actions CI workflow** — useful when there's a remote.
- **`docs/architecture.md`** — high-level overview. Worth writing once Phase 3b ships (full risk + P&L runtime).
- **detekt integration** — alongside ktlint. Optional.
- **Issue templates / PR template** — when the repo gains external contributors.
