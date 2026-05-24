# Phase 37 — Proportional STACK_AT sizing

**Status:** design
**Date:** 2026-05-24

## Phase

Phase 37. Engine + DSL relaxation of a Phase 27 restriction.

## Goal

Let a `STACK_AT` clause's `SIZING` expression reference the parent leg's filled
quantity, so stack legs scale proportionally with the main entry instead of
sitting on a literal hard-coded value:

```qkt
STACK_AT MFE >= 5 WITHIN 30m
    SIZING 0.5 * ENTRY_QTY + 0.05
    BRACKET { STOP LOSS BY 2.0; TAKE PROFIT BY 4.0 }
```

A single new keyword `ENTRY_QTY` exposes the parent's filled quantity inside
the sizing expression. Literals, arithmetic, and `ENTRY_QTY` are the entire
permitted vocabulary — no indicators, no stream fields, no `NOW.<field>`.

## Background

- Phase 27 introduced `STACK_AT` and constrained every clause field (MFE
  threshold, sizing, bracket distances) to be a compile-time constant. The
  rationale, recorded in `StackAtCompiler.kt`, was performance: keep the
  per-tick path free of expression evaluation.
- Hedge-straddle hits a gap (documented as GAP 2 in the strategy header):
  the main entry leg's lot size varies by hour-of-day (small in calm sessions,
  larger during liquidity-rich windows). Stack legs are sized by literal
  numbers and don't track the main leg, so a small main can be paired with
  oversized stacks (and vice versa).
- The fix is narrow: defer evaluation of the sizing expression from compile
  time to **parent-fill time** — the moment the orchestrator already learns
  `parentEntryPrice`. The parent's filled quantity is the only new identifier
  needed; everything else (MFE, bracket distances) stays compile-time
  constant.

## Non-goals

- Allowing `ENTRY_QTY` (or any non-constant identifier) in `MFE >=` thresholds
  or bracket SL/TP distances. The issue scope is sizing only. Each of those
  fields would need its own constraint relaxation and its own fire-time
  evaluation; defer until a strategy actually needs them.
- Allowing indicators (`SMA`, `ATR`, …), stream fields (`gold.close`,
  `gold.tick_size`), or `NOW.<field>` inside `STACK_AT SIZING`. They are
  rejected with the same pointed error the Phase 27 path uses today.
- Supporting non-`SizeQty` sizing variants (`SIZE_RISK FRAC`,
  `SIZE_PCT_EQUITY`, etc.) inside `STACK_AT`. Phase 27 already rejects those;
  this phase keeps the rejection.

## Approach

### Surface

A new keyword `ENTRY_QTY` reads as the parent leg's filled-fill quantity. It
is parsable only inside an expression and (per the compiler check) only inside
a `STACK_AT SIZING` expression. Examples:

```qkt
-- Constant fraction of the parent
SIZING 0.3 * ENTRY_QTY

-- Affine: 50% of parent plus a 0.05 lot floor
SIZING 0.5 * ENTRY_QTY + 0.05

-- Pure literal still works (regression-only path)
SIZING 0.10
```

The Phase 27 wording "compile-time constant" relaxes to "compile-time
fixed-form": the lambda is built at compile, then resolved with a single
runtime value at parent-fill time.

### Components

Six files change. None of them are large.

**`com.qkt.dsl.ast.ExprAst`** — one new leaf:

```kotlin
data object EntryQty : ExprAst
```

Same shape as `StackEntryRef`. No fields.

**`com.qkt.dsl.parse.Lexer` + `Parser`** — add `TokenKind.ENTRY_QTY` to the
keyword table and route it to the primary-expression switch:

```kotlin
TokenKind.ENTRY_QTY -> { advance(); EntryQty }
```

**`com.qkt.dsl.compile.StackAtCompiler`** — replace the constant-only
`compileSizing` with two helpers that build a `(BigDecimal) -> BigDecimal`
lambda:

```kotlin
private fun compileSizing(sizing: SizingAst): (BigDecimal) -> BigDecimal =
    when (sizing) {
        is SizeQty -> compileSizingExpr(sizing.expr)
        else ->
            error("STACK_AT only supports SIZING <qty-expr> (lots); got ${sizing::class.simpleName}")
    }

private fun compileSizingExpr(expr: ExprAst): (BigDecimal) -> BigDecimal =
    when (expr) {
        is NumLit -> { _ -> expr.value }
        EntryQty -> { parentQty -> parentQty }
        is UnaryOp ->
            when (expr.op) {
                UnOp.NEG -> compileSizingExpr(expr.arg).let { f -> { p -> f(p).negate() } }
                UnOp.NOT -> error("STACK_AT SIZING: boolean NOT is not a numeric expression")
            }
        is BinaryOp -> compileSizingBinary(expr)
        else ->
            error(
                "STACK_AT SIZING must use literals, ENTRY_QTY, and arithmetic only; " +
                    "got ${expr::class.simpleName}",
            )
    }

private fun compileSizingBinary(expr: BinaryOp): (BigDecimal) -> BigDecimal {
    val l = compileSizingExpr(expr.lhs)
    val r = compileSizingExpr(expr.rhs)
    return when (expr.op) {
        BinOp.ADD -> { p -> l(p).add(r(p)) }
        BinOp.SUB -> { p -> l(p).subtract(r(p)) }
        BinOp.MUL -> { p -> l(p).multiply(r(p)) }
        BinOp.DIV -> { p -> l(p).divide(r(p), Money.SCALE, Money.ROUNDING) }
        BinOp.AND, BinOp.OR ->
            error("STACK_AT SIZING: boolean operator ${expr.op} is not a numeric expression")
    }
}
```

The existing `evalConstant` helper stays unchanged and continues to gate
`mfeThreshold` + bracket SL/TP distances.

**`com.qkt.dsl.compile.StackEngine` — split `CompiledStackTier` into compile-
and resolve-time forms.**

```kotlin
/** Compile-time tier shape: sizing is a lambda over the parent's filled qty. */
data class CompiledStackTier(
    val mfeThreshold: BigDecimal,
    val withinMs: Long,
    val resolveStackQuantity: (BigDecimal) -> BigDecimal,
    val slDistance: BigDecimal,
    val tpDistance: BigDecimal,
)

/** Resolved form: lambda already applied with the actual parent qty. */
internal data class ResolvedStackTier(
    val mfeThreshold: BigDecimal,
    val withinMs: Long,
    val stackQuantity: BigDecimal,
    val slDistance: BigDecimal,
    val tpDistance: BigDecimal,
)
```

`StackEngine.tiers` becomes `List<ResolvedStackTier>` (replacing
`List<CompiledStackTier>`). The engine's per-tick logic is unchanged in
behavior — `buildStackSignal` still reads `tier.stackQuantity` as a plain
`BigDecimal`; only the field's owning type changes name.

**`com.qkt.dsl.compile.StackOrchestrator.onPrimaryFilled`** — adds
`parentQty: BigDecimal`, resolves each tier once before constructing the
engine:

```kotlin
fun onPrimaryFilled(
    parentLegId: String,
    parentSymbol: String,
    parentSide: Side,
    parentEntryPrice: BigDecimal,
    parentQty: BigDecimal,
    tiers: List<CompiledStackTier>,
    closeWatchIds: Set<String> = emptySet(),
) {
    ...
    val resolved = tiers.map { c ->
        ResolvedStackTier(
            mfeThreshold = c.mfeThreshold,
            withinMs = c.withinMs,
            stackQuantity = c.resolveStackQuantity(parentQty),
            slDistance = c.slDistance,
            tpDistance = c.tpDistance,
        )
    }
    engines[parentLegId] = StackEngine(..., tiers = resolved, ...)
}
```

**`com.qkt.app.OrderManager` (or the call site of `onPrimaryFilled`)** — pass
the fill quantity through. The fill event already carries it
(`BrokerEvent.OrderFilled.quantity`), so this is a one-line param threading.

### Data flow

```
Parse (in STACK_AT SIZING context):
  0.5 * ENTRY_QTY + 0.05
  → SizeQty(BinaryOp(ADD,
              BinaryOp(MUL, NumLit(0.5), EntryQty),
              NumLit(0.05)))

Compile (StackAtCompiler):
  compileSizing(...)        → λ(p) = 0.5·p + 0.05
  → CompiledStackTier(threshold=5, withinMs=…, resolveStackQuantity=λ, sl=2, tp=4)

Parent fills (OrderManager observes BrokerEvent.OrderFilled qty=0.3):
  stackOrchestrator.onPrimaryFilled(..., parentQty=0.3, tiers=[CompiledStackTier])
  → ResolvedStackTier(threshold=5, withinMs=…, stackQuantity=λ(0.3)=0.20, sl=2, tp=4)
  → StackEngine(tiers=[ResolvedStackTier], …)  // engine body unchanged

Stack fires (StackEngine.onTick observes MFE ≥ 5):
  buildStackSignal uses tier.stackQuantity = 0.20
  → emits OrderRequest.Bracket(quantity=0.20, …)
```

### Error handling

Five surfaces, all caught at compile time. No new runtime errors.

