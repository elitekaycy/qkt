# Phase 15 — DSL `LOG` with levels, placeholders, structured fields

**Released:** 2026-05-10
**Version:** 0.17.0

## Summary

Phase 15 turns the DSL `LOG` action from a literal-only print statement into a primary observability surface for live strategies. Levels (`INFO`/`WARN`/`ERROR`/`DEBUG`) let critical events stand out, `{name}` placeholders interpolate indicator and price values into messages, and trailing `key=expr` kvs become MDC entries ready for a future JSON appender. Stdout now shows the strategy name prefix (`[mybook/trend]`) so output from many strategies is distinguishable. As part of the logback configuration pass, a latent Phase 14 bug is fixed: child strategy names containing `/` no longer create unsafe subdirectories under `logs/`.

## What's new

- DSL: `LOG [LEVEL] "msg" (name=expr)*` — `LEVEL` is one of `WARN`, `ERROR`, `DEBUG`, defaulting to `INFO` when omitted.
- DSL: `{name}` placeholders in the message string, validated at parse time against the kvs.
- DSL: trailing `name=expr` kvs become structured fields. Numeric, boolean, and string-literal expressions accepted.
- DSL: string literals usable in expression position (`side="BUY"`). No string operators.
- AST: new `Value.Str` variant; new `StringLit` `ExprAst` node; `Log` reshaped to `(level, messageFormat, fields)`; new `LogLevel` enum.
- Compiler: `compileLog` validates placeholders at compile time, evaluates fields per tick, sets `log.<name>` MDC keys for the SLF4J call, dispatches to the chosen level, clears MDC even on exception.
- Kotlin DSL parity: `log("msg", "k" to expr)`, `warn(...)`, `error(...)`, `debug(...)` builders.
- Logback console pattern adds `[%X{strategy:-main}]` so stdout shows which strategy emitted each line.
- Logback `SiftingAppender` uses a custom `StrategyFilenameDiscriminator` that substitutes `/`→`__` in the file name, matching `StateDir.logFile`.

## Migration from Phase 14

**AST shape changed.** `Log("msg")` → `Log(LogLevel.INFO, "msg", emptyMap())`. Affects only test code that constructs the AST directly. The DSL surface (`LOG "msg"`) is unchanged for users.

**Stdout pattern changed.** Lines now contain `[%X{strategy}]` between the level and the logger name. Anything regex-matching the old `level logger - msg` shape needs to be updated to `level [strategy] logger - msg`. No CI assertions exist on this format today.

**Child file paths changed.** A child strategy named `mybook/trend` previously caused logback to write to `logs/mybook/trend.log` (creating a `mybook/` subdirectory). Now writes to `logs/mybook__trend.log`, matching `StateDir.logFile`. No production data is invalidated since Phase 14 hasn't run live.

**No DSL backward-compat shim.** `LOG "literal"` continues to parse with `INFO` level and no fields.

## Usage cookbook

### Simple log

```qkt
WHEN btc.close > 100
THEN LOG "buy condition met"
```

Stdout:

```text
12:34:56.789 [qkt-live-engine] INFO  [my-strategy] com.qkt.dsl.strategy - buy condition met
```

File `logs/my-strategy.log`:

```text
2026-05-10T12:34:56.789 [INFO] buy condition met
```

### Levels — WARN and ERROR

```qkt
WHEN account.equity < 9000
THEN LOG WARN "drawdown crossing 10%"

WHEN risk.haltActive
THEN LOG ERROR "risk halt: trading suspended"
```

Stdout (one line per emit):

```text
12:34:56.789 [qkt-live-engine] WARN  [my-strategy] com.qkt.dsl.strategy - drawdown crossing 10%
12:34:57.012 [qkt-live-engine] ERROR [my-strategy] com.qkt.dsl.strategy - risk halt: trading suspended
```

### Placeholder interpolation

```qkt
WHEN sma(btc, 9) crosses ABOVE sma(btc, 21)
THEN LOG "ema cross at {price}" price=btc.close
```

Renders:

```text
ema cross at 50125.00
```

### Structured fields (no placeholder)

