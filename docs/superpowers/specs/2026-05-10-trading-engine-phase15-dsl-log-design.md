# Phase 15 — DSL `LOG` action with levels, placeholders, structured fields

**Phase:** 15
**Status:** Design
**Author:** elitekaycy
**Date:** 2026-05-10

---

## 1. Goal

Make the DSL `LOG` action expressive enough to be the primary observability surface for live strategies. Phase 15 ships:

1. **Levels** — `INFO` (default), `WARN`, `ERROR`, `DEBUG`. Lets risk/error events stand out from routine traces.
2. **Format placeholders** — `LOG "buy at {price}" price=btc.close` renders the indicator value into the message.
3. **Structured fields** — trailing `name=expr` kvs are logged as MDC entries, enabling a future JSON appender for grafana/log-aggregator ingest.
4. **Strategy/child prefix on stdout** — the existing console pattern omits MDC, so today a `LOG` line on stdout has no indication which strategy emitted it. Phase 15 adds `[%X{strategy:-main}]` to the pattern.
5. **File-name fix for slash names** — phase 14 introduced child names with `/` (e.g. `mybook/trend`). The logback `SiftingAppender` writes to `logs/${strategy}.log` literally, so a child today would write to `logs/mybook/trend.log` (subdirectory) instead of the `logs/mybook__trend.log` produced by `StateDir.logFile`. Phase 15 unifies the substitution.

Out of scope (deferred):

- String concatenation in the expression grammar (`"a " + b.close`). Format-string placeholders cover the same use case with smaller surface.
- JSON appender wiring. Structured MDC fields are emitted; the appender that consumes them is a follow-on.
- Dynamic log filtering at runtime (`qkt logs --level WARN`). Logs are written at the chosen level; clients filter by reading.

## 2. Why

The Phase 13b `Log` action (`LOG "literal"`) writes a fixed string at INFO via SLF4J. That's enough to mark code paths but not enough for live trading. Three concrete pain points:

1. **No interpolation.** A trader logging "entered long at 50125" today has to either hard-code the price (useless) or skip LOG and hope the trade event line is enough. Indicator values, position sizes, P&L deltas — none are loggable from the DSL.
2. **No levels.** Routine traces and broker-disconnect errors mix into one stream. Operators paging on stderr can't filter usefully.
3. **No structured ingest.** Future grafana dashboards over per-strategy log fields require parseable kvs, not regex over free text.

Phase 14 also exposed a latent file-naming bug for slash-named children. Phase 15 fixes it as part of the same logback configuration pass.

## 3. Architecture

### 3.1 Surface syntax

```
action := LOG [level] STRING (IDENT '=' expr)*

level  := WARN | ERROR | DEBUG          # default INFO when omitted
expr   := existing expression grammar + string literals
         (string literals are usable as values; no string concatenation or
          string-returning operations in v1)
```

Examples:

```
LOG "buy at {price}" price=btc.close
LOG WARN "drawdown high"
LOG ERROR "broker down" code=42 retry=3
LOG "trade" qty=position(btc) price=btc.close side="BUY"
LOG DEBUG "tick {n}" n=tick_count
```

Compile-time validation:

- Every `{name}` placeholder must have a matching `name=expr` kv. Unmatched placeholder is a parse error at the action site (line:col of the LOG keyword), not a runtime error.
- A kv without a matching `{name}` is still logged as a structured field. This is intentional: the kvs are the structured payload, the message is a human-readable summary.
- Duplicate kv names are a parse error.

The level token sits between `LOG` and the message string. It is a reserved keyword. `INFO` is the default and is **not** a separate token — typing `LOG INFO "msg"` is a parse error in v1 to keep the grammar minimal. Future expansion may add `INFO` as a synonym for `LOG` with no level token.

### 3.2 Output formats

**stdout** (existing logback `STDOUT` appender). Pattern updates from:

```xml
<pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
```

to:

```xml
<pattern>%d{HH:mm:ss.SSS} [%thread] %-5level [%X{strategy:-main}] %logger{36} - %msg%n</pattern>
```

A child-emitted LOG now reads:

```
12:34:56.789 [qkt-live-engine] INFO  [mybook/trend] com.qkt.dsl.strategy - buy at 50125.00
```

**file** (existing logback `STRATEGY_FILE` `SiftingAppender`). Filename pattern updates from:

```xml
<file>${QKT_STATE_DIR:-${user.home}/.local/state/qkt}/logs/${strategy}.log</file>
```

to use a discriminator that substitutes `/` for `__` so a child name `mybook/trend` produces `mybook__trend.log` matching `StateDir.logFile`. Logback's discriminator API accepts a transform via the `<discriminator>` extension point; the simplest portable solution is a custom `Discriminator` subclass:

