# Phase 13b — STACK Polish, CANCEL Action, PORTFOLIO

> Status: design accepted, awaiting plan.
> Author: elitekaycy.
> Phase: 13b (combined). Three sub-phases: α STACK polish, β CANCEL, γ PORTFOLIO.

---

## 1. Mission

Phase 13b ships three pieces in one merge:

- **α — STACK polish.** Close test-coverage gaps from 13a's final review and lift the ChildRr-throws limitation by wiring stack outerBracket against the existing risk-metrics path.
- **β — CANCEL action.** Enable `CANCEL <stream>` and `CANCEL_ALL` in the DSL. Currently both throw `error("deferred")`. With STACK shipped, manual order-cancellation from inside a strategy is a missing primitive that PORTFOLIO will rely on.
- **γ — PORTFOLIO.** Add a `PORTFOLIO` keyword + `IMPORT` + `WHEN ... RUN` syntax that lets one file import N strategy files and gate them by regime expressions. The headline feature: regime-switched exclusive child execution.

The three sub-phases are bundled because PORTFOLIO depends on β (deactivation needs CANCEL semantics), and α is too small to ship alone.

---

## 2. Goals

### α — STACK polish

- SELL-side STACK end-to-end backtest test (mirror of the BUY pyramid happy path).
- `STACK 3 SPACING 100 BELOW` end-to-end backtest test (BUY averaging-down).
- Concurrent-stacks test: two STACK rules in one strategy fire independently; one stack's TP does not cancel the other's pending layers.
- ChildRr in stack outerBracket: replace the current `error("ChildRr is not supported in STACK outerBracket")` with proper risk-metrics evaluation, mirroring how non-stack ChildRr is computed today.

### β — CANCEL action

- `THEN CANCEL <stream>` cancels all pending orders for the stream's symbol within the current strategy. Includes pending stack layers (covered by `OrderManager.cancelStacksForSymbol`).
- `THEN CANCEL_ALL` cancels all pending orders for the strategy across all symbols.
- Closed positions are NOT touched. CANCEL is purely about pending entries.
- Round-trip equivalence: parser ↔ Kotlin DSL.

### γ — PORTFOLIO

- New `PORTFOLIO <name> VERSION <n>` file format alongside `STRATEGY`. Same `.qkt` extension; distinguished by content.
- `IMPORT "<relative-path>" AS <alias> [HOLD]` clause. Relative paths only (absolute allowed but discouraged).
- `WHEN <expr> RUN <alias>` rule clause; bare `RUN <alias>` for always-on children.
- One child active iff any of its RUN clauses (gated or unconditional) evaluates true.
- Children iterate in declaration order on every tick.
- Default deactivation = close-on-deactivate. Per-import `HOLD` opt-in keeps existing positions and brackets alive (pending stack layers always cancel regardless).
- Indicators (CandleHub-level) update for inactive children; their internal state stays current across deactivation.
- Per-child `strategyId` partitions PositionTracker / StrategyPnL / RiskState — per-child observability falls out of existing daemon-scope state machinery.
- One deploy unit at the daemon level. CLI sub-key syntax `<portfolio>:<child-alias>` for status/logs drill-down.

### Non-goals (deferred to v2 or later)

- WEIGHT clause / capital allocation per child.
- Import-time parameter overrides (`WITH { sizing = ... }`).
- Same strategy file imported multiple times with different aliases.
- Nested PORTFOLIO inside another PORTFOLIO.
- Daemon hot-reload of portfolio file changes.
- State persistence across daemon restart.
- Per-child `DISABLE_ON_ERROR` policy. Default: child runtime errors propagate.
- Portfolio-level risk caps. Children continue to use per-strategy RiskState.
- Per-child equity allocation in status snapshots (in v1 only aggregate equity is meaningful — children share the pool).

---

## 3. Worked examples

### 3.1 STACK polish — ChildRr in outerBracket

```
STRATEGY ema_pyr_rr VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    WHEN ema(btc.close, 9) CROSSES ABOVE ema(btc.close, 21)
    THEN BUY btc SIZING 0.1
         STACK 3 SPACING 100
         BRACKET { STOP LOSS BY 50, TAKE PROFIT RR 2.0 }
```

