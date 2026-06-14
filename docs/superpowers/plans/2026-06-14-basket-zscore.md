# BASKET pseudo-symbol + ratio z-score — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a strategy declare an equal-weight `BASKET` of bound symbols as a single
tradeable pseudo-stream (`antipodean.close` reads a composite price; `BUY/SELL/CLOSE
antipodean` fan out to constituents) and express a cross-symbol ratio z-score
(`zscore(gold.close / antipodean.close, 100) >= 2.0`). Compile to a deterministic,
backtest=live construct.

**Key finding driving the sequencing:** `zscore` **already ships** — `ZScore` indicator +
`ZSCORE` registry entry + expression-fed binding (#174). It already accepts
`zscore(<arbitrary expr referencing a stream>, n)`. So **Phase A is verification + docs**
(lands immediately, independent of BASKET). **Phase B is the real work: the BASKET synthetic
tradeable stream.** A basket is a stream with identity `HubKey("BASKET", <alias>, <tf>)`
whose composite candle is computed from its constituents' aligned bars and written into the
`CandleHub`; reads/positions/warmup all reuse existing per-stream paths.

**Composite definition (confirm before Phase B Task 5):** equal-weight **log-return index**,
`I(t) = I(t-1) * exp( (1/N) Σ ln(p_i(t)/p_i(t-1)) )`, `I(t0)=100`. Scale-invariant so the
ratio z-score thresholds are stable. This contradicts the issue's "price-weighted average"
wording — see spec §4 and Open Question 1.

**Tech Stack:** Kotlin/JVM, JUnit5 + AssertJ, no mocks (anonymous objects + real types).

**Spec:** `docs/superpowers/specs/2026-06-14-basket-zscore-design.md`. **Issue:** #457.

**Design decisions adopted from the spec (confirm before building):**
- Composite = equal-weight **log-return index**, BASE 100 (spec §4b). *(needs confirmation — Open Q1)*
- Basket identity = `HubKey("BASKET", alias.uppercased(), tf)` (spec §5b).
- Fan-out sizing = **equal notional** per constituent (`total/N`), reusing the `SizeNotional` formula (spec §5d).
- `POSITION.<basket>` = unit-normalized **+1 / -1 / 0** (spec §5e).
- Composite candle: `open=I(t-1)`, `close=I(t)`, `high/low`=max/min, `volume=0` (spec §5c).
- Basket orders are **plain market** in v1; brackets/TIF/stack on a basket = compile error (spec §5d).
- Beta-weighting is **out of scope** (P2 follow-up, spec §8).

---

## File Structure

Phase A (zscore — verify + document):
- `src/test/kotlin/com/qkt/backtest/RatioZScoreBacktestTest.kt` — end-to-end ratio-z-score backtest (real data, determinism).
- `docs/reference/dsl/indicators.md` — add the `zscore` entry + pairs-spread example.

Phase B (BASKET):
- `src/main/kotlin/com/qkt/dsl/ast/BasketDecl.kt` — `BasketDecl` + `BasketWeighting` sealed type.
- `src/main/kotlin/com/qkt/dsl/ast/StrategyAst.kt` — add `baskets: List<BasketDecl>`.
- `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt` — add `BASKET`, `EQUAL_WEIGHT`.
- `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` — parse basket lines in `parseSymbols`; carry on `SymbolsBlock`.
- `src/main/kotlin/com/qkt/dsl/compile/BasketCompositor.kt` — rolling index state + composite-candle synthesis.
- `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt` — register basket streams, implicit sync-group, wire compositor, fan-out validation.
- `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt` — fan-out for `BUY/SELL/CLOSE` on a basket alias.
- `src/main/kotlin/com/qkt/dsl/compile/SizingCompiler.kt` — reuse for per-constituent notional (no new mode expected).
- `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt` — basket-aware `POSITION.<basket>`.
- `src/main/kotlin/com/qkt/positions/PositionLeg.kt` — nullable `basketAlias` tag.
- `examples/` — a market-neutral XAU-vs-antipodean example strategy.
- `docs/reference/dsl/` — basket documentation page.
- Tests alongside each, plus the end-to-end backtest.

---

# Phase A — zscore (already implemented: verify + document)

### Task A1: End-to-end ratio z-score backtest

**Files:**
- Create: `src/test/kotlin/com/qkt/backtest/RatioZScoreBacktestTest.kt`

Prove the *existing* `zscore` works end-to-end as a cross-symbol ratio trigger and is
deterministic. Use two real stored symbols (e.g. the XAUUSD + AUDUSD data the backtest
suite already exercises) at one timeframe, a strategy with
`WHEN zscore(a.close / b.close, N) >= 2.0 THEN ...` and the symmetric `<= -2.0`, plus a
reversion exit.

- [ ] **Step 1: failing test** — assert (a) the strategy compiles, (b) it produces ≥1 trade
  on the chosen window, (c) two runs over the same range are bit-identical (reuse the parity
  assertion style already in the backtest tests).
- [ ] **Step 2:** run; if `zscore` already satisfies all three, the test passes immediately
  — that is the expected and desired outcome (it documents the existing capability). If it
  fails, capture the exact reason (likely an alignment/warmup detail) before any change.
- [ ] **Step 3 (only if Step 2 fails):** root-cause and fix the minimal binding/alignment
  issue; do **not** rewrite `ZScore`.
- [ ] **Step 4:** `./gradlew test --tests '*RatioZScoreBacktestTest*'` green.
- [ ] **Step 5:** `./gradlew ktlintFormat` + commit `test(backtest): end-to-end ratio z-score entry`.

### Task A2: Document `zscore`

**Files:**
- Modify: `docs/reference/dsl/indicators.md`

Add a `zscore(series, period)` entry: definition (`(latest − mean)/sample-stddev`), warmup
= period, null-on-flat / null-until-warm semantics, and a **pairs-spread worked example**
(`zscore(gold.close / silver.close, 100) >= 2.0`). Note that the series may be any arithmetic
expression referencing at least one stream (expression-fed binding, #174), and that a
cross-stream series should share a `SYNCHRONIZE` group for same-window alignment.

- [ ] **Step 1:** write the entry, matching the page's existing indicator-doc style.
- [ ] **Step 2:** `mkdocs build --strict` green (if docs site is built locally).
- [ ] **Step 3:** commit `docs(dsl): document zscore ratio-spread function`.

> Phase A is independent of Phase B and can merge on its own.

---

# Phase B — BASKET synthetic tradeable stream

### Task B1: `BasketDecl` AST

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/ast/BasketDecl.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/ast/StrategyAst.kt` (add `baskets: List<BasketDecl> = emptyList()`)
- Test: `src/test/kotlin/com/qkt/dsl/ast/BasketDeclTest.kt`

`BasketWeighting` sealed interface with sole member `EqualWeight` (reserves space for
`BetaWeighted` later). `BasketDecl(alias, weighting, constituents: List<String>, timeframe)`
with `init` requires: alias non-blank, `constituents.size >= 2`, constituents distinct,
timeframe non-blank. KDoc with a one-line example (`antipodean = BASKET EQUAL_WEIGHT [aud, nzd] EVERY 1h`).

- [ ] **Step 1: failing test** — construct a valid `BasketDecl`; assert each `require`
  rejects (one constituent, duplicate constituents, blank alias).
- [ ] **Step 2:** run, expect FAIL (class missing).
- [ ] **Step 3:** implement; add the field to `StrategyAst` (default empty, no other call-site churn).
- [ ] **Step 4:** run, expect PASS.
- [ ] **Step 5:** `ktlintFormat` + commit `feat(dsl): add BasketDecl AST node`.

### Task B2: Lexer keywords + parse basket lines

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/parse/TokenKind.kt` (add `BASKET`, `EQUAL_WEIGHT`)
- Modify: `src/main/kotlin/com/qkt/dsl/parse/Parser.kt` (`parseSymbols`, `SymbolsBlock`)
- Test: `src/test/kotlin/com/qkt/dsl/parse/BasketParseTest.kt`

Adding the two `TokenKind` entries auto-registers them as keywords (`Lexer.KEYWORDS` derives
from `TokenKind.values()`). In `parseSymbols`, when a stream line is `<alias> = BASKET ...`,
branch to a basket-line parser: `BASKET EQUAL_WEIGHT '[' IDENT (',' IDENT)+ ']' EVERY <tf>`.
Collect `BasketDecl`s on `SymbolsBlock` (add a `baskets` field) and thread them into
`StrategyAst` at `parseStrategy`. The disambiguation point is the token after `=`: `BASKET`
vs an `IDENT` broker prefix.

- [ ] **Step 1: failing test** — parse a SYMBOLS block mixing real streams and a basket;
  assert the `BasketDecl` fields. Assert errors for: missing `[`, single constituent,
  trailing comma / empty list, unknown weighting word.
- [ ] **Step 2:** run, expect FAIL.
- [ ] **Step 3:** implement the token entries + parser branch.
- [ ] **Step 4:** run, expect PASS; also run the full parser suite (no regressions in
  `parseSymbols`).
- [ ] **Step 5:** `ktlintFormat` + commit `feat(dsl): parse BASKET EQUAL_WEIGHT declarations`.

### Task B3: Constituent-binding validation in AstCompiler

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/BasketValidationTest.kt`

Before any composite/fan-out wiring, add compile-time validation (spec §6):
unbound constituent (not a declared real stream), basket-of-baskets (constituent is itself a
basket), and timeframe mismatch (basket `tf` ≠ a constituent's `tf`). Each is a clear,
named error. No runtime behaviour yet — this task only rejects bad strategies.

- [ ] **Step 1: failing test** — assert each invalid strategy throws with the expected
  message substring; a valid one does not throw.
- [ ] **Step 2:** run, expect FAIL.
- [ ] **Step 3:** implement the validations in `compile` (alongside the existing
  `rejectMacroOrders` block).
- [ ] **Step 4:** run, expect PASS.
- [ ] **Step 5:** `ktlintFormat` + commit `feat(dsl): validate BASKET constituent bindings`.

### Task B4: Register basket streams + implicit sync-group

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/BasketRegistrationTest.kt`

In `compile`: for each `BasketDecl`, add `alias -> HubKey("BASKET", alias.uppercased(), tf)`
to `streams`, and add the basket key to `retentionByKey`. Build an implicit `SyncGroupKey`
over the constituents and register/subscribe it in `bindToHub` (reuse the existing
sync-group path). **No composite computed yet** — the sync callback can be a no-op stub. The
point of this task is that a basket alias is now a registered stream: `streamAliasesIn` finds
it, `ctx.streams` contains it, and `compileCandleField` would read its (still empty) hub key
without error.

- [ ] **Step 1: failing test** — compile a strategy with a basket; assert `declaredStreams`
  contains the basket alias mapped to `HubKey("BASKET", ...)`; assert retention includes it.
- [ ] **Step 2:** run, expect FAIL.
- [ ] **Step 3:** implement registration + implicit sync-group wiring (stub callback).
- [ ] **Step 4:** run, expect PASS.
- [ ] **Step 5:** `ktlintFormat` + commit `feat(dsl): register BASKET as a synthetic stream`.

### Task B5: BasketCompositor — composite candle from aligned bars

**Files:**
- Create: `src/main/kotlin/com/qkt/dsl/compile/BasketCompositor.kt`
- Test: `src/test/kotlin/com/qkt/dsl/compile/BasketCompositorTest.kt`

> **Confirm Open Q1 (composite definition) before implementing.**

A `BasketCompositor` holds, per basket: previous index `I(t-1)` and previous aligned close
per constituent. Method `onAligned(bars: Map<alias, Candle>): Candle` computes
`R(t) = (1/N) Σ ln(p_i(t)/p_i(t-1))`, `I(t) = I(t-1) * exp(R(t))`, with `I(t0)=100` and the
first aligned bar emitting `close=open=high=low=100` (R=0). Synthesize the basket candle per
spec §5c (`open=I(t-1)`, `close=I(t)`, `high/low`=max/min, `volume=0`, window from the
aligned bars). Pure BigDecimal `Money.CONTEXT`, reuse `FuncRegistry` `LOG`/`EXP` numeric
convention. Return `null` until the second aligned bar (the first *return*).

- [ ] **Step 1: failing test** — feed deterministic aligned closes over several windows;
  assert exact index values (e.g. +1% then −1% returns ~100); assert null before the second
  aligned bar; assert the synthesized OHLC fields.
- [ ] **Step 2:** run, expect FAIL.
- [ ] **Step 3:** implement; keep it allocation-light (O(N) per window).
- [ ] **Step 4:** run, expect PASS.
- [ ] **Step 5:** `ktlintFormat` + commit `feat(dsl): add BASKET log-return composite index`.

### Task B6: Wire compositor into the sync callback + hub write

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt` (replace the B4 stub)
- Test: `src/test/kotlin/com/qkt/dsl/compile/BasketEvalTest.kt`

In the basket's sync callback: call `compositor.onAligned(bars)`, and if non-null **write the
candle into the hub** under the basket key, then run the basket's own
update-then-fire (its indicator bindings + rules) on that synthesized close — reusing the
two-pass the sync path already does for aligned aliases. After this, `antipodean.close` in a
rule reads the live composite from the hub via the **unchanged** `compileCandleField`.

- [ ] **Step 1: failing test** — drive a hub with constituent bars across windows; assert
  `antipodean.close` (via a compiled `StreamFieldRef` eval, or a rule that fires on it)
  equals the compositor's index; assert it is `Undefined` before warm.
- [ ] **Step 2:** run, expect FAIL.
- [ ] **Step 3:** implement the callback + hub write + basket rule/indicator firing.
- [ ] **Step 4:** run, expect PASS.
- [ ] **Step 5:** `ktlintFormat` + commit `feat(dsl): compute and publish BASKET composite bars`.

### Task B7: `zscore` over a basket ratio (composition test)

**Files:**
- Test: `src/test/kotlin/com/qkt/dsl/compile/BasketZScoreCompositionTest.kt`

No production code expected — verify the two primitives compose:
`zscore(gold.close / antipodean.close, N)` compiles (basket alias satisfies the primary-alias
gate), binds with `gold` primary, and yields the expected scalar on a hand-built series with
`gold` and the basket in a shared sync group. If a real alignment gap surfaces, fix it
minimally here (spec §7b) — but the expectation is it already works.

- [ ] **Step 1: failing test** — assert compile + an expected z-score value sequence; assert
  Undefined during warmup.
- [ ] **Step 2:** run; expected PASS (or minimal alignment fix).
- [ ] **Step 3:** `ktlintFormat` + commit `test(dsl): zscore over a BASKET ratio composes`.

### Task B8: Order fan-out — `BUY` / `SELL` antipodean

**Files:**
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt`
- Modify: `src/main/kotlin/com/qkt/dsl/compile/AstCompiler.kt` (pass basket→constituents map into the action compiler)
- Test: `src/test/kotlin/com/qkt/dsl/compile/BasketFanOutTest.kt`

When `compileBuySell`'s `stream` is a basket alias, emit **one order per constituent** on its
real `qktSymbol`, each sized to `total/N` notional via the existing `SizeNotional` formula
(resolve the action's sizing once; divide notional by N; per constituent
`qty_i = (notional/N)/(price_i * contractSize_i)`). Same side for all constituents. Reject
(compile error) any basket order carrying bracket/OCO/TIF/stack options (spec §5d). Tag each
emitted order so the resulting legs can be marked with the basket alias (Task B9).

- [ ] **Step 1: failing test** — `BUY antipodean SIZING NOTIONAL X` emits N `Signal`s whose
  per-constituent notionals equal `X/N` (assert quantities against fixed instrument
  metadata); `SELL` emits the symmetric side; a basket order with a bracket is rejected at
  compile.
- [ ] **Step 2:** run, expect FAIL.
- [ ] **Step 3:** implement the fan-out branch + option rejection.
- [ ] **Step 4:** run, expect PASS.
- [ ] **Step 5:** `ktlintFormat` + commit `feat(dsl): fan out BASKET orders to constituents`.

### Task B9: Leg tagging + `POSITION.<basket>` + `CLOSE` fan-out

**Files:**
- Modify: `src/main/kotlin/com/qkt/positions/PositionLeg.kt` (nullable `basketAlias`)
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ExprCompiler.kt` (`compilePositionRef` basket case)
- Modify: `src/main/kotlin/com/qkt/dsl/compile/ActionCompiler.kt` (`Close` basket fan-out)
- Test: `src/test/kotlin/com/qkt/dsl/compile/BasketPositionTest.kt`

Add `basketAlias: String? = null` to `PositionLeg` (storage key unchanged). Fanned-out
constituent fills carry the basket alias. `POSITION.antipodean` returns **+1** if all basket
legs are long, **-1** if all short, **0** if none — reading only legs tagged with that
basket. `CLOSE antipodean` fans out: for each constituent, emit the same close signals
`closeSignalsFor` already produces. One basket close → N constituent closes.

- [ ] **Step 1: failing test** — after a fanned `BUY`, `POSITION.antipodean` = +1 and each
  leg carries the basket tag; after `CLOSE antipodean`, N close signals are emitted, one per
  constituent; flat → `POSITION.antipodean` = 0.
- [ ] **Step 2:** run, expect FAIL.
- [ ] **Step 3:** implement leg tag + position-ref case + close fan-out.
- [ ] **Step 4:** run, expect PASS; run the full `positions` suite (no regressions — the new
  field defaults null).
- [ ] **Step 5:** `ktlintFormat` + commit `feat(dsl): basket position view and CLOSE fan-out`.

### Task B10: End-to-end market-neutral backtest

**Files:**
- Create: `src/test/kotlin/com/qkt/backtest/BasketSpreadBacktestTest.kt`
- Create: `examples/<basket-spread-example>.qkt` (the XAU-vs-antipodean strategy)

The full motivating strategy: `SELL gold` + `BUY antipodean` on `zscore(gold.close /
antipodean.close, 100) >= 2.0` (symmetric at `<= -2.0`), exit on reversion to 0, stop at
±3.5, time-stop. Run on real stored bars for XAUUSD + AUDUSD + NZDUSD at one timeframe.

- [ ] **Step 1: failing test** — assert: compiles; produces gold + both-constituent legs on
  entry; closes all on exit; **two runs are bit-identical** (parity invariant); no mocks.
- [ ] **Step 2:** run, expect FAIL until the path is complete.
- [ ] **Step 3:** finalize the example strategy + any last wiring.
- [ ] **Step 4:** `./gradlew test --tests '*BasketSpreadBacktestTest*'` green.
- [ ] **Step 5:** `ktlintFormat` + commit `test(backtest): end-to-end market-neutral basket spread`.

### Task B11: Documentation + phase changelog

**Files:**
- Create: `docs/reference/dsl/basket.md`
- Modify: `docs/reference/dsl/index.md` (link it)
- Phase changelog if this is folded into a numbered phase (per qkt skill §6).

Document the `BASKET EQUAL_WEIGHT [...]` grammar, the composite definition (log-return index,
with the worked +1%/−1% example), fan-out + sizing semantics, `POSITION.<basket>` /
`CLOSE <basket>`, all validation errors, and the named out-of-scope items (beta-weighting,
per-leg brackets). Cross-link the spec.

- [ ] **Step 1:** write the page + index link; add a usage-cookbook example.
- [ ] **Step 2:** `mkdocs build --strict` green (if built locally).
- [ ] **Step 3:** commit `docs(dsl): document BASKET equal-weight composite`.

---

## Pre-PR checklist (per qkt skill §4)

- [ ] `./gradlew build` green (compile + all tests + assemble).
- [ ] `git log --oneline <base>..HEAD` — every message follows Conventional Commits, no AI footer.
- [ ] `grep -rEn 'TODO|FIXME|XXX' src/` — none new without an issue link.
- [ ] PR targets `dev`; body uses the template; `Refs #457` (prod-gated) or `Fixes #457` per the dev-default rule.

## Sequencing summary

- **Phase A (A1-A2)** verifies + documents the **already-shipped** zscore; merges independently.
- **Phase B (B1-B11)** builds BASKET incrementally: AST → parse → validate → register →
  composite → wire → compose with zscore → fan-out → position/close → e2e → docs. Each task
  is independently testable; the strategy only fully runs at B10.