```kotlin
class StrategyFilenameDiscriminator : ContextBasedDiscriminator() {
    override fun getDiscriminatingValue(e: ILoggingEvent): String =
        e.mdcPropertyMap["strategy"]?.replace("/", "__") ?: "main"
    override fun getKey(): String = "strategy_filename"
}
```

logback.xml references this class. The file pattern becomes:

```xml
<discriminator class="com.qkt.cli.daemon.logging.StrategyFilenameDiscriminator"/>
<sift>
    <appender name="FILE-${strategy_filename}" ...>
        <file>${QKT_STATE_DIR:-...}/logs/${strategy_filename}.log</file>
        ...
    </appender>
</sift>
```

The file's per-line pattern already includes level (`%d{ISO8601} [%level] %msg%n`); no change.

**structured fields** (new). Each LOG call evaluates its kvs, sets them in MDC for the duration of the SLF4J call, then clears them. Example:

```kotlin
MDC.put("log.price", "50125.00")
MDC.put("log.qty", "0.1")
strategyLogger.info("buy at 50125.00")
MDC.remove("log.price")
MDC.remove("log.qty")
```

The `log.` prefix namespaces user-defined kvs to avoid colliding with engine-set MDC keys (`strategy`, `parent`). A future JSON appender consumes any `log.*` key into its event JSON.

### 3.3 AST shape

`RuleAst.kt`:

```kotlin
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class Log(
    val level: LogLevel,
    val messageFormat: String,
    val fields: Map<String, ExprAst>,
) : ActionAst
```

The `Log` data class replaces the existing 13b version. Migration: existing `Log("msg")` callers become `Log(LogLevel.INFO, "msg", emptyMap())`. Affects test code only — the parser/compiler boundary is internal.

### 3.4 Expression grammar — string literals

`Value.kt` (currently in `CompiledExpr.kt`) gains a third variant:

```kotlin
sealed interface Value {
    data class Num(val v: BigDecimal) : Value
    data class Bool(val v: Boolean) : Value
    data class Str(val v: String) : Value      // NEW
    data object Undefined : Value
}
```

`StringLit(value: String) : ExprAst` is a new AST node. The parser already handles STRING tokens for `LOG "literal"`; the change is permitting STRING in any expression position. ExprCompiler maps `StringLit` → constant `Value.Str` evaluator.

No string operators are added in v1. `Value.Str` is read-only and only flows out: kvs evaluate to it, the formatter stringifies any `Value.Num` / `Value.Bool` for placeholder substitution. Attempting `Str + Str` or `Str + Num` is a runtime error from the existing arithmetic eval (which expects `Value.Num`); since these aren't legal v1 expressions, the runtime should never see them.

### 3.5 Compile-time placeholder validation

`ActionCompiler.compileLog` validates that every `{name}` in `messageFormat` has a matching key in `fields`. Pseudocode:

```kotlin
private fun compileLog(log: Log): (EvalContext) -> List<Signal> {
    val placeholders = extractPlaceholders(log.messageFormat)  // returns Set<String>
    val unmatched = placeholders - log.fields.keys
    if (unmatched.isNotEmpty()) {
        error("LOG placeholders missing field expressions: $unmatched")
    }
    val compiledFields = log.fields.mapValues { (_, expr) -> exprCompiler.compile(expr) }
    return { ctx ->
        val rendered = renderMessage(log.messageFormat, compiledFields, ctx)
        try {
            for ((k, v) in compiledFields) {
                MDC.put("log.$k", stringify(v.evaluate(ctx)))
            }
            when (log.level) {
                LogLevel.DEBUG -> strategyLogger.debug(rendered)
                LogLevel.INFO  -> strategyLogger.info(rendered)
                LogLevel.WARN  -> strategyLogger.warn(rendered)
                LogLevel.ERROR -> strategyLogger.error(rendered)
            }
        } finally {
            for (k in compiledFields.keys) MDC.remove("log.$k")
        }
        emptyList()
    }
}
```

`extractPlaceholders` regex: `\{([a-zA-Z_][a-zA-Z0-9_]*)\}`. `renderMessage` substitutes each `{k}` with `stringify(field.evaluate(ctx))`. `stringify`: `Num.v.toPlainString()`, `Bool.v.toString()`, `Str.v` literal.

If the parser rejects unmatched placeholders, the compile-time check is defense-in-depth and will not fire. Both layers exist for clarity of intent.

### 3.6 Kotlin DSL parity

`com.qkt.dsl.kotlin.ActionScope` gains:

```kotlin
fun log(message: String, vararg fields: Pair<String, ExprAst>)
fun warn(message: String, vararg fields: Pair<String, ExprAst>)
fun error(message: String, vararg fields: Pair<String, ExprAst>)
fun debug(message: String, vararg fields: Pair<String, ExprAst>)
```

Round-trip equivalence with text DSL is enforced by the existing `RoundTripEquivalenceTest` pattern.

## 4. Migration

**Breaking AST change.** `Log("msg")` → `Log(LogLevel.INFO, "msg", emptyMap())`. The DSL surface (`LOG "msg"`) is unchanged for users. Only test code that constructs the AST directly needs an update — the existing `ActionCompilerExtensionsTest.compileLog "Log emits no signals"` test changes its construction site.

**Behavior change on stdout.** Lines now include `[strategy]`. Anything grepping the existing pattern format (`logger - msg`) keeps working since `logger - msg` is still the suffix. CI log assertions might need a regex update; none exist today.

**Behavior change on filename.** Slash-named child strategies now write to `logs/mybook__trend.log`. Phase 14 was producing `logs/mybook/trend.log` (a subdirectory) which was buggy — no production data is invalidated since Phase 14 hasn't run live.

**No DSL backward-compat shim.** Per CLAUDE.md, no compat without explicit approval. The `LOG "literal"` syntax keeps working unchanged.

## 5. Testing

### Unit / parser

- `LOG "literal"` parses with `LogLevel.INFO`, no fields.
- `LOG WARN "msg"`, `LOG ERROR "msg"`, `LOG DEBUG "msg"` parse with the right level.
- `LOG "msg" k=expr` parses with one field; multiple kvs parse in order.
- `LOG "buy at {price}" price=btc.close` parses; placeholder + matching field.
- `LOG "buy at {price}"` (placeholder, no field) fails parse with a clear error citing the unmatched placeholder.
- Duplicate kv names fail parse.
- `LOG "msg" code="STR_LITERAL"` parses; string literals supported in expression position.

### Unit / compiler

- `compileLog` invocation writes through SLF4J at the chosen level (use a captured-logger in test).
- MDC `log.*` keys are set during the call and cleared after, even if the underlying logger throws.
- Placeholder substitution renders numerics with `toPlainString()`; booleans render `true/false`; strings render verbatim.
- Compile rejects unmatched placeholders (defense-in-depth — parse already rejects).

### Integration

- Round-trip: `LOG "buy at {p}" p=btc.close` text → AST → Kotlin DSL → text equality.
- End-to-end (one focused test): a strategy with `LOG "trade {q}" q=position(btc)` running through `LiveSession` produces a log line in the strategy's file that contains the rendered message.

### Logback config

- Console pattern includes `[%X{strategy:-main}]`. A test that captures stdout and runs a trivial strategy with a LOG action asserts the prefix appears.
- File pattern: deploy a child strategy named `parent/child`, emit a LOG, assert `logs/parent__child.log` exists and contains the line, and `logs/parent/child.log` does NOT exist.

## 6. Risks

**Medium — MDC leakage.** If a LOG call throws during message rendering, the `log.*` MDC keys must be cleared anyway. Mitigation: `try/finally` around the MDC.put / log.X / MDC.remove block. Test that asserts MDC is empty after a deliberately-throwing field expression.

**Medium — Logback discriminator class loading.** Custom `Discriminator` subclass must be on the classpath when logback initializes. If it's missing or misnamed, logback falls back silently. Mitigation: a startup self-test (called from `Main.kt` once on daemon boot) emits a LOG with `MDC.strategy = "self/test"` and verifies `logs/self__test.log` exists.

**Low — placeholder regex.** The `\{([a-zA-Z_][a-zA-Z0-9_]*)\}` regex doesn't support escaping. To log a literal `{`, users would need to wait for a future enhancement. Acceptable for v1.

**Low — string literal flow.** Adding `Value.Str` opens the door for users writing expressions like `position(btc) > "BUY"`. The arithmetic eval will throw a class-cast at runtime. This is the same risk as today's mix-up between `Num` and `Bool`. Acceptable.

**Low — log-level filtering.** Logback root level is `INFO`. `LOG DEBUG "msg"` lines are written but suppressed by the root filter. Users who want DEBUG visible must set the level explicitly. Documented in the changelog.

## 7. References

- Existing `Log` AST: `src/main/kotlin/com/qkt/dsl/ast/RuleAst.kt:34`
- Existing parser support: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt:687` (`TokenKind.LOG` branch)
- Existing compiler: `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt:100` (`compileLog`)
- Logback config: `src/main/resources/logback.xml`
- StateDir filename substitution: `src/main/kotlin/com/qkt/cli/daemon/StateDir.kt:34`
- Phase 14 changelog (latent slash-bug context): `docs/phases/phase-14.md`