| Trigger | Message | Stage |
|---|---|---|
| `ENTRY_QTY` in `mfeThreshold` or bracket distance | `STACK_AT MFE threshold must be a compile-time constant; got EntryQty` | `evalConstant` (unchanged) |
| Indicator / stream-field / `NOW` in sizing | `STACK_AT SIZING must use literals, ENTRY_QTY, and arithmetic only; got <variant>` | `compileSizingExpr` |
| Boolean operator in sizing | `STACK_AT SIZING: boolean operator AND is not a numeric expression` | `compileSizingBinary` |
| Non-`SizeQty` sizing variant | `STACK_AT only supports SIZING <qty-expr> (lots); got <variant>` | `compileSizing` (existing) |
| `ENTRY_QTY` outside `STACK_AT SIZING` (e.g. in a regular `WHEN`) | `Unknown identifier ENTRY_QTY` from the regular `ExprCompiler` path, or a parser error if the keyword is registered globally | depending on lexer scope decision below |

A subtle decision: the `ENTRY_QTY` keyword either is parsed everywhere (in
which case the regular `ExprCompiler` must reject it with a clear message —
"ENTRY_QTY only valid inside STACK_AT SIZING"), or only inside the STACK_AT
sizing scope (lexer-scoped, more invasive). The simpler choice is global lex
+ regular-compiler reject, so the parser stays one-pass and untouched.

### Testing

Two new test files plus light extensions to existing parser tests.

**`StackAtCompilerEntryQtyTest`** (new, ~7 tests, ~120 lines):

- `ENTRY_QTY alone resolves to parentQty`
- `0.3 * ENTRY_QTY resolves to 0.3 * parentQty`
- `0.5 * ENTRY_QTY + 0.05 resolves to the affine combination`
- `ENTRY_QTY / 3 rounds via Money.SCALE`
- `pure literal still works (regression — SIZING 0.10)`
- `ENTRY_QTY rejected at compile in mfeThreshold`
- `Ref or StreamFieldRef in sizing rejected at compile with the unified
  ENTRY_QTY-or-arithmetic-only message`

Fixture: a `StackAtClause` built directly via AST constructors, fed through
`StackAtCompiler.compile`, and the resulting lambda applied with a known
parent qty.

**`StackOrchestratorEntryQtyTest`** (new, ~50 lines, 1 test): synthesise a
clause with `ENTRY_QTY * 0.5`, call `onPrimaryFilled` with `parentQty=0.40`,
trigger an MFE crossing on the engine, assert the emitted `OrderRequest.Bracket`
has `quantity = 0.20`.

**`ParserStackAtTest`** (extend) — one assertion that `... SIZING ENTRY_QTY *
0.3 ...` parses to the expected AST shape.

**`ExprCompilerEntryQtyTest`** (extend or new) — one assertion that
`EntryQty` outside a `STACK_AT SIZING` context fails compile with a clear
"ENTRY_QTY only valid inside STACK_AT SIZING" message.

Around 10 new tests across two-or-three files. No new test infrastructure.

## Risks

- **Lexer-vs-scope confusion.** Recognising `ENTRY_QTY` globally means it can
  appear in any expression position the parser accepts, and the
  reject-elsewhere lives in `ExprCompiler`. A reader of a non-STACK_AT `WHEN
  ENTRY_QTY > 0` strategy sees a compile error, not a parse error. Mitigation:
  ensure the `ExprCompiler` rejection message names `STACK_AT SIZING`
  explicitly so the operator knows where it belongs.
- **Numeric edge cases.** `BigDecimal.divide(r, Money.SCALE, Money.ROUNDING)`
  was the existing path; reusing it preserves rounding semantics. A
  pathological `ENTRY_QTY / 0` is undefined and would throw at fire time —
  documented in error handling above, no defensive guard.
- **Restart fidelity.** Phase 29 persists fired-tier state. The persisted
  shape stores `stackQuantity: BigDecimal` (already resolved) and **does not**
  store the sizing lambda or the source expression. A daemon restart while a
  stack engine is mid-lifecycle replays the stored resolved values — correct
  behavior because the parent qty at original fill time is what mattered.
  Documented in the changelog.

## Open questions

None for this phase. Scope is narrow because the issue text directly names
the gap and the fix shape.

## References

- Issue: <https://github.com/elitekaycy/qkt/issues/49>
- Predecessor: [Phase 27 — conditional bracketed stacks](../../phases/phase-27-conditional-bracketed-stacks.md)
- Pattern source (compile-then-resolve): [Phase 39 — INSTRUMENT_META DSL accessor spec](2026-05-24-phase39-instrument-meta-dsl-design.md)
