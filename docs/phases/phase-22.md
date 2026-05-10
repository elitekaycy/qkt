# Phase 22 — KDoc the public API

## Summary

Phase 22 documents qkt's public Kotlin surface with KDoc so the Dokka site stops being a signature dump and starts being a useful reference. Every Tier 1 public type now carries a class-level paragraph explaining what it is and when to use it, plus per-member KDoc on the methods strategy authors, integrators, and contributors actually call. A new `docs/contributing/kdoc-style.md` style guide codifies what to KDoc and what to skip, and the qkt SKILL has a new rule requiring KDoc on any new public surface in future PRs. Dokka's `reportUndocumented` flag is on so the build surfaces missing KDoc as warnings without failing.

## What's new

- `docs/contributing/kdoc-style.md` — the canonical style guide. Class-level paragraph + one-line summary per member, `@param`/`@return`/`@throws` only when non-obvious, no temporal references, no roadmap, no `@sample` boilerplate.
- KDoc on every Tier 1 type:
    - `com.qkt.bus.EventBus`, `com.qkt.engine.Engine`, `com.qkt.events.Event` + every subtype + `BrokerEvent` + `RiskEvent`
    - `com.qkt.execution.OrderRequest` (with KDoc per shape: Market, Limit, Stop, StopLimit, IfTouched, TrailingStop, OCO, OTO, Bracket, ScaleOut, TimeExit, Stack), `Trade`, `OrderState`, `TimeInForce`, `TrailMode`, `ExpiryAction`, `ScaleOutLeg`, `StackPlan`, `ManagedOrder`, `Signal.toOrderRequest`
    - `com.qkt.broker.Broker` + `OrderModification` + `OrderTypeCapability` + `SubmitAck` + `BrokerStateRecovery`
    - `com.qkt.broker.PaperBroker`, `LogBroker`, `CompositeBroker`
    - `com.qkt.broker.mt5.MT5Broker`, `MT5Client`, `MT5BrokerProfile`, `SymbolPolicy`, `InstrumentSpec`, `MT5BrokerProfileLoader`, `MT5DefaultProfiles`, `MT5Protocol`, `MT5Symbol`, `MT5OrderTranslator`, `MT5PositionPoller`, `MT5StateRecovery`, every wire type in `MT5WireTypes`
    - `com.qkt.broker.bybit.BybitSpotBroker`, `BybitLinearBroker`, `BybitClient`, `BybitApiException`, `BybitConnectException`, `BybitRateLimitException`, `BybitTransport`, `BybitSigner`, `BybitSymbol`, `BybitBalanceTranslator`, `BybitOrderTranslator`, `BybitSpotStateRecovery`, `BybitLinearStateRecovery`
    - `com.qkt.marketdata.Tick`, `Candle`, `MarketPriceProvider`, `MarketPriceTracker`, `TickFeed` + every implementation (`HistoricalTickFeed`, `CsvTickFeed`, `MockTickFeed`, `ConcatenatedTickFeed`, `MergingTickFeed`, `RangeClippedTickFeed`)
    - `com.qkt.strategy.Strategy`, `Signal` + variants, `StrategyContext`, `Mode`, `WarmupSpec` + variants, `Warmable`
    - `com.qkt.dsl.compile.DslCompiledStrategy`, `CandleHub`, `HubKey`; `com.qkt.dsl.parse.Dsl`, `ParseResult`, `ParseError`, `ParsedFile`
    - `com.qkt.app.LiveSession`, `TradingPipeline`, `OrderManager`, `IndicatorWarmer`, `LiveSessionHandle`
    - `com.qkt.cli` every command class + `Args`/`ArgError`/`Config`/`BuildInfo`/`ExitCodes`/`ReportFormat`/`ReportPrinter`
    - `com.qkt.cli.observe.ObservabilityServer`, `EventRing`, `EventEntry`, `Routes`, `PortPrinter`, every DTO in `StatusSnapshot`
