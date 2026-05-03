# Phase 2 Backlog

Items deferred from Phase 1 (and earlier reviews) to consider during Phase 2 design.

## Phase 2 primary scope (per spec §11)

- `Strategy.onCandle(candle, emit)` — extend the `Strategy` interface.
- Candle aggregator — service that consumes ticks and emits candles on time windows.
- `Event` sealed class + event bus — replace direct method calls with typed event dispatch (`TickEvent`, `CandleEvent`, `SignalEvent`, `OrderEvent`, `TradeEvent`).
- Multiple strategies / strategy registry — one engine, many strategies, fan-out per event type.
- Coroutines + `Channel`/`Flow` evaluation — decide whether the event bus warrants moving off the single-threaded blocking loop.
- SLF4J logging — replace the `println` in `Main.kt` with structured logging.

## Carried-over observations (from Phase 1 reviewers)

These were flagged during Phase 1 implementation and reviews. None block Phase 1; revisit when Phase 2 work touches the relevant area.

### Build configuration

- **`kotlin { jvmToolchain(21) }` and `java { toolchain { ... } }` are double-declared** in `build.gradle.kts`. Defensive but redundant. Trim to one source of truth when convenient.
- **`testLogging.showStandardStreams = true`** will get noisy as the project grows. Per CLAUDE.md "Test output must be pristine," revisit when production logging starts producing chatty output.
- **No `allWarningsAsErrors`** — cheapest forcing function for Kotlin code hygiene. Decide whether to flip on for Phase 2.
- **`.kotlin/`** not in `.gitignore` — Kotlin 2.x sometimes writes per-project caches there. Add if it ever shows up.
- **No version catalog (`gradle/libs.versions.toml`)** — for a long-term project, centralizing version pins is the standard pattern. Migrate when Phase 2 adds new dependencies.

### Code quality

- **`MarketPriceTracker` naming smell** — `Tracker` describes a role, not the thing (it's a latest-price-per-symbol cache). Renaming now would touch 5+ files for marginal gain. Consider if a Phase 2 refactor opens the door anyway.
- **`EngineTest.kt` is 154 lines**, slightly above the spec's "~150 line" soft cap. Trade-off of full real-types testing without mocks. If Phase 2 adds more engine tests, decide whether to split by behavior (e.g., `EngineRoutingTest`, `EngineSignalConversionTest`) or accept the size.
- **Double-precision floats for prices** (D13). Revisit when Phase 3's P&L math compounds drift. Conversion to `BigDecimal` is mechanical but touches every data class.
- **Test redundancy** — `MarketPriceTrackerTest` has two tests that overlap (`update then lastPrice` and `tracks multiple symbols`). Acceptable as documentation; trim if it bothers you when adding more tests.

### Validation gaps (intentionally absent in Phase 1)

- `MarketPriceTracker.update` accepts `NaN`, `Infinity`, negative prices silently. Phase 1 trusts callers; Phase 3 risk engine will likely surface the need for boundary validation.
- `Order.timestamp` not validated against `Order.id`'s implied creation time. Trivia.
- No symbol whitelist — strategies can emit signals for arbitrary symbol strings. Real-broker phase concern.

### Observability

- No tracing of strategy → signal → order → trade chain. Phase 4+ when metrics arrive.
- No way to inspect engine state at runtime (last tick, last order, etc.). Future debugging concern.

## Process / open-source readiness items

(Tracked separately in the project skill at `.claude/skills/qkt/SKILL.md` once it exists.)

- LICENSE file (need to choose: MIT vs Apache 2.0 vs BSL — elitekaycy's call).
- README.md with clear "what this is" + "how to run" for external readers.
- CONTRIBUTING.md (will reference the project skill).
- ktlint or detekt integration — pick one, wire it into the build.
- Pre-push git hook running `./gradlew build` (catches broken commits before they leave the machine).
- GitHub Actions CI workflow — at minimum `./gradlew build` on PRs.
