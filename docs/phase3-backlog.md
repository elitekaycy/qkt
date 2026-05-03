# Phase 3 Backlog

Items remaining after Phase 2b (candle aggregator + `Strategy.onCandle` + tick-interval feed) shipped. Phase 3 is the risk + position + P&L layer.

## Phase 3 primary scope

- **Risk engine** — intercepts `SignalEvent` (or `OrderEvent`), validates against limits, approves or rejects. Spec D11 from Phase 1: `RiskEngine.approve(order: Order): Boolean`.
- **Position tracking** — running per-symbol holdings updated on `TradeEvent`. Probably a `PositionTracker` with read interface, similar shape to `MarketPriceTracker` (read/write split via type system).
- **P&L (profit and loss)** — realized P&L on closes; unrealized P&L using current tracker price. Per-symbol and aggregate.
- **`BigDecimal` migration** — replace `Double` for prices and money math. Phase 1 D13 deferred this; Phase 3's P&L compounding makes float drift unacceptable.

Decisions to brainstorm:
- Where does risk live in the event pipeline? Between Signal→Order? Between Order→Trade? As a separate gate?
- Static config for risk limits, or pluggable rules?
- Position tracker write side: who updates it (a subscriber, the broker, the engine)?
- P&L: per-trade pre-computed or computed on-demand from positions?
- BigDecimal scope: only prices, or quantities too? Order sizes?
- Migration: in-place rewrite of every data class, or new types alongside?

## Items shipped in Phase 2b (closed)

- `CandleEvent` + `CandleAggregator` — done (`com.qkt.candles`).
- `TimeWindow` value class with `windowStartFor`/`windowEndFor` — done.
- `Strategy.onCandle` default no-op — done.
- `MockTickFeed.tickIntervalMs` — done.
- Aggregator-before-strategies wiring invariant — verified by EndToEndTest.
- 65 tests; clock-aligned candles producing on `./gradlew run`.

## Items shipped in Phase 2a (closed)

- Event sealed class + event bus — done.
- Multi-strategy support — done.
- SLF4J logging — done.
- Sequence id stamping — done.

## Items shipped post-Phase-1 (closed)

- `gradle/libs.versions.toml` (version catalog) — done.
- ktlint integration — done.
- README.md, CONTRIBUTING.md, .editorconfig — done.
- Pre-push git hook + installer script — done.
- Project skill (`.claude/skills/qkt/SKILL.md`) — done.

## Carried-over observations (still open)

### Build configuration

- **`kotlin { jvmToolchain(21) }` and `java { toolchain { ... } }` are double-declared.** Defensive but redundant. Trim to one source of truth when convenient.
- **`testLogging.showStandardStreams = true`** is now noisier with SLF4J + candle output. Per CLAUDE.md "Test output must be pristine," revisit if Phase 3 adds more chatty logging.
- **No `allWarningsAsErrors`** — cheapest forcing function for Kotlin code hygiene. Decide whether to flip on during Phase 3.
- **`.kotlin/`** not in `.gitignore` — Kotlin 2.x sometimes writes per-project caches there. Add if it appears.

### Code quality

- **`MarketPriceTracker` naming smell** — `Tracker` describes a role, not the thing. Phase 3 will introduce a `PositionTracker`; consider whether to rename both for symmetry, or accept the convention now.
- **`EndToEndTest.kt` is 278 lines** (above the project skill's 200-line soft cap). Phase 3 will likely add more end-to-end cases. Decide whether to split: `EndToEndTest` (basic pipeline) + `EndToEndCandleTest` + `EndToEndRiskTest`. Not blocking but warranted.
- **`Signal.toOrder` is duplicated** between `Main.kt` and `EndToEndTest.kt`. Phase 3's risk-engine work touches this conversion logic — extract to a proper home then.
- **Double-precision floats for prices** (D13 from Phase 1). Phase 3's P&L math compounds drift. Migrate to `BigDecimal` (or fixed-point integers) as part of the risk/P&L work.
- **`MutableCandle` (private nested class in CandleAggregator)** — works. If Phase 3 adds more aggregators with similar shape (e.g., position aggregator), consider whether the pattern deserves a shared base.

### Validation gaps (intentionally absent)

- `MarketPriceTracker.update` accepts `NaN`, `Infinity`, negative prices silently. Phase 3 risk engine will likely surface the need for boundary validation.
- No symbol whitelist — strategies emit signals for arbitrary symbols. Real-broker phase concern.
- No bus-level guard against re-entrant publication of the same event type → `StackOverflowError`. Acceptable per Phase 2a D11.
- Subscriber registration during a `publish` would CME. Phase 5 DSL may force a fix.
- `CandleAggregator` does not validate ticks (out-of-order, NaN, negative). Phase 1 trust-callers policy carries forward.

### Observability

- No tracing of strategy → signal → order → trade chain across the bus. Phase 4 backtest replay will need event log support (`subscribeAll` or similar).
- No way to inspect engine state at runtime (last tick, positions, last order, etc.). Phase 3's `PositionTracker` will partially address this.
- `MockTickFeed` is single-symbol. Cross-symbol strategies still need pre-seeded tracker tests. Phase 3 risk + position tests likely motivate a multi-symbol mock.

## Process / open-source readiness items (still open)

- **LICENSE file** — needs your call (MIT vs Apache 2.0 vs BSL). Blocks public-ness.
- **GitHub Actions CI workflow** — `.github/workflows/build.yml` running `./gradlew build` on PRs. Useful when there's a remote.
- **`docs/architecture.md`** — high-level overview that references each phase's spec. Worth writing once Phase 3 ships (full event-driven trading runtime).
- **detekt integration** — alongside ktlint, for static analysis. Optional.
- **Issue templates / PR template** — when the repo gains external contributors.