- `build.gradle.kts` — Dokka `reportUndocumented = true`. Build still passes; missing KDoc surfaces as warnings.
- `.claude/skills/qkt/SKILL.md` — new rule in §6 Feature documentation: "Every new public type, interface, top-level function, or externally-callable method MUST land with KDoc." PR template gains a `## Docs` section.

## Migration from previous phase

No code or behavior change. The Dokka site at `/api/` now displays prose alongside every signature; readers don't need to chase commits to understand what a class does.

```kotlin
// Before — readers had to infer:
class EventBus(clock: Clock, sequencer: SequenceGenerator)

// After — the contract is at the source:
/**
 * Single-threaded publish/subscribe bus for [Event]s.
 *
 * The backbone of qkt's event-driven pipeline. Every component publishes through one
 * bus instance and subscribes through the same bus instance ...
 */
class EventBus(clock: Clock, sequencer: SequenceGenerator)
```

## Usage cookbook

### Read the rendered docs

After CI deploys, browse at `https://elitekaycy.github.io/qkt/api/`. Tier 1 types land you on a page with a class-level explanation; methods have one-line summaries; parameters have prose where the type alone isn't enough.

### Add KDoc to new code

Per the SKILL rule, any PR that adds public surface includes KDoc:

```kotlin
/**
 * Caches the last N candles seen on a symbol so indicators can reach back without
 * re-aggregating from raw ticks.
 */
class CandleCache(private val capacity: Int) {
    /** Returns the most recent candle for [symbol], or `null` if none cached yet. */
    fun latest(symbol: String): Candle? = ...
}
```

### Check what's still undocumented

`./gradlew dokkaHtml` prints `Undocumented: <fqn>` warnings for every public symbol still missing KDoc. The build doesn't fail — these are visibility only. Treat them as a todo list while writing KDoc for new packages.

### Browse the style guide

```bash
mkdocs serve  # then open http://localhost:8000/contributing/kdoc-style/
```

Every example in the guide compiles — copy-paste a template and adapt.

## Testing patterns

No new tests in Phase 22 — KDoc is documentation, not behavior. Verification is:

- **`./gradlew build`** — KDoc syntax errors fail compilation; the existing build catches them.
- **`./gradlew dokkaHtml`** — must complete successfully. Undocumented warnings are expected for internal types and Tier 2/3 surfaces not yet covered.
- **`mkdocs build --strict`** — link-checks the new `contributing/kdoc-style.md` page.

## Known limitations

- Only Tier 1 (the most-touched public surface) is comprehensively KDoc'd. Tier 2 (backtest reporting types, risk types, portfolio supervisor, indicator catalog) still surfaces undocumented warnings. They land organically as features ship — the SKILL rule prevents new code from regressing.
- Internal compile helpers in `com.qkt.dsl.compile.*` (AstCompiler, ExprCompiler, ActionCompiler, SnapshotStore, etc.) don't carry KDoc. They're implementation details of the DSL compiler and don't have callers outside their package.
- No `@sample` integration yet. The infrastructure (a `samples/` source set wired into Dokka) is in the [Phase 22 spec](../superpowers/specs/2026-05-10-phase22-kdoc-public-api-design.md) and waits for the first sample to need it.
- No CI gate that fails the build on missing KDoc. By design — `reportUndocumented = true` warns, doesn't fail. Flip to fail when Tier 1 → Tier 2 → Tier 3 coverage is complete.

## References

- Spec: [`docs/superpowers/specs/2026-05-10-phase22-kdoc-public-api-design.md`](../superpowers/specs/2026-05-10-phase22-kdoc-public-api-design.md)
- Style guide: [`docs/contributing/kdoc-style.md`](../contributing/kdoc-style.md)
- Dokka: https://kotlinlang.org/docs/dokka-introduction.html
- KDoc syntax: https://kotlinlang.org/docs/kotlin-doc.html
- Phase 21 (the documentation site that renders all of this): [`docs/phases/phase-21.md`](phase-21.md)