Behaviour: each filled layer's TP is computed at risk-multiple `2.0` (TP distance = SL distance × 2). Currently this throws; α implements the calculation.

### 3.2 CANCEL — manual cancellation from inside a strategy

```
WHEN price_protection_breach
THEN CANCEL btc

WHEN account_drawdown_exceeded
THEN CANCEL_ALL
```

`CANCEL btc` cancels all pending orders for `btc`'s symbol — including any pending STACK layers (via `OrderManager.cancelStacksForSymbol`). Filled positions are not touched (use `CLOSE` for that).

### 3.3 PORTFOLIO — regime-switched exclusive

```
PORTFOLIO trend_or_range VERSION 1

SYMBOLS
    btc = BYBIT:BTCUSDT EVERY 1h

IMPORT "ema_cross.qkt" AS trend
IMPORT "mean_revert.qkt" AS range

RULES
    WHEN adx(btc.close, 14) > 25 RUN trend
    WHEN adx(btc.close, 14) <= 25 RUN range
```

Behaviour: one child active at any time, switched by 1h ADX. When ADX flips below 25, `trend` deactivates (closes its open positions, cancels its pending orders), `range` activates and starts receiving ticks. The portfolio's own `btc` (1h timeframe) is independent from whatever `btc` the imported strategies define internally.

### 3.4 PORTFOLIO — always-on bundle

```
PORTFOLIO multi_strat VERSION 1

IMPORT "trend.qkt" AS t
IMPORT "carry.qkt" AS c

RULES
    RUN t
    RUN c
```

Both children always active. Capital shared. The portfolio is essentially a deployment bundle; no regime gating. SYMBOLS section omitted because the gate expressions don't need stream context.

### 3.5 PORTFOLIO — mixed (always-on + gated)

```
PORTFOLIO complex VERSION 1

SYMBOLS
    cal = CALENDAR:NULL EVERY 1m

IMPORT "scalper.qkt" AS s
IMPORT "swing.qkt" AS w HOLD

RULES
    WHEN session() == LONDON RUN s
    WHEN dayOfWeek() != FRIDAY RUN w
```

Two children. `s` (scalper) only active during London session — closes its positions when out of session. `w` (swing) active except Friday — but `HOLD` flag means existing swing positions live across the deactivation; only new entries blocked.

(`session()` and `dayOfWeek()` are existing time-derived helpers; if they don't yet exist as DSL functions, that's a separate addition outside this spec.)

### 3.6 PORTFOLIO — Kotlin DSL parity

```kotlin
portfolio("trend_or_range", version = 1) {
    symbols { stream("btc", "BYBIT", "BTCUSDT", "1h") }

    import("ema_cross.qkt", alias = "trend")
    import("mean_revert.qkt", alias = "range")

    rules {
        whenRun(
            cond = (indicator("adx", streamFieldRef("btc", "close"), num("14")) gt num("25")),
            child = "trend",
        )
        whenRun(
            cond = (indicator("adx", streamFieldRef("btc", "close"), num("14")) le num("25")),
            child = "range",
        )
    }
}
```

---

## 4. Architecture

### 4.1 α — STACK polish

**ChildRr support.** The non-stack path uses `ChildPriceResolver.compile(childPrice, ChildKind.TAKE_PROFIT)` which produces a `CompiledChildPrice` evaluated against `(side, entry, stopDistance)`. The stack path's `attachLayerTp` already has access to `fillPrice` (entry) and can derive `stopDistance` from the SL it's about to attach. The fix: in `computeChildPrice`, the `is ChildRr` branch computes `tpDistance = sl.distance * rr.multiplier` and applies the side-aware sign. Reuse the existing arithmetic.

This requires `attachLayerTp` to know the SL distance. Re-order so SL is computed first, the distance is captured, then TP is computed using that distance for ChildRr.