```qkt
THEN LOG "trade" qty=1 price=btc.close side="BUY"
```

Message: `trade`. The kvs are attached as MDC entries (`log.qty=1`, `log.price=50125.00`, `log.side=BUY`) for the duration of the SLF4J call — visible to any logback appender that reads MDC, including a future JSON appender.

### Combined

```qkt
THEN LOG ERROR "broker rejected order {id}" id=42 retry=3
```

Renders `broker rejected order 42` at ERROR level with `log.id=42` and `log.retry=3` in MDC.

### DEBUG (filtered by default)

```qkt
THEN LOG DEBUG "tick {n}" n=tick_count
```

The root logback level is `INFO`, so DEBUG lines are written but suppressed by the default filter. Override the root level in `logback.xml` (or via JVM property) to see them.

### Composition with portfolios

A child strategy `mybook/trend` emitting:

```qkt
THEN LOG WARN "trend reversal at {price}" price=btc.close
```

Stdout:

```text
12:34:56.789 [qkt-live-engine] WARN  [mybook/trend] com.qkt.dsl.strategy - trend reversal at 50125.00
```

File: `logs/mybook__trend.log` (NOT `logs/mybook/trend.log`).

### Kotlin DSL parity

```kotlin
strategy("my-strategy", 1) {
    val btc = stream("btc", "BACKTEST", "BTCUSDT", "1m")
    rule {
        whenever(btc.close gt 100.bd)
        then { warn("buy at {price}", "price" to btc.close) }
    }
}
```

Round-trips byte-for-byte with the equivalent text DSL.

## Testing patterns

Capture log events with a logback `AppenderBase` for unit tests:

```kotlin
val captured = mutableListOf<ILoggingEvent>()
val appender = object : AppenderBase<ILoggingEvent>() {
    override fun append(eventObject: ILoggingEvent) { captured.add(eventObject) }
}
appender.context = LoggerFactory.getILoggerFactory() as LoggerContext
appender.start()
val logger = LoggerFactory.getLogger("test") as ch.qos.logback.classic.Logger
logger.addAppender(appender)
logger.level = Level.DEBUG

ActionCompiler(ExprCompiler(), logger).compile(action).invoke(ctx)
assertThat(captured[0].level).isEqualTo(Level.WARN)
assertThat(captured[0].mdcPropertyMap).containsEntry("log.price", "50125")
```

Discriminator substitution is unit-testable directly:

```kotlin
val discriminator = StrategyFilenameDiscriminator().also { it.start() }
assertThat(discriminator.getDiscriminatingValue(eventWithMdc("strategy" to "mybook/trend")))
    .isEqualTo("mybook__trend")
```

Round-trip equivalence between text DSL and Kotlin DSL is enforced in `RoundTripEquivalenceTest`.

## Known limitations

- **No string concatenation in expression grammar.** `LOG "msg " + btc.close` is not supported. Use placeholder syntax: `LOG "msg {x}" x=btc.close`.
- **No escape syntax for `{`.** Logging a literal `{` requires a future enhancement; for now, avoid `{` in `LOG` message text unless used as a placeholder.
- **JSON appender plumbing deferred.** `log.<name>` MDC keys are emitted during each LOG call but the consuming JSON appender is not enabled by default. Adding one is a one-block addition to `logback.xml`.
- **Default root level is `INFO`.** `LOG DEBUG` lines are filtered out unless the root level is changed.
- **Logback discriminator runtime API.** The `StrategyFilenameDiscriminator` extends `MDCBasedDiscriminator` from `logback-classic 1.4.x`. Future logback major versions may move the base class; lock the dependency version in `gradle/libs.versions.toml`.

## References

- Spec: [`docs/superpowers/specs/2026-05-10-trading-engine-phase15-dsl-log-design.md`](../superpowers/specs/2026-05-10-trading-engine-phase15-dsl-log-design.md)
- Plan: [`docs/superpowers/plans/2026-05-10-trading-engine-phase15-dsl-log.md`](../superpowers/plans/2026-05-10-trading-engine-phase15-dsl-log.md)
- Phase 14 changelog (slash-name origin): [`docs/phases/phase-14.md`](phase-14.md)
