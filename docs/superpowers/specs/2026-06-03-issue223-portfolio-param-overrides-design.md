# Portfolio import-time parameter overrides (#223)

Status: design approved
Issue: #223 (P3, enhancement) — inject/override a child strategy's named parameters at portfolio import.

## Problem

A portfolio reuses child strategies, but a child hardcodes its tunables (risk pct, thresholds,
periods). Portfolio v3's `WEIGHT` (#69) covers only a child's *capital basis* (via `ACCOUNT.equity`);
everything else is fixed in the child file. To run the same logic at two different risk levels you
must copy the `.qkt` file and edit the number. There is no way for a portfolio to retune a child's
named parameters at import.

## Goals

- A child strategy can declare named, overridable scalar parameters with defaults.
- A portfolio `RUN` can override those parameters per-alias, inline, without editing the child:
  `RUN aggressive WEIGHT 0.6 OVERRIDE { riskPct = 0.008, threshold = 30 }`.
- The same child file, imported under two aliases, can run with different parameters.
- Overriding a name that the child did not declare as a parameter is a compile error.
- Overriding with a value of the wrong type is a compile error.
- The backtest=live bit-identical invariant is untouched.

## Non-goals

- **Expression overrides.** Override values are scalar literals only — not `threshold = ema(close,20)`.
  (Deferred; would extend the same `PARAM` mechanism if ever needed.)
- **Structured/per-hour-profile overrides.** Overriding whole tables/maps is out of scope. (If a
  per-hour profile is needed, it is expressed today as scalar-driven rules, which scalar params cover.)
- **CLI / runtime parameter injection.** Params are set by the portfolio override or their default,
  resolved once at compile time. They are not passed per-run on the CLI and never change at runtime.
- **Overriding `LET`s or other declarations.** Only `PARAM`s are overridable.

## The `PARAM` mechanism

A `PARAM` is a named, tunable scalar input to a strategy, with a required default. It is referenced
by name in expressions exactly like a `LET`, and evaluates to a fixed value (the same on every tick).
Unlike a `LET` (which holds a computed expression), a `PARAM` holds a literal — chosen once at
compile time as either its default or a portfolio override.

```
STRATEGY meanrev VERSION 1
PARAM riskPct   = 0.01     -- number
PARAM rsiPeriod = 14       -- number
PARAM threshold = 35       -- number
RULES
  WHEN rsi(gold.close, rsiPeriod) < threshold
    THEN BUY gold SIZING RISK $ (ACCOUNT.equity * riskPct)
```

- **Types:** the scalar literal kinds the DSL already has — number (`BigDecimal`), boolean, string,
  duration. A param's type is the type of its default; an override must match it.
- **Standalone:** a strategy with `PARAM`s runs directly (backtest/live) using the declared defaults —
  functionally identical to having hardcoded those literals.
- **Reference:** a `PARAM` name resolves like a `LET` name in any expression. `PARAM` and `LET` names
  share one namespace within a strategy (a `PARAM` may not collide with a `LET` or another `PARAM`).

## The `OVERRIDE` syntax

`OVERRIDE { key = literal, ... }` rides on a portfolio `RUN`, after the optional `WEIGHT` (mirroring
how `WEIGHT` already attaches to `RUN`):

```
PORTFOLIO book VERSION 1 CAPITAL 100000
IMPORT 'meanrev.qkt' AS aggressive
IMPORT 'meanrev.qkt' AS conservative
RULES
  RUN aggressive   WEIGHT 0.6 OVERRIDE { riskPct = 0.008, threshold = 30 }
  RUN conservative WEIGHT 0.4 OVERRIDE { riskPct = 0.003 }
```

- The block is optional; a `RUN` with no `OVERRIDE` behaves exactly as today.
- Each `key = literal` replaces that param's default for that alias's instance. Omitted params keep
  their declared defaults (`conservative` keeps `rsiPeriod=14`, `threshold=35`).
- Works on both rule forms: `WHEN <cond> RUN <alias> ...` and bare `RUN <alias> ...`.
- Order: `RUN <alias> [WEIGHT <n>] [OVERRIDE { ... }]`.

## Grammar + AST changes

Grounded in the current parser/AST (file:line from the codebase):

- **Tokens** (`dsl/parse/TokenKind.kt`, auto-registered by `Lexer.kt`): add `PARAM` and `OVERRIDE`.
- **Strategy AST** (`dsl/ast/StrategyAst.kt`): add `ParamDecl(name: String, value: <scalar literal>, ...)`
  to the strategy body, alongside the existing `LetDecl`. The strategy AST carries `params: List<ParamDecl>`.
  The value is held as the existing literal AST (`ExprAst.NumLit`/`BoolLit`/`StringLit`/duration) so the
  type is intrinsic.