**Tests.** Three new end-to-end backtest tests in `src/test/kotlin/com/qkt/backtest/StackBacktestTest.kt`:
- `SELL stack pyramid fills three layers at decreasing prices`
- `BUY stack with BELOW direction averages down`
- `concurrent stacks one tp does not cancel other pending layers`

### 4.2 β — CANCEL action

`ActionCompiler` currently does:

```kotlin
is Cancel -> error("CANCEL action is deferred...")
is CancelAll -> error("CANCEL_ALL action is deferred...")
```

Replace with:

```kotlin
is Cancel -> compileCancel(action.stream)
is CancelAll -> compileCancelAll()

private fun compileCancel(streamAlias: String): (EvalContext) -> List<Signal> = { ctx ->
    val symbol = ctx.streams[streamAlias]?.symbol ?: error("Unknown stream alias: $streamAlias")
    listOf(Signal.CancelStacksForSymbol(symbol))
}

private fun compileCancelAll(): (EvalContext) -> List<Signal> = { ctx ->
    ctx.streams.values.mapNotNull { it.symbol }.distinct().map { Signal.CancelStacksForSymbol(it) }
}
```

`Signal.CancelStacksForSymbol` (already added in 13a Bug 4 fix) is reused. The signal cancels both pending non-stack orders for the symbol AND pending stack layers — since `OrderManager.cancelStacksForSymbol` finds stacks targeting that symbol and cancels them, plus the existing per-symbol pending-cancel logic.

Wait — that's a gap. `Signal.CancelStacksForSymbol` ONLY cancels stack layers, not other pending orders. CANCEL action's intent is broader: cancel all pending orders for the symbol, stack-related or not.

Resolution: rename / generalize. Either:
- Rename `Signal.CancelStacksForSymbol` to `Signal.CancelPendingForSymbol` and have it cancel ALL pending orders for the symbol (stack and non-stack alike).
- Add a separate `Signal.CancelPendingForSymbol` and have CANCEL emit both.

Cleaner: rename + generalize. `OrderManager.cancelPendingForSymbol(symbol)` cancels all pending orders + all stacks for the symbol. CLOSE-cascade and CANCEL action both use it.

This means a small refactor to 13a's wiring (`Signal.CancelStacksForSymbol` becomes `CancelPendingForSymbol`; `OrderManager.cancelStacksForSymbol` becomes `cancelPendingForSymbol`). All callers update.

**Round-trip equivalence.** The Kotlin DSL `cancel(stream)` and `cancelAll()` already exist as `ActionScope.cancelStream`/`closeAll`-style helpers (or are added if not). Round-trip tests verify parser ↔ Kotlin DSL for both.

### 4.3 γ — PORTFOLIO

#### 4.3.1 AST

Under `com.qkt.dsl.ast`:

```kotlin
data class PortfolioAst(
    val name: String,
    val version: Int,
    val symbols: SymbolsBlock?,
    val imports: List<ImportClause>,
    val rules: List<PortfolioRule>,
)

data class ImportClause(
    val path: String,         // relative path as written
    val alias: String,
    val hold: Boolean = false,
)

sealed interface PortfolioRule

data class WhenRun(
    val cond: ExprAst,
    val alias: String,
) : PortfolioRule

data class AlwaysRun(
    val alias: String,
) : PortfolioRule
```

`StrategyAst` and `PortfolioAst` are sibling root types. The parser's top-level dispatches on the first keyword (`STRATEGY` vs `PORTFOLIO`).

#### 4.3.2 Lexer additions

New token kinds:

| Kind | Lexeme |
|---|---|
| `PORTFOLIO` | `PORTFOLIO` |
| `IMPORT` | `IMPORT` |
| `AS` | `AS` |
| `RUN` | `RUN` |
| `HOLD` | `HOLD` |

