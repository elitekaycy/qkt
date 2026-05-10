# Phase 22 — KDoc the public API

**Status:** queued, post-Phase-21.

## Why

Phase 21 shipped a Dokka build, but only 7 of 281 source files have any KDoc. The generated API reference today is a signature dump — useful as a directory but not as documentation. To make the site live up to its homepage promise ("Look it up: DSL grammar, CLI commands, **config schema, API reference**"), the anchor types people actually consume need real KDoc.

## What's in scope

KDoc for the **public-facing types** — the ones a strategy author, integrator, or new contributor touches. Not every internal helper.

### Tier 1 — must have KDoc

Core engine seams:

- `com.qkt.broker.Broker` (interface + every concrete impl: `PaperBroker`, `MT5Broker`, `BybitBroker`, `CompositeBroker`)
- `com.qkt.broker.BrokerFactory` typealias
- `com.qkt.bus.EventBus`
- `com.qkt.engine.Engine`
- `com.qkt.app.LiveSession` + `TradingPipeline`
- `com.qkt.marketdata.MarketPriceTracker` + `MarketPriceProvider`
- `com.qkt.execution.OrderManager`
- `com.qkt.strategy.Strategy` + `StrategyContext` + `Signal`

Live integration:

- `com.qkt.broker.mt5.MT5Broker` + `MT5Client` + `MT5BrokerProfile` + `MT5DefaultProfiles`
- `com.qkt.broker.composite.CompositeBroker` + `SymbolPattern`

DSL surface (what strategy authors reach into):

- `com.qkt.dsl.compile.DslCompiledStrategy` + `EvalContext` + `CandleHub`
- All `com.qkt.dsl.action.*` action classes (Buy, Sell, Cancel, Log, ...)
- The parser entry points in `com.qkt.dsl.parser`

CLI + daemon control plane:

- Public entry points on `com.qkt.cli` (DaemonCommand, RunCommand, DeployCommand, ...)
- `com.qkt.observability.ObservabilityServer`

### Tier 2 — nice to have

- Backtest reporting types (`BacktestResult`, `EquitySample`, `PerformanceReport`)
- Risk types (`RiskEngine`, `RiskRule`, `HaltRule`)
- Portfolio supervisor + deployer
- Indicators public catalog (`com.qkt.indicators.catalog.*`)

### Out of scope

- Internal helpers and private classes
- Test fakes and fixtures
- Generated/AST-level types deep inside the parser

## Acceptance criteria

1. Every Tier 1 type has a class-level KDoc paragraph (what it is + when to use it) and KDoc on every public method/property.
2. `reportUndocumented = true` in `build.gradle.kts` Dokka config; Gradle build emits a warning per undocumented Tier 1 type (not failing — visibility only).
3. Class-level KDoc on Tier 1 types uses `@sample` to reference a working snippet under a new `samples/` source set where it makes the page meaningfully better (Broker, Strategy, EventBus at minimum).
4. The Dokka site at `/qkt/api/` is browsable — every Tier 1 type lands you on a page with prose, not just signatures.
5. A KDoc style guide lands in `docs/contributing/kdoc-style.md` covering: paragraph structure, `@param` discipline, `@throws` for documented exceptions only, no temporal references, no "this is a wrapper around X" boilerplate.

## Approach

One commit per package. Each commit:

- Add KDoc to every public type/member in that package
- Run `./gradlew dokkaHtml` to verify it renders
- Spot-check the generated HTML

This is grinding work that benefits from being unbroken — best done as a single dedicated phase rather than woven into feature work. Estimate: 1-2 days of focused work.

## Sample-source-set setup (if pursued)

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        val main by getting
        val samples by creating {
            dependencies {
                implementation(main.output)
            }
        }
    }
}

tasks.named<org.jetbrains.dokka.gradle.DokkaTask>("dokkaHtml") {
    dokkaSourceSets.configureEach {
        samples.from("src/samples/kotlin")
    }
}
```

Each sample is a top-level `fun` annotated with `// SAMPLE_BEGIN` / `// SAMPLE_END` markers that Dokka extracts.

## Open questions

- Do we KDoc the DSL action classes (Tier 1) or treat the DSL grammar reference page as the canonical doc and leave the action classes thin? **Lean toward**: Tier 1, because anyone writing custom DSL extensions reads the action classes directly.
- Should `reportUndocumented` ever fail the build? **Recommendation**: warn only — failing is too aggressive while the API is still moving.

## References

- Dokka docs: https://kotlinlang.org/docs/dokka-introduction.html
- KDoc syntax: https://kotlinlang.org/docs/kotlin-doc.html
- Phase 21 changelog: `docs/phases/phase-21.md`