- **Strategy parse** (`dsl/parse/Parser.kt`, strategy body loop): parse `PARAM <name> = <literal>`. The
  RHS must be a literal (reuse the literal-parsing path; reject a non-literal expression with a clear error).
- **Portfolio rule AST** (`dsl/ast/Portfolio.kt`): add `overrides: Map<String, ExprAst> = emptyMap()`
  (literal-valued) to both `WhenRun` and `AlwaysRun`.
- **Portfolio rule parse** (`dsl/parse/Parser.kt` `parsePortfolioRule`/`parseOptionalWeight`, ~210-236):
  after the optional `WEIGHT`, parse an optional `OVERRIDE { key = literal (, key = literal)* }` into the
  map. Duplicate keys in one block → error.

## Compile seam + determinism

The override is a **pure, compile-time AST transform**, applied at the one place children are compiled:

- In `dsl/portfolio/PortfolioLoader.kt` (~42-77), each child file is parsed then compiled
  (`AstCompiler().compile(childAst)`, ~line 66). **Before** that compile call, apply the alias's
  overrides: produce a copy of `childAst` whose matching `ParamDecl` default values are replaced by the
  override literals. Compile the transformed AST.
- A `PARAM` lowers like a literal-valued binding (the same shape a `LET = <literal>` produces): a named
  constant in the compiled `Strategy`. The override only changes which literal.
- **Determinism:** compilation is synchronous, happens once at load, and runs the identical code path for
  backtest (`BacktestCommand`) and live (`RunCommand`). The transform reads no clock, randomness, or
  environment — only the static portfolio file. Given the same portfolio file, the compiled child is
  bit-identical across backtest and live, so the invariant holds. (Confirmed against `ScheduleRunner`'s
  tick-only time guarantee — params are resolved before any tick.)

## Validation / error handling

All at parse/compile time, via the existing `ParseError(line, col, message)`:

- **Unknown override key:** an `OVERRIDE` key that is not a declared `PARAM` of that child →
  `OVERRIDE: child '<alias>' has no PARAM '<key>'`. (Checked in `PortfolioLoader` once the child AST is
  parsed, since the child's param list is needed; carries the override's source line/col.)
- **Type mismatch:** override literal's type ≠ the param's default type →
  `OVERRIDE: PARAM '<key>' of '<alias>' is <type>, got <type>`.
- **PARAM default not a literal:** `PARAM x = ema(...)` → `PARAM must be a literal value`.
- **Name collision:** a `PARAM` colliding with a `LET`/`PARAM` name in the same strategy → error (AST
  `init`/validation, mirroring `Portfolio.kt`'s existing `require(...)` style).
- **Duplicate override key** in one block → error.

## Testing

- **Parse — PARAM** (`ParserStrategy*`/round-trip tests): `PARAM x = 0.01` of each scalar type parses to
  `ParamDecl`; non-literal default errors; collision with a `LET` errors.
- **Parse — OVERRIDE** (`ParserPortfolioTest`, mirroring the WEIGHT tests ~116-132):
  `RUN a WEIGHT 0.6 OVERRIDE { riskPct = 0.005, p = 20 }` parses to the overrides map on the rule; bare
  `RUN a OVERRIDE {...}` (no weight); duplicate key errors.
- **Apply** (`PortfolioLoaderTest`): a portfolio overriding a child's PARAM compiles a child whose param
  resolves to the override value; an omitted param keeps its default; the same child under two aliases
  with different overrides yields two differently-tuned compiled children.
- **Validation:** unknown key → expected `ParseError`; type mismatch → expected error.
- **Determinism guard:** the same portfolio file compiled twice yields identical compiled output; a
  param override changes the compiled child exactly as setting the literal in the child file would
  (compare against a hand-edited child).

## Backtest invariant / safety

Parameter overrides are resolved entirely at compile time, before any market data flows, on the shared
backtest/live compile path. They introduce no runtime branch and no order-path behavior. The compiled
strategy is a pure function of the portfolio + child files. The determinism invariant is preserved.

## References

- Issue #223. Split from #69 (Portfolio v3 WEIGHT); orthogonal to capital allocation.
- Parser/AST: `dsl/parse/Parser.kt` (`parsePortfolioRule` ~210-236, strategy body), `dsl/ast/Portfolio.kt`
  (`WhenRun`/`AlwaysRun` ~78-90), `dsl/ast/StrategyAst.kt` (`LetDecl` ~49-56, latent `ConstantDecl` ~40-47).
- Compile seam: `dsl/portfolio/PortfolioLoader.kt` (~42-77), `dsl/compile/AstCompiler.kt` (~27).
- WEIGHT prior art: `dsl/portfolio/PortfolioAllocation.kt` (~20-37).
- Errors: `dsl/parse/ParseError.kt`.