`AS` already exists if used elsewhere; check before adding. (It currently doesn't appear in TokenKind from 12c-era inspection — adding it.)

#### 4.3.3 Grammar (BNF-ish)

```
file              ::= strategy | portfolio
portfolio         ::= "PORTFOLIO" IDENT "VERSION" NUMBER
                     symbols_block?
                     import_clause+
                     rules_block?
import_clause     ::= "IMPORT" STRING "AS" IDENT "HOLD"?
rules_block       ::= "RULES" portfolio_rule+
portfolio_rule    ::= "WHEN" expr "RUN" IDENT
                    | "RUN" IDENT
```

Validation rules enforced at parse / compile time:
- At least one IMPORT clause required.
- Aliases must be unique within the portfolio.
- Same import path twice rejected.
- WHEN-RUN's `RUN <alias>` must reference a declared import alias.
- Cycle in import graph rejected at load time (post-parse).

#### 4.3.4 Compilation pipeline

New module `com.qkt.dsl.portfolio.PortfolioLoader`:

```kotlin
object PortfolioLoader {
    fun load(path: java.nio.file.Path): PortfolioCompiled {
        val ast = parsePortfolioFile(path)
        detectCycles(path, ast)
        val children = ast.imports.map { imp ->
            val childPath = path.parent.resolve(imp.path).normalize()
            val childStrategy = StrategyLoader.load(childPath)  // existing path
            CompiledChild(imp.alias, imp.hold, childStrategy, generateChildStrategyId(ast.name, imp.alias))
        }
        return PortfolioCompiled(ast, children)
    }
}
```

`PortfolioCompiled` is then wrapped in `PortfolioStrategy` (a `Strategy` impl).

#### 4.3.5 Runtime: `PortfolioStrategy`

```kotlin
class PortfolioStrategy(
    private val compiled: PortfolioCompiled,
    private val gateCompiler: ExprCompiler,
) : Strategy {
    private val children = compiled.children.map { ChildRunner(it) }
    private val gateExprs: List<CompiledGate> = compiled.ast.rules.map { rule -> compileGate(rule) }
    private var lastGateState: Map<String, Boolean> = emptyMap()

    override fun onTick(tick: Tick, ctx: StrategyContext, emit: (Signal) -> Unit) {
        val newGateState = evaluateGates(ctx)
        val transitions = diffGateState(lastGateState, newGateState)
        applyTransitions(transitions, ctx, emit)
        for (child in children) {
            if (newGateState.getValue(child.alias)) {
                child.runner.onTick(tick, ctx.scopedTo(child.strategyId), { sig -> emit(sig) })
            }
        }
        lastGateState = newGateState
    }
}

private data class ChildRunner(
    val alias: String,
    val strategyId: String,
    val hold: Boolean,
    val runner: Strategy,
    val streams: List<String>,    // for emitting CancelPendingForSymbol on deactivation
)
```

Notable details:
- Gate state evaluated every tick; transitions are detected by diffing previous-tick state.
- On `inactive → active`: child resumes; nothing special.
- On `active → inactive`: portfolio emits `Signal.CancelPendingForSymbol` for each of the child's symbols. If `!hold`: also emits Sell/Buy signals to close open positions for the child's strategyId.
- Children iterate in declaration order; gate eval also in declaration order.
- `ctx.scopedTo(childStrategyId)` returns a child-scoped `StrategyContext` whose `pnl` / `positions` / `risk` views are filtered to the child's strategyId.

#### 4.3.6 Per-child strategyId generation

Format: `<portfolio-strategyId>:<child-alias>`. Example: `trend_or_range:trend`.

`StrategyHandle` (12c daemon) tracks the parent portfolio's strategyId. Children's strategyIds are internal; observability resolves the `<portfolio>:<child>` syntax to the right internal id.

#### 4.3.7 Daemon integration

- `qkt deploy portfolio.qkt` — registers ONE `StrategyHandle` for the portfolio. The handle's metadata records `type=portfolio` + `children=[alias1, alias2, ...]`.
- `qkt list` — shows portfolios with a `[type=portfolio, children=N]` marker.
- `qkt status portfolio.qkt` — top-level status (aggregate equity, gate state per child).
- `qkt status portfolio.qkt:trend` — drill into child. Resolves the sub-key, reads from PositionTracker / StrategyPnL filtered to the child's strategyId.
- `qkt logs portfolio.qkt:trend` — child's logs (logback's MDC routing — child's strategyId is the log key).
- `qkt stop portfolio.qkt` — cascades through `PortfolioStrategy.onStop` which deactivates all children (close-or-hold per import flags).

All of these reuse the existing daemon machinery — no new endpoints needed beyond the sub-key parsing in the CLI. Sub-key parsing: any CLI argument matching `^(.+):([^:]+)$` is treated as `(portfolio, child)`; the daemon dispatches accordingly.

#### 4.3.8 Backtest integration

`qkt backtest portfolio.qkt --from ... --to ...` works without API changes:
- `Backtest` uses `StrategyLoader.load(path)` — extend it to also dispatch on PORTFOLIO files via `PortfolioLoader`.
- Backtest's tick loop already drives any `Strategy`. `PortfolioStrategy` is just another `Strategy`.
- Reports include per-child trade log + aggregate metrics.

#### 4.3.9 Determinism

- Gate evaluation deterministic given same tick stream (same expression, same indicator values from CandleHub).
- Children iterate in declaration order; identical signal sequence in backtest vs live.
- Activation/deactivation transitions fire on the same tick in backtest vs live.

#### 4.3.10 Logging

Portfolio-level logs (with the portfolio's own strategyId in MDC):

```
INFO portfolio gate_eval portfolio=trend_or_range trend=true range=false
INFO portfolio gate_flip portfolio=trend_or_range child=trend from=true to=false
INFO portfolio activate portfolio=trend_or_range child=range
INFO portfolio deactivate portfolio=trend_or_range child=trend hold=false
```

Per-child logs continue to flow tagged with the child's strategyId.

---

## 5. Lexer / Parser changes

| Token kind | Lexeme | Phase |
|---|---|---|
| `PORTFOLIO` | `PORTFOLIO` | γ |
| `IMPORT` | `IMPORT` | γ |
| `AS` | `AS` | γ |
| `RUN` | `RUN` | γ |
| `HOLD` | `HOLD` | γ |

`CANCEL` and `CANCEL_ALL` already exist (token kinds present, parser branch present, ActionCompiler currently throws).

Parser adds:
- `parseFile()` dispatches on first keyword (STRATEGY → existing parser; PORTFOLIO → new `parsePortfolio()`).
- `parsePortfolio()` parses header, optional symbols, imports, optional rules block.
- `parseImport()` parses `IMPORT STRING AS IDENT (HOLD)?`.
- `parsePortfolioRule()` parses `WHEN expr RUN IDENT` or bare `RUN IDENT`.

Public entry point: existing `Parser.parseStrategy()` is renamed/aliased; new `Parser.parseFile(): Either<StrategyAst, PortfolioAst>` (or a sealed type covering both).

---

## 6. Kotlin DSL surface

```kotlin
fun portfolio(name: String, version: Int, init: PortfolioBuilder.() -> Unit): PortfolioAst { ... }

class PortfolioBuilder {
    fun symbols(init: SymbolsBuilder.() -> Unit) { ... }
    fun import(path: String, alias: String, hold: Boolean = false) { ... }
    fun rules(init: PortfolioRulesBuilder.() -> Unit) { ... }
}

class PortfolioRulesBuilder {
    fun whenRun(cond: ExprAst, child: String) { ... }
    fun run(child: String) { ... }
}
```

Round-trip equivalence: Kotlin-built `PortfolioAst` must equal parser-produced `PortfolioAst` for the same logical portfolio.

---

## 7. CLI sub-key syntax

Existing daemon CLI commands (`qkt status`, `qkt logs`, `qkt stop`) accept a `<portfolio>:<child>` form. Parsing rule: when an argument contains a `:` and the prefix matches a registered portfolio strategyId, treat the suffix as a child alias.

Implementation in `ControlClient` (12c):
- Existing single-key path unchanged.
- New: `splitSubKey(arg): Pair<String, String?>` returns `(portfolioId, childAlias?)`.
- For child queries: server-side filters PositionTracker / StrategyPnL by the resolved child strategyId.

---

## 8. Failure modes

| Scenario | Behavior |
|---|---|
| Portfolio file fails to parse | Load fails. Error shows file + line + reason. |
| Imported child file fails to parse | Load fails. Error shows portfolio file + IMPORT line + child file's parse error. |
| Cycle in import graph | Load fails. Error shows full cycle path. |
| Same alias declared twice | Parse error. |
| Same import path declared twice | Parse error (no overrides in v1, so duplicate imports are pointless). |
| WHEN-RUN references unknown alias | Parse / compile error pointing to the offending RUN clause. |
| Child throws at runtime in onTick | Propagate by default (matches current TradingPipeline behavior). v2 may add `DISABLE_ON_ERROR` policy. |
| Gate expression throws at runtime | Propagate (treat as user bug — misformed gate). |
| Portfolio with zero RUN clauses (parse OK but no rules) | Warning at parse: "portfolio has no RULES block; no children will activate". |
| Portfolio with imports that all gate to false | Children all inactive. Portfolio idle. |

---

## 9. Testing strategy

### α — STACK polish

- `OrderManagerStackTest`: ChildRr in outerBracket; verify TP price computed at `entry + slDistance * rr.multiplier`.
- `StackBacktestTest`: 3 new scenarios (SELL pyramid; BUY BELOW; concurrent stacks).

### β — CANCEL

- `ActionCompilerTest`: CANCEL emits `Signal.CancelPendingForSymbol`; CANCEL_ALL emits one per stream.
- `OrderManagerTest`: `cancelPendingForSymbol` cancels both stacks AND non-stack pending orders for the symbol.
- `RoundTripEquivalenceTest`: CANCEL / CANCEL_ALL round-trip parser ↔ Kotlin DSL.
- End-to-end backtest scenario: a strategy with both pending orders and stacks, then a CANCEL rule fires.

### γ — PORTFOLIO

- `LexerPortfolioTest`: PORTFOLIO / IMPORT / AS / RUN / HOLD tokens.
- `ParserPortfolioTest`: full surface (SPACING form for gates, layer-list nope — gate exprs are arbitrary boolean exprs), error cases (cycle, unknown alias, duplicate alias, etc.).
- `PortfolioBuilderTest`: Kotlin DSL parity.
- `PortfolioLoaderTest`: file-loading, cycle detection, error message quality.
- `PortfolioStrategyTest`: gate evaluation, transitions, activation/deactivation cleanup, HOLD behavior.
- `PortfolioBacktestTest`: 4 end-to-end scenarios:
  - Regime switch trend↔range; verify exclusive activation.
  - Always-on bundle (two children, both active).
  - HOLD: position survives deactivation.
  - Default close-on-deactivate: position flattens, pending cancel.
- Round-trip equivalence for at least 2 portfolio shapes.

### Integration

- Daemon: `qkt deploy portfolio.qkt` + `qkt list` shows portfolio + children.
- Daemon: `qkt status portfolio.qkt:child` returns child PnL.
- Daemon: `qkt stop portfolio.qkt` cascades through child deactivation cleanly.

---

## 10. Risk

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Gate transitions cause subtle ordering bugs (e.g., a child activated mid-tick sees the same tick a deactivated sibling already saw) | Medium | High | Gate evaluation happens BEFORE child onTick dispatch. Same tick, all gates evaluated, transitions applied (cleanup signals first), then active children receive the tick. Deterministic order. Unit tests for boundary cases. |
| Child indicator state divergence in backtest vs live (because of gating) | Low | Medium | Indicators stay current (N1) — they're CandleHub-level, decoupled from strategy lifecycle. Backtest/live use same CandleHub semantics. |
| Per-child strategyId collision | Low | High | strategyId format `<portfolio>:<alias>`; alias uniqueness enforced at parse. |
| Cycle detection misses a cycle | Low | Medium | Standard DFS-based detection at load time. Tests cover 2-cycle, 3-cycle, self-import. |
| Capital risk-stacking (N children using RISK 1% each) | Medium | High | Document loudly in changelog. v2's WEIGHT clause will partition. v1 is the same as running N strategies on one account today — known behavior. |
| HOLD child's open positions stranded if portfolio crashes | Low | Medium | Daemon's existing recovery path (12c BybitStateRecovery etc.) treats child positions as belonging to their child strategyId — no special portfolio recovery needed. |
| Round-trip equivalence breaks subtly between parser and Kotlin DSL | Medium | Medium | Round-trip tests for at least 2 portfolio shapes (mirrors STACK's 13a approach). |

---

## 11. Phase decomposition (preview for the plan)

Sub-phase α (≈3 tasks):
1. ChildRr support in stack outerBracket (`computeChildPrice` + `attachLayerTp` reorder).
2. Three new backtest tests (SELL, BELOW, concurrent stacks).
3. Update changelog known-limitations (remove ChildRr-throws line).

Sub-phase β (≈3 tasks):
4. Rename `Signal.CancelStacksForSymbol` → `Signal.CancelPendingForSymbol`. Generalize `OrderManager.cancelStacksForSymbol` → `cancelPendingForSymbol` to cover non-stack pending too. Update all call sites.
5. Implement `compileCancel` / `compileCancelAll` in ActionCompiler.
6. Tests + round-trip equivalence.

Sub-phase γ (≈18 tasks):
7. AST: `PortfolioAst`, `ImportClause`, `PortfolioRule` (`WhenRun` / `AlwaysRun`), file root sealed type.
8. Lexer: PORTFOLIO / IMPORT / AS / RUN / HOLD tokens.
9. Parser: top-level dispatch on first keyword.
10. Parser: `parsePortfolio` + `parseImport` + `parsePortfolioRule`.
11. Parser: error cases (cycle, unknown alias, duplicate alias, etc.).
12. Kotlin DSL: `portfolio { ... }` builder + helpers.
13. PortfolioLoader: file resolution, child parse, cycle detection.
14. ChildRunner + scoped StrategyContext.
15. PortfolioStrategy: gate eval, transition diff, dispatch.
16. PortfolioStrategy: deactivation cleanup (close-or-hold per HOLD flag).
17. Daemon: register portfolio with type marker; CompiledFactory dispatch.
18. Daemon: status sub-key syntax (`portfolio:child`).
19. Daemon: logs sub-key syntax.
20. Daemon: stop cascade through portfolio.
21. Backtest: PortfolioStrategy works in Backtest engine.
22. Round-trip equivalence: 2 portfolio shapes.
23. Integration tests: deploy / list / status / stop / logs (all with sub-keys).
24. Phase 13b changelog covering α + β + γ.

Estimated 24 tasks total across the three sub-phases.

---

## 12. Out of scope (explicit)

- WEIGHT clause / capital allocation per child.
- Import-time parameter overrides (`WITH { ... }`).
- Same strategy file imported multiple times.
- Nested PORTFOLIO inside another PORTFOLIO.
- Daemon hot-reload of portfolio file changes.
- State persistence across daemon restart (children re-evaluate gates fresh on restart).
- Per-child `DISABLE_ON_ERROR` policy.
- Portfolio-level risk caps.
- Per-child equity allocation in status snapshots.
- `regime()` built-in primitive (gate expressions are pure user-defined boolean expressions).
- Stack persistence inside HOLD children across daemon restart.

---

## 13. References

- Phase 13a (STACK) spec: `docs/superpowers/specs/2026-05-08-trading-engine-phase13a-stack-design.md`.
- Phase 13a changelog: `docs/phases/phase-13a-stack.md`.
- Phase 12c (daemon) spec: `docs/superpowers/specs/2026-05-08-trading-engine-phase12c-design.md`.
- `Strategy` interface: `src/main/kotlin/com/qkt/strategy/Strategy.kt`.
- `OrderManager.cancelStacksForSymbol` (becomes `cancelPendingForSymbol`): `src/main/kotlin/com/qkt/app/OrderManager.kt`.
- `Signal.CancelStacksForSymbol` (becomes `CancelPendingForSymbol`): `src/main/kotlin/com/qkt/strategy/Signal.kt`.
- Existing CANCEL deferred-error: `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt`.
