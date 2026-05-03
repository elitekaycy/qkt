# Phase 2b Backlog

Items remaining after Phase 2a (event bus + multi-strategy + SLF4J) shipped. Phase 2b is the candle layer.

## Phase 2b primary scope

- `Strategy.onCandle(candle, emit)` — extend the `Strategy` interface alongside `onTick`.
- `CandleAggregator` — subscribes to `TickEvent`, emits `CandleEvent` on closed windows. Time-window driven (e.g., 1-minute candles).
- `CandleEvent` — new variant of the `Event` sealed class.
- `TimeWindow` types — likely a small abstraction so we can support 1m, 5m, 1h, etc. without proliferating types.
- Multi-symbol support in the candle path — one aggregator instance per (symbol, window) pair, or a single multi-symbol aggregator. Decision deferred to brainstorming.

Decisions to brainstorm before designing:
- One aggregator instance per symbol+window, or one multi-symbol aggregator?
- Window boundaries: clock-aligned (e.g., 12:00:00, 12:01:00) or first-tick-aligned?
- What happens when a window closes with zero ticks — emit a synthetic candle, or skip?
- How does the existing `EveryNthTickBuyStrategy` interact (it consumes only ticks)? Stays unchanged or gains an `onCandle` no-op?

## Items shipped in Phase 2a (closed)

- Event sealed class + event bus — done (`com.qkt.bus.EventBus`, `com.qkt.events.Event`).
- Multi-strategy support — done (`main()` subscribes each strategy).
- SLF4J logging — done (api + simple, used in engine + bus + main).
- Coroutines decision — deferred (chose single-threaded; revisit when there is a forcing reason).
- Sequence id stamping — done (`MonotonicSequenceGenerator`).

## Items shipped post-Phase-1 (closed)

- `gradle/libs.versions.toml` (version catalog) — done.
- ktlint integration — done.
- README.md, CONTRIBUTING.md, .editorconfig — done.
- Pre-push git hook + installer script — done.
- Project skill (`.claude/skills/qkt/SKILL.md`) — done.

## Carried-over observations (still open)

### Build configuration

- **`kotlin { jvmToolchain(21) }` and `java { toolchain { ... } }` are double-declared.** Defensive but redundant. Trim to one source of truth when convenient.
- **`testLogging.showStandardStreams = true`** is now slightly noisier with SLF4J output. Per CLAUDE.md "Test output must be pristine," revisit if the noise gets in the way during Phase 2b.
- **No `allWarningsAsErrors`** — cheapest forcing function for Kotlin code hygiene. Decide whether to flip on during Phase 2b.
- **`.kotlin/`** not in `.gitignore` — Kotlin 2.x sometimes writes per-project caches there. Add if it ever shows up.

### Code quality

- **`MarketPriceTracker` naming smell** — `Tracker` describes a role, not the thing. Phase 2b touches the candle layer; if a refactor opens the door, consider renaming. Otherwise leave.
- **`EndToEndTest.kt` is 194 lines** (within the 200 hard cap, but close). Phase 2b will likely add cases there. Decide whether to split when adding more.
- **`Signal.toOrder` is duplicated** between `Main.kt` and `EndToEndTest.kt`. Phase 3 risk-engine work will touch this conversion logic — extract to a proper home then.
- **Double-precision floats for prices** (D13). Revisit when Phase 3's P&L math compounds drift.
- **`@PublishedApi internal val subscribers`** in `EventBus.kt` is the standard Kotlin idiom for inline-function access to private state. Worth a brief comment explaining the why for future readers.

### Validation gaps (intentionally absent)

- `MarketPriceTracker.update` accepts `NaN`, `Infinity`, negative prices silently. Phase 1 trusts callers; Phase 3 risk engine will likely surface the need for boundary validation.
- No symbol whitelist — strategies can emit signals for arbitrary symbol strings. Real-broker phase concern.
- No bus-level guard against re-entrant publication of the same event type → `StackOverflowError`. Acceptable per spec; revisit if it bites.
- Subscriber registration during a `publish` would CME. Spec §3 D12 documents this; not actionable until Phase 5 DSL needs runtime registration.

### Observability

- No tracing of strategy → signal → order → trade chain across the bus. Phase 4 backtest replay will likely need event log support (`subscribeAll` or similar).
- No way to inspect engine state at runtime (last tick, last order, etc.).
- `MockTickFeed` is single-symbol. Cross-symbol strategies need either multiple feeds + a multiplexer or a multi-symbol mock. Phase 2b candle work may motivate this.

## Process / open-source readiness items (still open)

- **LICENSE file** — needs your call (MIT vs Apache 2.0 vs BSL). Blocks public-ness.
- **GitHub Actions CI workflow** — `.github/workflows/build.yml` running `./gradlew build` on PRs. Useful when there's a remote.
- **`docs/architecture.md`** — high-level overview that references each phase's spec. Write when Phase 2 fully ships (after 2b).
- **detekt integration** — alongside ktlint, for static analysis (cyclomatic complexity, magic numbers). Optional; ktlint is sufficient for now.
- **Issue templates / PR template** — when the repo gains external contributors.
